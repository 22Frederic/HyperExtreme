package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.frederic22.hyperextreme.applyAppSystemBars
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.hook.SpatialGlCore
import com.frederic22.hyperextreme.requestHighRefreshRate
import com.frederic22.hyperextreme.resolveAppDark
import com.frederic22.hyperextreme.spatial.SpatialCrop
import com.frederic22.hyperextreme.spatial.SpatialDepth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 「调整显示区域」—— **独立 Activity**（2026-11-04），吃系统转场动画。
 *
 * ## 为什么是独立 Activity
 *
 * 用户明确要求；顺带解决了两件事：
 * * 全屏沉浸不会污染二级页的 `DetailTabHost` 状态；
 * * 系统默认的进入/退出转场，比 Compose 路由更像系统相册。
 *
 * ## 布局（2026-11-27 改版）
 *
 * 从「图片铺满全屏 + 玻璃顶栏 + 底部两个按钮」改成**小米锁屏编辑**那套：
 * 恒定 `#1a1a1a` 背景、中间一张屏幕比例的预览卡、上方一行「显示区域」、顶栏只有两个
 * 常显玻璃圆钮、底部一个「恢复默认」。骨架全部在 [PreviewCardScaffold] 里 ——
 * 本文件只负责预览本身（图 + shader + 手势 + 保存），后续任何「导入图片后看大图」的页面
 * 都复用同一个骨架。
 *
 * ## 预览公式 = 渲染公式
 *
 * AGSL 里那段和 `SpatialGlCore.FRAG` **同一套**球面映射 + `SpatialCrop` 的仿射，
 * 只是去掉了位移场（预览是静止姿态，`uShift = 0`）。所以「预览里看到的就是壁纸上显示的」。
 * 卡片与屏幕同为屏幕比例 ⇒ `SpatialGlCore.geometry()` 的结果与全屏时**完全一致**，
 * 手势 clamp 也天然对齐，改版不需要动任何几何。
 *
 * ## 交互
 *
 * * 单指拖动 / 双指缩放（1×~2×，下限保证画面永远铺满）；
 * * 顶栏圆钮常显（不再单击切换）；状态栏由骨架常隐。
 */
internal class SpatialCropActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_KEY = "key"

        fun intent(ctx: Context, key: String): Intent =
            Intent(ctx, SpatialCropActivity::class.java).putExtra(EXTRA_KEY, key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        setContent {
            val prefs = remember { MaskPrefs(this) }
            val themeMode = remember { ThemeMode.of(prefs.themeMode) }
            val controller = rememberThemeController(themeMode, remember { prefs.monetColor })
            MiuixTheme(controller = controller, textStyles = HyperTextStyles) {
                CropScreen(key = key, onClose = { finish() })
            }
        }
    }

    /**
     * 无论「确认」还是返回键退出，都回报 OK —— 空间页那边只拿它当「回来重扫一次」的信号，
     * 真正的落盘只在「确认」里发生，所以这里不会造成误保存。
     */
    override fun finish() {
        runCatching { setResult(RESULT_OK) }
        super.finish()
    }

    override fun onDestroy() {
        // ⚠️ 骨架在 onDispose 里已经 show 回来了，这里双保险：任何路径退出都别把状态栏
        // 留在隐藏态（二级页顶栏的高度依赖它）。
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        super.onDestroy()
    }
}

/**
 * 预览用的 AGSL：与 `SpatialGlCore.FRAG` 同一套球面 + 显示区域仿射（无位移场）。
 *
 * **4 taps 超采样**（2026-11-04）：预览解码的是原图（2802 宽），屏幕只显示其中
 * 69%（约 1938 px）压到 1200 物理像素上 —— **降采样 1.62×**。单层双线性只有 2×2 个
 * 采样点，1.6 倍降采样必然出摩尔纹和细线断裂（观感就是「插值没插好的锯齿」）。
 * 在屏幕空间取 4 个点（间距 1px，各自走双线性）再平均 ⇒ 等效 4×4 盒式滤波，
 * 覆盖范围正好盖住降采样比。
 */
