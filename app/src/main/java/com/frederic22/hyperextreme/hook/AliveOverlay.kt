package com.frederic22.hyperextreme.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.frederic22.hyperextreme.data.AliveFlag

/**
 * Alive 壁纸的**锁屏景深主体层**（2026-11-08）。
 *
 * ## 层结构照抄 `RasterOverlay`（光栅景深已验证可用）
 *
 * ```
 * SpatialForegroundHider.handle()
 *   ├ AliveOverlay.detachSystemSubject(layer)   ← 把系统那个 deducted_image_view 摘掉
 *   ├ AliveOverlay.ensure(layer)                ← 自己往 keyguard_foreground_layer 里插 View
 *   └ AliveOverlay.onDisabled()                 ← 移掉我们的 + 把系统主体插回去
 * ```
 *
 * ⚠️ **不能复用系统的 `deducted_image_view` 换图**（第一版就是这么写的，一直"没生效"）：
 * 那个 ImageView 的图、`alpha`、`visibility` 全由 `KeyguardPanelViewController` 重算，
 * 我们换上去的图会被它下一次 `setImageDrawable` 覆盖，可见性也会被 folme 动画翻回去。
 * **必须自己建一个 View** 才不受干扰 —— 光栅 2026-11-04 踩过并写在注释里。
 *
 * ## 为什么是 AGSL 而不是 `View.scaleX/scaleY`
 *
 * 第二版用 `scaleX/scaleY = lens.scale / expandedScale`，结果真机上「主体只会缩小消失，
 * 不参与变形」—— 因为小球里那层背景是被 lens shader **桶形畸变 + 圆形裁切 + zoom**
 * 处理过的，均匀缩放一张矩形图跟它对不上。
 *
 * 所以这里的 `SubjectView` 跑**与 `AliveGlCore.LENS_FRAG` 同一套几何**：
 *
 * ```glsl
 * uv      = (vUv.x, vUv.y * aspect)
 * center  = (0.5 + tx, (0.5 - ty) * aspect)      // View 的 y 向下，GL 的 uv.y 向上 ⇒ 减
 * dist    = distance(uv, center)
 * distorted = zoomPoint(uv + lensDistortion(uv - center, α), center, zoom)
 * 圆外 → 透明（主体层要透出背景球，不是画黑）
 * ```
 */
internal object AliveOverlay {

    private const val AUTHORITY = "com.frederic22.hyperextreme.provider"
    private const val ID_DEDUCTED = "deducted_image_view"
    private const val SYSTEMUI_PKG = "com.android.systemui"

    /** 解锁淡出时长（用户 2026-11-08：0.1s）。 */
    private const val UNLOCK_FADE_MS = 100L

