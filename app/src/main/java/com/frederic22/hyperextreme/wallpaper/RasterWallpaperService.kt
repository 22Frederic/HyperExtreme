package com.frederic22.hyperextreme.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.frederic22.hyperextreme.data.RasterStore
import com.frederic22.hyperextreme.hook.RasterGlCore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **光栅壁纸的系统壁纸服务版**（2026-11-04）—— 免 root 的降级方案。
 *
 * ## 与 LSPosed 链的关系
 *
 * 两条链**互斥**，共用同一份图片缓存（`files/raster/<id>/`）与同一份擦除公式
 * （[RasterGlCore]）。区别只在"画到哪儿"：
 *
 * | | LSPosed 链（`hook.RasterRenderer`） | 本类 |
 * |---|---|---|
 * | 画布 | 自建 `SurfaceControl`，reparent 到系统壁纸 leash | 系统给的 `Engine` surface |
 * | 生效范围 | 锁屏 / 桌面 / 全局可分别选（挂不同的 leash） | 由系统壁纸设置页决定（桌面/锁屏/同时） |
 * | 解锁淡出 / AOD / 模糊 | 自己适配（一堆时序坑） | **白捡**，系统把它当自己的壁纸 |
 * | root | 需要（`setprop` 走 su） | **不需要** |
 *
 * ## 为什么在独立进程
 *
 * `AndroidManifest` 里给了 `android:process=":wallpaper"`：GL 纹理常驻（约 38MB）
 * 不和 Compose UI 抢；主进程跑抠图 / 推理时内存峰值很高，同进程会互相拖累。
 * 代价是配置要走文件（[RasterServiceTarget]）。
 *
 * ## 生命周期要点（与 `SpatialWallpaperService` 逐条相同，真机血泪）
 *
 * * **不可见时绝对不许 `eglSwapBuffers`** —— 系统不消费 buffer，swap 会阻塞 GL 线程，
 *   醒来后整个渲染卡死。`visible` 是硬闸。
 * * `onSurfaceDestroyed` 必须 `eglMakeCurrent(NO_SURFACE)` + `eglDestroySurface`，
 *   只 detach 不 destroy 会让下次 `eglCreateWindowSurface` 失败。
 * * `context` / 纹理跨 surface 重建保留（系统可能只是转屏/重配，不是真的销毁）。
 * * 传感器只在可见时注册（不可见 = 锁屏/全屏 app/灭屏），省电且天然对齐 AOD。
 *
 * ## 光栅特有的一条
 *
 * 服务版拿不到 hook 版的 AOD 回调，也没有"解锁那一刻"。所以基点的重设**只靠可见性**：
 * 每次重新可见都从**第一张**开始（`pan = 0`）。用户从 AOD / 锁屏回来看到的是图 0，
 * 与 hook 版"擦回第一张"的落点一致，只是没有那 350ms 的过渡（系统淡入会盖住这一刀）。
 */
class RasterWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RasterEngine()

    private inner class RasterEngine : Engine() {

        private val thread = HandlerThread("he-raster-wp").apply { start() }
        private var gl: Handler? = Handler(thread.looper)

        // ── EGL ──
        private var display: EGLDisplay? = null
        private var context: EGLContext? = null
        private var config: EGLConfig? = null
        private var surface: EGLSurface? = null

        // ── GL ──
        private var program = 0
        private var quad: FloatBuffer? = null
        private var aPos = 0
        private var aUv = 0
        private var uFromTex = 0
        private var uToTex = 0
        private var uT = 0
        private var uDir = 0
        private var uBand = 0
        private var uDim = 0
        private var uFromUv = 0
        private var uToUv = 0
        private var uStyle = 0
        private var uTransWidth = 0
        private var uAlphaTransWidth = 0
        private var uMaskTransWidth = 0
        private var uBumpScale = 0
        private var uRefractionScale = 0
        private var uDispersionFactor = 0
        private var uShadowStrength = 0
        private var uStripePeriodPx = 0
        private var uFrostCellPx = 0
        private var uScreenW = 0
        private var uScreenH = 0
        private var uLightDir = 0

        // ── 内容 ──
        private var images: List<String> = emptyList()
        private var groupDir: File? = null
        private var loadedKey: String? = null
        private var loadedStamp = 0L

        /** 3 槽纹理环：够用「上一张 / 当前 / 下一张」，反向擦除不用重传。 */
        private val slotTex = IntArray(3)
        private val slotIdx = IntArray(3) { -1 }
        private val slotUv = Array(3) { floatArrayOf(1f, 1f, 0f, 0f) }

        /** 后台预取（与 hook 版同一套策略：预取 ±2、最多留 4 张，各约 12.8MB）。 */
        private val preloaded = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
        private val preloading = java.util.Collections.synchronizedSet(HashSet<Int>())

        // ── 画布 ──
        private var screenW = 0
        private var screenH = 0
        private var surfaceReady = false
        private var visible = false
        private var destroyed = false

        // ── 姿态 ──
        private var pan = 0f
        /** 满量程角（弧度）：由 `RasterServiceTarget` 的 `panDeg` 决定，默认 25°。 */
        private var panMaxRad = RasterGlCore.PAN_MAX_RAD
        private var panDeg = 25
        /** 「停顿时就近归位」（`RasterServiceTarget` 的 `settle`，默认开）：关 = 停在两张之间不动。 */
        private var settleOnIdle = true
        /**
         * 「左右镜像展示」（`RasterServiceTarget` 的 `mirror`，默认开；2026-11-13）。
         *
         * 开 = 归位点第 1 张、两侧共用同一序列；关 = 归位点取中点、两侧各走半边。
         * 与 hook 链同一套算法（[RasterGlCore.baseOf] / [posOf] / [panOf]）。
         */
        private var mirror = true
        /** 归位点下标（0 基）：开镜像 / 不足 3 张 ⇒ 0；关且 ≥3 张 ⇒ `n / 2`。 */
        private var baseIndex = 0
        /** 「过渡效果」（`RasterServiceTarget` 的 `transition`）：0 默认 / 1 条纹 / 2 磨砂 / 3 扭曲。 */
        private var style = 0
        /** 条纹周期（屏幕像素）= screenW × 19/1200，随 surface 尺寸一起更新。 */
        private var stripePeriodPx = 0f
        /** 磨砂颗粒（屏幕像素）= screenW × 2/1200。 */
        private var frostCellPx = 0f
        private var lastMoveDir = 1f
        private var gyroRate = 0f
        private var lastAt = 0L
        private var lastMotionAt = 0L
        private var displayRot = Surface.ROTATION_0
        private var rotAt = 0L

        // 停在半擦时的收尾动画
        private var snapping = false
        private var snapFrom = 0f
        private var snapTo = 0f
        private var snapStartAt = 0L

        // 绘制簿记
        private var dirty = true
        private var lastDrawnF = -1f
        private var frames = 0

        // ── 传感器 ──
        private var sm: SensorManager? = null
        private var gyroListener: SensorEventListener? = null

        // ─────────────────────────── 生命周期 ───────────────────────────

        /** 盯住主进程写进 `filesDir` 的 target 文件（2026-11-07）。 */
        private var targetObserver: FileObserver? = null

        override fun onCreate(holder: SurfaceHolder) {
            log("Engine onCreate preview=$isPreview")
            startTargetWatch()
        }

        /**
         * 让「过渡效果 / 停顿时就近归位」**生效中改完立刻生效**（2026-11-07，免 root）。
         *
         * 以前只有 `onSurfaceCreated` / `onSurfaceChanged(resized)` / `onVisibilityChanged(true)`
         * 会调 `ensureContent()` ⇒ 锁屏一直亮着时改选项，壁纸进程根本不知道文件变了，
         * 要熄屏再亮才生效。`FileObserver` 是事件驱动的，静止时零开销；这条通道全程
         * 只读写自家 `filesDir` 的文件，**不需要 setprop、不需要 root**。
         *
         * 写入方是「先写 `NAME.tmp` 再 rename 成 `NAME`」，所以两个事件都听；哪怕触发
         * 两次也没关系，[applyRealtimeParams] 会比对值，没变就不动作。
         */
        private fun startTargetWatch() {
            if (targetObserver != null) return
            val obs = object : FileObserver(
                applicationContext.filesDir.absolutePath,
                CLOSE_WRITE or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (path != RasterServiceTarget.NAME &&
                        path != "${RasterServiceTarget.NAME}.tmp"
                    ) return
                    // FileObserver 回调在它自己的线程；所有 GL 状态只能在 GL 线程改。
                    gl?.post { runCatching { applyRealtimeParams() } }
                }
            }
            runCatching { obs.startWatching() }
            targetObserver = obs
        }

        private fun stopTargetWatch() {
            runCatching { targetObserver?.stopWatching() }
            targetObserver = null
        }

        /**
         * 只热更新「过渡效果 / 停顿归位 / 左右镜像展示」三项。
         *
         * ⚠️ `panDeg` 故意不读：分母变了会让 `t = pan/panMaxRad` 当场跳图，按用户决定
         * 角度维持「下一次可见（`ensureContent`）」才生效。所以 UI 改角度虽然也会重写
         * target、触发 FileObserver，但这里值没变 ⇒ 不归位、也不动角度。
         */
        private fun applyRealtimeParams() {
            if (!visible || destroyed) return
            val ctx = applicationContext
            val tr = RasterServiceTarget.readTransition(ctx)
            val settle = RasterServiceTarget.readSettle(ctx)
            val mirrorNow = RasterServiceTarget.readMirror(ctx)
            if (tr == style && settle == settleOnIdle && mirrorNow == mirror) return
            style = tr
            settleOnIdle = settle
            mirror = mirrorNow
            baseIndex = RasterGlCore.baseOf(images.size, mirror)
            log(
                "参数热更新：过渡效果=" + when (tr) {
                    1 -> "条纹玻璃"
                    2 -> "磨砂玻璃"
                    3 -> "条纹玻璃（扭曲变形）"
                    else -> "默认"
                } + " 停顿时就近归位=${if (settle) 1 else 0}" +
                    " 左右镜像展示=${if (mirror) 1 else 0}（归位点 #$baseIndex）",
            )
            // 用户定：改这几项时立刻硬切归位一次（此刻画面在模块手里，跳变可接受）。
            if (surfaceReady) resetBase(redraw = true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            val h = gl ?: return
            h.post {
                runCatching {
                    ensureEgl(holder)
                    ensureContent(force = false)
                    surfaceReady = true
                    if (visible) drawFrame(force = true)
                }.onFailure { log("onSurfaceCreated 失败：${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val h = gl ?: return
            h.post {
                val resized = width != screenW || height != screenH
                screenW = width
                screenH = height
                // 条纹周期按**屏宽等比**（19 px @1200）：与 hook 链同一口径，换个屏幕不会变粗。
                stripePeriodPx = width * (RasterGlCore.STRIPE_PERIOD_PX_1200 / RasterGlCore.STRIPE_BASE_W)
                frostCellPx = width * (RasterGlCore.FROST_CELL_PX_1200 / RasterGlCore.STRIPE_BASE_W)
                log("surface ${width}x$height")
                runCatching {
                    // 纹理是按旧屏幕尺寸 cover 裁好的 ⇒ 尺寸变了必须重裁，否则拉伸。
                    if (resized) {
                        dropTextures()
                        ensureContent(force = true)
                    }
                    if (surfaceReady && visible) drawFrame(force = true)
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            val h = gl ?: return
            h.post {
                surfaceReady = false
                runCatching {
                    display?.let { d ->
                        EGL14.eglMakeCurrent(
                            d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                        )
                        surface?.let { s ->
                            if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s)
                        }
                    }
                    surface = null
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            log("可见性 -> $visible")
            if (visible) {
                val h = gl ?: return
                h.post {
                    runCatching {
                        // 回到前台：配置可能变了（用户换了组 / 换了图），先对一次。
                        ensureContent(force = false)
                        // 基点 = 第一张（服务版没有 AOD 回调，靠可见性重设）。
                        resetBase(redraw = false)
                        if (surfaceReady) drawFrame(force = true)
                    }
                }
                registerSensor()
            } else {
                unregisterSensor()
                // 不可见期间不渲染；下次可见由 [resetBase] 从第一张重新开始。
                // 状态本身的清零也在 GL 线程（post 到 resetBase），这里不碰 pan。
            }
        }

        override fun onDestroy() {
            log("Engine onDestroy")
            stopTargetWatch()
            destroyed = true
            visible = false
            unregisterSensor()
            gl?.post { runCatching { releaseGl() } }
            gl = null
            runCatching { thread.quitSafely() }
        }

        // ─────────────────────────── 传感器 ───────────────────────────

        private fun registerSensor() {
            if (gyroListener != null) return
            runCatching {
                val mgr = getSystemService(SENSOR_SERVICE) as SensorManager
                val gyro = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
                    log("没有陀螺仪，静态显示")
                    return
                }
                val l = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val v = event.values
                        if (v.size < 2) return
                        // 回调在主线程：只投递，积分 / 绘制全部回 GL 线程
                        //（与 `hook.RasterRenderer` 同一做法 —— 所有姿态状态只归 GL 线程所有）。
                        val x = v[0]
                        val y = v[1]
                        val ts = event.timestamp
                        gl?.post { onGyro(x, y, ts) }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                mgr.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
                sm = mgr
                gyroListener = l
                log("gyro OK（陀螺积分，满量程 25°）")
            }.onFailure { log("传感器注册失败：${it.message}") }
        }

        private fun unregisterSensor() {
            runCatching { gyroListener?.let { sm?.unregisterListener(it) } }
            gyroListener = null
            sm = null
            lastAt = 0L
        }

        /** 屏幕旋转（横屏时陀螺要换轴）；500ms 缓存。 */
        private fun currentRotation(): Int {
            val now = SystemClock.elapsedRealtime()
            if (now - rotAt < 500L) return displayRot
            rotAt = now
            displayRot = runCatching {
                val dm = getSystemService(DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
            }.getOrNull() ?: Surface.ROTATION_0
            return displayRot
        }

        /**
         * 陀螺 → 姿态。与 `RasterRenderer.onGyro` **同一套口径**：
         * 一阶低通 → 静止门限 → 积分 → 静止 80ms 后补完这一擦。
         */
        private fun onGyro(x: Float, y: Float, ts: Long) {
            if (!visible || destroyed) return
            // ROTATION_0(竖屏)=Y；90=+X；180=−Y；270=−X（与 hook 版同一张表）。
            val w = when (currentRotation()) {
                Surface.ROTATION_90 -> x
                Surface.ROTATION_180 -> -y
                Surface.ROTATION_270 -> -x
                else -> y
            }
            if (lastAt == 0L) {
                lastAt = ts
                return
            }
            val dt = (ts - lastAt) / 1_000_000_000f
            lastAt = ts
            if (dt <= 0f || dt > 0.5f) return

            val a = dt / (dt + RasterGlCore.GYRO_TAU_S)
            gyroRate += (w - gyroRate) * a
            // 静止门限：低于它不积分 ⇒ 噪声不会累积成漂移。
            if (abs(gyroRate) < RasterGlCore.GYRO_GATE) return
            if (snapping) {
                // 正在补完这一擦时用户又明显动了 ⇒ 立刻放弃收尾，重新跟手。
                if (abs(gyroRate) > 0.35f) cancelSnap() else return
            }

            val before = pan
            val range = RasterGlCore.panRangeOf(images.size, baseIndex, panMaxRad)
            pan = (pan + gyroRate * dt).coerceIn(range.start, range.endInclusive)
            val delta = pan - before
            if (abs(delta) < 1e-9f) return
            lastMotionAt = SystemClock.elapsedRealtime()
            lastMoveDir = if (delta > 0f) 1f else -1f
            dirty = true
            requestDraw()
            scheduleIdleCheck()

            if (++frames % 60 == 0) {
                log(
                    "gyro ω=${"%.2f".format(w)} rate=${"%.2f".format(gyroRate)} " +
                        "pan=${"%.3f".format(pan)}rad f=${"%.2f".format(fValue())} rot=${currentRotation()}",
                )
            }
        }

        /** 当前连续图号（0 .. N−1）。与 hook 链**同一个函数**（开镜像 = |pan|/step；关 = base + pan/step）。 */
        private fun fValue(): Float = RasterGlCore.posOf(pan, images.size, baseIndex, panMaxRad)

        // ─────────────────────────── 收尾 / 基点 ───────────────────────────

        private fun scheduleIdleCheck() {
            gl?.removeCallbacks(idleCheck)
            gl?.postDelayed(idleCheck, RasterGlCore.SNAP_DELAY_MS)
        }

        private val idleCheck = Runnable {
            if (!visible || destroyed || snapping) return@Runnable
            // 用户关掉「停顿时就近归位」⇒ 停在两张之间，不补这一擦（2026-11-04）。
            if (!settleOnIdle) return@Runnable
            if (SystemClock.elapsedRealtime() - lastMotionAt < RasterGlCore.SNAP_DELAY_MS) return@Runnable
            val f = fValue()
            val idx = f.toInt()
            if (idx >= images.size - 1) return@Runnable
            val frac = f - idx
            if (frac < 0.02f || frac > 0.98f) return@Runnable
            // 就近：过半就擦完，不到一半就退回去。
            val target = if (frac >= 0.5f) (idx + 1).toFloat() else idx.toFloat()
            // 图号 → 累计角：开镜像要带符号（两侧共用同一序列），关镜像由 pos−base 决定。
            startSnap(
                RasterGlCore.panOf(
                    target, images.size, baseIndex, panMaxRad,
                    if (pan >= 0f) 1f else -1f,
                ),
            )
        }

        private fun startSnap(targetPan: Float) {
            snapping = true
            snapFrom = pan
            snapTo = targetPan
            snapStartAt = SystemClock.elapsedRealtime()
            gl?.post(snapTick)
        }

        private val snapTick = object : Runnable {
            override fun run() {
                if (!snapping) return
                val t = ((SystemClock.elapsedRealtime() - snapStartAt).toFloat() / RasterGlCore.SNAP_MS)
                    .coerceIn(0f, 1f)
                // ease-out cubic：收尾要"停得住"，不能匀速撞线。
                val u = 1f - t
                val e = 1f - u * u * u
                pan = snapFrom + (snapTo - snapFrom) * e
                dirty = true
                requestDraw(force = true)
                if (t >= 1f) {
                    pan = snapTo
                    snapping = false
                    dirty = true
                    requestDraw(force = true)
                    log("收尾完成：${"%.3f".format(pan)} rad")
                } else {
                    gl?.postDelayed(this, 16)
                }
            }
        }

        private fun cancelSnap() {
            if (!snapping) return
            snapping = false
            gl?.removeCallbacks(snapTick)
        }

        /** 立刻回到归位点（无过渡）：`pan = 0` 恰好对应 [baseIndex] 那张。 */
        private fun resetBase(redraw: Boolean) {
            cancelSnap()
            // 张数 / 镜像开关都可能在两次可见之间变过 ⇒ 这里兜底重算一次。
            baseIndex = RasterGlCore.baseOf(images.size, mirror)
            pan = 0f
            lastMoveDir = 1f
            lastAt = 0L
            gyroRate = 0f
            dirty = true
            if (redraw) requestDraw(force = true)
        }

        // ─────────────────────────── 请求重绘 ───────────────────────────

        private fun requestDraw(force: Boolean = false) {
            if (!visible || !surfaceReady || destroyed) return
            val now = SystemClock.elapsedRealtime()
            if (!force) {
                val f = fValue()
                if (!dirty && abs(f - lastDrawnF) < RasterGlCore.DRAW_EPS) return
            }
            val h = gl ?: return
            h.post { runCatching { drawFrame(force = force) } }
        }

        // ─────────────────────────── EGL ───────────────────────────

        private fun ensureEgl(holder: SurfaceHolder) {
            if (display == null) {
                val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(d != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
                val ver = IntArray(2)
                check(EGL14.eglInitialize(d, ver, 0, ver, 1)) { "eglInitialize 失败" }
                display = d
                val attrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                val cfgs = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                check(EGL14.eglChooseConfig(d, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) {
                    "eglChooseConfig 失败"
                }
                config = cfgs[0]
                val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                val c = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
                check(c != null && c != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
                context = c
                log("EGL 就绪")
            }
            val d = display ?: return
            surface?.let { old ->
                if (old != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(d, old)
                }
            }
            val s = EGL14.eglCreateWindowSurface(
                d, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败" }
            surface = s
            check(EGL14.eglMakeCurrent(d, s, s, context)) { "eglMakeCurrent 失败" }
            if (program == 0) buildProgram()
        }

        private fun buildProgram() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, RasterGlCore.VERT)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, RasterGlCore.FRAG)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val st = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
            check(st[0] == GLES20.GL_TRUE) { "link 失败：${GLES20.glGetProgramInfoLog(p)}" }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            program = p
            aPos = GLES20.glGetAttribLocation(p, "aPos")
            aUv = GLES20.glGetAttribLocation(p, "aUv")
            uFromTex = GLES20.glGetUniformLocation(p, "uFrom")
            uToTex = GLES20.glGetUniformLocation(p, "uTo")
            uT = GLES20.glGetUniformLocation(p, "uT")
            uDir = GLES20.glGetUniformLocation(p, "uDir")
            uBand = GLES20.glGetUniformLocation(p, "uBand")
            uDim = GLES20.glGetUniformLocation(p, "uDim")
            uFromUv = GLES20.glGetUniformLocation(p, "uFromUv")
            uToUv = GLES20.glGetUniformLocation(p, "uToUv")
            uStyle = GLES20.glGetUniformLocation(p, "uStyle")
            uTransWidth = GLES20.glGetUniformLocation(p, "uTransWidth")
            uAlphaTransWidth = GLES20.glGetUniformLocation(p, "uAlphaTransWidth")
            uMaskTransWidth = GLES20.glGetUniformLocation(p, "uMaskTransWidth")
            uBumpScale = GLES20.glGetUniformLocation(p, "uBumpScale")
            uRefractionScale = GLES20.glGetUniformLocation(p, "uRefractionScale")
            uDispersionFactor = GLES20.glGetUniformLocation(p, "uDispersionFactor")
            uShadowStrength = GLES20.glGetUniformLocation(p, "uShadowStrength")
            uStripePeriodPx = GLES20.glGetUniformLocation(p, "uStripePeriodPx")
            uFrostCellPx = GLES20.glGetUniformLocation(p, "uFrostCellPx")
            uScreenW = GLES20.glGetUniformLocation(p, "uScreenW")
            uScreenH = GLES20.glGetUniformLocation(p, "uScreenH")
            uLightDir = GLES20.glGetUniformLocation(p, "uLightDir")
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            )
            quad = ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(verts); position(0) }
            log("program 就绪")
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "shader 编译失败：${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private fun releaseGl() {
            runCatching {
                display?.let { d ->
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    surface?.let { if (it != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, it) }
                    context?.let { if (it != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, it) }
                    EGL14.eglTerminate(d)
                }
            }
            surface = null
            context = null
            display = null
            program = 0
            dropTextures()
            clearPreloaded()
        }

        // ─────────────────────────── 内容 ───────────────────────────

        /**
         * 读 [RasterServiceTarget] 的 key 并加载组内图片。
         *
         * `force=false` 时只有「key 变了」或「目标文件被重写过」才重载 —— 用户换组后
         * 系统不会通知壁纸进程，只能靠这个。
         */
        private fun ensureContent(force: Boolean) {
            val ctx = applicationContext
            // **角度独立于组内容**：先对一次（用户改了「最大幅度角度」→ 下次可见就生效），
            // 不跟组一起走下面那条"key/stamp 变了才重载"的早退 —— 否则要重传全部纹理。
            val deg = RasterServiceTarget.readPanDeg(ctx)
            if (deg != panDeg) {
                panDeg = deg
                panMaxRad = RasterGlCore.radOf(deg)
                log("最大幅度角度=${deg}°（满量程 ${"%.4f".format(panMaxRad)} rad）")
            }
            // 「停顿时就近归位」同一条通道（与角度一起写在 target 里，同一刻的配置快照）。
            val settle = RasterServiceTarget.readSettle(ctx)
            if (settle != settleOnIdle) {
                settleOnIdle = settle
                log("停顿时就近归位=${if (settle) 1 else 0}")
            }
            // 「过渡效果」同一条通道（2026-11-06）：独立于组内容。
            // 2026-11-07：改完由 FileObserver 实时生效，这里是「下次可见」的兜底重读。
            val tr = RasterServiceTarget.readTransition(ctx)
            if (tr != style) {
                style = tr
                log(
                    "过渡效果=" + when (tr) {
                        1 -> "条纹玻璃"
                        2 -> "磨砂玻璃"
                        3 -> "条纹玻璃（扭曲变形）"
                        else -> "默认"
                    },
                )
            }
            // 「左右镜像展示」（2026-11-13）：同样独立于组内容；值变了要重算归位点并落位。
            val mirrorNow = RasterServiceTarget.readMirror(ctx)
            if (mirrorNow != mirror) {
                mirror = mirrorNow
                baseIndex = RasterGlCore.baseOf(images.size, mirror)
                log("左右镜像展示=${if (mirror) 1 else 0}（归位点 #$baseIndex）")
                if (surfaceReady) runCatching { resetBase(redraw = true) }
            }
            val key = RasterServiceTarget.readKey(ctx)
            if (key == null) {
                if (loadedKey != null) {
                    log("目标被清空 → 释放纹理")
                    dropTextures()
                    images = emptyList()
                    baseIndex = 0
                    groupDir = null
                    loadedKey = null
                    loadedStamp = 0L
                }
                return
            }
            val stamp = RasterServiceTarget.stamp(ctx)
            if (!force && key == loadedKey && stamp == loadedStamp) return
            val dir = File(RasterStore.root(ctx), key)
            val group = RasterStore.load(dir)
            if (group == null || group.items.isEmpty()) {
                log("组不存在或为空：$key")
                loadedKey = key
                loadedStamp = stamp
                return
            }
            dropTextures()
            images = group.items
            groupDir = dir
            loadedKey = key
            loadedStamp = stamp
            // 张数变了 ⇒ 归位点跟着重算（关镜像时是中点，n 一变就换一张）。
            baseIndex = RasterGlCore.baseOf(images.size, mirror)
            log("组 OK key=$key imgs=${images.size} 归位点=#$baseIndex")
        }

        private fun dropTextures() {
            for (i in slotTex.indices) {
                if (slotTex[i] != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(slotTex[i]), 0) }
                slotTex[i] = 0
                slotIdx[i] = -1
                slotUv[i] = floatArrayOf(1f, 1f, 0f, 0f)
            }
            clearPreloaded()
        }

        private fun uvOf(index: Int): FloatArray {
            for (i in slotTex.indices) if (slotIdx[i] == index) return slotUv[i]
            return floatArrayOf(1f, 1f, 0f, 0f)
        }

        /**
         * 拿到某一号的纹理；没有就现解现传。
         *
         * @param keep 另一个正在用的号 —— 不许把它的槽抢掉，否则同一帧里会来回重传。
         */
        private fun textureFor(index: Int, keep: Int): Int {
            for (i in slotTex.indices) if (slotIdx[i] == index) return slotTex[i]
            var target = -1
            for (i in slotTex.indices) if (slotIdx[i] < 0) { target = i; break }
            if (target < 0) {
                for (i in slotTex.indices) if (slotIdx[i] != keep) { target = i; break }
            }
            if (target < 0) target = 0
            if (slotTex[target] != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(slotTex[target]), 0) }
                slotTex[target] = 0
                slotIdx[target] = -1
            }
            val t0 = SystemClock.elapsedRealtime()
            val bmp = preloaded.remove(index) ?: loadBitmap(index) ?: return 0
            val tex = upload(bmp)
            val bw = bmp.width
            val bh = bmp.height
            bmp.recycle()
            // cover 已在 [loadBitmap] 里按 CPU 裁好 ⇒ 纹理就是屏幕尺寸，uv 恒等。
            slotUv[target] = floatArrayOf(1f, 1f, 0f, 0f)
            slotTex[target] = tex
            slotIdx[target] = index
            log("纹理 #$index 上传完成 ${SystemClock.elapsedRealtime() - t0}ms ${bw}×$bh")
            return tex
        }

        /**
         * 读第 index 张，**在 CPU 侧就按 cover 裁好、缩到屏幕尺寸**（与 hook 版同一套做法：
         * 原图直接上纹理等于单层双线性 + 无 mipmap，缩 1.67× 会边缘发毛、转动时闪）。
         */
        private fun loadBitmap(index: Int): Bitmap? = runCatching {
            val dir = groupDir ?: return null
            val name = images.getOrNull(index) ?: return null
            val f = File(dir, name)
            if (!f.isFile) return null
            val tw = screenW
            val th = screenH
            if (tw <= 0 || th <= 0) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val sw = bounds.outWidth
            val sh = bounds.outHeight
            if (sw <= 0 || sh <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(sw, sh, tw, th)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            if (src.width == tw && src.height == th) return src

            val sAsp = tw.toFloat() / th
            val dAsp = src.width.toFloat() / src.height
            val cw: Int
            val ch: Int
            if (dAsp > sAsp) {
                ch = src.height
                cw = (src.height * sAsp).roundToInt().coerceIn(1, src.width)
            } else {
                cw = src.width
                ch = (src.width / sAsp).roundToInt().coerceIn(1, src.height)
            }
            val cx = (src.width - cw) / 2
            val cy = (src.height - ch) / 2
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                src,
                Rect(cx, cy, cx + cw, cy + ch),
                Rect(0, 0, tw, th),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            src.recycle()
            out
        }.getOrNull()

        /** 长边先降到目标的 1~2 倍之间（`inSampleSize` 只能是 2 的幂）。 */
        private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
            var s = 1
            while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
            return s
        }

        /** 后台解码第 index 张（已在缓存/在途就跳过）。只在可见时做 —— 不可见时解码纯浪费。 */
        private fun preload(index: Int) {
            if (!visible || destroyed) return
            if (index < 0 || index >= images.size) return
            if (preloaded.containsKey(index) || !preloading.add(index)) return
            Thread {
                runCatching {
                    val bmp = loadBitmap(index)
                    if (bmp != null && visible && !destroyed) putPreloaded(index, bmp) else bmp?.recycle()
                }
                preloading.remove(index)
            }.apply { isDaemon = true }.start()
        }

        /** 只保留最近 4 张（2026-11-13 与 hook 链同步：预取 ±2），多的立刻回收。 */
        private fun putPreloaded(index: Int, bmp: Bitmap) {
            preloaded[index] = bmp
            if (preloaded.size > PRELOAD_KEEP) {
                for (k in preloaded.keys.toList()) {
                    if (preloaded.size <= PRELOAD_KEEP) break
                    if (k != index) preloaded.remove(k)?.recycle()
                }
            }
        }

        private fun clearPreloaded() {
            for (b in preloaded.values) runCatching { b.recycle() }
            preloaded.clear()
            preloading.clear()
        }

        private fun upload(bmp: Bitmap): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val t = ids[0]
            if (t == 0) return 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            return t
        }

        // ─────────────────────────── 绘制 ───────────────────────────

        private fun drawFrame(force: Boolean = false) {
            if (!visible || !surfaceReady || destroyed) return
            if (program == 0 || screenW <= 0 || screenH <= 0 || images.isEmpty()) return
            val d = display ?: return
            val s = surface ?: return
            val f = fValue()
            if (!force && !dirty && abs(f - lastDrawnF) < RasterGlCore.DRAW_EPS) return
            if (!EGL14.eglMakeCurrent(d, s, s, context)) return
            drawScene()
            EGL14.eglSwapBuffers(d, s)
            dirty = false
            lastDrawnF = f
        }

        private fun drawScene() {
            val n = images.size
            if (n <= 0) return

            var fromIdx: Int
            var toIdx: Int
            var t: Float
            val f = fValue()
            var idx = f.toInt()
            if (idx > n - 1) idx = n - 1
            var frac = f - idx
            // **递增 = 图号变大的方向**：开镜像恒递增；关镜像看 pan 符号。
            // 关镜像往回翻时 f 递减，from/to 必须跟着换（与 hook 版同一条）。
            val asc = baseIndex <= 0 || pan >= 0f
            if (asc) {
                if (idx >= n - 1) frac = 0f
                fromIdx = idx
                toIdx = (idx + 1).coerceAtMost(n - 1)
                if (toIdx == fromIdx) frac = 0f
                t = frac
            } else {
                // pos 从 idx+1 走向 idx ⇒ from = idx+1（旧图）、to = idx（新图），t 由 1→0。
                fromIdx = (idx + 1).coerceAtMost(n - 1)
                toIdx = idx
                if (fromIdx == toIdx) frac = 0f
                t = 1f - frac
            }
            // 方向 = **累计角的符号**（与 hook 版同一条，不是瞬时运动方向）。
            val dir = if (pan >= 0f) 1f else -1f
            // t=0 显示 from、t=1 显示 to；极小时收成一张（两种配对都收 from）。
            if (t <= 0.0005f) {
                toIdx = fromIdx
                t = 0f
            }

            GLES20.glViewport(0, 0, screenW, screenH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aUv)
            quad?.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad?.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)

            // ⚠️ 顺序不能反：先确保纹理在位（首次会现传并算出 cover 参数），再读 uv。
            // 预取 ±2（2026-11-13：单组上限 16 张，只备 ±1 时甩快一点仍会现解 528ms）。
            // 中心取 from/to 里较小的那个（关镜像往回翻时 from = idx+1、to = idx）。
            val near = minOf(fromIdx, toIdx)
            preload(near + 1)
            preload(near + 2)
            preload(near - 1)
            preload(near - 2)
            val texF = textureFor(fromIdx, toIdx)
            val texT = if (toIdx == fromIdx) texF else textureFor(toIdx, fromIdx)
            val uvF = uvOf(fromIdx)
            val uvT = uvOf(toIdx)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texF)
            GLES20.glUniform1i(uFromTex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texT)
            GLES20.glUniform1i(uToTex, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            GLES20.glUniform1f(uT, t)
            GLES20.glUniform1f(uDir, dir)
            GLES20.glUniform4f(uFromUv, uvF[0], uvF[1], uvF[2], uvF[3])
            GLES20.glUniform4f(uToUv, uvT[0], uvT[1], uvT[2], uvT[3])
            // 系统壁纸服务链由**系统**做 AOD 压暗（setWallPaperAnimProcess），这里恒 1.0。
            GLES20.glUniform1f(uDim, 1f)
            // 「过渡效果」四档：与 hook 链同一份 shader、同一套像素口径。
            val sp = RasterGlCore.paramsOf(style)
            GLES20.glUniform1f(uStyle, style.toFloat())
            GLES20.glUniform1f(uTransWidth, RasterGlCore.VIVO_TRANS_WIDTH)
            GLES20.glUniform1f(uAlphaTransWidth, RasterGlCore.VIVO_ALPHA_TRANS_WIDTH)
            GLES20.glUniform1f(uMaskTransWidth, RasterGlCore.VIVO_MASK_TRANS_WIDTH)
            GLES20.glUniform1f(uBumpScale, sp.bump)
            GLES20.glUniform1f(uRefractionScale, sp.refract)
            GLES20.glUniform1f(uDispersionFactor, sp.dispersion)
            GLES20.glUniform1f(uShadowStrength, sp.shadow)
            GLES20.glUniform1f(uStripePeriodPx, stripePeriodPx.coerceAtLeast(1f))
            GLES20.glUniform1f(uFrostCellPx, frostCellPx.coerceAtLeast(0.5f))
            GLES20.glUniform1f(uScreenW, screenW.toFloat().coerceAtLeast(1f))
            GLES20.glUniform1f(uScreenH, screenH.toFloat().coerceAtLeast(1f))
            GLES20.glUniform3f(uLightDir, sp.lightX, RasterGlCore.TWIST_LIGHT_Y, RasterGlCore.TWIST_LIGHT_Z)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun log(msg: String) = Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "RASTER_WP"

        /** 预取缓存上限（2026-11-13：与 ±2 的预取半径配套；与 hook 链同值）。 */
        const val PRELOAD_KEEP = 4
    }
}
