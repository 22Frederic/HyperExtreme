package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.R
import com.frederic22.hyperextreme.data.AodPackageName
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import com.frederic22.hyperextreme.data.MiuiHomePackageName
import com.frederic22.hyperextreme.data.MiuiWallpaperPackageName
import com.frederic22.hyperextreme.data.RasterStore
import com.frederic22.hyperextreme.data.SystemUiPackageName
import com.frederic22.hyperextreme.hook.HookRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 主页

/**
 * 主页。
 *
 * 2026-10-29：状态卡（[BigStatusCard]）从关于页**搬回主页最上面**（用户指定）——
 * 「模块有没有激活」是打开 App 第一眼要确认的事，放在关于页要多点一次才看得到。
 *
 * 2026-11-08：顺序改为 **状态卡 → 「未检测到Root」卡（条件）→ 「当前运行」卡（条件）→ 设备信息卡**。
 * Root 卡从「标题 + 一个大按钮」改成选项行（`BasicComponent`：标题 / summary / 右侧箭头，
 * 整行点击刷新）；新增「当前运行」卡，点它直接进对应二级页。
 *
 * 同一天删掉了底部的「去选图替换」按钮：二级页入口在「功能」页签里已经有了，这里是重复路径。
 */
@Composable
internal fun HomeTab(state: MaskUiState, onOpen: (DetailPage) -> Unit) {
    val context = state.context
    val activated = state.activated
    val haptic = rememberClickHaptic()

    // 「当前运行」判据与冲突弹窗共用 [WallpaperConflict]（2026-11-08）——
    // 服务模式要查 `WallpaperManager`，所以在**进前台**时算一次，别塞进重组。
    var running by remember { mutableStateOf(WallpaperConflict.current(context, state)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                running = WallpaperConflict.current(context, state)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 视差是 State：在本页关掉它时不会走 ON_RESUME，单独跟一下。
    LaunchedEffect(state.parallaxActive) {
        running = WallpaperConflict.current(context, state)
    }

    TabColumn {
        // 模块激活状态：**永远是第一张**（即使下面的 Root 卡也在）。
        BigStatusCard(
            activated = state.activated,
            hasRoot = state.rootAvailable,
            version = appVersion(context),
            settled = state.activationSettled,
        )

        // 记录里是"无 root"时给这张卡 + 重测入口；记录是"有 root"时这张卡不出现，
        // 只有「立即替换」跑完发现 su 不可用才会把记录改回"无 root"。
        //
        // 配色 2026-09-29 改成**橙**（原来是 `errorContainer / onErrorContainer` 那套红）：
        // 与状态卡的"无 Root"档**同一组色值**（`StatusCardAmber*`，见 MaskSwapScreen），
        // 两张卡放一起时是同一种"缺东西但还能修"的语气，而不是"致命错误"。
        //
        // 2026-11-08：卡内排版改成**选项行**（用户要求）—— 标题 / summary 的字号与颜色
        // 走 `BasicComponent` 那套规范，右侧加箭头，原来的「刷新 Root」按钮删掉、整行点击。
        if (activated && state.rootAvailable == false) {
            // 卡面是固定色值、不跟动态取色，所以深浅色得自己判（和关于页状态卡一个套路）。
            val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                insideMargin = PaddingValues(0.dp),
                // 文字颜色**刻意不显式指定**：让它走 Card 默认的 contentColor ——
                // 关于页状态卡那行「模块未激活」也是不指定，两边才真的一致（用户要求"颜色保持一致"）。
                colors = CardDefaults.defaultColors(
                    color = if (dark) StatusCardAmberDark else StatusCardAmberLight,
                ),
            ) {
                BasicComponent(
                    title = "未检测到Root",
                    summary = "点此以刷新",
                    endActions = { LinkArrow() },
                    onClick = { haptic(); state.refreshRoot() },
                )
            }
        }

        // 「当前运行」卡（2026-11-08）：四链互斥，正常最多一条；没有就整卡不出现。
        running?.let { current ->
            RunningWallpaperCard(current = current, onOpen = onOpen)
        }

        // 设备信息卡。
        //
        // 2026-10-29：① 砍掉「模块版本」「目标应用」两项；
        // ② 排版改用官方 `BasicComponent` —— 标题 `headline1`(17sp) 就是设置项正文的字号，
        //    summary `body2`(14sp) 就是设置项副标题的字号，行高（≥56dp）与 16dp 内边距
        //    全是官方那套，和设置页的选项逐像素一致（原来的自绘 `InfoRow` 已删除）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(title = "设备名称", summary = deviceName(context))
            BasicComponent(title = "设备型号", summary = Build.MODEL)
            BasicComponent(title = "HyperOS 版本", summary = osVersionName())
            BasicComponent(title = "Android 版本", summary = androidVersionName())
        }

        // 捐赠卡（2026-10-29）：整行可点 → 爱发电主页。
        // 排版复用 [LinkRow]（标题 + 右侧箭头，与关于页「开源代码许可」那些行同款）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            LinkRow(title = "通过捐赠支持开发者", summary = null) {
                openUrl(context, DonateUrl)
            }
        }
    }
}

// ------------------------------------------------------------------ 功能（一级：四张入口卡片）

/**
 * 一级功能页：**一张卡片一个作用域应用**（图标 + 名称 + 包名 + 右箭头），点进去是二级页。
 *
 * 2026-09-29 改版前，这个位置直接就是"选图 + 立即替换"那一整页；
 * 现在那些内容搬进了二级页 [MaskReplaceDetail]，一级只留这几张入口卡片。
 */
@Composable
internal fun FunctionHome(onOpen: (DetailPage) -> Unit) {
    TabColumn {
        AppEntryCard(
            packageName = AodPackageName,
            title = "锁屏遮罩替换",
            onClick = { onOpen(DetailPage.MaskReplace) },
        )
        // 「视差壁纸」（2026-10-31 由「视差壁纸效果」改名）：与「锁屏遮罩替换」同构 ——
        // 同一套系统转场 / 二级页模板。二级页顶栏标题见 `MaskDetailActivity` 的 when。
        AppEntryCard(
            packageName = AodPackageName,
            title = "视差壁纸",
            onClick = { onOpen(DetailPage.ParallaxBg) },
        )
        // 「空间壁纸」（2026-10-22）：**与视差完全独立的另一条链** ——
        // 视差是「两层平面各自按传感器平移」；空间壁纸是「深度图分 N 层 + 真 3D 透视转动」。
        // 两者共用「用背景图」这个前提，但状态、开关、渲染管线**互不影响**，
        // 关掉其中任何一个都不影响另一个。入口紧挨着视差，方便对比。
        AppEntryCard(
            // 2026-10-29：图标/包名改成**壁纸服务**本身（空间壁纸是靠改壁纸层实现的，
            // 挂在 AOD 名下不准确）。
            packageName = MiuiWallpaperPackageName,
            title = "空间壁纸",
            onClick = { onOpen(DetailPage.SpatialWallpaper) },
        )
        // 「光栅壁纸」（2026-11-03）：转动手机在 2~4 张图之间渐变过渡 + 主体景深。
        // 渲染层计划复用空间壁纸那套 GL；图标/包名与空间壁纸保持一致（同样是壁纸层的事）。
        // 当前二级页只是**主体验证**（系统 AI 能不能给遮罩），还不是完整功能。
        AppEntryCard(
            packageName = MiuiWallpaperPackageName,
            title = "光栅壁纸",
            onClick = { onOpen(DetailPage.RasterWallpaper) },
        )
        // 「Alive壁纸」（2026-11-07）：照片透镜「启航」—— AOD 里是自转的小球，
        // 锁屏 / 桌面铺满原图，三态之间连续转场。与光栅同列（同样是壁纸层的事），
        // 但只有 LSPosed 一种挂载方式（AOD 动画必须挂在 SystemUI 的 leash 上）。
        AppEntryCard(
            packageName = MiuiWallpaperPackageName,
            title = "Alive壁纸",
            onClick = { onOpen(DetailPage.AliveWallpaper) },
        )
        AppEntryCard(
            packageName = SystemUiPackageName,
            title = "系统界面",
            onClick = { onOpen(DetailPage.SystemUi) },
        )
        // 「系统桌面」（2026-10-16）：与上面几张同构 —— 「多任务」页的入口。
        // 触发信号由桌面自己发给 SystemUI，模块**不往桌面进程装载任何东西**；
        // 生效逻辑（多任务出现 → 藏状态栏内容、退出还原）见 hook/RecentsStatusBar。
        AppEntryCard(
            packageName = MiuiHomePackageName,
            title = "系统桌面",
            onClick = { onOpen(DetailPage.Desktop) },
        )
    }
}

