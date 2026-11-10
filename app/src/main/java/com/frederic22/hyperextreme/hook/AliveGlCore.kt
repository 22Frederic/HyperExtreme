package com.frederic22.hyperextreme.hook

/**
 * Alive 壁纸「启航」的 GL 核心：着色器、三态参数、模糊核、转场曲线。
 *
 * ## 来源与许可
 *
 * 效果语义来自 openalive 的照片版「启航」（`photo/aod/lensPhoto` + `photo/blend/lens`），
 * 2026-11-07 逐行核对过 `renderer.rs::aod_pass` / `SceneMotion.lensState` / `SceneMatrices`。
 * **这里是自己重写的等价实现**：数学公式相同（那是算法），GLSL 文本重写；
 * 上游 `app/assets/shader/` 与 `native/shaders.rs` 不属 MIT，一个字都没有复制。
 *
 * 装饰图（球面阴影）同样是自绘：官方那张是「纯黑 + alpha 渐变」的压暗遮罩，
 * 5 阶多项式拟合，逐像素 MAE 0.9/255。见 `alive分析/decorator_compare/`。
 *
 * ## 「启航」到底是什么
 *
 * 照片先高斯模糊 → 桶形畸变 → 裁成圆 → 叠球面阴影 → 圆外纯黑。
 * AOD 态是一个自转的小球（半径 0.22 屏宽），锁屏 / 桌面态是原图直出，
 * 三者之间是「圆从小球扩张到铺满」的连续转场。
 *
 * ## 三态参数（`SceneMotion.lensState`，逐值对齐）
 *
 * | 态 | blur | scale | distortion | 装饰 α | x / y | 自转 |
 * | AOD | 22 | 0.22 | 9 | 1 | 0 / 0.335 | −40°/s |
 * | 锁屏 | 0 | expanded | 0 | 0 | 0 / 0 | 停 |
 * | 桌面 | 16 | expanded | 0 | 0 | 0 / 0 | 停 |
 *
 * `expanded` = 屏幕高宽比四舍五入到两位恰为 2.33 时取 1.3，否则 1.24。
 *
 * @author 22Frederic
 */
internal object AliveGlCore {

    // ───────────────────────────── 三态参数 ─────────────────────────────

    /** AOD：模糊半径（px，内部上限 12）。 */
    const val AOD_BLUR = 22f
    /** AOD：圆半径（单位 = 屏宽）。 */
    const val AOD_SCALE = 0.22f
    /** AOD：畸变系数（shader 里要再除以 scale ⇒ 实际 40.9）。 */
    const val AOD_DISTORTION = 9f
    /** AOD：装饰透明度。 */
    const val AOD_DECOR_ALPHA = 1f
    /** AOD：圆心 x（相对屏幕，单位 = 屏宽）。 */
    const val AOD_X = 0f

    /**
     * AOD 圆心 y **默认值**（屏幕高度 35%，用户 2026-11-08 定）。
     *
     * 换算：`center_y(GL, 从下往上) = 0.5 + ty` ⇒ 屏幕 35%（从上往下）对应
     * `ty = 0.5 − 0.35 = 0.15`。与 `AlivePreview.CENTER_Y` 是同一个视觉位置。
     *
     * ⚠️ 运行时会被 [aodCenterY] 覆盖 —— 「锁屏联动息屏」这一种样式会实测 AOD 时钟的
     * 位置并把球心对上去（见 `AodClockProbe`）；量不到就退回这个值。
     */
    const val AOD_Y = 0.15f

    /**
     * AOD 球心的 y 参数（`center_y = 0.5 + ty`，坐标系 GL 从下往上）。
     *
     * 值 = `0.5 − 时钟中心的屏幕比例`。实测「锁屏联动息屏」的 `LinkageClock`
     * 时钟块中心在屏幕 0.2665 处 ⇒ 这里应约 0.2335。
     * 由 `AodClockProbe` 在每次 AOD 布局后写入；读不到就是 [AOD_Y]。
     */
    @Volatile
    var aodCenterY: Float = AOD_Y

    /** AOD：自转速度倍率。 */
    const val AOD_SPEED = 1f

    /** 锁屏：不模糊。 */
    const val LOCK_BLUR = 0f
    /** 桌面：模糊 16（只在转场中间可见）。 */
    const val HOME_BLUR = 16f

    /** 锁屏 / 桌面共用的装饰角度与 ripple。 */
    const val EXPANDED_DECOR_ANGLE = -149f
    const val EXPANDED_RIPPLE = 1.24f

    /** 自转角速度（度/秒）。 */
    const val SPIN_DEG_PER_SEC = 40f

    /** 模糊半径的内部上限（照 `blur::kernel_with_limit`）。 */
    const val BLUR_LIMIT = 12f

    /**
     * 锁屏 / 桌面的放大半径。
     *
     * 上游：`"2.33".equals(DecimalFormat("#.##").format(height / width)) ? 1.3 : 1.24`。
     * 本机 2670/1200 = 2.225 ⇒ 2.23 ⇒ **1.24**。
     */
    fun expandedScale(w: Int, h: Int): Float =
        if (w <= 0) 1.24f else {
            val r = h.toFloat() / w
            val rounded = Math.round(r * 100f) / 100f
            if (rounded == 2.33f) 1.3f else 1.24f
        }

    // ───────────────────────────── 状态 / 参数 ─────────────────────────────

