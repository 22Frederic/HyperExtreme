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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import com.frederic22.hyperextreme.ui.effect.BgEffectBackground
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.squircle.squircleClip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.R
import com.frederic22.hyperextreme.data.AodPackageName
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.HomeBg
import com.frederic22.hyperextreme.data.HomeCover
import com.frederic22.hyperextreme.spatial.SpatialCrop
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale
import kotlin.math.max
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
internal fun HomeTab(
    state: MaskUiState,
    onOpen: (DetailPage) -> Unit,
    /** 「当前没有应用壁纸」时点卡片 → 切到「功能」页签（2026-11-24 用户要求）。 */
    onGoFunction: () -> Unit,
) {
    val context = state.context

    // 「当前运行」判据与冲突弹窗共用 [WallpaperConflict]（2026-11-08）——
    // 服务模式要查 `WallpaperManager`，所以在**进前台**时算一次，别塞进重组。
    //
    // 2026-11-25：组合期改读进程缓存（[WallpaperConflict.cached]）。主页被 pager 销毁重建时
    //（功能页 ↔ 设置页会把主页踢出 / 拉进组合树）不再跑跨进程查询 —— 原来每次重建最多
    // 4 次 `getWallpaperInfo`，一次翻页就够掉一帧。ON_RESUME 仍走 `current` 强制检测：
    // 它早于首帧，卡片文字的即时性不变。
    var running by remember { mutableStateOf(WallpaperConflict.cached(context, state)) }
    // 「主页背景同步应用状态」的重算标记（2026-11-25 用户报「同一条链里换组合不刷新」）：
    // `RunningWallpaper` 只带 chain + mode，同一个链里换 key / id 时它**不变** ⇒ 拿它当
    // 刷新判据不可靠。二级页是独立 Activity，切完组合回主页必走 ON_RESUME，
    // 所以每次回前台就把这个号 +1，逼着重新解析一遍封面。
    var coverEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                running = WallpaperConflict.current(context, state)
                coverEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 视差是 State：在本页关掉它时不会走 ON_RESUME，单独跟一下。
    // 检测本身放 IO —— 组合期和首帧都不该为一次跨进程查询等。
    val parallaxNow = state.parallaxActive
    LaunchedEffect(parallaxNow) {
        running = withContext(Dispatchers.IO) { WallpaperConflict.current(context, parallaxNow) }
    }

    // 主页 = 壁纸 + 三张**定住**的卡（2026-11-24 用户要求，不再滚动）；
    // 开了「隐藏激活卡片」且确实已激活时只剩两张（2026-11-25）。
    //
    // 布局从 `TabColumn`（内部 `verticalScroll`）换成固定 Box：
    //   · 壁纸层铺满，被底栏玻璃 / 顶栏渐进模糊的录制层包含 ⇒ 两处玻璃都透壁纸色；
    //   · 三张卡整体贴底，最后一张（捐赠）离底 120dp —— 复刻关于页滚到底时最后一张卡的位置；
    //   · 卡片区上沿加一条**反向**渐进模糊（顶栏是「上实下虚」，这条是「下实上虚」），
    //     让卡片区与上方壁纸平滑衔接。
    // 「隐藏主页激活卡片」（2026-11-25）：**只在模块已激活时生效**（与设置里的 summary 一致）。
    // 未激活 / 还在检测时那张卡必须留着 —— 它是"为什么没生效"的唯一提示。
    val hideStatusCard = state.hideHomeStatusCard && state.activated
    // 「主页背景同步应用状态」（2026-11-25）：把正在应用那条链的卡片封面当壁纸。
    // 解析 + 解码都在 [HomeCover] 里（**进程级缓存**）：
    //   · 页被 pager 销毁重建时 `image` 还是上一张 ⇒ 首帧不闪 `home_sample`；
    //   · 同一个文件（路径 + mtime + size）命中缓存 ⇒ 不重新解码、不重复分配 13MB；
    //   · 重复请求单飞合并 ⇒ ON_RESUME 的 `coverEpoch++` 不会打断冷启动那一轮。
    // 这个 effect 只负责"什么时候该重算"：换了链 / 开关 / 自定义背景版本 / 回前台。
    LaunchedEffect(running, state.homeBgFollowApp, state.homeBgCustom, coverEpoch) {
        HomeCover.request(context, state.homeBgFollowApp)
    }
    // **同一个 `Image` 节点只换 painter** ⇒ `wallpaper` / `cardBackdrop` 那几层录制自动跟随，
    // 不用为换图重建节点。没图（null）才回退 `home_sample`。
    val coverImage = HomeCover.image
    // 「显示区域」（2026-11-27）：平面口径，key = `home_bg`。
    //
    // ⚠️ **只对「自定义背景」那张图生效**：这组 `cx/cy/zoom` 是用户在调整页为 `custom.jpg`
    // 定的；「背景同步应用状态」开着时显示的是**那条链的封面**，它没有自己的显示区域，
    // 必须铺满居中。以前不判来源 ⇒ 同步封面被按同一组 `cx/cy` 平移（用户 2026-11-27 报的
    // 「错位，且方向/幅度与调整页完全一致」）。判据由 [HomeCover.imageSource] 给。
    val cropActive = HomeCover.imageSource == HomeCover.Source.CUSTOM
    val homeCrop = remember(state.homeBgCustom, cropActive) {
        if (cropActive) SpatialCrop.read(context, HomeBg.CROP_KEY) else SpatialCrop.DEFAULT
    }
    // 原图尺寸（px）—— 手动 cover 要它算「铺满所需的最小缩放」。
    // 回退 `home_sample` 时为 0 ⇒ 退化成「按容器尺寸、不额外缩放」，与改版前视觉一致。
    val coverW = coverImage?.width ?: 0
    val coverH = coverImage?.height ?: 0
    val wallpaper = rememberLayerBackdrop()
    // 三张卡的磨砂（2026-11-25 用户要求，参考关于页）：走**底栏那条 AGSL 管线**
    //（`glassPanel` + `PuredThinGlass` + 描边，见 [rememberGlassCardModifier]）。
    // 壁纸 `Image` 铺满且不透明 ⇒ 不像关于页那样需要先 `drawRect` 铺底。
    //
    // 采样源是下面那层**合成层**（2026-11-25 方案 A）：壁纸 + 那条 60dp 渐进模糊。
    //
    // 底栏采的是 `TabHost` 的整页内容层 —— 壁纸在那层里早就被这条 60dp 糊过了；卡片原来只采
    // 壁纸原图（`wallpaper`，`Image` 自己的录制），20dp 材料直接作用在清晰图上 ⇒ 看着比底栏「透」。
    // 包完这层，卡片内 = 60dp 糊 + 材料 20dp，与底栏的输入一致。
    val cardBackdrop = rememberLayerBackdrop()
    val cardBlur = rememberGlassCardModifier(cardBackdrop, tone = GlassCardTone.NavigationBar)
    // 与顶栏那条带同高：`GlassTopAppBar` 里是 `(遮住的高度 + 20dp) × 1.2`，
    // 这里用「顶栏前景高度」近似「遮住的高度」。
    val bandHeight = (LocalTopBarHeight.current + 20.dp) * 1.2f
    // 渐进模糊的区间（2026-11-24 用户定义）：
    //   · 从**屏幕底部**到「激活卡从下往上 1/4 处」= 满强度（卡片区背后整片是糊的）；
    //   · 从那里再往上 `bandHeight`（= 顶栏那条的长度）线性衰减到 0。
    // `ProgressiveBlur` 的比例沿 `angle = 270`（从底往顶）量，所以换算成「距屏幕底的距离 / 页面高」。
    //
    // ⚠️ **位置用固定值，不做动态测量**（2026-11-25 用户定）：三张卡的高度 / 间距是死的，
    // 之前用 `boundsInRoot` + `onSizeChanged` 两路测量，首帧跨帧拼值会算错，表现为「刚进模块时
    // 模糊位置乱跑、点一下卡片才恢复」。页面高用 `BoxWithConstraints` 的 `maxHeight`（组合期同步可得），
    // 卡片区的终点直接用常量 [HomeFadeStartFromBottom]。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 藏了激活卡 ⇒ 卡片区整体下移，满强度终点跟着下移「激活卡高 + 一个间距」
        //（2026-11-25 用户要求：渐变模糊再向下同样一段）。不藏时仍是原常量。
        val fadeStartFromBottom = if (hideStatusCard) {
            (HomeFadeStartFromBottom - HomeStatusCardBlockHeight).coerceAtLeast(0.dp)
        } else {
            HomeFadeStartFromBottom
        }
        // **带的总高 = 满强度段（屏幕底 → 卡片区上沿）+ 衰减段 [bandHeight]**（2026-11-26）。
        //
        // 2026-11-26 之前是「整页一层 + 按比例切 startF/endF」；现在改成「只在这一段上画
        // 0→1 渐变」，两者**视觉效果完全等价**，但结构才和 `GlassTopAppBar` 一致 —— 照抄它的
        // `colors` / `scrim` 时，那些层只作用在这条带的范围内，不会盖满整页。
        val fadeBandHeight = fadeStartFromBottom + bandHeight
        // 下面这套参数逐项照 `GlassTopAppBar`（rc01 版，2026-11-26 用户定）：
        //   curve 0.25 / radius 80 / colors = surface 20% / scrim 0.50→0 / graphicsLayer alpha=1。
        // 唯一按方向镜像的是渐变与 scrim（顶栏 Top、这里 Bottom）。
        val surfaceColor = MiuixTheme.colorScheme.surface
        val homeBlendColors = remember(surfaceColor) {
            listOf(BlendColorEntry(surfaceColor.copy(alpha = 0.2f)))
        }
        val homeBlurColors = BlurDefaults.blurColors(blendColors = homeBlendColors)
        val homeScrimBrush = remember(surfaceColor) {
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.10f to surfaceColor.copy(alpha = 0.05f),
                0.25f to surfaceColor.copy(alpha = 0.16f),
                0.5f to surfaceColor.copy(alpha = 0.32f),
                0.75f to surfaceColor.copy(alpha = 0.44f),
                1f to surfaceColor.copy(alpha = 0.50f),
            )
        }
        // 这一层只干一件事：把「壁纸 + 那条 60dp 渐进模糊」的**合成结果**录给卡片采样
        //（2026-11-25 方案 A）。`wallpaper` 仍是内层、`progressiveTextureBlur` 照旧采它。
        // ⚠️ 卡片在下面 `Column` 里、是这层 Box 的**兄弟** ⇒ 不会把自己录进 backdrop。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(cardBackdrop),
        ) {
            // ⚠️ **壁纸层用 `Canvas` + `drawImage`，不用 `Image`**（2026-11-27 用户实测两次）：
            // ① `ContentScale.Crop` 会先把图按屏幕比例裁掉两侧 ⇒ 被裁的永远调不出来；
            // ② `ContentScale.None` 也没用 —— 可绘制区域就是 modifier 尺寸，
            //    先裁到容器再缩放 ⇒「屏幕比例的一小块再缩小」；
            // ③ `graphicsLayer` 缩放后超出父容器，Compose 不裁子元素 ⇒ 会画到相邻 pager 页
            //    （用户截图：主页壁纸出现在「功能」页左侧）。
            // `drawImage` 的 dst 矩形任意、DrawScope 天然裁切，三个问题一起解决。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(wallpaper)
                    .clipToBounds(),
            ) {
                val img = coverImage
                if (img != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pw = coverW.toFloat()
                        val ph = coverH.toFloat()
                        // zoom 下限 = 1 ⇒ s ≥ base ⇒ 图永远铺满（与预览页同一套公式）
                        val s = max(size.width / pw, size.height / ph) * homeCrop.zoom
                        val dstW = pw * s
                        val dstH = ph * s
                        drawImage(
                            image = img,
                            dstOffset = IntOffset(
                                (size.width / 2f - homeCrop.cx * dstW).roundToInt(),
                                (size.height / 2f - homeCrop.cy * dstH).roundToInt(),
                            ),
                            dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                } else {
                    // 没有可用封面 ⇒ 回退内置样例图（按容器比例铺满，不参与「显示区域」）。
                    Image(
                        painter = painterResource(R.drawable.home_sample),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 卡片区上沿的渐进模糊（2026-11-26 整套照 `GlassTopAppBar` 的带搬）：
            // 只覆盖 [fadeBandHeight] 这一段（底满 → 顶 0），绘制在卡片**下面** ⇒
            // 卡片之间的缝隙能看到「模糊的壁纸」，卡片本身不受影响。
            // ⚠️ 别用 `textureBlur + DstIn` 手搓：那只是让模糊层变透明、半径恒定（2026-11-24 踩过）。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(fadeBandHeight)
                    .graphicsLayer { alpha = 1f }
                    .progressiveTextureBlur(
                        backdrop = wallpaper,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Bottom.copy(curve = 0.25f),
                        blurRadius = 80f,
                        colors = homeBlurColors,
                        enabled = true,
                    )
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = homeScrimBrush)
                    },
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 与 [TabColumn] 一致（2026-11-25）：120 → 106dp，滚到底/贴底时最后一张卡
                // 与底栏玻璃顶边留 24dp。
                .padding(bottom = 106.dp),
        ) {
            // 模块激活状态：**永远是第一张**（除非开了「隐藏主页激活卡片」且确实已激活）。
            if (!hideStatusCard) {
                BigStatusCard(
                    activated = state.activated,
                    hasRoot = state.rootAvailable,
                    version = appVersion(context),
                    settled = state.activationSettled,
                    // `noRoot` 档整卡可点 → 重测 su（原「未检测到Root」卡的功能，2026-11-24 并进来）。
                    onNoRootClick = { state.refreshRoot() },
                    // 磨砂卡面（2026-11-25）：状态色由它内部按三态淡染。
                    glassBackdrop = cardBackdrop,
                )
            }
            // 「当前运行」卡：**常驻**（2026-11-24）—— 没有应用时显示空态并跳「功能」页签。
            RunningWallpaperCard(
                current = running,
                onOpen = onOpen,
                onGoFunction = onGoFunction,
                cardModifier = cardBlur,
            )
            // 捐赠卡（2026-10-29）：整行可点 → 爱发电主页。**固定最后一张**（2026-11-24）。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlur),
                // 磨砂卡：Card 自己必须透明，否则会盖住 `textureBlur` 画的模糊层（2026-11-25）。
                colors = CardDefaults.defaultColors(Color.Transparent, MiuixTheme.colorScheme.onSurface),
                insideMargin = PaddingValues(0.dp),
            ) {
                LinkRow(title = "通过捐赠支持开发者", summary = null) {
                    openUrl(context, DonateUrl)
                }
            }
        }
    }
}

