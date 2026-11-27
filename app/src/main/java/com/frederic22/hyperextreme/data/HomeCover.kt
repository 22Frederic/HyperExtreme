package com.frederic22.hyperextreme.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.ui.WallpaperChain
import com.frederic22.hyperextreme.ui.WallpaperConflict
import com.frederic22.hyperextreme.ui.WallpaperMode
import com.frederic22.hyperextreme.wallpaper.RasterServiceTarget
import com.frederic22.hyperextreme.wallpaper.SpatialServiceTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主页背景的**进程级**取图与解码（2026-11-25 重写）。
 *
 * 取图规则没变（2026-11-25 用户定，**背景同步优先**）：
 *
 * | 优先级 | 来源 |
 * |---|---|
 * | 1 | 「背景同步应用状态」开着 ⇒ 正在应用那条链的卡片封面 |
 * | 2 | 「自定义背景」（`filesDir/home_bg/custom.jpg`） |
 * | 3 | 都没有 ⇒ 调用方回退 `R.drawable.home_sample` |
 *
 * ## 为什么要改成 store（而不是原来那两个 `produceState`）
 *
 * 旧实现里，「解析文件」和「解码位图」各是一个 `produceState`，挂在 `HomeTab` 的
 * `remember` 里 —— 而 `HorizontalPager` 的 `beyondViewportPageCount = 1` 会把超出窗口的页
 * **整棵销毁**（停在设置页时主页就在窗口外）。于是每次从设置 / 关于回主页：
 *
 *  1. 两个 `produceState` 都以 `null` 重新起步 ⇒ 首帧画 `home_sample`，解码完才换真图
 *     （用户报的「闪一下 home_sample」）；
 *  2. 重新 `BitmapFactory.decodeFile` 出一份 12~13MB 的 ARGB_8888，旧的再被 GC
 *     （用户报的「功能页 ↔ 设置页轻微掉帧」的一部分）；
 *  3. `ON_RESUME` 的 `coverEpoch++` 会把**正在跑的第一轮**取消、从头再跑一遍
 *     （冷启动本来只有 400ms 遮罩窗口，被这么一打断就来不及）。
 *
 * 现在改成：解析 + 解码的结果缓存在**进程**里，
 *
 *  * [image] 是 `mutableStateOf` —— Compose 订阅它，页重建时直接拿上一张，**没有 null 帧**；
 *  * 同一个文件（路径 + `lastModified` + `length` 都没变）**直接复用同一份像素**，
 *    不解码、不重新分配；
 *  * [request] 单飞（`running` / `dirty` 合并重复请求）：新一轮请求不会取消正在跑的那轮，
 *    跑完若又有人请求就再补一轮 —— 所以冷启动那次不会被打断。
 *
 * ⚠️ 所有解析 / 解码都在 IO 线程；[image] 只在主线程写。
 * ⚠️ 视觉上零变化：解码短边仍是 [SHORT_SIDE]，换图仍是「同一个 `Image` 节点换 painter」，
 *    不加淡入、不改采样。
 */
internal object HomeCover {

    /**
     * 解码短边（像素）。
     *
     * 2026-11-27 从 1200 提到 **2400**：主页支持「显示区域调整」之后，图不再被
     * `ContentScale.Crop` 裁成屏幕比例，而是**完整保留 + 手动 cover** —— 一张横图
     * 在竖屏上要横向溢出 3 倍，按 1200 解会糊成一片。
     * 2400 与 [HomeBg] 的存盘尺寸对齐（横图 3200×2400，铺满 2670 高约 1.11×）。
     *
     * 代价：整张 ARGB_8888 约 30MB，常驻一份（[pixel]）—— 换来的是一次解码之后
     * 页面怎么重建都不再分配。
     */
    const val SHORT_SIDE = 2400

    /**
     * 当前该显示的封面像素。`null` = 没有可用封面，调用方回退 `home_sample`。
     *
     * 页被销毁重建时它**保持不变**（进程级），所以重建后的首帧就是真图。
     */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 当前这张图**从哪来**（2026-11-27）。
     *
     * 主页的「显示区域」（`HomeBg.CROP_KEY` 那组 `cx/cy/zoom`）只属于**自定义背景**那张图；
     * 同步封面（正在应用那条链的封面）没有自己的显示区域，必须是「铺满居中」。
     * 以前不区分来源 ⇒ 只要调过自定义背景的显示区域，同步封面也会被按同一组 `cx/cy`
     * 平移（用户 2026-11-27 报的「错位，方向和幅度与调整页完全一致」）。
     */
    var imageSource by mutableStateOf(Source.NONE)
        private set

    /** [image] 的来源。 */
    enum class Source {
        /** 正在应用那条链的封面（「背景同步应用状态」开着且确实有链在跑）。 */
        SYNCED,

        /** 用户选的自定义背景（`home_bg/custom.jpg`）—— 只有它吃「显示区域」。 */
        CUSTOM,

        /** 都没有（调用方回退 `home_sample`）。 */
        NONE,
    }

    /** 最近一次解码结果。与 [pixelFile] / [pixelStamp] 一起构成"同一张图"的判据。 */
    private var pixelFile: File? = null
    private var pixelStamp: Long = 0L
    private var pixel: ImageBitmap? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在跑的那轮。 */
    private var running = false

    /** 跑的过程中又来请求（参数可能已变）⇒ 跑完再补一轮。 */
    private var dirty = false

