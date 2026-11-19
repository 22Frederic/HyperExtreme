package com.frederic22.hyperextreme.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「锁屏内容该不该存在」的**权威闸门**（2026-11-19）。
 *
 * ## 解决什么
 *
 * 通知中心（解锁后下拉）的背景走的是**"包含下方"的捕获管线，它既不尊重可见性、也不尊重
 * 祖先 alpha**（2026-10-12 用 v7 探针实测，见《04-功能设计存档》）。视差链（[ParallaxBg]）
 * 在 v9 就针对这一点做了"内容抑制"，但后来新增的三条景深主体链
 * （[SpatialOverlay] / [RasterOverlay] / [AliveOverlay]）**没有跟进** ——
 * 它们的 `SubjectView` 在解锁后仍然留在 `keyguard_foreground_layer` 里、仍然绘制，
 * 于是被通知中心连同背景一起捕走，观感就是"通知中心里出现主体层"。
 *
 * ## 为什么状态机是权威信号
 *
 * 可见性在解锁退场期间会**反复瞬闪**（实测 7~17ms），只靠 `isShown` 判断会误恢复；
 * 而 `StatusBarStateControllerImpl#setState` 在桌面稳定为 `SHADE(0)`，**下拉通知中心不改变它**
 * —— 这正是"内容该不该存在"的判据。初值未知时退回可见性判断（不影响开机首帧）。
 *
 * ## 谁在用
 *
 * | 使用方 | 用法 |
 * | --- | --- |
 * | [ParallaxBg] | 注册监听器，驱动它自己那套带防抖 / 渐隐的抑制时序（行为不变） |
 * | [SpatialOverlay] / [RasterOverlay] / [AliveOverlay] | `onDraw` 里 `allowDraw(this)` 早退 + 状态变化时 `invalidate()` |
 *
 * 三者都不再各自 hook `setState`（同一方法被同一模块 hook 两次会互相覆盖）。
 */
internal object KeyguardContentGate {

    /** 真机 `analyse/re-glass` 核实：`setState(int, boolean)`。 */
    private const val CLS_STATE_IMPL = "com.android.systemui.statusbar.StatusBarStateControllerImpl"

    const val STATE_SHADE = 0
    const val STATE_KEYGUARD = 1
    const val STATE_SHADE_LOCKED = 2

    /** 还没收到过 `setState`（开机首帧 / hook 失灵）。 */
    const val UNKNOWN = Int.MIN_VALUE

    /** 最近一次状态（[UNKNOWN] = 未知）。 */
    @Volatile
    var state: Int = UNKNOWN
        private set

    /** 上一次状态，供监听器判断方向（如"1/2 → 0"= 解锁退场）。 */
    @Volatile
    var prevState: Int = UNKNOWN
        private set

    /** hook 是否装上了；没装上时 [contentAllowed] 恒 true（退化到旧行为，不误伤）。 */
    @Volatile
    var installed: Boolean = false
        private set

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 状态变化监听器（**主线程**回调）。 */
    private val listeners = CopyOnWriteArrayList<(prev: Int, now: Int) -> Unit>()

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installed) return true
        val clazz = runCatching { Class.forName(CLS_STATE_IMPL, false, loader) }.getOrNull()
        if (clazz == null) {
            log("未找到 $CLS_STATE_IMPL")
            return false
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setState" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            log("未找到 setState(int, boolean)")
            return false
        }
        val ok = runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runCatching {
                        // 扫第一个 Int 参数（防个别框架把接收者混进 args）。
                        val now = chain.getArgs().firstOrNull { it is Int } as? Int ?: return@runCatching
                        if (now == state) return@runCatching
                        val prev = state
                        prevState = prev
                        state = now
                        log("state=$now（${name(now)}）")
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            dispatch(prev, now)
                        } else {
                            mainHandler.post { runCatching { dispatch(prev, now) } }
                        }
                    }.onFailure { log("状态处理异常：${it.javaClass.simpleName}: ${it.message}") }
                    return result
                }
            })
            true
        }.getOrElse {
            log("挂 setState 失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
        installed = ok
        if (ok) log("已挂状态机（内容闸门 + 视差抑制共用同一份状态）")
        return ok
    }

    /** 注册监听器（幂等由调用方保证；同一 lambda 重复注册会重复回调）。 */
    fun addListener(listener: (prev: Int, now: Int) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 内容是否**该存在**：锁屏在场（含锁屏上拉）或状态未知时允许。
     *
     * 解锁后（`SHADE/桌面`）一律不允许 —— 这正是通知中心捕获的那一档。
     */
    fun contentAllowed(): Boolean {
        val s = state
        return s == UNKNOWN || s == STATE_KEYGUARD || s == STATE_SHADE_LOCKED
    }

    /**
     * 绘制闸门：状态允许 **且** 视图此刻真的可见。
     *
     * 两个条件缺一不可 —— 状态管"解锁后不许画"，可见性管"灭屏 / AOD / 被 MC 封面顶掉"。
     * 只判状态会让灭屏期间的内容留在 RenderNode 里；只判可见性会被解锁退场的可见性瞬闪骗过。
     */
    fun allowDraw(view: View): Boolean =
        contentAllowed() && runCatching { view.isShown }.getOrDefault(false)

    private fun dispatch(prev: Int, now: Int) {
        listeners.forEach { runCatching { it(prev, now) } }
    }

    private fun name(s: Int): String = when (s) {
        STATE_SHADE -> "SHADE/桌面"
        STATE_KEYGUARD -> "KEYGUARD/锁屏"
        STATE_SHADE_LOCKED -> "SHADE_LOCKED/锁屏上拉"
        else -> "未知($s)"
    }

    private fun log(msg: String) = HookRuntime.log("KCG $msg")
}