/**
 * 主页渐进模糊的「满强度终点」距屏幕底部的距离（2026-11-25）。
 *
 * 构成（自底向上）：Column 底 padding 106 + 捐赠卡 ~56 + 间距 12 + 当前应用卡 ~74 + 间距 12
 * + 激活卡的 3/4（激活卡 ~98，取 1/4 分界）≈ 336dp，**实机再下移 48 + 24dp**（用户 2026-11-25 调）。
 *
 * 2026-11-25：底留白 120 → 106（卡片与底栏留 24dp），这个值同步 278 → **264dp**；
 * 「隐藏激活卡片」那一态（减 [HomeStatusCardBlockHeight]）自动跟着减，两态一起下移 14dp。
 *
 * 用户明确「三张卡不会变、位置可控」⇒ 不做运行时测量（测量版会在首帧算出错位置、要点击才恢复）。
 * 卡片高度以后若调整，改这个常量即可。
 */
private val HomeFadeStartFromBottom = 264.dp

/**
 * 激活卡整块让出的高度（2026-11-25）。
 *
 * 构成：激活卡 ~98dp + 与下一张卡之间的 12dp 间距。只在「隐藏主页激活卡片」打开时用来把
 * [HomeFadeStartFromBottom] 往下挪同样一段（用户原话：「渐变模糊再向下激活卡片的高度 + 一个卡片间距」）。
 *
 * 与 [HomeFadeStartFromBottom] 一样是**固定常量、不做测量** —— 测量版会在首帧算错（见上）。
 * 激活卡高度以后若调整，改这里。
 */
