package com.frederic22.hyperextreme.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 与 **HyperMusicCover** 的单向状态共享（2026-11-14 立项，2026-11-14 晚重做判据）。
 *
 * ## MC 的封面到底画在哪
 *
 * 翻过它的源码（`analyse/HyperMusicCover-src`，2026-11-14）：
 *
 * * 封面**不是** SystemUI 里的一个 View，而是**锁屏壁纸纹理本身** —— 它 hook 壁纸进程
 *   （`com.miui.miwallpaper`）的 `ImageEngineImpl`，把 keyguard renderer 要上传的 bitmap
 *   换成专辑封面（`WallpaperProbe.sArt` / `CoverPush.pushArtToWallpaper`）；
 * * 三种形态 `CoverCardStyle`：`FULL`（全屏封面）/ `CARD`（方形卡片，壁纸是卡片的背景）/
 *   `KEEP`（保留壁纸：源码里 `keep = on && keepsWallpaper()` 会把这次推送直接改成 `off`，
 *   壁纸**始终是用户自己的**）。
 *
 * 我们的 `he-*` 层是**壁纸 leash 的子层**（`Transaction.reparent(sc, leash)`），
 * 永远画在壁纸纹理之上 ⇒ 只要 MC 推了封面，我们就必然盖住它。**只能让开，不能让 z 序**
 * （`SurfaceControl` 没有 `getParent`，也挂不到 token 那层 —— 见《04》v1.22.90 的层树实验）。
 *
 * ## 所以判据 = "MC 把封面推上锁屏了吗"
 *
 * ```
 * 让开 = sCoverMode && !keepsWallpaper()      // 亮屏 / AOD / 熄屏一律让
 * ```
 *
 * * **主判据**：反射读 MC 的 `Main.sCoverMode`（`PUBLIC STATIC VOLATILE Z`）与
 *   `Main.keepsWallpaper()`（`PUBLIC STATIC ()Z`）。设备上那个发行版 APK 用 dexdump 逐条核过，
 *   **两个名字都没被 R8 混淆**（`Main` 类名也在）。字段是 volatile，读它等于免费。
 * * **类从哪来**：MC 的 `ProbeReceiver` 自己被混淆了，所以**不猜类名** —— 从
 *   `ContextImpl#registerReceiver` 抓到的 receiver 实例取 `javaClass.classLoader`，
 *   拿它去 `Class.forName("com.os4.musiccover.Main", false, cl)`，**成功就算认到人**。
 *   MC 每次热重载会重新注册 receiver，我们顺势换新的 classloader。
 * * **兜底**：`ContextImpl#sendBroadcast` 里 `action=com.os4.musiccover.WPROBE && op=art`
 *   的 `off` 位（`CoverPush#pushArtToWallpaper` 的 on/off 就是这一个开关）。
 * * 两条都读不到 ⇒ **不让路**（零影响），只在日志里提示一次。
 *
 * ⚠️ 别再回到「扫 SystemUI 视图树找 music/lyric/cover 类名」那条路（v1.22.95 的做法）：
 * `FULL` 模式下封面在壁纸进程里，SystemUI **一个对应 View 都没有**（方卡只在 `CARD` 时
 * attach、歌词层只在有歌词时可见）⇒ 该让的时候发现不了，不该让的时候（媒体卡 / 岛）反而
 * 整条 Alive 退出。用户 2026-11-14 原话：「它这个兼容就是检测到直接退出」。
 *
 * ## 时间常数（v1.22.102 压过一轮）
 *
 * * **主信号 = hook `enterCoverMode` / `exitCoverMode`**（0 延迟）：这两个方法在发行版里
 *   没被 R8 改名（dexdump 核过），`proceed()` 之后重新 `apply(rawCoverOn())` 即可。
 * * 轮询 300ms **降级为兜底**（hook 装不上 / MC 升级改名时）：只在装了 MC 时启动。
 * * 退出延后 [LEAVE_DELAY_MS] = 120ms：原先 450ms 是「等 MC 的封面淡出（~400ms）」，
 *   但我们自己的渲染器从 `start()` 到首帧上屏本身 370~420ms，**天然盖住** ⇒ 450 是重复等待。
 * * `setState()` 里 `notifyAll` 唤醒三条链的 tick 线程（它们用 `wait` 而非 `sleep`）。
 * * **AOD 也让**（2026-11-14 真机改判）：起初按「AOD 的 `AODView` 黑底盖着、我们的层看不见」
 *   写成不让路，实测不成立 —— v1.22.80 起 AOD 的 `AODView` 背景被我们置空（为了透出壁纸），
 *   于是 AOD 里我们的画面和 MC 的封面**在同一块屏幕上抢层**（用户原话：「AOD 也会和
 *   全屏封面抢层」）。现在不再按 `isInteractive` 过滤：亮屏 / AOD / 熄屏一律让开。
 */