    /**
     * 与 `AliveGlCore.LENS_FRAG` 同源的几何（去掉饱和度 / 装饰 / 压暗，圆外改透明）。
     *
     * ⚠️ 所有 `y` 的口径见类注释：`uCenter.y` 用 `0.5 - ty`，`src` 直接回 0..1
     * （主体位图是正常方向，没有 FBO 那层翻转）。
     */
    private const val AGSL = """
uniform shader uPhoto;
uniform float4x4 uTexMatrix;
uniform float2 uViewSize;
uniform float2 uCenter;
uniform float uAspect;
uniform float uAspectRecip;
uniform float uLensScale;
uniform float uDistortion;
uniform float uZoom;
uniform float uBlurPx;
uniform float uDecorAlpha;

// ── 留光（相纸）专用（2026-11-09）────────────────────────────────────────
// 留光没有球：主体要跟**相纸**走，几何与 `AliveGlCore.LIUGUANG_FRAG` 逐字同源。
// 只在这一组被用到时切分支，启航（uPaperMode = 0）完全走原来的球路径。
/** 1 = 留光（相纸映射），0 = 启航（球）。 */
uniform float uPaperMode;
/** 相纸画布边长 / 屏宽（AOD 0.70486 / 锁屏桌面 2.35）。 */
uniform float uPaperScale;
/** 相纸倾角（弧度，直接传 st.rot，**不取反** —— 见下方推导）。 */
uniform float uPaperRot;
/** 相纸中心在屏幕高度的比例（从顶部，0..1）。 */
uniform float uPaperCenterY;
/** 官方 `values[3]`（contentScale）：AOD 1.0 / 锁屏桌面 aspect/2.35。 */
uniform float uPaperContent;

/**
 * 主体透明度 = `1 - photoAlpha`（**在 `onDraw` 里现算**）。
 *
 * 以前是写 `v.alpha`，但那会被 `resetAlpha()`（ACTION_SCREEN_ON）抬回 1，
 * 而 AOD 稳态已经停笔、没有下一帧来纠正 ⇒ 主体就那么亮着。
 * 写进 shader 之后 `photoAlpha = 1` 恒等于透明，`v.alpha` 只留给解锁的 0.1s 淡出。
 */
uniform float uSubjectAlpha;
/**
 * 相片内容遮罩：mask 帧的 **G 通道**（`AliveMaskFrames.GT` × `GT`，灰度打包成 ARGB）。
 *
 * 这是主体层裁切与 alpha 的**唯一来源** —— 与背景相纸用的是同一帧同一通道，
 * 所以「每一帧锁屏的裁剪区、alpha 是多少，主体层就是多少」。
 */
uniform shader uMask;
/** `uMask` 的边长（像素）。 */
uniform float uMaskSize;

float2 lensDistortion(float2 r, float a) {
    return r * (1.0 - a * dot(r, r));
}

float2 zoomPoint(float2 uv, float2 p, float z) {
    return (uv - p) * z + p;
}

const float3 SAT_W = float3(0.2125, 0.7154, 0.0721);

float3 increaseSaturation(float3 base, float strength) {
    float lum = dot(base, SAT_W);
    return mix(float3(lum), base, 1.0 + strength);
}

// 球面阴影：与 `AliveGlCore.LENS_FRAG` 的 `decorAlpha` 逐系数一致（21 项，5 阶）。
// 参数 p 是**归一化球面坐标**（-1..1），下方最暗。
//
// ⚠️ 符号：GL 里是 `vec2(uv.x - center.x, center.y - uv.y) / uLensScale`，
//    而 AGSL 的 `uv.y` 与 GL 互为镜像（View 原点在顶、GL uv 原点在底），
//    两次取反正好抵消 ⇒ 这里直接用 `(uv - center)`，**不要再取反**。
//    验法：球下方 `uv - center` 的 y 为正 → 多项式里 `+0.38*v` 占主导 → 更暗 ✓
float decorAlpha(float2 p) {
    float u = p.x;
    float v = p.y;
    float u2 = u * u, u3 = u2 * u, u4 = u3 * u, u5 = u4 * u;
    float v2 = v * v, v3 = v2 * v, v4 = v3 * v, v5 = v4 * v;
    float a =
        0.17079832
        + 0.38000503 * v + 0.27049240 * v2 - 0.02328848 * v3 - 0.08169705 * v4 - 0.01471029 * v5
        - 0.37975070 * u - 0.16269897 * u * v + 0.23632210 * u * v2
        + 0.13708094 * u * v3 - 0.07022234 * u * v4
        + 0.27037001 * u2 - 0.23601462 * u2 * v - 0.06374525 * u2 * v2 + 0.04455454 * u2 * v3
        + 0.02329872 * u3 + 0.13719989 * u3 * v - 0.04486833 * u3 * v2
        - 0.08159617 * u4 + 0.07015046 * u4 * v
        + 0.01459191 * u5;
    return clamp(a, 0.0, 0.8);
}

// 与背景同源的模糊：半径 = min(lens.blur, BLUR_LIMIT)，逐帧插值。
// AGSL 是单 pass，做不了「水平→垂直两趟」，但偏移 ≤12px、球又小，
// 圆盘 13 tap 与背景的可分离高斯看不出差别。
// 分档只是为了省：blur<4 时 5 tap 就够（那个量级本身也糊不出细节）。
half4 sampleBlur(float2 px) {
    if (uBlurPx < 0.5) {
        return uPhoto.eval(px);                       // 锁屏态：单次采样
    }
    if (uBlurPx < 4.0) {
        half4 acc = uPhoto.eval(px) * 2.0;
        float wsum = 2.0;
        float r = uBlurPx * 1.2;
        for (int i = 0; i < 4; i++) {
            float a = float(i) * 1.5707963 + 0.7853982;
            acc += uPhoto.eval(px + float2(cos(a), sin(a)) * r);
            wsum += 1.0;
        }
        return acc / wsum;
    }
    // ⚠️ 半径必须是 **2σ**：`AliveGlCore.kernel()` 里 `sigma = radius`，
    //    截断半径是 `sqrt(-2σ²·ln(1/256·norm)) ≈ 2.07σ`。只取 σ 会「不够糊」。
    half4 acc = uPhoto.eval(px) * 1.2;
    float wsum = 1.2;
    for (int i = 0; i < 20; i++) {
        float fi = float(i) + 0.5;
        float rr = sqrt(fi / 20.0) * uBlurPx * 2.0;    // 圆盘内均匀分布到 2σ
        float a = fi * 2.39996323;                     // 黄金角，避免规则网格
        float w = exp(-3.0 * fi / 20.0);               // σ=R/2 对应的衰减
        acc += uPhoto.eval(px + float2(cos(a), sin(a)) * rr) * w;
        wsum += w;
    }
    return acc / wsum;
}

half4 main(float2 fragCoord) {
    float2 vUv = fragCoord / uViewSize;

    // ── 留光：**相纸映射**（2026-11-09）────────────────────────────────
    // 与 `AliveGlCore.LIUGUANG_FRAG` 同一套局部坐标，只是坐标系 y 向下（View 原点在顶）。
    //
    // 符号推导：GL 侧 pass 的 y 向上、`uRot` 传的是 `radians(-rot)`，shader 里再算
    // `r = (ca·dx + sa·dy, -sa·dx + ca·dy)`；换到 y 向下时 `dy` 多一个负号、
    // `sa = sin(-rot)` 也多一个负号，**两次取反抵消** ⇒ 这里直接用 `sin(+rot)`、
    // 公式形状与 GL 完全一致。
    if (uPaperMode > 0.5) {
        float2 p = float2(vUv.x, vUv.y * uAspect);
        float2 pc = float2(0.5, uPaperCenterY * uAspect);
        float2 dd = p - pc;
        float ca = cos(uPaperRot);
        float sa = sin(uPaperRot);
        float2 r = float2(ca * dd.x + sa * dd.y, -sa * dd.x + ca * dd.y);
        // ── 裁切 / alpha = **mask 的 G 通道**（逐帧与锁屏完全同源）──────────────
        // 用户 2026-11-09 定稿：「每一帧，锁屏的裁剪区域、alpha 值是多少，主体层就是多少」。
        // 相纸里「照片内容」的可见度**就是** mask 的 G 通道（`LIUGUANG_FRAG` 里
        // `alpha = 1 - m.g` 的反面），实测是圆角矩形：宽恒 324/720、高 299→719、
        // 四角圆角 7→28→0、边缘 1~2px 硬边。
        // 用 bbox 近似必然差几像素、四个角是方的（用户：「裁得比 green 还里面」）。
        float2 cq = 0.5 + r / uPaperScale;
        float m = uMask.eval(clamp(cq, float2(0.0015, 0.0015), float2(0.9985, 0.9985)) * uMaskSize).r;
        if (m <= 0.002) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }
        // 内容取景（官方 `u_contentMatrix`）：与背景 `cuv` 同一个公式
        float2 cuv = 0.5 + (cq - 0.5) * float2(uAspect / uPaperContent, 1.0 / uPaperContent);
        half4 colP = uPhoto.eval(clamp(cuv, 0.002, 0.998) * uViewSize);
        // ⚠️ **必须连 RGB 一起乘**（2026-11-10）：AGSL 的 `eval()` 返回的是**预乘**颜色，
        //    只把 alpha 压小、RGB 不变 ⇒ 合成时按 1/alpha 反预乘，画面会**越淡越亮**
        //    （真机：「锁屏到 AOD 主体层越来越亮」）。
        half k = m * uSubjectAlpha;
        return half4(colP.rgb * k, colP.a * k);
    }

    float2 uv = float2(vUv.x, vUv.y * uAspect);
    float2 c = float2(uCenter.x, uCenter.y * uAspect);
    float dist = distance(uv, c);
    // 圆外透明；边缘 4‰ 软一下，避免和背景球的边差一两个像素。
    float edge = 1.0 - smoothstep(uLensScale - 0.004, uLensScale, dist);
    if (edge <= 0.002) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }
    float2 d = zoomPoint(uv + lensDistortion(uv - c, uDistortion), c, uZoom);
    // 采样坐标交给**与背景 lens pass 同一个** uTexMatrix（含自转 rot 与照片平移 trans）。
    // 它吃 GL 口径的 NDC（y 向上），所以先回到 0..1 再取反 y。
    // 锁屏态（x=y=0、angle≡0）下它的输出正好等于「y 向下的 0..1」——
    // 因为 copy 的 uFlip 与矩阵末尾的 T(0,1)·S(1,-1) 恰好抵消，不用再翻一次。
    float2 srcAg = float2(d.x, d.y * uAspectRecip);
    float2 srcGl = float2(srcAg.x, 1.0 - srcAg.y);
    float4 t = uTexMatrix * float4(srcGl * 2.0 - 1.0, 0.0, 1.0);
    half4 col = sampleBlur(t.xy * uViewSize);
    float3 rgb = float3(col.rgb);
    if (uDecorAlpha > 0.0001) {
        // 与背景 lens pass 末尾逐字一致：先提饱和、再叠球面阴影。
        // 不做这一步，AOD 里主体就是「亮的一块」，跟球面明暗对不上。
        rgb = increaseSaturation(rgb, uDecorAlpha * 0.5);
        rgb = mix(rgb, float3(0.0), decorAlpha((uv - c) / uLensScale) * uDecorAlpha);
    }
    // 预乘：压 alpha 必须连 RGB 一起压（2026-11-10，同留光那条）
    half k = half(col.a * edge * uSubjectAlpha);
    return half4(half3(rgb) * k, k);
}
"""

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val logged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun logOnce(tag: String): Boolean = logged.putIfAbsent(tag, true) == null

