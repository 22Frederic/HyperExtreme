package com.frederic22.hyperextreme.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.ui.icons.CheckCircleOutlineIcon
import com.frederic22.hyperextreme.ui.icons.ErrorOutlineIcon
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Refresh
import com.frederic22.hyperextreme.ui.icons.InfoIcon
import com.frederic22.hyperextreme.ui.icons.SettingsIcon
import com.frederic22.hyperextreme.ui.icons.TuneIcon
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/** 目标应用（= 模块的作用域），定义在 data 层，这里只做别名。 */

/**
 * 启动后多久做一次激活体检。
 *
 * 3 秒是给两条异步链路留的余量：LSPosed 送服务 binder（跨进程），以及首次静默的 root 探测（走 su）。
 * 问得太早容易误报"未激活"。
 */
private const val ActivationAlertDelayMs = 3000L

/**
 * 页面底部的"渐隐纱"（2026-10-04 用户要求）：从屏幕底向上 **290px**（@3x = 96.7dp）线性渐隐 ——
 * 底部 alpha = [BottomFadeAlpha]（0.85），到顶端为 0；浅色用白色、深色用黑色。
 *
 * 放在页面录制层（`layerBackdrop`）**内**：底栏玻璃采样到的背景也会包含它（用户选 A）；
 * 绘制顺序 = 页面内容 → 这层纱 → 底栏阴影 → 底栏玻璃，即"在底栏（含阴影）的下层"。
 */
private val BottomFadeHeight = 96.7.dp
private const val BottomFadeAlpha = 0.85f

/**
 * 一级功能页各张卡片通向的**二级页**。
 *
 * 二级页是**盖住底栏**的全屏页（用户指定），所以它们不占 [Tab] 的位置。
 * 见 [FunctionHome] / [MaskReplaceDetail] / [SystemUiDetail]。
 */
internal enum class DetailPage { MaskReplace, ParallaxBg, SpatialWallpaper, RasterWallpaper, AliveWallpaper, SystemUi }

// 二级页（[DetailPage]）的转场**不归本文件管**：它现在是独立 Activity（[MaskDetailActivity]），
// 转场由**系统的 Activity 动画**负责 —— 为什么必须如此、以及为什么不在这里复刻，见那个类的注释。
// （本文件里曾经有两版自绘复刻：先照 HyperChanger、再照 AOSP 的四个 anim，都被否掉，已删干净。）

/** 底部导航的四个页签 —— 以后加功能就往这里加。 */
private enum class Tab(val label: String, val icon: ImageVector) {
    Home("主页", MiuixIcons.Home),
    // 下面三个图标已经从 miuix-icons 提取到 `ui/icons/`（原样、Regular 字重），
    // 想改形状（比如换成实心）直接编辑那三个文件里的 pathData 即可，不用动依赖。
    Function("功能", TuneIcon),
    Settings("设置", SettingsIcon),
    About("关于", InfoIcon),
}

@Composable
fun MaskSwapApp(onDarkModeChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { MaskPrefs(context) }
    var themeMode by remember { mutableStateOf(ThemeMode.of(prefs.themeMode)) }
    // 「启用Monet颜色」（2026-11-07）：状态提到这一层，因为换配色必须**换 ThemeController 实例**
    //（它的 lightColors / darkColors 都是 val）。真正的换实例逻辑在 [rememberThemeController]。
    var monetColor by remember { mutableStateOf(prefs.monetColor) }
    val controller = rememberThemeController(themeMode, monetColor)

    // 玻璃背景录制层（miuix 0.9.5）：对象在这里持有、由 Shell 挂到页面内容上录制，
    // 并向下 provide —— 弹窗（写在 Shell 函数体里）就能读到同一份。详见 [LocalPageBackdrop]。
    val pageBackdrop = rememberLayerBackdrop()

    // textStyles 用 [HyperTextStyles]：miuix 默认的正文是 400，HyperOS 系统界面是 MiSans Medium，
    // 不补这一档字重就会"比系统显示的细一档"（详见该文件里的说明）。
    MiuixTheme(controller = controller, textStyles = HyperTextStyles) {
        CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
            MaskSwapShell(
                backdrop = pageBackdrop,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    prefs.setThemeMode(mode.id)
                },
                onMonetChange = { monetColor = it },
                onDarkModeChange = onDarkModeChange,
            )
        }
    }
}

