package com.frederic22.hyperextreme.hook

import android.view.animation.PathInterpolator

/**
 * 留光样式的 AOD 姿态轨道。
 *
 * **动画参数照 openalive 的 `PhotoStyle(1, "leave_light", ...)` + `FrameMotion`**
 * （用户 2026-11-09：「动画参数尽量与 openalive 保持一致，不要参考分支的，分支的动画有问题」）：
 *
 * | 轨道 | 进 AOD | 出 AOD |
 * |---|---|---|
 * | 相纸边长 / 中心（槽 2/4/5） | **517ms，delay 50ms**，`(.28,0,.33,1)` | **450ms**，`(.28,0,.33,1)` |
 * | 倾角（槽 1） | **167ms**，`(.33,0,.67,1)` | **450ms**，`(.28,0,.33,1)` |
 * | 遮罩时间轴（槽 6） | **线性 483ms**（`|Δmicros|/1000`） | 同左 |
 *
 * **位置/大小用我们自己的实测**（用户 2026-11-09：「位置还是我们的」）：
 * 边长 = 屏宽 × 0.70486、中心 = 屏高 0.335、倾角 −5°。
 *
 * ⚠️ 方向：mask 的 **000 帧是小相片、060 帧是铺满全屏的竖幅**。
 * 所以**进 AOD 是倒放**（micros 从 end 收到 0），出 AOD 才是正放。
 *
 * ## 遮罩没就绪时**冻结时间轴**（2026-11-09，照 openalive `FrameSample`）
 *
 * 官方在 `MaskFrames.upload()` 返回 false 时**整帧 return**（几何/照片/遮罩共用一个采样），
 * 时间轴跟着停，所以形状永远和几何同步。我们原来「取不到就沿用上一帧、几何照走」，
 * 真机表现就是相卡变形/停在半大状态/整块消失。
 *
 * 现在的约定：
 * 1. `drawLiuguang` 拿不到 mask ⇒ [hold] 冻结 + 本帧不画；
 * 2. 画成功 ⇒ [settle] 释放冻结并判定结束。
 */
internal object AliveLiuguang {

    // ── AOD 稳态（我们的实测值） ───────────────────────────────────────────
    /** 相纸边长 / 屏宽（官方 `PhotoStyle.scale`）。 */
    const val SCALE_AOD = 0.70486f
    /** 倾角（度，逆时针为正）。 */
    const val ROT_AOD = -5f
    /** 相纸中心在屏幕高度的比例。 */
    const val CENTER_Y_AOD = 0.335f

    // ── 锁屏 / 桌面态 ─────────────────────────────────────────────────────
    /** 相纸边长 / 屏宽：放大到横向铺满（官方 `state(1/2)` 里的 `2.35`）。 */
    const val SCALE_FULL = 2.35f
    const val CENTER_Y_FULL = 0.5f

    // ── 时间（openalive） ────────────────────────────────────────────────
    private const val OPEN_GEOM_MS = 517L
    private const val OPEN_GEOM_DELAY = 50L
    private const val OPEN_ROT_MS = 167L
    private const val CLOSE_MS = 450L
    private const val MASK_MS = 483L

    private val GEOM_CURVE = PathInterpolator(0.28f, 0f, 0.33f, 1f)
    /** 内容缩放（官方槽 3 用 `AccelerateDecelerateInterpolator`，400ms 无延迟）。 */
    private val CONTENT_CURVE = android.view.animation.AccelerateDecelerateInterpolator()
    private const val CONTENT_MS = 400L
    private val ROT_OPEN_CURVE = PathInterpolator(0.33f, 0f, 0.67f, 1f)
    /**
     * 进出 AOD 的整体时长（给 renderer 判「还在转场」用）。
     *
     * ⚠️ 出口取 **483**（= `MASK_MS`）而不是 450：几何 450ms 就走完了，但遮罩时间轴要
     * 483ms 才到 end 帧。取 450 会让最后 33ms 的遮罩被 `settle` 硬跳到 end（差一帧形状）。
     */
    const val OPEN_TOTAL_MS = OPEN_GEOM_MS + OPEN_GEOM_DELAY
    const val CLOSE_TOTAL_MS = MASK_MS