private val HomeStatusCardBlockHeight = 110.dp

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
 * 主页的「当前运行」卡（2026-11-08，2026-11-24 改成**常驻**）。
 *
 * 显示**真的在跑**的那一条（判据来自 [WallpaperConflict.current]，与冲突弹窗同一份）；
 * 一条都没跑时显示空态：无图标、标题「当前没有应用壁纸」、summary「点击跳转到功能页」，
 * 点击切到「功能」页签（用户指定）。
 *
 * 有应用时的排版照功能页的 [AppEntryCard]：系统图标 44dp / 圆角 10dp → 标题 `headline1` →
 * summary `body2`（`onSurfaceVariantSummary`）→ 右侧 16dp 箭头，整行可点。
 *
 * **summary 只在双模式的链上出现**：视差 / Alive 只有 LSPosed 一条，写了反而像
 * 在暗示可以换挂载方式。服务模式的措辞是「系统壁纸服务」，不带「（免Root）」。
 */
@Composable
private fun RunningWallpaperCard(
    current: RunningWallpaper?,
    onOpen: (DetailPage) -> Unit,
    onGoFunction: () -> Unit,
    /** 卡片磨砂装饰（2026-11-25）。主页传 `rememberGlassCardModifier(wallpaper)`；默认无。 */
    cardModifier: Modifier = Modifier,
) {
    val haptic = rememberClickHaptic()
    // 空态（2026-11-24）：**无 icon**，整行可点 → 切「功能」页签。
    if (current == null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp)
                .then(cardModifier),
            // contentColor 显式给 `onSurface`：透明卡不能再用 `Transparent`（文字会跟着透明）。
            colors = CardDefaults.defaultColors(Color.Transparent, MiuixTheme.colorScheme.onSurface),
            insideMargin = PaddingValues(0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { haptic(); onGoFunction() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "当前没有应用壁纸", style = MiuixTheme.textStyles.headline1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "点击跳转到功能页",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
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
        return
    }
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
            .padding(top = 12.dp)
            .then(cardModifier),
        // contentColor 显式给 `onSurface`：透明卡不能再用 `Transparent`（文字会跟着透明）。
        colors = CardDefaults.defaultColors(Color.Transparent, MiuixTheme.colorScheme.onSurface),
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
    val cardWPx = screen.width * LOCK_CARD_WIDTH_RATIO
    val cardW: Dp = with(density) { cardWPx.toDp() }
    // 卡高 = 卡宽 × 屏高 / 屏宽（与卡片流同一个比例：卡片是"屏幕的一块"）。
    val cardH: Dp = cardW * screen.height / screen.width
    // 圆角 = 系统屏幕圆角 × 卡宽比（2026-11-27）。
    val radius: Dp = with(density) { rememberCardRadiusPx(cardWPx, screen.width.toFloat()).toDp() }
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
        // 同卡片里另一个开关（详细LSPosed日志）并进了设置页的「测试」，不在这边。
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
    val cardWPx = screen.width * CARD_WIDTH_RATIO
    val cardW: Dp = with(density) { cardWPx.toDp() }
    val cardH: Dp = cardW * screen.height / screen.width
    // 圆角 = 系统屏幕圆角 × 卡宽比（2026-11-27）。
    val radius: Dp = with(density) { rememberCardRadiusPx(cardWPx, screen.width.toFloat()).toDp() }
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「自定义背景」：系统照片选择器，选完立刻按短边 1600 压成 JPEG 存进 filesDir，
    // **不持有 uri**（不需要持久权限，也不会因为相册条目被删而失效）。
    val bgPicker = rememberLauncherForActivityResult(ImagePick.One()) { r ->
        val uri = ImagePick.consume(context, r).firstOrNull()
            ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { HomeBg.save(context, uri) }
            if (ok) {
                state.applyHomeBgCustom(System.currentTimeMillis())
            } else {
                AppToast.show(context, "背景保存失败")
            }
        }
    }
    // 「显示区域调整」（2026-11-27）：独立页，平面口径（见 `HomeBgCropActivity`）。
    // 回来时只推进版本戳 ⇒ `remember(state.homeBgCustom)` 重新读 crop，不重新解码。
    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { state.applyHomeBgCustom(System.currentTimeMillis()) }
    // 「恢复到默认背景」确认框（常驻组合树、用 show 控制 —— 条件包裹会让退出动画播不出来）。
    var showResetBgConfirm by remember { mutableStateOf(false) }
    // 有没有选过自定义背景（决定「已选择」summary 与「恢复到默认背景」那一行）。
    val hasCustomBg = state.homeBgCustom > 0L
    TabColumn {
        // 「替换行为」整张卡片 2026-09-29 拆掉了（用户指定）：「替换前备份原文件」搬到二级页
        // 「锁屏遮罩替换」（那张卡片的标题**仍是「替换行为」**；该开关后于 2026-10-15 随备份/
        // 还原整组逻辑删除）；「详细LSPosed日志」并进下面的「测试」卡片。
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
            // 「Monet颜色」（2026-11-07）：miuix 支持读系统动态色（`platformDynamicColors`），
            // 打开后整套配色跟随壁纸取色。默认关 —— 等价于原来那套固定配色，老用户升级后零变化。
            SwitchRow(
                title = "Monet颜色",
                checked = state.monetColor,
                onCheckedChange = {
                    state.applyMonetColor(it)
                    onMonetChange(it)
                },
            )
        }

        // 「主页」（2026-11-25 用户要求）：从「外观」里独立成卡，两个开关的标题都去掉「主页」，
        // 另加「自定义背景」/「恢复到默认背景」两项。
        SmallTitle(text = "主页")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 默认关，**只在模块已激活时生效**（未激活时那张卡是"为什么没生效"的唯一提示，不能藏）。
            SwitchRow(
                title = "隐藏激活卡片",
                summary = "仅在模块已激活时生效",
                checked = state.hideHomeStatusCard,
                onCheckedChange = { state.applyHideHomeStatusCard(it) },
            )
            // 默认**开**、无 summary。开着时壁纸换成正在应用那条链的卡片封面（空间 / 光栅 / Alive）。
            SwitchRow(
                title = "背景同步应用状态",
                checked = state.homeBgFollowApp,
                onCheckedChange = { state.applyHomeBgFollowApp(it) },
            )
            // 自定义背景：未选无 summary，选了显示「已选择」。⚠️ `BasicComponent` 不自带箭头。
            BasicComponent(
                title = "自定义背景",
                summary = if (hasCustomBg) "已选择" else null,
                endActions = { LinkArrow() },
                onClick = {
                    haptic()
                    bgPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            // 只在真的选过图时出现的**一组**子项。用 `AnimatedVisibility` 而不是裸 `if`：
            // 裸 `if` 是当帧插入/移除，卡片高度瞬变、看着"突然冒出来"（2026-11-25 用户报）。
            // 默认 enter/exit = `fadeIn() + expandIn()` / `shrinkOut() + fadeOut()`，
            // 与 miuix demo 的 `IconPage` 手风琴同一套。
            //
            // ⚠️ **必须自己包一层 `Column`**（照本文件「强制锁屏元素深浅模式」那段）：
            // `AnimatedVisibility` 的 content 是 `AnimatedVisibilityScope`（不是 `ColumnScope`），
            // 内部 `Layout` 把**每个**子项都 place 到 `y = 0` —— 多个子项直接平铺会全部叠在同一点
            //（2026-11-25 实测：6 个下拉的文字叠成一团黑块）。
            AnimatedVisibility(visible = hasCustomBg) {
                Column {
                    BasicComponent(
                        title = "显示区域调整",
                        summary = null,
                        endActions = { LinkArrow() },
                        onClick = {
                            haptic()
                            cropLauncher.launch(HomeBgCropActivity.intent(context))
                        },
                    )
                    BasicComponent(
                        title = "恢复到默认背景",
                        summary = null,
                        endActions = { LinkArrow() },
                        onClick = {
                            haptic()
                            showResetBgConfirm = true
                        },
                    )
                }
            }
        }

        // 「行为」（2026-11-26 用户要求）：放「主页」之后、「测试」之前 —— 改动频率低，
        // 不该跟主页那组体验选项挤在一起。
        SmallTitle(text = "行为")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            DropdownRow(
                title = "图片选择调用方式",
                options = listOf("系统相册组件", "系统文件管理组件"),
                selectedIndex = state.imagePickMode,
                onSelect = { state.applyImagePickMode(it) },
            )
        }

        // 「更新」整块 2026-11-25 按用户要求搬到「关于」页（开发者卡与设备信息卡之间），
        // 且**不再要小标题**（"更新本来一般就在关于页"）。

        // 测试：这张卡片现在只剩「详细LSPosed日志」一个开关 ——
        // 当年测试卡里的「使用自定义背景」「显示自检与最近操作」2026-11-07 整链删除 ——
        // 与 2026-11-25 新加的「主页」卡里的「自定义背景」不是同一个东西，别混淆。
        //（排查改看 LSPosed 日志与 `run_log.txt`，用户发 log 即可，界面上不再留入口）。
        SmallTitle(text = "测试")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「详细LSPosed日志」（2026-11-27 从「详细过程写入LSPosed日志」改名）2026-09-29 从「替换行为」挪进来。
            SwitchRow(
                title = "详细LSPosed日志",
                summary = "便于排查问题",
                checked = state.verbose,
                onCheckedChange = { state.applyVerbose(it) },
            )
        }
    }

    AppGlassDialog(
        title = "请确认",
        summary = "主页将恢复到默认背景。",
        show = showResetBgConfirm,
        onDismissRequest = { showResetBgConfirm = false },
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        showResetBgConfirm = false
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "确认",
                    // 警示色：恢复默认会丢掉用户自己选的那张图（2026-11-25 用户要求）。
                    danger = true,
                    onClick = {
                        HomeBg.clear(context)
                        state.applyHomeBgCustom(0L)
                        dismissState?.invoke()
                    },
                )
            }
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
            // 同一个理由（2026-11-25）：子项随开关出现/消失要走 `AnimatedVisibility`，
            // 默认就是一整块从上往下展开。
            // ⚠️ **必须自己包一层 `Column`**：`AnimatedVisibility` 的 content 是
            // `AnimatedVisibilityScope`（不是 `ColumnScope`），内部用自定义 `Layout` 把**每个**
            // 子项都 place 到 `y = 0` —— 多个子项直接平铺会全部叠在同一点（2026-11-25 实测：
            // 6 个下拉的文字叠成一团黑块）。
            AnimatedVisibility(visible = state.forceToneMode) {
                Column {
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
}

/** 「强制锁屏元素深浅模式」的下拉三档 —— 索引与 `MaskPrefs.FORCE_TONE_*`（0/1/2）一一对应。 */
private val ForceToneOptions = listOf("保持系统判定", "浅色", "深色")

/**
 * 「快捷方式图标」专用五档（2026-10-11 加「不透明」两档）——
 * 索引对应 `MaskPrefs.FORCE_TONE_*`（0/1/2/3/4，"不透明" = 把系统 80% 的图标 tint 拉满）。
 */
private val ForceToneOptionsShortcut =
    listOf("保持系统判定", "浅色", "深色", "浅色（不透明）", "深色（不透明）")

// ------------------------------------------------------------------ 关于

// 开发者主页链接（酷安）2026-10-31 随「选择平台」弹窗搬去 `PlatformDialog.kt`
//（见那里的 `DeveloperCoolapkUrl`）—— 开发者卡现在先弹窗、由弹窗分发到 Github / B 站 / 酷安。

/** 捐赠入口 —— 主页那张「通过捐赠支持开发者」卡点一下打开这个链接（2026-10-29）。 */
private const val DonateUrl = "https://afdian.com/a/Frederic22"

/** 本项目自己的协议全文（AGPL-3.0）—— 关于页那张「本项目使用AGPL-3.0许可协议」卡跳这里（2026-11-24）。 */
private const val ProjectLicenseUrl =
    "https://github.com/22Frederic/HyperExtreme/blob/main/LICENSE"

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
 * - **依赖与权重**：miuix / libxposed API / AndroidX / Compose Multiplatform /
 *   ONNX Runtime（深度推理的执行引擎）/ Depth Anything V2 **Small**（`assets/models/dav2_small_int8.onnx`）；
 * - **移植进工程的代码**：HyperChanger 的 AOD / 景深 hook（`hook/HookEntry.kt` 等）、
 *   PatchMatch 的 C 移植（`cpp/patchmatch/`，编成 `libspatial_fill.so`）、
 *   Material Icons 的两个图标（`ui/icons/`）。
 *
 * ⚠️ **带版本号的那两条不是随手写的**：
 * - `HyperChanger v1.1.3` 是 hook 里 AOD / 景深那几段参照的实现版本（commit `a01dc46`，2026-09-18），
 *   该版本是 Apache-2.0；上游后来把协议换成了 **AGPL-3.0-or-later**。Apache-2.0 授权不可撤销，
 *   我们那份永久有效，但版本号必须留着 —— 它划清"我们用的是换协议之前的那一版"。
 * - `Depth Anything V2 Small` 同理：只有 Small/Base/Large 权重是 Apache-2.0，**Giant 是 CC-BY-NC-4.0**。
 */
private val OpenSourceLicenses = listOf(
    OpenSourceEntry(
        "miuix", "compose-miuix-ui（YuKongA）", "Apache-2.0",
        "https://github.com/compose-miuix-ui/miuix",
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
    // ⚠️ **版本号必须留**（2026-11-02 查证）：hook 里 AOD / 景深那几段参照的是 **v1.1.3**
    //    （commit `a01dc46`，2026-09-18），该 tag 是 Apache-2.0；上游后来换成 **AGPL-3.0-or-later**。
    //    Apache-2.0 不可撤销 ⇒ 我们的副本永久有效，但标出版本才能划清边界。
    //    引用点：`hook/HookEntry.kt`（AOD / 景深那几处的注释写明了对应实现）。
    //    （悬浮底栏那部分 2026-11-19 已整体退役，换成 miuix 官方的 GlassNavigationBar。）
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
 * 「第三方许可证」二级页（2026-11-24）：原来这些条目直接铺在关于页，把页面撑得很长，
 * 现在拆成独立二级页，关于页只留一行入口。
 *
 * **每条许可一个 Card**（用户指定），与二级页其它页一致用普通 Card ——
 * 这里没有 `BgEffectBackground` 当采样源，磨砂只会糊出底色，没有意义。
 */
@Composable
internal fun LicensesDetail() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        OpenSourceLicenses.forEach { entry ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    // 与一级页一致：**每张卡（含第一张）**都留 12dp，顶栏那段落白由外层提供。
                    .padding(top = 12.dp),
                insideMargin = PaddingValues(0.dp),
            ) {
                LinkRow(
                    title = entry.name,
                    summary = "${entry.author} | ${entry.license}",
                    onClick = { openUrl(context, entry.url) },
                )
            }
        }
    }
}