@Composable
private fun MaskSwapShell(
    backdrop: LayerBackdrop,
    onThemeModeChange: (ThemeMode) -> Unit,
    onMonetChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { MaskPrefs(context) }
    val state = remember { MaskUiState(context, prefs, scope) }

    // 用 HorizontalPager 承载四个页签：翻页动画走 miuix 的 PagerGestureUtils
    //（PagerNavigationSpringSpec + springAnimateToPage + pagerGestureOverride），对齐 HyperOS 系统手感。
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    // 跳页与"松手吸附"共用同一个 spring（miuix 官方 example 的配置）。
    val pagerFlingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = PagerNavigationSpringSpec,
    )
    // 悬浮底栏的玻璃效果需要一份"背景录制"：只录页面内容（连底栏一起录会和 RenderNode 形成环）。
    // 2026-11-18 起对象由 [MaskSwapApp] 创建并作为参数传进来 —— 弹窗也要读它（见 [LocalPageBackdrop]）。
    // 选中页与 pager 的联动交给 [AppPagerState]（miuix 官方 example 的 `MainPagerState` 同款）：
    // `currentPage` 在滚动途中会逐页经过中间页，不能直接拿来驱动底栏的选中块。
    val appPagerState = rememberAppPagerState(pagerState, scope)
    LaunchedEffect(pagerState.currentPage) { appPagerState.syncPage() }
    val isDark = when (state.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // 顶栏右上角的重启按钮：一级四个页签 + 二级页共用一个弹窗（`RestartDialog`）。
    var askRestartAod by remember { mutableStateOf(false) }

    // 二级页**不在这里**：它现在是独立 Activity（[MaskDetailActivity]），
    // 连"选图 + 三个弹窗"那套都搬过去了 —— 一级页只剩自己的东西。
    // 之前那版在同一棵树里叠一层 AnimatedVisibility、再用 graphicsLayer 推一级页面，
    // 2026-09-29 全删（转场交给系统，见 [MaskDetailActivity] 的注释）。

    // 系统栏/导航条的图标明暗跟着【应用】主题走：SDK 默认判定读的是系统配置，
    // 应用强制浅色而系统是深色时，状态栏图标会看不见。
    LaunchedEffect(isDark) { onDarkModeChange(isDark) }

    LaunchedEffect(state.revision) {
        state.reloadPreviews()
    }

    // 回到前台时对齐一次状态：hook 在编辑器进程里可能已经自动解除了保护（用户换了壁纸），
    // 那时只有 SharedPreferences 变了；界面不重新读一次就会永远停在"保护中"。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 「选图」那条链（picker + pickRole + 尺寸不一致弹窗）2026-09-29 整体搬去
    // `MaskDetailActivity` 的 `MaskDetailScreen` —— 二级页成了独立 Activity，它得自己走完这条链。

    // 检查更新（2026-11-16）：**每天（北京时间）首次**进模块自动查一次。
    // 这里是冷启动那条路；回前台 / 跨零点走 [MaskUiState.onResume]。
    // 两处都受同一个日期闸门约束，重复调用直接返回（开关关着也直接返回）。
    LaunchedEffect(Unit) { state.autoCheckUpdateOnce() }

    // 「屏幕灭亮提醒」也搬去 `MaskDetailActivity` 了：它只可能被二级页的「立即替换」触发
    //（`MaskUiState.requestReplace()`），一级页面没有那条路。

    // 启动体检：3 秒后状态还不是「模块已激活」，就把缺什么一次说清楚。
    // 三种文案对应三种残缺：两样都缺 / 只缺 LSPosed / 只缺 Root。
    //
    // ⚠️ summary 后面那句「无 Root 下用空间壁纸」是给**只有 LSPosed 或只有 Root** 的人看的：
    // 2026-10-30 起空间壁纸有了「系统壁纸服务（免Root）」这档降级方案，没激活模块也能用。
    var activationAlert by remember { mutableStateOf(false) }
    var activationNoMore by remember { mutableStateOf(false) }
    val activationCtx = LocalContext.current
    val activationPrefs = remember { MaskPrefs(activationCtx) }
    LaunchedEffect(Unit) {
        delay(ActivationAlertDelayMs)
        if (!state.fullyActivated && !activationPrefs.activationWarnDismissed) {
            activationAlert = true
        }
    }

    // 底栏在 Scaffold 内、`layerBackdrop` 外（位置见下面调用处的说明）。
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // 顶栏搬到每一页里面了（见 [TabHost]），所以这里不再消费任何 inset ——
            // 否则状态栏会被扣两次。顶栏自己按 systemBars.top 留白并把底色铺到状态栏区域。
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // 底栏的玻璃只录这一层（页面内容，含每页自己的顶栏）。
                //
                // background 挂在 layerBackdrop 的**内层**：这样页面底色也会被录进背景副本。
                // 否则滚到底时胶囊后面是一片透明（那里只有 Scaffold 画的底色、不在录制范围内），
                // 玻璃就只剩自己的深色底 —— 看起来就是"底栏发黑"。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .background(MiuixTheme.colorScheme.surface),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            // miuix 0.9.5 的 CrossAxis 手势（PagerSwipeNode）：跟手拖动 +
                            // 松手走 PagerNavigationSpringSpec，纵向拖动会退出、交给页面自己滚。
                            // ⚠️ 0.9.5 起 `flingBehavior` 是**必填**参数（0.9.4 时可省），
                            // 且枚举 `CrossAxisInterceptor` 改名 `CrossAxis`、`TapToHalt` 被删 ——
                            // 我们只用默认模式，所以补一个 flingBehavior 即可。
                            .pagerGestureOverride(
                                pagerState = pagerState,
                                flingBehavior = pagerFlingBehavior,
                                enabled = true,
                            ),
                        // miuix 的 CrossAxis 手势接管：pager 自己的触摸滚动关掉，
                        // 由 pagerGestureOverride 的识别器仲裁（横滑翻页 / 竖滑交给页面内容）。
                        userScrollEnabled = false,
                        pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                        flingBehavior = pagerFlingBehavior,
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val tab = Tab.entries[page]
                        TabHost(title = tab.label, onRestartAod = { askRestartAod = true }) {
                            when (tab) {
                                // 主页（2026-10-29：状态卡搬回最上面、「去选图替换」按钮已删；
                                // 2026-11-08：「当前运行」卡可直点进二级页 ⇒ 走同一个 onOpen）
                                Tab.Home -> HomeTab(state, onOpen = { page ->
                                    MaskDetailActivity.start(context, page)
                                })

                                // 一级功能页现在只有两张入口卡片，真正的内容在二级页（独立 Activity）里。
                                Tab.Function -> FunctionHome(onOpen = { page ->
                                    MaskDetailActivity.start(context, page)
                                })

                                Tab.Settings -> SettingsTab(state, onThemeModeChange = { mode ->
                                    state.applyThemeMode(mode)
                                    onThemeModeChange(mode)
                                }, onMonetChange = onMonetChange)

                                Tab.About -> AboutTab(state)
                            }
                        }
                    }

                    // 底部渐隐纱：贴屏幕底、页面内容之上、底栏（含阴影）之下；在录制层内 → 玻璃采样含它。
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(BottomFadeHeight)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        (if (isDark) Color.Black else Color.White).copy(alpha = 0f),
                                        (if (isDark) Color.Black else Color.White).copy(alpha = BottomFadeAlpha),
                                    ),
                                ),
                            ),
                    )
                }

                // 沉浸式：底栏浮在内容之上（内容能从它下面滚过去）。不能用 Scaffold 的 bottomBar 槽位，
                // 否则内容会被裁在底栏上沿，也没有"压在上面"的层次。
                //
                // ⚠️ 必须在 Scaffold **里面**（2026-09-30）：miuix 的弹层**连同变暗遮罩**
                // 渲染在 `Scaffold` 的 `popupHost` 槽位里（javap 核过 Scaffold 的参数表；
                // `MiuixPopupUtils.PopupLayout` 是同层级绘制 + `zIndex`，不是独立窗口）。
                // 底栏以前放在 Scaffold **外面**，于是它盖在遮罩之上 —— 弹出下拉时**不跟着变暗**。
                //
                // 但仍要留在 `layerBackdrop` 那层**外面**：进了那层就会被当成背景录进玻璃的副本里
                //（玻璃会把自己录进去）。所以位置是"Scaffold 内、layerBackdrop 外"。
                // 底栏：miuix 官方的玻璃悬浮导航条（2026-11-19 替换掉从 HyperChanger 移植的那套）。
                //
                // 位置由组件自己的 `defaultWindowInsetsPadding` 决定（系统导航条 inset + 官方
                // 浮动边距）；两侧各留 24dp 等价于原来"0.88 屏宽"。
                // ⚠️ `isDynamicColor` 为 false（我们只在开 Monet 时用动态色），选中态走
                // `onBackground` + 低 alpha 的指示块，与官方 example 一致。
                GlassNavigationBar(
                    items = Tab.entries.map { item ->
                        GlassNavigationItem(icon = item.icon, label = item.label)
                    },
                    selectedIndex = appPagerState.selectedPage,
                    onSelect = { appPagerState.animateToPage(it) },
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp),
                )

                // ===== 弹窗（必须留在 Scaffold 子树内：miuix 的 overlay 靠 MiuixPopupHost 消费）=====
                // 顶栏右上角重启按钮的弹窗 —— 与二级页共用一份（`RestartDialog`）。
                RestartDialog(
                    show = askRestartAod,
                    onDismissRequest = { askRestartAod = false },
                    onConfirm = { state.restartTargets(it) },
                )

                // 更新相关三个弹窗 —— 与其它弹窗一样**常驻组合树、用 show 控制**
                //（条件包裹会让 miuix 的退出动画播不出来）。
                UpdateFoundDialog(
                    show = state.updateFound != null,
                    versionName = state.updateFound?.versionName.orEmpty(),
                    versionCode = state.updateFound?.versionCode ?: 0,
                    onDismissRequest = { state.updateFound = null },
                    onConfirm = {
                        state.updateFound = null
                        // 不直接跳转：再弹「选择平台」（大陆用户多半要走网盘）
                        state.showReleaseDialog = true
                    },
                )
                UpdateFailedDialog(
                    show = state.updateFailed,
                    onDismissRequest = { state.updateFailed = false },
                )
                PlatformDialog(
                    show = state.showReleaseDialog,
                    onDismissRequest = { state.showReleaseDialog = false },
                    onPick = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { AppToast.show(context, "无法打开链接") }
                    },
                    entries = ReleaseEntries,
                )

                AppGlassDialog(
                    title = "本模块未激活",
                    summary = (when {
                        !state.activated && state.rootAvailable == false -> "请检查LSPosed与Root"
                        !state.activated -> "请检查LSPosed"
                        else -> "请检查Root"
                    // 2026-10-31：末尾这句里给档位名加引号 + 补「（免Root）」，与页面下拉里的文案逐字一致。
                    // 2026-11-05：两处「空间壁纸」补上「与光栅壁纸」—— 光栅壁纸同样有免 Root 的服务模式。
                    }) + "，如果需要在无Root下使用空间壁纸与光栅壁纸，请点击忽略，并在空间壁纸与光栅壁纸的挂载模式中选择" +
                        "“系统壁纸服务（免Root）”。",
                    show = activationAlert,
                    onDismissRequest = { activationAlert = false },
                ) {
                    val dismissState = LocalDismissState.current
                    // 整行点击的统一振动（2026-11-18）：Checkbox 自己由 miuix 内部振，文字区域补上。
                    val haptic = rememberClickHaptic()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 「以后不再弹出该弹窗」：默认不勾；**勾了并点下面的按钮才写入**。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic()
                                    activationNoMore = !activationNoMore
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "以后不再弹出该弹窗",
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(
                                state = ToggleableState(activationNoMore),
                                onClick = { activationNoMore = !activationNoMore },
                                // 玻璃弹窗专用配色（与 Normal 按钮同源），见 appCheckboxColors。
                                colors = appCheckboxColors(),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButtonLike(
                                text = "忽略",
                                onClick = {
                                    if (activationNoMore) activationPrefs.setActivationWarnDismissed(true)
                                    activationAlert = false
                                    dismissState?.invoke()
                                },
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButtonLike(
                                text = "退出",
                                primary = true,
                                onClick = {
                                    if (activationNoMore) activationPrefs.setActivationWarnDismissed(true)
                                    activationAlert = false
                                    dismissState?.invoke()
                                    // "退出" = 关掉模块界面回到桌面，不动任何系统状态。
                                    context.findActivity()?.finish()
                                },
                            )
                        }
                    }
                }

            }
        }

        // 二级页**不在这里叠**：2026-09-29 起它是独立 Activity（[MaskDetailActivity]），
        // 转场交给系统。原来那层 `AnimatedVisibility` + 两处 graphicsLayer 已整块删除。
    }
}

