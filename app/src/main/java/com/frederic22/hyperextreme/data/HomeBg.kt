package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.frederic22.hyperextreme.spatial.SpatialCrop
import java.io.File
import kotlin.math.min

/**
 * 主页「自定义背景」的那张图（2026-11-25 用户要求）。
 *
 * 只有一个槽位：`filesDir/home_bg/custom.jpg`，选新图直接覆盖。真值在磁盘上，
 * `MaskPrefs.homeBgCustom` 只存一个时间戳（0 = 没有），用来驱动界面显隐与重新解码。
 *
 * ## 为什么要压一道
 *
 * 主页是整屏壁纸（`ContentScale.Crop`），但用户相册里的原图动辄 4000×3000、10MB+：
 *  * 存原图要占一份大空间，解码还要再吃一次内存；
 *  * 主页只需要约 1200 短边（见 `HomeTab` 的 `HomeCoverShortSide`）。
 *
 * 所以选完立刻按 **短边 1600** 采样、压成 **JPEG 92** —— 比显示尺寸高一档留余量，
 * 文件通常 300~600KB。
 *
 * ## 为什么用 `ImageDecoder` 而不是 `BitmapFactory`
 *
 * 相册图普遍带 EXIF 方向，`BitmapFactory` 不认，存下来会躺倒；`ImageDecoder` 会自动
 * 按 EXIF 转正。另外必须 `ALLOCATOR_SOFTWARE` —— 硬件位图不允许 `compress()`。
 */
internal object HomeBg {

    private const val TAG = "HX-HOMEBG"
    private const val DIR = "home_bg"
    private const val NAME = "custom.jpg"

    /**
     * 存盘时的目标短边（像素）—— **必须和 [HomeCover.SHORT_SIDE] 对齐**。
     *
     * 2026-11-27 从 1600 提到 2400：主页是 1200×2670，一张 4:3 的图铺满要放大
     * `2670 / (2400/4*3)` = 1.11×（1600 时要放大 2.2×，肉眼可见的糊）。
     * 只影响新选的图；旧图不迁移。
     */
    private const val SHORT_SIDE = 2400

    /** JPEG 质量。92 在整屏壁纸上肉眼无损，体积只有原图的一小部分。 */
    private const val QUALITY = 92

    /**
     * 「显示区域」参数的存储 key（[com.frederic22.hyperextreme.spatial.SpatialCrop] 的 JSON）。
     *
     * 复用空间壁纸那套 `crop.json` 格式 —— 只有 `cx / cy / zoom` 三个数，与具体渲染公式无关；
     * 主页是**平面**口径（边界用 `SpatialCrop.clampFlat`），空间是球面口径，各读各的 key。
     */
    const val CROP_KEY = "home_bg"

    /** 自定义背景文件（可能不存在，调用方自己 `isFile` 判断）。 */
    fun customFile(ctx: Context): File = File(File(ctx.filesDir, DIR), NAME)

    /**
     * 把 [uri] 存成新的自定义背景（同步、可能几百毫秒，调用方放 IO 线程）。
     *
     * @return 成功与否；失败时旧图保持不动（先写临时文件再原子替换）。
     */
    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        // 第一趟只读尺寸，用来算 inSampleSize（2 的幂）。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw > 0 && sh > 0) {
            while (min(sw, sh) / (sample * 2) >= SHORT_SIDE) sample *= 2
        }

        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val out = customFile(ctx)
        out.parentFile?.mkdirs()
        // 先写临时文件再 rename：编码失败 / 空间不足时不会把用户原来那张图写坏。
        val tmp = File(out.parentFile, "$NAME.tmp")
        tmp.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)) { "compress 返回 false" }
        }
        bitmap.recycle()
        check(tmp.renameTo(out)) { "rename 失败" }
        // 换了图就是新构图，旧的显示区域参数作废（否则新图会带着上一张的偏移/缩放）。
        SpatialCrop.clear(ctx, CROP_KEY)
        true
    }.onFailure {
        Log.w(TAG, "保存自定义背景失败：${it.message}")
    }.getOrDefault(false)

    /** 删掉自定义背景（幂等），顺带清掉它的显示区域参数。 */
    fun clear(ctx: Context) {
        runCatching { customFile(ctx).delete() }
        SpatialCrop.clear(ctx, CROP_KEY)
    }
}