    /** 一个态的 10 个镜头参数，字段顺序与上游 `lensState()` 一致。 */
    internal class LensState(
        var blur: Float = 0f,
        var scale: Float = 1f,
        var distortion: Float = 0f,
        var decorAlpha: Float = 0f,
        var decorAngle: Float = 0f,
        var x: Float = 0f,
        var y: Float = 0f,
        var speed: Float = 0f,
        var ripple: Float = 0f,
        var angle: Float = -360f,
    ) {
        fun copyInto(dst: LensState) {
            dst.blur = blur; dst.scale = scale; dst.distortion = distortion
            dst.decorAlpha = decorAlpha; dst.decorAngle = decorAngle
            dst.x = x; dst.y = y; dst.speed = speed
            dst.ripple = ripple; dst.angle = angle
        }
    }

    /**
     * 某个态的镜头参数。**AOD 的 `angle` 用 [Float.NaN]** 表示「不参与插值」——
     * 上游 `lensState(0)` 第 10 项就是 NaN，转场时那一项只挂一个空动画。
     */
    fun stateFor(mode: Int, expanded: Float): LensState = when (mode) {
        0 -> LensState(
            blur = AOD_BLUR, scale = AOD_SCALE, distortion = AOD_DISTORTION,
            decorAlpha = AOD_DECOR_ALPHA, decorAngle = 0f,
            x = AOD_X, y = aodCenterY, speed = AOD_SPEED, ripple = 0f,
            angle = Float.NaN,
        )
        1 -> LensState(
            blur = LOCK_BLUR, scale = expanded, distortion = 0f,
            decorAlpha = 0f, decorAngle = EXPANDED_DECOR_ANGLE,
            x = 0f, y = 0f, speed = 0f, ripple = EXPANDED_RIPPLE, angle = -360f,
        )
        else -> LensState(
            blur = HOME_BLUR, scale = expanded, distortion = 0f,
            decorAlpha = 0f, decorAngle = EXPANDED_DECOR_ANGLE,
            x = 0f, y = 0f, speed = 0f, ripple = EXPANDED_RIPPLE, angle = -360f,
        )
    }

    // ───────────────────────────── 转场曲线 ─────────────────────────────

    /** cubic-bezier(x1,y1,x2,y2) 求值（与 Android `PathInterpolator` 同一条曲线）。 */
    internal class Curve(
        private val x1: Float,
        private val y1: Float,
        private val x2: Float,
        private val y2: Float,
    ) {
        private fun axis(a1: Float, a2: Float, u: Float): Float {
            val v = 1f - u
            return 3f * v * v * u * a1 + 3f * v * u * u * a2 + u * u * u
        }

        fun at(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            var lo = 0f
            var hi = 1f
            var u = t
            repeat(22) {
                val x = axis(x1, x2, u)
                if (x < t) lo = u else hi = u
                u = (lo + hi) * 0.5f
            }
            return axis(y1, y2, u)
        }
    }

    val EASE = Curve(0.33f, 0f, 0.67f, 1f)

    /**
     * 上游 `SceneMotion` 里另有一条同名 `EASE = path(.4, 0, .2, 1)` —— 它只用在
     * **三界混合**（`blend.setInterpolator(tr==2||tr==3 ? EASE : LINEAR)`）。
     * 与上面那条不是同一条曲线，改名避免混淆。
     */
    val EASE_BLEND = Curve(0.4f, 0f, 0.2f, 1f)
    val EXPAND = Curve(0.13f, 0.04f, 0f, 1f)
    val COLLAPSE = Curve(0.37f, 0.04f, 0.04f, 1f)
    val DISTORT = Curve(0f, 0.5f, 0f, 1f)
    val ROTATE = Curve(0.17f, 0f, 0.67f, 1f)
    val SLAM = Curve(0f, 0f, 0f, 1f)
    val SNAP = Curve(0.17f, 0f, 0.83f, 1f)
    val COLLAPSE_TOP = Curve(0.37f, 0.02f, 0.04f, 1f)

    /** Android `AccelerateDecelerateInterpolator`：`(cos((t+1)π)+1)/2`。 */
    internal object DefaultCurve {
        fun at(t: Float): Float =
            (Math.cos(((t + 1f) * Math.PI).toDouble()) / 2.0 + 0.5).toFloat()
    }

    /** 一条轨道 = 时长 + 曲线（二选一，[curve] 为 null 用 DefaultCurve）。 */
    internal class Track(val ms: Long, val curve: Curve?)

