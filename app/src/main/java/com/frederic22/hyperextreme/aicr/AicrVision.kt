package com.frederic22.hyperextreme.aicr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.ResultReceiver
import android.util.Log
import com.frederic22.hyperextreme.data.RunLog
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 小米澎湃 AI 引擎（`com.xiaomi.aicr`）的**抠图**客户端 —— 纯手写 AIDL，不依赖它家的 SDK。
 *
 * ## 为什么手写
 *
 * `com.xiaomi.aicr` 的客户端类只存在于系统应用（aod / miwallpaper）里，我们自己的 APK 拿不到。
 * 但 AIDL 的线上协议是稳定的：接口描述符 + transaction code + Parcel 布局，全部从
 * `IImageSegmentService$Stub$Proxy` / `IAiCrCoreService$Stub$Proxy` 的字节码里读出来（2026-11-03）：
 *
 * | 方法 | code | 说明 |
 * |---|---|---|
 * | `IAiCrCoreService.getPluginBinder` | 1 | 参数 (callback, tag, 接口名, 版本)，**Bundle 是 out** |
 * | `IAiCrCoreService.releaseConnect` | 2 | |
 * | `IAiCrCoreService.isSupport` | 3 | |
 * | `IImageSegmentService.segment` | 4 | Bitmap 版（大图会撞 1MB binder 限制，不用） |
 * | `IImageSegmentService.segmentByFd` | 5 | **本类用的就是这个** |
 * | `IImageSegmentService.releaseAll` | 7 | |
 *
 * `getPluginBinder` 的返回 Bundle 里 `Status` 是枚举 ordinal：`OK = 0`、
 * `DOWNLOADING_FIRST_USE = 1`（首次要下模型，约 1MB）、`NEED_UPGRADE = 2` …
 *
 * ## 参数与返回（真机实测）
 *
 * `VisionAttribute`（Parcel 顺序：int, bool, Bundle, bool, bool）：
 * `visionType / isPrivicy / params / isExtractCombinedSubject / isMaxAreaAsSingleSubject`。
 * 后两个才是真正起作用的开关：合并多个主体 / 只取面积最大的一个。
 *
 * `SegmentResult`（Parcel 顺序：`readTypedList(Bitmap)`、`readList(RectF)`、`readList(contour)`）。
 *
 * ⚠️ 服务返回的不是灰度遮罩，而是**已经抠好的 RGBA 主体**：实测 1052×1600 的图里
 * 60.7% 的像素 alpha=0，其余不透明 ⇒ `alpha` 就是遮罩。想看灰度就 [deriveMask]。
 *
 * ⚠️ `depthSegmentMainFunc` 在这版 AICR 上是空壳：服务端只打
 * `AiCrEngine_SegmentPlugin: depthSegmentMainFunc not support!` 然后返回 null（2026-11-03 实测）。
 * 所以系统给不了深度，空间壁纸继续用 DA V2。
 *
 * @author 22Frederic
 */
internal object AicrVision {

    private const val TAG = "HX-AICR"

    private const val PKG = "com.xiaomi.aicr"
    private const val ACTION = "com.xiaomi.aicr.access.AICR_ENGINE"

    /**
     * `AICR_ENGINE` 的实际实现组件（`dumpsys package com.xiaomi.aicr` 核对过：
     * `com.xiaomi.aicr/.access.AiCrCoreService`）。
     *
     * 2026-11-19（issue #4）：绑定改成**先显式、失败回退隐式**。Android 11+ 的包可见性过滤
     * 只作用于**隐式** Intent 的解析，显式 ComponentName 不走那条路 —— 用户「绑不上」的
     * 第一嫌疑就是包可见性（K80 Pro / HyperOS 4 上复现，本机同版本同系统却正常）。
     */
    private val CORE_COMPONENT = ComponentName(PKG, "com.xiaomi.aicr.access.AiCrCoreService")

    /** 广播通道等待上限：要盖过 AICR 侧「首次下载模型」的 45s 等待。 */
    private const val BROADCAST_TIMEOUT_MS = 60_000L

    /** 单次调用的结果。`mask` 是**已经归一化成灰度**的那份，`bitmap` 是服务原样返回的抠像。 */
    data class Result(
        val ok: Boolean,
        val status: Int,
        val bitmap: Bitmap?,
        val mask: Bitmap?,
        val elapsedMs: Long,
        val detail: String,
        /** 为「首次下载」等掉的毫秒数（0 = 没等）。界面用它决定要不要提示。 */
        val waitedMs: Long = 0,
        /**
         * **实际生效的通道**（2026-11-19，issue #4）：`"广播"` / `"binder"` / `""`（没走到通道就失败了）。
         *
         * 广播是主通道（AICR 进程内本地 bind 自己，绕开跨应用限制）；
         * binder 是托底。run_log 靠它区分"哪条路走通的"。
         */
        val channel: String = "",
    )