/**
 * 当前页顶栏的**展开态高度**（含状态栏留白）。
 *
 * 由 [TabHost] / [DetailTabHost] 实测后提供；内容容器（[TabColumn] 与三个二级页的列）用它
 * 在内容头部留白 —— 等价于"内容从顶栏下面开始"（`Column` 布局时代的旧视觉），
 * 同时内容本身仍在**全屏**滚动，滚起来之后才能从顶栏玻璃下穿过去。
 *
 * 首帧为 0（还没测到）——会有一帧"内容顶到最上"，发生在页面转场期间，肉眼不可见。
 */
internal val LocalTopBarHeight = compositionLocalOf { 0.dp }

/**
 * 当前页的「玻璃背景录制层」（miuix 0.9.5 的玻璃组件用）。
 *
 * ## 为什么需要它
 *
 * 0.9.5 的 `GlassDropdownPreference` / `GlassDialog` 都要求一个 `Backdrop` 做背景采样，
 * 而 backdrop 原本是各页 `rememberLayerBackdrop()` 建的**局部变量**：
 *  * 一级页：`MaskSwapShell` 里那份整页录制（底栏玻璃也用它，2026-11-18 提到 `MaskSwapApp`）；
 *  * 二级页：`DetailTabHost` 里那份（顶栏模糊也用它）。
 *
 * 弹窗 / 下拉的**组合位置**在这些 Host 的局部作用域之外（`WindowDialog(...)` 直接写在
 * `MaskSwapShell` / `MaskDetailActivity` 的函数体里，下拉行在各页组件深处），读不到局部变量；
 * 包成 CompositionLocal 也必须有地方 provide —— 所以统一在**调用方的最外层** provide，
 * 下游用 `LocalPageBackdrop.current` 取。
 *
 * ## 采样范围（官方规范 `guide/glass.md#background-sampling`）
 *
 * 只包含页面内容与页面底色，**不包含**底栏与弹窗自身 —— 底栏在 `Scaffold` 内、`layerBackdrop`
 * 外，弹窗在 `popupHost` 里，两者天然不在录制范围，不会自采样。
 *
 * ⚠️ 与 `LocalTopBarHeight` / `LocalPageScroll` 不同，这里用 `staticCompositionLocalOf`：
 * backdrop 的**身份**在一次会话里不变（同一个 `LayerBackdrop` 对象反复录制），换页也不换对象，
 * 没必要为它触发重组。真的拿不到（裸 Activity、首帧还没上报）就是 null，玻璃组件自动退回
 * 不透明底色 —— 官方就是这么设计的，不会崩。
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 本页的滚动状态（[TabHost] / [DetailTabHost] 各持有一份，向下提供）。
 *
 * 顶栏的收起量按**内容滚动位置** 1:1 推导（见两个 Host 里的 `snapshotFlow`），所以 Host
 * 必须拿到页面唯一的滚动状态；滚动容器（[TabColumn] 与三个二级页的列）也直接用它 ——
 * "滚动的人"和"联动的人"天然是同一个，不会出现两个滚动状态、顶栏跟错人。
 */
