package com.frederic22.hyperextreme.aicr

import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log

/**
 * 与 `com.xiaomi.aicr` 的**线上协议**（2026-11-19 抽出）。
 *
 * 原来这套只写在 App 侧的 [AicrVision] 里；广播通道（issue #4）要在 **AICR 进程**里
 * 走同一套 transact，所以抽到这里，两边共用一份 —— 否则字段顺序/事务码迟早漂移。
 *
 * ## 两条通道
 *
 * | 通道 | 谁发起 | 走什么 |
 * | --- | --- | --- |
 * | **广播**（新，优先） | App `sendBroadcast(setPackage("com.xiaomi.aicr"))` | AICR 进程收到后**本地 bind 自己**，同包同 uid ⇒ 不受任何跨应用限制 |
 * | **binder**（旧，托底） | App 直接 `bindService` | 跨应用绑定，部分设备/系统版本解析不到 |
 *
 * 两边最终都调这里同一套：`getPluginBinder`（code 1）→ `segmentByFd`（code 5）。
 */
internal object AicrProtocol {

    const val PKG = "com.xiaomi.aicr"

    /** `AICR_ENGINE` 的实际实现组件。 */
    const val CORE_CLASS = "com.xiaomi.aicr.access.AiCrCoreService"

    /** 隐式绑定用的 action（包可见性受限，仅作托底的第二选择）。 */
    const val ACTION_ENGINE = "com.xiaomi.aicr.access.AICR_ENGINE"

    const val IFACE_CORE = "com.xiaomi.aicr.IAiCrCoreService"
    const val IFACE_SEGMENT = "com.xiaomi.aicr.plugin.IImageSegmentService"

    const val PLUGIN_VERSION = "5.0"

    const val CODE_GET_PLUGIN = 1
    const val CODE_SEGMENT_BY_FD = 5

    const val STATUS_OK = 0
    const val STATUS_DOWNLOADING = 1

    /** 首次下载的等待上限 / 轮询间隔。 */
    const val PLUGIN_WAIT_MS = 45_000L
    const val PLUGIN_POLL_MS = 1_500L

    // ── 广播通道（App → AICR 进程）──────────────────────────────
    //
    // 用 `setPackage` 定向；`MAGIC` 只是防误触，不是安全边界
    // （AICR 侧 receiver 是动态注册的，也只能靠这个区分）。

    const val ACTION_SEGMENT = "com.frederic22.hyperextreme.AICR_SEGMENT"
    const val EXTRA_MAGIC = "magic"
    const val EXTRA_IN_URI = "in_uri"
    const val EXTRA_OUT_URI = "out_uri"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ERROR = "error"
    const val MAGIC = "HX-SEG-1"

    /**
     * 文件交换 provider 的 authority（必须与 manifest 里 `AicrFileProvider` 一致）。
     *
     * ⚠️ 广播 extras **不能带 `ParcelFileDescriptor`**：`Intent.prepareToLeaveProcess()`
     * 会对 extras 调 `Bundle.setAllowFds(false)`，写入时直接抛
     * `RuntimeException: Not allowed to write file descriptors here`（2026-11-19 真机实测）。
     * 所以改成「复制到 provider 目录 + 广播传 content:// Uri + 临时授权」。
     */
    const val AUTHORITY = "com.frederic22.hyperextreme.aicr"

    /** `content://` 形式的文件 Uri（名字必须落在 provider 允许的那一层）。 */
    fun fileUri(name: String): String = "content://$AUTHORITY/$name"

    /** AICR 侧处理结果码 —— 走 `ResultReceiver` 回传（Binder，不是 fd）。 */
    const val RESULT_OK = 1
    const val RESULT_ERR = 2

    private const val TAG = "HX-AICR"

    // ──────────────────────────────────────────────────────────── transact

