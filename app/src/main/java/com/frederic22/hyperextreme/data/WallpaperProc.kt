package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 壁纸进程（`com.miui.miwallpaper`）的**运行状态**：能不能现在注入（2026-11-15）。
 *
 * ## 为什么需要
 *
 * 空间 / Alive 的「关闭」会**延迟 400ms 杀一次壁纸进程**（[SpatialControl.restartWallpaperService]
 * / [AliveControl.restartWallpaperService]），目的是让壁纸层整体重建、清掉我们残留的子层。
 * 问题在于这 400ms 是个"盲区"：
 *
 * * 用户点完「取消应用」**马上又点「应用」**（或在冲突弹窗里「关闭并应用」）⇒ 我们的层刚
 *   `reparent` 到壁纸 leash，400ms 后那一刀落下，父层连同我们的子层一起被销毁；
 * * 渲染器本身不知道这件事（空间 / 光栅**没有** leash 自检），属性还是 `active=1`、
 *   进程还在跑 ⇒ 屏幕上什么都没有，要等别的状态变化才恢复。
 *
 * 所以"应用"之前必须先确认壁纸进程处于**可注入**状态：没有待执行的那一刀、刚杀完也过了沉降期、
 * 进程真的在。
 *
 * ## 三个状态量
 *
 * | 字段 | 含义 | 谁写 |
 * |---|---|---|
 * | [pendingKillAt] | 我们排下的那一刀**将要**执行的时刻（`elapsedRealtime`） | [scheduleKill] |
 * | [firedAt] | 上一次 kill **真正执行完**的时刻 | [scheduleKill] / [noteKill] |
 * | [pidOf] | 现在 miwallpaper 的 pid（一次 `su pidof`，只在真等待时调） | —— |
 *
 * ## 与 hook 侧的分工
 *
 * 这里只解决"**应用**要不要等"。**进程重启 → 自动关闭**是另一条链：`miwallpaper` 进程在
 * `onPackageReady` 里写 `debug.hyperextreme.wp_pid`（见 `HookEntry.markWallpaperProcess`），
 * SystemUI 侧的 `WallpaperProcWatch` 发现 pid 变了就经 provider 通知 App 收尾
 * （[MaskProvider.METHOD_WALLPAPER_RESTART]）—— 那条链在 App 没运行时也有效，这里不重复。
 */
object WallpaperProc {

    const val PACKAGE = MiuiWallpaperPackageName

    /**
     * kill 落刀之后到"可以重新注入"的沉降时间。
     *
     * 真机实测（2026-11-15 日志）：`disable` → `killall` → 新进程 `onPackageReady` 约 350ms。
     * 再留 ~550ms 给壁纸服务 bind + 首个 leash 建立，合计 900ms。宁可多等半秒，
     * 也别让层挂到一个马上被销毁的 leash 上。
     */
    const val SETTLE_MS = 900L

    private const val TAG = "HyperExtreme"

    /** 我们排下的那一刀的执行时刻；0 = 没有排。 */
    @Volatile private var pendingKillAt = 0L

    /** 上一次 kill 真正执行完的时刻；0 = 本次进程生命周期内没杀过。 */
    @Volatile private var firedAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 登记一次「别处已经杀过壁纸进程」（[AppRestarter] 的「重启壁纸」按钮走这条路）。
     *
     * 它不杀进程，只让 [readyNow] / [awaitReady] 知道"刚重启过，先别注入"。
     */
    fun noteKill() {
        firedAt = SystemClock.elapsedRealtime()
        pendingKillAt = 0L
    }