internal val LocalPageScroll = staticCompositionLocalOf<ScrollState?> { null }

/** 取当前页面的滚动状态：Host 提供过就用它；没提供（正常不会发生）才自建一份兜底。 */
@Composable
internal fun rememberPageScroll(): ScrollState {
    val provided = LocalPageScroll.current
    val fallback = rememberScrollState()
    return provided ?: fallback
}

/**
 * 页面级模糊背景（2026-10-15）。
 *
 * 与底栏玻璃用的 [backdrop]（整页录制、**含顶栏**）刻意分开：顶栏要采样的源里不能有它自己
 * （否则玻璃把自己录进去、递归成鬼影）。所以这里每页各建一份、只录**内容层**，
 * 交给 [GlassTopAppBar] 的 `backdrop` —— 2026-11-18 起顶栏换成 miuix 的 `GlassTopAppBar`，
 * `progressiveBlur = true` 时它会在内部再录一层、画出「顶部实、向下渐隐」的渐变模糊带。
 *
 * `drawRect(surface)` 先铺不透明底色：录制内容若有透明区域，模糊会把颜色扩散到透明像素、
 * 出现色块（官方指南原文）。
 */
@Composable
private fun rememberPageBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * 一页 = 自己的顶栏 + 可滚动内容。
 *
 * 顶栏刻意放在**页内**而不是 Scaffold 的 topBar 槽位：这样翻页时整页（含大标题）一起横向滑走，
 * 而不是"页面滑完了标题才突然换字"。折叠仍然是 miuix 的 [GlassTopAppBar] + ScrollBehavior 那套
 * （展开时大字号居左 → 上滑收成小标题居中），只是每一页各有一套 state，互不影响 ——
 * 也就不再需要在翻页时手动把 heightOffset 归零了。
 *
 * ## 顶栏收起怎么驱动（2026-10-15 修正）
 *
 * **不用** `scrollBehavior.nestedScrollConnection`：miuix 默认的 ExitUntilCollapsed 会把
 * 上滑手势整个吃去收大标题 —— 大标题收完内容才开始滚（用户实测"内容不跟着动"，否决）。
 *
 * 现在内容**自由滚动**（没有任何消费者），页面的滚动状态（[LocalPageScroll]）由本组件持有并
 * 下发，再用 [snapshotFlow] 把滚动位置 1:1 写成 `heightOffset`：内容滚多少、大标题收多少。
 * （顶栏玻璃也天然对上：收起完成那一刻，内容恰好滑到顶栏底下。）
 */
