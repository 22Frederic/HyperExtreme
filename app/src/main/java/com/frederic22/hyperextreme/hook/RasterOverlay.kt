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
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 光栅壁纸「**锁屏景深**」的**前景主体层**（2026-11-04）。
 *
 * ## 它解决什么
 *
 * 光栅的两张图挂在壁纸 leash 上 —— 那条 leash 在 SurfaceFlinger 里比 SystemUI 窗口**低**
 * ⇒ 主体永远在时钟**下面**，看不出景深。系统的景深主体之所以能压住时钟，是因为它画在
 * `keyguard_foreground_layer`（`KeyguardPanelView` 的第 3 个子层，第 2 个是时钟）里。
 *
 * 所以这里把**当前这一帧的主体抠像**用 AGSL 画一遍、插进那一层 —— 位置、z 序、可见性
 * 全部跟着系统走。注入骨架与 [SpatialOverlay] 逐条相同（同一层、同一个系统主体、
 * 同一套 AOD / 解锁 / 缩放），差别只在**画什么**：
 *
 * | | 空间壁纸 | 光栅壁纸 |
 * |---|---|---|
 * | 数据 | `layers/layer1.webp` + `depth.png`（位移场） | `raster/<id>/subject/<序号>`（RGBA 抠像） |
 * | 几何 | 球面映射 + 逐像素位移 | **原样铺满**（与背景同一 cover 裁剪，1:1 采样） |
 * | 过渡 | 静止时不动 | 跟随擦除：`mix(from, to, a)`，公式与 [RasterGlCore.FRAG] 逐字一致 |
 *
 * ## 为什么用 AGSL 而不是第二份 GL
 *
 * 与空间壁纸同一条理由：不需要第二套 EGL surface（不会踩「不可见 surface 的
 * `eglSwapBuffers` 阻塞」），与时钟同窗口同一次绘制（没有跨层错位）。
 *
 * ## 主体图为什么必须 CPU 侧 cover 裁到屏幕
 *
 * 主体图是原图尺寸（实测 2802×4830），屏幕 1200×2670 —— 直接 `eval(coord)` 会把整张
 * 压进屏幕（比例不同 ⇒ 拉伸），而且与背景 GL 的 cover 裁剪不一致 ⇒ 错位。
 * 加载时按**与背景完全相同的 cover 矩形**裁到屏幕尺寸（见 [loadSubject]），
 * 之后 View 坐标与位图坐标 1:1，不需要在 shader 里插值。
 *
 * ## 没算过主体的图
 *
 * 该帧主体层**透明**（用户 2026-11-04 明确：等于没开景深）。用一个 1×1 全透明
 * BitmapShader 顶替，`mix` 自然产生淡入淡出，不会闪。
 */
internal object RasterOverlay {

    private const val AUTHORITY = "com.frederic22.hyperextreme.provider"

    /** 系统的前景层（`res/layout/keyguard_panel_view.xml` 第 3 个子层）。 */
    private const val ID_FOREGROUND_LAYER = "keyguard_foreground_layer"

    /** 系统景深主体（`keyguard_foreground_layer.xml` 里那个全屏 CENTER_CROP 的 ImageView）。 */
    private const val ID_DEDUCTED = "deducted_image_view"

    private const val SYSTEMUI_PKG = "com.android.systemui"