internal object Coexist {

    /** MC 的封面正盖在锁屏壁纸上 ⇒ 我们的**锁屏份**必须让开（桌面份不受影响）。 */
    @Volatile
    var musicCoverOn: Boolean = false
        private set

    /** 状态翻转的订阅者（渲染器改 alpha；前景层重算可见性）。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    private fun dispatchChanged() {
        for (l in listeners) runCatching { l() }
    }

    // ───────────────────────────── 探测 ─────────────────────────────

    /** MC 的**安装包名**（源码 `namespace` 是 `com.os4.musiccover`，applicationId 是这个）。 */
    private const val MC_PACKAGE = "com.github.zyl6932.HyperMusicCover"

    /** 它 SystemUI 侧的入口类（保留名，见类注释）。 */
    private const val MC_MAIN = "com.os4.musiccover.Main"

    /** 它 SystemUI ↔ 壁纸进程的私有广播（`CoverPush.wallpaperIntent`）。 */
    const val ACTION = "com.os4.musiccover.WPROBE"

    /** 进出封面的两个静态方法（发行版里没被 R8 改名，见 [installModeHooks]）。 */
    private val MODE_METHODS = arrayOf("enterCoverMode", "exitCoverMode")

    private const val POLL_MS = 300L

    /**
     * 退出封面后**等多久才真正恢复**。
     *
     * 2026-11-14 从 450ms 压到 120ms：这段原本是「等 MC 的封面淡出走完」（~400ms），
     * 但我们自己的渲染器从 `start()` 到首帧上屏本身就要 370~420ms（建 EGL + 首帧 + 上传纹理），
     * 天然盖住淡出 ⇒ 450 是**重复等待**，真机上表现为「退出好一会才回来」。
     */
    private const val LEAVE_DELAY_MS = 120L

    private var started = false
    private var tries = 0
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var appCtx: Context? = null

    @Volatile private var mainClass: Class<*>? = null
    @Volatile private var fCoverMode: java.lang.reflect.Field? = null
    @Volatile private var mKeepsWallpaper: java.lang.reflect.Method? = null

    /** 试过的 classloader（弱引用，MC 热重载换代后不拦新的一代）。 */
    private val triedLoaders: MutableSet<ClassLoader> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    /** 广播兜底：最后一次 `op=art` 的结果；null = 还没收到过。 */
    @Volatile private var artOnFromBroadcast: Boolean? = null

    private var warnedNoClass = false

    // ───────────────────── 即时信号：hook MC 自己的进出封面方法 ─────────────────────
    //
    // 2026-11-14 用户：「退出好一会才恢复，有没有更早的？」—— 300ms 轮询虽然是「读最新的
    // `sCoverMode`」，但**发现**它靠的是节拍。`Main.enterCoverMode(Z)V` / `exitCoverMode(Z)V`
    // 这两个方法在发行版里**没被 R8 改名**（dexdump 核过：`Main.enterCoverMode:(Z)V` @0x3be140、
    // `Main.exitCoverMode:(Z)V` @0x3be414；同批的 `pushArtToWallpaper` 已被改名，说明是 MC
    // 自己的 keep 规则保下的），直接 hook 就能拿到 **0 延迟**的进 / 出信号。
    //
    // 兜底不变：hook 装不上（MC 升级改名）就继续靠 300ms 轮询，功能不退化。

