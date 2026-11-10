package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 一张 Alive 卡 = **一组三槽**（2026-11-08 从「一图」扩成「三槽」）。
 *
 * | 槽 | 用途 | 来源 |
 * |---|---|---|
 * | [lock] | 锁屏画面，同时是 AOD 层的照片源 | 用户导入 |
 * | [desktop] | 桌面画面 | 用户导入（`followLock` 时忽略） |
 * | [aod] | AOD 预览图（整屏比例，球心 y = 0.5） | 由 [lock] 算出来，自动生成 |
 *
 * 另有两条景深附属文件：[lockSubject]（AICR 抠像）与 [lockMask]（用户导入的遮罩/主体）。
 * 两者都挂在 [lock] 这一张上 —— 本轮只有锁屏有景深。
 */
internal data class AliveItem(
    val id: String,
    val dir: File,
    val lock: File?,
    val desktop: File?,
    val aod: File?,
    val followLock: Boolean,
    val lockSubject: File?,
    val lockMask: File?,
    val createdAt: Long,
    /** 息屏样式：[AliveStore.STYLE_QIHANG]（圆球）= 0，[AliveStore.STYLE_LIUGUANG]（相片）= 1。 */
    val style: Int = AliveStore.STYLE_QIHANG,
    /** 「AOD 与锁屏一致」（2026-11-09）：true = AOD 用锁屏图算，false = 用 [aodSrc]（留光专用）。 */
    val aodFollowLock: Boolean = true,
    /** AOD 自己的源图（留光 + 关一致 时才有值）。⚠️ 它不是 [aod]（那是生成的预览图）。 */
    val aodSrc: File? = null,
    /**
     * AOD 预览的**运行期递增版本号**（不写 meta、不参与持久化）。
     *
     * 为什么必须有：草稿期预览写的是固定名 `aod.png.draft`，路径永远不变 ⇒
     * UI 那份「按文件路径判定要不要重解码」的缓存会把新预览当成旧预览，
     * 换了样式也不刷新。带上版本号才能真正区分「同一路径下的新内容」。
     */
    val aodVer: Long = 0L,
) {
    /** 卡片流封面用锁屏图（桌面通常同源或没有）。 */
    val coverFile: File? get() = lock ?: desktop

    /** 实际生效的桌面图：开了「桌面与锁屏一致」就是锁屏那张。 */
    val effectiveDesktop: File? get() = if (followLock) lock else (desktop ?: lock)

    /**
     * AOD 实际用哪张图算预览。
     *
     * ⚠️ **不回落**（2026-11-09 用户确认）：关了「AOD 与锁屏一致」但还没选图时
     * 就是 `null` —— UI 显示「新建」卡、不生成预览。别拿锁屏图兜底，
     * 那会让「新建卡」和「实际画面」对不上。
     */
    val effectiveAodSrc: File? get() = if (aodFollowLock) lock else aodSrc

    /** AOD 卡能不能点（只有「留光 + 关一致」才允许给 AOD 单独选图）。 */
    val aodPickable: Boolean get() = style == AliveStore.STYLE_LIUGUANG && !aodFollowLock

    /** 三个槽都空 ⇒ 保存时整组删掉。 */
    val isEmpty: Boolean get() = lock == null && desktop == null

    /**
     * 图片版本（2026-11-09）：所有槽**文件名**拼串的哈希。
     *
     * 换图时卡的 id 不变，`AliveFlag.PROP_KEY` 判不出变化 ⇒ 渲染器不会重启 ⇒ 新图进不了 GL
     * （真机表现：「自己导入的 AOD 图无效」）。这个值随 `AliveFlag.PROP_VER` 一起推下去，
     * 轮询把它也纳入重启判据。
     */
    val imageVer: String
        get() = listOfNotNull(lock, desktop, aodSrc, aod, lockSubject, lockMask)
            .joinToString("|") { it.name }
            .hashCode().toUInt().toString(16)

    /** 有锁屏图才谈得上渲染（AOD 层与转场均以它为源）。 */
    val usable: Boolean get() = lock != null
}