/**
 * 一级页的「应用入口」卡片。
 *
 * 图标走**系统里真实的那一个**（`PackageManager.getApplicationIcon`），和桌面 / 设置里看到的一致，
 * 不另外自带图。取不到（理论上不会）就只显示文字，不留空位。
 */
@Composable
private fun AppEntryCard(packageName: String, title: String, onClick: () -> Unit) {
    val haptic = rememberClickHaptic()
    val icon = rememberAppIcon(packageName)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 卡片里只有这一行，所以整行可点。
                .clickable { haptic(); onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MiuixTheme.textStyles.headline1)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 箭头与**关于页的 [LinkArrow] 严格一致**：`Image` + `size(16.dp)` + 同一个 tint。
            // 原来这里是 miuix 的 `Icon`（默认 24dp），卡片右侧那个箭头明显偏大（2026-09-29 用户指出）。
            // 两处共用同一份写法，别再各写一套。
            Image(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 主页的「当前运行」卡（2026-11-08）。
 *
 * 只显示**真的在跑**的那一条（判据来自 [WallpaperConflict.current]，与冲突弹窗同一份）；
 * 没有运行时调用方根本不会组合它。
 *
 * 排版照功能页的 [AppEntryCard]：系统图标 44dp / 圆角 10dp → 标题 `headline1` →
 * summary `body2`（`onSurfaceVariantSummary`）→ 右侧 16dp 箭头，整行可点。
 *
 * **summary 只在双模式的链上出现**：视差 / Alive 只有 LSPosed 一条，写了反而像
 * 在暗示可以换挂载方式。服务模式的措辞是「系统壁纸服务」，不带「（免Root）」。
 */
@Composable
private fun RunningWallpaperCard(current: RunningWallpaper, onOpen: (DetailPage) -> Unit) {
    val haptic = rememberClickHaptic()
    // 图标用功能页那张卡同一个系统图标：视差挂 aod，其余三条挂壁纸服务。
    val pkg = when (current.chain) {
        WallpaperChain.PARALLAX -> AodPackageName
        else -> MiuiWallpaperPackageName
    }
    val icon = rememberAppIcon(pkg)
    val page = when (current.chain) {
        WallpaperChain.PARALLAX -> DetailPage.ParallaxBg
        WallpaperChain.SPATIAL -> DetailPage.SpatialWallpaper
        WallpaperChain.RASTER -> DetailPage.RasterWallpaper
        WallpaperChain.ALIVE -> DetailPage.AliveWallpaper
    }
    val summary = when (current.chain) {
        WallpaperChain.SPATIAL, WallpaperChain.RASTER ->
            "挂载模式（${if (current.mode == WallpaperMode.SERVICE) "系统壁纸服务" else "LSPosed"}）"
        else -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { haptic(); onOpen(page) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "当前应用（${current.label}）", style = MiuixTheme.textStyles.headline1)
                if (summary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Image(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 取系统里那个应用的真实图标；拿不到就返回 null（卡片只显示文字）。 */
@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmapOrNull()
                ?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * `Drawable` → `Bitmap`。
 *
 * 不用 `androidx.core` 那个 `toBitmap()`：本工程没有显式依赖 core-ktx，而这点活儿自己画一遍就够了
 * （自适应图标也能按 bounds 正确渲染出来）。
 */
private fun Drawable.toBitmapOrNull(): Bitmap? = runCatching {
    if (this is BitmapDrawable) {
        bitmap?.let { return@runCatching it }
    }
    val width = intrinsicWidth.takeIf { it > 0 } ?: FallbackIconSize
    val height = intrinsicHeight.takeIf { it > 0 } ?: FallbackIconSize
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, width, height)
    draw(Canvas(result))
    result
}.getOrNull()

/** 图标拿不到内在尺寸时按这个边长画（兜底；这两个系统应用实际上都有尺寸）。 */
private const val FallbackIconSize = 144

// ------------------------------------------------------------------ 功能（二级：锁屏遮罩替换）

/**
 * 二级页「锁屏遮罩替换」的全部内容（2026-11-07 两轮改版后的最终形态）。
 *
 * ## 布局
 *
 * 两张**并排**的竖版卡，视觉与一级页卡片流一致（squircle 圆角 = 卡宽 × [CARD_RADIUS_RATIO]、
 * 1dp 纯黑 10% 描边、图铺满裁切）：卡宽 = 屏宽 × [LOCK_CARD_WIDTH_RATIO]（0.39）、
 * 间距 [LOCK_CARD_GAP]（16dp）。**标题在卡片下方**（2026-11-09 从卡上移到卡下），标题与卡之间 12dp、
 * 字号与颜色同选项 `summary`（`body2` + `onSurfaceVariantSummary`）。
 *
 * | 位置 | 卡片 | 交互 |
 * | --- | --- | --- |
 * | 左 | 「当前壁纸」—— hook 从 AOD 进程推回来的锁屏壁纸缩略图 | 只读 |
 * | 右 | 「主体或遮罩」—— 候选图；没有图时是加号卡 | 点卡选图；右上角删除钮清空 |
 *
 * 卡下**单个**「立即替换」按钮（主题色；宽 = 卡片流的 0.45 × 5/6 = 0.375 屏宽，与光栅页逐像素同宽；
 * 整行居中）。原「清空选择」已删 —— 清空改由右卡右上角的删除钮负责（有图才出现，带 0.15s
 * 缩放 + 淡入淡出）。左卡换图时走 0.15s 交叉过渡。
 *
 * ⚠️ **删除钮不参与排版**：它挂在尺寸写死的卡片 Box 里、用 `offset` 探出卡角，
 * `offset` 只改绘制位置、不改测量尺寸 ⇒ 两张卡 + 间距算居中时**天然不含**那半颗钮，
 * 左右两卡不会因为多了一个钮而整体偏左（用户 2026-11-07 明确要求）。
 *
 * ## 与旧版的区别
 *
 * 旧版是「主体」「遮罩」两张卡**各自**选图、卡内各带「选择图片 / 清空选择」两个按钮，两者互斥。
 * 现在**只有一个槽**：选一张图，由 [RasterStore.probeKind] 自动判定它是主体还是遮罩
 * （判据与光栅的组合编辑完全同一套），判定不出来就提示、不导入。
 *
 * **刻意不用 [TabColumn]**：那里带着 120dp 的底栏留白，而二级页是**盖住底栏**的全屏页，
 * 留那么多空白没有意义。
 */
@Composable
internal fun MaskReplaceDetail(state: MaskUiState, onPick: () -> Unit) {
    val haptic = rememberClickHaptic()
    val ctx = LocalContext.current
    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    val cardW: Dp = with(density) { (screen.width * LOCK_CARD_WIDTH_RATIO).toDp() }
    // 卡高 = 卡宽 × 屏高 / 屏宽（与卡片流同一个比例：卡片是"屏幕的一块"）。
    val cardH: Dp = cardW * screen.height / screen.width
    val radius: Dp = cardW * CARD_RADIUS_RATIO
    val surface = rememberCardSurface()
    // 按钮宽度按**卡片流的卡宽**（[CARD_WIDTH_RATIO] = 0.45）算 5/6 —— 与本页两张 0.39 的卡无关：
    // 用户 2026-11-07 要求"跟光栅页一样宽"，光栅页就是 0.45 × 5/6 = 0.375 屏宽。
    val buttonW: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO * 5f / 6f).toDp() }
    val thumbCache = remember(ctx) { File(ctx.cacheDir, MaskProvider.LOCK_THUMB_NAME) }

    // 只有一个槽：主体或遮罩，谁在就显示谁。
    val role = MaskRole.selectable.firstOrNull { state.sizes[it] != null }
    val bitmap = role?.let { state.previews[it] }
    val thumb = rememberLockThumb()
    val busy = state.busy

    // 缩略图刷新（2026-11-07 第二轮，用户报「换了锁屏壁纸，左卡不刷新」）：
    // 旧逻辑只在**缓存文件不存在**时才请一次 ⇒ 缓存一落盘，之后换多少次壁纸都看旧的。
    // 现在两个时机都请：① 进页面；② 回到前台（用户常见路径 = 去系统设置换壁纸再切回来）。
    // 两次都过 [LOCK_THUMB_REFRESH_MIN_MS] 节流 —— 这条请求要么唤醒宿主进程、要么在宿主里
    // 重新解码 + JPEG 编码，进出页面会连触两三次，没必要。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) requestLockThumb(state, thumbCache.isFile)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { requestLockThumb(state, thumbCache.isFile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 一、两张并排卡。所有按钮都先过 [MaskUiState.requireActivated]。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(LOCK_CARD_GAP)) {
                // 左：当前壁纸（只读，没有删除钮）。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(cardW, cardH)
                            .squircleClip(radius)
                            .background(surface)
                            .squircleStroke({ CARD_STROKE_WIDTH }, { CARD_STROKE }, radius),
                    ) {
                        // 换壁纸时**交叉过渡** 0.15s（用户 2026-11-07）：旧图保持不动、新图淡入盖上去。
                        // ⚠️ 这里用裸 `Image` 而不是 [CardImage]：后者自带一次 0.15s 渐显，
                        //    套在 Crossfade 里会变成"先淡出到白底再淡入"，看着像闪一下。
                        Crossfade(
                            targetState = thumb,
                            animationSpec = tween(durationMillis = LOCK_THUMB_CROSSFADE_MS),
                            modifier = Modifier.fillMaxSize(),
                        ) { frame ->
                            if (frame != null) {
                                Image(
                                    bitmap = frame.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                    LockCardTitle(text = "当前壁纸", width = cardW)
                }
                // 右：主体或遮罩。空态是加号卡（点它选图），有图时整张卡也是选图入口，
                // 右上角挂删除钮 —— 视觉与交互全在 [PickCard] 里（与视差页共用同一份）。
                // ⚠️ 判据用 `role`（= 有没有候选）而不是 `bitmap`：预览是异步解码的，
                // 用 bitmap 判会在进来的头一两帧闪成加号卡，看着像"没选过图"。
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PickCard(
                        cardW = cardW,
                        cardH = cardH,
                        radius = radius,
                        surface = surface,
                        hasImage = role != null,
                        bitmap = bitmap,
                        enabled = !busy,
                        onPick = {
                            if (state.requireActivated()) {
                                haptic()
                                onPick()
                            }
                        },
                        onClear = {
                            // 处理中照样显示钮（与卡片流一致），只是点了不响应。
                            if (!busy && state.requireActivated()) {
                                haptic()
                                state.clearRoles()
                            }
                        },
                    )
                    LockCardTitle(text = "主体或遮罩", width = cardW)
                }
            }
        }

        // 二、单个「立即替换」（用户 2026-11-07 第二轮：砍掉「清空选择」、只留这一个并居中）。
        // 宽度沿用卡宽 5/6（与空间页同一个比例），整行居中。
        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 提示色主按钮。走 requestReplace（没选图时的兜底提示也在里面）。
            // `buttonColors()` 默认是**次级灰**；这个按钮是唯一的主动作，用户 2026-11-07 要求用主题色。
            Button(
                onClick = {
                    haptic()
                    if (state.requireActivated() && !busy) state.requestReplace()
                },
                modifier = Modifier.width(buttonW),
                enabled = role != null && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = if (busy) "处理中…" else "立即替换",
                    style = MiuixTheme.textStyles.button,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 三、「替换行为」。2026-09-29 从设置页整张卡片搬过来（用户指定，标题不变）；
        // 2026-11-07 按用户要求**删掉了 SmallTitle**，间距改由上面的 Spacer 给。
        // 同卡片里另一个开关（详细过程写入LSPosed日志）并进了设置页的「测试」，不在这边。
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「替换前备份原文件」开关 2026-10-15 随备份/还原整组逻辑删除（用户要求"删干净"）。
            // 「替换后自动灭亮屏一次」（2026-10-04 用户要求）：
            // 灭亮屏从"固定行为"改成可选（注入改走 hook 后，刷新已经由切
            // `wallpaper_effect_type_2` 完成，灭亮屏只是"便于快速查看效果"）。
            SwitchRow(
                title = "替换后自动灭亮屏一次",
                summary = "便于快速查看效果",
                checked = state.autoScreenFlash,
                onCheckedChange = { state.applyAutoScreenFlash(it) },
            )
            // 「解除景深遮挡限制」（2026-10-01）。和"选图 → 替换"主干无关，只管**编辑器里
            // "主体遮挡时间过多"那条判定**，默认关。
            SwitchRow(
                title = "解除景深遮挡限制",
                summary = "适用于主体被判定遮挡时间过多的情况",
                checked = state.depthOcclusionRemoved,
                onCheckedChange = { state.applyDepthOcclusionRemoved(it) },
            )
        }
    }
}

/** 锁屏遮罩页两张卡的卡宽 ÷ 屏宽（2026-11-07 第二轮用户拍板 0.39）。 */
private const val LOCK_CARD_WIDTH_RATIO = 0.39f

/** 两张卡的间距（2026-11-07 第二轮用户拍板 16dp）。 */
private val LOCK_CARD_GAP = 16.dp

/** 上一次请 hook 重推缩略图的时刻（节流，见 [requestLockThumb]）。 */
private var LockThumbRequestAt = 0L

/** 两次「请 hook 重推缩略图」之间的最小间隔（毫秒）。 */
private const val LOCK_THUMB_REFRESH_MIN_MS = 2_000L

/** 「当前壁纸」换图时的交叉过渡时长（用户 2026-11-07 定 0.15s）。 */
private const val LOCK_THUMB_CROSSFADE_MS = 150

/**
 * 请 hook 重推一次「当前壁纸」缩略图（[MaskReplaceDetail] 进页面 / 回前台各调一次）。
 *
 * [cacheExists] = 本地已经有缩略图 ⇒ 允许 hook 按「同一张壁纸不重推」去重（省一次解码 +
 * 一次 720 短边的 JPEG 编码，也让卡片不会因为重推而重新渐显闪一下）；缓存不在时必须
 * 让 hook 无条件推，否则它会以为"早就推过了"而一直不给图。
 */
private fun requestLockThumb(state: MaskUiState, cacheExists: Boolean) {
    val now = SystemClock.elapsedRealtime()
    if (now - LockThumbRequestAt < LOCK_THUMB_REFRESH_MIN_MS) return
    LockThumbRequestAt = now
    state.refreshLockThumb(force = !cacheExists)
}

/**
 * 卡片**下方**的标题（2026-11-09 用户要求从卡上移到卡下；间距仍是 12dp，
 * 字号 / 颜色改成与选项 `summary` 一致 = `body2` + `onSurfaceVariantSummary`）。
 */
@Composable
private fun LockCardTitle(text: String, width: Dp) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(width)
            .padding(top = 12.dp),
    )
}