    /**
     * 擦除 + 玻璃着色器 —— 与 [RasterGlCore.FRAG] **同一套公式**（2026-11-06 v1.19.1）。
     *
     * 2026-11-06：主体层不再只做擦除，**选中哪档「过渡效果」就跟着做哪档**
     * （条纹 / 磨砂 / 扭曲），否则背景有玻璃、主体是干净的，带内会露馅。
     * 公式与 GL 那份逐字一致，只是采样从 `texture2D` 换成 `shader.eval`：
     *
     * ```
     * e     = dir > 0 ? uv.x : 1 − uv.x
     * pa    = t × (1 + 2·TransWidth) − TransWidth
     * pMask = smoothstep(pa − MaskTransWidth, pa, e) × smoothstep(pa + MaskTransWidth, pa, e)
     * a     = smoothstep(pa − AlphaTransWidth, pa + AlphaTransWidth, e)
     * ```
     *
     * `uScale = 主体位图尺寸 / View 尺寸`：正常情况下是 1（两边都是屏幕尺寸），
     * 留它是为了防止 View 与位图尺寸不一致时（系统改了前景层大小）出现错位。
     */
    private const val AGSL = """
        uniform shader uFrom;
        uniform shader uTo;
        // 色散的 RGB 源：原图（1/2 尺寸、不透明）。主体的透明区 RGB 已被清零，
        // 且 Skia 采样必预乘（a=0 ⇒ rgb=0），色散跨出轮廓时只能拿到 0 ⇒ 必须换源。
        uniform shader uFromRGB;
        uniform shader uToRGB;
        uniform float2 uRgbScale;
        uniform float2 uViewSize;
        uniform float2 uScale;
        uniform float uT;
        uniform float uDir;
        uniform float uBand;
        uniform float uTransWidth;
        uniform float uMaskTransWidth;
        uniform float uDim;
        uniform float uStyle;
        uniform float uBumpScale;
        uniform float uRefractionScale;
        uniform float uDispersionFactor;
        uniform float uShadowStrength;
        uniform float uStripePeriodPx;
        uniform float uFrostCellPx;
        uniform float uScreenW;
        uniform float uScreenH;
        uniform float3 uLightDir;

        float hash21(float2 p) {
            p = fract(p * float2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float noise2(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        float rasterHeight(float2 uv) {
            float x = uv.x * uScreenW / max(uStripePeriodPx, 1.0);
            if (uStyle < 1.5) {
                return 0.5 + 0.5 * cos(6.2831853 * x);
            } else if (uStyle < 2.5) {
                float2 q = float2(uv.x * uScreenW, uv.y * uScreenH) / max(uFrostCellPx, 0.5);
                return noise2(q);
            } else if (uStyle < 3.5) {
                // 扭曲变形：余弦 + 10% 扰动
                float n = noise2(float2(x * 5.0, 7.3));
                return clamp(0.5 + 0.46 * cos(6.2831853 * x) + 0.10 * (n - 0.5), 0.0, 1.0);
            }
            // 细线玻璃：vivo d4 贴图（实测 108x200）逐像素解出的波形。
            // 与 GL 的 [RasterGlCore.FRAG] **逐字同源**，否则「背景有细线、主体没有」会露馅。
            float u = fract(x) * 108.0;
            if (u < 48.0) {
                float t = u / 48.0;
                return 1.0 - t * t;
            } else if (u < 54.0) {
                return 0.5 * smoothstep(0.0, 1.0, (u - 48.0) / 6.0);
            } else if (u < 59.0) {
                return 0.5 * (1.0 - smoothstep(0.0, 1.0, (u - 54.0) / 5.0));
            }
            float t2 = (108.0 - u) / 48.0;
            return 1.0 - t2 * t2;
        }

        half4 main(float2 coord) {
            float2 uv = coord / uViewSize;
            // dir > 0（向左转）= 新图从左边进来；与 GL 那份**逐字一致**。
            float e = (uDir > 0.0) ? uv.x : (1.0 - uv.x);
            float pa = uT * (1.0 + 2.0 * uTransWidth) - uTransWidth;
            float a = smoothstep(pa - uBand, pa + uBand, e);

            float pMask = smoothstep(pa - uMaskTransWidth, pa, e)
                        * smoothstep(pa + uMaskTransWidth, pa, e);

            float2 ruv = uv;
            float3 normal = float3(0.0, 0.0, 1.0);
            float h0 = 0.0;
            if (uStyle > 0.5 && pMask > 0.001) {
                float px = 1.0 / max(uScreenW, 1.0);
                float l = rasterHeight(uv - float2(px, 0.0));
                float r = rasterHeight(uv + float2(px, 0.0));
                float dx = (r - l) * 0.5;
                float dy = 0.0;
                if (uStyle > 1.5 && uStyle < 2.5) {
                    float tt = rasterHeight(uv + float2(0.0, px));
                    float bb = rasterHeight(uv - float2(0.0, px));
                    dy = (tt - bb) * 0.5 * (uScreenW / max(uScreenH, 1.0));
                }
                normal = normalize(float3(dx * uBumpScale, dy * uBumpScale, 1.0));
                h0 = rasterHeight(uv);
                ruv = clamp(uv + normal.xy * uRefractionScale * pMask, 0.0, 1.0);
            }

            // ⚠️ SkSL **不允许 `shader` 作函数参数**（"parameters of type 'shader' not allowed"）
            // ⇒ 色散只能内联写两遍，不能抽成 sampleDisperse(shader, …)。
            half4 cf;
            half4 ct;
            float disp = uDispersionFactor * 0.16 * pMask * 208.333;
            if (uStyle > 3.5 && abs(ruv.x - uv.x) > 0.0005) {
                // 细线玻璃：d4 的 `applyDispersionEffect`（STEPS=1）——
                // R 不偏移、G 偏 1.0、B 偏 1.5。与下面 d1 的 Tx3 加权**不是一套算法**。
                float2 dxy = (ruv - uv) * disp;
                float2 p0 = ruv * uViewSize * uScale;
                float2 p1 = (ruv + dxy * 1.0) * uViewSize * uScale;
                float2 p2 = (ruv + dxy * 1.5) * uViewSize * uScale;
                // alpha 从主体取（ALPHA_8，只读 a）
                half4 f0 = uFrom.eval(p0);
                half4 f1 = uFrom.eval(p1);
                half4 f2 = uFrom.eval(p2);
                // RGB 从**背景层那份共享原图**取（不透明）：主体的透明区是透明黑，
                // 色散跨出轮廓时会整条归零 ⇒ 纯红/纯青描边。见《07》§7.15 第 20 条。
                half3 r0 = uFromRGB.eval(p0 * uRgbScale).rgb;
                half3 r1 = uFromRGB.eval(p1 * uRgbScale).rgb;
                half3 r2 = uFromRGB.eval(p2 * uRgbScale).rgb;
                cf = half4(half3(r0.r, r1.g, r2.b) * f0.a, f0.a);
                half4 t0 = uTo.eval(p0);
                half4 t1 = uTo.eval(p1);
                half4 t2 = uTo.eval(p2);
                half3 s0 = uToRGB.eval(p0 * uRgbScale).rgb;
                half3 s1 = uToRGB.eval(p1 * uRgbScale).rgb;
                half3 s2 = uToRGB.eval(p2 * uRgbScale).rgb;
                ct = half4(half3(s0.r, s1.g, s2.b) * t0.a, t0.a);
            } else if (uStyle > 0.5 && abs(ruv.x - uv.x) > 0.0005) {
                float2 dxy = (ruv - uv) * disp;
                float2 p0 = ruv * uViewSize * uScale;
                float2 p1 = (ruv + dxy * 0.2) * uViewSize * uScale;
                float2 p2 = (ruv + dxy * 0.4) * uViewSize * uScale;
                // 同细线：alpha 取主体、RGB 取共享原图。
                half4 f0 = uFrom.eval(p0);
                half4 f1 = uFrom.eval(p1);
                half4 f2 = uFrom.eval(p2);
                half3 c0 = uFromRGB.eval(p0 * uRgbScale).rgb;
                half3 c1 = uFromRGB.eval(p1 * uRgbScale).rgb;
                half3 c2 = uFromRGB.eval(p2 * uRgbScale).rgb;
                cf = half4(
                    half3(
                        c0.r * 0.9 + c1.r * 0.08 + c2.r * 0.02,
                        c0.g * 0.1 + c1.g * 0.8  + c2.g * 0.1,
                        c0.b * 0.02 + c1.b * 0.08 + c2.b * 0.9
                    ) * f0.a,
                    f0.a
                );
                half4 t0 = uTo.eval(p0);
                half4 t1 = uTo.eval(p1);
                half4 t2 = uTo.eval(p2);
                half3 d0 = uToRGB.eval(p0 * uRgbScale).rgb;
                half3 d1 = uToRGB.eval(p1 * uRgbScale).rgb;
                half3 d2 = uToRGB.eval(p2 * uRgbScale).rgb;
                ct = half4(
                    half3(
                        d0.r * 0.9 + d1.r * 0.08 + d2.r * 0.02,
                        d0.g * 0.1 + d1.g * 0.8  + d2.g * 0.1,
                        d0.b * 0.02 + d1.b * 0.08 + d2.b * 0.9
                    ) * t0.a,
                    t0.a
                );
            } else {
                float2 p = ruv * uViewSize * uScale;
                // ⚠️ uFrom/uTo 是 **ALPHA_8**（只有 alpha），RGB 一律来自共享原图，
                // 否则这一支会输出纯黑。2026-11-26。
                half a0 = uFrom.eval(p).a;
                half a1 = uTo.eval(p).a;
                cf = half4(uFromRGB.eval(p * uRgbScale).rgb * a0, a0);
                ct = half4(uToRGB.eval(p * uRgbScale).rgb * a1, a1);
            }
            // ⚠️ 顺序必须与背景层 [RasterGlCore.FRAG] 的 `mix(to, from, mixFactor)` **同构**：
            // factor/a 小时显示的是**新图**。2026-11-06 真机「主体和图顺序错位（差一张）」
            // 就是因为这里写成了 mix(cf, ct, a)（a 小时给旧图）—— 方向修好后彻底露出来。
            half4 c = mix(ct, cf, half(a));

            if (uStyle > 0.5 && pMask > 0.001) {
                if (uStyle < 1.5) {
                    c.rgb -= half3(h0 * pMask * uShadowStrength);
                } else if (uStyle > 2.5) {
                    float diffuse = max(dot(normal, normalize(uLightDir)), 0.0);
                    c.rgb *= half(1.0 - (1.0 - diffuse) * uShadowStrength * pMask);
                }
            }
            // AOD 压暗：与 GL 同一个值（那边乘在片元里）；只乘 RGB，压暗 ≠ 变透明。
            return half4(c.rgb * half(uDim), c.a);
        }
    """

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var view: SubjectView? = null

