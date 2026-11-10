package com.frederic22.hyperextreme.hook

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.frederic22.hyperextreme.data.AliveFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * **让 AOD 透出壁纸**（v1.22.80，2026-11-10）。
 *
 * ## 问题
 *
 * 从**桌面**按电源进 AOD 全黑、只剩时钟；从**锁屏**进 AOD 正常。
 * SurfaceFlinger 里我们的层 `he-alive-lock` 一直 visible、alpha=1、buffer 一直在画 ——
 * 它没被藏起来，是被**盖住**了。
 *
 * ## 根因（`com.miui.aod` 反编译实证）
 *
 * AOD 的画面就是「一块全屏黑底 + 上面的时钟」，时钟是它的子 View，所以在；我们的层在壁纸
 * leash 上，被这块黑底整片盖死。黑底来自三处：
 *
 * ```java
 * // DozeHost.showSecurityIdentityViewAt()
 * boolean z = Utils.isLinkageState(...) && this.mNeedLinkageWallpaperAnim;
 * if (!this.mNeedShowWallpaper && !z) return;      // ★ 早退，跳过下面这行
 * aODViewFindAodView.setBackgroundColor(0);        // 只有走到这里才透明
 *
 * // AODView.makeNormalPanel()
 * if (!Utils.isUsingSuperWallpaperStyle(getContext()) || this.mMode == 1) {
 *     dozeHost.disableShowWallpaper();             // 里面 setBackgroundColor(-16777216)
 *     setBackgroundColor(-16777216);
 * }
 * ```
 *
 * `z` 只在**熄屏那一刻已经站在锁屏上**时才为 true
 * （`DozeServiceHostInjector#updateScreenOffNeedLinkageAnimStateInternal` 要求
 * `mKeyguardShowing && StatusBarState == KEYGUARD`，然后
 * `MiuiDozeService#onDreamingStarted` 把值 `aodView.setTag(...)` 递给 aod 插件）。
 * 从桌面熄屏 `mKeyguardShowing == false` ⇒ `z=false` ⇒ 黑底留下。
 *
 * `mNeedShowWallpaper` 对我们恒为 false（它要求两张壁纸都是 `CATEGORY_SUPER_WALLPAPER`，
 * 我们是静态壁纸）。AOD 窗口本身是**半透明**的（`ro.hardware.fp.fod=true` ⇒
 * `isGxzwSensor()` ⇒ `pixelFormat = -2`），所以只要 View 背景透明，壁纸就一定透得出来。
 *
 * ## 装在哪里（v1.22.80 第一版踩的坑）
 *
 * **AOD 的类不在 `com.miui.aod` 进程里**，而是在 **SystemUI 进程**里由插件 classloader 加载 ——
 * logcat 铁证：`12:28:05.910 27090 27090 D AODView : onAodContentLayoutChange`，
 * 27090 就是 `com.android.systemui`（这也和 `AodClockProbe` 里「宿主 loader 看不见
 * `com.miui.aod.AODView`」的老结论一致）。所以 hook 必须装 SystemUI，且**按类名认人**
 * （拿不到插件 Class）。
 *
 * ## 做法
 *
 * 不碰 `isUsingSuperWallpaperStyle`（那会把 `mMode` 改成 2 = 超壁纸定位，连带改时钟布局，
 * 破坏按 `LinkageClock` 实测对齐的球心），只把**这层黑底**变成透明：
 *
 * | hook | 作用 |
 * | --- | --- |
 * | `View#setBackgroundColor`（只认 AODView 实例） | 五处 `setBackgroundColor(-16777216)` 全部改判成 0 |
 * | `View#setBackgroundResource/setBackground/setBackgroundDrawable` | 布局里若带不透明底也一并掐掉 |
 * | `View#onAttachedToWindow`（复用现成那条，只判 AOD 宿主） | 上屏前兜底置空 + 诊断 |
 *
 * 只在 `AliveFlag.PROP_ACTIVE == 1` 时改行为；Alive 关掉后 AOD 完全恢复系统原样。
 */
internal object AliveAodHost {

    private const val TAG = "ALIVE AOD"

    private const val AOD_PACKAGE = "com.miui.aod."
    private const val AOD_VIEW = "com.miui.aod.AODView"

    /** `0xFF000000` —— 系统给 AOD 涂的那个不透明纯黑。 */
    private const val BLACK = -0x1000000

    /** 属性读取缓存：hook 都是低频调用，1s 足够。 */
    private const val CACHE_MS = 1000L

