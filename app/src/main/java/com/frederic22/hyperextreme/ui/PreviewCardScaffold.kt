package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 预览卡占屏幕宽 / 高的比例（2026-11-27）。
 *
 * 小米锁屏编辑实测（1200×2670 截图）：卡片 869×1934 px ⇒ **0.7242 / 0.7243**，两者几乎相等
 * —— 因为卡片就是「按屏幕比例缩小的一整屏」。这里取 0.7243，宽高同乘一个系数，
 * 比例天然保持（不写死宽高各自的值，避免四舍五入把比例带偏）。
 */
private const val PREVIEW_CARD_RATIO = 0.7243f

/** 整页背景色，恒定（不跟主题，小米锁屏编辑也是这个值）。 */
private val PREVIEW_BG = Color(0xFF1A1A1A)

/** 标题文字到卡片顶的间距（截图实测 13.7dp）。 */
private val TITLE_CARD_GAP = 14.dp

/**
 * 标题占位盒的高度 = `title4`（18sp）的行高（22dp）。
 *
 * 固定它才能把标题精确钉在「卡片顶 - 14dp」，不用测量回填（回填会让第一帧跳一下）。
 * 文字底部对齐盒子底部 ⇒ 多出来的空白只在文字上方，和卡片的间距永远准。
 */
private val TITLE_BOX_H = 22.dp

/**
 * 顶栏圆钮的浅色玻璃叠色（2026-11-27）。
 *
 * 为什么不用 miuix 的 `puredThinGlass`：那套材质三层都是 `SoftLight` / `HardLight`
 * **混合模式**（`0x05` 黑 + `0x99` 白 SoftLight + `0x66` 白 HardLight），
 * 在 `#1a1a1a` 这种纯色背景上几乎提不亮 ⇒ 圆钮看着完全透明、只剩一圈描边。
 * 这里直接给白 18% 的实色底（落在 `#1a1a1a` 上约 `#3d3d3d`），白图标压得住；
 * 描边与内高光仍由 `GlassIconButton` 自己画。
 */
private val BUTTON_GLASS_FILL = Color.White.copy(alpha = 0.18f)

/** 卡片底 → 底部按钮顶：**恒定 32dp**（2026-11-27 用户要求，不随屏高变化）。 */
private val CARD_ACTION_GAP = 32.dp

/**
 * 「大预览 + 两个顶栏圆钮 + 底部动作」的整页骨架（2026-11-27）。
 *
 * ## 布局（数值全部从截图反推，见 [PREVIEW_CARD_RATIO]）
 *
 * ```
 * 顶栏（自排 Row，52dp / 左右 16dp，只有两个常显玻璃圆钮；不铺底色）
 *   ↕ weight(1)            ← 与下面那个 weight 严格相等
 * 标题
 *   ↕ 14dp
 * 卡片（0.7243 屏高，屏幕比例，圆角 = 系统圆角 × 卡宽比）
 *   ↕ weight(1)
 * actions（底部按钮，居中）
 * 导航栏留白
 * ```
 *
 * 两个 `weight(1f)` 把「顶栏→卡片」与「卡片→按钮」**自动均分** —— 不写死间距，
 * 换任何屏高都成立；卡片高度按比例固定，所以屏高变化时两段留白同步伸缩。
 *
 * ## 状态栏
 *
 * **常隐**（小米这套没有「单击隐藏顶栏」的交互，顶栏一直显示两个圆钮）。
 * 用 `SideEffect` 每帧重申一次：`hide()` 幂等，且能保证第一帧就生效、也不会被别处 show 回来。
 * `onDispose` 恢复 —— 退出页面后二级页的状态栏必须回来。
 *
 * ## 为什么抽成组件
 *
 * 用户 2026-11-27：「任何导入图片后的场景」都要接 —— 光栅 / Alive / 遮罩的「选完图 →
 * 看大图 → 确认」。所以这里只管骨架，卡片内容和底部按钮都由调用方插进来。
 */