    private var deductedId = 0

    /** 我们自己的主体 View（插在 `keyguard_foreground_layer` 的 index 0）。 */
    @Volatile private var view: SubjectView? = null
    @Volatile private var installedKey = ""
    private var bitmap: Bitmap? = null

    /** 解锁淡出进行中：这期间不再让渲染器推 alpha（但几何照推）。 */
    @Volatile private var fading = false

    /**
     * 锁屏态主体该用的缩放：**跟壁纸 leash 的预放大同步**（照 `SpatialOverlay` / `RasterOverlay`）。
     *
     * ## 为什么会有 5%
     *
     * 只有「AOD 与锁屏样式相同」（`miuiFullAodManager.fullAodEnable()`）这一种模式下，系统才会
     * 在锁屏态给壁纸 leash 常驻 `setMatrix(1.05)`（`linkageViewAnim` 的 `f = 1.05f`）。
     * 我们的 GL 两份（`he-alive-lock` / `he-alive-desk`）是那条 leash 的**子层** ⇒ 跟着放大 5%；
     * 而本 View 挂在 `keyguardForegroundLayer`（SystemUI 窗口内）⇒ **不吃 leash 矩阵**。
     * 两者就差 5%，视觉上正是「主体比背景小 0.05、轮廓对不齐」（2026-11-09 用户报的）。
     *
     * 自定义 AOD 模式下系统从不调 `doWallpaperScaleAnim` ⇒ leash 就是 1.0，这里也必须 1.0
     * （写死 1.05 会反向多放大，空间壁纸 2026-10-30 踩过）。
     *
     * 值由 `SpatialForegroundHider.syncLeashScale()` 在每次锁屏视图更新时下发；未确认前按 1.0。
     */
    @Volatile private var leashScale = 1f

    /**
     * 主体层**当前应有的 alpha**（渲染器每帧推、解锁淡出推 0、回锁屏推 1）。
     *
     * 单独存一份是为了 [attach]：`View` 的 `alpha` 默认是 **1**，前景层每次重建
     * 我们都会 new 一个新的 `SubjectView`（真机日志里一晚上插了十几次），
     * 新的那份在渲染器下一帧推值之前就是「完全不透明」—— 桌面→AOD 那一瞬
     * 主体会冒出来一下（2026-11-09 用户报的「还能看见主体层」）。
     * 出生就按这个值设，别再依赖「下一帧会推下来」。
     */
    @Volatile private var curAlpha = 1f