/**
 * 「当前壁纸」卡的图：hook 从 AOD 进程推回来的锁屏壁纸缩略图。
 *
 * 推送是**异步**的（还要先唤醒 AOD 进程），所以这里按文件 mtime 轮询（400ms）——
 * 比在 provider 里跨进程回调界面简单得多，开销也只有一次 `lastModified()`。
 *
 * ⚠️ **换图时不能把 value 清空**：旧实现用 `produceState(initialValue = null, stamp)`，
 * 新 stamp 一到 value 先变 null、再等解码完成 ⇒ 卡片会白闪一下（每次重推都闪）。
 * 这里改成"先留着上一张、解码成功才替换"，解码失败也保留旧图。
 */
@Composable
private fun rememberLockThumb(): Bitmap? {
    val ctx = LocalContext.current
    val file = remember(ctx) { File(ctx.cacheDir, MaskProvider.LOCK_THUMB_NAME) }
    var stamp by remember { mutableStateOf(0L) }
    LaunchedEffect(file) {
        while (true) {
            val m = file.lastModified()
            if (m != stamp) stamp = m
            delay(400L)
        }
    }
    val bitmap = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(stamp) {
        if (stamp <= 0L) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
        if (decoded != null) bitmap.value = decoded
    }
    return bitmap.value
}