    /**
     * 抠图：以**调用方自己的 uid** bind 系统服务。
     *
     * 服务端会主动打印 `调用方 uid: 10335 nameForUID:com.frederic22.hyperextreme`，
     * 说明它是公开接受普通应用的，不是钻空子。
     */
    fun segment(ctx: Context, src: Bitmap): Result {
        val startAt = System.currentTimeMillis()
        val input = File(ctx.cacheDir, "aicr_mask_in.png")
        val scaled = downscale(src, 1600)
        runCatching {
            input.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { return fail(startAt, "写临时文件失败：$it") }
        if (scaled !== src) scaled.recycle()
        // 临时文件用完要删；原图不能删。
        return runFd(ctx, input, startAt, deleteAfter = true)
    }

    /**
     * **直接把原图文件**扔给 AICR（2026-11-04）：不解码、不缩放、不重编码。
     *
     * 服务本来就收 `ParcelFileDescriptor`（`segmentByFd`），它自己会解 ——
     * 我们多做的那次"解码 → 缩到 1600 → PNG"纯属白掉精度。抠图质量直接取决于送进去的像素，
     * 所以景深主体一律走这条。
     */
    fun segmentFile(ctx: Context, file: File): Result =
        runFd(ctx, file, System.currentTimeMillis(), deleteAfter = false)

    /**
     * 真正干活的那段：**先广播、再 binder 托底**（2026-11-19，issue #4）。
     *
     * 广播通道：App 发 `setPackage("com.xiaomi.aicr")` 的自定义广播 → AICR 进程收到后
     * **本地 bind 自己**（同包同 uid），完全绕开跨应用的包可见性 / 组件管控 / bind 限制。
     * binder 通道：原来的那条，跨应用 bind —— 部分设备上 `resolveService` 直接返回 null。
     */
    private fun runFd(ctx: Context, input: File, startAt: Long, deleteAfter: Boolean): Result {
        // ① 广播（主通道）
        val (viaBroadcast, bcError) = runBroadcast(ctx, input, startAt)
        if (viaBroadcast != null) {
            if (deleteAfter) runCatching { input.delete() }
            return viaBroadcast
        }

        // ② binder（托底）
        val conn = connect(ctx, 8000)
            ?: return fail(
                startAt,
                "广播失败（${bcError ?: "未知"}）；binder 也绑不上 $PKG（服务不存在 / 被系统限制）",
                0, "binder",
            )

        var pfd: ParcelFileDescriptor? = null
        var waited = 0L
        try {
            val core = conn.binder ?: return fail(startAt, "服务连接为空", 0, "binder")
            val waitStart = System.currentTimeMillis()
            val (plugin, status) = AicrProtocol.awaitPlugin(core)
            waited = System.currentTimeMillis() - waitStart
            Log.i(TAG, "plugin: status=$status binder=$plugin waited=${waited}ms")
            if (plugin == null) {
                return fail(startAt, "getPluginBinder 返回空（${AicrProtocol.statusText(status)}）", waited, "binder")
            }
            if (status != AicrProtocol.STATUS_OK) {
                return fail(
                    startAt,
                    if (status == AicrProtocol.STATUS_DOWNLOADING) {
                        "AI 模型首次下载超时（等了 ${waited / 1000} 秒仍未就绪）"
                    } else {
                        "插件未就绪（${AicrProtocol.statusText(status)}）"
                    },
                    waited, "binder",
                )
            }

            pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val bitmap = AicrProtocol.segmentByFd(plugin, pfd!!)?.bitmaps?.firstOrNull()
            val cost = System.currentTimeMillis() - startAt
            if (bitmap == null) {
                return fail(startAt, "服务返回空位图", waited, "binder")
            }
            val mask = deriveMask(bitmap)
            Log.i(TAG, "ok=${bitmap.width}x${bitmap.height} cost=${cost}ms")
            return Result(
                true, status, bitmap, mask, cost,
                "成功（binder 托底）：${bitmap.width}×${bitmap.height}", waited, "binder",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败", t)
            return fail(startAt, "${t.javaClass.simpleName}: ${t.message}", waited, "binder")
        } finally {
            runCatching { pfd?.close() }
            runCatching { ctx.unbindService(conn.connection) }
            if (deleteAfter) runCatching { input.delete() }
        }
    }

    /**
     * 广播通道（2026-11-19，issue #4）。
     *
     * 协议：App 把「原图 fd（读）」和「结果 pipe（写）」塞进广播 → AICR 进程收到后
     * 先写一个 [AicrProtocol.FLAG_ACCEPTED] 字节表示「已接手」，处理完再写 PNG。
     *
     * @return 成功结果；**返回 null 一律表示「这条路没走通」**，由调用方回退 binder。
     */
    /**
     * 广播通道（2026-11-19，issue #4）：App 发**有序**广播 → AICR 进程本地 bind 自己 → 抠图。
     *
     * ⚠️ 第一版把 `ParcelFileDescriptor` 塞进广播 extras，真机直接抛
     * `RuntimeException: Not allowed to write file descriptors here`
     * —— `Intent.prepareToLeaveProcess()` 会对 extras 调 `Bundle.setAllowFds(false)`，
     * 这是 framework 硬限制、与设备无关。现在改成 **ContentProvider + Uri**：
     *
     * ```
     * 输入图复制到 cacheDir/aicr/<token>_in.png
     * 广播 extras 只放 content:// 字符串 + FLAG_GRANT_READ/WRITE_URI_PERMISSION + ResultReceiver
     * AICR 自己 openFileDescriptor / openOutputStream，处理完 receiver.send(OK)
     * ```
     *
     * @return (成功结果, null) 或 (null, 失败原因) —— 后者用于回退 binder 时把两条路的
     *         原因一起写进 run_log。
     */
    private fun runBroadcast(ctx: Context, input: File, startAt: Long): Pair<Result?, String?> {
        val token = UUID.randomUUID().toString().replace("-", "")
        val dir = File(ctx.cacheDir, AicrFileProvider.DIR).apply { mkdirs() }
        val inFile = File(dir, "${token}_in.png")
        val outFile = File(dir, "${token}_out.png")

        // provider 只暴露 cacheDir/aicr/ 这一层 ⇒ 输入图必须先复制进来。
        val copied = runCatching {
            input.inputStream().use { i -> inFile.outputStream().use { o -> i.copyTo(o) } }
        }.isSuccess
        if (!copied) {
            runCatching { inFile.delete() }
            return null to "复制输入图失败"
        }

        val latch = CountDownLatch(1)
        var err: String? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == AicrProtocol.RESULT_ERR) {
                    err = resultData?.getString(AicrProtocol.EXTRA_ERROR) ?: "未说明原因"
                }
                latch.countDown()
            }
        }

