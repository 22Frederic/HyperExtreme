package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.frederic22.hyperextreme.applyAppSystemBars
import com.frederic22.hyperextreme.data.HomeBg
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.requestHighRefreshRate
import com.frederic22.hyperextreme.resolveAppDark
import com.frederic22.hyperextreme.spatial.SpatialCrop
import com.frederic22.hyperextreme.spatial.SpatialDepth
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.LocalDismissState
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「自定义背景 · 显示区域调整」—— 独立 Activity（2026-11-27）。
 *
 * ## 与 `SpatialCropActivity` 的关系
 *
 * 共用同一个骨架 [PreviewCardScaffold]（背景 / 顶栏 / 卡片尺寸与圆角 / 标题 / 底部按钮），
 * 但**预览公式完全不同**：
 *
 * | | 空间壁纸 | 主页背景 |
 * |---|---|---|
 * | 几何 | 球面映射 + `SPHERE_SAFE` 放大 | 平面 `ContentScale.Crop` |
 * | 边界 | `SpatialCrop.clampWith`（要几何信息） | `SpatialCrop.clampFlat`（`0.5/zoom`） |
 * | 渲染 | AGSL 仿射 | `graphicsLayer` 的 scale + translation |
 *
 * 所以不复用那个 Activity 的 shader 链 —— 两条口径混在一起，出问题很难定位。
 *
 * ## 参数
 *
 * 存 `SpatialCrop` 同一套 JSON，key = [HomeBg.CROP_KEY]（`"home_bg"`）。
 * 选新图 / 恢复默认时 [HomeBg] 会把这份参数一起清掉。
 */
internal class HomeBgCropActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, HomeBgCropActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        setContent {
            val prefs = remember { MaskPrefs(this) }
            val themeMode = remember { ThemeMode.of(prefs.themeMode) }
            val controller = rememberThemeController(themeMode, remember { prefs.monetColor })
            MiuixTheme(controller = controller, textStyles = HyperTextStyles) {
                HomeBgCropScreen(onClose = { finish() })
            }
        }
    }

    /** 无论 ✓ 还是返回键退出都回报 OK —— 落盘只在 ✓ 里发生，这里不会造成误保存。 */
    override fun finish() {
        runCatching { setResult(RESULT_OK) }
        super.finish()
    }

    override fun onDestroy() {
        // 骨架在 onDispose 里已经 show 回来了，这里双保险。
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        super.onDestroy()
    }
}

/** 「恢复默认」按钮宽度 —— 与 `SpatialCropActivity` 同一个值，两页视觉一致。 */
private val RESET_BUTTON_WIDTH = 150.dp