@Composable
private fun TabHost(
    title: String,
    onRestartAod: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 只借 `MiuixScrollBehavior` 携带 state（顶栏读它的 `heightOffset`）；**不挂**它的
    // `nestedScrollConnection`（原因见上面的函数说明）。收起量由内容滚动位置驱动（见下方）。
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(appBarState)
    // 顶栏右上角的重启按钮：四个页签各有一个，都从这个 [TabHost] 出。
    val haptic = rememberClickHaptic()
    // 2026-10-15 顶栏均匀模糊：结构从「Column（顶栏占位 + 内容在下方）」改成
    // 「Box 叠层（内容全屏 + 顶栏浮层盖在上面）」—— miuix 官方的顶栏玻璃就是这个结构
    // （Scaffold 把 topBar 和 content 都放 (0,0)、content 先画）：只有内容能滚进顶栏区域，
    // 顶栏玻璃才有采样的东西。顶栏原来的"占位"改由内容自己用 [LocalTopBarHeight] 留白
    // （见 [TabColumn]）。
    val blurBackdrop = rememberPageBlurBackdrop()
    // 顶栏展开态高度（含状态栏留白）：记"最大值"（折叠只会变矮），内容头部留白用它。
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { barHeightPx.toDp() }
    // 内容滚动位置 → 顶栏收起量（1:1）。直接写 `heightOffset`：state 的 setter 自带
    // [heightOffsetLimit, 0] 钳制 —— 滚过收起上限自动停在收起态，回滚时按位置精确展开
    // （纯位置推导，没有累积误差）。
    val pageScroll = rememberScrollState()
    LaunchedEffect(pageScroll, appBarState) {
        snapshotFlow { pageScroll.value }
            .collect { value -> appBarState.heightOffset = -value.toFloat() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier),
        ) {
            CompositionLocalProvider(
                LocalTopBarHeight provides barHeight,
                LocalPageScroll provides pageScroll,
            ) {
                content()
            }
        }
        // 玻璃顶栏（miuix 0.9.5）：渐变模糊带 + 大标题收起 + 小标题模糊过渡。
        // `progressiveBlur` 内部会再录一层 backdrop（= 页面内容），画出「顶部实、向下渐隐」的模糊带；
        // 高度 = (状态栏 + 折叠高度 + bottomContent + 20dp) × 1.2，不占布局。
        GlassTopAppBar(
            title = title,
            // ⚠️ 必须显式传，不能让它从 scrollBehavior 推导：`isContentScrolled` 的实现是
            // `scrollBehavior?.state?.contentOffset ?: visualContentOffset`，而 contentOffset 是
            // 非空 Float —— 只要传了 scrollBehavior 就永远读它。我们不挂 nestedScrollConnection
            //（见上面 LaunchedEffect 的说明），contentOffset 恒 0 ⇒ 判"没滚" ⇒ 玻璃圆钮的背景
            // 层 alpha 恒 0（按钮只剩图标、没有玻璃底）。
            isContentScrolled = pageScroll.value > 0,
            largeTitle = title,
            backdrop = blurBackdrop,
            scrollBehavior = scrollBehavior,
            // 有符号内容位置（向上滚为负）：collapseRamp 用 `(-offset - RampStart) / RampDistance`，
            // 与上面 `heightOffset = -value` 同号，别传成正值。
            visualContentOffset = { -pageScroll.value.toFloat() },
            progressiveBlur = true,
            modifier = Modifier.align(Alignment.TopCenter),
            // 测**前景**高度（不含背景带向外延伸的 bandOverhang）；内容头部留白靠它。
            contentModifier = Modifier.onSizeChanged { size ->
                if (size.height > barHeightPx) barHeightPx = size.height
            },
            actions = {
                GlassIconButton(onClick = { haptic(); onRestartAod() }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "重启锁屏编辑器",
                    )
                }
            },
        )
    }
}