    /**
     * 转场轨道表。索引与 `LensState` 字段一一对应：
     * 0 blur / 1 scale / 2 distortion / 3 decorAlpha / 4 decorAngle /
     * 5 x / 6 y / 7 speed / 8 ripple / 9 angle。
     *
     * 数值逐条照 `SceneMotion.lens()`：
     * * `tr==0`（AOD→锁屏）/ `tr==2`（AOD→桌面）
     * * `tr==1 / 3`（锁屏/桌面→AOD）
     * * `tr==4`（锁屏↔桌面）、`tr==5`
     */
    fun tracks(type: Int): Array<Track> {
        fun t(ms: Long, c: Curve?) = Track(ms, c)
        return when (type) {
            0 -> arrayOf(
                t(183, EASE), t(950, EXPAND), t(800, DISTORT), t(33, EASE), t(1000, null),
                t(950, EXPAND), t(950, EXPAND), t(1000, null), t(950, EASE), t(24, ROTATE),
            )
            2 -> arrayOf(
                t(183, EASE), t(1000, SLAM), t(900, DISTORT), t(33, EASE), t(1000, null),
                t(1000, SLAM), t(1000, SLAM), t(1000, null), t(1000, EASE), t(24, ROTATE),
            )
            1, 3 -> arrayOf(
                t(650, SNAP), t(650, COLLAPSE), t(650, EASE), t(650, EASE),
                t(650, COLLAPSE_TOP), t(650, COLLAPSE), t(650, COLLAPSE), t(650, null),
                t(0, EASE), t(1000, null),
            )
            4 -> arrayOf(
                t(3840, EASE), t(650, EXPAND), t(600, DISTORT), t(1000, null), t(1000, null),
                t(650, EXPAND), t(650, EXPAND), t(1000, null), t(650, EASE), t(24, ROTATE),
            )
            else -> arrayOf(
                t(0, EASE), t(1000, null), t(1000, null), t(1000, null), t(1000, null),
                t(1000, null), t(1000, null), t(1000, null), t(1000, EASE), t(24, ROTATE),
            )
        }
    }

    /** `tr==1 / 3` 时 ripple 的起始延迟（上游 `rippleDelay = 650`）。 */
    fun rippleDelay(type: Int): Long = if (type == 1 || type == 3) 650L else 0L

    /** `SceneMotion.transition(from,to)`。 */
    fun transition(from: Int, to: Int): Int = when {
        from == 0 -> if (to == 1) 0 else 2
        to == 0 -> if (from == 1) 1 else 3
        else -> if (from == 1) 4 else 5
    }

    /** 三界混合时长（`SceneMotion.blendDuration`，`aod == 0` 那一档）。 */
    fun blendDuration(type: Int): Long {
        // ⚠️ **tr=2 不用上游的 300ms**（2026-11-08 用户实测「缩放很奇怪」）。
        // 上游 aod / launcher 是同一张图的不同处理，300ms 只是"对焦变清晰"；
        // 我们锁屏图与桌面图是**两张不同的图**，300ms 会让球里的内容先换掉、
        // 而球半径（1000ms，SLAM 前慢后急）几乎还没动 ⇒ 看起来就是「先硬换图再猛扩」。
        // 拉到与 lens 同步，内容变化和球扩是同一个节奏。
        if (type == 2) return 1000L
        val a = longArrayOf(950, 650, 300, 650, 650, 300)
        val l = longArrayOf(250, 250, 250, 250, 400, 400)
        val h = longArrayOf(250, 250, 250, 250, 500, 500)
        return when {
            type < 2 -> maxOf(a[type], l[type])
            type < 4 -> maxOf(a[type], h[type])
            else -> maxOf(l[type], h[type])
        }
    }

    /** 角度走最短弧（`SceneMotion.nearestRotation`）。 */
    fun nearestRotation(current: Float, target: Float): Float {
        val distance = Math.floorMod((target - current).toInt(), 360)
        return target + if (distance >= 180) 360 - distance else -distance
    }

    // ───────────────────────────── 模糊核 ─────────────────────────────

    /** 一个模糊核：`factor` = 降采样比例，`weights` / `offsets` 是配对后的抽样。 */
    internal class Kernel(val factor: Float, val weights: FloatArray, val offsets: FloatArray)

    /**
     * 高斯核（逐行照 `blur::kernel_with_limit`）。
     *
     * 截断半径按 `1/256` 的能量阈值推：`sqrt(-2σ²·ln(1/256·sqrt(2πσ²)))`，
     * 然后**相邻两抽合并**（`i` 与 `i+1` 的权重相加、位置取加权平均），
     * 把采样数砍半。`factor = max(radius/limit, 1)` 交给降采样 pass。
     */
    fun kernel(radius: Float, limit: Float = BLUR_LIMIT): Kernel {
        if (!radius.isFinite() || radius < 2f) {
            return Kernel(1f, floatArrayOf(1f), floatArrayOf(0f))
        }
        val lim = if (limit.isFinite()) limit.coerceIn(2f, 12f) else 12f
        val sigma = minOf(radius, lim).toDouble()
        val norm = Math.sqrt(sigma * sigma * 2.0 * Math.PI)
        var extent = Math.sqrt(
            -2.0 * sigma * sigma * Math.log((1.0 / 256.0) * norm),
        ).toInt()
        extent += extent % 2

        val weights = DoubleArray(extent + 1) { i ->
            Math.exp(-(i * i).toDouble() / (2.0 * sigma * sigma)) / norm
        }
        var sum = weights[0] + 2.0 * weights.drop(1).sum()
        val w = FloatArray(extent + 1) { (weights[it] / sum).toFloat() }

        val paired = ArrayList<Float>()
        val offsets = ArrayList<Float>()
        paired.add(w[0])
        offsets.add(0f)
        var i = 1
        while (i <= extent) {
            val a = w[i]
            val b = if (i + 1 <= extent) w[i + 1] else 0f
            val s = a + b
            if (s > 0f) {
                paired.add(s)
                offsets.add((i * a + (i + 1) * b) / s)
            }
            i += 2
        }
        return Kernel(maxOf(radius / lim, 1f), paired.toFloatArray(), offsets.toFloatArray())
    }

    // ───────────────────────────── 着色器 ─────────────────────────────