// ------------------------------------------------------------------ 功能（二级：视差壁纸效果）

/**
 * 二级页「视差壁纸效果」的全部内容（2026-11-07 第三次改版：单卡版）。
 *
 * ## 布局
 *
 * **一张卡居中**（不做卡片流）：复用锁屏遮罩页右卡那张「单槽选图卡」—— 空态是加号卡、
 * 有图时铺满 + 右上角删除钮（[PickCard]，两页同一份实现）。卡宽 = 屏宽 ×
 * [CARD_WIDTH_RATIO]（0.45），卡高按屏幕比例（卡片是"屏幕的一块"），
 * **没有标题、没有 summary**（2026-11-07 用户要求）。
 *
 * 卡下是**单个**按钮（「立即替换」⇄「关闭替换」，主题色；宽 = 卡宽 5/6 = 0.375 屏宽，
 * 与锁屏遮罩页逐像素同宽）。再下面是「参数调整」卡 —— 2026-11-07 起**卡外标题也删了**，
 * 靠上一档 Spacer 给间距。
 *
 * ⚠️ **删除钮不参与居中**：它由 [PickCard] 用 `offset` 探出卡角，只改绘制位置、不改测量
 * 尺寸 ⇒ 卡片按 0.45 屏宽居中，不会因为多了半颗钮而偏左（用户 2026-11-07 明确要求）。
 *
 * ## 状态机（用户 2026-10-12 定，未变）
 *
 * | 状态 | 按钮 | 选图 / 清空 | 卡片 |
 * | --- | --- | --- | --- |
 * | 未替换 | 「立即替换」 | ✅ 可动 | 显示候选（如有） |
 * | 已替换（开启中） | 「关闭替换」 | ❌ 禁用 | 显示已生效背景，**跨重启持久** |
 *
 * * 点卡选图时先过**景深检查**（没开 → toast「请先在系统锁屏编辑开启景深效果」并中止，
 *   判定在 `MaskDetailActivity` 的 picker 里）；
 * * 「关闭替换」后图退回普通候选，随「启动清空残留候选」在下次打开 App 时清空（见 `App.onCreate`）；
 *   「壁纸改变后自动关闭替换」（默认开）开着时，视差生效中检测到换壁纸也走同一条关闭路径；
 * * 全程不写系统文件、不需要 root、无「替换行为」小节（备份 / 灭亮屏 / 遮挡限制都属主体链）。
 */