    /** 最近一次请求的「背景同步应用状态」。 */
    private var wantSync = true

    /**
     * 请求刷新（可从任意线程调，内部保证只在 IO 上做重活）。
     *
     * @param followApp 「背景同步应用状态」开关的当前值（`MaskUiState.homeBgFollowApp`）。
     *   关着时只看「自定义背景」。**同一个值重复调用是无害的**：命中缓存直接返回。
     */
    fun request(ctx: Context, followApp: Boolean) {
        val app = ctx.applicationContext
        wantSync = followApp
        if (running) {
            dirty = true
            return
        }
        running = true
        scope.launch {
            try {
                do {
                    dirty = false
                    val follow = wantSync
                    val (file, source) = withContext(Dispatchers.IO) { resolve(app, follow) }
                    val bitmap = withContext(Dispatchers.IO) { imageFor(file) }
                    image = bitmap
                    // 与 image 同步写：主页据此决定要不要套用自定义背景的「显示区域」。
                    imageSource = if (bitmap == null) Source.NONE else source
                } while (dirty)
            } finally {
                running = false
            }
        }
    }

    /**
     * 取图：同步封面优先，其次自定义背景；都没有返回 `(null, NONE)`。
     *
     * 返回值里带上**来源** —— 主页要靠它判断「当前这张图是否吃自定义背景的显示区域」
     *（只有 [Source.CUSTOM] 才吃，见 [imageSource]）。
     */
    private fun resolve(ctx: Context, followApp: Boolean): Pair<File?, Source> {
        val synced = if (followApp) runningCover(ctx) else null
        if (synced != null) return synced to Source.SYNCED
        val custom = HomeBg.customFile(ctx).takeIf { it.isFile }
        return custom to if (custom != null) Source.CUSTOM else Source.NONE
    }

    /**
     * 正在应用那条链的卡片封面（原 `current` 的取图逻辑，一行未改）。
     *
     * | 链 | 应用中的 id | 封面 |
     * |---|---|---|
     * | 空间 | `SERVICE` → [SpatialServiceTarget.readKey]；`HOOK` → [MaskPrefs.spatialKey] | `SpatialCandidate.cover` |
     * | 光栅 | `SERVICE` → [RasterServiceTarget.readKey]；`HOOK` → [MaskPrefs.rasterActiveId] | `RasterGroup.coverFile` |
     * | Alive | 只有 HOOK → [MaskPrefs.aliveActiveId] | `AliveItem.coverFile` |
     * | 视差 | —— | 不参与（用户 2026-11-25 明确「视差不管了」） |
     *
     * ⚠️ **必须按 mode 分支读 id**：空间 / 光栅在 SERVICE 模式下**不写 prefs**
     * （见 `RasterWallpaperPage.applyGroupNow` 的注释「真值在壁纸进程」），
     * 只认 `*ServiceTarget.readKey()`；照 `prefs` 读会永远拿到空。
     *
     * 只在 IO 线程调用（服务模式下会走 `WallpaperManager` 的跨进程查询）。
     */
    private fun runningCover(ctx: Context): File? {
        val prefs = MaskPrefs(ctx)
        // 与主页「当前应用」卡、冲突弹窗共用同一份判据：三处对「在不在跑」永远一个答案。
        // 这里从 prefs 取视差状态，不读 MaskUiState（本函数跑在 IO 线程）。
        val running = WallpaperConflict.current(ctx, prefs.parallaxBgActive) ?: return null
        return runCatching {
            when (running.chain) {
                WallpaperChain.PARALLAX -> null

                WallpaperChain.SPATIAL -> {
                    val key = if (running.mode == WallpaperMode.SERVICE) {
                        SpatialServiceTarget.readKey(ctx).orEmpty()
                    } else {
                        prefs.spatialKey
                    }
                    if (key.isEmpty()) return@runCatching null
                    SpatialDepth.listCandidates(ctx).firstOrNull { it.key == key }?.cover
                }

                WallpaperChain.RASTER -> {
                    val id = if (running.mode == WallpaperMode.SERVICE) {
                        RasterServiceTarget.readKey(ctx).orEmpty()
                    } else {
                        prefs.rasterActiveId
                    }
                    if (id.isEmpty()) return@runCatching null
                    RasterStore.list(ctx).firstOrNull { it.id == id }?.coverFile
                }

                WallpaperChain.ALIVE -> {
                    val id = prefs.aliveActiveId
                    if (id.isEmpty()) return@runCatching null
                    AliveStore.list(ctx).firstOrNull { it.id == id }?.coverFile
                }
            }
        }.getOrNull()
    }

    /**
     * 文件 → 像素。同一张图（路径 + mtime + size）直接复用缓存，**不解码、不分配**。
     *
     * 只在 IO 线程调用（由 [request] 的同一个协程串行执行 ⇒ [pixel] 无需加锁）。
     * 解码失败返回 null（调用方回退 `home_sample`），旧缓存保留。
     */
    private fun imageFor(file: File?): ImageBitmap? {
        if (file == null) return null
        val stamp = file.lastModified() * 31 + file.length()
        val cached = pixel
        if (cached != null && pixelFile == file && pixelStamp == stamp) return cached

        val decoded = SpatialDepth.decodePreview(file, SHORT_SIDE) ?: return null
        val bitmap = decoded.asImageBitmap()
        pixelFile = file
        pixelStamp = stamp
        pixel = bitmap
        return bitmap
    }
}