    /** 上一帧推过的值（变化超过阈值才投递主线程，避免 60fps 刷爆消息队列）。 */


    // ── 被我们摘下来的系统主体（照抄 RasterOverlay 的四个字段） ──
    private var systemSubject: View? = null
    private var systemSubjectParent: ViewGroup? = null
    private var systemSubjectIndex = 0
    private var systemSubjectVisibility = View.VISIBLE

    private fun resolveId(ctx: Context) {
        if (deductedId != 0) return
        runCatching {
            deductedId = ctx.resources.getIdentifier(ID_DEDUCTED, "id", SYSTEMUI_PKG)
            HookRuntime.log("ALIVE 景深 id 解析 ⇒ 系统主体视图=$deductedId")
        }
    }

    /**
     * 由 `SpatialForegroundHider` 在 `updateKeyguardElementsVisibility()` 之后调用。
     *
     * 幂等：host / key 没变就直接返回；变了才重建。系统每次状态变化都会走到 ⇒ 天然自愈。
     */
    fun ensure(host: ViewGroup) {
        // ⚠️ 必须同时看开关（2026-11-14 真机发现）：只看 KEY 的话，Alive 关掉之后
        // `alive_key` 还留在属性里（关闭时只把 active 置 0），这里会继续把系统景深主体
        // 摘掉、把我们的主体插进 `keyguard_foreground_layer` —— 日志里
        // `ALIVE 景深 已摘掉系统主体` 在 active=0 时还在刷，屏幕上就是"关了还留着东西"。
        if (HookRuntime.systemProperty(AliveFlag.PROP_ACTIVE) != "1") {
            runCatching { onDisabled() }
            return
        }
        // ⚠️ 还要看**渲染器在不在跑**（2026-11-14 真机）：AOD 关掉 / 启动失败时
        // `runningKey == null`，但 active 仍是 1、key 也还在 ⇒ 这里会把**上一帧的
        // 人像位图**继续插在 `keyguard_foreground_layer` 上。用户看到的就是
        // 「Alive 明明没在跑，锁屏上却还有我们的东西」，MC 的封面也会被它压住。
        if (!AliveRenderer.isRunning()) {
            runCatching { onDisabled() }
            return
        }
        val key = HookRuntime.systemProperty(AliveFlag.PROP_KEY)
        if (key.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) attach(host, key)
        else mainHandler.post { runCatching { attach(host, key) } }
    }