private const val CROP_AGSL = """
    uniform shader uTex;
    uniform float2 uSize;      // 屏幕像素
    uniform float2 uTexSize;   // 纹理像素
    uniform float2 uK;         // tan(半角) 与 aspect（同 SpatialGlCore.geometry）
    uniform float2 uSpan;      // 图像覆盖的球面半角
    uniform float2 uUvScale;   // 1/zoom
    uniform float2 uUvOffset;  // c − 0.5/zoom

    // 屏幕像素 → 纹理 uv（球面映射 + 显示区域仿射）。
    float2 uvAt(float2 fragCoord) {
        // 屏幕像素 → NDC（y 向上）
        float2 ndc = float2(fragCoord.x / uSize.x * 2.0 - 1.0, 1.0 - fragCoord.y / uSize.y * 2.0);
        float2 q0 = float2(atan(ndc.x * uK.x) / uSpan.x, atan(ndc.y * uK.y) / uSpan.y);
        // y 反转：GL 的 v=0 在底部，屏幕 y 在顶部（与渲染 shader 一致）
        float2 uv = float2(q0.x * 0.5 + 0.5, 0.5 - q0.y * 0.5);
        return uv * uUvScale + uUvOffset;
    }

    half4 main(float2 fragCoord) {
        // 4 taps：对角 ±0.5 屏幕像素，平均位置仍是当前像素中心。
        half4 c  = uTex.eval(uvAt(fragCoord + float2(-0.5, -0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2( 0.5, -0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2(-0.5,  0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2( 0.5,  0.5)) * uTexSize);
        return c * 0.25;
    }
"""

/** 「恢复默认」按钮宽度（2026-11-27 用户要求保持原值，不跟新卡宽走）。 */
private val RESET_BUTTON_WIDTH = 150.dp