/**
 * Alive 壁纸的磁盘存储。
 *
 * ## 目录即真相
 *
 * ```
 * files/alive/<id>/
 *   meta.json                 {"version":2,"id":…,"createdAt":…,
 *                              "lock":"lock_….jpg","desktop":"desk_….jpg",
 *                              "aod":"aod.png","followLock":false,
 *                              "lockSubject":"….subject.png","lockMask":"….mask.png"}
 *   lock_….jpg                锁屏原图（字节原样）
 *   desk_….jpg                桌面原图
 *   aod.png                   AOD 预览（整屏比例，含黑边）
 *   ….subject.png             锁屏景深主体（AICR 抠像）
 *   ….mask.png                锁屏遮罩 / 手导主体
 * ```
 *
 * 与 [RasterStore] 同一条理由：相册选的 `content://` 是临时授权，进程重启就失效，
 * 必须立刻把字节复制进私有目录。
 *
 * ## 判重
 *
 * 文件名里带 **SHA-256 前 16 hex**（与光栅 / 空间同口径）。同一组内重复导入同一张图，
 * 直接复用已有文件（覆盖写），不会堆出两份。
 */
internal object AliveStore {

    private const val TAG = "HX-ALIVE"
    private const val ROOT = "alive"
    private const val META = "meta.json"
    private const val META_VERSION = 3
    private const val PREFIX_LOCK = "lock_"
    private const val PREFIX_DESK = "desk_"

    /** AOD 源图（用户为留光单独选的那张）的文件前缀。 */
    private const val PREFIX_AOD = "aod_"
    private const val AOD_NAME = "aod.png"
    private const val HASH_HEX = 16

    /** 息屏样式：启航（圆球）。 */
    const val STYLE_QIHANG = 0

    /** 息屏样式：留光（相片）。 */
    const val STYLE_LIUGUANG = 1

    /** 卡片封面解码的短边（卡宽 45% × 屏宽 ≈ 540px）。 */
    const val CARD_SHORT_SIDE = 540

    /**
     * 槽位。
     *
     * ⚠️ [AOD] 只在「留光 + 关『AOD 与锁屏一致』」时才允许用户导入 ——
     * 它是 **AOD 的源图**（`aod_*`），不是生成的预览 `aod.png`。
     */
    enum class Slot { AOD, LOCK, DESKTOP }

    fun root(ctx: Context): File = File(ctx.filesDir, ROOT)

    fun list(ctx: Context, order: List<String> = emptyList()): List<AliveItem> = runCatching {
        val found = root(ctx).listFiles()
            ?.mapNotNull { if (it.isDirectory) load(it) else null }
            ?.filter { it.usable }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        if (order.isEmpty()) return@runCatching found
        val byId = found.associateBy { it.id }
        order.mapNotNull { byId[it] } + found.filterNot { it.id in order }
    }.onFailure {
        Log.w(TAG, "扫描失败：${it.message}")
    }.getOrElse { emptyList() }

    /**
     * 新建一个**空组**（三槽全空），返回它；浮页取消时调用方负责 [delete]。
     *
     * 与光栅 `RasterStore.create` 同一语义：先把目录建出来，后续导入直接往里写。
     */
    fun create(ctx: Context): AliveItem {
        val id = newId(ctx)
        val dir = File(root(ctx), id)
        dir.mkdirs()
        val item = AliveItem(
            id = id,
            dir = dir,
            lock = null,
            desktop = null,
            aod = null,
            followLock = false,
            lockSubject = null,
            lockMask = null,
            createdAt = System.currentTimeMillis(),
        )
        writeMeta(item)
        return item
    }

    fun load(dir: File): AliveItem? = runCatching {
        val meta = File(dir, META)
        if (!meta.isFile) return null
        val o = JSONObject(meta.readText())
        val createdAt = o.optLong("createdAt", dir.name.toLongOrNull() ?: 0L)
        fun slot(key: String): File? =
            o.optString(key).takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile }

