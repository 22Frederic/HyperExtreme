package com.frederic22.hyperextreme.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 空间壁纸在锁屏生效时，**撤掉系统的景深前景层**（2026-10-28）。
 *
 * ## 为什么是「撤掉」而不是「盖住」
 *
 * 空间壁纸的 GL 层挂在**锁屏壁纸的 leash** 下（`getWallpaperByShowWhenLocked(0, true)`，
 * 2026-10-28 真机探针已验证锁屏可见、桌面不可见），它全屏不透明 ⇒ 系统锁屏壁纸已经被盖住。
 * 此时唯一还压在它上面的，是 SystemUI 窗口里的 `keyguard_foreground_layer`。
 *
 * 把那一层撤掉，露出来的就是我们的图 —— **视觉上等价于「我们的图盖住了主体」，但不用新建
 * 任何 View、不用 TextureView、不用额外的合成**。
 *
 * ## 层级依据（`res/layout/keyguard_panel_view.xml`，aapt2 解原 APK 得到）
 *
 * ```
 * KeyguardPanelView
 *  [0] FrameLayout                  keyguard_background_layer
 *  [1] KeyguardSignatureLayerView   keyguard_signature_layer
 *  [2] KeyguardClockContainer       miui_keyguard_clock_container   ← 时钟
 *  [3] include → keyguard_foreground_layer   ← 景深主体 + 色带 + 装饰（本类要撤的）
 *  [4] FrameLayout                  miui_keyguard_foreground_clock_container
 *  [5] KeyguardInfoLayerView        keyguard_info_layer
 * ```
 *
 * `keyguard_foreground_layer` 内部（`res/layout/keyguard_foreground_layer.xml`）：
 * `deducted_image_view`（主体，全屏 CENTER_CROP）+ `ColorRibbonsContainer`（景深色带）+ 装饰。
 * **里面没有时钟、没有通知** —— 时钟在 [2]、通知在窗口根的兄弟容器里，撤掉它不影响两者。
 *
 * ## 为什么 hook `updateKeyguardElementsVisibility()`
 *
 * 这一层的显隐由 `KeyguardPanelViewController#updateKeyguardElementsVisibility()` 统一设置
 * （真机反编译 L8060-8063：`keyguardForegroundLayer.setVisibility(... ? 0 : 4)`）。
 * 系统每次状态变化都会重算 ⇒ 我们在 `proceed()` **之后**压回去，**天然自愈**，
 * 不需要自己监听 `onAttachedToWindow`、也不需要维护"层丢了"的状态。
 *
 * 只在「系统认为该显示（VISIBLE）」时才压 —— 系统本来设 4 的场合我们不干预，
 * 亮灭屏 / 退出 / pre-hide 全部走系统原逻辑。
 *
 * ## 开关
 *
 * 两个属性都满足才压（属性通道与 `HookRuntime.systemProperty` 一致，300ms 缓存）：
 * * `SpatialFlag.PROP_ACTIVE == 1` —— 空间壁纸生效中；
 * * `SpatialFlag.PROP_SCOPE != SPATIAL_SCOPE_DESKTOP` —— 锁屏或全局档。
 *
 * **与视差完全独立**：不读不写任何 `parallax*` 字段，也不碰 `ParallaxBg` 的 View 树。
 */
internal object SpatialForegroundHider {

    private const val CLS_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val METHOD = "updateKeyguardElementsVisibility"
    private const val FIELD_FOREGROUND = "keyguardForegroundLayer"

    /** 自检周期：2s。只比引用，开销可忽略。 */
    private const val SELF_CHECK_MS = 2_000L

    /** 同一条日志只打一次（`updateKeyguardElementsVisibility` 调用很频繁）。 */
    private val logged = ConcurrentHashMap<String, Boolean>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 最近一次 hook 到的 KeyguardPanelViewController，供音乐屏状态变化时重算。 */
    @Volatile private var lastSelf: Any? = null

    /** `Coexist` 订阅只装一次。 */
    private var coexistHookInstalled = false