    /**
     * 三界混合时长（官方 `FrameMotion.prepare` 的口径）：
     * `max(PhotoStyle.durations[tr], SceneMotion.blendDuration(tr))`。
     *
     * | tr | `durations[tr]` | `blendDuration` | 取 |
     * |---|---|---|---|
     * | 0（AOD→锁屏） | 567 | 400 | **567** |
     * | 1（锁屏→AOD） | 530 | 400 | **530** |
     * | 2（AOD→桌面） | 567 | 400 | **567** |
     * | 3（桌面→AOD） | 530 | 400 | **530** |
     *
     * **混合永远比几何长**（567 vs 450）—— 相片铺满后混合还要跑 117ms，
     * 这就是「AOD→桌面 到最后还差一点点」的正解（之前三者同长是错的）。
     */
    const val BLEND_OPEN_MS = 567L
    const val BLEND_EXIT_MS = 567L

    /**
     * AOD 退出的起始延迟（官方 `SceneMotion.prepare`：`if (previous == 0 && !interrupted)
     * animation.setStartDelay(150)`）。
     *
     * 让系统自己的解锁动画先起，我们的相片放大晚 150ms 才开始 —— 少了这 150ms，
     * 观感是「我们的动画抢在系统前面」，看着就是「晃」。
     */
    const val EXIT_DELAY_MS = 150L

    /** 采样结果。renderer 每帧读一次，**不要 new**。 */
    class State {
        /** 相纸边长 / 屏宽。 */
        var scale = SCALE_FULL
        /** 倾角（度）。 */
        var rot = 0f
        /** 相纸中心 / 屏高。 */
        var centerY = CENTER_Y_FULL
        /** 遮罩时间轴（微秒）。 */
        var micros = AliveMaskFrames.END_MICROS
        /** 当前遮罩帧号（量化后的偶数帧）。 */
        var frame = AliveMaskFrames.FRAMES - 1
        /** 官方 `values[3]`（contentScale）：AOD 1.0 / 锁屏-桌面 aspect/2.35。 */
        var content = 1f
        /**
         * 官方 `values[7]`（photo alpha）：
         *
         * | 态 | 值 | 含义 |
         * |---|---|---|
         * | AOD / 锁屏 | 1 | 内容框里是**照片** |
         * | 桌面 | 0 | 内容框里是**桌面壁纸** |
         *
         * 桌面→AOD 时 0→1（照片从桌面壁纸里浮出来），AOD→桌面时 1→0。
         * 时长照官方槽 7 的默认值：400ms `AccelerateDecelerateInterpolator`。
         */
        var photoAlpha = 1f

        /**
         * 主体层该用多少 alpha（**已经算好**，View 直接用）。
         *
         * 正常就是 `1 - photoAlpha`（锁屏 1 / AOD 0 / 进出场交叉），
         * 但**「桌面 → AOD」整段必须是 0** —— 桌面上本来没有主体，
         * 从桌面进 AOD 时用户明确不要它出现（旧版是 `computeSubjectAlpha` 里判
         * `aodFromDesktop`，改成 View 现场采样后这条规则搬到这里）。
         */
        var subjectAlpha = 1f
    }

    /** 采样结果（GL 线程专用，`drawLiuguang` / `blend` 共用）。 */
    private val state = State()

    /**
     * **稳态快照**（2026-11-10）。
     *
     * 主体 View 现在在 `onDraw` 里按绘制时刻自己采样（照 `SpatialOverlay` 的做法），
     * 它**不能读 [state]** —— 那是 GL 线程每帧原地改的对象。
     * 所以 `running == false` 时统一从这份快照拷，两边各自持有实例，互不相干。
     */
    private val settled = State()

    /**
     * 轨道参数的锁。
     *
     * 只有一件事会被两个线程同时碰：GL 线程在 `start/settle/snapTo` 里改
     * `startAt / from* / entering`，主线程在 `onDraw` 里读。锁的临界区全是
     * 十几个 float 的加减，每帧每侧各进一次，代价可以忽略。
     */
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var entering = true
    private var startAt = 0L

    /** 冻结时刻（0 = 没冻结）。mask 没解出来时停在这里，等下一帧再试。 */
    private var holdAt = 0L

    /** 本次转场是「桌面 → AOD」：整段不显示主体（桌面上本来就没有）。 */
    @Volatile private var deskEntry = false

    /** 连续冻结了多少帧（素材不可用时防止无限排帧）。 */
    private var holdCount = 0

    /** 冻结上限：约 3 秒（60fps）。超了就认命停笔，别把帧循环挂死。 */
    private const val MAX_HOLD_FRAMES = 180