    /**
     * 把系统那个景深主体**从父容器摘掉**（照抄 `RasterOverlay.detachSystemSubject`）。
     *
     * ⚠️ 不能只设 `INVISIBLE`：系统的 folme 动画会在下一帧把可见性翻回来。必须 `removeView`。
     */
    fun detachSystemSubject(host: ViewGroup): Boolean {
        val held = systemSubject
        if (held != null) {
            if (systemSubjectParent === host) return true
            systemSubject = null
            systemSubjectParent = null
        }
        val ctx = host.context
        resolveId(ctx)
        if (deductedId == 0) return false
        val v = host.rootView.findViewById<View>(deductedId) ?: return false
        val parent = v.parent as? ViewGroup ?: return false
        val idx = parent.indexOfChild(v)
        systemSubjectVisibility = v.visibility
        runCatching { parent.removeView(v) }.onFailure {
            HookRuntime.log("ALIVE 景深 摘系统主体失败：${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        systemSubject = v
        systemSubjectParent = parent
        systemSubjectIndex = idx
        HookRuntime.log("ALIVE 景深 已摘掉系统主体（index=$idx，visibility=$systemSubjectVisibility）")
        return true
    }

    /** 关闭时按原 index 插回系统主体（visibility 恢复原值，之后由系统重算接管）。 */
    private fun restoreSystemSubject() {
        val v = systemSubject ?: return
        val p = systemSubjectParent
        systemSubject = null
        systemSubjectParent = null
        if (p == null) return
        runCatching {
            if (v.parent == null) {
                p.addView(v, systemSubjectIndex.coerceIn(0, p.childCount))
            }
            v.visibility = systemSubjectVisibility
        }.onFailure {
            HookRuntime.log("ALIVE 景深 插回系统主体失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 清场：移掉我们的 View + **把系统主体插回去**（幂等）。 */
    fun onDisabled() {
        if (view == null && installedKey.isEmpty() && systemSubject == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) disabledOnMain()
        else mainHandler.post { runCatching { disabledOnMain() } }
    }

    private fun disabledOnMain() {
        runCatching { detach() }
        runCatching { restoreSystemSubject() }
    }

    /** 建/换我们自己的主体 View（照抄 `RasterOverlay.attach`）。 */
    private fun attach(host: ViewGroup, key: String) {
        val cur = view
        if (cur != null && cur.parent === host && installedKey == key) return
        detach()

        val v = SubjectView(host.context)
        runCatching {
            host.addView(
                v,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }.onFailure {
            HookRuntime.log("ALIVE 景深 插入前景层失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        view = v
        installedKey = key
        fading = false
        // 出生就带上 leash 预放大与当前 alpha（否则第一次进锁屏会差 5%、
        // 桌面→AOD 那一瞬会冒出一个不透明的主体）
        v.scaleX = leashScale
        v.scaleY = leashScale
        v.alpha = curAlpha
        // AOD 里插入（curAlpha=0）就别画，省一次全屏 AGSL
        v.visibility = if (curAlpha <= 0.004f) View.INVISIBLE else View.VISIBLE
        // 新 View 没有任何几何（`pMatrix` 也是空）⇒ 让渲染器立刻补一帧推下来。
        // 不补的话，静止的锁屏（帧循环已停笔）会一直画默认几何。
        runCatching { AliveRenderer.requestSubjectRefresh() }
        HookRuntime.log("ALIVE 景深 前景主体已插入（key=$key，host=${host.javaClass.simpleName}）")

        val ctx = host.context.applicationContext ?: host.context
        Thread {
            val bmp = readDepth(ctx, key)
            mainHandler.post {
                if (bmp == null) {
                    HookRuntime.log("ALIVE 景深 主体图读不出来（key=$key）")
                    return@post
                }
                if (view !== v) {
                    bmp.recycle()
                    return@post
                }
                v.setBitmap(bmp)
                bitmap?.recycle()
                bitmap = bmp
                HookRuntime.log("ALIVE 景深 主体已挂上（${bmp.width}×${bmp.height}，key=$key）")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detach() {
        val v = view ?: return
        view = null
        installedKey = ""
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
    }

    /** 读景深主体：`content://…/alive/<key>/depth`（优先 AICR 抠像，回落用户导入的遮罩）。 */
    private fun readDepth(ctx: Context, key: String): Bitmap? = runCatching {
        val uri = Uri.parse("content://$AUTHORITY/alive/$key/depth")
        if (logOnce("load-$key")) HookRuntime.log("ALIVE 景深 开始读 $uri")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // ⚠️ **必须 cover 到屏幕尺寸**（2026-11-08「主体错位」的根因）：
        //    `BitmapShader` 的 `eval()` 是**像素 1:1** 映射，不是"整张图铺满绘制区"。
        //    2000×3333 的主体图叠在 1200×2670 的 View 上，`src * uViewSize` 只取到
        //    位图左上角那一块 ⇒ 主体和背景球里的内容完全错位。
        //    裁剪口径还要与 `AliveRenderer.loadBitmap` 的 `coverToScreen` 一致
        //    （居中 cover），否则两张图虽然都"满屏"，内容位置仍差一截。
        val dm = ctx.resources.displayMetrics
        val dstW = dm.widthPixels.coerceAtLeast(1)
        val dstH = dm.heightPixels.coerceAtLeast(1)
        var sample = 1
        val short = minOf(bounds.outWidth, bounds.outHeight)
        val cap = maxOf(dstW, dstH)
        while (short / (sample * 2) >= cap) sample *= 2
        val raw = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return null
        coverToScreen(raw, dstW, dstH)
    }.onFailure {
        HookRuntime.log("ALIVE 景深 读图失败：${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 居中 cover 裁剪到屏幕比例，再用 Skia 缩到屏幕尺寸（与 `AliveRenderer.coverToScreen` 同一口径）。
     *
     * 产物与 View 尺寸 1:1 ⇒ `BitmapShader` 的 `eval(src * uViewSize)` 才是对的。
     */
    private fun coverToScreen(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        if (src.width == dstW && src.height == dstH) return src
        val srcAspect = src.width.toFloat() / src.height.coerceAtLeast(1)
        val dstAspect = dstW.toFloat() / dstH
        val cropW: Int
        val cropH: Int
        if (srcAspect > dstAspect) {
            cropH = src.height
            cropW = (src.height * dstAspect).toInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width / dstAspect).toInt().coerceIn(1, src.height)
        }
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val cropped = if (cropW == src.width && cropH == src.height) {
            src
        } else {
            runCatching { Bitmap.createBitmap(src, left, top, cropW, cropH) }.getOrNull() ?: return src
        }
        val scaled = runCatching { Bitmap.createScaledBitmap(cropped, dstW, dstH, true) }.getOrNull()
        if (cropped !== src) cropped.recycle()
        if (scaled == null) return src
        if (scaled !== src) src.recycle()
        return scaled
    }

    /**
     * 每帧推一次（由 `AliveRenderer` 在 GL 线程调用）。
     *
     * 直接把 [lens] 整套几何传下去 —— 主体层要跟背景球**同源变形**，
     * 只传 scale 是不够的（那只能均匀缩放）。
     */
    /**
     * **启航（球）**：每帧只 poke 一次，几何/alpha/矩阵全部交给 View 当场采样。
     *
     * 与留光同一个数据流（2026-11-10）：推值会让主体恒差 1~2 帧。启航的轨道比留光复杂
     * （`fromState → targetState` 的十路插值 + AOD 自转），所以采样放在渲染器里
     * （`AliveRenderer.sampleSubject`），View 只负责在 `onDraw` 那一刻调一次。
     */
    fun onQihangFrame() {
        if (view == null) return
        if (invalidatePending) return
        invalidatePending = true
        pokeAt = SystemClock.elapsedRealtime()
        mainHandler.post {
            invalidatePending = false
            val v = view ?: return@post
            v.exitPaperMode()
            // 内容 alpha 在 shader 里（`uSubjectAlpha`），`v.alpha` 只归解锁淡出
            if (!fading) {
                curAlpha = 1f
                v.alpha = 1f
            }
            v.invalidateSphere()
        }
    }

    /** 合并投递：一帧内多次 poke 只 post 一次（照 `SpatialOverlay.onAttitudeChanged`）。 */
    @Volatile private var invalidatePending = false

    /** 最近一次 poke 的时刻（GL 线程写）——诊断「poke → 真正绘制」的间隔。 */
    @Volatile private var pokeAt = 0L

    /**
     * **留光（相纸）**：每帧只 poke 一次，几何/alpha/遮罩全部交给 View 自己采样。
     *
     * ## 为什么不推值（2026-11-10 定案，照 `SpatialOverlay.onAttitudeChanged`）
     *
     * 推值 = 主体画的是「GL 那一帧算好的状态」，而它要晚 1~2 帧才上屏 ⇒ 相对背景
     * **恒差 1~2 帧**（留光出 AOD 是 450ms 内位移 0.165 屏高 + 缩放 3.3 倍，
     * 一帧就是 16px 的错位，肉眼一眼看出「两个层」）。
     *
     * 空间的压力不比留光小却能完全对齐，就是因为它**在 `onDraw` 里取实时姿态**：
     * 主体和背景各自的「采样 → 上屏」都是 1 帧，相位差被抵消；而且掉一帧是
     * 「直接跳到最新状态」（自愈），不是重放旧状态（累积滞后）。
     *
     * 所以这里只 `invalidate()`，参数由 `SubjectView.onDraw` 现场调
     * [AliveLiuguang.sampleInto] / [AliveMaskFrames.peekGray] 拿。
     */
    fun onPaperFrame() {
        if (view == null) return
        // 合并投递：`drawScene` 每帧可能被两个 mount 各调一次
        if (invalidatePending) return
        invalidatePending = true
        pokeAt = SystemClock.elapsedRealtime()
        mainHandler.post {
            invalidatePending = false
            val v = view ?: return@post
            v.enterPaperMode()
            // ⚠️ **内容 alpha 已经在 shader 里了**（`uSubjectAlpha`），`v.alpha` 只归
            //    解锁那 0.1s 淡出所有。所以这里必须把它抬回 1 —— 否则
            //    ① 从启航切过来时还留着上一轮的 0（AOD 里主体是透明的）；
            //    ② 解锁淡出之后 `fading` 清掉、却没人把 alpha 抬回来 ⇒ 锁屏看不见人。
            if (!fading) {
                curAlpha = 1f
                v.alpha = 1f
            }
            v.invalidate()
        }
    }

    /**
     * 结束淡出、让 `onQihangFrame` / `onPaperFrame` 重新接管（进 AOD / 回锁屏时调）。
     *
     * 不清 `fading` 的话，解锁后不灭屏直接回锁屏，`fading` 会一直停在 true
     * （它只在 `resetAlpha()` 里清，而那个挂在 `ACTION_SCREEN_ON`）。
     */
    fun endFade() {
        if (!fading) return
        fading = false
    }

    /** 解锁退场：主体层 0.1s 淡出（用户 2026-11-08 指定）。 */
    fun fadeOutOnUnlock() {
        val v = view ?: return
        curAlpha = 0f
        mainHandler.post {
            if (fading) return@post
            fading = true
            v.animate().cancel()
            v.animate()
                .alpha(0f)
                .setDuration(UNLOCK_FADE_MS)
                .withEndAction {
                    if (v.alpha <= 0.004f) v.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    /** 回锁屏 / 重新亮屏：复位到「不透明」。几何由 View 在 `onDraw` 里现取。 */
    fun resetAlpha() {
        // ⚠️ `fading` 要先清 —— 否则 view 还没建（首次进锁屏）时下面 return，
        //    标志就永久卡 true，之后所有主体帧全被挡掉。
        fading = false
        curAlpha = 1f
        val v = view ?: return
        mainHandler.post {
            v.animate().cancel()
            v.alpha = 1f
            v.visibility = View.VISIBLE
            // 幂等兜底：万一某次 sync 没赶上，回锁屏时也把 leash 缩放拉正
            v.scaleX = leashScale
            v.scaleY = leashScale
        }
    }

    /**
     * 由 `SpatialForegroundHider.syncLeashScale()` 下发（幂等，照 `RasterOverlay.setLeashScale`）。
     *
     * @param scale 「AOD 与锁屏样式相同」时为 1.05（跟随 leash 预放大），自定义 AOD 时为 1.0
     */
    fun setLeashScale(scale: Float) {
        if (kotlin.math.abs(scale - leashScale) < 0.001f) return
        leashScale = scale
        if (Looper.myLooper() == Looper.getMainLooper()) applyLeashScaleOnMain()
        else mainHandler.post { runCatching { applyLeashScaleOnMain() } }
        HookRuntime.log("ALIVE 景深 主体缩放 → $scale（跟随壁纸 leash 预放大）")
    }

    private fun applyLeashScaleOnMain() {
        val v = view ?: return
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    /**
     * 主体 View：全屏、透明底，内容全由 AGSL 算（与背景球同源几何）。
     *
     * ⚠️ 参数从 GL 线程写、主线程读，全部 `@Volatile`；`invalidate()` 必须在主线程。
     */
    private class SubjectView(ctx: Context) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: RuntimeShader? = null
        private var shaderFailed = false
        private var bitmapShader: BitmapShader? = null

        /** 内容遮罩（mask 的 G 通道），见 [ensureMask]。 */
        private var maskBmp: Bitmap? = null
        private var maskShader: BitmapShader? = null
        private var maskSize = 1
        private var maskLogged = false

        /** `lastMaskFrame` 对应的就是 [maskBmp] 里的内容。 */
        private var lastMaskFrame = -1

        /** 留光的采样实例（**自己一份**，绝不去读 GL 线程那个 `State`）。 */
        private val paper = AliveLiuguang.State()

        /** 启航的采样实例（同样自己一份，渲染器往这里写）。 */
        private val subjectSample = AliveRenderer.SubjectSample()

        /** 主体透明度 = `1 - photoAlpha`（在 `onDraw` 里现算）。 */
        private var subjectAlphaUniform = 1f

        /** 相位诊断：累计绘制次数 / 观测到的最大 poke→draw 间隔。 */
        private var paperDraws = 0
        private var maxPokeDelay = 0L

        init {
            // 默认 1×1 全白：启航路径要绑一个非空 input（未绑定的 AGSL input 采样不可靠），
            // 留光首帧还没拿到 mask 时也等价于「整块画布不裁切」，而不是整片消失。
            val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            b.setPixel(0, 0, 0xFFFFFFFF.toInt())
            maskBmp = b
            maskShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        @Volatile private var pScale = 1f
        @Volatile private var pTx = 0f
        @Volatile private var pTy = 0f
        @Volatile private var pDistortion = 0f
        @Volatile private var pZoom = 0.5f
        @Volatile private var pBlur = 0f
        @Volatile private var pDecor = 0f

        // ── 留光（相纸）参数（2026-11-09）──
        /** 1 = 走相纸映射，0 = 走球。 */
        @Volatile private var pPaperMode = 0f
        @Volatile private var pPaperScale = 1f
        @Volatile private var pPaperRot = 0f
        @Volatile private var pPaperCy = 0.5f
        @Volatile private var pPaperContent = 1f


        /** 与背景 lens pass 同一个 `photoMatrix`（含自转 + 照片平移）。每次换新数组，避免跨线程改。 */
        @Volatile private var pMatrix: FloatArray? = null

        /**
         * **pivot 必须对齐系统壁纸 leash 的 (50%, 40%)**（2026-11-09「主体比背景向上偏了一点」）。
         *
         * 系统 `KeyguardPanelViewController#doDeductedImageScaleAnim` 给壁纸 leash 的 matrix
         * 用的是 `MiuiConfigs.getLockScreenSize()` 的 **(50%, 40%)** 当 pivot（见分支
         * `SpatialOverlay.SubjectView.onSizeChanged` 同一条注释，2026-10-30 定案）。
         * View 默认绕中心 **(50%, 50%)** 缩放 ⇒ 我们跟着放大 1.05 时，垂直方向会差
         * `0.1 × 屏高 × 0.05 ≈ 13px`，正好是「主体比背景向上偏」的量级（水平为 0）。
         */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w * 0.5f
            pivotY = h * 0.4f
        }

        fun setBitmap(bmp: Bitmap) {
            bitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            invalidate()
        }

        /**
         * 切回启航（球）分支。
         *
         * **不传几何**（2026-11-10）：启航的几何也在 `onDraw` 里现场采样，
         * 见 `AliveOverlay.onQihangFrame`。
         */
        fun exitPaperMode() {
            pPaperMode = 0f
        }

        /**
         * 留光路径：相纸映射（2026-11-09）。`rotRad` 传 `st.rot` 的弧度，不取反。
         *
         * [rect] 是相片**内容框**（画布坐标 0..1，`[left, top, right, bottom]`）——
         * 主体只能出现在这一块里：相纸宽恒 322/720，按整块画布裁会大 2.2 倍
         * （真机表现 =「主体没参与裁切 + 像素拉伸带」）。
         */
        /**
         * 切到留光（相纸）分支。
         *
         * **只置模式、不传几何**（2026-11-10）：几何在 [onDraw] 里按绘制时刻自己采样，
         * 见 `AliveOverlay.onPaperFrame` 的注释。
         */
        fun enterPaperMode() {
            pPaperMode = 1f
        }

        /**
         * 换一帧内容遮罩（主线程）。
         *
         * `BitmapShader` **每帧重建**：Bitmap 的像素是原地改的（`setPixels`），
         * 老 shader 在 HWUI 里会缓存上一次的纹理，不重建就永远是第一帧的形状。
         */
        fun ensureMask(frame: Int) {
            // 帧号量化到偶数帧 ⇒ 每两帧才真的换一次；同一帧重复 setPixels 是白写 129 600 个像素
            if (frame == lastMaskFrame) return
            val gf = AliveMaskFrames.peekGray(frame) ?: return
            val size = AliveMaskFrames.GT
            val gray = gf.gray
            var bmp = maskBmp
            if (bmp == null || bmp.width != size) {
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                maskBmp = bmp
            }
            bmp.setPixels(gray, 0, size, 0, 0, size, size)
            maskShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            maskSize = size
            lastMaskFrame = frame
            if (!maskLogged) {
                maskLogged = true
                // 中心像素 = 相片内容区（应该接近 255）；角上应该是 0。
                // `uMask` 采样为 0 的话主体整片透明，这条能一眼分出来是「没绑上」还是「mask 全黑」。
                HookRuntime.log(
                    "ALIVE 景深 遮罩已挂 ${size}² 中心=${gray[(size / 2) * size + size / 2] and 0xFF} " +
                        "角=${gray[0] and 0xFF}",
                )
            }
        }

        /**
         * 只重绘**球的包围盒**。
         *
         * 圆外的像素在 shader 里本来就早退，但仍然要跑一遍 fragment 入口；
         * 给 `invalidate` 划出范围后 GPU 连入口都不进（转场初期球接近满屏时差别不大，
         * AOD 端球只有 0.22 屏宽，省下来的就是 95% 的光栅化）。
         */
        fun invalidateSphere() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) {
                invalidate()
                return
            }
            // 脏区必须按**这一帧**的几何算（不能用上一帧画完留下的 p*，球在转，会裁掉边缘）
            AliveRenderer.sampleSubject(
                SystemClock.elapsedRealtime(), subjectSample, withMatrix = false,
            )
            val st = subjectSample.lens
            val cx = (0.5f + st.x) * w
            val cy = (0.5f - st.y) * h
            // uLensScale 是 uv 空间（x 为 0..1 的归一化半径）⇒ 像素半径 = scale * w
            val r = st.scale * w + minOf(st.blur, AliveGlCore.BLUR_LIMIT) + 2f
            invalidate(
                (cx - r).toInt(), (cy - r).toInt(),
                (cx + r).toInt(), (cy + r).toInt(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            if (alpha <= 0.001f) return
            val bs = bitmapShader ?: return
            val s = shader ?: run {
                if (shaderFailed) return
                runCatching { RuntimeShader(AGSL) }
                    .onFailure {
                        shaderFailed = true
                        HookRuntime.log("ALIVE 景深 AGSL 编译失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrNull()
                    ?.also { shader = it }
                    ?: return
            }
            runCatching {
                // ── 留光：**在绘制的这一刻采样**（2026-11-10 定案）──────────────
                // 别再让渲染器把「上一帧算好的几何」推下来 —— 推值会让主体恒差 1~2 帧，
                // 真机就是「像两个层」。空间壁纸能完全对齐就是因为它在 onDraw 里取实时姿态。
                if (pPaperMode > 0.5f) {
                    val st = AliveLiuguang.sampleInto(SystemClock.elapsedRealtime(), paper)
                    pPaperScale = st.scale
                    pPaperRot = Math.toRadians(st.rot.toDouble()).toFloat()
                    pPaperCy = st.centerY
                    pPaperContent = st.content
                    // `subjectAlpha` 是轨道算好的（= 1 - photoAlpha，但「桌面→AOD」整段是 0）
                    subjectAlphaUniform = st.subjectAlpha.coerceIn(0f, 1f)
                    ensureMask(st.frame)
                    // 相位诊断（2026-11-10）：poke → 真正绘制 的间隔。
                    // 改之前是「推值」，这段恒 +1~2 帧；改成现场采样后应该只剩主线程排队。
                    val d = (SystemClock.elapsedRealtime() - pokeAt).coerceAtLeast(0L)
                    if (d > maxPokeDelay) maxPokeDelay = d
                    if (++paperDraws % 15 == 0) {
                        HookRuntime.log(
                            "ALIVE 景深 相位：poke→draw=${d}ms 最大=${maxPokeDelay}ms " +
                                "scale=${"%.3f".format(st.scale)} pα=${"%.2f".format(st.photoAlpha)}",
                        )
                    }
                } else {
                    // 启航（球）：同样在**绘制这一刻**采样（2026-11-10）。
                    // 轨道在渲染器里（`fromState→targetState` 十路插值 + 角度），
                    // 这里只取结果；`photoMatrix` 也一并算好（含自转角与照片平移）。
                    AliveRenderer.sampleSubject(SystemClock.elapsedRealtime(), subjectSample)
                    val st = subjectSample.lens
                    pScale = st.scale
                    pTx = st.x
                    pTy = st.y
                    // AGSL 里用的是 `distortion / scale`（与背景 `lensDistortion` 同口径）
                    pDistortion = if (st.scale > 1e-4f) st.distortion / st.scale else 0f
                    pZoom = 1f / (minOf(st.scale, 0.5f) * 4f)
                    pBlur = minOf(st.blur, AliveGlCore.BLUR_LIMIT)
                    pDecor = st.decorAlpha
                    pMatrix = subjectSample.matrix
                    subjectAlphaUniform = subjectSample.alpha.coerceIn(0f, 1f)
                    // AOD 稳态（球还在自转，帧循环 40fps）主体 alpha 恒 0：
                    // 这里直接不画，别为了一个全透明的结果跑满屏 AGSL。
                    // `onDraw` 不提交任何绘制指令 ⇒ HWUI 把这一层清成透明。
                    if (subjectAlphaUniform <= 0.002f) return
                }
                s.setInputShader("uPhoto", bs)
                s.setFloatUniform("uViewSize", w, h)
                // ⚠️ y 用 `0.5 - ty`：GL 的 uv.y 原点在底部，View 的在顶部
                //    （标定方式见《07》第 36 条：旧版 AOD_Y=0.335 ↔ 球在 16.7%）
                s.setFloatUniform("uCenter", 0.5f + pTx, 0.5f - pTy)
                s.setFloatUniform("uAspect", h / w)
                s.setFloatUniform("uAspectRecip", w / h)
                s.setFloatUniform("uLensScale", pScale)
                s.setFloatUniform("uDistortion", pDistortion)
                s.setFloatUniform("uZoom", pZoom)
                s.setFloatUniform("uBlurPx", pBlur)
                s.setFloatUniform("uDecorAlpha", pDecor)
                // 留光（相纸）参数：即使走球分支也设一遍 —— AGSL 的 uniform 必须有值
                s.setFloatUniform("uPaperMode", pPaperMode)
                s.setFloatUniform("uPaperScale", pPaperScale)
                s.setFloatUniform("uPaperRot", pPaperRot)
                s.setFloatUniform("uPaperCenterY", pPaperCy)
                s.setFloatUniform("uPaperContent", pPaperContent)
                s.setFloatUniform("uSubjectAlpha", subjectAlphaUniform)
                // 内容遮罩（mask 的 G 通道）：裁切与 alpha 都取自它
                maskShader?.let {
                    s.setInputShader("uMask", it)
                    s.setFloatUniform("uMaskSize", maskSize.toFloat())
                }
                pMatrix?.let { s.setFloatUniform("uTexMatrix", it) }
                paint.shader = s
                canvas.drawRect(0f, 0f, w, h, paint)
            }.onFailure {
                if (logOnceLocal("draw")) {
                    HookRuntime.log("ALIVE 景深 绘制失败：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

        private val localLogged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private fun logOnceLocal(tag: String): Boolean = localLogged.putIfAbsent(tag, true) == null
    }
}
