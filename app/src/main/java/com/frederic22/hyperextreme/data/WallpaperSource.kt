package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 「当前壁纸」的定位。关键是把**两个坐标空间**分清楚，否则锚点和裁切都会错。
 *
 * | 空间 | 是哪一份 | 尺寸 | 谁在用 |
 * | --- | --- | --- | --- |
 * | **原图空间** | `templates/current/wallpaper`（编辑器拿它当裁剪画布的那张原图） | 原图像素 | `subject` / `subject_mask` / `WallpaperPositionInfo` 的四个边 |
 * | **显示空间** | 按裁切矩形裁过、贴到屏幕上的那份 | = 裁切矩形 | `crop_subject` / `cropSource` |
 *
 * 证据（反编译 `com.miui.aod`）：
 *
 * * `GestureManager.getPosition(bitmap)` 先要求 `bitmap.width == srcDrawAble.getIntrinsicWidth()`
 *   （即传进来的就是原图），然后 `left = -mappedRect.left / scaleY`、`right - left = screenWidth / scaleY`
 *   —— 返回的是**源图像素**里的可见窗口，也就是原图坐标；
 * * `TemplateApplier` 把 `lockOriginWallpaper` 交给 `applyEditPageTemplate`，后者用
 *   `cropBitmap(subject, positionInfo, false)` 生成 `crop_subject`；
 * * `<filesDir>/templates/current/wallpaper` ← `wallpaperInfo.source` 的拷贝（原图）；
 *   `wallpaper_cropped` ← `copyCroppedWallpaper(裁过的 bitmap)`（显示空间）；`wallpaper_scaled.jpg` 同理。
 *
 * 所以锚点**只能是原图空间那一份**。拿显示空间的那份去比（或去和遮罩做「壁纸 ∩ 遮罩」合成），
 * 遮罩和它就不在同一个坐标系里，`crop_subject` 跟着一起错 —— 用户实测踩到的就是这个：
 * 被当成锚点的是显示空间那份，而它的尺寸会随壁纸缩放/移动变化（`screenWidth / scale`）。
 */
object WallpaperSource {

    /**
     * 原图空间的文件名候选，按优先级排。
     *
     * `wallpaper` 就是编辑器当前在用的原图（用户确认过：原图 = `current/wallpaper`），
     * 必须排第一；`wallpaper_copied` 是 `originResourcePath` 的逐字节拷贝，同一张原图，
     * 只在 `wallpaper` 被清掉时顶上。
     */
    val sourceTemplateNames = listOf("wallpaper", "wallpaper_copied")

    /**
     * 显示空间的文件名 —— **不能**当对齐锚点，也不要拿去和遮罩合成。
     *
     * `wallpaper_cropped` / `wallpaper_scaled.jpg` 的尺寸 = 原图里那块可见窗口
     * （`screenWidth / scale`），壁纸一缩放/移动它就变，所以拿它当锚点必然错。
     */
    val displayTemplateNames = listOf("wallpaper_cropped", "wallpaper_scaled.jpg", "wallpaper_home")

    /**
     * 显示空间的绝对路径候选（已应用的壁纸）。
     *
     * ⚠️ 真实路径带 `image/` 段（`MiuiWallpaperPathUtils`：`theme_magic/users/<uid>/wallpaper/image/lock_wallpaper.jpg`），
     * 旧代码漏了这一段，等于这几个候选一直是无效的。
     */
    fun displayPaths(userId: Int): List<String> = listOf(
        "/data/system/theme_magic/users/$userId/wallpaper/image/lock_wallpaper.jpg",
        "/data/system/theme_magic/users/$userId/wallpaper/image/lock_wallpaper.png",
        "/data/system/theme_magic/users/$userId/wallpaper/image/home_wallpaper.jpg",
        "/data/system/users/$userId/wallpaper_lock",
        "/data/system/users/$userId/wallpaper_lock_orig",
        "/data/system/users/$userId/wallpaper",
    )

    /** 小于这个的多半是缩略图 / 配置文件，不是壁纸。 */
    private const val MIN_BYTES = 50_000L

    fun currentUserId(): Int = Process.myUid() / 100_000