    // 起点值（被中途打断时用当前值，避免跳变）
    private var fromScale = SCALE_FULL
    private var fromRot = 0f
    private var fromY = CENTER_Y_FULL
    private var fromMicros = AliveMaskFrames.END_MICROS
    private var fromContent = 1f
    private var fromPhotoAlpha = 1f

    /** 本次转场的 photo alpha 目标（AOD/锁屏 1、桌面 0）。 */
    private var toPhotoAlpha = 1f

    /**
     * 屏幕高宽比（`screenH / screenW`）。
     *
     * 锁屏/桌面态的 `content` = `aspect / 2.35`（官方 `state(1/2)` 的
     * `aspect/2.35`）—— 让照片在放大的 quad 里保持与全屏一致的比例。
     */
    @Volatile
    private var aspect = 2.225f

    fun setAspect(a: Float) {
        if (a > 0.01f) aspect = a
    }

    init {
        // 构造时先落一次锁屏稳态（`settled` 的默认值 `photoAlpha = 1` 是 AOD 的，
        // 万一在第一次 `reset()` 之前就有人来采样，会画出一个不该出现的主体）
        applyEnd(false)
        syncSettled()
    }

    fun isRunning() = running

    /**
     * 当前转场还剩多少毫秒（GL 线程；没在跑就是 0）。
     *
     * 用途：`crossFadeOnUnlock` 撞上「AOD→锁屏」转场跑到一半时（偶发，取决于
     * `doWallpaperBlackAnim` 与 `scrim=UNLOCKED` 的间隔），要把换层延时对到
     * **这个转场真正结束之后**，否则两个转场打架 ⇒ 一帧闪。
     */
    fun remainingMs(): Long = synchronized(lock) {
        if (!running) return 0L
        val total = if (entering) OPEN_TOTAL_MS else CLOSE_TOTAL_MS
        val used = (if (holdAt != 0L) holdAt else android.os.SystemClock.elapsedRealtime()) - startAt
        (total - used).coerceAtLeast(0L)
    }

    /** 当前是否被 mask 冻住（诊断用）。 */
    fun isHolding() = holdAt != 0L && holdCount < MAX_HOLD_FRAMES

    /**
     * 进/出 AOD 时调一次（GL 线程）。
     *
     * @param enter true = 进 AOD
     * @param delayMs 起始延迟（官方 AOD 退出是 150ms，让系统解锁动画先起）
     *
     * ## photo alpha 的方向（官方槽 7，2026-11-09 第二次修正）
     *
     * 官方 `state(0)` = 1、`state(1/2)` = `scene==1 && !paired ? 1 : 0`：
     * **paired（相框里另一张照片）时，锁屏和桌面都是 0**。也就是说「AOD 图 ≠ 目标界的图」
     * 时，退出是让**内容框里的 AOD 图淡成目标界的图**（1→0），不是保持 1。
     *
     * 我们允许 AOD 单独选图（`aodSrc`），所以按 paired 的语义走：
     * * 进入：0 → 1（内容框里从起点界的图淡成 AOD 图）；
     * * 退出：1 → 0（内容框里从 AOD 图淡成终点界的图）—— 回锁屏、回桌面都是 0。
     *
     * AOD 图与锁屏图相同时（「AOD 与锁屏一致」）两边同图，alpha 怎么走都无感。
     */
    fun start(enter: Boolean, now: Long, delayMs: Long = 0L, fromDesktop: Boolean = false) {
        var pfFrom = 0
        var pfStep = 0
        synchronized(lock) {
            deskEntry = enter && fromDesktop
            fromScale = state.scale
            fromRot = state.rot
            fromY = state.centerY
            fromMicros = state.micros
            fromContent = state.content
            if (enter) {
                // 进入时起点必须落成 0：上一次退出后如果停在 1，这一进来就是「AOD 图硬切」
                state.photoAlpha = 0f
            }
            fromPhotoAlpha = state.photoAlpha
            toPhotoAlpha = if (enter) 1f else 0f
            entering = enter
            startAt = now + delayMs
            holdAt = 0L
            holdCount = 0
            running = true
            pfFrom = if (enter) AliveMaskFrames.FRAMES - 1 else AliveMaskFrames.indexOf(fromMicros)
            pfStep = if (enter) -2 else 2
        }
        // 立刻预取起点附近的几帧（方向 = 播放方向）。
        // ⚠️ 放在锁外：`AliveMaskFrames` 自己有锁，别嵌套
        AliveMaskFrames.prefetch(pfFrom, 8, pfStep)
    }

