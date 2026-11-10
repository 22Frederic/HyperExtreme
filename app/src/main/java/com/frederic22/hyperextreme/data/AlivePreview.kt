package com.frederic22.hyperextreme.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AOD 预览图生成（2026-11-08）。
 *
 * 把锁屏图渲染成「AOD 小球」的**静态预览**，给浮页里的 AOD 卡显示。
 * 公式与 [com.frederic22.hyperextreme.hook.AliveGlCore.LENS_FRAG] 同源，
 * 但这里是 CPU 侧一次性出图，不需要 GL。
 *
 * 参数取 AOD 三态那一档：
 * * 模糊半径 22、`scale` 0.22（半径 = 0.22 屏宽）、`distortion` 9（内部再除以 scale）；
 * * 球心**固定屏幕高度 0.5**（用户 2026-11-08 指定），不跟时钟；
 * * 圆外纯黑；装饰按 5 阶多项式压暗（左下最暗、右上有微光）。
 *
 * 尺寸策略：输出宽度默认 [OUT_W]（600），高度按屏幕比例 —— 预览图只用于卡片展示，
 * 全分辨率既慢又没必要。
 */
internal object AlivePreview {

    /** 输出宽度（屏幕宽的一半，够卡片放大看）。 */
    const val OUT_W = 600

    // AOD 三态参数（与 AliveGlCore.AOD_* 保持一致）
    private const val BLUR = 22f
    private const val SCALE = 0.22f
    private const val DISTORTION = 9f

    /** AOD 的装饰透明度（`AliveGlCore.AOD_DECOR_ALPHA`，提饱和用它的 0.5 倍）。 */
    private const val AOD_DECOR = 1f

    /**
     * 预览图的模糊再放大倍数（2026-11-08 用户反馈「看得出原图很诡异」）。
     *
     * 真机 AOD 的球是**一直在自转**的，运动本身掩盖了细节；预览是静止图、还能放大看，
     * 按实渲染的半径（等效 12px）就显得太清楚。这里单独加糊，只影响预览、不影响渲染。
     */
    private const val PREVIEW_BLUR_SCALE = 1.8f

    /** 球心：屏幕高度 35%（用户 2026-11-08 定，与实渲染的 `AOD_Y = 0.15` 同一个位置）。 */
    private const val CENTER_Y = 0.35f

    // ── 留光（相片）样式：参数来自官方 `PhotoStyle` / 000 帧实测
    /** 相片边长 / 屏宽（`PhotoStyle(1)` 的 `scale = 0.70486`）。 */
    private const val LIUGUANG_SCALE = 0.70486f
    /** 相片中心在屏幕高度的位置。 */
    private const val LIUGUANG_CY = 0.335f
    /** 倾角：视觉上**逆时针** 5°（Canvas 正角度是顺时针，所以画的时候取负）。 */
    private const val LIUGUANG_ANGLE = 5f
    /** 衬纸相对相纸的姿态（000 帧实测反解）：右移 27.4、下移 2、顺转 7.13°。 */
    private const val BACKER_DX = 27.4f
    private const val BACKER_DY = 2f
    private const val BACKER_ROT = 7.13f