    /** 一个候选：文件 + 日志用的标签 + 它属于哪个空间。 */
    data class Candidate(val file: File, val tag: String, val sourceSpace: Boolean) {
        val path: String get() = file.absolutePath
    }

    private fun usable(file: File): Boolean = runCatching {
        file.isFile && file.length() >= MIN_BYTES
    }.getOrDefault(false)

    /** 模板配置里记着的原图路径（`source` / `originResourcePath` / `imagePath`）。 */
    fun configPaths(context: Context): List<String> =
        WallpaperVersion.originalPathsOf(WallpaperVersion.readTemplateInfo(context))

    /** 模板目录。hook 进程（com.miui.aod）读得到自己那份；模块 app 进程这里通常是空的。 */
    private fun templateRoots(context: Context): List<File> =
        listOf(File(context.filesDir, "templates/current"))

    /**
     * 原图空间的候选，按优先级：
     * `templates/current/wallpaper` → `wallpaper_copied` → 配置里的原图路径。
     *
     * 配置路径放最后是因为它可能是**原始文件**（分辨率可能和编辑器实际用的那份不同，编辑器可能缩过），
     * 只在模板目录里没有原图时才用它。
     */
    fun sourceCandidates(
        context: Context,
        configPaths: List<String> = configPaths(context),
    ): List<Candidate> = buildList {
        templateRoots(context).forEach { dir ->
            sourceTemplateNames.forEach { name -> add(Candidate(File(dir, name), "模板/$name", true)) }
        }
        configPaths.forEach { path -> add(Candidate(File(path), "配置原图", true)) }
    }

    /** 显示空间的候选：模板目录里的 crop/scaled，以及已应用的那几份。 */
    fun displayCandidates(context: Context, userId: Int = currentUserId()): List<Candidate> = buildList {
        templateRoots(context).forEach { dir ->
            displayTemplateNames.forEach { name -> add(Candidate(File(dir, name), "模板/$name", false)) }
        }
        displayPaths(userId).forEach { add(Candidate(File(it), "已应用", false)) }
    }

    /** 原图空间那份（**只有它**能当对齐锚点）；找不到返回 null。 */
    fun resolveSourceSpace(context: Context, configPaths: List<String> = configPaths(context)): File? =
        sourceCandidates(context, configPaths).firstOrNull { usable(it.file) }?.file

    /**
     * 所有读得到的候选（原图空间优先），带空间标记。
     *
     * 只给"必须有张图才能干活"的场景用（合成主体）。调用方**必须**看 [Candidate.sourceSpace]：
     * 显示空间那份和遮罩不在同一坐标系，拿它合成会错位。
     */
    fun resolveAny(context: Context, configPaths: List<String> = configPaths(context)): Candidate? =
        (sourceCandidates(context, configPaths) + displayCandidates(context)).firstOrNull { usable(it.file) }

    /**
     * 已应用的锁屏壁纸文件 —— 一级「我的搭配」当前卡片的贴图基准。
     *
     * 卡片把这张壁纸和 `crop_subject` 当一对叠着画，位置由按**壁纸贴图**算出的 GL 矩阵给出，
     * 所以 `crop_subject` 的像素尺寸要跟它一致（见 [MaskComposer.fitTo]）。
     */
    fun appliedLockWallpaper(context: Context, userId: Int = currentUserId()): File? =
        displayPaths(userId)
            .filter { it.endsWith("lock_wallpaper.jpg") }
            .map(::File)
            .firstOrNull { usable(it) }

    /**
     * 候选能否装下裁切矩形。
     *
     * 判据来自 `GestureManager.getPosition()`：矩形是**原图坐标**里的可见窗口，所以原图必然满足
     * `right ≤ W` 且 `bottom ≤ H`；显示空间那份是窗口本身（或更小），必然装不下 —— 这条正好能把
     * 两者分开。容 2px 取整误差。
     */
    fun fits(size: Pair<Int, Int>, position: WallpaperPosition, tolerance: Int = 2): Boolean =
        size.first + tolerance >= position.right.toDouble().let { Math.ceil(it).toInt() } &&
            size.second + tolerance >= position.bottom.toDouble().let { Math.ceil(it).toInt() }
}
