package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 空间壁纸的 **AOD（息屏显示）适配**（2026-10-30，v1.5.76）。
 *
 * ## 系统在 AOD 上做了什么（反编译实证）
 *
 * `KeyguardPanelViewController#linkageViewAnim(show, …)` 是锁屏 ↔ AOD 的唯一入口，
 * 它按 `miuiFullAodManager.fullAodEnable()` 分两条路：
 *
 * | 模式 | 壁纸 | 我们的层 |
 * |---|---|---|
 * | 与锁屏一致（全屏 AOD） | leash `setMatrix(1.0)`（锁屏是 1.05 ⇒ 缩 5%）＋ `setWallPaperAnimProcess(0.5)` | 缩放**跟**（子层继承），压暗**不跟** |
 * | 自定义 AOD | 不缩放，`setWallPaperAnimProcess(1.0)` | 全黑**不跟** |
 *
 * 压暗走的是 MIUI 私有的壁纸专有通道（`SurfaceControl.Transaction#setWallPaperAnimProcess` /
 * `ViewRootImpl#setWallpaperBlack`），只作用于 miwallpaper 自己的 Surface，**不辐射 leash 的
 * 兄弟子层** —— 这就是真机实测「我们的视图完全不参与全黑 / 参与了但不够暗」的原因。
 *
 * ## 为什么挂 `doWallpaperBlackAnim`
 *
 * 它是两种模式**都会经过**的那个方法（L1602 全屏分支、L1712 自定义分支各一次），
 * 而且第二个参数 `black` 就是系统的目标压暗值：
 *
 * ```
 * 全屏 AOD  : getDefaultWallpaperBlack() → ro.miui.special.aod.mask（未设）⇒ 0.5
 * 自定义 AOD: 硬编码 1.0f
 * ```
 *
 * ⇒ 直接拿参数用，**不写死常量**，换机 / 改属性都不会偏。
 *
 * ## 与其它链的关系
 *
 * 与解锁淡出（[SpatialUnlockFade]）互斥：AOD 是 `mMiKeyguardFadingAway` 全程 false 的
 * 另一条路，不会同时触发。本链只碰 [SpatialRenderer] 的压暗/冻结与 [SpatialOverlay]
 * 的缩放，不读不写任何 parallax / 锁屏遮罩字段。
 */
internal object SpatialAod {

    private const val CLS_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val METHOD_BLACK = "doWallpaperBlackAnim"

    /** 同一条日志只打一次（方法每次进/出 AOD 都会调，但没必要每次都刷）。 */
    private val logged = ConcurrentHashMap<String, Boolean>()

    /**
     * **诊断日志**（2026-11-07，排查「AOD 自定义模式 → 锁屏压暗不恢复」）。
     *
     * `doWallpaperBlackAnim` 在 AOD 期间会被系统反复调（真机实测 0.1~1.8s 一次），
     * 全打会刷爆日志；但 `show` 变化那一刻必须留痕，否则无法判断某次退出到底
     * 收没收 `show=true`。⇒ **状态跳变必打，同状态节流 2s 一条**。
     * 本轮只加日志，不改任何压暗 / 冻结逻辑。
     */
    @Volatile private var callLogAt = 0L
    @Volatile private var callLogShow = -1 // -1 未初始化 / 0 = false / 1 = true
    private const val CALL_LOG_MS = 2000L