/**
 * 二级页的承载 —— 与一级的 [TabHost] 相比**多一个返回按钮**（`navigationIcon`）、
 * 并且**自带一个 [Scaffold]**（见下）。
 *
 * 大标题、右上角重启按钮都跟一级保持一致（用户明确要求：「所有二级界面仍有大标题……
 * 所有二级界面右上角仍有重启按钮」）。
 *
 * 2026-09-29 由 `private` 改为 `internal`：二级页现在是独立 Activity（[MaskDetailActivity]），
 * 它在另一个文件里用这个承载。
 *
 * ## ⚠️ Scaffold 不能省（2026-10-11：「下拉栏点不开」的根因）
 *
 * miuix 的页内弹层（`OverlayListPopup` 的下拉列表，连同那层变暗遮罩）是**同层级绘制**，
 * 必须由 **`Scaffold` 的 `popupHost` 槽位**（默认就是 `MiuixPopupHost`）来渲染 ——
 * 机制与 [MaskSwapScreen] 里那段"底栏要在 Scaffold 里面"的说明是同一件事。
 *
 * 一级页的 Scaffold 在 `MaskSwapScreen`；二级页是**独立 Activity**，没有那一层（它只是
 * `Column` + 顶栏）。少了它，`OverlayListPopup` 把状态塞进的是**无人渲染的默认空表**
 * （`LocalPopupStates` 的 static 默认值，`Scaffold` 没 provide 时就是它），
 * 于是二级页里的下拉「点了没反应」。所以二级页在这里**自带一个 Scaffold**。
 *
 * `contentWindowInsets = WindowInsets(0)` 与一级页一致：顶栏自己按 `systemBars.top`
 * 留白并把底色铺到状态栏区域，这里若再消费一次 inset 会扣两次。
 */
@Composable
internal fun DetailTabHost(
    title: String,
    onBack: () -> Unit,
    onRestartAod: () -> Unit,
    /**
     * 把本页的玻璃录制层上报给调用方（2026-11-18）。
     *
     * 二级页的弹窗写在 `DetailTabHost` **外面**（`MaskDetailActivity` 的 Box 里），
     * 读不到这里的局部变量；上报之后由调用方在更外层 provide [LocalPageBackdrop]，
     * 弹窗与页面内深处的下拉行就都能拿到。传 null 表示运行时不支持着色器（玻璃退化）。
     */
    onPageBackdrop: (Backdrop?) -> Unit = {},
    /**
     * 浮层槽位（2026-11-18）：弹窗必须挂在这里，不能挂在 Host 外面。
     *
     * miuix 的 `GlassDialog` 是**同层级 overlay**（`OverlayDialog` → `DialogContentLayout`
     * → `LocalDialogStates` → `Scaffold` 的 `MiuixPopupHost` 消费），调用点不在 Scaffold
     * 子树里时，状态塞进的是**没人消费的默认空表**，表现为"弹窗点了没反应、也不报错"。
     * 旧的 `WindowDialog` 是独立窗口、不受此限，换玻璃弹窗后才有了这条硬约束。
     */
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // 收起驱动与一级页 [TabHost] 同一套（2026-10-15 修正）：只借 `MiuixScrollBehavior`
    // 携带 state，**不挂**它的 `nestedScrollConnection`（ExitUntilCollapsed 会先吃光上滑
    // 手势收大标题、内容不滚 —— 用户实测否决）；收起量由内容滚动位置 1:1 驱动，见下方。
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(appBarState)
    val haptic = rememberClickHaptic()
    // 系统返回键：先关掉二级页，而不是把应用整个退掉。
    BackHandler { onBack() }
    // 与一级页 [TabHost] 同款（2026-11-18 换成玻璃顶栏）：内容全屏 + 顶栏浮层。
    val blurBackdrop = rememberPageBlurBackdrop()
    // 上报给 [MaskDetailActivity]，让写在 Host 外面的弹窗也能拿到同一份录制层。
    SideEffect { onPageBackdrop(blurBackdrop) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { barHeightPx.toDp() }
    // 内容滚动位置 → 顶栏收起量（1:1），与一级页 [TabHost] 同一套（原理见那里的说明）。
    val pageScroll = rememberScrollState()
    LaunchedEffect(pageScroll, appBarState) {
        snapshotFlow { pageScroll.value }
            .collect { value -> appBarState.heightOffset = -value.toFloat() }
    }
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier),
            ) {
                CompositionLocalProvider(
                    LocalTopBarHeight provides barHeight,
                    LocalPageScroll provides pageScroll,
                ) {
                    content()
                }
            }
            // 与一级页同款玻璃顶栏（见 [TabHost] 里的说明），多一个返回按钮。
            GlassTopAppBar(
                title = title,
                // 同 [TabHost]：必须显式传，从 scrollBehavior 推导只会拿到恒 0 的 contentOffset。
                isContentScrolled = pageScroll.value > 0,
                largeTitle = title,
                backdrop = blurBackdrop,
                scrollBehavior = scrollBehavior,
                visualContentOffset = { -pageScroll.value.toFloat() },
                progressiveBlur = true,
                modifier = Modifier.align(Alignment.TopCenter),
                contentModifier = Modifier.onSizeChanged { size ->
                    if (size.height > barHeightPx) barHeightPx = size.height
                },
                navigationIcon = {
                    GlassIconButton(onClick = { haptic(); onBack() }) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    GlassIconButton(onClick = { haptic(); onRestartAod() }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "重启锁屏编辑器",
                        )
                    }
                },
            )
            // 弹窗等浮层：必须在 Scaffold 子树内（本函数自带 Scaffold，见上方 KDoc）。
            overlay()
        }
    }
}