        val inUri = Uri.parse(AicrProtocol.fileUri(inFile.name))
        val outUri = Uri.parse(AicrProtocol.fileUri(outFile.name))
        // 广播 flags 的 Uri 授权对**动态注册**的 receiver 不一定生效（AOSP 只在静态 receiver
        // 分支 grant），所以这里再显式授权一次 —— provider 是 exported=false +
        // grantUriPermissions=true，按单个 Uri 授权给 AICR 包，收尾时 revoke。
        runCatching {
            ctx.grantUriPermission(AicrProtocol.PKG, inUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.grantUriPermission(AicrProtocol.PKG, outUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            val intent = Intent(AicrProtocol.ACTION_SEGMENT)
                .setPackage(AicrProtocol.PKG)
                .putExtra(AicrProtocol.EXTRA_MAGIC, AicrProtocol.MAGIC)
                .putExtra(AicrProtocol.EXTRA_IN_URI, inUri.toString())
                .putExtra(AicrProtocol.EXTRA_OUT_URI, outUri.toString())
                .putExtra(AicrProtocol.EXTRA_RESULT, receiver)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            runCatching {
                // 有序广播：AICR 有 5+ 个进程都注册了接收器，第一个收到的进程
                // `abortBroadcast()` 独占，其余不再收到（否则同一个输出文件被写多遍）。
                ctx.sendOrderedBroadcast(intent, null)
            }.onFailure { t ->
                val msg = "广播发送失败：${t.javaClass.simpleName}: ${t.message}"
                report(ctx, msg)
                return null to msg
            }

            val done = runCatching {
                latch.await(BROADCAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!done) return null to "广播 ${BROADCAST_TIMEOUT_MS / 1000}s 超时（没有 AICR 进程接）"
            if (err != null) return null to "广播：$err"

            val png = runCatching { outFile.readBytes() }.getOrNull()
            if (png == null || png.isEmpty()) return null to "广播：结果文件为空"
            val bmp = runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
                ?: return null to "广播：结果解码失败"
            val mask = deriveMask(bmp)
            val cost = System.currentTimeMillis() - startAt
            Log.i(TAG, "ok(broadcast)=${bmp.width}x${bmp.height} cost=${cost}ms")
            return Result(
                true, AicrProtocol.STATUS_OK, bmp, mask, cost,
                "成功（广播通道）：${bmp.width}×${bmp.height}", 0, "广播",
            ) to null
        } finally {
            runCatching { ctx.revokeUriPermission(inUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { ctx.revokeUriPermission(outUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            runCatching { inFile.delete() }
            runCatching { outFile.delete() }
        }
    }


    // ──────────────────────────────────────────────────────────── 绑定 / 插件

    private class Conn(val connection: ServiceConnection) {
        @Volatile var binder: IBinder? = null
    }

    /** 同类失败进程内只写一次 run_log（重试多次会刷屏，500 行很快被挤掉）。 */
    private val reported = ConcurrentHashMap<String, Boolean>()

    /**
     * 绑定失败的**持久化**记录（2026-11-19，issue #4）。
     *
     * release 包的 `Log.i` 在 HyperOS 上被日志白名单拦掉 —— LSPosed 日志里看不到 App 侧
     * 任何输出，`HX-AICR` 那条 TAG 从没出现在用户日志里。唯一能带出来的通道是
     * `run_log.txt`（`/data/user/0/com.frederic22.hyperextreme/run_log.txt`，见 [RunLog]）。
     *
     * 记的内容直接对应根因：
     * * `显式/隐式 bind 抛 XxxException: …` → 组件启动被管控（链式启动那套）
     * * `两种 Intent 都 bindService=false` → 服务解析不到（包可见性 / 组件被隐藏）
     * * `… 绑定超时` → 服务拉不起来（冷启动慢 / 被冻结）
     */
    private fun report(ctx: Context, msg: String) {
        Log.w(TAG, msg)
        val key = msg.substringBefore(':').substringBefore('：')
        if (reported.putIfAbsent(key, true) != null) return
        runCatching { RunLog.append(ctx, "AICR 绑定失败", listOf(msg), ok = false) }
    }

    private fun connect(ctx: Context, timeoutMs: Long): Conn? {
        val latch = CountDownLatch(1)
        lateinit var conn: Conn
        val sc = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                conn.binder = service
                Log.i(TAG, "onServiceConnected: $name")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "onServiceDisconnected: $name")
                conn.binder = null
            }
        }
        conn = Conn(sc)

        // 2026-11-19（issue #4）：先走**显式组件**。Android 11+ 的包可见性过滤只作用于
        // 隐式 Intent 的解析，显式 ComponentName 不受影响。
        var how = "显式"
        var bound = runCatching {
            ctx.bindService(Intent().setComponent(CORE_COMPONENT), sc, Context.BIND_AUTO_CREATE)
        }.onFailure {
            report(ctx, "显式 bind 抛 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)

        // 显式失败 ⇒ 回退原来的隐式 action。**只在 bound=false 时回退**：
        // 已经 bind 成功过的 ServiceConnection 不能再用同一个实例绑第二次。
        if (!bound) {
            how = "隐式"
            bound = runCatching {
                ctx.bindService(Intent(ACTION).setPackage(PKG), sc, Context.BIND_AUTO_CREATE)
            }.onFailure {
                report(ctx, "隐式 bind 抛 ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
        }

        if (!bound) {
            report(ctx, "两种 Intent 都 bindService=false（服务解析不到）")
            return null
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            report(ctx, "$how 绑定超时 ${timeoutMs}ms")
            return null
        }
        return conn
    }

    /** @return (插件 binder, Status) */
    // ──────────────────────────────────────────────────────────── 工具

    private fun fail(startAt: Long, msg: String, waitedMs: Long = 0, channel: String = ""): Result {
        Log.w(TAG, msg)
        return Result(false, -1, null, null, System.currentTimeMillis() - startAt, msg, waitedMs, channel)
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * 服务返回的可能是**抠好的 RGBA 主体**（有 alpha），也可能是**灰度遮罩**（alpha 全 255）。
     * 这里做一次自检：只要有 1% 以上的像素带透明，就按 alpha 取；否则取亮度。
     */
    internal fun deriveMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var transparent = 0
        for (p in px) if ((p ushr 24) < 250) transparent++
        val useAlpha = transparent > px.size / 100
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val v = if (useAlpha) {
                a
            } else {
                (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            }
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Log.i(TAG, "deriveMask: ${w}x$h useAlpha=$useAlpha transparent=$transparent/${px.size}")
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