        // version 1 的 `file` 不再兼容（用户 2026-11-08：老数据他自己删）。
        val lock = slot("lock")
        val desktop = slot("desktop")
        if (lock == null && desktop == null) return null
        AliveItem(
            id = o.optString("id").ifEmpty { dir.name },
            dir = dir,
            lock = lock,
            desktop = desktop,
            aod = slot("aod"),
            followLock = o.optBoolean("followLock", false),
            lockSubject = slot("lockSubject"),
            lockMask = slot("lockMask"),
            createdAt = createdAt,
            style = o.optInt("style", STYLE_QIHANG),
            aodFollowLock = o.optBoolean("aodFollowLock", true),
            aodSrc = slot("aodSrc"),
        )
    }.getOrNull()

    /**
     * 往某个槽导入一张图，返回导入后的卡（`null` = 读不到 / 不是图片）。
     *
     * 同一槽重复导入会**替换**：旧的同槽文件（`lock_*` / `desk_*`）先删掉，
     * 连带它的景深附属文件也清掉（图换了，旧主体必然对不上）。
     *
     * ⚠️ **会立刻写 meta、立刻删旧文件**。浮页的编辑一律走 [Draft]（2026-11-09），
     * 这个方法只给"不要确认的批量操作"留口子，别在 UI 里直接用。
     */
    fun import(ctx: Context, item: AliveItem, slot: Slot, uri: Uri): AliveItem? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val hash = sha256Hex(bytes).take(HASH_HEX)
        val ext = extOf(uri, bytes)
        val prefix = if (slot == Slot.LOCK) PREFIX_LOCK else PREFIX_DESK

        // 同槽旧文件全部清掉（含它的 subject / mask —— 图换了，缓存必然失效）
        item.dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix)) f.delete()
        }
        val name = "$prefix${System.currentTimeMillis()}_$hash.$ext"
        File(item.dir, name).writeBytes(bytes)

        val next = if (slot == Slot.LOCK) {
            item.copy(lock = File(item.dir, name), lockSubject = null, lockMask = null)
        } else {
            item.copy(desktop = File(item.dir, name))
        }
        writeMeta(next)
        next
    }.onFailure {
        Log.w(TAG, "导入失败：${it.message}")
    }.getOrNull()

    /** 清掉某个槽（含锁屏的景深附属）。 */
    fun clearSlot(item: AliveItem, slot: Slot): AliveItem {
        val prefix = if (slot == Slot.LOCK) PREFIX_LOCK else PREFIX_DESK
        item.dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix)) f.delete()
        }
        val next = if (slot == Slot.LOCK) {
            item.copy(lock = null, lockSubject = null, lockMask = null)
        } else {
            item.copy(desktop = null)
        }
        writeMeta(next)
        return next
    }

    fun setFollowLock(item: AliveItem, follow: Boolean): AliveItem =
        item.copy(followLock = follow).also { writeMeta(it) }

    /** AOD 预览算完后落盘（传 `null` = 清掉，锁屏图被删时用）。 */
    fun setAod(item: AliveItem, bmp: Bitmap?): AliveItem {
        val f = File(item.dir, AOD_NAME)
        if (bmp == null) {
            f.delete()
            return item.copy(aod = null).also { writeMeta(it) }
        }
        runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "AOD 预览写盘失败：${it.message}") }
        return item.copy(aod = if (f.isFile) f else null).also { writeMeta(it) }
    }

    fun setLockSubject(item: AliveItem, bmp: Bitmap?): AliveItem {
        val f = File(item.dir, "${item.lock?.name ?: "lock"}.subject.png")
        if (bmp == null) {
            f.delete()
            return item.copy(lockSubject = null).also { writeMeta(it) }
        }
        runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return item.copy(lockSubject = if (f.isFile) f else null).also { writeMeta(it) }
    }

    /** 景深主体的落盘路径（导入结果写这里，渲染侧 `/depth` 优先读它）。 */
    fun subjectFileOf(item: AliveItem): File =
        File(item.dir, "${item.lock?.name ?: "lock"}.subject.png")

    /**
     * 探测用户选的那张图是**主体**还是**遮罩**（只读，IO 线程调用）。
     *
     * 判据完全复用光栅的 `RasterStore.probeSubjectOf`（透明区域 / 是否有颜色两条规则），
     * 同时带回源尺寸与原图尺寸，供「尺寸与壁纸不一致」弹窗使用。
     */
    fun probeSubject(
        ctx: android.content.Context,
        item: AliveItem,
        uri: android.net.Uri,
    ): RasterStore.SubjectProbe? = item.lock?.let { RasterStore.probeSubjectOf(ctx, it, uri) }

    /**
     * 导入景深附属（主体 / 遮罩），照光栅 `RasterStore.applySubject`：
     *
     * * [RasterStore.SubjectKind.SUBJECT]：缩放到锁屏图尺寸后**直接存**；
     * * [RasterStore.SubjectKind.MASK]：`MaskComposer.compose` 合成成主体再存（黑→透明、白→保留）。
     *
     * 存完写回 `lockSubject` 并落 meta。返回 null = 读图失败。
     */
    fun importDepth(
        ctx: android.content.Context,
        item: AliveItem,
        uri: android.net.Uri,
        kind: RasterStore.SubjectKind,
    ): AliveItem? {
        val lock = item.lock ?: return null
        val out = subjectFileOf(item)
        if (!RasterStore.applySubjectOf(ctx, lock, uri, kind, out)) return null
        return item.copy(lockSubject = out.takeIf { it.isFile }).also { writeMeta(it) }
    }

    fun setLockMask(item: AliveItem, bmp: Bitmap?): AliveItem {
        val f = File(item.dir, "${item.lock?.name ?: "lock"}.mask.png")
        if (bmp == null) {
            f.delete()
            return item.copy(lockMask = null).also { writeMeta(it) }
        }
        runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return item.copy(lockMask = if (f.isFile) f else null).also { writeMeta(it) }
    }

    fun writeMeta(item: AliveItem) {
        runCatching {
            File(item.dir, META).writeText(
                JSONObject().apply {
                    put("version", META_VERSION)
                    put("id", item.id)
                    put("createdAt", item.createdAt)
                    item.lock?.let { put("lock", it.name) }
                    item.desktop?.let { put("desktop", it.name) }
                    item.aod?.let { put("aod", it.name) }
                    put("followLock", item.followLock)
                    item.lockSubject?.let { put("lockSubject", it.name) }
                    item.lockMask?.let { put("lockMask", it.name) }
                    put("style", item.style)
                    put("aodFollowLock", item.aodFollowLock)
                    item.aodSrc?.let { put("aodSrc", it.name) }
                }.toString(),
            )
        }.onFailure { Log.w(TAG, "写 meta 失败：${it.message}") }
    }

    fun delete(item: AliveItem) {
        runCatching { item.dir.deleteRecursively() }
            .onFailure { Log.w(TAG, "删除失败：${it.message}") }
    }

    fun deleteAll(ctx: Context): Int {
        var n = 0
        root(ctx).listFiles()?.forEach {
            if (it.isDirectory && it.deleteRecursively()) n++
        }
        return n
    }

    /** 采样解码一张图（卡片封面 / 预览用）。 */
    fun decode(file: File, shortSide: Int = CARD_SHORT_SIDE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        while (shortest / (sample * 2) >= shortSide) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun newId(ctx: Context): String {
        var id = System.currentTimeMillis().toString()
        while (File(root(ctx), id).exists()) id = (id.toLong() + 1).toString()
        return id
    }

    private fun extOf(uri: Uri, bytes: ByteArray): String {
        val fromName = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (fromName == "jpg" || fromName == "jpeg" || fromName == "png" || fromName == "webp") {
            return if (fromName == "jpeg") "jpg" else fromName
        }
        // 按魔数兜底
        if (bytes.size > 12) {
            if (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return "png"
            if (bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()) return "webp"
        }
        return "jpg"
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    // ───────────────────────────── 草稿（2026-11-09） ─────────────────────────────

    /** 草稿里「延迟落地」的文件后缀：确认时去掉，取消时删掉。 */
    private const val TMP = ".draft"

    /**
     * 编辑中的草稿：**所有改动只写文件、绝不写 `meta.json`**，被「删掉」的旧文件也留在盘上。
     *
     * 用户 2026-11-09：「alive 壁纸控件更改过，即便点左上角关闭也会被存储，应该像光栅一样
     * 更改要点击右上角确认才生效」。与光栅 `RasterGroupSheet` 的 `names` 同一语义 ——
     * 浮页里怎么改都只动草稿，点 ✓ 才 [commit]，点 ✕ 就 [rollback]。
     *
     * | 操作 | 磁盘 | 内存 |
     * |---|---|---|
     * | 导入图片 | 新名字（`lock_<ts>_<hash>.jpg`，天然不覆盖） | `added += 新文件` |
     * | 删槽位 | **不删**，只登记 | `removed += 旧文件`、字段置 null |
     * | AOD / 主体 / 遮罩 | 写 `*.draft`（不碰正式文件） | `added += tmp` |
     *
     * ⚠️ `aod.png` / `*.subject.png` / `*.mask.png` 都是**固定名**（provider 有文件名白名单
     * `^(aod\.png|(lock|desk)_…)$`，改主名就读不到），不能像导入图那样靠时间戳避让 ⇒
     * 草稿期写 `*.draft`，[commit] 时才转正。这样取消时正式文件一个字节都没动。
     */
    class Draft internal constructor(original: AliveItem) {

        var item: AliveItem = original
            private set

        /** 本次新写盘的文件（[rollback] 删、[commit] 里没被引用的也删）。 */
        private val added = LinkedHashSet<File>()

        /** 本次逻辑删除的旧文件（[commit] 才物理删）。 */
        private val removed = LinkedHashSet<File>()

        private fun own(f: File): File = f.also { added.add(it) }

        private fun drop(f: File?) {
            if (f != null) removed.add(f)
        }

        /** 导入一张图到某个槽。同槽旧图只登记，不删。 */
        fun importImage(ctx: Context, slot: Slot, uri: Uri): AliveItem? = runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val hash = sha256Hex(bytes).take(HASH_HEX)
            val ext = extOf(uri, bytes)
            val prefix = when (slot) {
                Slot.LOCK -> PREFIX_LOCK
                Slot.DESKTOP -> PREFIX_DESK
                Slot.AOD -> PREFIX_AOD
            }

            // 同槽旧文件只「逻辑删除」：登记下来，文件留在盘上等 commit
            item.dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(prefix)) drop(f)
            }
            val f = own(File(item.dir, "$prefix${System.currentTimeMillis()}_$hash.$ext"))
            f.writeBytes(bytes)

            val next = when (slot) {
                Slot.LOCK -> {
                    // 图换了，旧的景深附属与 AOD 预览必然对不上
                    drop(item.lockSubject); drop(item.lockMask); drop(item.aod)
                    item.copy(lock = f, lockSubject = null, lockMask = null, aod = null)
                }
                Slot.DESKTOP -> item.copy(desktop = f)
                // AOD 源图换了只需重算预览，跟锁屏无关
                Slot.AOD -> {
                    drop(item.aod)
                    item.copy(aodSrc = f, aod = null)
                }
            }
            item = next
            next
        }.onFailure {
            Log.w(TAG, "草稿导入失败：${it.message}")
        }.getOrNull()

        /** 清掉一个槽（含景深附属与 AOD 预览）。文件全部只登记。 */
        fun clearSlot(slot: Slot): AliveItem {
            val prefix = when (slot) {
                Slot.LOCK -> PREFIX_LOCK
                Slot.DESKTOP -> PREFIX_DESK
                Slot.AOD -> PREFIX_AOD
            }
            item.dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(prefix)) drop(f)
            }
            val next = when (slot) {
                Slot.LOCK -> {
                    drop(item.lockSubject); drop(item.lockMask)
                    // ⚠️ AOD 只有「与锁屏一致」时才是**从锁屏算出来的派生图**，那才连带删。
                    //    「留光 + 关一致」时 AOD 是用户自己选的图（走 `aodSrc`），
                    //    删锁屏不该把它一起带走（2026-11-09 用户报「删除锁屏导致 aod 被一起删除」）。
                    if (item.aodFollowLock) {
                        drop(item.aod)
                        item.copy(lock = null, lockSubject = null, lockMask = null, aod = null)
                    } else {
                        item.copy(lock = null, lockSubject = null, lockMask = null)
                    }
                }
                Slot.DESKTOP -> item.copy(desktop = null)
                Slot.AOD -> {
                    drop(item.aod)
                    item.copy(aodSrc = null, aod = null)
                }
            }
            item = next
            return next
        }

        fun setFollowLock(follow: Boolean): AliveItem =
            item.copy(followLock = follow).also { item = it }

        /**
         * 切「息屏样式」。
         *
         * ⚠️ **不清 `aod`**（2026-11-09 用户报「切换时旧的消失 → 新建页 → 新的」）：
         * 清了就立刻变成「新建」卡，而新预览还在后台算（几百毫秒），中间那一段是空的。
         * 正确做法是让**旧预览一直留着**，等 `regenerateAod` 算出新图、`setAod` 换上去时
         * 由卡片自己的 `Crossfade` 做交叉过渡。
         */
        fun setStyle(style: Int): AliveItem {
            if (item.style == style) return item
            return item.copy(style = style).also { item = it }
        }

        /** 切「AOD 与锁屏一致」。同样不清 `aod`，理由见 [setStyle]。 */
        fun setAodFollowLock(follow: Boolean): AliveItem {
            if (item.aodFollowLock == follow) return item
            return item.copy(aodFollowLock = follow).also { item = it }
        }

        /** AOD 预览：写 `aod.png.draft`，commit 时转正。 */
        fun setAod(bmp: Bitmap?): AliveItem {
            if (bmp == null) {
                drop(item.aod)
                return item.copy(aod = null, aodVer = item.aodVer + 1).also { item = it }
            }
            val tmp = own(File(item.dir, AOD_NAME + TMP))
            val ok = runCatching {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            if (!ok) {
                added.remove(tmp)
                return item
            }
            // 旧预览登记为「不再引用」，但**不物理删** —— 卡片此刻还在显示它，
            // 等新图写盘完成后由 `Crossfade` 交叉过渡（`commit` 时它会因为同名被 keep 掉）。
            drop(item.aod)
            return item.copy(aod = tmp, aodVer = item.aodVer + 1).also { item = it }
        }

        /** 景深主体：写 `*.subject.png.draft`，commit 时转正。 */
        fun setLockSubject(bmp: Bitmap?): AliveItem {
            if (bmp == null) {
                drop(item.lockSubject)
                return item.copy(lockSubject = null).also { item = it }
            }
            val name = "${item.lock?.name ?: "lock"}.subject.png"
            val tmp = own(File(item.dir, name + TMP))
            val ok = runCatching {
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            if (!ok) {
                added.remove(tmp)
                return item
            }
            drop(item.lockSubject)
            return item.copy(lockSubject = tmp).also { item = it }
        }

        /** 导入景深附属（主体 / 遮罩），同样先写 `.draft`。 */
        fun importDepth(ctx: Context, uri: Uri, kind: RasterStore.SubjectKind): AliveItem? {
            val lock = item.lock ?: return null
            val tmp = File(item.dir, "${lock.name}.subject.png$TMP")
            if (!RasterStore.applySubjectOf(ctx, lock, uri, kind, tmp)) {
                runCatching { tmp.delete() }
                return null
            }
            added.add(tmp)
            drop(item.lockSubject)
            return item.copy(lockSubject = tmp).also { item = it }
        }

        /**
         * 确认：`.draft` 转正 → 写 meta → 删掉不再被引用的文件。
         *
         * 顺序不能反 —— 先写 meta 再删，万一删失败也不会让 meta 指向已删文件。
         */
        fun commit() {
            item = item.copy(
                aod = promote(item.aod),
                lockSubject = promote(item.lockSubject),
                lockMask = promote(item.lockMask),
            )
            writeMeta(item)
            Log.i(
                TAG,
                "commit: id=${item.id} style=${item.style} lock=${item.lock?.name} " +
                    "desk=${item.desktop?.name} aod=${item.aod?.name} " +
                    "aodSrc=${item.aodSrc?.name} keep=${keep0(item)}",
            )
            // ⚠️ `aodSrc` 必须进 keep：漏了就会被当成"不再被引用"的孤儿删掉，
            //    表现为「选了 AOD 图、点 ✓ 之后就没了」。
            val keep = setOfNotNull(
                item.lock, item.desktop, item.aod, item.aodSrc, item.lockSubject, item.lockMask,
            )
            (removed + added).filterNot { it in keep }.forEach { f ->
                runCatching { f.delete() }
            }
            added.clear()
            removed.clear()
        }

        private fun keep0(i: AliveItem): String = listOfNotNull(
            i.lock, i.desktop, i.aod, i.aodSrc, i.lockSubject, i.lockMask,
        ).joinToString(",") { it.name }

        /** 取消：只删本次新写的，正式文件一个没动。 */
        fun rollback() {
            added.forEach { f -> runCatching { f.delete() } }
            added.clear()
            removed.clear()
        }

        /**
         * `.draft` 转正：先复制到正式名再删临时文件 —— 不用 `renameTo` 是因为它在某些
         * 文件系统上会静默失败，而 meta 里一旦写进 `.draft` 名字，provider 白名单会拒绝。
         */
        private fun promote(f: File?): File? {
            if (f == null || !f.name.endsWith(TMP)) return f
            val dst = File(f.parentFile, f.name.removeSuffix(TMP))
            val ok = runCatching {
                f.copyTo(dst, overwrite = true)
                f.delete()
            }.isSuccess && dst.isFile
            if (!ok) return f
            added.remove(f)
            return dst
        }
    }
}