/** 每个页签的滚动容器。滚动状态由 Host 提供（[LocalPageScroll]）—— 顶栏收起跟它 1:1 联动。 */
@Composable
internal fun TabColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            // 顶部留白 = 顶栏展开态高度（[TabHost] 实测提供，2026-10-15 顶栏均匀模糊）：
            // 内容初始从顶栏下面开始，滚动时这段留白先滚走、内容从顶栏玻璃下穿过。
            // 底部留白：给悬浮底栏留出空间，内容能滚到它下面，最后一项也不会被挡住。
            .padding(top = LocalTopBarHeight.current, bottom = 120.dp),
    ) {
        content()
    }
}

/**
 * 关于页的状态卡 —— **样式照 HyperMusicCover 的激活卡搬过来**（2026-09-29 用户指定）。
 *
 * ⚠️ **只搬了样式**：检测逻辑、状态判定、`settled` 语义全是我们自己的（用户明确"我们只是搬样式"）；
 * 卡片**不可点**（HyperMusicCover 那张点一下会重新检测，我们不做那套交互）。
 *
 * ## 布局不是"几行文字排成一列"，而是三个盒子叠着放
 *
 * 照搬它的做法（它自己的注释写明是学 KernelSU 的 HomeMiuix 卡片）：
 * 左上 = 状态 + 版本号，左下 = `HyperExtreme`，右下 = 一个 **110dp 的大图标故意溢出卡片**
 * （`offset(27dp, 31dp)` 把它推出右下角，溢出部分靠卡片裁切）——
 * 左下与右下在对角上互相抵住，中间那片空正是"不显拥挤"的关键；三样排成一列就会挤在顶部。
 *
 * 外层那个 `Row(IntrinsicSize.Min)` 是**必需的**：里面几个盒子都是 `fillMaxSize()`、自己贡献不了高度，
 * 没有这一层卡片会塌成一行文字那么高（这条也是从它那儿学来的）。
 *
 * ## 三态配色（用户指定）
 *
 * **激活 = 绿、有 LSPosed 无 Root = 橙、无 LSPosed = 红**。卡面是固定色值（不跟动态取色），
 * 深浅色各一套；图标用 Material Icons 的 `check_circle_outline` / `error_outline`（见 `ui/icons/StatusIcons.kt`）。
 */