/**
 * 打开外部链接。没装浏览器之类的异常直接吞掉 —— 不能因为点一下就把应用崩了。
 *
 * `internal`（2026-10-31）：`PlatformDialog` 也要用，同包可见。
 */
internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 玻璃卡片的配色方案。 */
internal enum class GlassCardTone {
    /** 关于页 / miuix demo 那套（2026-11-24 起）：60dp + 蓝 Overlay + 白 HardLight。 */
    Demo,

    /**
     * 底栏那套（2026-11-25 用户定，主页三张卡用）。
     *
     * **不再手抄色值**，直接走底栏同一条 AGSL 管线：`Modifier.glassPanel` +
     * `GlassMaterials.puredThinGlass(dark)` + `GlassEdgeStrokes.forTheme(dark)`，
     * 与 `GlassNavigationBar` 内部完全一致。只有 `shadow = null`（用户 2026-11-25：
     * 阴影不要抄）。
     */
    NavigationBar,
}

/**
 * 玻璃卡片的通用装饰（2026-11-25 从关于页抽出来，**主页三张卡复用同一份**）。
 *
 * 两条路，按 [tone] 选：
 *  * [GlassCardTone.NavigationBar]：底栏那条 AGSL 管线（`glassPanel` + `puredThinGlass` + 描边），
 *    圆角由 `GlassShape(smoothing = 1f)` 画成连续圆角；
 *  * [GlassCardTone.Demo]：关于页那套 `textureBlur`（60dp）。这里 `squircleClip` 与
 *    `textureBlur(RectangleShape)` 必须成对出现 —— `Card` 自己画的是 **squircle**
 *    （`CardDefaults.CornerRadius` = 16dp，走 miuix 的 shader mask），而 `textureBlur` 的 `shape`
 *    只认普通 `Shape`，两边形状不一致会露出「不平滑的圆角」（用户 2026-11-24 报）；
 *    外层 `squircleClip` 裁轮廓、`textureBlur` 用矩形不再二次裁，模糊层与内容被同一轮廓裁一次。
 *
 * @param backdrop 采样源。关于页传它自己那层
 *   `rememberLayerBackdrop { drawRect(surface); drawContent() }`；主页传铺满壁纸的 `Image` 上的
 *   `wallpaper` —— 那张图不透明，所以**不需要**像关于页那样先 `drawRect` 铺底。
 * @param tint 非空 = 在玻璃上再叠一层该色的淡染（状态卡的绿 / 橙 / 红；2026-11-25 用户定
 *   「颜色不用太重，能看出来就行」）。`null` = 不加色。
 * @param tone 配色方案，见 [GlassCardTone]。默认关于页那套，主页三张卡传
 *   [GlassCardTone.NavigationBar]（底栏那套）。
 */