    /** 已按哪个组 id 插进树里（**不等位图**：位图慢一步，插树要立刻幂等）。 */
    @Volatile private var installedKey: String = ""

    /** 被我们摘掉的系统景深主体（关闭时要按原位插回）。 */
    private var systemSubject: View? = null
    private var systemSubjectParent: ViewGroup? = null
    private var systemSubjectIndex = 0
    private var systemSubjectVisibility = View.VISIBLE

    /** 位图加载代号：换组 / 卸载时 +1，过期的后台结果直接丢弃。 */
    private val loadToken = AtomicInteger(0)

    private var fgLayerId = 0
    private var deductedId = 0

    /** 是否已排过一次 invalidate（合并同一帧内的多次场景更新）。只在主线程读写。 */
    private var invalidatePending = false

    /** 锁屏态主体该用的缩放（跟 leash 的 1.05 预放大，日 1.0）。 */
    @Volatile private var leashScale = 1f
    private const val AOD_FADE_MS = 300L
    private const val UNLOCK_FADE_MS = 40L
    @Volatile private var unlockFadeStarted = false

    /** 当前擦除场景（由 [RasterRenderer.drawScene] 每帧推过来）。 */
    @Volatile private var sceneFrom = 0
    @Volatile private var sceneTo = 0
    @Volatile private var sceneT = 0f
    @Volatile private var sceneDir = 1f