    /**
     * 所有 pass 共用：一个全屏 quad，UV 的 y 向下（与上游 quad 完全一致）。
     *
     * `uUvScale` / `uUvOffset` 对应上游模糊链的 `u_texture_matrix`
     * （`[s,0,0, 0,s,0, (1-s)/2,(1-s)/2,1]`）：降采样后只有中心 `s×s` 有内容，
     * 后续 pass 必须把采样限定在那块里。其余 pass 传 (1,1)/(0,0)。
     */
    const val VERT = """
attribute vec3 aPos;
attribute vec2 aUv;
uniform vec2 uUvScale;
uniform vec2 uUvOffset;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 1.0);
    vUv = aUv * uUvScale + uUvOffset;
}
"""

    /** 照片 → 离屏靶：cover 裁剪 + 可选 Y 翻转（与上游 `COPY_V/COPY_F` 同义）。 */
    const val COPY_FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uCrop;
uniform float uFlip;
void main() {
    vec2 uv = (vUv - 0.5) * uCrop + 0.5;
    uv.y = mix(uv.y, 1.0 - uv.y, uFlip);
    gl_FragColor = texture2D(uTex, uv);
}
"""

    /**
     * 启航主效果。
     *
     * 逐条对齐上游 `lens_photo_frag.glsl`：
     * * `uv.y` 乘 `h/w`（各向异性坐标，圆才是正圆）
     * * `lens_distortion(r, α) = r·(1 − α·|r|²)`
     * * `zoom_point(uv,c,z) = (uv−c)·z + c`
     * * 纹理矩阵过完再 `y = 1 − y`
     * * 装饰：先提饱和（0.5×α），再按**球面坐标**叠阴影（等价于上游的装饰图纹理采样）
     * * 圆边 `smoothstep(scale−0.002, scale, dist)` 混到黑
     *
     * ⚠️ 上游 `ENABLE_RIPPLE` 是 0，波纹代码**不编译** —— 这里也不实现。
     *
     * ## 装饰图为什么变成公式
     *
     * 官方 `lens_decorator.png` 是 660×660 的纯黑 + alpha 渐变，贴图坐标恰是
     * 「以球心为原点、半径 = 球半径」的归一化坐标 ⇒ 与 `(uv - center) / lensScale`
     * 完全等价。这里直接按 5 阶多项式算，省一次纹理采样、也没有 8bit 量化误差。
     * 系数来自对官方 alpha 的最小二乘拟合（14 项→5 阶，MAE 0.9/255），
     * 见 `alive分析/decorator_compare/gen_decorator.py`。
     */
    const val LENS_FRAG = """
precision highp float;
varying vec2 vUv;

uniform sampler2D uPhoto;
uniform vec3 uTranslation;
uniform mat4 uTextureMatrix;
uniform float uLensScale;
uniform float uAspect;
uniform float uAspectRecip;
uniform float uDistortion;
uniform float uZoom;
uniform float uDecorAlpha;
uniform float uDarkStrength;

const vec3 SAT_W = vec3(0.2125, 0.7154, 0.0721);

vec2 lensDistortion(vec2 r, float alpha) {
    return r * (1.0 - alpha * dot(r, r));
}

vec2 zoomPoint(vec2 uv, vec2 point, float zoom) {
    return (uv - point) * zoom + point;
}

vec3 increaseSaturation(vec3 base, float strength) {
    float lum = dot(base, SAT_W);
    return mix(vec3(lum), base, 1.0 + strength);
}

vec3 blendNormal(vec3 base, vec3 blend, float opacity) {
    return blend * opacity + base * (1.0 - opacity);
}

vec4 applyDarkIfNeed(vec4 color, float darkStrength) {
    if (darkStrength <= 0.01) {
        return color;
    }
    color.rgb = color.rgb * (1.0 - 0.3 * darkStrength);
    color.rgb = (color.rgb - vec3(0.5)) * (1.0 - 0.1 * darkStrength) + vec3(0.5);
    return color;
}

/** 球面阴影遮罩：p 为归一化球面坐标（-1..1，y 向下），等价于官方装饰图。 */
float decorAlpha(vec2 p) {
    float u = p.x;
    float v = p.y;
    float u2 = u * u;
    float u3 = u2 * u;
    float u4 = u3 * u;
    float u5 = u4 * u;
    float v2 = v * v;
    float v3 = v2 * v;
    float v4 = v3 * v;
    float v5 = v4 * v;
    float a =
        0.17079832
        + 0.38000503 * v
        + 0.27049240 * v2
        - 0.02328848 * v3
        - 0.08169705 * v4
        - 0.01471029 * v5
        - 0.37975070 * u
        - 0.16269897 * u * v
        + 0.23632210 * u * v2
        + 0.13708094 * u * v3
        - 0.07022234 * u * v4
        + 0.27037001 * u2
        - 0.23601462 * u2 * v
        - 0.06374525 * u2 * v2
        + 0.04455454 * u2 * v3
        + 0.02329872 * u3
        + 0.13719989 * u3 * v
        - 0.04486833 * u3 * v2
        - 0.08159617 * u4
        + 0.07015046 * u4 * v
        + 0.01459191 * u5;
    return clamp(a, 0.0, 0.8);
}

void main() {
    vec2 uv = vec2(vUv.x, vUv.y * uAspect);
    vec2 center = vec2(0.5 + uTranslation.x, (0.5 + uTranslation.y) * uAspect);
    float dist = distance(uv, center);
    if (dist > uLensScale) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 distorted = zoomPoint(uv + lensDistortion(uv - center, uDistortion), center, uZoom);
    vec2 tuv = (uTextureMatrix *
        vec4(vec2(distorted.x, distorted.y * uAspectRecip) * 2.0 - 1.0, 0.0, 1.0)).xy;
    // ⚠️ 上游这里还有一次 `tuv.y = 1.0 - tuv.y`，是与它自己那套 top-origin FBO 配套的。
    // 我们的 photoT 方向已经由 copy 的 uFlip 定好（锁屏/桌面直接采样它是正的），
    // 再翻一次球面就上下颠倒了（2026-11-07 真机现象）⇒ 这里不翻。

    vec3 color = texture2D(uPhoto, tuv).rgb;
    if (uDecorAlpha > 0.0001) {
        color = increaseSaturation(color, uDecorAlpha * 0.5);
        // ⚠️ **p.y 必须取反**（2026-11-08 真机定量证实）：lens 写进 aodT 之后，
        // blend 采样它时整幅会上下翻转（FBO 纹理原点在左下，而 pass 内 vUv 原点在顶部），
        // 照片靠 `uTextureMatrix` 补偿了这层翻转，纯几何算出来的装饰没有 ⇒
        // 不取反就会「上暗下亮」，与官方图（下方 alpha 0.65 最暗、上方 0.00）正好相反。
        // 实测证据：球内 0.8 半径处 上 0.244 / 下 0.644、左上 0.265 最暗 / 右下 0.717 最亮。
        vec2 p = vec2(uv.x - center.x, center.y - uv.y) / uLensScale;
        color = blendNormal(color, vec3(0.0), decorAlpha(p) * uDecorAlpha);
    }

    vec4 dark = applyDarkIfNeed(vec4(color, 1.0), uDarkStrength);
    gl_FragColor = mix(dark, vec4(0.0, 0.0, 0.0, 1.0),
        smoothstep(uLensScale - 0.002, uLensScale, dist));
}
"""

    /**
     * 留光（leave_light）：AOD 是一张**实体相片**，不是球。
     *
     * 语义照 openalive 的 `chromakey_photo_frag.glsl`（三通道遮罩 + 三次 blend），
     * ES2 重写；素材是我们自绘的 61 帧 `.rgbz`（`AliveMaskFrames`）。
     *
     * | 通道 | 语义 | 合成 |
     * |---|---|---|
     * | R | 衬纸（纸背） | `mix(col, rear, m.r)` |
     * | B | 白边（相纸 0.96 / 衬纸外沿 0.58） | `mix(col, vec3(1.0), m.b)` |
     * | G | 相片内容 | `mix(col, content, m.g)` |
     *
     * ## 坐标
     *
     * pass 内 `vUv` 与屏幕 y **反向**（实测见 [LENS_FRAG] 的注释：lens 写进 aodT 后
     * 整幅会翻 y）⇒ 屏幕上「相纸中心在 0.335」对应这里 `uCenter.y = 1 − 0.335 = 0.665`。
     * 角度同理取**正**（pass 内 y 向上，逆时针为正）。
     *
     * ## 取景
     *
     * 相片内容有**独立取景**（官方 `u_frame_crop`，窗口是相纸里的正方形）。
     * v1 直接从 `photoT`（已 cover 到屏幕）取中心正方形：`uContentZoom` 是取景框高度
     * 占屏高的比例，x 方向乘 `uAspect` 保证不拉伸。
     *
     * 纸背用同一块内容的**镜像**（官方 `M_rear` 的近似）—— 不镜像的话衬纸看起来
     * 像一块纯色板（2026-11-09 CPU 预览踩过）。
     */
    const val LIUGUANG_FRAG = """
precision highp float;
varying vec2 vUv;

uniform sampler2D uPhoto;
uniform sampler2D uMask;
/** 桌面图（cover 到屏幕比例）。只被 `uPhotoAlpha < 1` 用到 —— 官方槽 7 的内容交叉。 */
uniform sampler2D uLauncher;
/** 官方 `values[7]`（photo alpha）：AOD/锁屏 1.0、桌面 0.0。 */
uniform float uPhotoAlpha;
uniform float uAspect;       // 屏高 / 屏宽
uniform float uScale;        // **mask 720 画布的边长 / 屏宽**（= 官方 PhotoStyle.scale）
uniform vec2  uCenter;       // quad 中心（pass 内 UV，y 从下往上）
uniform float uRot;          // 弧度
uniform float uContent;      // 官方 values[3]：AOD 1.0 / 锁屏-桌面 aspect/2.35
/**
 * 纸背偏移（衬纸 R 通道中心 − 内容 G 通道中心，画布比例）。
 *
 * 衬纸在画布上比内容框**右移 47/720**（000 帧实测），直接按画布采样会掉到负数，
 * 被 `clamp` 压成一条边缘列 —— 真机表现就是「下层相卡全是横条纹」。
 * 减掉它，衬纸采到的是「上层同一位置的画面，整体平移过去」。
 */
uniform vec2 uRearOff;

// 相纸外框在 720 画布里的位置（复刻素材几何，逐帧对齐过）
const float PAPER_L = 190.0 / 720.0;
const float PAPER_S = 340.0 / 720.0;

void main() {
    // 换到「以屏宽为单位」的坐标（x、y 同单位，相纸才是正方形）
    vec2 p = vec2(vUv.x, vUv.y * uAspect);
    vec2 c = vec2(uCenter.x, uCenter.y * uAspect);
    vec2 d = p - c;

    float ca = cos(uRot);
    float sa = sin(uRot);
    vec2 r = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y);