@Composable
internal fun PreviewCardScaffold(
    /** 卡片上方那行字（如「显示区域」），15sp 居中。 */
    title: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** 底部按钮区（居中）。放在 `cardContent` 之前，让尾随 lambda 归卡片内容。 */
    actions: @Composable () -> Unit,
    /** 卡片里的内容。**已被裁到圆角**，直接铺满即可。 */
    cardContent: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val screen = rememberScreenSizePx()

    applyStatusBarHidden(view)
    DisposableEffect(Unit) {
        onDispose { setStatusBarVisible(view, true) }
    }

    // ⚠️ **隐藏状态栏 ≠ 顶栏置顶**（2026-11-27 用户明确）：顶栏要和二级页停在同一个
    // 像素位置，只是它上面那条区域空着。`statusBars` 在隐藏后变 0，所以用
    // 「忽略可见性」的那份高度定位。
    val statusBarH: Dp = with(density) {
        WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    }

    val cardWPx = screen.width * PREVIEW_CARD_RATIO
    val cardHPx = screen.height * PREVIEW_CARD_RATIO
    val cardW: Dp = with(density) { cardWPx.toDp() }
    val cardH: Dp = with(density) { cardHPx.toDp() }
    val radiusPx = rememberCardRadiusPx(cardWPx, screen.width.toFloat())
    val radius: Dp = with(density) { radiusPx.toDp() }
    val shape = remember(radius) { SquircleShape(radius) }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(PREVIEW_BG)) {
        // **卡片在全屏里居中（含状态栏区域）** —— 照小米 `CombineEditAdapter.initEditView`：
        // 卡片本身是「整屏视图」，以 `(屏宽/2, 屏高/2)` 为 pivot 缩放 ⇒ 中心永远等于屏幕中心。
        // 所以这里不用 weight 凑，直接算出来。
        val cardTop: Dp = (maxHeight - cardH) / 2

        // ── 录制层（背景 + 标题 + 卡片 + 按钮；**不含顶栏**）──
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = cardTop)
                    .size(cardW, cardH)
                    .clip(shape),
                content = cardContent,
            )

            // 标题：**文字底**贴卡片顶 - 14dp。外层固定 22dp 高、文字底部对齐 ——
            // 不靠测量回填（否则第一帧会跳一下），22dp 正好是 title4（18sp）的行高。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = cardTop - TITLE_CARD_GAP - TITLE_BOX_H)
                    .fillMaxWidth()
                    .height(TITLE_BOX_H),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = title,
                    // 恒定白色（2026-11-27 用户要求）：这页背景永远是 #1a1a1a，不跟主题走。
                    // `title4` = 18sp（body1 是 16sp，用户要求"大一号"）。
                    style = MiuixTheme.textStyles.title4,
                    color = Color.White,
                )
            }

            // 底部按钮：**顶端**贴卡片底 + 24dp（恒定，不随屏高变化）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = cardTop + cardH + CARD_ACTION_GAP)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                actions()
            }
        }

        // ── 顶栏浮层：**自己排**，不用 `SmallTopAppBar` ──
        //
        // 为什么不用：`SmallTopAppBar` 会 provides `LocalGlassHeaderContext`，而
        // `GlassIconButton` 内部是 `surfaceAlpha * headerContext.alpha` —— 顶栏背景设成
        // 透明（alpha = 0）就把圆钮一起乘没了，只剩一圈描边（2026-11-27 用户指出
        // 「正常浅色圆钮除了模糊还会叠一个浅色底色，这里完全看不出来」）。
        // `LocalGlassHeaderContext` 是 internal，置不了空 ⇒ 只能把圆钮移到顶栏外面。
        //
        // 尺寸全部取 `TopAppBarDefaults` 的标准值 ⇒ 按钮位置与 miuix 顶栏逐像素一致。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset(y = statusBarH)
                .height(TopAppBarDefaults.CollapsedHeight)
                .padding(horizontal = TopAppBarDefaults.NavigationIconPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButtonWithFeedback(onClick = onBack, fill = BUTTON_GLASS_FILL) {
                Icon(
                    imageVector = MiuixIcons.ChevronBackward,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            GlassIconButtonWithFeedback(onClick = onConfirm, fill = BUTTON_GLASS_FILL) {
                PreviewCheckIcon()
            }
        }
    }
}