    /** 当前「过渡效果」档位（0..3）—— 主体层跟着背景做同一档玻璃（2026-11-06）。 */
    @Volatile private var sceneStyle = 0

    private fun resolveIds(ctx: Context) {
        if (fgLayerId != 0 && deductedId != 0) return
        runCatching {
            fgLayerId = ctx.resources.getIdentifier(ID_FOREGROUND_LAYER, "id", SYSTEMUI_PKG)
            deductedId = ctx.resources.getIdentifier(ID_DEDUCTED, "id", SYSTEMUI_PKG)
            HookRuntime.log("RASTER 景深 id 解析 ⇒ 前景层=$fgLayerId，系统主体=$deductedId")
        }
    }

    /** 是否该接管前景层：**渲染器确实按这个配置起来了**（不是属性本身，语义同空间壁纸）。 */
    fun active(): Boolean = RasterRenderer.lockDepthOn()

    // ───────────────── 场景同步（GL 线程 → 主线程）─────────────────

    /**
     * 擦除场景变了（**由 `RasterRenderer.drawScene` 在 GL 线程调用**）。
     *
     * `from/to/t/dir` 与背景 GL 是**同一组值** —— 各算一遍迟早漂移，主体就会和背景错位。
     * 这里只记录 + 合并投递一次 invalidate；位图按需异步加载。
     */
    fun onSceneChanged(from: Int, to: Int, t: Float, dir: Float, style: Int) {
        if (view == null) return
        if (sceneFrom == from && sceneTo == to &&
            kotlin.math.abs(sceneT - t) < 1e-4f && sceneDir == dir && sceneStyle == style
        ) {
            return
        }
        sceneFrom = from
        sceneTo = to
        sceneT = t
        sceneDir = dir
        sceneStyle = style
        repin(from, to)
        if (invalidatePending) return
        invalidatePending = true
        mainHandler.post {
            invalidatePending = false
            runCatching {
                val v = view ?: return@runCatching
                v.ensureBitmaps(from, to)
                v.invalidate()
            }
        }
    }

    // ───────────────── 解锁退场 / AOD / 缩放（与 SpatialOverlay 逐条同构）─────────────────

    /** 解锁退场：前景主体快速淡出（幂等，重复调用不会重启动画）。 */
    fun fadeOutOnUnlock() {
        if (Looper.myLooper() == Looper.getMainLooper()) fadeOutOnMain()
        else mainHandler.post { runCatching { fadeOutOnMain() } }
    }