    /**
     * 强制归位到某一态（`animate = false` 的瞬间切态、关闭壁纸、切样式）。
     *
     * @param desktop true = 桌面稳态（photo alpha 0，内容框里是桌面壁纸）。桌面态其实
     *   不画留光层，但「从桌面进 AOD」需要一个正确的起点，否则照片会从 alpha 1 硬切进来。
     */
    fun snapTo(aod: Boolean, desktop: Boolean = false) = synchronized(lock) {
        running = false
        deskEntry = false
        holdAt = 0L
        holdCount = 0
        applyEnd(aod)
        if (desktop) {
            state.photoAlpha = 0f
            // 桌面稳态**没有主体**：`snapTo(desktop = true)` 是「桌面 → AOD」的起点，
            // 落成 1 的话那一瞬间会闪一张全亮的人像（真机：「桌面到 AOD 闪一下主体层」）
            state.subjectAlpha = 0f
        }
        syncSettled()
        fromScale = state.scale
        fromRot = state.rot
        fromY = state.centerY
        fromMicros = state.micros
        fromContent = state.content
        fromPhotoAlpha = state.photoAlpha
    }

    /** 归位到锁屏/桌面态。 */
    fun reset() = snapTo(false)

    private fun applyEnd(aod: Boolean) {
        state.scale = if (aod) SCALE_AOD else SCALE_FULL
        state.rot = if (aod) ROT_AOD else 0f
        state.centerY = if (aod) CENTER_Y_AOD else CENTER_Y_FULL
        state.micros = if (aod) 0L else AliveMaskFrames.END_MICROS
        state.frame = AliveMaskFrames.indexOf(state.micros)
        state.content = if (aod) 1f else aspect / SCALE_FULL
        // 照片 alpha（官方槽 7，`u_photo_alpha`）：**AOD = 1 / 锁屏 = 0**（paired 语义，
        // 两端终态都是「对方那张图」）。这里必须按状态落值 —— 一律写 1 的话，
        // `reset()`（渲染器启动）之后锁屏稳态的 photoAlpha 会停在 1：
        // 背景内容框里显示的是 AOD 图，而主体层的 `1 - photoAlpha` 又变成 0（人不见了）。
        state.photoAlpha = if (aod) 1f else 0f
        state.subjectAlpha = if (aod) 0f else 1f
    }

    /** 有效时间（被冻结时停在 [holdAt]）。 */
    private fun elapsed(now: Long): Long =
        (if (holdAt != 0L) holdAt else now) - startAt

    /**
     * 遮罩此刻取不到 —— **冻结时间轴**（GL 线程）。
     *
     * 只记录第一次的冻结时刻：连续多帧取不到时时间不会累积流逝。
     * 稳态（`running == false`）也接受：AOD 里 cache 被淘汰后要能补画。
     */
    fun hold(now: Long) = synchronized(lock) {
        if (holdAt == 0L) holdAt = now
        holdCount++
    }

    /**
     * 这一帧真的画出去了（GL 线程）：释放冻结、推进时间轴、够了就收工。
     */
    fun settle(now: Long) = synchronized(lock) {
        holdCount = 0
        if (holdAt != 0L) {
            startAt += now - holdAt
            holdAt = 0L
        }
        if (!running) return@synchronized
        val total = if (entering) OPEN_TOTAL_MS else CLOSE_TOTAL_MS
        if (now - startAt >= total) {
            applyEnd(entering)
            // 退出终态可能是桌面（照片 alpha 0），applyEnd 只认 AOD/锁屏
            if (!entering) state.photoAlpha = toPhotoAlpha
            running = false
            syncSettled()
        }
    }

    /**
     * 采样一帧（GL 线程）。`now` = `SystemClock.elapsedRealtime()`。
     *
     * ⚠️ 这里**不自己结束** `running`：结束要等 mask 真的画出去（[settle]）。
     * 否则最后一帧可能还停在中间帧号上，AOD 里就显示一张「半大」的相卡。
     */
    fun sample(now: Long): State = sampleInto(now, state, prefetch = true)

