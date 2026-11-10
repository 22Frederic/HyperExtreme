package com.frederic22.hyperextreme.hook

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.zip.Inflater

/**
 * 留光（leave_light）的遮罩帧序列。
 *
 * 素材：`assets/alive/masks/leave_light/NNN.rgbz` —— **zlib 压缩的 RGB888、720×720、top-down**，
 * 解压后每帧 1.5MB。61 帧合计约 441KB（自绘复刻版，比官方 960KB 还小）。
 *
 * 三个通道**都是 alpha**，不是颜色（官方 `chromakey_photo_frag` 的语义）：
 *
 * | 通道 | 语义 |
 * |---|---|
 * | G | 相片内容（恒宽 324/720，高度 300 → 720） |
 * | R | 衬纸（纸背） |
 * | B | 白边（相纸 245 / 衬纸外沿 147） |
 *
 * ## 为什么按 **2 帧一跳** 取（2026-11-09）
 *
 * 官方时间轴 120fps、483ms 走完 61 帧 ⇒ **126 帧/秒**，而屏幕只有 60fps：
 * 一半的帧根本不会被显示。单线程 zlib 解一帧（1.5MB 输出）实测 8~14ms，
 * 483ms 只能解 35~60 帧 —— 照抄 61 帧必掉队，形状与几何就错位。
 *
 * 所以 [indexOf] 把帧号**量化到偶数**（0,2,4…60，共 31 帧）：483ms / 31 ≈ 15.6ms/帧，
 * 正好 60fps 一帧一帧放，解码吞吐也刚好覆盖。视觉上与 61 帧无差别。
 *
 * ⚠️ **缓存上限 12 帧 = 18MB**，只在 AOD 期间持有。全解 61 帧要 95MB，绝不能那么做。
 */
internal object AliveMaskFrames {

    const val SIZE = 720
    const val FRAMES = 61

    /** 官方 `endMicros`（`PhotoStyle(1,...)` 的第 6 个参数）。 */
    const val END_MICROS = 483_667L

    /** 官方 `fps`，时间轴 120fps。 */
    const val FPS = 120

    /** 量化步长：只播偶数帧（见类注释）。 */
    private const val STEP = 2

    private const val DIR = "alive/masks/leave_light"
    private const val CACHE_MAX = 12
    private const val BYTES = SIZE * SIZE * 3

    private var assets: AssetManager? = null

    /** 单线程解码器：预取与按需解压共用，保证不会同时解两帧抢 CPU。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HX-AliveMask").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val lock = Any()

    /** 复用池：被 LRU 淘汰的 direct buffer 回收到这里，避免每帧 allocate 1.5MB。 */
    private val pool = ArrayDeque<ByteBuffer>(CACHE_MAX)

    /**
     * 上一帧 [peek] 交出去的 buffer。
     *
     * ⚠️ 淘汰回收时必须跳过它：GL 线程 `peek` 拿到引用后到 `glTexSubImage2D` 之间
     * 会释放锁，worker 若在此刻把这个 buffer 收回池子并复用，上传的就是别的帧。
     * 丢一个 1.5MB 的 buffer 无所谓，错一帧形状很显眼。
     */
    private var inUse: ByteBuffer? = null

    /**
     * **常驻帧**：两个端点（000 = AOD 稳态的小相片、060 = 铺满的竖幅）。
     *
     * 照 fork2 的 `MaskFrames.wanted()`：`result.add(start); result.add(end);` ——
     * 端点永远不淘汰，否则「进 AOD 的第一帧（60）」或「落地的第 0 帧」会去重新解码，
     * 真机表现就是进 AOD 闪一下黑、或 AOD 里相卡迟迟不出现。
     */
    private val pinned = intArrayOf(0, FRAMES - 1)

    /** LRU（accessOrder = true）：最近用过的 12 帧，**端点不淘汰**。 */
    private val cache = LinkedHashMap<Int, ByteBuffer>(CACHE_MAX, 0.75f, true)

