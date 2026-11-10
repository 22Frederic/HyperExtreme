package com.frederic22.hyperextreme.hook

/**
 * 光栅壁纸的**渲染常量与着色器**（2026-11-04，2026-11-06 按 vivo 原版重写）。
 *
 * 与空间的 [SpatialGlCore] 分开：那边是「两层纹理 + 双通道位移场 + 逐像素 k」，
 * 这边是「两张全屏图 + 一条擦除带」，公式与 uniform 完全不重叠，硬塞进一份只会互相拖累。
 *
 * ## 2026-11-06：对齐 vivo 原版（`engine.apk` 的 `assets/raster/d1~d4.glsl`）
 *
 * 旧版是「线性 alpha + refract() 双重折射」，与 vivo 差得远。现在整段照 vivo 的公式重写：
 *
 * ```
 * pa        = P × (1 + 2·TransWidth) − TransWidth        // 擦除边界
 * pam       = 同值（多图模型里单次擦除的 frac 就是 0..1，不需要 vivo 的反向镜像）
 * pMask     = smoothstep(pam − MaskTransWidth, pam, e) × smoothstep(pam + MaskTransWidth, pam, e)
 * mixFactor = smoothstep(pa − AlphaTransWidth, pa + AlphaTransWidth, e)
 * dxy       = normal.xy × RefractionScale × pMask
 * 色散       = (refractedUV − uv) × (DispersionFactor × 0.16 × pMask × 208.333)
 * 阴影       = heightRGB × pMask × ShadowStrength   （d1：从色里减）
 * 光照       = 1 − (1 − dot(n, L)) × ShadowStrength × pMask （d4：乘）
 * ```
 *
 * **高度场全部程序化**，不带 vivo 的三张 png（AGPL 仓库不放厂商资源）：
 * * d1 条纹：`0.5 + 0.5·cos(2πx)`（15×12 图实测就是一条单半圆柱）
 * * d3 磨砂：2D value-noise（256×256 图实测是纯随机噪点）
 * * d4 扭曲：`0.5 + 0.46·cos(2πx) + 10% 噪声`（108 点波形谐波分解：k=1 占 0.231，
 *   k=2..8 全部只有 0.017~0.024）
 *
 * ## 四档与 vivo 的参数（`DrawNConfigure.<init>` 里的 const，已逐条核对）
 *
 * | 参数 | d1 条纹 | d3 磨砂 | d4 扭曲 |
 * |---|---|---|---|
 * | bumpScale | 3.0 | 3.0 | **10.0** |
 * | refractionScale | 0.04 | 0.045 | **0.25** |
 * | dispersionFactor | 0.03 | 0.03 | 0.01 |
 * | shadowStrengthFactor | 0.03 | 0 | **0.5** |
 * | lightDir | (0,0,1) | (0,0,1) | **(0.3,0,1)** |
 *
 * 公共项：`transWidth = 0.8`、`alphaTransWidth = 0.4`、`maskTransWidth = 0.8`。
 */
internal object RasterGlCore {

    /**
     * 默认满量程角位移 = **25°**（用户 2026-11-04 定；最初定的 15° 太容易扫到底）。
     *
     * ⚠️ 2026-11-04 起它只是**默认值**：真正生效的是 `MaskPrefs.rasterMaxAngle`
     * （15~45°，UI 可调），渲染器启动时换算成弧度存进 `panMaxRad`。
     * 这条常量留给"读不到属性时"兜底，以及作为两处默认值的唯一来源。
     */
    const val PAN_MAX_RAD = 0.4363323f

    /** 度数 → 弧度（运行时口径；`PAN_MAX_RAD` 就是 `radOf(25)`）。 */
    fun radOf(deg: Int): Float = Math.toRadians(deg.toDouble()).toFloat()

    /**
     * 陀螺一阶低通的时间常数（秒）—— 与空间壁纸 `SpatialGlCore.GYRO_TAU_MS` 同一个量级。
     */
    const val GYRO_TAU_S = 0.05f

    /**
     * **静止门限**（rad/s）：低通后的角速度低于它**一滴都不积分**。
     *
     * 这是"不归位"能成立的前提：没有绝对基准，只要静止时还在累加，噪声就会让画面
     * 一格格往前爬。参考项目 `tianyinwallpaper` 用的也是这个值（0.01）。
     */
    const val GYRO_GATE = 0.01f

    /** 停在半擦状态时，多久后自动补完这一擦（毫秒）。 */
    const val SNAP_DELAY_MS = 80L

    /** 补完这一擦的时长（毫秒）。 */
    const val SNAP_MS = 200L

    /** 从 AOD / 解锁恢复时「擦回第一张」的时长（毫秒）。 */
    const val HOME_WIPE_MS = 350L

    /** 等待"恢复后第一个运动方向"的上限（毫秒）；超时就用最后一次的运动方向。 */
    const val HOME_WAIT_MS = 1500L

    /** 重绘死区（以"图号"为单位）：小于它不重绘。 */
    const val DRAW_EPS = 1e-4f

    // ───────────────────── 「过渡效果」四档（2026-11-06）─────────────────────