@Composable
internal fun rememberGlassCardModifier(
    backdrop: LayerBackdrop,
    tint: Color? = null,
    tone: GlassCardTone = GlassCardTone.Demo,
): Modifier {
    // 与底栏 / 顶栏同一个判据（`GlassNavigationBarDefaults` 用的是 background）。
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    if (tone == GlassCardTone.NavigationBar) {
        // 底栏同一条路：`GlassNavigationBar` 内部就是
        // `glassPanel(puredThinGlass, stroke = GlassEdgeStrokes.forTheme(...), shading = false)`。
        // 圆角由 `GlassShape` 自己画（`smoothing` 默认 1f = 连续圆角），**不要再套 `squircleClip`**
        // —— 那会把贴边的描边一起削掉；Card 的内容（状态卡那枚溢出右下角的 110dp 图标）
        // 本来就由 Card 自己的 squircle mask 裁。
        val shape = GlassShape(CardDefaults.CornerRadius)
        var glass = Modifier
            .glassPanel(
                backdrop = backdrop,
                shape = shape,
                style = GlassDefaults.Style,
                material = GlassMaterials.puredThinGlass(dark),
                // 底栏的描边（`GlassNavigationBarDefaults.stroke()` 同一个入口）。
                stroke = GlassEdgeStrokes.forTheme(dark),
                // 用户 2026-11-25 明确：**阴影不要抄**。`null` 就是完全不加。
                shadow = null,
                // 与底栏一致：卡面由 material 提供，不做折射 / 明暗。
                shading = false,
                fallback = Modifier
                    .clip(shape)
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            )
        if (tint != null) {
            // 激活卡着色（用户要求保留）：叠在玻璃之上、内容之下。第三层（深灰 Overlay /
            // 白 HardLight）比较重，暗色 24% / 浅色 20% 才「能看出来」。
            glass = glass.drawBehind {
                drawRect(tint.copy(alpha = if (dark) 0.24f else 0.20f))
            }
        }
        return glass
    }
    val blendColors = remember(dark, tint) {
        // demo 的卡片调色（`AboutPage.kt`）：**暗色用的是 `ColorBlendToken.Overlay_Thin_Light`**，
        // 不是名字更像的 `Pured_Regular_Dark`（2026-11-24 用户报「深色下卡片背景没做」——
        // 之前抄错成后者：0x75000000 ColorBurn + 0x52000000 SrcOver 会把整块压暗到与深色背景融在一起）。
        val base = if (dark) {
            listOf(
                BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
                BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
                BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
            )
        }
        // 状态淡染放最上层（最后叠），否则会被上面那层（白 HardLight / 深灰 Overlay）洗掉。
        if (tint == null) {
            base
        } else {
            base + BlendColorEntry(
                tint.copy(alpha = if (dark) 0.24f else 0.20f),
                BlurBlendMode.SrcOver,
            )
        }
    }
    return Modifier
        .squircleClip(CardDefaults.CornerRadius)
        .textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 60f,
            colors = BlurDefaults.blurColors(blendColors = blendColors),
        )
}