@Composable
internal fun ParallaxBgDetail(state: MaskUiState, onPick: () -> Unit) {
    val haptic = rememberClickHaptic()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    // 卡宽 = 卡片流的卡宽（0.45 屏宽，用户 2026-11-07 拍板）；卡高 / 圆角沿用同一套比例。
    val cardW: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO).toDp() }
    val cardH: Dp = cardW * screen.height / screen.width
    val radius: Dp = cardW * CARD_RADIUS_RATIO
    val surface = rememberCardSurface()
    // 按钮宽度与锁屏遮罩页同一个算式（卡片流卡宽 × 5/6 = 0.375 屏宽）。
    val buttonW: Dp = cardW * 5f / 6f
    val busy = state.busy
    // 生效中不许动选图 / 清空（用户 2026-10-12 定：只有非替换状态下才能动选图）。
    val editable = !busy && !state.parallaxActive

    // 功能冲突弹窗（2026-11-04 起三侧统一：空间 / 光栅任一在跑都不许应用）
    var showConflict by remember { mutableStateOf(false) }
    var conflictOther by remember { mutableStateOf<RunningWallpaper?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 一、单卡居中。空态点加号选图；有图时整张卡也是选图入口，右上角删除钮清空。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            PickCard(
                cardW = cardW,
                cardH = cardH,
                radius = radius,
                surface = surface,
                // 判据用"文件在不在"而不是 bitmap：预览异步解码，用它会先闪一下加号卡。
                hasImage = state.parallaxHasBg,
                bitmap = state.parallaxPreview,
                enabled = editable,
                onPick = {
                    if (state.requireActivated()) {
                        haptic()
                        onPick()
                    }
                },
                onClear = {
                    if (!busy && state.requireActivated()) {
                        haptic()
                        // 生效中内部会 toast「请先关闭替换」并中止（界面上的钮也已挡，这是兜底）。
                        state.clearParallaxBg()
                    }
                },
            )
        }

        // 二、**单个**按钮（用户指定）——「立即替换」⇄「关闭替换」，不是两个按钮。
        // 主题色 + 卡宽 5/6 的尺寸，与锁屏遮罩页的「立即替换」完全一致（用户 2026-11-07）。
        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    haptic()
                    if (state.requireActivated() && !state.busy) {
                        when {
                            state.parallaxActive -> state.disableParallax()
                            // **最后**才查互斥：激活 / busy 这些「本来就能不能开」的条件
                            // 已经过了（它们不满足时自己会弹 toast），这时才轮到外部状态。
                            else -> {
                                // 统一检测（空间 / 光栅，含两种挂载模式）；
                                // 「正在运行的名字要对」—— 文案由实际命中的那条拼。
                                val other = WallpaperConflict.running(ctx, state, WallpaperChain.PARALLAX)
                                if (other != null) {
                                    conflictOther = other
                                    showConflict = true
                                } else {
                                    state.enableParallax()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.width(buttonW),
                // 没选图时禁用（与遮罩页同一口径）；「关闭替换」永远可点。
                enabled = (state.parallaxActive || state.parallaxHasBg) && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = when {
                        busy -> "处理中…"
                        state.parallaxActive -> "关闭替换"
                        else -> "立即替换"
                    },
                    style = MiuixTheme.textStyles.button,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 三、「参数调整」（2026-10-13 加；2026-11-07 起卡外标题删掉，间距改由上面的 Spacer 给）
        // —— 卡内 2 开关 + 6 滑块，调完直接锁屏查看（SystemUI 侧 ~4s 内取用，
        // 无需重启、无需重开替换）。
        Spacer(Modifier.height(24.dp))
        ParallaxParamsCard(
            state = state,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 功能冲突弹窗（常驻组合树、用 show 控制）。「关闭」只关空间壁纸、
    // **不继续**本次替换；要再点一次「立即替换」才生效（用户 2026-10-29 定）。
    FeatureConflictDialog(
        show = showConflict,
        summary = conflictOther?.let { WallpaperConflict.summary(it, WallpaperChain.PARALLAX) } ?: "",
        onDismissRequest = {
            showConflict = false
            conflictOther = null
        },
        onConfirmClose = {
            val other = conflictOther
            showConflict = false
            conflictOther = null
            if (other != null) scope.launch { WallpaperConflict.disable(ctx, state, other) }
        },
    )
}

/**
 * 「参数调整」—— 一张控件卡（2 开关 + 6 滑块；首行「壁纸改变后自动关闭替换」为
 * 2026-10-15 用户指定新增，其余为 2026-10-13 起陆续加入）。
 *
 * * **2026-11-07 起没有卡外标题**（用户要求删掉 `SmallTitle("参数调整")`）——
 *   卡片直接跟在「立即替换」按钮下面，间距由调用方的 Spacer 给；
 * * 开关「使主体与背景移动方向相同」（v22.5 语义翻转，用户 2026-10-14）：默认关 = **反方向移动**
 *   （背景取反）；开关打开 = 主体与背景同方向。方向语义 = **只翻背景**（主体保持现状）。
 * * 倍率滑块 1~1.25（步长 0.05）；幅度滑块范围**随倍率动态**：上限 =（半屏宽 ×（倍率−1）− 5px，
 *   见 [MaskPrefs.parallaxAmpUpperBound]）、下限 = 上限 ×（主体 50% / 背景 35%）；
 *   倍率 = 1.0 时上限 0 → 滑块锁 0 + 禁用态；
 * * 拖动中 `persist = false` 只更新内存（`commit()` 每帧写盘会卡拖动 —— 滑块类设置的同一教训），
 *   抬手（`onValueChangeFinished`）一次性落盘；
 * * SystemUI 侧 3~4 秒内取用（config TTL 3s + 传感器帧轮询），**无需重启、无需重开替换**，
 *   替换关着时也允许调整（下次开启带着生效）。
 */
@Composable
private fun ParallaxParamsCard(state: MaskUiState, modifier: Modifier = Modifier) {
    val subjectRange = state.parallaxSubjectAmpRange()
    val bgRange = state.parallaxBgAmpRange()
    Card(
        modifier = modifier.padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column {
            // 「壁纸改变后自动关闭替换」（2026-10-15 加，**默认开**）：视差生效中检测到换壁纸
            // → 自动关闭视差替换（无 summary —— 用户指定）。
            SwitchRow(
                title = "壁纸改变后自动关闭替换",
                checked = state.parallaxAutoOff,
                onCheckedChange = { state.applyParallaxAutoOff(it) },
            )
            // 「视差效果覆盖桌面」（2026-10-21 加，**默认关**，无 summary —— 用户指定）。
            // 当前只做「解锁动画抑制」；视差层真正覆盖桌面的逻辑后续再接。
            SwitchRow(
                title = "视差效果覆盖桌面",
                checked = state.parallaxCoverDesktop,
                onCheckedChange = { state.applyParallaxCoverDesktop(it) },
            )
            SwitchRow(
                title = "使主体与背景移动方向相同",
                summary = "默认为反方向移动",
                checked = state.parallaxReverse,
                onCheckedChange = { state.applyParallaxReverse(it) },
            )
            ParallaxSliderRow(
                title = "主体放大倍率",
                text = formatScale(state.parallaxSubjectScale),
                value = state.parallaxSubjectScale,
                range = MaskPrefs.PARALLAX_SCALE_MIN..MaskPrefs.PARALLAX_SCALE_MAX,
                steps = 4,
                onValueChange = { state.applyParallaxSubjectScale(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
            ParallaxSliderRow(
                title = "背景放大倍率",
                text = formatScale(state.parallaxBgScale),
                value = state.parallaxBgScale,
                range = MaskPrefs.PARALLAX_SCALE_MIN..MaskPrefs.PARALLAX_SCALE_MAX,
                steps = 4,
                onValueChange = { state.applyParallaxBgScale(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
            ParallaxSliderRow(
                title = "主体移动最大幅度",
                text = "${state.parallaxSubjectAmp.roundToInt()} px",
                value = state.parallaxSubjectAmp,
                range = subjectRange,
                steps = sliderSteps(subjectRange),
                enabled = subjectRange.endInclusive > 0f,
                onValueChange = { state.applyParallaxSubjectAmp(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
            ParallaxSliderRow(
                title = "背景移动最大幅度",
                text = "${state.parallaxBgAmp.roundToInt()} px",
                value = state.parallaxBgAmp,
                range = bgRange,
                steps = sliderSteps(bgRange),
                enabled = bgRange.endInclusive > 0f,
                onValueChange = { state.applyParallaxBgAmp(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
            // 「最大幅度角度」（2026-10-13 用户加）：转动的满量程角 —— 绕长边转多少度达到满幅；
            // 量程 15°~45°、步进 1°、默认 25°（用户定；v22.6 上限 30°→45°）。
            // 位置在"背景幅度"与"归位等待"中间（用户指定）。
            ParallaxSliderRow(
                title = "最大幅度角度",
                text = "${state.parallaxGyroDeg.roundToInt()}°",
                value = state.parallaxGyroDeg,
                range = MaskPrefs.PARALLAX_GYRO_DEG_MIN..MaskPrefs.PARALLAX_GYRO_DEG_MAX,
                steps = sliderSteps(
                    MaskPrefs.PARALLAX_GYRO_DEG_MIN..MaskPrefs.PARALLAX_GYRO_DEG_MAX,
                ),
                onValueChange = { state.applyParallaxGyroDeg(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
            // 「恢复原位等待时长」（2026-10-13 用户加）：静止判定窗口 —— 停下来后**等多久**
            // 开始归位动画；量程 0.2s~1s、步进 0.1s。排在「背景移动最大幅度」下方（用户指定）。
            ParallaxSliderRow(
                title = "恢复原位等待时长",
                text = formatSeconds(state.parallaxHomeWait),
                value = state.parallaxHomeWait,
                range = MaskPrefs.PARALLAX_HOME_WAIT_MIN..MaskPrefs.PARALLAX_HOME_WAIT_MAX,
                steps = 7,
                onValueChange = { state.applyParallaxHomeWait(it, persist = false) },
                onFinished = { state.persistParallaxParams() },
            )
        }
    }
}

/**
 * 「参数调整」卡里的一行滑块 —— 标题行右侧实时数值 + 全宽 Slider。
 */
@Composable
internal fun ParallaxSliderRow(
    title: String,
    /** 右侧实时数值（倍率 "1.15" / 幅度 "45 px"）。 */
    text: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    // 范围退化（倍率 = 1.0 → 上限 0）时用安全占位范围：滑块禁用 + 值留在 0，避免 0..0 的除零。
    val safeRange = if (range.endInclusive > range.start) range else 0f..1f
    BasicComponent(
        title = title,
        endActions = {
            Text(
                text = text,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                textAlign = TextAlign.End,
            )
        },
    )
    Slider(
        value = value.coerceIn(safeRange),
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        valueRange = safeRange,
        steps = if (enabled) steps else 0,
        enabled = enabled,
        onValueChangeFinished = onFinished,
    )
}

/** 滑块的步进数（"步进 1 个单位" = 段数−1；段数按范围跨度取整）——幅度（px）与角度（°）共用。 */
internal fun sliderSteps(range: ClosedFloatingPointRange<Float>): Int =
    ((range.endInclusive - range.start).roundToInt() - 1).coerceAtLeast(0)

/** 倍率显示（"1.15"；Locale.US 防止小数点被本地化成逗号）。 */
private fun formatScale(value: Float): String = String.format(Locale.US, "%.2f", value)

/** 秒显示（"0.5 s"；归位等待时长用）。 */
private fun formatSeconds(value: Float): String = String.format(Locale.US, "%.1f s", value)

// ------------------------------------------------------------------ 设置

@Composable
internal fun SettingsTab(
    state: MaskUiState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onMonetChange: (Boolean) -> Unit,
) {
    // 这里只给按钮用：开关的振动由 SwitchRow 自己处理（点整行 → ContextClick；点开关 → miuix 内置的 Toggle）。
    val haptic = rememberClickHaptic()
    TabColumn {
        // 「替换行为」整张卡片 2026-09-29 拆掉了（用户指定）：「替换前备份原文件」搬到二级页
        // 「锁屏遮罩替换」（那张卡片的标题**仍是「替换行为」**；该开关后于 2026-10-15 随备份/
        // 还原整组逻辑删除）；「详细过程写入LSPosed日志」并进下面的「测试」卡片。
        // 下面这两段历史说明留着有据可查：
        //
        // 官方排布：SwitchRow 自带四周 16dp 内边距（纵向那 16dp 就是**行距**）、行高 ≥56dp，
        // 所以卡内既不包 padding、也不在选项之间加 Spacer。
        // 「灭屏后结束保护」这个开关 2026-09-25 就从界面撤了；2026-09-29 用户又要求
        // "逻辑固定在代码里、不留开关"，于是 `releaseOnScreenOn` 连同它的 prefs 键一起删掉 ——
        // 判定点现在**直接看 `prefs.protecting`**（`MaskProvider` / `HookRuntime`），
        // 不再有任何开关参与（保护主路收尾在替换完成时，灭亮屏只是兜底）。
        // 「替换后自动灭亮屏一次」2026-09-29 曾整体删除、后于 2026-10-04 按用户要求以**可选开关**
        // 恢复（在主体链「替换行为」卡里；「屏幕灭亮提醒」弹窗 2026-10-04 一并删除）。
        // 外观：原来是一排三个按钮（跟随系统 / 浅色 / 深色），2026-09-29 用户要求合成**一个下拉**。
        SmallTitle(text = "外观")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            DropdownRow(
                title = "主题模式",
                options = ThemeMode.entries.map { it.label },
                selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                onSelect = { index -> onThemeModeChange(ThemeMode.entries[index]) },
            )
            // 「启用Monet颜色」（2026-11-07）：miuix 支持读系统动态色（`platformDynamicColors`），
            // 打开后整套配色跟随壁纸取色。默认关 —— 等价于原来那套固定配色，老用户升级后零变化。
            SwitchRow(
                title = "启用Monet颜色",
                checked = state.monetColor,
                onCheckedChange = {
                    state.applyMonetColor(it)
                    onMonetChange(it)
                },
            )
        }

        // 测试：这张卡片现在只剩「详细过程写入LSPosed日志」一个开关 ——
        // 「使用自定义背景」「显示自检与最近操作」2026-11-07 按用户要求整链删除
        //（排查改看 LSPosed 日志与 `run_log.txt`，用户发 log 即可，界面上不再留入口）。
        SmallTitle(text = "测试")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「详细过程写入LSPosed日志」2026-09-29 从「替换行为」挪进来。
            SwitchRow(
                title = "详细过程写入LSPosed日志",
                summary = "便于排查问题",
                checked = state.verbose,
                onCheckedChange = { state.applyVerbose(it) },
            )
        }
    }
}
// ------------------------------------------------------------------ 系统界面（二级）

/**
 * 二级页「系统界面」的内容 —— 从设置页搬来的「锁屏修改」。
 *
 * 为什么值得一张独立的二级页：这个开关作用在**锁屏（SystemUI）**上，跟"替换模板文件"
 * 那条链完全无关，混在设置页的「替换行为」里名不副实（2026-09-29 用户要求搬过来）。
 */
@Composable
internal fun SystemUiDetail(state: MaskUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        SmallTitle(text = "锁屏修改")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchRow(
                title = "使自定义锁屏按钮显示在上层",
                summary = "防止被通知覆盖的情况",
                checked = state.lockScreenButtonOnTop,
                onCheckedChange = { state.applyLockScreenButtonOnTop(it) },
            )
            // 「强制锁屏元素深浅模式」（2026-10-11 加）—— 主开关打开后，下面出现 6 个元素的档位下拉。
            // （2026-10-11 撤掉「通知」项：该系统版本玻璃材质下深浅两档渲染代码完全相同，切档无视觉变化。）
            SwitchRow(
                title = "强制锁屏元素深浅模式",
                summary = "开启后可分别调整",
                checked = state.forceToneMode,
                onCheckedChange = { state.applyForceToneMode(it) },
            )
            if (state.forceToneMode) {
                DropdownRow(
                    title = "状态栏",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneStatusBar,
                    onSelect = { state.applyForceToneStatusBar(it) },
                )
                DropdownRow(
                    title = "时间与锁屏内容",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneClock,
                    onSelect = { state.applyForceToneClock(it) },
                )
                DropdownRow(
                    title = "快捷方式图标",
                    options = ForceToneOptionsShortcut,
                    selectedIndex = state.forceToneShortcut,
                    onSelect = { state.applyForceToneShortcut(it) },
                )
                DropdownRow(
                    title = "指纹图标",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneFingerprint,
                    onSelect = { state.applyForceToneFingerprint(it) },
                )
                DropdownRow(
                    title = "底部提示文字",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneBottomHint,
                    onSelect = { state.applyForceToneBottomHint(it) },
                )
                DropdownRow(
                    title = "锁屏签名/杂志文字",
                    options = ForceToneOptions,
                    selectedIndex = state.forceToneSignature,
                    onSelect = { state.applyForceToneSignature(it) },
                )
            }
        }
    }
}

/** 「强制锁屏元素深浅模式」的下拉三档 —— 索引与 `MaskPrefs.FORCE_TONE_*`（0/1/2）一一对应。 */
private val ForceToneOptions = listOf("保持系统判定", "浅色", "深色")

/**
 * 「快捷方式图标」专用五档（2026-10-11 加「不透明」两档）——
 * 索引对应 `MaskPrefs.FORCE_TONE_*`（0/1/2/3/4，"不透明" = 把系统 80% 的图标 tint 拉满）。
 */
private val ForceToneOptionsShortcut =
    listOf("保持系统判定", "浅色", "深色", "浅色（不透明）", "深色（不透明）")

// ------------------------------------------------------------------ 系统桌面（二级）

/**
 * 二级页「系统桌面」的内容 —— 「多任务」（2026-10-16 加）。
 *
 * 只有一张卡、一个开关；真正的逻辑全在 SystemUI 侧（`hook/RecentsStatusBar`）：
 * 桌面经 `LauncherProxyService` 把「多任务显示 / 隐藏」通知 SystemUI，那边按本开关
 * 设 / 清状态栏 disable 位（内容隐藏、下拉保留；滑入过渡瞬间的时机口径见该文件注释）。
 * 默认关（用户定的）—— 打开后进出一次多任务即可看到效果。
 */
@Composable
internal fun DesktopDetail(state: MaskUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        SmallTitle(text = "多任务与文件夹")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchRow(
                title = "打开多任务和文件夹时隐藏状态栏",
                checked = state.hideStatusBarInRecents,
                onCheckedChange = { state.applyHideStatusBarInRecents(it) },
            )
        }
    }
}

// ------------------------------------------------------------------ 关于

// 开发者主页链接（酷安）2026-10-31 随「选择平台」弹窗搬去 `PlatformDialog.kt`
//（见那里的 `DeveloperCoolapkUrl`）—— 开发者卡现在先弹窗、由弹窗分发到 Github / B 站 / 酷安。

/** 捐赠入口 —— 主页那张「通过捐赠支持开发者」卡点一下打开这个链接（2026-10-29）。 */
private const val DonateUrl = "https://afdian.com/a/Frederic22"

/** 一条开源许可：[author] 与 [license] 会拼成"开发者 | 协议"，显示在名字下面。 */
private data class OpenSourceEntry(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
)

/**
 * 用到的开源项目。**这里是唯一权威清单** —— 改它不需要同步别处（`agent.md` 没有副本）。
 *
 * 两大类：
 * - **依赖与权重**：miuix / Kyant 两个库 / libxposed API / AndroidX / Compose Multiplatform /
 *   ONNX Runtime（深度推理的执行引擎）/ Depth Anything V2 **Small**（`assets/models/dav2_small_int8.onnx`）；
 * - **移植进工程的代码**：HyperChanger 的悬浮底栏（`ui/nav/`）、PatchMatch 的 C 移植
 *   （`cpp/patchmatch/`，编成 `libspatial_fill.so`）、Material Icons 的两个图标（`ui/icons/`）。
 *
 * ⚠️ **带版本号的那两条不是随手写的**：
 * - `HyperChanger v1.1.3` 是我们实际引用的 tag（commit `a01dc46`，2026-09-18），该版本是 Apache-2.0；
 *   上游后来把协议换成了 **AGPL-3.0-or-later**。Apache-2.0 授权不可撤销，我们那份永久有效，
 *   但版本号必须留着 —— 它划清"我们用的是换协议之前的那一版"。
 * - `Depth Anything V2 Small` 同理：只有 Small/Base/Large 权重是 Apache-2.0，**Giant 是 CC-BY-NC-4.0**。
 */
private val OpenSourceLicenses = listOf(
    OpenSourceEntry(
        "miuix", "compose-miuix-ui（YuKongA）", "Apache-2.0",
        "https://github.com/compose-miuix-ui/miuix",
    ),
    OpenSourceEntry(
        "Backdrop", "Kyant（Kyant0）", "Apache-2.0",
        "https://github.com/Kyant0/AndroidLiquidGlass",
    ),
    OpenSourceEntry(
        "Shapes", "Kyant（Kyant0）", "Apache-2.0",
        "https://github.com/Kyant0/Shapes",
    ),
    // 官方仓库（不是 LSPosed 主仓库）：libxposed API 单独发布在 libxposed/api，
    // 我们 `compileOnly` 的 `libs/libxposed-api-102.jar` 就出自 `io.github.libxposed:api:102.0.0`。
    OpenSourceEntry(
        "libxposed API", "LSPosed", "Apache-2.0",
        "https://github.com/libxposed/api",
    ),
    OpenSourceEntry(
        "AndroidX Activity Compose", "Google / AndroidX", "Apache-2.0",
        "https://github.com/androidx/androidx",
    ),
    OpenSourceEntry(
        "Compose Multiplatform", "JetBrains", "Apache-2.0",
        "https://github.com/JetBrains/compose-multiplatform",
    ),
    // 空间壁纸的深度推理模型（`assets/models/dav2_small_int8.onnx`，ViT-S 全量动态量化）。
    // ⚠️ 名称必须带 **Small**：只有 **Small/Base/Large** 权重是 Apache-2.0；**Giant** 那档是
    //    CC-BY-NC-4.0，换模型前先看协议（见《07-坑点台账》§7.13 旁边那条）。
    OpenSourceEntry(
        "Depth Anything V2 Small", "DepthAnything", "Apache-2.0",
        "https://github.com/DepthAnything/Depth-Anything-V2",
    ),
    // 深度推理的执行引擎（`implementation(libs.onnxruntime.android)`，1.31.0）。MIT。
    OpenSourceEntry(
        "ONNX Runtime", "Microsoft", "MIT",
        "https://github.com/microsoft/onnxruntime",
    ),
    // ⚠️ **版本号必须留**（2026-11-02 查证）：我们引用的是 **v1.1.3**（commit `a01dc46`，2026-09-18），
    //    该 tag 是 Apache-2.0；上游后来换成 **AGPL-3.0-or-later**。
    //    Apache-2.0 不可撤销 ⇒ 我们的副本永久有效，但标出版本才能划清边界。
    //    引用点：`ui/nav/Os4FloatingNavigationBar.kt`（文件头有 SPDX + 原版权）。
    OpenSourceEntry(
        "HyperChanger v1.1.3", "btm_m（ColdP）", "Apache-2.0",
        "https://github.com/ColdP/HyperChanger",
    ),
    // `cpp/patchmatch/` 的 C 移植（编成 `libspatial_fill.so`，负责底图填充）。
    // MIT，Copyright (c) 2012 Younesse ANDAM —— LICENSE 文件是上游 2025-05 才补的，我们 2026-10 移植。
    OpenSourceEntry(
        "PatchMatch", "Younesse ANDAM", "MIT",
        "https://github.com/younesse-cv/PatchMatch",
    ),
    // 状态卡那两个大图标（`check_circle_outline` / `error_outline`）的路径数据是从这里搬的，
    // 落在 `ui/icons/StatusIcons.kt`（见那个文件顶部的说明）。
    OpenSourceEntry(
        "Material Icons", "Google", "Apache-2.0",
        "https://github.com/google/material-design-icons",
    ),
)

/**
 * 打开外部链接。没装浏览器之类的异常直接吞掉 —— 不能因为点一下就把应用崩了。
 *
 * `internal`（2026-10-31）：`PlatformDialog` 也要用，同包可见。
 */
internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
internal fun AboutTab(state: MaskUiState) {
    val context = state.context
    val haptic = rememberClickHaptic()
    // 「选择平台」弹窗的显隐（2026-10-31）：开发者卡不再直接跳酷安，先弹三选一。
    var showPlatform by remember { mutableStateOf(false) }
    TabColumn {
        // 状态卡已于 2026-10-29 搬回主页最上面（见 [HomeTab]）—— 关于页从开发者卡开始。
        // 开发者卡：头像 + 名字 + 一句话 + 右侧箭头，整行可点（打开 Coolapk 主页）。
        // 排版用 miuix 官方的 `BasicComponent`：`startAction` 放头像、`endActions` 放右侧箭头，
        // 官方那套 16dp 内边距与行高照旧。箭头照官方 `ArrowPreference` 的画法
        // （`onSurfaceVariantActions` 描色、在行内垂直居中），只是图标换成 `ChevronForward` ——
        // 我们依赖的 miuix-icons 0.9.4 里还没有官方那个 `MiuixIcons.Basic.ArrowRight`。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "Frederic22",
                summary = "相逢的人会再相逢。",
                startAction = {
                    Image(
                        painter = painterResource(R.drawable.profile_photo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            // BasicComponent 里头像与文字之间固定 8dp，这里再补 6dp（共 14dp），
                            // 让间距宽一点点。padding 必须放在 size **之前**，否则会被 clip 压成 42dp。
                            .padding(end = 6.dp)
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                },
                endActions = { LinkArrow() },
                onClick = {
                    haptic()
                    showPlatform = true
                },
            )
        }

        // 开源代码许可：一条一条列（名字 + "开发者 | 协议" + 右侧箭头），整行可点 → 对应仓库。
        // 排版和开发者卡一致（官方 `BasicComponent`），每条自带 16dp 内边距与行高。
        SmallTitle(text = "开源代码许可")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            OpenSourceLicenses.forEach { entry ->
                LinkRow(
                    title = entry.name,
                    summary = "${entry.author} | ${entry.license}",
                    onClick = { openUrl(context, entry.url) },
                )
            }
        }
    }

    // 「选择平台」弹窗：**常驻组合树、用 show 控制**（条件包裹会让 miuix 的退出动画播不出来）。
    PlatformDialog(
        show = showPlatform,
        onDismissRequest = { showPlatform = false },
        onPick = { openUrl(context, it) },
    )
}

/** 一条可点的链接行：标题 + 说明 + 右侧箭头，整行可点。[summary] 传 null 则不画副标题。 */
@Composable
private fun LinkRow(title: String, summary: String?, onClick: () -> Unit) {
    val haptic = rememberClickHaptic()
    BasicComponent(
        title = title,
        summary = summary,
        endActions = { LinkArrow() },
        onClick = {
            haptic()
            onClick()
        },
    )
}

/** 右侧那个 ">"：颜色与尺寸照官方 `ArrowPreference` 的 endAction 画法（开发者卡也用它）。 */
@Composable
private fun RowScope.LinkArrow() {
    Image(
        imageVector = MiuixIcons.ChevronForward,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
        modifier = Modifier
            .size(16.dp)
            .align(Alignment.CenterVertically),
    )
}

/**
 * 一个开关选项：用 miuix 官方的 `BasicComponent`（= 官方 `SwitchPreference` 的排版）——
 * 官方字号（标题 `headline1` = 17sp、说明 `body2` = 14sp）、文字列与右侧开关自动垂直居中、
 * 行高是官方的 `heightIn(min = 56.dp)`；按下时整行"变深"来自 miuix 主题给 `LocalIndication`
 * 提供的 `MiuixIndication`。
 *
 * **`insideMargin` 用官方默认值（四周 16dp）**，这也是官方设置页那种"行距舒展"的来源：
 * 纵向的 16dp 上下 padding 合起来就是相邻两行之间的间距（所以卡片里不需要再加 `Spacer`），
 * 横向的 16dp 是左右留白；而 `clickable` 在 `padding` 的**外层**，按压高亮正好铺满整个卡片宽度。
 * （踩过两次：① 设成 0 + 由卡片 `Column(padding(16.dp))` 提供边距 → 高亮左右缩进、看着怪；
 * ② 只留横向 → 行距又太紧。回到官方默认就对了。）
 *
 * 唯一的差异是**振动分两条路**，点哪儿都只振一次：点**开关**走 `Switch` 内置的
 * `ToggleOn` / `ToggleOff`（事件被子组件消费，整行的 clickable 不触发）；
 * 点**整行其它位置**走我们的 `haptic()`（`ContextClick`）。官方 `SwitchPreference` 把两条路
 * 合成同一个 `onCheckedChange`，在那儿补振动会让点开关时连振两下。
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    /** false = 整行变灰、不可点（2026-10-30：系统壁纸服务模式下属选项无效用）。 */
    enabled: Boolean = true,
) {
    val haptic = rememberClickHaptic()
    BasicComponent(
        title = title,
        summary = summary,
        enabled = enabled,
        endActions = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        onClick = {
            if (enabled) {
                haptic()
                onCheckedChange(!checked)
            }
        },
        role = Role.Switch,
    )
}

/**
 * 一个**下拉选择**选项：整行可点，右侧显示「当前值 + 上下箭头」，点开弹出官方那套展开栏。
 *
 * 2026-09-29 加（用户给了 MIUI 设置页「设置图标」那张截图，要求把设置页原来的三个主题按钮
 * 合成这样一个下拉）。
 *
 * ## ⚠️ 骨架必须照官方源码，不能自己想 —— 首版显示错误，就是想当然写的
 *
 * 官方出处：`compose-miuix-ui/miuix` 仓库（`miuix-preference/…/OverlayDropdownPreference.kt`、
 * `…/popup/OverlayDropdownPopup.kt`、`…/popup/DropdownEntriesContent.kt`）。三个要点：
 *
 * 1. **弹出层要写在 `endActions` 里**（官方就是这么做的 —— 它是 `RowScope` 里的一个可组合项），
 *    这样它才**锚在这一行**上。放到 `BasicComponent` 外面当兄弟就是错的（没有锚点）。
 * 2. **内容必须包一层 `ListPopupColumn`** —— 列表的宽度（200~288dp）与滚动都由它管。
 *    少了这一层正是"展开栏显示错误"的直接原因（首版就是漏了它）。
 * 3. `alignment = PopupPositionProvider.Align.End`（对齐行尾）；点击走 `DropdownItem` 自带的
 *    `onClick`，选中态看 `item.selected` —— 不是自己把 `isSelected` 算出来传进去。
 *
 * > ⚠️ **miuix 的成品下拉不在 `miuix-ui` 里**：`OverlayDropdownPreference` / `ArrowPreference`
 * > 那些在**另一个模块 `miuix-preference`**，本工程没引（也不打算引：本地缓存只有 `0.9.4-rc01`，
 * > 与其余 miuix 模块的 `0.9.4` 不是同一版本，混用有风险）。所以这里用 `miuix-ui` 里
 * > 官方提供的**积木**、照上面那份源码接线 —— 观感、动画、配色仍然是官方那套。
 * > 部件全是官方的：`BasicComponent` / `OverlayListPopup` / `ListPopupColumn` / `DropdownImpl` /
 * > `DropdownItem` / `DropdownEntry` / `DropdownArrowEndAction` / `DropdownDefaults`。
 */
@Composable
internal fun DropdownRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    /** false = 整行变灰、不可点（2026-10-30：空间壁纸「挂载模式」在运行中锁死用）。 */
    enabled: Boolean = true,
) {
    val haptic = rememberClickHaptic()
    val hapticFeedback = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val expanded = remember { mutableStateOf(false) }
    val holdDown = remember { mutableStateOf(false) }
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions
    val dropdownColors = DropdownDefaults.dropdownColors()
    // **照官方**：一组 `DropdownEntry`，每个 `DropdownItem` 自带 `selected` 与 `onClick`。
    val entries = remember(options, selectedIndex, onSelect) {
        listOf(
            DropdownEntry(
                items = options.mapIndexed { index, text ->
                    DropdownItem(
                        text = text,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                },
            ),
        )
    }

    BasicComponent(
        title = title,
        summary = summary,
        interactionSource = interactionSource,
        enabled = enabled,
        endActions = {
            // 当前值：官方用 `body2` 字号 + `onSurfaceVariantActions`，右对齐。
            Text(
                text = options.getOrElse(selectedIndex) { "" },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = actionColor,
                textAlign = TextAlign.End,
            )
            DropdownArrowEndAction(actionColor = actionColor)
            // 弹出层**就在 endActions 里**（要点 1）。
            OverlayListPopup(
                show = expanded.value,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { expanded.value = false },
                onDismissFinished = { holdDown.value = false },
            ) {
                // 要点 2：`ListPopupColumn` 管宽度与滚动，漏了就显示错。
                ListPopupColumn {
                    entries.forEach { entry ->
                        val last = entry.items.lastIndex
                        entry.items.forEachIndexed { index, item ->
                            DropdownImpl(
                                item = item,
                                optionSize = entry.items.size,
                                isSelected = item.selected,
                                index = index,
                                dropdownColors = dropdownColors,
                                enabled = entry.enabled && item.enabled,
                                isFirst = index == 0,
                                isLast = index == last,
                                onSelectedIndexChange = { picked ->
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    entry.items.getOrNull(picked)?.onClick?.invoke()
                                    expanded.value = false
                                },
                            )
                        }
                    }
                }
            }
        },
        onClick = {
            // 禁用态整行不可点（`BasicComponent` 自己也会拦，这里双保险）
            if (enabled) {
                // 官方：展开时振一下、并把整行置成"按住"态（`holdDownState`）。
                val next = !expanded.value
                expanded.value = next
                if (next) {
                    holdDown.value = true
                    haptic()
                }
            }
        },
        role = Role.DropdownList,
        holdDownState = holdDown.value,
    )
}