    fun render(src: Bitmap, screenW: Int, screenH: Int, outW: Int = OUT_W): Bitmap? = runCatching {
        val aspect = screenH.toFloat() / screenW
        val outH = (outW * aspect).roundToInt()
        val k = outW.toFloat() / screenW

        // 1. cover 裁剪到输出尺寸（居中裁，与 GL 的 copy pass 同义）
        val base = coverCrop(src, outW, outH)
        // 2. 模糊（半径按缩放系数走；半径 22 在 1200 宽屏上是 22px，这里按比例缩）
        val blurred = boxBlur(
            base,
            (BLUR * k * PREVIEW_BLUR_SCALE / 2f).roundToInt().coerceAtLeast(1),
        )
        if (blurred !== base && base !== src) base.recycle()

        // 3. 逐像素：反向畸变 + 缩放到球面 + 装饰 + 圆外黑
        val w = outW
        val h = outH
        val srcPx = IntArray(w * h)
        blurred.getPixels(srcPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        val zoom = 1f / (min(SCALE, 0.5f) * 4f)          // AOD: 1.136
        val alphaD = DISTORTION / SCALE                   // AOD: 40.9
        val cx = 0.5f
        val cy = CENTER_Y * aspect                        // uv 空间的球心（y 已乘 aspect）
        val radius = SCALE

        for (py in 0 until h) {
            val uy = py.toFloat() / h * aspect
            for (px in 0 until w) {
                val ux = px.toFloat() / w
                val dx = ux - cx
                val dy = uy - cy
                val dist = sqrt(dx * dx + dy * dy)
                val idx = py * w + px
                if (dist > radius) {
                    out[idx] = 0xFF000000.toInt()
                    continue
                }
                // **正向映射**（与 `lens_photo_frag.glsl:57` 逐字一致，不要反解！）：
                //   distortionUV = zoomPoint(uv + lensDistortion(uv − center, α), center, zoom)
                //                = center + r · (2 − α·r²) · zoom
                // 屏幕像素 → 采样坐标是正向的；之前写成「反解 α·s³ − s + t = 0」是错的
                //（那个方程在 AOD 的 α=40.9 下经常无解 / 牛顿发散）。
                val f = 2f - alphaD * (dx * dx + dy * dy)
                val sux = cx + dx * f * zoom
                val suy = cy + dy * f * zoom
                // 双线性：GL 侧是 `GL_LINEAR` + mipmap。原来用 `toInt()`（最近邻），
                // 预览的球内会多出一层硬锯齿，和实渲染对不上（2026-11-09）。
                val fx = (sux * w - 0.5f).coerceIn(0f, w - 1.001f)
                val fy = (suy / aspect * h - 0.5f).coerceIn(0f, h - 1.001f)
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val tx = fx - x0
                val ty = fy - y0
                val x1 = if (x0 + 1 < w) x0 + 1 else x0
                val y1 = if (y0 + 1 < h) y0 + 1 else y0
                val c00 = srcPx[y0 * w + x0]
                val c10 = srcPx[y0 * w + x1]
                val c01 = srcPx[y1 * w + x0]
                val c11 = srcPx[y1 * w + x1]
                val w00 = (1f - tx) * (1f - ty)
                val w10 = tx * (1f - ty)
                val w01 = (1f - tx) * ty
                val w11 = tx * ty
                var cr = (c00 shr 16 and 0xFF) * w00 + (c10 shr 16 and 0xFF) * w10 +
                    (c01 shr 16 and 0xFF) * w01 + (c11 shr 16 and 0xFF) * w11
                var cg = (c00 shr 8 and 0xFF) * w00 + (c10 shr 8 and 0xFF) * w10 +
                    (c01 shr 8 and 0xFF) * w01 + (c11 shr 8 and 0xFF) * w11
                var cb = (c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                    (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11

                // 装饰：**先提饱和、再压暗**，与 `LENS_FRAG` 同序同参数。
                // ⚠️ p.y 必须取反 —— shader 是 `center.y - uv.y`，原写法用 `dy`（向下为正）
                //    符号相反，球面明暗会上下颠倒（2026-11-09 对齐）。
                val a = decorAlpha(dx / radius, -dy / radius)
                if (a > 0.0001f) {
                    // increaseSaturation(color, uDecorAlpha * 0.5)，AOD 的 uDecorAlpha = 1
                    val sat = AOD_DECOR * 0.5f
                    val lum = 0.2125f * cr + 0.7154f * cg + 0.0721f * cb
                    val k = 1f + sat
                    cr = (cr * k - lum * sat).coerceIn(0f, 255f)
                    cg = (cg * k - lum * sat).coerceIn(0f, 255f)
                    cb = (cb * k - lum * sat).coerceIn(0f, 255f)
                }
                val dim = 1f - a

                // 圆边 smoothstep 混到黑
                val edge = if (dist <= radius - 0.002f) 1f else {
                    val x = ((dist - (radius - 0.002f)) / 0.002f).coerceIn(0f, 1f)
                    x * x * (3f - 2f * x)                    // smoothstep
                }
                val cover = (1f - edge)

                val r = (cr * dim * (1f - cover)).toInt().coerceIn(0, 255)
                val g = (cg * dim * (1f - cover)).toInt().coerceIn(0, 255)
                val b = (cb * dim * (1f - cover)).toInt().coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        if (blurred !== src) blurred.recycle()
        result
    }.getOrNull()

    /** 与 shader 同一条 5 阶多项式（对官方 `lens_decorator.png` 的 alpha 拟合）。 */
    /**
     * 留光样式的 AOD 预览：**一张相片**（不是球）。
     *
     * 几何全部照 000 帧实测（720 坐标系 → 按 `side / 720` 缩放）：
     * 相纸 340×340 圆角 18、内容 324×300 圆角 10、白边 8/8/32、
     * 衬纸同形 + 右移 27.4 / 下移 2 / 顺转 7.13°（只露出右上一条斜楔）。
     *
     * 合成顺序与 `chromakey_photo_frag` 一致：衬纸(纸背) → 相纸白底 → 相片内容。
     * 整块再转 −5°、边长 = 屏宽 × 0.70486、中心落在屏高 0.335。
     *
     * @param src AOD 的源图（「AOD 与锁屏一致」时是锁屏图）。
     */
    fun renderLiuguang(src: Bitmap, screenW: Int, screenH: Int, outW: Int = OUT_W): Bitmap? = runCatching {
        val aspect = screenH.toFloat() / screenW
        val outH = (outW * aspect).roundToInt()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)

        val side = outW * LIUGUANG_SCALE
        val k = side / 720f

        c.save()
        c.translate(outW / 2f, outH * LIUGUANG_CY)
        c.rotate(-LIUGUANG_ANGLE)
        c.translate(-side / 2f, -side / 2f)
        drawPaperStack(c, src, k)
        c.restore()

        out
    }.getOrNull()

    /** 在 `side × side` 的坐标系里画「相纸 + 露出的衬纸」。`k` = 720 → side 的缩放。 */
    private fun drawPaperStack(c: Canvas, src: Bitmap, k: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tmp = ArrayList<Bitmap>(2)

        // ── 1. 衬纸（先画，后面会被相纸盖住大部分）
        val bHalf = 170f * k
        val bCx = 360f * k + BACKER_DX * k
        val bCy = 360f * k + BACKER_DY * k
        val bR = 18f * k
        val bRect = RectF(bCx - bHalf, bCy - bHalf, bCx + bHalf, bCy + bHalf)
        c.save()
        c.rotate(BACKER_ROT, bCx, bCy)
        // 外沿 8px：亮度 147（实测比相纸那圈 245 暗一档，这是"两张纸"的来源）
        paint.color = Color.rgb(147, 147, 147)
        c.drawRoundRect(bRect, bR, bR, paint)
        // 内缩 8px = 纸背（同一张照片，稍压暗）
        val bIn = RectF(bRect)
        bIn.inset(8f * k, 8f * k)
        val bPath = Path().apply { addRoundRect(bIn, 10f * k, 10f * k, Path.Direction.CW) }
        c.save()
        c.clipPath(bPath)
        val back = coverCrop(src, bIn.width().roundToInt().coerceAtLeast(1),
            bIn.height().roundToInt().coerceAtLeast(1))
        tmp.add(back)
        c.drawBitmap(back, null, bIn, paint)
        paint.color = Color.argb(70, 0, 0, 0)
        c.drawRect(bIn, paint)
        c.restore()
        c.restore()

        // ── 2. 相纸白底（190~530 = 340×340，圆角 18）
        val fRect = RectF(190f * k, 190f * k, 530f * k, 530f * k)
        paint.color = Color.WHITE
        c.drawRoundRect(fRect, 18f * k, 18f * k, paint)

        // ── 3. 相片内容（198~522 × 198~498 = 324×300，圆角 10）
        val cRect = RectF(198f * k, 198f * k, 522f * k, 498f * k)
        val cPath = Path().apply { addRoundRect(cRect, 10f * k, 10f * k, Path.Direction.CW) }
        c.save()
        c.clipPath(cPath)
        val content = coverCrop(
            src,
            cRect.width().roundToInt().coerceAtLeast(1),
            cRect.height().roundToInt().coerceAtLeast(1),
        )
        tmp.add(content)
        c.drawBitmap(content, null, cRect, paint)
        c.restore()

        tmp.forEach { if (it !== src) it.recycle() }
    }

    private fun decorAlpha(u: Float, v: Float): Float {
        val u2 = u * u; val u3 = u2 * u; val u4 = u3 * u; val u5 = u4 * u
        val v2 = v * v; val v3 = v2 * v; val v4 = v3 * v; val v5 = v4 * v
        val s = 0.17079832f +
            0.38000503f * v + 0.27049240f * v2 - 0.02328848f * v3 -
            0.08169705f * v4 - 0.01471029f * v5 -
            0.37975070f * u - 0.16269897f * u * v + 0.23632210f * u * v2 +
            0.13708094f * u * v3 - 0.07022234f * u * v4 +
            0.27037001f * u2 - 0.23601462f * u2 * v - 0.06374525f * u2 * v2 +
            0.04455454f * u2 * v3 +
            0.02329872f * u3 + 0.13719989f * u3 * v - 0.04486833f * u3 * v2 -
            0.08159617f * u4 + 0.07015046f * u4 * v +
            0.01459191f * u5
        return s.coerceIn(0f, 0.8f)
    }

    /** 居中 cover 裁剪到目标尺寸。 */
    private fun coverCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val target = w.toFloat() / h
        val source = sw.toFloat() / sh
        val (cw, ch) = if (source > target) {
            (sh * target).roundToInt().coerceAtMost(sw) to sh
        } else {
            sw to (sw / target).roundToInt().coerceAtMost(sh)
        }
        val x = (sw - cw) / 2
        val y = (sh - ch) / 2
        return Bitmap.createBitmap(src, x, y, cw, ch).let {
            if (it.width != w || it.height != h) Bitmap.createScaledBitmap(it, w, h, true) else it
        }
    }

    /** 可分离 box blur（三次近似高斯），原地返回新 Bitmap。 */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius < 1) return src
        var px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            px = blurH(px, w, h, radius)
            px = blurV(px, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurH(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[y * w + i.coerceIn(0, w - 1)]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[y * w + (x + r + 1).coerceIn(0, w - 1)]
                val sub = src[y * w + (x - r).coerceIn(0, w - 1)]
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    private fun blurV(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val sub = src[(y - r).coerceIn(0, h - 1) * w + x]
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }
}