    // ⚠️ quad 的整块 = mask 的 **720 画布**（相纸只占中间 340/720）。
    //    当成「相纸边长」会把相纸放大 720/340 = 2.12 倍，并裁掉衬纸。
    vec2 quv = r / uScale + 0.5;
    vec2 muv = vec2(quv.x, 1.0 - quv.y);          // mask 是 top-down
    if (muv.x < 0.0 || muv.x > 1.0 || muv.y < 0.0 || muv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 m = texture2D(uMask, muv).rgb;

    // ⚠️ 内容取景必须在 **quad 空间**做（官方 `u_contentMatrix` =
    //    `T(.5) S(aspect/content, 1/content) T(-.5)` 作用在 quad UV 上）。
    //    之前拿「相纸局部 + 000 帧的固定内容框」去映射，展开到中间帧时
    //    内容框高度已经变了（300→720），常量框就对不上 ⇒ 画面被拉伸/扭曲。
    //    quad 是正方形（以屏宽为单位），photoT 的 v 方向是屏幕高 ⇒ x 乘 aspect 才等比。
    vec2 cq = vec2(quv.x, 1.0 - quv.y);
    vec2 cuv = 0.5 + (cq - 0.5) * vec2(uAspect / uContent, 1.0 / uContent);
    vec3 content = texture2D(uPhoto, clamp(cuv, 0.002, 0.998)).rgb;

    // 官方 `chromakey_photo_frag` 的 `u_photo_alpha`（槽 7）：
    // `contentColor = mix(launcherTex, contentColor, u_photo_alpha)`。
    //
    // 桌面态 alpha = 0 ⇒ **内容框里显示的是桌面壁纸**，跟着相纸一起缩放；
    // AOD / 锁屏态 alpha = 1 ⇒ 只显示照片。
    // 不做这一步的话，AOD 用另一张图时「退出 AOD」会在相片铺满后硬换成锁屏/桌面图。
    // ⚠️ launcher 的取景坐标与 photo **完全同一个 `cuv`**（官方就是同一个
    //    `v_content_texCoord`）—— 两张靶都是「cover 到屏幕比例」，所以能对上。
    if (uPhotoAlpha < 0.999) {
        vec3 launcher = texture2D(uLauncher, clamp(cuv, 0.002, 0.998)).rgb;
        content = mix(launcher, content, uPhotoAlpha);
    }

    // 纸背 = 上层内容的**平移副本**（2026-11-10 用户口径：「上层显示什么，下层照搬」）。
    //
    // ⚠️ 原来写的是 `0.5 + (0.5 - cq.x) * …`（镜像）—— 衬纸本身比内容框右移 47/720，
    //    再镜像一次直接变成负数（000 帧实测 ruv.x ∈ [−0.136, 0.852]），
    //    被 `clamp` 全压到 0.002 ⇒ 衬纸区域整块是「照片最左一列」被横向拉满
    //    = 横条纹。去掉镜像 + 减掉偏移后，坐标正好落回 [0.000, 0.994]。
    vec2 rc = cq - uRearOff;
    vec2 ruv = 0.5 + (rc - 0.5) * vec2(uAspect / uContent, 1.0 / uContent);
    vec3 rear = texture2D(uPhoto, clamp(ruv, 0.002, 0.998)).rgb * 0.94;

    // ⚠️ 底色必须是**纯黑**（官方 `finalColor = vec3(0.0)`）。
    //    这里原来写的是纸背基色 ⇒ quad 内、相纸外的整块变成淡灰色方块。
    vec3 col = vec3(0.0);
    col = mix(col, rear, m.r);
    col = mix(col, vec3(1.0), m.b);
    col = mix(col, content, m.g);
    // ⚠️ alpha = 1 − G（官方 `chromakey_photo_frag` 的最后一行）：
    //    相片内容区 alpha = 0，相框外 / 白边 / 衬纸 alpha = 1。
    //    blend 层用它把「非相片区域」压黑 —— 转场中间才不会露出壁纸。
    gl_FragColor = vec4(col, 1.0 - m.g);
}
"""

    /**
     * 三界混合（AOD / 锁屏 / 桌面）。
     *
     * 照上游 `lens_blend_frag.glsl` 的 6 个分支，一字不差：
     * 单独一界直接取层；两两/三界融合按比例加权；桌面那一路用圆形遮罩
     * （`getLauncherColor`，圆心 y 用 `0.5 − ty`）。
     */
    /**
     * **启航（球）的 blend** —— 逐字对应 fork2 的 `common_blend`。
     *
     * ⚠️ **这一份与 [BLEND_FRAG_LIUGUANG] 必须完全独立**（2026-11-09 用户要求
     * 「两个样式的分支务必做清楚」）。之前的教训：为了修留光去改同一个 shader，
     * 把 `aod-launcher` 从「加权」改成「直接取 aod」⇒ **启航 AOD→桌面 球外的桌面图整块消失**。
     * 两个样式的 blend 分支口径本来就不同，别为了少写几十行再合并。
     */
    const val BLEND_FRAG_QIHANG = """
precision highp float;
varying vec2 vUv;

uniform sampler2D uAod;
uniform sampler2D uLock;
uniform sampler2D uLauncher;
uniform vec3 uRatio;
uniform float uDarkStrength;
uniform bool uSupportLockLauncher;
uniform vec2 uTranslation;
uniform float uLensScale;
uniform float uAspect;
uniform float uDim;

vec4 applyDarkIfNeed4(vec4 color, float darkStrength) {
    if (darkStrength <= 0.01) {
        return color;
    }
    color.rgb = color.rgb * (1.0 - 0.3 * darkStrength);
    color.rgb = (color.rgb - vec3(0.5)) * (1.0 - 0.1 * darkStrength) + vec3(0.5);
    return color;
}

float getDist() {
    vec2 aspectUv = vec2(vUv.x, vUv.y * uAspect);
    vec2 distortionCenter = vec2(0.5 + uTranslation.x, (0.5 - uTranslation.y) * uAspect);
    return distance(aspectUv, distortionCenter);
}

/** 启航的 launcher = **圆球圆裁 + 球心平移采样**（`uLensScale` 是球半径）。 */
vec3 getLauncherColor() {
    float dist = getDist();
    if (dist > uLensScale) {
        return vec3(0.0, 0.0, 0.0);
    }
    vec2 uv = vUv + vec2(-uTranslation.x, uTranslation.y);
    vec3 launcherTex = texture2D(uLauncher, uv).rgb;
    float factor = smoothstep(uLensScale - 0.002, uLensScale, dist);
    return mix(launcherTex, vec3(0.0, 0.0, 0.0), factor);
}

void main() {
    vec3 contentColor;
    if (uRatio.x > 0.999999) {
        contentColor = texture2D(uAod, vUv).rgb;
    } else if (uRatio.y > 0.999999) {
        contentColor = texture2D(uLock, vUv).rgb;
    } else if (uRatio.z > 0.999999) {
        contentColor = getLauncherColor();
    } else if (uRatio.x < 0.000001) {
        // lock-launcher 融合
        vec3 lockTex = texture2D(uLock, vUv).rgb;
        vec3 launcherTex = texture2D(uLauncher, vUv).rgb;
        contentColor = (lockTex * uRatio.y + launcherTex * uRatio.z) / (uRatio.y + uRatio.z);
    } else if (uRatio.y < 0.000001) {
        // aod-launcher 融合：**加权**（fork2）。球扩时球外靠 getLauncherColor 的圆裁铺开。
        vec3 aodTex = texture2D(uAod, vUv).rgb;
        vec3 launcherTex = getLauncherColor();
        contentColor = (aodTex * uRatio.x + launcherTex * uRatio.z) / (uRatio.x + uRatio.z);
    } else if (uRatio.z < 0.000001) {
        // aod-lock 融合：直接取 aod（v1.22.56 起就是这个口径）
        contentColor = texture2D(uAod, vUv).rgb;
    } else {
        // aod-lock-launcher 三界融合
        if (uSupportLockLauncher) {
            vec3 aodTex = texture2D(uAod, vUv).rgb;
            vec3 launcherTex = getLauncherColor();
            contentColor = (aodTex * (uRatio.x + uRatio.y) + launcherTex * uRatio.z) /
                (uRatio.x + uRatio.y + uRatio.z);
        } else {
            contentColor = texture2D(uAod, vUv).rgb;
        }
    }
    gl_FragColor = applyDarkIfNeed4(vec4(contentColor, 1.0), uDarkStrength);
    gl_FragColor.rgb *= uDim;
}
"""

    /**
     * **留光（相片）的 blend** —— 对应 openalive 的 `chromakey_blend`。
     *
     * 与 [BLEND_FRAG_QIHANG] 的**口径差异**（务必保留，别合并）：
     *
     * | 分支 | 启航 | 留光 |
     * |---|---|---|
     * | aod-launcher | 加权（球外靠圆裁铺桌面） | **直接取 aod**（图交叉交给 `uPhotoAlpha`） |
     * | aod-lock | 直接取 aod | **直接取 aod**（同上） |
     * | 三界 | 加权 | 加权 + **用 aod alpha 压黑**（约束绿幕） |
     * | launcher 形状 | 圆球圆裁 | **方相纸**（`uAodShape` 控制，稳态整屏） |
     */
    const val BLEND_FRAG_LIUGUANG = """
precision highp float;
varying vec2 vUv;

uniform sampler2D uAod;
uniform sampler2D uLock;
uniform sampler2D uLauncher;
uniform vec3 uRatio;
uniform float uDarkStrength;
uniform bool uSupportLockLauncher;
uniform vec2 uTranslation;
uniform float uLensScale;
uniform float uAspect;
uniform float uDim;

vec4 applyDarkIfNeed4(vec4 color, float darkStrength) {
    if (darkStrength <= 0.01) {
        return color;
    }
    color.rgb = color.rgb * (1.0 - 0.3 * darkStrength);
    color.rgb = (color.rgb - vec3(0.5)) * (1.0 - 0.1 * darkStrength) + vec3(0.5);
    return color;
}

/** 相纸倾角（弧度）。 */
uniform float uLensRot;
/**
 * 1 = 用**相纸方形**约束 launcher，0 = 整屏直出。
 *
 * 只有「AOD 稳态 / 进出场」才该是 1 —— 锁屏、桌面稳态（`ratio` 单界）必须整屏，
 * 否则相纸的小 scale 会把桌面图裁成屏幕中间一条（左右黑边）。
 */
uniform float uAodShape;

/**
 * launcher（桌面图）在留光下的可见形状 = **方相纸**，与 `LIUGUANG_FRAG` 同一套局部坐标
 * （含 −5° 倾角）。AOD 态相纸 0.705 屏宽 ⇒ 只露中间一条；放大到 2.35 时自动整屏。
 */
vec3 getLauncherColor() {
    if (uAodShape < 0.5) return texture2D(uLauncher, vUv).rgb;
    vec2 p = vec2(vUv.x, vUv.y * uAspect);
    vec2 c = vec2(0.5 + uTranslation.x, (0.5 - uTranslation.y) * uAspect);
    vec2 d = p - c;
    float ca = cos(uLensRot);
    float sa = sin(uLensRot);
    vec2 r = vec2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y);
    // 相纸真实外框：宽 340/720 画布、高按画布整高（end 帧铺满）
    vec2 halfSize = vec2(uLensScale * (170.0 / 720.0), uLensScale * 0.5);
    vec2 edge = abs(r) - halfSize;
    float feather = max(edge.x, edge.y);
    float a = 1.0 - smoothstep(-0.002, 0.002, feather);
    if (a <= 0.0) return vec3(0.0, 0.0, 0.0);
    return texture2D(uLauncher, vUv).rgb * a;
}