    /**
     * 放一帧进缓存并做淘汰（不依赖 `removeEldestEntry` —— 它没法「跳过某个 key 继续找下一个」）。
     */
    private fun putLocked(index: Int, buf: ByteBuffer) {
        cache[index] = buf
        while (cache.size > CACHE_MAX) {
            val victim = cache.keys.firstOrNull { it != pinned[0] && it != pinned[1] } ?: break
            val v = cache.remove(victim) ?: break
            // ⚠️ 正在被 GL 上传的那帧不能回收复用（见 [inUse]）
            if (v !== inUse && pool.size < CACHE_MAX) pool.addLast(v)
        }
    }

    /** 已经排队/正在解压的帧号，避免同一帧被重复提交。 */
    private val pending = HashSet<Int>()

    /** 解码用的暂存（单线程 worker 独占，不参与同步）。 */
    private var scratch: ByteArray? = null

    /** 诊断：本次 AOD 会话成功解出的帧数。 */
    @Volatile
    var decodedCount = 0
        private set

    fun resetStats() {
        decodedCount = 0
    }

    /**
     * 绑定素材来源。
     *
     * ⚠️ **不能用 `ctx.assets`**：渲染器跑在 SystemUI 进程，`ctx` 是 SystemUI 的 Context，
     * 它的 assets 里没有我们的素材。模块 APK 的 assets 只能靠 `AssetManager#addAssetPath`
     * 挂进来（LSPosed 把我们的类加载进宿主进程，但资源还是各自 APK 的）。
     */
    fun attach(am: AssetManager?) {
        assets = am
    }

    fun detach() {
        synchronized(lock) {
            cache.clear()
            pool.clear()
            pending.clear()
            grayCache.clear()
        }
        scratch = null
        assets = null
    }

    /** 灰度缩略图边长（720 / 2）：给主体层做**逐帧同源**的 alpha 与裁切。 */
    const val GT = 360