    private fun fadeOutOnMain() {
        val v = view ?: return
        if (unlockFadeStarted) return
        unlockFadeStarted = true
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(UNLOCK_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** 回锁屏 / 重新亮屏：把主体 alpha 复位到 1（幂等）。 */
    fun resetAlpha() {
        if (Looper.myLooper() == Looper.getMainLooper()) resetOnMain()
        else mainHandler.post { runCatching { resetOnMain() } }
    }

    private fun resetOnMain() {
        val v = view ?: return
        unlockFadeStarted = false
        v.animate().cancel()
        // 同一个坑的第三个入口（2026-11-07）：AOD 直接解锁不经 `onExitAod()`，
        // 主体可能还留着一帧 `uDim=0` 的黑录制；这里复位 alpha 前先重录一次。
        v.invalidate()
        if (v.alpha < 0.99f) {
            // 走 300ms 淡入，与系统壁纸的亮屏淡入同步；scale 一起走，避免单独跳一下。
            v.animate()
                .alpha(1f)
                .scaleX(leashScale)
                .scaleY(leashScale)
                .setDuration(AOD_FADE_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            return
        }
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    /** 由 `SpatialForegroundHider` 在每次锁屏视图更新时下发（幂等）。 */
    fun setLeashScale(scale: Float) {
        if (kotlin.math.abs(scale - leashScale) < 0.001f) return
        leashScale = scale
        if (Looper.myLooper() == Looper.getMainLooper()) applyLeashScaleOnMain()
        else mainHandler.post { runCatching { applyLeashScaleOnMain() } }
    }

    private fun applyLeashScaleOnMain() {
        val v = view ?: return
        if (unlockFadeStarted) return
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    /** 进入 AOD：**只淡出，不动 scale**（理由同空间壁纸：系统已经把祖先缩了 5%）。 */
    fun onEnterAod() {
        if (Looper.myLooper() == Looper.getMainLooper()) enterAodOnMain()
        else mainHandler.post { runCatching { enterAodOnMain() } }
    }

    private fun enterAodOnMain() {
        val v = view ?: return
        unlockFadeStarted = true      // 与解锁淡出互斥：别让 40ms 那条把节奏抢走
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(AOD_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // **必须重录**（2026-11-07）：`uDim` 是在 `onDraw` 里写给 shader 的，
        // 而 `RasterRenderer` 已经在调本方法之前把 dim 改成 AOD 目标值。
        // 不 invalidate 就可能把「压暗前」的帧留到下一次显示（退出时正好反过来 ⇒ 主体全黑）。
        v.invalidate()
    }

    /** 退出 AOD（回锁屏）：alpha 淡回 1。 */
    fun onExitAod() {
        if (Looper.myLooper() == Looper.getMainLooper()) exitAodOnMain()
        else mainHandler.post { runCatching { exitAodOnMain() } }
    }

    private fun exitAodOnMain() {
        val v = view ?: return
        unlockFadeStarted = false
        v.animate().cancel()
        v.animate()
            .scaleX(leashScale)
            .scaleY(leashScale)
            .alpha(1f)
            .setDuration(AOD_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // **必须重录**（2026-11-07 根因）：`uDim` 只在 `onDraw` 里写，而 `onSceneChanged`
        // 的去重键不含 dim —— AOD 期间 `resetNow` 把场景抹回 #0→#0 后录了一帧 `uDim=0`，
        // 退出时场景没变 ⇒ 那次调用直接早退、不 invalidate ⇒ 黑帧被 alpha=1 原样显示出来。
        // 真机日志：55.097 退出、直到 58.028 用户转头改了场景才恢复。
        v.invalidate()
    }

    // ───────────────── 装配 / 拆卸 ─────────────────

    /**
     * 幂等装配：把我们的主体 View 挂进 `keyguard_foreground_layer` 的 index 0。
     *
     * **由 `SpatialForegroundHider` 在 `updateKeyguardElementsVisibility` 之后调用** ——
     * 系统每次算显隐都会走到那里，所以锁屏视图重建 / SystemUI 重启后能自己补回来。
     *
     * 与空间壁纸**互斥**：`keyguard_foreground_layer` 只有一个，谁在跑谁占。
     */
    fun ensure(host: ViewGroup) {
        if (!active()) return
        val key = RasterRenderer.currentKey() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) attach(host, key)
        else mainHandler.post { runCatching { attach(host, key) } }
    }

    /**
     * 把系统那个景深主体**从父容器摘掉**（理由见 `SpatialOverlay.detachSystemSubject`：
     * 设 INVISIBLE 会被系统的 folme 动画在下一帧翻回来）。
     */
    fun detachSystemSubject(host: ViewGroup): Boolean {
        val held = systemSubject
        if (held != null) {
            if (systemSubjectParent === host) return true
            systemSubject = null
            systemSubjectParent = null
        }
        val ctx = host.context
        resolveIds(ctx)
        if (deductedId == 0) return false
        val v = host.rootView.findViewById<View>(deductedId) ?: return false
        val parent = v.parent as? ViewGroup ?: return false
        val idx = parent.indexOfChild(v)
        systemSubjectVisibility = v.visibility
        runCatching { parent.removeView(v) }.onFailure {
            HookRuntime.log("RASTER 景深 摘系统主体失败：${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        systemSubject = v
        systemSubjectParent = parent
        systemSubjectIndex = idx
        HookRuntime.log("RASTER 景深 已摘掉系统主体（index=$idx，visibility=$systemSubjectVisibility）")
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
                v.visibility = systemSubjectVisibility
            }
        }.onFailure {
            HookRuntime.log("RASTER 景深 插回系统主体失败：${it.javaClass.simpleName}: ${it.message}")
        }
        HookRuntime.log("RASTER 景深 系统主体已插回（index=$systemSubjectIndex）")
    }

    private fun attach(host: ViewGroup, key: String) {
        val cur = view
        if (cur != null && cur.parent === host && installedKey == key) {
            // 让路时只设了 GONE（位图保留），这里原样抬回来即可（v1.22.108）
            if (cur.visibility != View.VISIBLE) cur.visibility = View.VISIBLE
            return
        }
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
            HookRuntime.log("RASTER 景深 插入前景层失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        view = v
        installedKey = key
        v.groupKey = key
        unlockFadeStarted = false
        v.scaleX = leashScale
        v.scaleY = leashScale
        HookRuntime.log("RASTER 景深 前景主体已插入（key=$key，host=${host.javaClass.simpleName}）")
        // 出生就按当前场景预载一次（否则第一帧是空的，等下一次擦除才有图）。
        v.ensureBitmaps(sceneFrom, sceneTo)
    }

    private fun detach() {
        val v = view ?: return
        view = null
        installedKey = ""
        loadToken.incrementAndGet()
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        v.releaseBitmaps()
    }

    /** 清场：移掉我们的 View + **把系统主体插回去**（幂等）。 */
    /**
     * 锁屏状态变了（2026-11-19）：重绘一帧，让 [SubjectView.onDraw] 的闸门真正生效
     * （`onDraw` 只在需要重绘时才跑，不 invalidate 的话上一帧会留在 RenderNode 里被通知中心捕走）。
     */
    fun onContentGateChanged() {
        val v = view ?: return
        mainHandler.post { runCatching { v.invalidate() } }
    }

    /** 自检用（2026-11-22）：插进前景层的那个 View（没建 / 已 detach 就是 null）。 */
    internal fun attachedView(): View? = view?.takeIf { it.isAttachedToWindow }

    fun onDisabled() = onDisabled(keepBitmaps = false)

    /**
     * @param keepBitmaps **MC 让路专用**（v1.22.108）：只把 View 设 `GONE`、**不移除也不释放位图**。
     *
     * 从前让路走的是 `detach()`，里面 `releaseBitmaps()` 会把已解码的主体位图扔掉；
     * 退出封面重新 `attach()` 时 `ensureBitmaps()` 又要读盘 + decode 一张 1200×2670
     * ⇒ 壁纸先回来、人像慢半拍（用户 2026-11-15：「光栅恢复比空间 / Alive 要慢一点」）。
     * 让路是高频短时事件，留着一张位图（≈几 MB）比每次重解码划算。
     */
    fun onDisabled(keepBitmaps: Boolean) {
        if (view == null && installedKey.isEmpty() && systemSubject == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) disabledOnMain(keepBitmaps)
        else mainHandler.post { runCatching { disabledOnMain(keepBitmaps) } }
    }

    private fun disabledOnMain(keepBitmaps: Boolean) {
        if (keepBitmaps) {
            // 只藏起来：View / installedKey / 位图全部保留，恢复时 attach() 会原样抬回来
            view?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        } else {
            runCatching { detach() }
        }
        runCatching { restoreSystemSubject() }
    }

    /**
     * 主体 View：全屏、透明底、内容全由 AGSL 算。
     *
     * 3 张位图缓存（上一张 / 当前 / 下一张）：擦除只在两两之间发生，来回转也不用重解码。
     */
    private class SubjectView(context: Context) : View(context) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader: RuntimeShader? = null
        private var shaderFailed = false

        /** 组 id —— 换组时旧位图必须整体丢弃。 */
        var groupKey: String = ""

        private val bitmaps = LinkedHashMap<Int, Bitmap>()
        private val shaders = HashMap<Int, BitmapShader>()

        /**
         * 色散 RGB 源的 shader 缓存 —— 位图本身**不是我们的**：直接引用
         * [RasterRenderer.sharedBitmap] 那份（与 GL 纹理同源，全分辨率）。
         * 用 [sharedRefs] 记身份，池淘汰后换过对象就重建。
         */
        private val sharedShaders = HashMap<Int, BitmapShader>()
        private val sharedRefs = HashMap<Int, Bitmap>()

        /** 已确认"这张没有主体"的序号，避免反复请求 provider。 */
        private val missing = HashSet<Int>()

        /** 正在后台加载的序号。 */
        private val loading = HashSet<Int>()

        /** 1×1 全透明位图 —— 没算过主体的帧用它顶替，`mix` 自然淡入淡出。 */
        private var emptyShader: BitmapShader? = null

        private var lastLoggedFrom = -1
        private var lastLoggedTo = -1

        /** pivot 必须对齐系统壁纸 leash（与 `SpatialOverlay.SubjectView` 同一条）。 */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w * 0.5f
            pivotY = h * 0.4f
        }

        private fun empty(): BitmapShader {
            emptyShader?.let { return it }
            val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                emptyShader = it
            }
        }

        private fun shaderOf(index: Int): BitmapShader = shaders[index] ?: empty()

        /** 共享原图的 shader；返回 null = 背景层还没解好，这一帧先不画。 */
        private fun rgbShaderOf(index: Int): BitmapShader? {
            val bmp = RasterRenderer.sharedBitmap(index) ?: return null
            val cached = sharedShaders[index]
            if (cached != null && sharedRefs[index] === bmp) return cached
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sharedShaders[index] = sh
            sharedRefs[index] = bmp
            return sh
        }

        /** 确保 from / to 两张主体在位图缓存里；缺的异步加载。主线程调用。 */
        fun ensureBitmaps(from: Int, to: Int) {
            if (groupKey.isEmpty()) return
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            for (idx in intArrayOf(from, to)) {
                if (idx < 0) continue

                // 主体抠像 → 只留 alpha（RGB 由背景层那份共享位图提供）
                if (!shaders.containsKey(idx) && idx !in missing && idx !in loading) {
                    loading.add(idx)
                    val token = loadTokenSnapshot()
                    val ctx = context.applicationContext
                    Thread {
                        val bmp = loadSubjectAlpha(ctx, groupKey, idx, w, h)
                        mainHandler.post {
                            loading.remove(idx)
                            if (token != loadTokenSnapshot() || this !== view) {
                                bmp?.recycle()
                                return@post
                            }
                            if (bmp == null) {
                                missing.add(idx)
                            } else {
                                bitmaps[idx] = bmp
                                shaders[idx] = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                                evictIfNeeded()
                            }
                            invalidate()
                        }
                    }.apply { isDaemon = true }.start()
                }

            }
        }

        /** alpha 留 3 张（3.2MB/张）；超出优先丢**不在当前场景里**的最旧项。 */
        private fun evictIfNeeded() {
            val keep = hashSetOf(sceneFrom, sceneTo)
            // ⚠️ 不能用 `keys.iterator().remove()` 再 `remove()` 一次：第二次拿到 null，
            // 位图就漏了 recycle（2026-11-04 写的时候差点踩到）。
            if (bitmaps.size > 3) {
                val victims = bitmaps.keys.filter { it !in keep }.take(bitmaps.size - 3)
                for (k in victims) {
                    shaders.remove(k)
                    bitmaps.remove(k)?.recycle()
                }
            }
        }

        fun releaseBitmaps() {
            bitmaps.values.forEach { runCatching { it.recycle() } }
            bitmaps.clear()
            shaders.clear()
            // ⚠️ 共享位图**不归我们回收** —— 那是背景层的池（`RasterRenderer.preloaded`），
            // GL 纹理还在用。这里只丢 shader 引用，并把 pin 还回去。
            pinnedFrom?.let { RasterRenderer.unpinBitmap(it) }
            pinnedTo?.let { RasterRenderer.unpinBitmap(it) }
            pinnedFrom = null
            pinnedTo = null
            sharedShaders.clear()
            sharedRefs.clear()
            missing.clear()
            loading.clear()
            emptyShader = null
            groupKey = ""
        }

        override fun onDraw(canvas: Canvas) {
            // 内容闸门（2026-11-19）：解锁后通知中心的"包含下方"捕获**不尊重可见性**，
            // 主体留在 RenderNode 里就会被连同背景一起取走（观感 = 通知中心出现主体层）。
            // 非锁屏态一律不画，让这一帧的 RenderNode 是空的。见 [KeyguardContentGate]。
            if (!KeyguardContentGate.allowDraw(this)) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // 首次布局完成后才拿得到尺寸（`attach` 里 width 还是 0）⇒ 在这里兜底触发一次加载，
            // 否则静止不动时主体永远是空的（场景没变 ⇒ 没人再调 ensureBitmaps）。
            ensureBitmaps(sceneFrom, sceneTo)
            // 一张都没有也画：empty 会让整层透明，等于没开（用户 2026-11-04 明确）。
            val fromS = shaderOf(sceneFrom)
            val toS = shaderOf(sceneTo)
            // 共享 RGB 源（背景层的全分辨率位图）。没解好就**整帧不画** —— 宁可透明，
            // 也不要画错（主体会变成纯黑/纯色块）。
            val fromRgb = rgbShaderOf(sceneFrom) ?: return
            val toRgb = rgbShaderOf(sceneTo) ?: return

            val s = shader ?: run {
                if (shaderFailed) return
                runCatching { RuntimeShader(AGSL) }
                    .onFailure {
                        shaderFailed = true
                        HookRuntime.log("RASTER 景深 AGSL 编译失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrNull()
                    ?.also { shader = it }
                    ?: return
            }

            runCatching {
                s.setInputShader("uFrom", fromS)
                s.setInputShader("uTo", toS)
                // 色散的 RGB 源（1/2 原图，不透明）—— 缺失时由 uRgbScale=0 触发降级。
                s.setInputShader("uFromRGB", fromRgb)
                s.setInputShader("uToRGB", toRgb)
                s.setFloatUniform("uViewSize", w, h)
                // 位图已按屏幕 cover 裁好 ⇒ 正常是 1:1；留这个比值是防止 View 尺寸被系统改。
                val bw = bitmaps[sceneFrom]?.width ?: bitmaps[sceneTo]?.width ?: 0
                val bh = bitmaps[sceneFrom]?.height ?: bitmaps[sceneTo]?.height ?: 0
                if (bw > 0 && bh > 0) {
                    s.setFloatUniform("uScale", bw / w, bh / h)
                } else {
                    s.setFloatUniform("uScale", 1f, 1f)
                }
                // 共享位图是「屏幕尺寸 + cover 已裁好」⇒ 与 View 同尺寸，正常就是 1。
                // 留这个比值是防 View 被系统改尺寸。
                val rb = RasterRenderer.sharedBitmap(sceneFrom)
                    ?: RasterRenderer.sharedBitmap(sceneTo)
                if (rb != null) {
                    s.setFloatUniform("uRgbScale", rb.width / w, rb.height / h)
                } else {
                    s.setFloatUniform("uRgbScale", 1f, 1f)
                }
                s.setFloatUniform("uT", sceneT)
                s.setFloatUniform("uDir", sceneDir)
                // 2026-11-06：与背景层 [RasterGlCore.FRAG] 逐字一致 —— 擦除带 + 五档玻璃。
                s.setFloatUniform("uBand", RasterGlCore.VIVO_ALPHA_TRANS_WIDTH)
                s.setFloatUniform("uTransWidth", RasterGlCore.VIVO_TRANS_WIDTH)
                s.setFloatUniform("uMaskTransWidth", RasterGlCore.VIVO_MASK_TRANS_WIDTH)
                val sp = RasterGlCore.paramsOf(sceneStyle)
                s.setFloatUniform("uStyle", sceneStyle.toFloat())
                s.setFloatUniform("uBumpScale", sp.bump)
                s.setFloatUniform("uRefractionScale", sp.refract)
                s.setFloatUniform("uDispersionFactor", sp.dispersion)
                s.setFloatUniform("uShadowStrength", sp.shadow)
                s.setFloatUniform(
                    "uStripePeriodPx",
                    RasterGlCore.periodPxOf(sceneStyle, w).coerceAtLeast(1f),
                )
                s.setFloatUniform(
                    "uFrostCellPx",
                    (w * RasterGlCore.FROST_CELL_PX_1200 / RasterGlCore.STRIPE_BASE_W)
                        .coerceAtLeast(0.5f),
                )
                s.setFloatUniform("uScreenW", w)
                s.setFloatUniform("uScreenH", h)
                s.setFloatUniform(
                    "uLightDir",
                    sp.lightX, RasterGlCore.TWIST_LIGHT_Y, RasterGlCore.TWIST_LIGHT_Z,
                )
                s.setFloatUniform("uDim", RasterRenderer.aodDimValue())
                paint.shader = s
                canvas.drawRect(0f, 0f, w, h, paint)

                if (lastLoggedFrom != sceneFrom || lastLoggedTo != sceneTo) {
                    lastLoggedFrom = sceneFrom
                    lastLoggedTo = sceneTo
                    HookRuntime.log(
                        "RASTER 景深 场景 #$sceneFrom→#$sceneTo t=${"%.3f".format(sceneT)} " +
                            "dir=${if (sceneDir > 0) "+" else "-"} " +
                            "有主体=${shaders.keys.sorted()}",
                    )
                }
            }.onFailure {
                if (!shaderFailed) {
                    shaderFailed = true
                    HookRuntime.log("RASTER 景深 AGSL 绘制失败：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }

    /** 当前加载代号（切换组 / 卸载时递增，过期结果丢弃）。 */
    private fun loadTokenSnapshot(): Int = loadToken.get()

    /**
     * 景深层正在采样的两张序号 —— 告诉背景层的池「别把这两张回收了」。
     *
     * 共享位图是**别人的**（`RasterRenderer.preloaded`），池在预取时按 LRU 淘汰；
     * 如果不 pin，`BitmapShader` 正指着的位图会被 `recycle()`，当帧直接空白。
     */
    private var pinnedFrom: Int? = null
    private var pinnedTo: Int? = null

    private fun repin(from: Int, to: Int) {
        if (pinnedFrom != from) {
            pinnedFrom?.let { RasterRenderer.unpinBitmap(it) }
            RasterRenderer.pinBitmap(from)
            pinnedFrom = from
        }
        if (pinnedTo != to) {
            pinnedTo?.let { RasterRenderer.unpinBitmap(it) }
            RasterRenderer.pinBitmap(to)
            pinnedTo = to
        }
    }

    /**
     * 读 `raster/<组id>/subject/<序号>`，**只提取 alpha**（`ALPHA_8`，3.2MB）。
     *
     * 生成端继续存 RGBA（存储不差这点，也免了迁移旧数据）；内存侧只要 alpha ——
     * 色散的 RGB 全部由背景层那份共享位图提供（[RasterRenderer.sharedBitmap]），
     * 因为两者内容本来就一模一样。提取完立刻把 RGBA 副本回收，峰值只多 16MB 一瞬。
     *
     * 读不到（没算过主体）= 返回 null，上层按"透明"处理。
     */
    private fun loadSubjectAlpha(ctx: Context, key: String, index: Int, tw: Int, th: Int): Bitmap? {
        val rgba = loadCovered(
            ctx,
            Uri.parse("content://$AUTHORITY/raster/$key/subject/$index"),
            tw,
            th,
        ) ?: return null
        return runCatching {
            val a = rgba.extractAlpha()
            rgba.recycle()
            a
        }.getOrElse {
            runCatching { rgba.recycle() }
            null
        }
    }

    /**
     * 通用：解码后**按与背景相同的 cover 裁到 tw×th**。
     *
     * 背景 GL 用 `RasterRenderer.loadBitmap` 做同一件事；主体图与原图同尺寸（用户
     * 2026-11-04 确认），所以两边算出来的 cover 矩形完全一致 ⇒ 主体与背景严丝合缝。
     */
    private fun loadCovered(ctx: Context, uri: Uri, tw: Int, th: Int): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sw = bounds.outWidth
            val sh = bounds.outHeight
            if (sw <= 0 || sh <= 0) return null

            // 先让解码器丢一轮像素（与背景同一套：源图比目标大很多时一次大比例双线性会糊）。
            var sample = 1
            while (sw / (sample * 2) >= tw && sh / (sample * 2) >= th) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            if (src.width == tw && src.height == th) return src

            val sAsp = tw.toFloat() / th
            val dAsp = src.width.toFloat() / src.height
            val cw: Int
            val ch: Int
            if (dAsp > sAsp) {
                ch = src.height
                cw = (src.height * sAsp).roundToInt().coerceIn(1, src.width)
            } else {
                cw = src.width
                ch = (src.width / sAsp).roundToInt().coerceIn(1, src.height)
            }
            val cx = (src.width - cw) / 2
            val cy = (src.height - ch) / 2
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            // 画到透明底上：主体的 alpha 必须原样保留（不能用不透明底）。
            Canvas(out).drawBitmap(
                src,
                android.graphics.Rect(cx, cy, cx + cw, cy + ch),
                android.graphics.Rect(0, 0, tw, th),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            src.recycle()
            out
        }.getOrNull()
}