void main() {
    vec3 contentColor;
    if (uRatio.x > 0.999999) {
        contentColor = texture2D(uAod, vUv).rgb;
    } else if (uRatio.y > 0.999999) {
        contentColor = texture2D(uLock, vUv).rgb;
    } else if (uRatio.z > 0.999999) {
        contentColor = getLauncherColor();
    } else if (uRatio.x < 0.000001) {
        // lock-launcher 融合
        vec3 lockTex = texture2D(uLock, vUv).rgb;
        vec3 launcherTex = texture2D(uLauncher, vUv).rgb;
        contentColor = (lockTex * uRatio.y + launcherTex * uRatio.z) / (uRatio.y + uRatio.z);
    } else if (uRatio.y < 0.000001) {
        // aod-launcher：**直接取 aod**（openalive）。「AOD 图 ≠ 桌面图」的过渡
        // 由 aod 层内部的 `uPhotoAlpha` 完成，blend 再加权就是双重混合。
        contentColor = texture2D(uAod, vUv).rgb;
    } else if (uRatio.z < 0.000001) {
        // aod-lock：同样直接取 aod
        contentColor = texture2D(uAod, vUv).rgb;
    } else {
        // 三界融合：加权 + 用 aod 的 alpha（= 1 − G）压黑。
        // 官方 `chromakey_blend_frag` 只在**这一档**做 `mix(contentColor, 0, aodTex.a)`；
        // 单界 / 两界一律直接输出（aod 纹理自己带黑底）。
        // ⚠️ 写成全局 if 就会把 AOD 稳态的白边/衬纸（alpha = 1）整块压黑 = 「相卡消失」。
        if (uSupportLockLauncher) {
            vec4 aodTex = texture2D(uAod, vUv);
            vec3 launcherTex = getLauncherColor();
            contentColor = (aodTex.rgb * (uRatio.x + uRatio.y) + launcherTex * uRatio.z) /
                (uRatio.x + uRatio.y + uRatio.z);
            contentColor = mix(contentColor, vec3(0.0), aodTex.a);
        } else {
            contentColor = texture2D(uAod, vUv).rgb;
        }
    }
    gl_FragColor = applyDarkIfNeed4(vec4(contentColor, 1.0), uDarkStrength);
    gl_FragColor.rgb *= uDim;
}
"""


    /** 降采样：只画 `aPos × uScale` 的中心块，UV 仍是整幅。 */
    const val BLUR_DOWN_FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""

    /** 横向 / 纵向高斯：配对抽样 + 边缘 clamp 到降采样块内。 */
    const val BLUR_GAUSS_FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform float uWeights[50];
uniform float uOffsets[50];
uniform int uRadius;
uniform bool uHorizontal;
uniform float uTexMin;
uniform float uTexMax;
uniform float uStepX;
uniform float uStepY;

vec2 clampCoord(float x, float y) {
    return clamp(vec2(x, y), vec2(uTexMin), vec2(uTexMax));
}

const int MAX_R = 25;

void main() {
    vec3 sum = texture2D(uTex, vUv).rgb * uWeights[0];
    for (int i = 1; i < MAX_R; i++) {
        if (i >= uRadius) { break; }
        float o = uOffsets[i];
        if (uHorizontal) {
            sum += texture2D(uTex, clampCoord(vUv.x + o * uStepX, vUv.y)).rgb * uWeights[i];
            sum += texture2D(uTex, clampCoord(vUv.x - o * uStepX, vUv.y)).rgb * uWeights[i];
        } else {
            sum += texture2D(uTex, clampCoord(vUv.x, vUv.y + o * uStepY)).rgb * uWeights[i];
            sum += texture2D(uTex, clampCoord(vUv.x, vUv.y - o * uStepY)).rgb * uWeights[i];
        }
    }
    gl_FragColor = vec4(sum, 1.0);
}
"""

    /** 升采样：从降采样块放大回全屏。 */
    const val BLUR_UP_FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""
}