    /**
     * 采样一帧到 `out`（**任意线程**，2026-11-10）。
     *
     * GL 线程走 [sample]（写共享的 `state`）；主体 View 在 `onDraw` 里走这个重载，
     * 写自己的实例 —— 也就是**在绘制的这一刻才采样**，和 `SpatialOverlay` 一个套路。
     * 这样主体和背景的「采样 → 上屏」延迟都是 1 帧，相位差就没了；
     * 而推值（把 GL 那一帧算好的值送过去）恒差 1~2 帧。
     *
     * @param prefetch 是否顺手预取遮罩帧。GL 侧 true（它负责喂缓存），View 侧 false。
     */
    fun sampleInto(now: Long, out: State, prefetch: Boolean = false): State {
        var pfFrame = -1
        var pfStep = 0
        synchronized(lock) {
            if (!running) {
                // 稳态：从快照拷（绝不读 GL 的 `state`）
                out.scale = settled.scale
                out.rot = settled.rot
                out.centerY = settled.centerY
                out.micros = settled.micros
                out.frame = settled.frame
                out.content = settled.content
                out.photoAlpha = settled.photoAlpha
                out.subjectAlpha = settled.subjectAlpha
                return out
            }
            val t = elapsed(now).coerceAtLeast(0L)

            // 几何：进 AOD 带 50ms 延迟；出 AOD 立即开始
            val geomMs = if (entering) OPEN_GEOM_MS else CLOSE_MS
            val delay = if (entering) OPEN_GEOM_DELAY else 0L
            val pg = GEOM_CURVE.getInterpolation(
                ((t - delay).toFloat() / geomMs).coerceIn(0f, 1f),
            )
            val toScale = if (entering) SCALE_AOD else SCALE_FULL
            val toY = if (entering) CENTER_Y_AOD else CENTER_Y_FULL
            // 内容缩放（官方槽 3）：AOD 1.0 ↔ 锁屏-桌面 aspect/2.35，400ms 缓入缓出
            val pc = CONTENT_CURVE.getInterpolation((t.toFloat() / CONTENT_MS).coerceIn(0f, 1f))
            val toContent = if (entering) 1f else aspect / SCALE_FULL
            out.content = fromContent + (toContent - fromContent) * pc
            // 照片 alpha（官方槽 7）：AOD/锁屏 1 ↔ 桌面 0，同样 400ms 缓入缓出
            out.photoAlpha = fromPhotoAlpha + (toPhotoAlpha - fromPhotoAlpha) * pc
            // 桌面 → AOD：整段不出现主体（桌面上本来就没有，不想要它「闪出来」）
            out.subjectAlpha = if (deskEntry) 0f else (1f - out.photoAlpha).coerceIn(0f, 1f)
            out.scale = fromScale + (toScale - fromScale) * pg
            out.centerY = fromY + (toY - fromY) * pg

            // 倾角：进 AOD 只用 167ms（很快立起来），出 AOD 跟几何同 450ms
            val rotMs = if (entering) OPEN_ROT_MS else CLOSE_MS
            val pr = if (entering) {
                ROT_OPEN_CURVE.getInterpolation((t.toFloat() / rotMs).coerceIn(0f, 1f))
            } else {
                pg
            }
            val toRot = if (entering) ROT_AOD else 0f
            out.rot = fromRot + (toRot - fromRot) * pr

            // 遮罩时间轴：**线性**（官方槽 6 固定 LINEAR，时长 |Δ|/1000）
            val pm = (t.toFloat() / MASK_MS).coerceIn(0f, 1f)
            val toMicros = if (entering) 0L else AliveMaskFrames.END_MICROS
            out.micros = (fromMicros + (toMicros - fromMicros) * pm).toLong()

            out.frame = AliveMaskFrames.indexOf(out.micros)
            if (prefetch) {
                pfFrame = out.frame
                pfStep = if (entering) -2 else 2
            }
        }
        // 预取放在锁外：`AliveMaskFrames` 自己有一把锁，别嵌套
        if (pfFrame >= 0) AliveMaskFrames.prefetch(pfFrame, 6, pfStep)
        return out
    }

    /** 把 `state` 抄进 `settled`（`applyEnd` 之后调；调用方必须持锁）。 */
    private fun syncSettled() {
        settled.scale = state.scale
        settled.rot = state.rot
        settled.centerY = state.centerY
        settled.micros = state.micros
        settled.frame = state.frame
        settled.content = state.content
        settled.photoAlpha = state.photoAlpha
        settled.subjectAlpha = state.subjectAlpha
    }

    /** 供 `drawScene` 判「当前该不该画留光 AOD 层」。 */
    fun isAodSide(mode: Int): Boolean = running || mode == 0
}