    /**
     * 灰度副本（ARGB 打包，只有 R=G=B 有效），**与 RGB 缓存同生命周期**。
     *
     * 存在的理由（2026-11-09 用户口径「每一帧锁屏的裁剪区、alpha 是多少，主体层就是多少」）：
     * 相纸里"照片内容"的可见度就是 mask 的 **G 通道** —— 转场时它逐帧在变（宽恒 324/720，
     * 高 299→719，四角圆角 7→28→0）。用 bbox（外接矩形）去近似必然对不上：
     * 上下边界差几像素、四个角是方的。直接采 G 通道就一劳永逸。
     */
    private val grayCache = object : LinkedHashMap<Int, GrayFrame>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, GrayFrame>) =
            size > CACHE_MAX
    }

    /**
     * 一帧的灰度副本 + 纸背偏移。
     *
     * @param gray `GT`² 的 ARGB（R=G=B=G 通道值）
     * @param rearOff 衬纸（R 通道）中心相对内容框（G 通道）中心的偏移，**画布比例**。
     *   纸背采样减掉它之后，坐标才落回照片范围内（不减 = 被 `clamp` 压成竖带 = 条纹）。
     */
    class GrayFrame(val gray: IntArray, val rearOff: FloatArray)

    /**
     * 取一帧的灰度副本（GL 线程）。未命中且 mask 也没解好时返回 null。
     *
     * 代价：360² = 129 600 次绝对读，约 0.5ms；只在**帧号变化**时算一次（调用方保证）。
     */
    fun peekGray(index: Int): GrayFrame? {
        val i = index.coerceIn(0, FRAMES - 1)
        synchronized(lock) { grayCache[i]?.let { return it } }
        // 兜底：正常情况 worker 解码时就算好了（见 [prefetch]），只有「刚解码完就被要」
        // 这种极端时序才会走到这里。绝不阻塞太久：一帧 388k 次绝对读，约 1.5ms。
        val buf = peek(i) ?: return null
        val frame = buildGray(buf)
        synchronized(lock) { grayCache[i] = frame }
        return frame
    }

    /**
     * 生成一帧的灰度 + 纸背偏移。**主要在 worker 线程跑**（[prefetch] 解码后顺手做），
     * GL 线程只在极端兜底时才会走到这里 —— 那是 v1.22.74 卡顿的根源（每帧 3 次绝对读 ×
     * 129 600 像素全在 GL 线程，转场 31 帧又几乎每帧 miss）。
     */
    private fun buildGray(buf: ByteBuffer): GrayFrame {
        val step = SIZE / GT
        val out = IntArray(GT * GT)
        // 顺带把 G（内容）/ R（衬纸）的 bbox 量出来（同一次遍历，零额外成本）——
        // 纸背采样要减掉两者的中心差，否则坐标越界会被 clamp 压成竖带。
        var gx0 = GT; var gx1 = -1; var gy0 = GT; var gy1 = -1
        var rx0 = GT; var rx1 = -1; var ry0 = GT; var ry1 = -1
        for (y in 0 until GT) {
            var si = (y * step) * SIZE * 3 + 1
            var di = y * GT
            for (x in 0 until GT) {
                val v = buf.get(si).toInt() and 0xFF
                // ⚠️ **alpha 必须是 255**（2026-11-09：写成 `(v shl 16) or …` 时高位是 0，
                //    ARGB_8888 默认预乘 ⇒ RGB 被清成 0，`uMask` 采出来 `.r = 0`
                //    ⇒ `m = 0` ⇒ 主体整片透明 = 「主体层不显示了」）。
                out[di + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                if (v > 8) {
                    if (x < gx0) gx0 = x
                    if (x > gx1) gx1 = x
                    if (y < gy0) gy0 = y
                    if (y > gy1) gy1 = y
                }
                // si 指向 G，所以 R 在 si − 1
                if ((buf.get(si - 1).toInt() and 0xFF) > 8) {
                    if (x < rx0) rx0 = x
                    if (x > rx1) rx1 = x
                    if (y < ry0) ry0 = y
                    if (y > ry1) ry1 = y
                }
                si += 3 * step
            }
        }
        // 纸背偏移 = 衬纸中心 − 内容中心（画布比例）。任一侧量为空就不偏移（退回旧行为）。
        val rearOff = if (rx1 < 0 || gx1 < 0) {
            floatArrayOf(0f, 0f)
        } else {
            floatArrayOf(
                ((rx0 + rx1) - (gx0 + gx1)) / 2f / GT,
                ((ry0 + ry1) - (gy0 + gy1)) / 2f / GT,
            )
        }
        return GrayFrame(out, rearOff)
    }

    /**
     * 官方口径的帧号（`PhotoStyle.index`），再**量化到偶数帧**：
     *
     * | micros | 帧 |
     * |---|---|
     * | `<= 0` | 0（小相片，AOD 稳态） |
     * | `>= endMicros` | 60（铺满全屏的竖幅，锁屏/桌面态） |
     * | 否则 | `round(micros*120/1e6)` 向下取偶数 |
     *
     * ⚠️ 两端**不是同一帧**：`micros = 0` 是第 0 帧，`micros >= end` 是第 60 帧。
     * 抄 `PhotoStyle.index` 会写成两端都返回 end，结果 AOD 里显示「铺满态」。
     */
    fun indexOf(micros: Long): Int {
        if (micros <= 0L) return 0
        if (micros >= END_MICROS) return FRAMES - 1
        val i = Math.round(micros * FPS / 1_000_000f)
        return (i / STEP) * STEP
    }

    /**
     * 量一帧遮罩的**相片内容框**（G 通道 > 8 的范围），返回画布坐标 0..1 的
     * `[left, top, right, bottom]`；整帧全空返回 null。
     *
     * 2026-11-09（用户报「主体层没参与裁切、动画里有像素拉伸带」）：
     * 相纸**不是整块画布** —— 实测 61 帧里内容框**宽恒为 322/720**，
     * 高度从 298（AOD 稳态）长到 718（铺满），中心 y 从 347 移到 359。
     * 主体层如果按画布裁切，可见范围会比相纸大 **2.2 倍**（720/322），
     * 于是人像在相纸外露出一大块、还被 `clamp(cuv)` 拉成条带。
     *
     * 每 8 像素采样（90×90 = 8100 次绝对读），GL 线程**每帧只算一次**（帧号变了才算），
     * 实测 < 0.1ms。误差 ±8px（1.1% 画布），比人像边缘的抗锯齿还小。
     */
    fun boundsOf(buf: ByteBuffer): FloatArray? {
        val step = 8
        var minX = SIZE
        var maxX = -1
        var minY = SIZE
        var maxY = -1
        var y = 0
        while (y < SIZE) {
            val row = y * SIZE * 3
            var x = 0
            while (x < SIZE) {
                // G 通道（+1）= 相片内容 alpha
                if ((buf.get(row + x * 3 + 1).toInt() and 0xFF) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX < 0 || maxY < 0) return null
        val s = SIZE.toFloat()
        return floatArrayOf(
            minX / s, minY / s,
            (maxX + 1).coerceAtMost(SIZE) / s, (maxY + 1).coerceAtMost(SIZE) / s,
        )
    }

    /** 该帧号是否已经解好（GL 线程只读）。 */
    fun has(index: Int): Boolean {
        val i = index.coerceIn(0, FRAMES - 1)
        synchronized(lock) { return cache.containsKey(i) }
    }

    /**
     * 取一帧（GL 线程调用）。未命中返回 **null** 并异步解压。
     *
     * ⚠️ **不能在这里阻塞 GL 线程**；调用方拿不到就整帧不画（见 `drawLiuguang`），
     * 与官方 `MaskFrames.upload` 返回 false 时 `return` 的语义一致。
     */
    fun peek(index: Int): ByteBuffer? {
        val i = index.coerceIn(0, FRAMES - 1)
        synchronized(lock) {
            val b = cache[i]
            if (b != null) {
                inUse = b
                return b
            }
        }
        prefetch(i, 1)
        return null
    }

    /**
     * 预取 `from` 起连续 `count` 帧，`step=+1` 往后、`-1` 往前。
     *
     * ⚠️ 方向必须和播放方向一致：进 AOD 是**倒放**（60→0），预取也得分往前，
     * 否则 worker 队列里排的全是反方向用不上的帧（v1.22.56 就是这么丢掉整个相卡的）。
     */
    fun prefetch(from: Int, count: Int = 3, step: Int = 1) {
        val a = assets ?: return
        if (count <= 0 || step == 0) return
        for (k in 0 until count) {
            val i = (from + k * step).coerceIn(0, FRAMES - 1)
            val need = synchronized(lock) { !cache.containsKey(i) && pending.add(i) }
            if (!need) continue
            worker.execute {
                val buf = runCatching { decode(a, i) }.getOrNull()
                // **在解码线程顺手把灰度 + bbox 算好**（2026-11-10 修卡顿）：
                // 这段原来是 GL 线程懒生成的，转场时每帧要 3 × 129 600 次绝对读、约 1.5ms，
                // 加上 `grayCache` 只有 12 帧、31 帧的转场几乎每帧 miss ⇒ 直接吃掉帧预算。
                val gf = if (buf != null) runCatching { buildGray(buf) }.getOrNull() else null
                synchronized(lock) {
                    pending.remove(i)
                    if (buf != null) {
                        putLocked(i, buf)
                        if (gf != null) grayCache[i] = gf
                        decodedCount++
                    }
                }
            }
        }
    }

    /** 同步解一帧（**只能在 IO 线程**）。 */
    fun decodeSync(index: Int): ByteBuffer? {
        val a = assets ?: return null
        val i = index.coerceIn(0, FRAMES - 1)
        synchronized(lock) { cache[i]?.let { return it } }
        val buf = runCatching { decode(a, i) }.getOrNull() ?: return null
        synchronized(lock) { putLocked(i, buf) }
        return buf
    }

    private fun decode(a: AssetManager, index: Int): ByteBuffer {
        val name = "$DIR/" + index.toString().padStart(3, '0') + ".rgbz"
        val comp = a.open(name, AssetManager.ACCESS_STREAMING).use { it.readBytes() }
        val out = scratch ?: ByteArray(BYTES).also { scratch = it }
        val inflater = Inflater()
        try {
            inflater.setInput(comp)
            var off = 0
            while (off < BYTES && !inflater.finished()) {
                val n = inflater.inflate(out, off, BYTES - off)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                off += n
            }
        } finally {
            inflater.end()
        }
        // DirectByteBuffer：`glTexSubImage2D` 直接吃它，不用再拷一次
        val buf = synchronized(lock) { pool.removeLastOrNull() } ?: ByteBuffer.allocateDirect(BYTES)
        buf.clear()
        buf.order(ByteOrder.nativeOrder())
        buf.put(out)
        buf.position(0)
        return buf
    }
}
