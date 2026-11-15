package com.frederic22.hyperextreme.hook

import android.os.SystemClock
import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.MaskProvider
import com.frederic22.hyperextreme.data.RasterFlag
import com.frederic22.hyperextreme.data.SpatialFlag

/**
 * 壁纸进程（`com.miui.miwallpaper`）**重启**的发现者（2026-11-15，跑在 SystemUI 进程）。
 *
 * ## 为什么需要
 *
 * 我们的三层都是**壁纸 leash 的子层**。壁纸进程一重启，父层连同我们的子层一起被销毁，
 * 而空间 / 光栅的渲染器**没有** leash 自检（只有 Alive 有 `checkLeashFresh`）⇒
 * 属性仍是 `active=1`、渲染器还在跑，屏幕上却什么都没有，也不会自愈。
 *
 * 真机日志（2026-11-15）：
 * ```
 * 13:37:04.577  SPATIAL 挂载：scope=2 showWhenLocked=true leash=@4a045ec
 * 13:37:18.528  (com.miui.miwallpaper) 模块加载        ← 进程重启，leash 被销毁
 *               （此后只有「景深首帧已绘制」，再没有「挂载」）
 * ```
 *
 * ## 信号：进程 pid
 *
 * `HookEntry.onPackageReady` 的 `WALLPAPER_PACKAGE` 分支在**每次进程启动**时写
 * `debug.hyperextreme.wp_pid` / `wp_at`（[PROP_PID] / [PROP_AT]）。
 * 这里只比对 pid：变了 = 进程换了 = 我们的层全成孤儿。
 *
 * 为什么不用 `pidof`：SystemUI 侧 fork `su` 代价太高；属性是共享内存读，微秒级。
 * 为什么不用 leash 比对：`getWallpaperByShowWhenLocked` 是 binder 调用，更重，
 * 且只能说明"层变了"，说明不了"进程换了"（换壁纸也会重建层，那条另有指纹判据）。
 *
 * ## 什么时候才判断
 *
 * **只有某条链生效时才看**（三条 `*_active` 属性任一为 1）。空闲期只更新基线 ——
 * 用户开机、换壁纸、手动重启壁纸进程都不该弹提示。
 *
 * ## 为什么放在 SystemUI 而不是 App
 *
 * App 进程可能早被系统回收，而"壁纸被别的模块杀掉"随时可能发生。这里发现后经 provider
 * （[MaskProvider.METHOD_WALLPAPER_RESTART]）把 App 拉起来做收尾 —— 改 prefs + 弹 toast
 * 只能在 App 进程完成。
 */
internal object WallpaperProcWatch {

    /** miwallpaper 进程 pid（每次进程启动由 [HookEntry.markWallpaperProcess] 写入）。 */
    const val PROP_PID = "debug.hyperextreme.wp_pid"

    /** miwallpaper 进程启动时刻（`elapsedRealtime`），诊断用。 */
    const val PROP_AT = "debug.hyperextreme.wp_at"

    private const val TAG = "WALLPAPER_PROC"
    private const val TICK_MS = 500L
    private const val WARN_INTERVAL_MS = 60_000L

    @Volatile private var started = false

    /** 幂等：SystemUI 可能因 fallback 重装 hook 而进两次。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread {
            // 上一次看到的 pid（0 = 还没有基线）
            var baseline = 0
            var warnedAt = 0L
            HookRuntime.log("$TAG 监视已启动（pid 变化 → 自动关闭当前在用的那条链）")
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                    val pid = HookRuntime.systemProperty(PROP_PID).trim().toIntOrNull() ?: 0
                    val active = activeChains()
                    // 空闲期不做判断，只把基线拉到最新 —— 否则"开机/换壁纸/手动重启"都会误报
                    if (active.isEmpty()) {
                        baseline = pid
                        continue
                    }
                    if (pid == 0) {
                        // 模块没被注入 miwallpaper（没勾作用域 / LSPosed 停用）⇒ 这条信号不可用。
                        // 只打诊断日志，不猜、不误关。
                        val now = SystemClock.elapsedRealtime()
                        if (now - warnedAt > WARN_INTERVAL_MS) {
                            warnedAt = now
                            HookRuntime.log(
                                "$TAG 读不到 $PROP_PID（miwallpaper 未注入？）" +
                                    "⇒ 本次重启无法感知；生效中=${active.joinToString()}",
                            )
                        }
                        baseline = 0
                        continue
                    }
                    if (baseline == 0) {
                        baseline = pid
                        HookRuntime.log(
                            "$TAG 基线 pid=$pid（生效中=${active.joinToString()}）",
                        )
                        continue
                    }
                    if (pid != baseline) {
                        val old = baseline
                        baseline = pid
                        HookRuntime.log(
                            "$TAG 壁纸进程已重启：pid $old → $pid" +
                                "（生效中=${active.joinToString()}）→ 通知自动关闭",
                        )
                        notifyRestart(pid)
                    }
                } catch (t: Throwable) {
                    HookRuntime.log("$TAG 异常：${t.javaClass.simpleName}: ${t.message}")
                    runCatching { Thread.sleep(1000L) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 生效中的链（中文名，只用于日志）。三条互斥，正常最多一条。 */
    private fun activeChains(): List<String> = buildList {
        if (HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE).trim() == "1") add("空间")
        if (HookRuntime.systemProperty(RasterFlag.PROP_ACTIVE).trim() == "1") add("光栅")
        if (HookRuntime.systemProperty(AliveFlag.PROP_ACTIVE).trim() == "1") add("Alive")
    }

    private fun notifyRestart(pid: Int) {
        val ctx = HookRuntime.appContext()
        if (ctx == null) {
            HookRuntime.log("$TAG 拿不到宿主 Context，本次通知跳过")
            return
        }
        runCatching {
            ctx.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_WALLPAPER_RESTART,
                pid.toString(),
                null,
            )
        }.onFailure {
            HookRuntime.log("$TAG 通知 App 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