    /** 0 = 默认（纯擦除，对应 vivo 的 d2）；1 = 条纹玻璃（d1）；2 = 磨砂玻璃（d3）；3 = 条纹玻璃·扭曲（d4）。 */
    const val STYLE_STRIPE = 1
    const val STYLE_FROST = 2
    const val STYLE_TWIST = 3

    // vivo 公共项（DrawNConfigure 构造函数）
    const val VIVO_TRANS_WIDTH = 0.8f
    const val VIVO_ALPHA_TRANS_WIDTH = 0.4f
    const val VIVO_MASK_TRANS_WIDTH = 0.8f

    // d1 条纹
    const val STRIPE_BUMP = 3.0f
    const val STRIPE_REFRACT = 0.04f
    const val STRIPE_DISP = 0.03f
    const val STRIPE_SHADOW = 0.03f

    // d3 磨砂（无阴影；色散因子仍有值，会被公共分支读走 —— 见《02》§3.14.7 的待确认项）
    const val FROST_BUMP = 3.0f
    const val FROST_REFRACT = 0.045f
    const val FROST_DISP = 0.03f
    const val FROST_SHADOW = 0.0f

    // d4 扭曲条纹（强折射 + 强阴影 + 漫反射）
    const val TWIST_BUMP = 10.0f
    const val TWIST_REFRACT = 0.25f
    const val TWIST_DISP = 0.01f
    const val TWIST_SHADOW = 0.5f

    /** d4 的 lightDir = (0.3, 0, 1)（dex `Draw4Configure.<init>` 的 float[3] 实测）。 */
    const val TWIST_LIGHT_X = 0.3f
    const val TWIST_LIGHT_Y = 0.0f
    const val TWIST_LIGHT_Z = 1.0f

    /**
     * 参数基准屏宽（像素）。周期 / 磨砂颗粒都按它等比，AOD 的 5% leash 缩放不会变形。
     */
    const val STRIPE_BASE_W = 1200f

    /** 条纹周期：1200 宽屏上 19 px（862 宽截图实测 14 px，换算到 1200）。 */
    const val STRIPE_PERIOD_PX_1200 = 19f

    /** 磨砂颗粒：1200 宽屏上一格约 2 px（d3 的 256×256 图平铺后约 0.5~1 px，取 2 更稳）。 */
    const val FROST_CELL_PX_1200 = 2.0f

    /** 四档的着色参数（vivo `DrawNConfigure` 构造函数里的 const）。 */
    class StyleParams(
        val bump: Float,
        val refract: Float,
        val dispersion: Float,
        val shadow: Float,
        val lightX: Float,
    )

    /** 按档位取参数 —— **两条挂载链共用**（hook 链与服务链必须逐字一致）。 */
    fun paramsOf(style: Int): StyleParams = when (style) {
        STYLE_STRIPE -> StyleParams(STRIPE_BUMP, STRIPE_REFRACT, STRIPE_DISP, STRIPE_SHADOW, 0f)
        STYLE_FROST -> StyleParams(FROST_BUMP, FROST_REFRACT, FROST_DISP, FROST_SHADOW, 0f)
        STYLE_TWIST -> StyleParams(TWIST_BUMP, TWIST_REFRACT, TWIST_DISP, TWIST_SHADOW, TWIST_LIGHT_X)
        else -> StyleParams(0f, 0f, 0f, 0f, 0f)
    }