@Composable
internal fun BigStatusCard(
    activated: Boolean,
    hasRoot: Boolean?,
    version: String,
    /** 激活状态是否**已经确认**。[MaskUiState.activationSettled] —— false 时不能急着说"未激活"。 */
    settled: Boolean,
) {
    // "已激活但没有 root" = 模块装上也用不了（替换、探壁纸尺寸都要 su），所以单独一档配色（橙）。
    val noRoot = activated && hasRoot == false
    // 还没确认、也没读到激活：LSPosed 的 binder 是跨进程送来的，首帧常常早于它到达。
    // 这会儿就写"未激活"会白闪一下（R8 加快启动后必现），所以先按中性色说"正在检测…"。
    val probing = !settled && !activated
    val active = activated && !noRoot
    // 卡面是固定色值、不跟动态取色，所以深浅色得自己判（和 RoleCard 一个套路）。
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = when {
        probing -> MiuixTheme.colorScheme.surfaceContainerHigh
        active -> if (dark) StatusCardGreenDark else StatusCardGreenLight
        noRoot -> if (dark) StatusCardAmberDark else StatusCardAmberLight
        else -> if (dark) StatusCardRedDark else StatusCardRedLight
    }
    val glyphTint = when {
        probing -> MiuixTheme.colorScheme.onSurfaceContainerVariant
        active -> StatusCardGreenGlyph
        noRoot -> StatusCardAmberGlyph
        else -> StatusCardRedGlyph
    }
    val statusText = when {
        probing -> "正在检测…"
        !activated -> "模块未激活"
        noRoot -> "模块未激活（无 Root）"
        else -> "模块已激活"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            // 用 miuix 官方的 Card（圆角、内容色、按压反馈都跟系统一致）；`insideMargin` 用官方默认值。
            // 文字颜色**刻意不显式指定**：默认 contentColor 在深浅两套卡面上都读得清
            //（HyperMusicCover 也只传 `color`）。
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = containerColor),
        ) {
            Box {
                // 右下角的大图标：先铺满，再用 offset 推出右下角，溢出的一角被卡片裁掉。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        imageVector = if (active) CheckCircleOutlineIcon else ErrorOutlineIcon,
                        contentDescription = null,
                        tint = glyphTint,
                        modifier = Modifier.size(110.dp),
                    )
                }
                // 左下角：应用名（HyperMusicCover 那里放的是模块名）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 10.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(text = "HyperExtreme", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                // 左上角：状态 + 版本号。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(text = statusText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(1.dp))
                        // `versionName` 是 "1.0 Beta5"（不带 v），界面统一拼一个 "v" 显示。
                        Text(text = "v$version", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 状态卡的三个色值。
 *
 * 绿、红两套是**照 HyperMusicCover 抄的**：`0xFFDFFAE4` / `0xFF1A3825`（图标 `0xFF36D167`）、
 * `0xFFFDE8E8` / `0xFF3D1C1C`（图标 `0xFFDC3545`）。
 * **橙那套是我们自己配的**（它只有"激活/未激活"两种状态，没有"无 Root"这一档），
 * 沿用同一套明度关系。
 *
 * `internal` 而不是 `private`：主页那张「未检测到Root」卡（见 `MaskTabs.HomeTab`）也用同一组色值 ——
 * 用户要求它跟状态卡的橙是**同一个橙**，共用常量才能长期保证一致。
 */
internal val StatusCardGreenLight = Color(0xFFDFFAE4)
internal val StatusCardGreenDark = Color(0xFF1A3825)
internal val StatusCardRedLight = Color(0xFFFDE8E8)
internal val StatusCardRedDark = Color(0xFF3D1C1C)
internal val StatusCardAmberLight = Color(0xFFFDEEDC)
internal val StatusCardAmberDark = Color(0xFF3A2B14)
internal val StatusCardGreenGlyph = Color(0xFF36D167)
internal val StatusCardAmberGlyph = Color(0xFFE08A2B)
internal val StatusCardRedGlyph = Color(0xFFDC3545)

/**
 * 从 `LocalContext.current` 给的 Context 里解出 Activity。
 *
 * 它通常是 Activity 本身，但也可能是包了一层的 ContextWrapper（主题包装之类），所以逐层往上找。
 * 解不出来返回 null —— 调用方自己兜住，不能因为拿不到 Activity 就崩。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
}.getOrDefault("1.0")

/** 系统属性（MIUI/HyperOS 版本号都在这里）。 */
internal fun systemProperty(key: String): String? = runCatching {
    val clazz = Class.forName("android.os.SystemProperties")
    clazz.getMethod("get", String::class.java).invoke(null, key) as? String
}.getOrNull()?.takeIf { it.isNotBlank() }

internal fun osVersionName(): String =
    systemProperty("ro.mi.os.version.name")
        ?: systemProperty("ro.miui.ui.version.name")
        ?: "未知"

internal fun androidVersionName(): String =
    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

internal fun deviceName(context: Context): String = runCatching {
    android.provider.Settings.Global.getString(context.contentResolver, "device_name")
}.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

// `PendingImport` 与 `sameSize` 2026-09-29 一并搬去 `MaskDetailActivity.kt` —— 它们只服务
// 二级页的"选图 → 尺寸校验"那条链，一级页面已经不用了。

/**
 * 对话框里的两个按钮：主按钮用 miuix 的 primary 配色。
 *
 * 2026-09-29 由 `private` 改为 `internal`：二级页（[MaskDetailActivity]）与共用的
 * [RestartDialog] 都在别的文件里用它。
 *
 * 2026-11-03 加 [danger]：**警示档**，给「删除此组合？」这类不可恢复的确认用；
 * 默认 false，原有调用一个字都不用改。
 *
 * ## 2026-11-18：换 `GlassDialogButton`（miuix 0.9.5）
 *
 * 弹窗外壳换成玻璃之后，原来的 `TextButton` + `textButtonColors()`（`secondaryVariant`
 * 底 + `onSecondaryVariant` 字）在玻璃面上对比度不够，用户实测「看不清」。
 * 改成官方的 `GlassDialogButton` —— 它的三档配色就是为 `glassDialogSurface` 调的：
 *
 * | 档 | 底 | 字 |
 * | --- | --- | --- |
 * | `Normal` | `onBackground @ 6%(浅)/14%(深)` | `onBackground @ 0.9` |
 * | `Primary` | `primary` | `onPrimary` |
 * | `Destructive` | 同上淡底 | **`error` 红字** |
 *
 * ⚠️ **`danger` 的视觉变了**（2026-11-18 用户定「以官方为准」）：旧版是 **`error` 红底 +
 * `onError` 白字**，新版是**淡底 + 红字**。这是官方玻璃对话框的写法，视觉更轻。
 *
 * 函数签名与 `RowScope` 扩展不变 ⇒ 28 个调用点零改动；`weight(1f)` 保留在内部，
 * 外层的 `Row(SpaceBetween)` 照旧平分。
 */
@Composable
internal fun RowScope.TextButtonLike(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    // 所有弹窗的按钮都从这里出，振动统一加在这一层。
    val haptic = rememberClickHaptic()
    // 用 miuix 的 Button 而不是 GlassDialogButton：前者走 squircleSurface + LocalIndication，
    // 有平滑圆角与按压过渡；后者是 RoundedCornerShape + 瞬时按压遮罩（实测"突然"）。
    // 配色直接借官方玻璃按钮那一套（返回的就是 basic 的 ButtonColors）。
    Button(
        onClick = { haptic(); onClick() },
        modifier = Modifier.weight(1f),
        colors = GlassDialogButtonDefaults.buttonColors(
            when {
                danger -> GlassDialogButtonRole.Destructive
                primary -> GlassDialogButtonRole.Primary
                else -> GlassDialogButtonRole.Normal
            },
        ),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}
