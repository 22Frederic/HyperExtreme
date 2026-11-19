package com.frederic22.hyperextreme.aicr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 广播通道的**文件交换站**（2026-11-19，issue #4 续）。
 *
 * ## 为什么不能用 fd
 *
 * 第一版把 `ParcelFileDescriptor` 直接塞进广播的 extras，真机立刻抛：
 *
 * ```
 * java.lang.RuntimeException: Not allowed to write file descriptors here
 * ```
 *
 * 来源是 `Parcel.writeFileDescriptor` —— `ContextImpl.sendBroadcast` 会先走
 * `Intent.prepareToLeaveProcess()`，那里对 extras 调了 `Bundle.setAllowFds(false)`。
 * 这是 framework 的硬限制，**与设备无关**，任何机器上广播都带不了 fd。
 *
 * ## 改成什么
 *
 * 广播里只放**字符串 Uri**（`content://`），配 `FLAG_GRANT_READ/WRITE_URI_PERMISSION`
 * 让接收方（`com.xiaomi.aicr` 的 uid）临时拿到这一个文件的读写权，它自己用
 * `ContentResolver.openFileDescriptor` 打开。
 *
 * ```text
 * App   → 把待处理图复制到 cacheDir/aicr/<token>_in.png
 *       → 广播 extras: in_uri / out_uri / ResultReceiver
 * AICR  → openFileDescriptor(in_uri, "r")  openOutputStream(out_uri, "wt")
 *       → 抠图 → 写 PNG → 关流 → receiver.send(OK)
 * App   → 读 out 文件解码
 * ```
 *
 * ## 只暴露一个目录
 *
 * `openFile` 只认 `cacheDir/aicr/` 下的文件名（`[A-Za-z0-9_.-]{1,80}`），
 * 不接受路径分隔符或 `..`，避免把整个私有目录暴露出去。
 */
class AicrFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!NAME_RE.matches(name)) return null
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, name)
        // parseMode("r"/"w"/"wt") → MODE_READ_ONLY / MODE_WRITE_ONLY|CREATE|TRUNCATE …
        return runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode)) }
            .getOrNull()
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** `cacheDir/aicr` —— 与 [AicrVision] 的复制目标保持一致。 */
        const val DIR = "aicr"

        /** 只允许纯文件名，挡掉 `../` 与子路径。 */
        private val NAME_RE = Regex("[A-Za-z0-9_.-]{1,80}")
    }
}