    private var module: XposedModule? = null

    /** 已装过方法的 classloader（MC 热重载会换代，换代后要在新 loader 上重装）。 */
    private val hookedLoaders: MutableSet<ClassLoader> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    /**
     * 三条链 tick 线程共用的等待锁。
     *
     * 状态一变就 `notifyAll` —— tick 线程用 `wait(interval)` 而不是 `sleep(interval)`，
     * 于是「让路 / 恢复」不用等下一次节拍（空间原来是 1s，最坏白等 1 秒）。
     */
    val tickLock: java.lang.Object = java.lang.Object()

    private fun wakeTicks() {
        synchronized(tickLock) { tickLock.notifyAll() }
    }

    /** 由 `HookEntry.installSystemUiHooks` 调一次，用于装 `enter/exitCoverMode` 的 hook。 */
    fun bindModule(m: XposedModule) {
        module = m
    }

    /** 延后恢复的定时（封面淡出）。 */
    private var offRun: Runnable? = null

    /**
     * 包名闸门 + 起轮询。由 `HookEntry.installSystemUiHooks` 调一次。
     *
     * **没装 MC 就完全不启动** —— 连 Handler 都不建，普通用户零成本
     * （设备上没装时日志只有一行 `未安装 → 不启动`）。
     */
    fun start(ctx: Context? = null) {
        if (started) return
        val app = ctx ?: HookRuntime.appContext()
        if (app == null) {
            // init 阶段 Application 往往还没建好：每 1s 重试，最多 30 次
            tries++
            if (tries == 1) HookRuntime.log("MUSIC_COVER 等 Application（最多 30s）")
            if (tries <= 30) handler.postDelayed({ start(null) }, 1000L)
            else HookRuntime.log("MUSIC_COVER 放弃：拿不到 Application")
            return
        }
        val installed = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(MC_PACKAGE, 0) != null
        }.getOrDefault(false)
        if (!installed) {
            started = true
            HookRuntime.log("MUSIC_COVER 未安装 HyperMusicCover → 不启动（零轮询）")
            return
        }
        started = true
        appCtx = app
        handler.postDelayed(tick, POLL_MS)
        HookRuntime.log(
            "MUSIC_COVER 启动（${POLL_MS}ms 读 Main.sCoverMode，不扫视图树）",
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { apply(rawCoverOn()) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * 由 `ContextImpl#registerReceiver` 的 hook 调（每次注册都会过，纳秒级）。
     *
     * 用它认 MC 的 classloader：**不猜 receiver 的类名**（`ProbeReceiver` 已被 R8 混淆），
     * 直接拿这个 loader 去加载 `MC_MAIN`，成功就是它。
     */
    fun noteReceiver(receiver: Any?) {
        val cl = receiver?.javaClass?.classLoader ?: return
        if (mainClass?.classLoader === cl) return
        if (!triedLoaders.add(cl)) return
        runCatching {
            val c = Class.forName(MC_MAIN, false, cl)
            val f = c.getField("sCoverMode")
            val m = c.getMethod("keepsWallpaper")
            mainClass = c
            fCoverMode = f
            mKeepsWallpaper = m
            // ⚠️ 不要把 `warnedNoClass` 重置：SystemUI 里每个模块都有各自的 classloader，
            //    每来一个都 `Class.forName` 一次（失败也只要几微秒），但日志不该跟着刷。
            val cover = runCatching { f.getBoolean(null) }.getOrDefault(false)
            val keep = runCatching { m.invoke(null) as? Boolean }.getOrNull()
            HookRuntime.log(
                "MUSIC_COVER 已接入 MC 状态（$MC_MAIN）" +
                    "：sCoverMode=$cover keepsWallpaper=$keep",
            )
            // 拿到类就顺手装 0 延迟的进出信号
            installModeHooks(cl)
        }.onFailure {
            if (!warnedNoClass) {
                warnedNoClass = true
                HookRuntime.log(
                    "MUSIC_COVER 这个 classloader 没有 $MC_MAIN" +
                        "（${it.javaClass.simpleName}）—— 继续找下一个",
                )
            }
        }
    }

    /**
     * 在 MC 的类上装 `enterCoverMode` / `exitCoverMode` 的 hook。
     *
     * **必须在 `proceed()` 之后**读字段：`enterCoverMode` 第一行才 `sCoverMode = true`、
     * `exitCoverMode` 第二行才 `sCoverMode = false`，早了读到的还是旧值。
     *
     * 装不上就静默跳过 —— 300ms 轮询仍是兜底，功能不退化。
     */
    private fun installModeHooks(cl: ClassLoader) {
        val mod = module ?: return
        if (!hookedLoaders.add(cl)) return
        val cls = runCatching { Class.forName(MC_MAIN, false, cl) }.getOrNull() ?: return
        var ok = 0
        for (name in MODE_METHODS) {
            val method = cls.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: continue
            runCatching {
                method.isAccessible = true
                mod.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        runCatching { apply(rawCoverOn()) }
                        return r
                    }
                })
                ok++
            }.onFailure {
                HookRuntime.log(
                    "MUSIC_COVER $name hook 失败：${it.javaClass.simpleName}: ${it.message}",
                )
            }
        }
        if (ok > 0) {
            HookRuntime.log("MUSIC_COVER 已挂 $ok 个进出封面方法（0 延迟信号，轮询留作兜底）")
        } else {
            HookRuntime.log("MUSIC_COVER 没找到 ${MODE_METHODS.joinToString("/")}（继续靠轮询）")
        }
    }

    /**
     * 由 `ContextImpl#sendBroadcast` 的 hook 调。
     *
     * @return 是否命中 MC 的 `op=art`（调用方据此决定要不要打日志）
     */
    fun noteBroadcast(action: String?, op: String?, off: Boolean): Boolean {
        if (action != ACTION || op != "art") return false
        artOnFromBroadcast = !off
        handler.post { runCatching { apply(rawCoverOn()) } }
        return true
    }

    /** 真的"封面盖在锁屏上"吗；null = 两条判据都读不到。 */
    private fun rawCoverOn(): Boolean? {
        val f = fCoverMode
        val m = mKeepsWallpaper
        if (f != null && m != null) {
            val ok = runCatching {
                if (!f.getBoolean(null)) false else !(m.invoke(null) as Boolean)
            }.getOrNull()
            if (ok != null) return ok
        }
        return artOnFromBroadcast
    }

    private fun apply(raw: Boolean?) {
        if (raw == true) {
            offRun?.let { handler.removeCallbacks(it) }
            offRun = null
            setState(true)
            return
        }
        // 封面已撤：等 MC 的淡出走完再回来（≈400ms）
        if (!musicCoverOn || offRun != null) return
        val r = Runnable {
            offRun = null
            if (rawCoverOn() != true) setState(false)
        }
        offRun = r
        handler.postDelayed(r, LEAVE_DELAY_MS)
    }

    private fun setState(on: Boolean) {
        if (on == musicCoverOn) return
        musicCoverOn = on
        HookRuntime.log(
            "MUSIC_COVER 封面" +
                (if (on) "在锁屏上 → 我们的锁屏份让开" else "已撤下 → 恢复我们的锁屏份"),
        )
        dispatchChanged()
        // 三条链的 tick 线程用 `wait(interval)` 等节拍 ⇒ 立刻唤醒，别让它们睡到下一次
        // （空间原来是 1s，白等最久 1 秒）。
        wakeTicks()
    }
}
