package com.frederic22.hyperextreme.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import com.frederic22.hyperextreme.data.MaskPrefs

/**
 * 选图结果的**三态**（2026-11-26）。
 *
 * 为什么要跟 `Uri?` 区分：只有 `RESULT_OK` 但拿不到任何 uri 才算「组件坏了」，
 * 用户按返回键（`RESULT_CANCELED`）属于正常取消 —— 不提示、也不切换调用方式。
 * 合成一个 null/空列表就分不出来了。
 */
sealed interface PickResult {
    data class Picked(val uris: List<Uri>) : PickResult

    /** 用户主动返回 / 没选就退出。 */
    data object Cancelled : PickResult

    /** 点了确认，但 `data` 与 `clipData` 都是空的 —— 这条通道坏了。 */
    data object Empty : PickResult
}

/**
 * 统一的选图入口（2026-11-26）——替换掉全部 11 处 `PickVisualMedia` / `PickMultipleVisualMedia`。
 *
 * ## 为什么不用 androidx 的那两个 contract
 *
 * HyperOS 4 / Android 17 的部分机器上，`ACTION_PICK_IMAGES` 由系统
 * `com.android.photopicker` 接管后**不回结果**（`intent.data` 为 null），
 * 而 `PickVisualMedia.parseResult` 只读 `data`，于是「选完什么都没有、还一声不响」。
 * 本机（小米 14 / OS4.0.0.13）的 photopicker 版本恰好正常，所以复现不了 —— 但换台机器就中招。
 *
 * ## 两种调用方式（设置 →「行为」→「图片选择调用方式」）
 *
 * - [MaskPrefs.IMAGE_PICK_PHOTO]：`ACTION_PICK_IMAGES`（系统相册组件，默认；缩略图与筛选体验好）
 * - [MaskPrefs.IMAGE_PICK_FILE]：`ACTION_OPEN_DOCUMENT`（系统文件管理组件，能兜住所有坏掉的机型）
 *
 * 空结果时 [consume] 会把模式**持久**改成文件管理组件（等效用户手动改），并 toast 一句。
 *
 * ## input 仍然用 `PickVisualMediaRequest`
 *
 * 这样 11 处 `launch(PickVisualMediaRequest(ImageOnly))` 一行都不用动，只换 contract 类型。
 */
internal object ImagePick {

    /** 当前生效的调用方式（读 prefs，默认相册组件）。 */
    fun mode(ctx: Context): Int = MaskPrefs(ctx).imagePickMode

    fun setMode(ctx: Context, mode: Int) = MaskPrefs(ctx).setImagePickMode(mode)

    /**
     * 当前是否走**文件管理组件**。
     *
     * 只有这条通道会超量：DocumentsUI 没有「最大张数」的 extra（`EXTRA_ALLOW_MULTIPLE` 只是布尔），
     * 用户能选多少就多少；相册组件走 `EXTRA_PICK_IMAGES_MAX`，由 photopicker 卡死。
     * 所以「超出限制」这类提示必须先过这个判断，否则文案会在相册路径上撒谎。
     */
    fun isFileMode(ctx: Context): Boolean = mode(ctx) == MaskPrefs.IMAGE_PICK_FILE

    /** 单选。 */
    class One : ActivityResultContract<PickVisualMediaRequest, PickResult>() {
        override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
            buildIntent(context, multiple = false, maxItems = 1)

        override fun parseResult(resultCode: Int, intent: Intent?): PickResult =
            parse(resultCode, intent)
    }

    /**
     * 多选。
     *
     * `maxItems` 与原来的 `PickMultipleVisualMedia(maxItems)` 同义，但**只在相册组件下有效**：
     * 文件管理组件（`ACTION_OPEN_DOCUMENT`）无法在 intent 层限制张数，调用方需要自己截断
     * 并按 [isFileMode] 提示用户（见 `RasterGroupSheet.handlePicked`）。
     */
    class Many(private val maxItems: Int) :
        ActivityResultContract<PickVisualMediaRequest, PickResult>() {
        override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent =
            buildIntent(context, multiple = true, maxItems = maxItems)

        override fun parseResult(resultCode: Int, intent: Intent?): PickResult =
            parse(resultCode, intent)
    }

    private fun buildIntent(ctx: Context, multiple: Boolean, maxItems: Int): Intent {
        if (mode(ctx) == MaskPrefs.IMAGE_PICK_FILE) {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        // 相册组件：与 androidx `PickVisualMedia` 构造的完全一致。
        return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
            if (multiple && maxItems > 1) {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxItems)
            }
        }
    }

    /**
     * 解析结果：`data` 为主，`clipData` 兜底（部分定制 ROM 的多选只给 clipData）。
     *
     * ⚠️ 这里**只读、不写任何状态** —— 「组件坏了」这个判断要留给 [consume]（那时才有 Context，
     * 而且能区分取消）。
     */
    private fun parse(resultCode: Int, intent: Intent?): PickResult {
        if (resultCode != Activity.RESULT_OK) return PickResult.Cancelled
        val out = LinkedHashSet<Uri>()
        intent?.data?.let { out.add(it) }
        val cd = intent?.clipData
        if (cd != null) {
            for (i in 0 until cd.itemCount) {
                cd.getItemAt(i)?.uri?.let { out.add(it) }
            }
        }
        return if (out.isEmpty()) PickResult.Empty else PickResult.Picked(out.toList())
    }

    /**
     * 把 [PickResult] 收敛成「可用 uri 列表」，并处理空结果。
     *
     * - `Picked` → 原样返回；
     * - `Cancelled` → 空列表，**不提示也不切换**；
     * - `Empty` → 若当前是相册组件：持久切到文件管理组件 + toast；若已经是文件管理组件：静默。
     */
    fun consume(ctx: Context, r: PickResult): List<Uri> = when (r) {
        is PickResult.Picked -> r.uris
        PickResult.Cancelled -> emptyList()
        PickResult.Empty -> {
            if (mode(ctx) == MaskPrefs.IMAGE_PICK_PHOTO) {
                setMode(ctx, MaskPrefs.IMAGE_PICK_FILE)
                AppToast.show(ctx, "相册组件返回失败，已自动改为文件管理组件，请重新选择")
            }
            emptyList()
        }
    }
}