@Composable
private fun HomeBgCropScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = rememberClickHaptic()
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(SpatialCrop.DEFAULT) }
    // 进入时的那一份 —— 只用来判断「动过没有」（小米 `BaseTemplateView.onCancel` 的 `isEdited()`）。
    var cropAtEntry by remember { mutableStateOf(SpatialCrop.DEFAULT) }
    var showDiscard by remember { mutableStateOf(false) }
    val edited = crop != cropAtEntry

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val f = HomeBg.customFile(ctx)
            val bmp = if (f.isFile) SpatialDepth.decodePreview(f, 2000) else null
            bmp to SpatialCrop.read(ctx, HomeBg.CROP_KEY)
        }
        bitmap = loaded.first
        crop = loaded.second
        cropAtEntry = loaded.second
        android.util.Log.i(
            "HyperExtreme",
            "HOMEBG CROP 加载 bitmap=${loaded.first?.width}x${loaded.first?.height} crop=$loaded.second",
        )
    }

    // 玻璃弹窗的采样层：整页（**含顶栏**）。圆钮现在走 `fill` 不用 backdrop ⇒
    // 把它录进去也不会「自己采样自己」（2026-11-27 那次 `prepareTreeImpl` 递归的前提
    // 是圆钮**使用**了这个 backdrop，现在不用了）。
    val pageBackdrop: LayerBackdrop? = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop { drawContent() }
    } else {
        null
    }

    // 返回：动过就弹「放弃更改？」，否则直接退出（小米 `BaseTemplateView.onCancel` 的同一条逻辑）。
    val handleBack: () -> Unit = {
        if (edited) showDiscard = true else onClose()
    }
    // 系统返回键也走这条（左上角返回钮在骨架里，走 `onBack`）。
    BackHandler { handleBack() }

    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
    // ⚠️ **必须有 `Scaffold`**（2026-11-27 修「弹窗不显示」）：
    // miuix 的 `OverlayDialog` / `GlassDialog` 只是把 `DialogState` 注册进
    // `LocalDialogStates`，真正**渲染**它的是 `Scaffold` 里那个 `MiuixPopupHost`。
    // 裸 Activity 不套 Scaffold ⇒ 状态落进 `staticCompositionLocalOf` 的默认空列表、
    // 没有宿主遍历它 ⇒ 弹窗静默不出现（不报错、不崩）。
    // 透明容器 + `WindowInsets(0)`：不引入底色、也不改我们的布局（状态栏是自己隐的）。
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
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    SpatialCrop.write(ctx, HomeBg.CROP_KEY, crop)
                }
                android.widget.Toast.makeText(
                    ctx,
                    if (ok) "显示区域已保存" else "保存失败",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onClose()
            }
        },
        actions = {
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
        // 卡片内容：平面预览 + 手势。
        // 渲染公式与主页 `MaskTabs` 里那个 `Image` **逐字一致**：
        //   scale = zoom，translation = -(c - 0.5) × 卡片尺寸 × zoom
        // 所以「预览里看到的 = 主页上显示的」。
        val bmp = bitmap
        Box(modifier = Modifier.fillMaxSize()) {
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                // ⚠️ **必须用 Canvas + drawImage，不能用 `Image(ContentScale.None)`**
                //（2026-11-27 用户实测）：`Image` 的可绘制区域就是 modifier 尺寸，
                // 先按原尺寸画、居中、**把超出卡片的部分裁掉**，再交给 graphicsLayer 缩放 ⇒
                // 大图会变成「屏幕比例的一小块再缩小」，四周露卡片底色（图一）。
                // `drawImage` 的 dst 矩形不受布局尺寸限制，画的是完整原图。
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pw = img.width.toFloat()
                    val ph = img.height.toFloat()
                    // zoom 下限 = 1 ⇒ s ≥ base ⇒ **图永远铺满预览框**（用户 2026-11-27 的硬约束）
                    val s = max(size.width / pw, size.height / ph) * crop.zoom
                    val dstW = pw * s
                    val dstH = ph * s
                    drawImage(
                        image = img,
                        // uv 点 c 落在画布中心
                        dstOffset = IntOffset(
                            (size.width / 2f - crop.cx * dstW).roundToInt(),
                            (size.height / 2f - crop.cy * dstH).roundToInt(),
                        ),
                        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bmp) {
                        if (bmp == null) return@pointerInput
                        val pw = bmp.width.toFloat()
                        val ph = bmp.height.toFloat()
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTransformGestures
                            val base = max(w / pw, h / ph)
                            var c = crop
                            // 拖动：图上 1px 位移 = 1/(图宽 × s) 的 uv。
                            val s0 = base * c.zoom
                            c = c.copy(
                                cx = c.cx - pan.x / (pw * s0),
                                cy = c.cy - pan.y / (ph * s0),
                            )
                            if (zoomChange != 1f) {
                                // 以手势中心为锚点：手指下的 uv 点保持不动。
                                // 屏幕点 → uv：u = (px - 0.5) × w / (pw × s) + cx
                                val uvX = (centroid.x / w - 0.5f) * w / (pw * s0) + c.cx
                                val uvY = (centroid.y / h - 0.5f) * h / (ph * s0) + c.cy
                                val nz = (c.zoom * zoomChange)
                                    .coerceIn(SpatialCrop.ZOOM_MIN, SpatialCrop.ZOOM_MAX)
                                val s1 = base * nz
                                c = c.copy(
                                    cx = uvX - (centroid.x / w - 0.5f) * w / (pw * s1),
                                    cy = uvY - (centroid.y / h - 0.5f) * h / (ph * s1),
                                    zoom = nz,
                                )
                            }
                            val s = base * c.zoom
                            crop = SpatialCrop.clampHalf(
                                c,
                                halfX = w / (2f * pw * s),
                                halfY = h / (2f * ph * s),
                            )
                        }
                    },
            )
        }
    }
    }

    // ── 「放弃更改？」── 常驻组合树、用 `show` 控制（条件包裹会让 miuix 来不及播退出动画）。
    // 结构与配色照「清除所有光栅壁纸组合」那个确认框；`danger = true` 是官方 `Destructive`
    //（淡底 + 红字），不是旧版的红底白字。
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