// ------------------------------------------------------------------ 系统栏

/** 找到宿主 Activity（`LocalView.context` 通常就是它，但别赌）。 */
private fun View.hostActivity(): ComponentActivity? {
    var c: Context = context
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/** 每帧重申「状态栏隐藏」—— 幂等，且不会被别处 show 回来。 */
@Composable
private fun applyStatusBarHidden(view: View) {
    val controller = remember(view) {
        view.hostActivity()?.let { WindowCompat.getInsetsController(it.window, view) }
    }
    SideEffect {
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}

private fun setStatusBarVisible(view: View, visible: Boolean) {
    val activity = view.hostActivity() ?: return
    val c = WindowCompat.getInsetsController(activity.window, view)
    if (visible) c.show(WindowInsetsCompat.Type.statusBars())
    else c.hide(WindowInsetsCompat.Type.statusBars())
}

// ------------------------------------------------------------------ 顶栏零件

/**
 * 顶栏玻璃圆钮 + **按压反馈**（2026-11-27 从 `MaskSwapScreen` 提上来，两处共用一份）。
 *
 * ⚠️ miuix `0.9.5-rc01` 的 `GlassIconButton` 漏传 `interactionSource` ⇒ 自带按压反馈不生效。
 * 这里在外层 `pointerInput` **只观察**（`requireUnconsumed = false`，不消费，内层 clickable 照常触发），
 * 按下时叠一块圆形色块。**上游修好后删掉这一层**，判据见 `MaskSwapScreen` 里那段注释。
 *
 * @param backdrop 玻璃采样源；null 时走 miuix 默认（`LocalGlassHeaderContext` / 半透明底）。
 */
@Composable
internal fun GlassIconButtonWithFeedback(
    onClick: () -> Unit,
    backdrop: Backdrop? = null,
    /**
     * 玻璃叠色。**只在 `backdrop == null` 时生效**：
     * `GlassButtonSurface` 有 backdrop 走 `Modifier.glass(...)`（`fill` 整条被忽略），
     * 没 backdrop 才走 `.background(fill.copy(alpha = fill.alpha * opacity))`。
     */
    fill: Color = GlassTopAppBarDefaults.buttonFill(),
    content: @Composable () -> Unit,
) {
    val haptic = rememberClickHaptic()
    val overlay = GlassTopAppBarDefaults.buttonPressedOverlay()
    var pressed by remember { mutableStateOf(false) }
    // 渐变而不是硬切（与 `GlassButtonSurface` 同一条曲线）。
    val overlayAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) GlassMotion.pressDown() else GlassMotion.pressUp(),
        label = "glassIconButtonPressedOverlay",
    )
    Box(
        modifier = Modifier
            .size(GlassTopAppBarDefaults.ButtonSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            },
    ) {
        GlassIconButton(
            onClick = { haptic(); onClick() },
            backdrop = backdrop,
            fill = fill,
        ) { content() }
        // 常驻这一层、只动 alpha —— 硬 `if (pressed)` 会瞬间出现/消失。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = overlayAlpha }
                .clip(CircleShape)
                .background(overlay),
        )
    }
}

/**
 * 浮页那颗自绘 ✓（`RasterGroupSheet.SheetActionIcon` 的同一条路径）。
 *
 * 不用图标库：`MiuixIcons` 里那对 ✕ / ✓ 的笔画粗细差一截，用户 2026-11-03 明确要求自绘。
 * 图形占 60% 画布（原来 40% 比顶栏图标小一圈）。
 */
@Composable
private fun PreviewCheckIcon() {
    // 恒定白色：圆钮是浅色玻璃 + 白图标（2026-11-27 用户要求）。
    val tint = Color.White
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = "完成" },
    ) {
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.54f)
            lineTo(size.width * 0.40f, size.height * 0.74f)
            lineTo(size.width * 0.80f, size.height * 0.28f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