    /** 内容闸门订阅只装一次（2026-11-19）。 */
    private var contentGateInstalled = false

    // ── 开关读取（300ms 缓存：方法调用频繁，属性反射不宜每次付） ──
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheValue = false

    /** 当前 Alive 卡是否带锁屏景深主体（`enabled()` 里顺手算好，`handle()` 直接用）。 */
    @Volatile private var aliveDepthOn = false
    private const val CACHE_MS = 300L

    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cacheAt < CACHE_MS) return cacheValue
        val active = HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE).trim() == "1"
        val scope = HookRuntime.systemProperty(SpatialFlag.PROP_SCOPE).trim().toIntOrNull()
            ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val spatialOn = active && scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        // **光栅壁纸同样需要撤这一层**（2026-11-04）：它也挂在壁纸 leash 上、全屏不透明，
        // 系统那层 `keyguard_foreground_layer`（主体抠像 + 景深色带）会压在我们的图上面，
        // 看起来就是"旧壁纸的人浮在新壁纸上面"。它没有景深主体，所以走下面的"旧链"整层压掉。
        val rasterActive = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_ACTIVE,
        ).trim() == "1"
        val rasterScope = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_SCOPE,
        ).trim().toIntOrNull() ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val rasterOn = rasterActive && rasterScope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        // **Alive 壁纸同样要撤这一层**（2026-11-07）：它也挂在壁纸 leash 上、全屏不透明，
        // 系统那层 `keyguard_foreground_layer`（主体抠像 + 景深色带）会压在它的图上面。
        // 2026-11-08 起分两档：**有景深主体**（`alive_depth=1`）⇒ 保留这一层、
        // 把里面的 `deducted_image_view` 换成我们的主体（见 [AliveOverlay]）；
        // **没有主体** ⇒ 走"旧链"整层压掉。
        val aliveActive = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.AliveFlag.PROP_ACTIVE,
        ).trim() == "1"
        val aliveDepth = aliveActive && HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.AliveFlag.PROP_DEPTH,
        ).trim() == "1"
        aliveDepthOn = aliveDepth
        val v = spatialOn || rasterOn || aliveActive
        if (v != cacheValue) {
            log(
                "开关 -> $v（空间=$spatialOn[$active/$scope] 光栅=$rasterOn[$rasterActive/$rasterScope] " +
                    "Alive=$aliveActive/景深=$aliveDepth）",
            )
        }
        cacheAt = now
        cacheValue = v
        return v
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_PANEL, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_PANEL")
            return false
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == METHOD && it.parameterCount == 0
        }
        if (method == null) {
            log("未找到 $METHOD()")
            return false
        }
        val ok = runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // **必须在 proceed 之后**：系统刚把 visibility 算完，我们才盖得住。
                    // 闸门之前先收口：属性 active=0（关闭替换）时 [handle] 根本不会进，
                    // 不清的话我们建的 View 会一直留在前景层里（2026-10-29 问题 1）。
                    if (!enabled()) {
                        SpatialOverlay.onDisabled()
                        return result
                    }
                    runCatching { handle(chain.getThisObject()) }
                        .onFailure { log("撤前景层失败：${it.javaClass.simpleName}: ${it.message}") }
                    return result
                }
            })
            true
        }.getOrElse {
            log("挂 $METHOD 失败：$it")
            false
        }
        if (ok) {
            log("已挂上（锁屏/全局档时接管 keyguard_foreground_layer）")
            // 内容闸门（2026-11-19）：解锁后通知中心的"包含下方"捕获不尊重可见性 ⇒
            // 状态一变就让三个主体 View 重绘一帧，`onDraw` 里的 [KeyguardContentGate.allowDraw]
            // 会把这一帧画成空（RenderNode 里没有可被取走的内容）。
            // 闸门本身通常在 HookEntry 里更早装好，这里再兜一次（幂等）。
            KeyguardContentGate.install(module, loader)
            if (!contentGateInstalled) {
                contentGateInstalled = true
                KeyguardContentGate.addListener { _, now ->
                    SpatialOverlay.onContentGateChanged()
                    RasterOverlay.onContentGateChanged()
                    AliveOverlay.onContentGateChanged()
                    // **回锁屏时主动补挂**（2026-11-22 用户报：连续快速「桌面 → 黑屏 → 锁屏」
                    // 后三条链的景深主体层都不显示）。机制：锁屏前景层随锁屏树重建，我们的
                    // `SubjectView` 随旧层销毁、系统那个 `deducted_image_view` 却仍是"已摘"态；
                    // 而重新挂载的唯一入口 `updateKeyguardElementsVisibility` 在快速切态时
                    // 可能只调一次、且在层还是 INVISIBLE 的时候 ⇒ `handle` 早退后无人补。
                    // 这里派两次：下一帧（本次状态分发的布局走完）+ 200ms（锁屏树重建完）。
                    if (now == KeyguardContentGate.STATE_KEYGUARD ||
                        now == KeyguardContentGate.STATE_SHADE_LOCKED
                    ) {
                        mainHandler.post { runCatching { refresh() } }
                        mainHandler.postDelayed({ runCatching { refresh() } }, 200L)
                        // **把主体 alpha 抬回来**（2026-11-22 根因）：快速「桌面 → 黑屏 → 锁屏」时，
                        // 灭屏那一刻 `doWallpaperBlackAnim(show=false)` 会把主体淡到 0
                        // （`*Overlay.onEnterAod`），而亮屏回来的 `show=true` 可能整次调用被系统跳过
                        // ⇒ 没人抬。真机日志：`onDraw … allowed=true shown=true a=0.00`，
                        // 位图 / 尺寸 / 闸门全正常，只有 alpha 是 0。
                        // 只在**屏幕真的亮着**时抬：AOD 里 `isInteractive=false`，绝不能抬。
                        // 三条链一起（Alive 虽然强制 AOD 才用，但逻辑保持同步，避免下次又漏）。
                        mainHandler.postDelayed({
                            runCatching {
                                if (!screenInteractive()) return@runCatching
                                SpatialOverlay.resetAlpha()
                                RasterOverlay.resetAlpha()
                                AliveOverlay.resetAlpha()
                            }
                        }, 200L)
                    }
                }
                startSelfCheck()
            }
            // MC 共存：音乐屏状态一变就重算一次（不能等下一次 hook 触发）
            if (!coexistHookInstalled) {
                coexistHookInstalled = true
                Coexist.addListener {
                    mainHandler.post { lastSelf?.let { runCatching { handle(it) } } }
                }
            }
        }
        return ok
    }

    /**
     * **主动重算一次前景层**（v1.22.101）。
     *
     * 什么时候必须调 —— **MC 让路后恢复**：`musicCoverOn` 翻 false 的那一瞬间，
     * `Coexist.dispatchChanged` 会先调到这里，而**渲染器还没被 tick 拉起来**
     * ⇒ `SpatialOverlay.active()` / `RasterOverlay.active()` 都是 false、
     * `AliveOverlay.ensure()` 因 `!isRunning()` 直接 `onDisabled()`
     * ⇒ 走「旧链」把整层压成 INVISIBLE（或把系统主体摘掉却没插回我们的）。
     *
     * 之后 tick 重新 `start()`，但 `updateKeyguardElementsVisibility()` 不会因此被系统调用
     * ⇒ **景深主体永远回不来**（用户 2026-11-14：「三者恢复时我们的景深都没恢复」）。
     * 所以三条链的 `startInternal` 末尾各调一次。
     */
    fun refresh() {
        // 从没 hook 过（`lastSelf` 为空）= 系统还没算过可见性，此时层本来就是系统默认态
        val self = lastSelf ?: return
        mainHandler.post {
            runCatching {
                // `aliveDepthOn` 只在 `enabled()` 里算，且带 300ms 缓存 ⇒ 先清掉缓存重算，
                // 否则拿着让路期间的旧值去判断「该接管还是该压掉」。
                cacheAt = 0L
                if (!enabled()) {
                    SpatialOverlay.onDisabled()
                    RasterOverlay.onDisabled()
                    AliveOverlay.onDisabled()
                    return@runCatching
                }
                // ⚠️ **先把层抬回 VISIBLE**：让路退出那一刻 `handle()` 已经走过「旧链」把它
                // 压成 INVISIBLE 了，而 `handle()` 开头就有「不是 VISIBLE 就早退」的闸门
                // ⇒ 不抬的话这里也是空转。抬完让 `handle()` 自己判断该接管还是该压掉。
                val f = self.javaClass.getDeclaredField(FIELD_FOREGROUND)
                    .apply { isAccessible = true }
                (f.get(self) as? View)?.let {
                    if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
                }
                log("渲染器启动后主动重算前景层（MC 让路恢复）")
                handle(self)
            }
        }
    }

    /**
     * 处理前景层：**两种模式**（2026-10-29）。
     *
     * | 模式 | 判据 | 行为 |
     * |---|---|---|
     * | 锁屏景深（新） | [SpatialOverlay.active] —— 渲染器确实按「开关=开」启动了 | 层保留 VISIBLE、只压系统那个 `deducted_image_view`，并把我们的主体 View 插进去 |
     * | 旧行为（默认） | 其余全部情况 | 整层压 INVISIBLE（与加这个开关之前逐字一致） |
     *
     * 只在「系统认为该显示（VISIBLE）」时才动手 —— 系统本来设 4 的场合我们不干预，
     * 亮灭屏 / 退出 / pre-hide 全部走系统原逻辑。
     */
    private fun handle(self: Any) {
        // **先同步 AOD 模式**（2026-10-30，v1.5.78）：只有「与锁屏一致」模式下系统才会
        // 把壁纸 leash 预放大到 1.05，AGSL 主体要跟着；自定义模式下 leash 是 1.0。
        // 放在 visibility 检查之前 —— 层不可见时也要把模式记下来。
        runCatching { syncLeashScale(self) }
        val layer = readForeground(self) ?: return
        lastSelf = self
        // ⚠️ 顺序很关键（2026-11-14）：这一层平时被我们压成 INVISIBLE，
        // 若把 MC 封面判断放在下面的 visibility 闸门之后，封面出现时闸门直接 return
        // ⇒ 永远走不到放行分支，MC 的歌词 / 封面也就永远被压着。**先判封面，再判可见性**。
        //
        // 判据是 `Coexist.musicCoverOn` = **MC 真的把封面推上了锁屏壁纸**
        // （`Main.sCoverMode && !keepsWallpaper()`），不是"有音乐屏的 View"——
        // 后者只要媒体卡 / 岛在就成立，会把整条链白关掉（2026-11-14 用户指出）。
        if (Coexist.musicCoverOn) {
            SpatialOverlay.onDisabled()
            // 光栅：**保留位图**（v1.22.108）—— 让路是高频短时事件，
            // 释放后重新 attach 要读盘 + decode 1200×2670，人像会慢半拍。
            RasterOverlay.onDisabled(keepBitmaps = true)
            AliveOverlay.onDisabled()
            AliveOverlay.detachSystemSubject(layer)
            if (layer.visibility != View.VISIBLE) layer.visibility = View.VISIBLE
            if (logged.putIfAbsent("music", true) == null) {
                log("MC 封面在锁屏上：前景层保持 VISIBLE（不压封面 / 歌词）")
            }
            return
        }
        if (layer.visibility != View.VISIBLE) {
            // 系统可能"先调方法、后设可见"（快速切态时尤其明显）⇒ 隔一拍再试，别永久放弃。
            retryHandleLater(self)
            return
        }

        if (SpatialOverlay.active() || RasterOverlay.active()) {
            // 新链：我们的主体顶替系统主体 ⇒ 把系统那个 ImageView **从父容器摘掉**（问题 2：
            // 设 INVISIBLE 会被系统的 folme onBegin 每帧翻回来），整层留着（它管着我们的
            // View 的显隐、模糊、解锁退场）。
            //
            // ⚠️ 两套壁纸**互斥**：`keyguard_foreground_layer` 只有一个，谁在跑谁占，
            //    另一条链必须当场收口 —— 否则两个主体会叠在同一层上（2026-11-04）。
            if (SpatialOverlay.active()) {
                SpatialOverlay.detachSystemSubject(layer)
                SpatialOverlay.ensure(layer)
                RasterOverlay.onDisabled()
            } else {
                RasterOverlay.detachSystemSubject(layer)
                RasterOverlay.ensure(layer)
                SpatialOverlay.onDisabled()
            }
            logOnceDepth()
            return
        }

        // 旧链：整层压掉
        SpatialOverlay.onDisabled()
        RasterOverlay.onDisabled()
        // Alive 有主体 ⇒ 不压层：**摘掉系统主体 + 自己插一个主体 View**。
        // ⚠️ 与光栅（`RasterOverlay`）逐字同构。不能只"保留层 + 换系统那个 ImageView 的图"——
        //    那张图的 drawable / alpha / visibility 全由 KeyguardPanelViewController 每帧重算，
        //    换上去会被覆盖、设可见会被 folme 翻回去（2026-11-08 踩过一整天）。
        if (aliveDepthOn) {
            AliveOverlay.detachSystemSubject(layer)
            AliveOverlay.ensure(layer)
            logOnceAliveDepth()
            return
        }
        AliveOverlay.onDisabled()
        // **无景深：不压整层**（2026-11-23，issue #6）。
        //
        // 从前这里把整层设 INVISIBLE，用来顺手藏掉「系统主体 + 色带 + 装饰」。但这一层是**公共的**：
        // HyperMusicCover 在「保留壁纸」模式下把歌词层也挂在这里，一压就连歌词一起消失
        // （用户 2026-11-23 复现：Alive 不开锁屏景深 ⇒ 歌词空、不滚动；开景深 ⇒ 正常）。
        // 现在与「有景深」分支统一：只 `detachSystemSubject`（它记了 parent/index/visibility，
        // `onDisabled` 时会插回），层可见性完全交还系统 —— 我们不额外设 VISIBLE / INVISIBLE。
        val detached = SpatialOverlay.detachSystemSubject(layer)
        if (detached) logOnceNoDepth() else logNotDepthLayer()
    }

    /** 反射取 `keyguardForegroundLayer`（层会被锁屏树重建，别缓存）。 */
    private fun readForeground(self: Any): ViewGroup? = runCatching {
        self.javaClass.getDeclaredField(FIELD_FOREGROUND).apply { isAccessible = true }
            .get(self) as? ViewGroup
    }.getOrNull()

    /** `handle` 早退的重试计数（1s 内最多 2 次，避免高频方法刷屏）。 */
    @Volatile private var retryCount = 0

    @Volatile private var lastRetryAt = 0L

    /**
     * 层还不可见时的延迟重试（2026-11-22）。
     *
     * `handle` 只由 `updateKeyguardElementsVisibility` 触发，而系统可能是"先调方法、后设可见"
     * —— 尤其是快速「桌面 → 黑屏 → 锁屏」、锁屏树正在重建的那几帧。早退就永久放弃的话，
     * 主体层会一直不显示（用户 2026-11-22 报）。
     */
    private fun retryHandleLater(self: Any) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRetryAt > 1000L) retryCount = 0
        if (retryCount >= 2) return
        retryCount++
        lastRetryAt = now
        mainHandler.postDelayed({
            runCatching {
                if (readForeground(self)?.visibility == View.VISIBLE) {
                    retryCount = 0
                    handle(self)
                }
            }
        }, 80L)
    }

    /** 2s 自检只启动一次。 */
    private var selfCheckStarted = false

    /**
     * 低频自检（2026-11-22）—— 兜「系统彻底不再调 `updateKeyguardElementsVisibility`」。
     *
     * 判据：锁屏态 + 层 VISIBLE + 当前在跑的链的主体 View 不在层里 ⇒ 补一次 [refresh]。
     * 2s 一次、只比引用，开销可忽略；桌面态 / 层不可见时直接返回。
     */
    private fun startSelfCheck() {
        if (selfCheckStarted) return
        selfCheckStarted = true
        val tick = object : Runnable {
            override fun run() {
                runCatching { selfCheckOnce() }
                mainHandler.postDelayed(this, SELF_CHECK_MS)
            }
        }
        mainHandler.postDelayed(tick, SELF_CHECK_MS)
    }

    private fun selfCheckOnce() {
        val self = lastSelf ?: return
        if (!enabled()) return
        val s = KeyguardContentGate.state
        if (s != KeyguardContentGate.STATE_KEYGUARD && s != KeyguardContentGate.STATE_SHADE_LOCKED) return
        val layer = readForeground(self) ?: return
        if (layer.visibility != View.VISIBLE) return
        val expected = when {
            SpatialOverlay.active() -> SpatialOverlay.attachedView()
            RasterOverlay.active() -> RasterOverlay.attachedView()
            aliveDepthOn -> AliveOverlay.attachedView()
            else -> return
        }
        if (expected != null && expected.parent === layer) return
        log("自检：锁屏态但主体 View 不在前景层 → 重新挂载")
        refresh()
    }

    /**
     * 屏幕是否真的亮着（非 AOD / 非灭屏）。
     *
     * AOD 时 `PowerManager.isInteractive` 是 false —— 这正是「不能把主体抬到 AOD 上」的判据。
     * 拿不到 Context 时按 true 处理（宁可抬，不可漏）。
     */
    private fun screenInteractive(): Boolean = runCatching {
        val app = HookRuntime.appContext() ?: return@runCatching true
        (app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isInteractive
    }.getOrDefault(true)

    /** 无景深：已摘掉系统主体（层保持系统可见性）。 */
    private fun logOnceNoDepth() {
        if (logged.putIfAbsent("nodepth-detach", true) == null) {
            log(
                "无景深：已摘系统主体；前景层保持系统可见性" +
                    "（不再压整层 —— 避免误伤 MC 歌词等第三方 View，issue #6）",
            )
        }
    }

    /** 无景深但这一层里没找到系统主体（不是景深层 / 已被别人处理）—— 只记一次。 */
    private fun logNotDepthLayer() {
        if (logged.putIfAbsent("nodepth-noop", true) == null) {
            log("无景深：这一层里没有系统主体可摘，保持原样（不压整层）")
        }
    }

    /**
     * 反射 `miuiFullAodManager.fullAodEnable()`，据此决定 AGSL 主体的锁屏缩放。
     *
     * `fullAodEnable() = isDeviceSupport && mAodEnable && mAodFullScreenEnable && isFullAodSupport`
     * —— 只有它为 true 时 `linkageViewAnim` 才会调 `doWallpaperScaleAnim`（leash 1.05）。
     * 拿不到就**什么都不改**（保持当前值），不要猜。
     */
    private fun syncLeashScale(self: Any) {
        val full = runCatching {
            val f = self.javaClass.getDeclaredField("miuiFullAodManager").apply { isAccessible = true }
            val mgr = f.get(self) ?: return@runCatching null
            mgr.javaClass.getMethod("fullAodEnable").invoke(mgr) as? Boolean
        }.getOrNull() ?: return
        val scale = if (full) 1.05f else 1f
        // 三套壁纸（空间 / 光栅 / Alive）共用同一层，缩放口径也必须一致 —— 谁在跑谁吃这个值。
        SpatialOverlay.setLeashScale(scale)
        RasterOverlay.setLeashScale(scale)
        AliveOverlay.setLeashScale(scale)
    }

    private fun logOnceDepth() {
        if (logged.putIfAbsent("depth", true) == null) {
            log("锁屏景深开：前景层保留，只压系统主体 + 插入我们自己的主体 View")
        }
    }

    private fun logOnceAliveDepth() {
        if (logged.putIfAbsent("alive-depth", true) == null) {
            log("Alive 景深开：摘掉系统主体 + 插入 Alive 自己的主体 View")
        }
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL 前景层 $msg")
}
