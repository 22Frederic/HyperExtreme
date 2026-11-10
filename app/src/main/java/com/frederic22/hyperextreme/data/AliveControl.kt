package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Alive 壁纸「关闭」的唯一收口（2026-11-07）。
 *
 * 三个入口必须行为完全一致：
 * 1. 二级页的「取消应用」；
 * 2. 「功能冲突提醒」弹窗（要先关掉 Alive 才能开别的）；
 * 3. **换壁纸 → 自动关闭**（aod 侧检测到壁纸判据变化，经 provider 调进来）。
 *
 * 与 [RasterControl] 同一套：清 `alive_active_id` → 清壁纸基准 → 推系统属性 → toast。
 *
 * ## 为什么要重启 miwallpaper（2026-11-09 补，照 [SpatialControl]）
 *
 * 我们的层是挂在**壁纸 leash**（`ImageWallpaper` 那条）下的子层，不是 SystemUI 自己的。
 * 只 `Transaction#remove` 掉 SurfaceControl 不够：壁纸层里仍会留着历史残留的请求层
 * （真机 dump 到 2~4 个 `he-alive-lock#xxxxx`，全屏、alpha 255、冻结着当时的画面），
 * 得让壁纸服务整体重建壁纸层才彻底复位。
 *
 * 延迟 [RESTART_DELAY_MS] 再杀：先让渲染器读到 `active=0` 自己停掉并把层拆干净，
 * 否则它还攥着即将被销毁的 leash。
 */
object AliveControl {

    /** 关闭后等渲染器停下来的时间（轮询周期 ~200ms，留一倍余量）。 */
    const val RESTART_DELAY_MS = 400L

    /**
     * 关闭 Alive 壁纸。**已经关着就什么都不做**（不重复 toast）。
     *
     * @param toastText 提示文案。手动关闭用默认的「Alive壁纸已关闭」；
     *   换壁纸自动关闭传「检测到壁纸变化，Alive壁纸已自动关闭」；传 **null 表示静默**。
     */
    fun disable(ctx: Context, toastText: String? = "Alive壁纸已关闭") {
        val app = ctx.applicationContext ?: ctx
        val prefs = MaskPrefs(app)
        if (prefs.aliveActiveId.isEmpty()) return
        // ⚠️ 状态必须同步写：provider 的 call 跑在 binder 线程，晚写会让 aod 侧重复触发关闭。
        prefs.setAliveActiveId(null)
        prefs.setAliveWallpaperFp("")
        AliveFlag.push(false, null, MaskPrefs.SPATIAL_SCOPE_GLOBAL)
        restartWallpaperService()
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

    /** 后台重启 miwallpaper（强制重建壁纸层，连带销毁我们残留的子层）。 */
    fun restartWallpaperService(delayMs: Long = RESTART_DELAY_MS) {
        Thread {
            runCatching {
                if (delayMs > 0L) Thread.sleep(delayMs)
                RootShell.run("killall com.miui.miwallpaper")
            }
        }.apply { isDaemon = true }.start()
    }
}