    /** 与 [SpatialForegroundHider] / [SpatialUnlockFade] 同一判据 + 300ms 缓存。 */
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheValue = false
    private const val CACHE_MS = 300L

    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cacheAt < CACHE_MS) return cacheValue
        val active = HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE).trim() == "1"
        val scope = HookRuntime.systemProperty(SpatialFlag.PROP_SCOPE).trim().toIntOrNull()
            ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        // 桌面档没有锁屏份，AOD 不涉及我们
        cacheValue = active && scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        cacheAt = now
        return cacheValue
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_PANEL, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_PANEL")
            return false
        }
        val m = cls.declaredMethods.firstOrNull { it.name == METHOD_BLACK } ?: run {
            log("未找到 $METHOD_BLACK")
            return false
        }
        return runCatching {
            m.isAccessible = true
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val args = chain.getArgs()
                    // 参数：(boolean show, float black, int type, boolean anim, AnimationTracker)
                    // ⚠️ 不假定下标：getArgs() 可能带接收者，按类型/位置兜底取。
                    val show = args.getOrNull(0) as? Boolean
                        ?: args.filterIsInstance<Boolean>().firstOrNull()
                    val black = args.filterIsInstance<Float>().firstOrNull()
                    // ⚠️ **Alive 在跑时把这次压黑整个拦掉**（2026-11-07）。
                    //
                    // HyperOS 的自定义 AOD 会给 `black = 1.0`，系统随后把壁纸层压到全黑；
                    // 我们的 SC 挂在壁纸 leash 下，会被一起压暗 ⇒ AOD 里只剩黑屏，
                    // 「启航」的小球根本看不见（真机日志 black=1.00 / dim=0.00 已证实）。
                    //
                    // 只拦 Alive 这一条：四条壁纸链互斥，此刻空间 / 光栅不可能在跑；
                    // 而 Alive 需要 AOD 持续自转，本来就不能让系统把壁纸压没。
                    if (show == false && AliveRenderer.isRunning()) {
                        runCatching { AliveRenderer.onEnterAod(black ?: 1f) }
                        log(
                            "Alive 在跑 ⇒ 拦下 doWallpaperBlackAnim（不压黑，" +
                                "black=${"%.2f".format(black ?: 1f)}）",
                        )
                        return null
                    }
                    val result = chain.proceed()
                    if (show == null || black == null) return result
                    // 诊断：每次调用的 show/black 都要能复盘（状态跳变必打，同状态 2s 一条）。
                    val now = android.os.SystemClock.elapsedRealtime()
                    val s = if (show) 1 else 0
                    if (s != callLogShow || now - callLogAt >= CALL_LOG_MS) {
                        callLogShow = s
                        callLogAt = now
                        log("call show=$show black=${"%.2f".format(black)} @$now")
                    }
                    // **光栅壁纸也要这套时机**（2026-11-04）：它不做静止归位，只在这里重设基点。
                    // 与空间壁纸的开关无关，各自判各自在不在跑（没跑就是空操作）。
                    runCatching {
                        if (show) RasterRenderer.onExitAod() else RasterRenderer.onEnterAod(black)
                    }
                    // Alive 壁纸也吃同一个时机（没在跑就是空操作）。
                    runCatching {
                        if (show) AliveRenderer.onExitAod() else AliveRenderer.onEnterAod(black)
                    }
                    if (!enabled()) return result
                    runCatching {
                        if (show) {
                            logOnce("exit", "退出 AOD（回锁屏）→ 亮度复位 + 解冻")
                            SpatialRenderer.onExitAod()
                            SpatialOverlay.onExitAod()
                        } else {
                            logOnce(
                                "enter",
                                "进入 AOD（black=${"%.2f".format(black)}，" +
                                    "压暗到 ${"%.2f".format(1f - black.coerceIn(0f, 1f))}）" +
                                    "→ 先归位再冻结",
                            )
                            SpatialRenderer.onEnterAod(black)
                            SpatialOverlay.onEnterAod(black)
                        }
                    }.onFailure { log("AOD 处理异常：${it.javaClass.simpleName}: ${it.message}") }
                    return result
                }
            })
            log("已挂上（$METHOD_BLACK：AOD 进/出 → 压暗 + 主体淡出（不缩放））")
            true
        }.getOrElse {
            log("挂 $METHOD_BLACK 失败：$it")
            false
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (logged.putIfAbsent(key, true) == null) log(msg)
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL AOD $msg")
}