    @Volatile private var aliveAt = 0L
    @Volatile private var aliveValue = false

    private val logged = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 在 **SystemUI** 进程里安装（AOD 的类由插件 classloader 加载，见类注释）。
     * 只挂 `android.view.View` 上的方法，靠类名认 AODView，所以不需要插件 loader。
     */
    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val view = runCatching { Class.forName("android.view.View", false, loader) }.getOrNull()
        if (view == null) {
            HookRuntime.log("$TAG 找不到 android.view.View")
            return false
        }
        var count = 0

        // 1. 背景色：把系统涂的黑改判成透明（五处调用点一次覆盖）。
        count += hook(module, view, "setBackgroundColor", Int::class.javaPrimitiveType) { chain ->
            val target = chain.getThisObject()
            if (!isAodRoot(target)) return@hook chain.proceed()
            val color = chain.getArgs().firstOrNull() as? Int
            if (!isAlive()) {
                note("setBackgroundColor(${hex(color)}) 透传（Alive 未生效）")
                return@hook chain.proceed()
            }
            if (color != BLACK) return@hook chain.proceed()
            note("setBackgroundColor(黑) -> 透明")
            chain.proceed(arrayOf<Any?>(0))
        }

        // 2. 背景 Drawable：布局里若声明了不透明底，同样不能要。
        for (name in listOf("setBackground", "setBackgroundDrawable")) {
            count += hook(module, view, name, Drawable::class.java) { chain ->
                if (!isAlive() || !isAodRoot(chain.getThisObject())) return@hook chain.proceed()
                note("$name(Drawable) -> 跳过")
                null
            }
        }
        count += hook(module, view, "setBackgroundResource", Int::class.javaPrimitiveType) { chain ->
            if (!isAlive() || !isAodRoot(chain.getThisObject())) return@hook chain.proceed()
            note("setBackgroundResource -> 跳过")
            null
        }

        HookRuntime.log("$TAG 已挂 $count 个 hook（SystemUI 进程，Alive 生效时才动手）")
        return count > 0
    }

    /**
     * AOD 宿主 View 入窗 —— 由 `HookEntry` 里那条 `View#onAttachedToWindow` 探针分支调用
     * （那里已经用 `AodClockProbe.isCandidate` 认出 AOD 宿主了）。
     *
     * 这里做两件事：打一条诊断（背景到底是不是纯黑 / 到底是不是它盖的），以及在 Alive 生效时置空。
     */
    fun onAodViewAttached(view: View) {
        if (!isAodRoot(view)) return
        note(
            "AODView 入窗：class=${view.javaClass.name} bg=${describe(view.background)} " +
                "visibility=${view.visibility} alive=${isAlive()}",
        )
        if (!isAlive()) return
        view.setBackgroundColor(0)
    }

    private fun isAodRoot(target: Any?): Boolean {
        if (target !is View) return false
        var cls: Class<*>? = target.javaClass
        // 先做包名短路：SystemUI 里绝大多数 View 连第一关都过不了。
        if (cls?.name?.startsWith(AOD_PACKAGE) != true) return false
        var depth = 0
        while (cls != null && depth < 6) {
            if (cls.name == AOD_VIEW) return true
            cls = cls.superclass
            depth++
        }
        return false
    }

    private fun isAlive(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - aliveAt < CACHE_MS) return aliveValue
        aliveValue = HookRuntime.systemProperty(AliveFlag.PROP_ACTIVE) == "1"
        aliveAt = now
        return aliveValue
    }

    private fun describe(bg: Drawable?): String = when (bg) {
        null -> "null"
        is ColorDrawable -> "ColorDrawable(${hex(bg.color)})"
        else -> bg.javaClass.name
    }

    private fun hex(color: Int?): String =
        color?.let { "#%08X".format(it) } ?: "null"

    /** 同一个动作只打一条，免得每次进 AOD 刷屏。 */
    private fun note(message: String) {
        if (logged.add(message)) HookRuntime.log("$TAG $message")
    }

    private fun hook(
        module: XposedModule,
        owner: Class<*>,
        name: String,
        vararg params: Class<*>?,
        handler: (XposedInterface.Chain) -> Any?,
    ): Int = runCatching {
        val method = owner.getDeclaredMethod(name, *params)
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = handler(chain)
        })
        1
    }.getOrElse {
        HookRuntime.log("$TAG 挂 $name 失败：${it.message}")
        0
    }
}
