package com.frederic22.hyperextreme.hook

import android.os.Handler
import android.os.Looper
import android.view.View
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

    /** 同一条日志只打一次（`updateKeyguardElementsVisibility` 调用很频繁）。 */
    private val logged = ConcurrentHashMap<String, Boolean>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 最近一次 hook 到的 KeyguardPanelViewController，供音乐屏状态变化时重算。 */
    @Volatile private var lastSelf: Any? = null

    /** `Coexist` 订阅只装一次。 */
    private var coexistHookInstalled = false

    /**
     * 锁屏/息屏时钟（`hour_view` 在它里面，`minutes` + 日期嵌在它下面的前景容器里）。
     * **只用来判断「面板时钟在不在场」，永远不改它。**
     */
    private const val ID_PANEL_CLOCK = "miui_keyguard_clock_container"

    /** AOD 自己那份联动时钟；只有面板时钟也在场时才压它。 */
    private const val ID_AOD_CLOCK = "linkage_clock_container"

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
     * **AOD 期间只留一份时钟**。
     *
     * 锁屏/息屏的时钟由面板里的 `miui_keyguard_clock_container` 提供：`hour_view` 在它里面，
     * `miui_keyguard_foreground_clock_container`（`minute_view` + 日期）又嵌在它下面 ——
     * **动它就等于动整块时钟**，之前压这里直接导致时钟不全或消失，锁屏也一样。
     *
     * 所以这里只压 **AOD 自己的那份**（`com.miui.aod:id/linkage_clock_container`）。
     * 判据是面板时钟**内部**那块时钟真的亮着（见 [panelShownNow]）：从锁屏直接灭屏时它亮，
     * 两份重叠；从桌面进 AOD、通知 / 点击亮起时它被系统淡到 0，AOD 这份就是唯一的时钟，不能动。
     *
     * ⚠️ **每次 AOD 显示都重新允许压**：通知亮屏 / 点击亮屏都会先报隐藏、再报显示，
     * 面板时钟这一次亮屏里是否在场要单独判断，不能沿用上一轮的结论。
     */
    fun onAodState() {
        allowHide = true
        scheduleSync()
    }

    /**
     * AOD 真的重新显示出来了（`DozeHost.setAodVisibility(true)` / `DozeMachine → DOZE_AOD`）。
     *
     * 用来抵消 [onAodHidden] 的延迟结算：进 AOD 的转场里系统会先报隐藏、再报显示，
     * 这个先后不代表「暂停后重新点亮」，不能因此结束本轮。
     */
    fun onAodShown() {
        hideSeq++
        allowHide = true
        scheduleSync()
    }

    /**
     * AOD 自己把内容藏起来了（`DozeHost.setAodVisibility(false)` / 离开 AOD）：延迟结算。
     *
     * 转场里系统会先报隐藏、再报显示，这个先后不代表真的不显示了；压住 [HIDE_SETTLE_MS]
     * 没再收到显示事件，才还回时钟。之后再显示会重新按判据评估，不会一直压着。
     * 没压过就忽略。
     */
    fun onAodHidden() {
        if (hiddenAodClock == null) return
        val seq = ++hideSeq
        val self = lastSelf ?: return
        mainHandler.postDelayed({
            if (seq != hideSeq) return@postDelayed
            allowHide = false
            log("Alive AOD：AOD 已隐藏 ⇒ 还回时钟")
            runCatching { syncAodDuplicate(self, force = true) }
        }, HIDE_SETTLE_MS)
    }

    /**
     * 亮灭屏时重算（`AliveRenderer` 的屏幕广播调用）。
     *
     * AOD 视图在灭屏后要等一段动画才挂上来，所以灭屏后隔几个点重算。
     */
    fun onScreenState(on: Boolean) {
        val self = lastSelf ?: return
        hideSeq++
        if (on) {
            allowHide = false
            mainHandler.post { runCatching { syncAodDuplicate(self, force = true) } }
            return
        }
        allowHide = true
        scheduleSync()
    }

    /** AOD 时钟可能在转场里晚一步挂上来 ⇒ 几个时间点各算一次。 */
    private fun scheduleSync() {
        val self = lastSelf ?: return
        for (delay in AOD_SYNC_DELAYS) {
            mainHandler.postDelayed({
                runCatching { syncAodDuplicate(lastSelf ?: self, force = true) }
            }, delay)
        }
    }

    private val AOD_SYNC_DELAYS = longArrayOf(0L, 200L, 600L, 1500L, 3000L)

    /** 一次「隐藏」要压住多久没再显示，才算暂停 / 退出（更短的当成转场抖动）。 */
    private const val HIDE_SETTLE_MS = 1200L

    /**
     * 本轮 AOD 是否允许压掉 AOD 时钟。
     *
     * AOD 显示事件 / 灭屏会重新允许；确认隐藏（[HIDE_SETTLE_MS] 内没再显示）或亮屏会结束。
     * 重新允许后仍要看 [panelShownNow]：面板时钟真的在画才压。
     */
    @Volatile private var allowHide = false

    /** 隐藏 / 显示的先后序号：延迟结算发现又显示过就放弃。 */
    @Volatile private var hideSeq = 0

    /** 当前被我们压掉的 AOD 自带时钟 + 它原来的 alpha。只在主线程读写。 */
    private var hiddenAodClock: View? = null
    private var savedAodClockAlpha = 1f

    /** `handle()` 调用很频繁，非强制路径按这个间隔节流。 */
    private var lastSyncAt = 0L
    private const val SYNC_MIN_MS = 400L

    private var panelClockRef: java.lang.ref.WeakReference<View>? = null
    private var aodClockRef: java.lang.ref.WeakReference<View>? = null

    private fun syncAodDuplicate(self: Any, force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastSyncAt < SYNC_MIN_MS) return
        lastSyncAt = now
        val layer = runCatching {
            self.javaClass.getDeclaredField(FIELD_FOREGROUND).apply { isAccessible = true }
                .get(self) as? android.view.ViewGroup
        }.getOrNull() ?: return
        val panel = layer.parent as? android.view.ViewGroup ?: return
        val root = panel.rootView
        val panelClock = panelClock(root)
        val panelShown = panelShownNow(panel, panelClock)
        if (!allowHide || !AliveRenderer.isRunning()) {
            restoreAodClock()
            return
        }
        val aodClock = if (panelShown) findAodClock(root) else null
        if (aodClock == null) {
            restoreAodClock()
            return
        }
        if (hiddenAodClock !== aodClock) {
            restoreAodClock()
            hiddenAodClock = aodClock
            savedAodClockAlpha = aodClock.alpha
            log(
                "Alive AOD：面板时钟在场，压掉 AOD 自带的重复时钟" +
                    "（${idName(aodClock) ?: aodClock.javaClass.simpleName}）",
            )
        }
        if (aodClock.alpha != 0f) aodClock.alpha = 0f
    }

    private fun restoreAodClock() {
        val v = hiddenAodClock ?: return
        hiddenAodClock = null
        if (v.alpha == 0f) v.alpha = savedAodClockAlpha
        log("Alive AOD：还回 AOD 自带时钟（alpha=$savedAodClockAlpha）")
    }

    private fun alive(ref: java.lang.ref.WeakReference<View>?): View? =
        ref?.get()?.takeIf { it.isAttachedToWindow }

    private fun panelClock(root: View): View? {
        alive(panelClockRef)?.let { return it }
        val v = findView(root, 0) { idName(it) == ID_PANEL_CLOCK } ?: return null
        panelClockRef = java.lang.ref.WeakReference(v)
        return v
    }

    /**
     * 面板时钟是否**真的**在这次 AOD 亮屏里。
     *
     * 外层的 `miui_keyguard_clock_container` 任何状态下都是 alpha=1 / isShown，
     * 系统把里面那块时钟（`AllInOneHourClock`）淡到 0 来藏它（通知 / 点击亮起的 AOD
     * 实测 alpha=0.0），所以只能看内部节点的**累计 alpha**。
     */
    private fun panelShownNow(panel: android.view.ViewGroup, clock: View?): Boolean {
        if (!panel.isShown || panel.alpha <= 0f) return false
        if (clock == null || !clock.isShown || clock.alpha <= 0f || clock.width <= 0) return false
        return innerClockAlpha(clock) > 0.02f
    }

    /** 面板时钟子树里最亮的那个可见节点（累计 alpha，父级淡出也算），外层容器不算。 */
    private fun innerClockAlpha(clock: View): Float {
        var best = 0f
        fun walk(node: View, parentAlpha: Float, depth: Int) {
            if (depth > 8) return
            val acc = if (node === clock) parentAlpha else parentAlpha * node.alpha
            if (node !== clock && node.width > 0 && node.height > 0 && acc > best) best = acc
            if (node is android.view.ViewGroup) {
                for (i in 0 until node.childCount) walk(node.getChildAt(i), acc, depth + 1)
            }
        }
        walk(clock, 1f, 0)
        return best
    }

    /** AOD 的 View 树用插件资源，id 名要按各 View 自己的 `resources` 解，不能从窗口根查。 */
    private fun findAodClock(root: View): View? {
        alive(aodClockRef)?.let { return it }
        val v = findView(root, 0) { idName(it) == ID_AOD_CLOCK } ?: return null
        aodClockRef = java.lang.ref.WeakReference(v)
        return v
    }

    private fun findView(v: View, depth: Int, pred: (View) -> Boolean): View? {
        if (depth > 48) return null
        if (v.id != View.NO_ID && v.id != 0 && pred(v)) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                findView(v.getChildAt(i), depth + 1, pred)?.let { return it }
            }
        }
        return null
    }

    private fun idName(v: View): String? =
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

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
        val field = self.javaClass.getDeclaredField(FIELD_FOREGROUND).apply { isAccessible = true }
        val layer = field.get(self) as? android.view.ViewGroup ?: return
        lastSelf = self
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { syncAodDuplicate(self, force = false) }
        } else {
            mainHandler.post { runCatching { syncAodDuplicate(self, force = false) } }
        }
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
        if (layer.visibility != View.VISIBLE) return

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
        val view = layer as View
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.visibility = View.INVISIBLE
            logOnce()
        } else {
            mainHandler.post {
                runCatching {
                    if (view.visibility == View.VISIBLE) {
                        view.visibility = View.INVISIBLE
                        logOnce()
                    }
                }
            }
        }
    }

    private fun logOnce() {
        if (logged.putIfAbsent("hidden", true) == null) {
            log("锁屏景深关：景深前景层已撤（keyguard_foreground_layer → INVISIBLE）")
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