    /**
     * `IAiCrCoreService.getPluginBinder(callback, address, desc, minVersion, out Bundle)`。
     *
     * @return (插件 binder, Status)；Status 见 [statusText]
     */
    fun pluginBinder(core: IBinder): Pair<IBinder?, Int> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_CORE)
            data.writeStrongBinder(SilentCallback())
            data.writeString("HyperExtreme")
            data.writeString(IFACE_SEGMENT)
            data.writeString(PLUGIN_VERSION)
            core.transact(CODE_GET_PLUGIN, data, reply, 0)
            reply.readException()
            val binder = reply.readStrongBinder()
            val hasBundle = reply.readInt() != 0
            val bundle = android.os.Bundle()
            if (hasBundle) bundle.readFromParcel(reply)
            binder to bundle.getInt("Status", -1)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 取插件，**首次下载时会一直等到就绪**。
     *
     * 真机模型下好后状态恒为 0，但新设备第一次拿到的是 `Status=1`
     * （`DOWNLOADING_FIRST_USE`）—— 直接判失败会让用户看到「计算失败」，
     * 再点一次还是失败（模型没下完）。上限 [PLUGIN_WAIT_MS]。
     */
    fun awaitPlugin(core: IBinder): Pair<IBinder?, Int> {
        var last = pluginBinder(core)
        if (last.second != STATUS_DOWNLOADING) return last
        Log.i(TAG, "AI 模型首次下载中，最多等 ${PLUGIN_WAIT_MS / 1000} 秒")
        val deadline = System.currentTimeMillis() + PLUGIN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(PLUGIN_POLL_MS) }
            last = pluginBinder(core)
            Log.i(TAG, "轮询插件：Status=${last.second}")
            if (last.second != STATUS_DOWNLOADING) return last
        }
        return last
    }

    /** `IImageSegmentService.segmentByFd(pfd, VisionAttribute)` → `SegmentResult`。 */
    fun segmentByFd(plugin: IBinder, pfd: ParcelFileDescriptor): SegmentResult? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_SEGMENT)
            data.writeTypedObject(pfd, 0)
            data.writeTypedObject(
                VisionAttribute(
                    visionType = 0,
                    privacy = false,
                    // 把多个主体并成一块：光栅壁纸要的是「整个人」，不是「最大那个人」
                    extractCombined = true,
                    maxAreaAsSingle = false,
                ),
                0,
            )
            plugin.transact(CODE_SEGMENT_BY_FD, data, reply, 0)
            reply.readException()
            reply.readTypedObject(SegmentResult.CREATOR)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Status 枚举译成人话（界面/toast/日志共用一套说法）。 */
    fun statusText(status: Int): String = when (status) {
        0 -> "Status=0 就绪"
        1 -> "Status=1 AI 模型首次下载中"
        2 -> "Status=2 AI 引擎需要升级"
        // 服务端「一个字节都不写」的拒绝（空 reply）也会落到 -1。
        // AICR 4.13.8 起新增调用方签名白名单（AccessGuard），被拒时就是这种现象。
        -1 -> "Status=-1 服务未响应（新版 AI 引擎可能拒绝调用：需模块 hook 放行，" +
            "并确认 LSPosed 已勾选「小米澎湃 AI 引擎」作用域）"
        else -> "Status=$status"
    }

    // ──────────────────────────────────────────────────────────── Parcelable

    /**
     * 与 `com.xiaomi.aicr.vision.VisionAttribute` **逐字段同序**：
     * `int, bool, Bundle, bool, bool`。写错一个字段服务端就会收到垃圾。
     */
    internal class VisionAttribute(
        private val visionType: Int,
        private val privacy: Boolean,
        private val extractCombined: Boolean,
        private val maxAreaAsSingle: Boolean,
    ) : Parcelable {

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(visionType)
            dest.writeBoolean(privacy)
            dest.writeBundle(android.os.Bundle())
            dest.writeBoolean(extractCombined)
            dest.writeBoolean(maxAreaAsSingle)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<VisionAttribute> {
                override fun createFromParcel(source: Parcel): VisionAttribute {
                    val type = source.readInt()
                    val privacy = source.readBoolean()
                    source.readBundle(javaClass.classLoader)
                    val combined = source.readBoolean()
                    val maxArea = source.readBoolean()
                    return VisionAttribute(type, privacy, combined, maxArea)
                }

                override fun newArray(size: Int) = arrayOfNulls<VisionAttribute>(size)
            }
        }
    }

    /**
     * 只用于**读**服务端返回值。三个字段的顺序与 `SegmentResult.writeToParcel` 一致：
     * `writeTypedList(mBitmaps)` → `writeList(mRects)` → `writeList(mContours)`。
     */
    internal class SegmentResult(
        val bitmaps: List<Bitmap>,
    ) : Parcelable {

        override fun writeToParcel(dest: Parcel, flags: Int) = Unit

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SegmentResult> {
                override fun createFromParcel(source: Parcel): SegmentResult {
                    val list = ArrayList<Bitmap>()
                    @Suppress("DEPRECATION")
                    source.readTypedList(list, Bitmap.CREATOR)
                    val rects = ArrayList<Any?>()
                    source.readList(rects, Bitmap::class.java.classLoader)
                    val contours = ArrayList<Any?>()
                    source.readList(contours, Bitmap::class.java.classLoader)
                    return SegmentResult(list)
                }

                override fun newArray(size: Int) = arrayOfNulls<SegmentResult>(size)
            }
        }
    }

    /**
     * `ICoreServiceCallback` 的空实现。
     *
     * 服务端只在「模型首次下载」时回调 `onDownload(IBinder)` / `speed(String, long)`；
     * 我们是同步等待，不需要那些进度，**照单全收 return true** 即可。
     */
    internal class SilentCallback : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            return true
        }

        companion object {
            private const val DESCRIPTOR = "com.xiaomi.aicr.ICoreServiceCallback"
        }
    }
}
