package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 光栅壁纸「关闭」的唯一收口（2026-11-05）。
 *
 * ## 为什么抽出来
 *
 * 关闭动作现在有三个入口：
 * 1. 光栅壁纸二级页的「取消应用」；
 * 2. 「功能冲突提醒」弹窗（要先关掉光栅才能开别的）；
 * 3. **换壁纸 → 自动关闭**（aod 侧 hook 检测到壁纸判据变化，经 provider 调进来）。
 *
 * 三处必须行为完全一致：清 `raster_active_id` → 清壁纸基准 → 推系统属性 → toast。
 *
 * ## 与 [SpatialControl] 的差异
 *
 * **三条链挂的是同一个东西**：`WindowManagerGlobal#getWallpaperByShowWhenLocked(displayId,
 * showWhenLocked).getLeash()` —— 壁纸窗口那条 leash（锁屏 / 桌面各一条），
 * 我们的 SurfaceControl 都是它的子层。
 *
 * ⚠️ 别读成「光栅挂在 SystemUI 的窗口层上」：leash 对象由 SystemUI 的
 * `WindowManagerGlobal` 暴露，但那个 SurfaceControl 属于壁纸窗口（`miwallpaper` 的
 * `ImageWallpaper`），三条链的挂载代码逐字同构（见 `RasterRenderer.attachToLeash`）。
 *
 * 差别只在**关闭时的清理**：光栅层数少（锁屏 / 桌面各一份贴图 + 一条擦除带），
 * `RasterRenderer.stopInternal()` 的 `Transaction.remove` 能当场拆干净 —— 所以不需要杀进程
 * （也不该杀：SystemUI 重启的代价远大于空间那条链）。空间 / Alive 在真机 dump 到过残留子层
 * （`he-alive-lock#xxxxx`，全屏、alpha 255、冻结画面，见 [AliveControl]），才用
 * 「重启壁纸进程」做兜底。
 *
 * ## 服务模式
 *
 * 这里**只清状态与属性**，不碰系统壁纸。服务模式的手动关闭要额外走
 * `WallpaperManager.clear()`（见 `RasterWallpaperPage.clearActive`），那条路仍留在 UI 层；
 * 换壁纸自动关闭发生时系统已经把壁纸换掉了，再 clear 一次反而会清掉用户刚换的壁纸。
 */
object RasterControl {

    /**
     * 关闭光栅壁纸。**已经关着就什么都不做**（不重复 toast）。
     *
     * @param toastText 提示文案。手动关闭用默认的「光栅壁纸已关闭」；
     *   换壁纸自动关闭传「检测到壁纸变化，光栅壁纸已自动关闭」；
     *   传 **null 表示静默不弹**。
     *
     * 调用方负责同步自己的界面状态（页面里的 `activeId`）。
     */
    fun disable(ctx: Context, toastText: String? = "光栅壁纸已关闭") {
        val app = ctx.applicationContext ?: ctx
        val prefs = MaskPrefs(app)
        if (prefs.rasterActiveId.isEmpty()) return
        // ⚠️ 状态写入必须同步：provider 的 `call` 跑在 binder 线程，晚写几百毫秒的话
        // aod 侧下一次 config 读到的还是 active ⇒ 重复触发关闭（与空间同款坑）。
        prefs.setRasterActiveId(null)
        // 壁纸基准随关闭一起清 —— 下次开启时重新记。
        prefs.setRasterWallpaperFp("")
        // SystemUI 侧读的是系统属性，prefs 通道在那里不可用。
        RasterFlag.push(false, null, prefs.rasterScope)
        // Toast 需要 Looper：binder 线程直接弹会抛。
        if (toastText != null) {
            if (Looper.myLooper() == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, toastText, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(app, toastText, Toast.LENGTH_LONG).show()
            }
        }
    }
}