    const val VERT = """
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * 片元着色器（**无字符串插值，可以是 const val**）。
     *
     * 所有 vivo 参数都从 uniform 进来（一个 shader 服务四档），
     * 公式里的 `0.16` / `208.333` 是 vivo 原版的常数，不是可调项。
     */
    const val FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uFrom;
uniform sampler2D uTo;
// xy = uv 缩放，zw = uv 平移。用来做 **cover**：按比例铺满屏幕、多出来的居中裁掉。
// from / to 两张图比例可能不同，所以各带一套。
uniform vec4 uFromUv;
uniform vec4 uToUv;
uniform float uT;
uniform float uDir;
uniform float uDim;
// ───────── vivo 四档参数（2026-11-06）─────────
uniform float uStyle;              // 0 默认 / 1 条纹 / 2 磨砂 / 3 扭曲
uniform float uTransWidth;
uniform float uAlphaTransWidth;
uniform float uMaskTransWidth;
uniform float uBumpScale;
uniform float uRefractionScale;
uniform float uDispersionFactor;
uniform float uShadowStrength;
uniform float uStripePeriodPx;
uniform float uFrostCellPx;
uniform float uScreenW;
uniform float uScreenH;
uniform vec3  uLightDir;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 程序化高度场：替代 vivo 的 d1_15x15 / d3_256x256 / d4_15x15 三张 png。
float rasterHeight(vec2 uv) {
    float x = uv.x * uScreenW / max(uStripePeriodPx, 1.0);
    if (uStyle < 1.5) {
        // d1：15x12 图实测是一条单半圆柱
        return 0.5 + 0.5 * cos(6.2831853 * x);
    } else if (uStyle < 2.5) {
        // d3：256x256 图实测是纯随机噪点
        vec2 p = vec2(uv.x * uScreenW, uv.y * uScreenH) / max(uFrostCellPx, 0.5);
        return noise2(p);
    }
    // d4：108 点波形 ≈ 余弦 + 10% 扰动（中间那个小鼓包由噪声项带出来）
    float n = noise2(vec2(x * 5.0, 7.3));
    return clamp(0.5 + 0.46 * cos(6.2831853 * x) + 0.10 * (n - 0.5), 0.0, 1.0);
}

// vivo 的 Tx3 色散：三采样错开 0 / 0.2 / 0.4 倍，RGB 各取一套权重。
vec4 sampleDisperse(sampler2D tex, vec4 uvRect, vec2 uv0, float s) {
    vec2 dxy = (uv0 - vUv) * s;
    if (abs(dxy.x) > 0.001) {
        vec4 c0 = texture2D(tex, (uv0 + dxy * 0.0) * uvRect.xy + uvRect.zw);
        vec4 c1 = texture2D(tex, (uv0 + dxy * 0.2) * uvRect.xy + uvRect.zw);
        vec4 c2 = texture2D(tex, (uv0 + dxy * 0.4) * uvRect.xy + uvRect.zw);
        return vec4(
            c0.r * 0.9 + c1.r * 0.08 + c2.r * 0.02,
            c0.g * 0.1 + c1.g * 0.8  + c2.g * 0.1,
            c0.b * 0.02 + c1.b * 0.08 + c2.b * 0.9,
            c0.a
        );
    }
    return texture2D(tex, uv0 * uvRect.xy + uvRect.zw);
}

void main() {
    vec2 uv = vUv;
    // 方向与主体层（`RasterOverlay.AGSL`）**逐字一致**：dir>0 ⇒ uv.x。
    // 2026-11-06 修正：之前写成 (1 − uv.x)，与主体层相反（真机发现背景反了、主体是对的）。
    float e = (uDir > 0.5) ? uv.x : (1.0 - uv.x);

    // vivo：pa/pam 各自独立；我们多图模型里单次擦除的 frac 就是 0..1，A/M 同值。
    float pa = uT * (1.0 + 2.0 * uTransWidth) - uTransWidth;
    float pam = pa;

    float pMask = smoothstep(pam - uMaskTransWidth, pam, e)
                * smoothstep(pam + uMaskTransWidth, pam, e);
    float mixFactor = smoothstep(pa - uAlphaTransWidth, pa + uAlphaTransWidth, e);

    vec2 ruv = uv;
    vec3 normal = vec3(0.0, 0.0, 1.0);
    float h0 = 0.0;
    float band = (uStyle > 0.5) ? pMask : 0.0;
    if (band > 0.001) {
        // 法线：差分步长 = 1 屏幕像素（与 vivo 的 1/designW 同量级）
        float px = 1.0 / max(uScreenW, 1.0);
        float l = rasterHeight(uv - vec2(px, 0.0));
        float r = rasterHeight(uv + vec2(px, 0.0));
        float dx = (r - l) * 0.5;
        float dy = 0.0;
        if (uStyle > 1.5 && uStyle < 2.5) {
            float t = rasterHeight(uv + vec2(0.0, px));
            float b = rasterHeight(uv - vec2(0.0, px));
            dy = (t - b) * 0.5 * (uScreenW / max(uScreenH, 1.0));
        }
        normal = normalize(vec3(dx * uBumpScale, dy * uBumpScale, 1.0));
        h0 = rasterHeight(uv);
        ruv = clamp(uv + normal.xy * uRefractionScale * pMask, 0.0, 1.0);
    }

    float disp = uDispersionFactor * 0.16 * pMask * 208.333;
    vec4 from;
    vec4 to;
    if (band > 0.001) {
        // 带内才走色散（2 图 × 3 次），带外仍是 2 次 ⇒ 静止不重绘时不会多出常驻开销。
        from = sampleDisperse(uFrom, uFromUv, ruv, disp);
        to   = sampleDisperse(uTo,   uToUv,   ruv, disp);
    } else {
        from = texture2D(uFrom, ruv * uFromUv.xy + uFromUv.zw);
        to   = texture2D(uTo,   ruv * uToUv.xy   + uToUv.zw);
    }
    vec3 c = mix(to.rgb, from.rgb, mixFactor);

    if (band > 0.001) {
        if (uStyle < 1.5) {
            // d1：阴影直接从色里减（vivo 的 shadowColor 是高度图 RGB）
            c -= vec3(h0) * pMask * uShadowStrength;
        } else if (uStyle > 2.5) {
            // d4：漫反射乘性光照
            float diffuse = max(dot(normal, normalize(uLightDir)), 0.0);
            c *= (1.0 - (1.0 - diffuse) * uShadowStrength * pMask);
        }
    }
    // 压暗只乘 RGB（AOD 用；`Transaction#setColor` 在 HyperOS 上会发白，已封死）。
    gl_FragColor = vec4(c * uDim, 1.0);
}
"""
}