@Composable
private fun CropScreen(key: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = rememberClickHaptic()
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(SpatialCrop.DEFAULT) }
    // 「进页时的那一份」—— 只用来判断「动过没有」（小米 `BaseTemplateView.onCancel` 的 `isEdited()`）。
    // ⚠️ 初值必须是 **null**、不能是 `DEFAULT`：下面 `LaunchedEffect(geo)` 会拿老参数做一次
    // `clampWith` 修正（旧版本按 0.5/zoom 存过更宽的 cx）。如果这里先记 DEFAULT，
    // 修正完就会「一进页就 edited」⇒ 返回立刻误弹窗。null = 还没定，修正完才写入。
    var cropAtEntry by remember { mutableStateOf<SpatialCrop.Crop?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    val edited = cropAtEntry != null && crop != cropAtEntry
    // 画布实际尺寸（物理像素）—— 手势的 clamp 和 shader 必须用**同一个**几何。
    // 现在画布 = 卡片（0.7243 屏），与屏幕同为屏幕比例 ⇒ geometry 结果与全屏时一致。
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 载入：原图副本（没有就退回背景层图）+ 已存参数。
    LaunchedEffect(key) {
        val loaded = withContext(Dispatchers.IO) {
            val dir = File(SpatialDepth.cacheRoot(ctx), key)
            val src = SpatialDepth.sourceFile(dir).takeIf { it.isFile }
                ?: File(dir, "layers/layer0.png").takeIf { it.isFile }
            // 2000：卡片宽约 870px，放到 2× 时需要 1740 的有效像素；2000 是内存与清晰度的折中（约 21MB）。
            val bmp = src?.let { SpatialDepth.decodePreview(it, 2000) }
            bmp to SpatialCrop.read(ctx, key)
        }
        bitmap = loaded.first
        crop = loaded.second
        android.util.Log.i(
            "HyperExtreme",
            "CROP 加载 key=$key bitmap=${loaded.first?.width}x${loaded.first?.height} crop=$loaded.second",
        )
    }

    // 几何：**卡片实测尺寸** + 纹理尺寸 ⇒ 可见 uv 范围（手势 clamp 与 shader 共用）。
    // 用实测尺寸而不是屏幕：卡片尺寸由 `PreviewCardScaffold` 算，差一点就会让 clamp
    // 和实际采样对不上（表现为拖到边上露一丝黑）。
    val geo = remember(canvasSize, bitmap) {
        val b = bitmap
        if (b == null || canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            SpatialGlCore.geometry(canvasSize.width, canvasSize.height, b.width, b.height)
        }
    }

    // 老参数可能越界（旧版本按 0.5/zoom 存过更宽的 cx），几何一就绪就**修正一次**。
    var cropCorrected by remember { mutableStateOf(false) }
    LaunchedEffect(geo) {
        val g = geo ?: return@LaunchedEffect
        if (cropCorrected) return@LaunchedEffect
        val corrected = SpatialCrop.clampWith(
            crop, g.visibleHalfX, g.visibleHalfY, g.shiftMarginX, g.shiftMarginY,
        )
        crop = corrected
        // 入口基准取「修正后」的值 —— 这一下不算用户改动。
        if (cropAtEntry == null) cropAtEntry = corrected
        cropCorrected = true
    }

    val shader = remember { RuntimeShader(CROP_AGSL) }
    // 绘制用的 Paint：用原生 `android.graphics.Paint`（比 Compose 的包装类少一层、
    // `shader` 直接收 `android.graphics.Shader`，没有 value class 的转换歧义）。
    //
    // ⚠️ `isFilterBitmap` 在这里**不是**决定锯齿的那个开关：AGSL 的 `uTex.eval()`
    // 会被编译成 `sample(uTex, coord)`，采样参数由**子 shader 自己**决定，
    // 而 Paint 的过滤只作用于「用这个 paint 画图元、图元需要缩放」的路径 ——
    // 我们画的是铺满画布的矩形（1:1），所以它整条链上都不参与。
    // 真正的开关在下面 `BitmapShader.setFilterMode(...)`。
    val drawPaint = remember {
        android.graphics.Paint().apply {
            isFilterBitmap = true   // 留着无害；不指望它
            isAntiAlias = true
        }
    }
    // ⚠️ **必须在这里**（组合阶段）设置 input shader，不能用 LaunchedEffect：
    // LaunchedEffect 在组合提交之后才启动，而 Canvas 的 draw 在同一帧已经画过一遍 ——
    // 那时 uTex 还是空的 ⇒ 全黑；之后又没有东西触发重绘，于是「进页面黑屏、缩放一下才出图」
    //（2026-11-04 用户报的）。`remember(bitmap)` 同步执行，天然早于 draw。
    val texReady = remember(shader, bitmap) {
        val b = bitmap
        if (b == null) {
            false
        } else {
            shader.setInputShader(
                "uTex",
                BitmapShader(
                    b,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP,
                ).apply {
                    // ⚠️ **锯齿的真正开关**（2026-11-04 定案）：
                    // 三参构造传的是 `FILTER_MODE_DEFAULT`（= 0，「未指定」），
                    // 在 Skia 里落到 `kNearest`。而 AGSL 的 `eval` → `sample()`
                    // 只看子 shader 自己的采样选项，**绕开 Paint 的 filterBitmap**，
                    // 所以之前把双线性开在 Paint 上完全没用。
                    // 显式给 LINEAR 才是双线性（API 33 公开，minSdk 正好是 33）。
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                },
            )
            true
        }
    }

    // 玻璃弹窗的采样层：整页（含顶栏）。圆钮走 `fill`、不用 backdrop ⇒ 录进去也不会
    // 「自己采样自己」（对比 2026-11-27 那次 `prepareTreeImpl` 递归的前提）。
    val pageBackdrop: LayerBackdrop? = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop { drawContent() }
    } else {
        null
    }

    // 返回：动过就弹「放弃更改？」，否则直接退出（与 `HomeBgCropActivity` 同一条逻辑）。
    val handleBack: () -> Unit = {
        if (edited) showDiscard = true else onClose()
    }
    // 系统返回键也走这条（左上角返回钮在骨架里，走 `onBack`）。
    BackHandler { handleBack() }

    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
    // ⚠️ **必须有 `Scaffold`**：miuix 的 `GlassDialog` 只把 `DialogState` 注册进
    // `LocalDialogStates`，真正渲染它的是 `Scaffold` 里的 `MiuixPopupHost`。
    // 裸 Activity 不套 Scaffold ⇒ 弹窗静默不出现（不报错、不崩），见《07》§7.15 第 24 条。
    // 透明容器 + `WindowInsets(0)`：不引底色、不改布局（状态栏是骨架自己隐的）。
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { insetsPadding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(insetsPadding)
            .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
    ) {
    PreviewCardScaffold(
        title = "显示区域",
        onBack = handleBack,
        onConfirm = {
            // 振动在 `GlassIconButtonWithFeedback` 里已经做过一次，这里不再重复。
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    SpatialCrop.write(ctx, key, crop)
                }
                android.widget.Toast.makeText(
                    ctx,
                    // 正在应用的那张本来就点不进来（页面层拦掉了），
                    // 能改的都是"下一次应用才生效"的卡 —— 不需要再解释生效时机
                    //（2026-11-05 用户要求）。
                    if (ok) "显示区域已保存" else "保存失败",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onClose()
            }
        },
        actions = {
            // 「恢复默认」：只把**内存里的编辑状态**重置为默认，不写盘、不弹 toast ——
            // 用户还要点右上角 ✓ 才真正保存（2026-11-05 用户明确）。
            //
            // 2026-11-27：不再用玻璃材质 —— 页面背景已经是实色 `#1a1a1a`，透上去什么也看不见，
            // 直接走 miuix 标准主题色按钮（`buttonColorsPrimary`：`primary` 底 + `onPrimary` 字）。
            Button(
                onClick = {
                    haptic()
                    crop = SpatialCrop.DEFAULT
                },
                modifier = Modifier.width(RESET_BUTTON_WIDTH),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = "恢复默认", style = MiuixTheme.textStyles.button)
            }
        },
    ) {
        // ── 卡片内容：预览 + 手势 ──
        // `PreviewCardScaffold` 已经把这块裁到圆角，这里直接铺满。
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
                Canvas(Modifier.fillMaxSize()) {
                    if (!texReady) return@Canvas
                    val bmp = bitmap ?: return@Canvas
                    val g = geo ?: return@Canvas
                    shader.setFloatUniform("uSize", size.width, size.height)
                    shader.setFloatUniform("uTexSize", bmp.width.toFloat(), bmp.height.toFloat())
                    shader.setFloatUniform("uK", g.kx, g.ky)
                    shader.setFloatUniform("uSpan", g.spanX, g.spanY)
                    val sc = SpatialCrop.scaleOf(crop)
                    shader.setFloatUniform("uUvScale", sc, sc)
                    shader.setFloatUniform(
                        "uUvOffset",
                        SpatialCrop.offsetXOf(crop),
                        SpatialCrop.offsetYOf(crop),
                    )
                    // ⚠️ **不能用 `drawRect(brush = brush)`**（2026-11-04 的真根因）：
                    // Compose 的 `Paint()` 默认 `filterQuality = None`，而
                    // `AndroidPaint.setFilterQuality` 的实现就是 `paint.setFilterBitmap(quality != None)`
                    // ⇒ 默认 **setFilterBitmap(false) = 最近邻**；`BitmapShader` 的三参构造传的是
                    // `FILTER_MODE_DEFAULT`（跟随 Paint）⇒ 整条链全程最近邻。
                    // 所以之前 4 taps 只是「4 个硬点取平均」，等于没插值（锯齿自然还在）。
                    // 双线性在 `drawPaint` 构造时就打开了（`isFilterBitmap = true`）。
                    drawPaint.shader = shader
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, drawPaint)
                    }
                }
            }

            // 手势层（铺满卡片）：拖动 / 缩放。顶栏常显，所以**没有**单击切换。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // key 用 geo：它一变（图 / 画布尺寸就绪）就要重建手势，
                    // 否则闭包捕获的是旧的 null，拖动会直接 return。
                    .pointerInput(geo) {
                        val g = geo ?: return@pointerInput
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTransformGestures
                            var c = crop
                            // 拖动：1px = (1/zoom)/W 的 uv；向右拖 = 显示区域左移。
                            c = c.copy(
                                cx = c.cx - pan.x * (1f / c.zoom) / w,
                                cy = c.cy - pan.y * (1f / c.zoom) / h,
                            )
                            if (zoomChange != 1f) {
                                // 以手势中心为锚点缩放：手指下的 uv 点保持不动。
                                val px = centroid.x / w
                                val py = centroid.y / h
                                val uvX = c.cx + (px - 0.5f) / c.zoom
                                val uvY = c.cy + (py - 0.5f) / c.zoom
                                val nz = (c.zoom * zoomChange)
                                    .coerceIn(SpatialCrop.ZOOM_MIN, SpatialCrop.ZOOM_MAX)
                                c = c.copy(
                                    cx = uvX - (px - 0.5f) / nz,
                                    cy = uvY - (py - 0.5f) / nz,
                                    zoom = nz,
                                )
                            }
                            // 边界 = 「屏幕可见 uv 半宽 ÷ zoom + 位移余量」，
                            // 不是旧的 0.5/zoom（那个在 zoom = 1 时把 cx 锁死在 0.5）。
                            crop = SpatialCrop.clampWith(
                                c, g.visibleHalfX, g.visibleHalfY, g.shiftMarginX, g.shiftMarginY,
                            )
                        }
                    },
            )
        }
    }
    }

    // ── 「放弃更改？」── 常驻组合树、用 `show` 控制（条件包裹会让 miuix 来不及播退出动画）。
    // 结构与配色与 `HomeBgCropActivity` / 「清除所有光栅壁纸组合」那一套完全一致：
    // `danger = true` 是官方 `Destructive`（淡底 + 红字），不是旧版的红底白字。
    AppGlassDialog(
        title = "放弃更改？",
        summary = null,
        show = showDiscard,
        onDismissRequest = { showDiscard = false },
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
                        showDiscard = false
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "放弃",
                    danger = true,
                    onClick = {
                        showDiscard = false
                        // 不写盘 —— 直接退出，磁盘上还是进来时那份 crop。
                        onClose()
                    },
                )
            }
        }
    }
    }
    }
}
