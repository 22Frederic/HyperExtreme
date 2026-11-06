package com.frederic22.hyperextreme.hook

/**
 * 光栅壁纸的**渲染常量与着色器**（2026-11-04）。
 *
 * 与空间的 [SpatialGlCore] 分开：那边是「两层纹理 + 双通道位移场 + 逐像素 k」，
 * 这边是「两张全屏图 + 一条擦除带」，公式与 uniform 完全不重叠，硬塞进一份只会互相拖累。
 *
 * ## 擦除的数学（整条链只有这一处口径）
 *
 * ```
 * e    = dir > 0 ? 1 − uv.x : uv.x        // 统一成"擦除前进方向"的坐标
 * edge = −B/2 + t × (1 + B)               // 边界从一侧屏外扫到另一侧屏外
 * a    = clamp((edge − e)/B + 0.5, 0, 1)  // 过渡带内 alpha **线性**变化
 * color = mix(from, to, a)
 * ```
 *
 * `t = 0` 整屏是旧图、`t = 1` 整屏是新图；`−B/2` 与 `1+B/2` 两端的溢出保证起止干净
 * （不留半条带宽的残边）。
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

    /**
     * 擦除过渡带 = **屏宽的 1/5**（用户 2026-11-04 定），带内 alpha 线性。
     */
    const val BAND = 0.20f

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

    const val VERT = """
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAG = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uFrom;
uniform sampler2D uTo;
// xy = uv 缩放，zw = uv 平移。用来做 **cover**：按比例铺满屏幕、多出来的居中裁掉。
// from / to 两张图比例可能不同，所以各带一套。
uniform vec4 uFromUv;
uniform vec4 uToUv;
uniform float uT;
uniform float uDir;
uniform float uBand;
uniform float uDim;
void main() {
    vec2 uv = vUv;
    // dir > 0（向左转）= 新图从**左**边进来；dir < 0 = 从右边进来。
    // ⚠️ 2026-11-04 真机反馈方向反了 ⇒ 这里相对第一版取反（只影响从哪边进，不影响图号）。
    float e = (uDir > 0.0) ? uv.x : (1.0 - uv.x);
    float edge = -uBand * 0.5 + uT * (1.0 + uBand);
    float a = clamp((edge - e) / uBand + 0.5, 0.0, 1.0);
    vec4 c = mix(
        texture2D(uFrom, uv * uFromUv.xy + uFromUv.zw),
        texture2D(uTo,   uv * uToUv.xy   + uToUv.zw),
        a
    );
    // 压暗只乘 RGB（AOD 用；`Transaction#setColor` 在 HyperOS 上会发白，已封死）。
    gl_FragColor = vec4(c.rgb * uDim, c.a);
}
"""
}