@Composable
internal fun AboutTab(
    state: MaskUiState,
    /**
     * 本页是不是当前停留页（2026-11-25）。
     *
     * `false` 时把 [BgEffectBackground] 的颜色动画停掉 —— 它是**无限循环**（每
     * `colorInterpPeriod * 500` ms 一轮很软的 spring），而 pager 的 `beyondViewportPageCount = 1`
     * 让关于页在「停在设置页」时也留在组合树里 ⇒ 整个 App 每帧重绘（实测静止 120 帧 / 16.9ms）。
     */
    visible: Boolean = true,
) {
    val context = state.context
    val haptic = rememberClickHaptic()
    // 「选择平台」弹窗的显隐（2026-10-31）：开发者卡不再直接跳酷安，先弹三选一。
    var showPlatform by remember { mutableStateOf(false) }
    // 背景层（照 miuix demo 的 AboutPage，2026-11-24）：只录背景，logo / 卡片走 foreground blur
    // 采样它。没有这层，textureBlur 只能糊到纯色 surface，图案会完全隐形。
    //
    // ⚠️ **必须像 demo 的 `rememberBlurBackdrop()` 那样先铺一层不透明 surface**：`BgEffectBackground`
    // 的 shader 在色团之外 `color.a = 0`（透明），裸的 `rememberLayerBackdrop()` 会让 backdrop
    // 留下大片透明区 —— `textureBlur` 采到那里时会闪出一块「半透明矩形」（2026-11-24 用户见过一次）。
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    // 卡片磨砂走共享实现（2026-11-25 抽取：主页三张卡复用同一份）。
    // 调色层 / 圆角那两个坑都写在 [rememberGlassCardModifier] 的 KDoc 里。
    val cardBlurModifier = rememberGlassCardModifier(backdrop)
    // demo 的 `logoBlend`：不给调色层的话，logo 只糊出背景本身（浅色渐变）⇒ 和背景同色 = 隐形。
    // 这层把模糊压深/加色，白底图案才有对比（2026-11-24 第一次装上就是没加，完全看不见）。
    val logoBlend = remember(dark) {
        if (dark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }
    // 滚动进度（照 demo）：已滚距离 / 大标题区（顶栏下留白 + logo 高度 + 底部留白）。
    val density = LocalDensity.current
    val pageScroll = LocalPageScroll.current ?: rememberScrollState()
    var logoHeightPx by remember { mutableIntStateOf(0) }
    val topGap = 122.dp
    val bottomGap = 144.dp
    val logoHeight = with(density) { logoHeightPx.toDp() }
    val fadeDistance = topGap + logoHeight + bottomGap
    val progress: () -> Float = {
        val px = with(density) { fadeDistance.toPx() }
        if (px <= 0f) 0f else (pageScroll.value / px).coerceIn(0f, 1f)
    }
    BgEffectBackground(
        // 只在本页真的停在屏幕上时才跑颜色动画（2026-11-25）。离开时 `colorStage` 保留当前值，
        // 回来接着往下走，不会跳色。
        dynamicBackground = visible,
        modifier = Modifier.fillMaxSize(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        isFullSize = true,
        // demo：背景的动态流动随滚动整体渐隐。
        alpha = { 1f - progress() },
    ) {
        // 固定的大标题区（demo 的 Column 在 LazyColumn 之外）：**位置不动**，只按进度渐隐。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LocalTopBarHeight.current + topGap)
                .padding(horizontal = 24.dp)
                .onSizeChanged { logoHeightPx = it.height },
        ) {
            Image(
                painter = painterResource(R.drawable.hyperextreme_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .aspectRatio(1198f / 172f)
                    .graphicsLayer {
                        // demo：大标题 0.20 → 0.35 淡出 + 缩到 95%
                        val k = ((progress() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1f - k
                        scaleX = 1f - k * 0.05f
                        scaleY = 1f - k * 0.05f
                    }
                    .textureBlur(
                        backdrop = backdrop,
                        // 不能给圆角：贴边满宽的 logo 会被啃掉四角（H 左上角那件事）
                        shape = RoundedCornerShape(0.dp),
                        blurRadius = 60f,
                        colors = BlurDefaults.blurColors(blendColors = logoBlend),
                        contentBlendMode = ComposeBlendMode.DstIn,
                    ),
            )
            Text(
                text = appVersionLabel(context),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .graphicsLayer {
                        // demo：版本号 0.05 → 0.20 淡出（比大标题更早）+ 同样缩到 95%
                        val k = ((progress() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1f - k
                        scaleX = 1f - k * 0.05f
                        scaleY = 1f - k * 0.05f
                    },
            )
        }
        TabColumn {
            // 占位 = 大标题区总高度（顶栏下 122 + logo/版本号 + 到卡片 144），
            // 滚动 0 时卡片正好从大标题下方开始（demo 的 `logoSpacer` 同款）。
            Spacer(Modifier.height(topGap + logoHeight + bottomGap))
            // 开发者卡（透明 + 磨砂，照 demo 的 Card 处理）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlurModifier),
                colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent),
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

            // 更新卡（2026-11-25 从设置页搬来，用户定「更新本来一般就在关于页」；**没有小标题**）。
            // 套关于页的磨砂（`cardBlurModifier`），与上面开发者卡、下面设备信息卡一致。
            // 数据源只有 Gitee 一个 GET（GitHub 只在 Gitee 失败时兜底），夸克网盘**只作跳转目标**。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlurModifier),
                colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent),
                insideMargin = PaddingValues(0.dp),
            ) {
                SwitchRow(
                    title = "自动检测更新",
                    summary = "在每天首次进入模块时自动检测",
                    checked = state.autoUpdateCheck,
                    onCheckedChange = { state.applyAutoUpdateCheck(it) },
                )
                BasicComponent(
                    title = "立即检测更新",
                    // **无 summary**（用户 2026-11-16 指定）。点击反馈是 short toast
                    //「正在检测，请稍后」（见 `MaskUiState.checkUpdate` 入口）。
                    summary = null,
                    // ⚠️ `BasicComponent` **不自带箭头** —— 必须显式给 `endActions`。
                    endActions = { LinkArrow() },
                    onClick = {
                        haptic()
                        state.checkUpdate(manual = true)
                    },
                )
                BasicComponent(
                    title = "打开模块发布页",
                    summary = null,
                    endActions = { LinkArrow() },
                    onClick = {
                        haptic()
                        state.showReleaseDialog = true
                    },
                )
            }

            // 设备信息卡（2026-11-24 从主页搬来，用户要求：设备 / 软件信息归关于页）。
            //
            // 2026-10-29：① 砍掉「模块版本」「目标应用」两项；
            // ② 排版改用官方 `BasicComponent` —— 标题 `headline1`(17sp) 就是设置项正文的字号，
            //    summary `body2`(14sp) 就是设置项副标题的字号，行高（≥56dp）与 16dp 内边距
            //    全是官方那套，和设置页的选项逐像素一致（原来的自绘 `InfoRow` 已删除）。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlurModifier),
                colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent),
                insideMargin = PaddingValues(0.dp),
            ) {
                BasicComponent(title = "设备名称", summary = deviceName(context))
                BasicComponent(title = "设备型号", summary = Build.MODEL)
                BasicComponent(title = "HyperOS 版本", summary = osVersionName())
                BasicComponent(title = "Android 版本", summary = androidVersionName())
            }

            // 本项目协议卡（2026-11-24 用户要求）：跳仓库里的 LICENSE 全文。
            // 与下面「第三方许可证」分开两行 —— 上面是**我们自己**的协议，下面是**用到的**别人的。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlurModifier),
                colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent),
                insideMargin = PaddingValues(0.dp),
            ) {
                LinkRow(title = "本项目使用AGPL-3.0许可协议", summary = null) {
                    openUrl(context, ProjectLicenseUrl)
                }
            }

            // 第三方许可证（2026-11-24）：原来十几条许可直接铺在关于页，把页面撑得很长。
            // 现在这里只留一行入口，列表搬进二级页 [LicensesDetail]（每条一个 Card）。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
                    .then(cardBlurModifier),
                colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent),
                insideMargin = PaddingValues(0.dp),
            ) {
                LinkRow(title = "第三方许可证", summary = null) {
                    MaskDetailActivity.start(context, DetailPage.Licenses)
                }
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
 * 一个**下拉选择**选项：整行可点，右侧显示「当前值 + 上下箭头」，点开弹出**玻璃**下拉面板。
 *
 * 2026-09-29 加；**2026-11-18 换成 miuix 0.9.5 的 [GlassDropdownPreference]**（`miuix-glass`）。
 *
 * ## 为什么不再手拼
 *
 * 旧实现是拿 `miuix-ui` 的积木（`BasicComponent` + `OverlayListPopup` + `ListPopupColumn` +
 * `DropdownImpl`）照官方源码接线 —— 因为当时成品 `OverlayDropdownPreference` 在
 * `miuix-preference` 模块里，本工程没引。0.9.5 把玻璃下拉做进了 `miuix-glass`：
 * 锚定在行上的玻璃面板 + 展开动画（圆角 4dp → 24dp）+ 选中打勾，一个调用全有，
 * 13 个调用点的参数一个字不用改。
 *
 * ## 仍需 Scaffold（官方硬约束，别删二级页那个）
 *
 * `GlassDropdownPreference` 内部走 `renderInRootScaffold = true`，面板由 `Scaffold` 的
 * `MiuixPopupHost` 渲染 —— 与旧实现同一个前提。**`DetailTabHost` 里那个自建 `Scaffold`
 * 不能省**，否则下拉「点了没反应」（2026-10-11 踩过一次）。
 *
 * ## 玻璃背景
 *
 * `backdrop` 取 [LocalPageBackdrop]：一级页 = 整页录制层，二级页 = 顶栏那份。
 * 拿不到（裸 Activity / 首帧）时为 null，组件自动退回不透明底 —— 官方设计如此，不会崩。
 * ⚠️ 采样范围只含页面内容与底色，不含弹层自身（弹层在 `popupHost`，天然不在录制层里）。
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
    GlassDropdownPreference(
        title = title,
        items = options,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = onSelect,
        summary = summary,
        enabled = enabled,
        backdrop = LocalPageBackdrop.current,
    )
}
