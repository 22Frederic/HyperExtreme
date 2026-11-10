package com.frederic22.hyperextreme.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.abs

/**
 * 「锁屏联动息屏」下把 **AOD 小球对齐到时钟中心**（2026-11-08）。
 *
 * ## 为什么这么做
 *
 * 用户只做 `LinkageClock` 这一种 AOD 样式（「锁屏联动息屏」），它把**锁屏那套时钟**
 * 搬到息屏上，所以时钟位置不再是固定值 —— 球心必须跟着它走。
 * 换成别的样式（`CosmicClock` 等）时 `mAodClock` 根本不是 `LinkageClock`、
 * 也没有 `mClockViewContainer` 字段，这套逻辑自然不生效（正好是「别的不管」）。
 *
 * ## 怎么拿
 *
 * `AODView.mAodClock` 是 `com.miui.aod.widget.LinkageClock`（**控制器，不是 View**），
 * 它的 `mClockViewContainer`（`LinkageStyleClockView`）才是时钟本体。
 * 那个 View 的 `getLocationOnScreen` + 高度一半 = 时钟中心。
 *
 * ⚠️ `Class.forName("com.miui.aod.AODView", 宿主loader)` 找不到 —— AOD 的类由它自己的
 * classloader 加载（SystemUI 只是把 View 树 inflate 进自己的窗口）。所以判据走**实例类名**。
 *
 * 量不到就保持 [AliveGlCore.AOD_Y]（0.335，上游默认值）。
 */
internal object AodClockProbe {

    private const val TAG = "AODCLOCK"
    private const val FIELD_CLOCK = "mAodClock"
    private const val FIELD_CONTAINER = "mClockViewContainer"
    private const val AOD_PACKAGE = "com.miui.aod."

    /** 布局要等一拍；AOD 起来后还有位置动画，分几个时间点各量一次（取最后一个稳定值）。 */
    private val DELAYS = longArrayOf(120L, 600L, 1500L, 3000L)

    private var lastLogged = ""

    /** 是不是 AOD 的宿主 View：类名 `com.miui.aod.*` 且带 `mAodClock` 字段。 */
    fun isCandidate(v: View): Boolean {
        if (!v.javaClass.name.startsWith(AOD_PACKAGE)) return false
        return hasField(v.javaClass, FIELD_CLOCK)
    }

    fun onAodViewAttached(view: View) {
        val h = Handler(Looper.getMainLooper())
        for (d in DELAYS) h.postDelayed({ measure(view, d) }, d)
    }

    private fun measure(host: View, delay: Long) = runCatching {
        val clock = readField(host, FIELD_CLOCK)
        if (clock == null) {
            logOnce("+${delay}ms 读不到 $FIELD_CLOCK（host=${host.javaClass.name}）")
            return@runCatching
        }
        // 时钟本体：优先 mClockViewContainer（LinkageClock），否则时钟自己就是 View
        val view = readField(clock, FIELD_CONTAINER) as? View ?: clock as? View
        if (view == null) {
            logOnce("+${delay}ms ${clock.javaClass.simpleName} 里找不到时钟 View")
            return@runCatching
        }
        if (view.width <= 0 || view.height <= 0) {
            logOnce("+${delay}ms 时钟还没量出尺寸（${view.width}x${view.height}）")
            return@runCatching
        }
        val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        if (screenH <= 0) return@runCatching

        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        val centerY = (rect.top + rect.height() / 2f) / screenH
        // lens 的 y 是 `center_y − 0.5`（GL 从下往上），所以这里要翻
        val ty = 0.5f - centerY

        if (abs(AliveGlCore.aodCenterY - ty) > 0.0005f) {
            AliveGlCore.aodCenterY = ty
            HookRuntime.log(
                "$TAG +${delay}ms 球心对齐时钟：${view.javaClass.simpleName} rect=$rect " +
                    "centerY=${"%.4f".format(centerY)} ⇒ aodCenterY=${"%.4f".format(ty)}",
            )
            // 已经在 AOD 里了 ⇒ 立刻把当前参数拉过去，不用等下次进入
            runCatching { AliveRenderer.onAodClockYChanged() }
        } else if (delay == DELAYS.last()) {
            logOnce(
                "$TAG 时钟 ${view.javaClass.simpleName} centerY=${"%.4f".format(centerY)}" +
                    "（aodCenterY 已是 ${"%.4f".format(ty)}，无需改动）",
            )
        }
    }.onFailure {
        logOnce("+${delay}ms 异常：${it.javaClass.simpleName}: ${it.message}")
    }

    private fun hasField(c0: Class<*>, name: String): Boolean {
        var c: Class<*>? = c0
        while (c != null && c != Any::class.java) {
            if (runCatching { c.getDeclaredField(name) }.isSuccess) return true
            c = c.superclass
        }
        return false
    }

    /** 从实例往上找字段并取值（AODView / 其子类都可能声明）。 */
    private fun readField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val got = runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.get(obj)
            }.getOrNull()
            if (got != null) return got
            c = c.superclass
        }
        return null
    }

    /** 同一条日志只打一次（AOD 每次进出都会重新走一遍）。 */
    private fun logOnce(msg: String) {
        if (msg == lastLogged) return
        lastLogged = msg
        HookRuntime.log("$TAG $msg")
    }

    private fun log(msg: String) = HookRuntime.log("$TAG $msg")
}
