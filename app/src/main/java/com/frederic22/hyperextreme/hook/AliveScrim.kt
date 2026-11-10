package com.frederic22.hyperextreme.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * **Alive 的三态权威来源**（2026-11-08，参考 `alive分析/fork` 的 `AliveHooks`）。
 *
 * 挂 `ScrimController#updateScrims`，读它的 `mState`（`ScrimState` 枚举）：
 *
 * | mState | 含义 | Alive 的态 |
 * |---|---|---|
 * | `AOD` / `PULSING` | 息屏显示 | 0 |
 * | `KEYGUARD` | 锁屏 | 1 |
 * | `UNLOCKED` | 已解锁（桌面） | 2 |
 *
 * ## 为什么不用 `KeyguardManager.isKeyguardLocked`
 *
 * 真机日志（2026-11-08）证明：`doWallpaperBlackAnim(show=true)` 触发那一刻 keyguard
 * **仍然是 locked**（解锁动画还没开始）⇒ 靠它判断「回锁屏」还是「直接进桌面」永远
 * 得到「回锁屏」，AOD→桌面的 tr=2 球扩一次都跑不到。
 * `ScrimState` 是状态机的**输出**，AOD 退出后一定会走到 `KEYGUARD` 或 `UNLOCKED`，
 * 边界清楚。
 *
 * 只推状态、不接管任何系统行为；`mState` 读不到就什么都不做（不影响原逻辑）。
 */
internal object AliveScrim {

    private const val CLS = "com.android.systemui.statusbar.phone.ScrimController"
    private const val METHOD = "updateScrims"
    private val FIELDS = arrayOf("mState", "mScrimState")

    private var last = ""

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS, false, loader) }.getOrNull()
        if (cls == null) {
            HookRuntime.log("ALIVE 未找到 $CLS")
            return false
        }
        val methods = cls.declaredMethods.filter { it.name == METHOD }
        if (methods.isEmpty()) {
            HookRuntime.log("ALIVE 未找到 $METHOD")
            return false
        }
        var any = false
        for (m in methods) {
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        val st = readState(chain.getThisObject()) ?: return r
                        if (st != last) {
                            last = st
                            runCatching { AliveRenderer.onScrimState(st) }
                        }
                        return r
                    }
                })
                any = true
            }.onFailure { HookRuntime.log("ALIVE 挂 $METHOD 失败：${it.message}") }
        }
        if (any) HookRuntime.log("ALIVE scrim 状态 hook 已挂（$METHOD）")
        return any
    }

    /** `String.valueOf(mState)` —— 只用它做 `contains`，不依赖 `ScrimState` 的类型。 */
    private fun readState(host: Any): String? {
        for (name in FIELDS) {
            val v = runCatching {
                val f = host.javaClass.getDeclaredField(name)
                f.isAccessible = true
                f.get(host)
            }.getOrNull() ?: continue
            return v.toString()
        }
        return null
    }
}