    /** 排一刀 killall（[delayMs] 后执行），并把"将要落刀"的时刻记下来供应用侧让路。 */
    fun scheduleKill(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        pendingKillAt = at
        Log.i(TAG, "WALLPAPER_PROC 排定重启壁纸进程：${delayMs}ms 后 killall $PACKAGE")
        Thread {
            try {
                val wait = at - SystemClock.elapsedRealtime()
                if (wait > 0L) Thread.sleep(wait)
                val r = RootShell.run("killall $PACKAGE")
                Log.i(TAG, "WALLPAPER_PROC 已 killall $PACKAGE（rc=${r.exitCode}）")
            } catch (t: Throwable) {
                Log.w(TAG, "WALLPAPER_PROC killall 失败：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                firedAt = SystemClock.elapsedRealtime()
                if (pendingKillAt == at) pendingKillAt = 0L
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * **同步**判断：现在能不能直接注入。
     *
     * 只做时间戳判断（不 fork `su`，可以在主线程调）。判据两条：
     *
     * * `pendingKillAt > 0` ⇒ 有一刀排着（**直到 `killall` 真正跑完才清**）⇒ 不能注。
     *   这一条同时堵住了"刀已到点、线程还没跑完 su"那几十毫秒的缝。
     * * 刚杀完（`firedAt + SETTLE_MS` 内）⇒ 不能注。
     *
     * 真正的"进程在不在"由 [awaitReady] 在 IO 线程确认 —— 快路（没排刀、没刚杀完）直接放行，
     * 不额外付一次 su。
     */
    fun readyNow(): Boolean {
        if (pendingKillAt > 0L) return false
        val fired = firedAt
        if (fired > 0L && SystemClock.elapsedRealtime() - fired < SETTLE_MS) return false
        return true
    }

    /** 现在是否处于"重载中 / 刚重载完"的窗口（界面提示用）。 */
    fun reloading(): Boolean = !readyNow()

    /**
     * 包一层「应用」：**能直接注就同步注**（点按零延迟）；要等就提示后等，等完再注。
     *
     * [onReady] 一定在主线程执行（Compose 的状态只能在主线程写）。
     * 等待放在 App 级 [scope] 上，**不受页面销毁影响** —— 用户点完应用立刻退出页面也能注进去。
     */
    fun applyWhenReady(ctx: Context, onReady: () -> Unit) {
        if (readyNow()) {
            onReady()
            return
        }
        val app = ctx.applicationContext ?: ctx
        toast(app, "壁纸正在重载，完成后自动应用")
        Log.i(TAG, "WALLPAPER_PROC 应用受阻：等待壁纸进程重载完成")
        scope.launch {
            val ok = awaitReady()
            Handler(Looper.getMainLooper()).post {
                if (ok) {
                    Log.i(TAG, "WALLPAPER_PROC 重载完成 → 继续注入")
                    onReady()
                } else {
                    Log.w(TAG, "WALLPAPER_PROC 等待重载超时，放弃本次注入")
                    toast(app, "壁纸重载超时，请稍后重试")
                }
            }
        }
    }

    /**
     * 等到可以注入。**suspend**，跑在 IO 线程。
     *
     * 三步：等我们排的刀落地 → 等沉降期 → 确认进程真的在（`pidof`，最多 8 次 / 3.2s）。
     */
    suspend fun awaitReady(timeoutMs: Long = 12_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // ① 等那一刀真正跑完（`RESTART_DELAY_MS` = 400ms 的等待 + 一次 `su killall`）。
        //    `pendingKillAt` 由 `finally` 清，所以"清掉"就等于"刀已落地、`firedAt` 已写好"
        //    （两处都是 volatile，写序保证读到的 `firedAt` 一定不是旧的）。
        while (pendingKillAt > 0L) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(50L)
        }
        // ② 刚落刀，等沉降
        val fired = firedAt
        if (fired > 0L) {
            val left = SETTLE_MS - (SystemClock.elapsedRealtime() - fired)
            if (left > 0L) delay(left)
        }
        // ③ 进程真的起来了才注
        var tries = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pidOf() != null) return true
            if (++tries >= 8) return false
            delay(400L)
        }
        return false
    }

    /** 现在 miwallpaper 的 pid；不在返回 null。**会 fork 一次 `su`**，别在轮询里高频调。 */
    fun pidOf(): Int? = runCatching {
        RootShell.run("pidof $PACKAGE", timeoutSeconds = 8L).output
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.toIntOrNull()
    }.getOrNull()

    private fun toast(ctx: Context, text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
