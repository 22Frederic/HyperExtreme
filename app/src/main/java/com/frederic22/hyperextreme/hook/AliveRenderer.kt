package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Choreographer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.AliveStore
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialFlag
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Alive 壁纸「启航」的渲染器（2026-11-07）。
 *
 * ## 画什么
 *
 * 一张照片，三种态：
 * * **AOD** —— 模糊 22 → 桶形畸变 → 半径 0.22 屏宽的圆 → 球面阴影 → 圆外纯黑，照片 −40°/s 自转；
 * * **锁屏 / 桌面** —— 原图直出（cover 铺满），不放大、不模糊；
 * * 转场 —— 圆从 0.22 扩张到 1.24 铺满，同时 AOD 层与锁屏/桌面层按三界比例交叉淡入。
 *
 * 参数与曲线逐值对齐 openalive `SceneMotion.lensState()` / `lens()`；
 * 着色器是自己重写的等价实现（见 [AliveGlCore] 的类注释）。
 *
 * ## 与空间 / 光栅的关系
 *
 * 三条链**完全独立**，共用的只有思路：
 * `WindowManagerGlobal#getWallpaperByShowWhenLocked` 拿 leash、`reparent` 挂上去、
 * 锁屏 / 桌面 / 全局三档、AOD 在 shader 里乘 `uDim`（`Transaction#setColor` 在 HyperOS 会发白）。
 *
 * ## 帧驱动
 *
 * 与另外两条链**根本不同**：那边是「事件驱动 + 静止停笔」，这边 AOD 要连续出帧（自转）。
 * 所以单独一条 16/33ms 的循环，只在「转场中 / AOD 自转 / alpha 或 dim 在变」时真的画，
 * 锁屏和桌面静止时**停笔**（不画也不 swap，避免常驻耗电与 fifo 阻塞）。
 *
 * @author 22Frederic
 */
internal object AliveRenderer {

    private const val TAG = "ALIVE"
    private const val AUTHORITY = "com.frederic22.hyperextreme.provider"

    /**
     * 留光的取景框高度 / 屏高（`LIUGUANG_FRAG` 的 `uContentZoom`）。
     *
     * 官方相框有**独立取景**（`frame_crop` 的默认窗口是相纸里 320/720 的方形网格）。
     * 我们从 `photoT`（已 cover 到屏幕）取中心正方形：0.62 表示用屏幕中间 62% 高度的区域，
     * 数值越大取景越广（照片越缩）。真机按观感微调。
     */
    private const val CONTENT_ZOOM = 0.44f
    /** 转场档：0 = 每个 vsync（60fps）。留这个常量只是给日志/注释用。 */
    private const val FRAME_MS = 0L

    /**
     * AOD 自转档：25ms ≈ 40fps（用户 2026-11-08 定「转场 60 / AOD 稳态 40」）。
     *
     * 40fps 下小球每帧转 1°、运动矢量不到 2px，视觉与 60 无差；AOD 是长时间挂着显示的
     * 状态，功耗代价最大、收益最小 ⇒ 只有转场那 1 秒才跑满 vsync。
     */
    private const val AOD_FRAME_MS = 25L

    /**
     * 锁屏 → 桌面的内容交叉时长。
     *
     * **照 `alive分析/fork2` 的 `SceneMotion.blendDuration(4)` 取**（用户 2026-11-08 定
     * 「分支怎么做过过渡我们就怎么做」）：本机参数（`aod==0` / `lock==0`）下是
     * `max(l[4]=400, h[4]=500) = 500ms`。
     *
     * 一度试过 0.15s → 真机「闪一下」（单帧 166~188ms，t 从 0 直接 clamp 到 1，中间态一帧都没出）；
     * 又试过 1000ms → 观感明显偏长。500ms 是上游的原值，也是能稳定出 3~4 帧的量级。
     */
    private val CROSS_MS: Long get() = AliveGlCore.blendDuration(4)

    /**
     * 景深主体在 AOD 两端的渐变**进度**（占缩放行程的比例，用户 2026-11-08 最终口径）。
     *
     * ⚠️ 这里必须用**缩放进度**而不是墙钟时间，两个原因：
     * 1. `tracks(1/3)` 的几何轨道只有 **650ms**，而 `animDur` 被最后那条 angle 轨道的
     *    1000ms 拉长 ⇒ 按 `animDur` 算出来的窗口整个落在「缩放已经结束」之后；
     * 2. 曲线是 `COLLAPSE(0.37, 0.04, 0.04, 1)` 前快后慢（前 325ms 就缩了 81%），
     *    按时间对齐在视觉上一定偏晚。
     *
     * 按进度对齐后：`p` 从 0.5 开始淡、0.75 淡完 —— 球缩到一半时主体开始消失，
     * 缩到 3/4 时完全不见，剩下 1/4 行程球自己缩到最小。
     */
    private const val AOD_FADE_PROGRESS = 0.25f

    /**
     * AOD 退出后等多久再判「回锁屏」。
     *
     * 真机时序：`doWallpaperBlackAnim(show=true)` 早于解锁动画，那一刻 keyguard 还是
     * locked ⇒ 只能等一个窗口，窗口内收到解锁信号就走 tr=2，否则认为只是亮屏回锁屏。
     */
    private const val AOD_EXIT_GRACE_MS = 400L
    private const val FADE_MS = 380f
    private const val FADE_LATCH_MS = 800L

    /** AOD→桌面（启航）：tr=2 球扩 1000ms + 20ms 余量再换层。 */
    private const val AOD_TO_DESK_SWITCH_MS = 1020L

    /**
     * 换层时「先抬桌面份」到「压掉锁屏份」之间等多久。
     *
     * 两帧：足够让刚画的桌面份 fence signal、被 SurfaceFlinger 采信；
     * 这期间两层 alpha 都是 1、内容都是同一张桌面图，重叠看不出来。
     */
    private const val LAYER_SWAP_MS = 32L

    // ───────────────────────────── 线程 / 生命周期 ─────────────────────────────

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var runningKey: String? = null

    @Volatile
    private var runningScope = -1

    private var appCtx: Context? = null
    private var screenW = 0
    private var screenH = 0

    private class Mount(
        val locked: Boolean,
        val sc: android.view.SurfaceControl,
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
    )

    private val mounts = ArrayList<Mount>(2)
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var sharedLeash = false

    // ───────────────────────────── GL 对象 ─────────────────────────────

    /** 一个程序的 uniform 位置表（-1 = 没有这个 uniform，直接跳过）。 */
    private class Uni(val prog: Int) {
        private val map = HashMap<String, Int>()
        fun at(name: String): Int = map.getOrPut(name) { GLES20.glGetUniformLocation(prog, name) }
    }

    private var copyProg = 0
    private var lensProg = 0
    private var liuguangProg = 0
    private var blendProg = 0
    private var downProg = 0
    private var gaussProg = 0
    private var upProg = 0
    private var uCopy: Uni? = null
    private var uLens: Uni? = null
    private var uLiuguang: Uni? = null
    private var uBlend: Uni? = null
    private var uDown: Uni? = null
    private var uGauss: Uni? = null
    private var uUp: Uni? = null

    // ── 留光（2026-11-09） ──────────────────────────────────────────────
    /** 遮罩纹理（720×720 RGB，一帧一传；复用同一个纹理 ID）。 */
    private var maskTex = 0
    /** 已经传上去的帧号（-1 = 还没传）。 */
    private var maskFrame = -1
    /** 息屏样式：0 启航 / 1 留光（来自 `AliveFlag.PROP_STYLE`）。 */
    private var aodStyle = 0

    private class Target(val tex: Int, val fbo: Int)

    private var photoT: Target? = null
    /** 桌面图的 cover 裁剪靶（2026-11-08 双图）。锁屏那张仍叫 [photoT]。 */
    private var deskT: Target? = null
    private var aodT: Target? = null
    private var blurA: Target? = null
    private var blurB: Target? = null

    private var photoTex = 0
    private var photoAspect = 1f
    private var deskTex = 0
    private var deskAspect = 1f

    /**
     * 用户自己选的 AOD 图（`meta.aodSrc`，只在「留光 + 关『AOD 与锁屏一致』」时存在）。
     *
     * provider 的 `aod` 槽在「跟随锁屏」时**返回空** ⇒ 这里读到 null、纹理为 0，
     * `drawLiuguang` 回落 [photoTex]（= 锁屏图），与旧行为完全一致、不多占内存。
     */
    private var aodPhotoTex = 0

    /**
     * 锁屏图与桌面图是不是**同一份内容**。
     *
     * 相同 ⇒ 锁屏↔桌面直接落值（没有视觉变化，别白跑转场）；
     * 不同 ⇒ 解锁时跑 [CROSS_MS] 的交叉淡入淡出。
     */
    private var sameLayer = true

    private var quad: FloatBuffer? = null
    private var quadScaled: FloatBuffer? = null

    // ───────────────────────────── 状态 ─────────────────────────────

    /** 当前态：0 AOD / 1 锁屏 / 2 桌面。 */
    private var mode = 1

    private val lens = AliveGlCore.LensState()
    private val fromState = AliveGlCore.LensState()
    private val targetState = AliveGlCore.LensState()
    private var tracks: Array<AliveGlCore.Track>? = null
    private var animStart = 0L
    private var animDur = 0L

    /** 上一次转场的起点态（`prevMode == 0` = 从 AOD 出来）。 */
    private var prevMode = 1

    /**
     * 本次进 AOD 是不是**从桌面**来的（`enterAodInternal` 按 [deskShown] 判一次就存下）。
     *
     * ⚠️ 不能只靠 [prevMode]：`mode` 会被 `scrim=KEYGUARD` 的瞬态拉回 1，而屏幕上还是桌面份
     * （2026-11-09 真机日志：`AOD 进入（从=桌面 tr=3）` 之后 86ms 又来一条 `scrim=KEYGUARD ⇒ 回锁屏`）。
     * 判「主角该不该出现」必须用这个**物理起点**，不是内部态。
     */
    private var aodFromDesktop = false

    /**
     * 锁屏景深主体层的 alpha（每帧由 [computeSubjectAlpha] 算）。
     *
     * 用户 2026-11-08 定的口径：**几何与背景完全一致**，只有 alpha 在 AOD 两端各做 0.15s 渐变 ——
     * 进 AOD 用转场的**前** 0.15s 淡出，退 AOD 用转场的**后** 0.15s 淡入，
     * AOD 期间恒 0（球里没有人像）。
     */
    private var subjectAlpha = 1f

    /**
     * 主体层几何的锁（2026-11-10）。
     *
     * 启航的主体 View 现在也在 `onDraw` 里现场采样（与留光同一个数据流），
     * 而 `startAt / fromState / targetState / tracks / lens` 是 GL 线程每帧原地改的。
     * 临界区只有十几个 float + 两次状态拷贝，每帧每侧各一次。
     */
    private val subjLock = Any()

    /**
     * 主体层的采样结果（GL 线程写不进来，View 每帧填一次）。
     *
     * 启航没有 `AliveLiuguang` 那样的纯函数轨道 —— 它的几何是「转场插值 + AOD 自转」，
     * 所以采样搬进渲染器（[sampleSubject]），View 只负责在绘制那一刻调一次。
     */
    internal class SubjectSample {
        val lens = AliveGlCore.LensState()
        val matrix = FloatArray(16)
        var alpha = 1f
        var w = 0
        var h = 0
    }


    /** AOD 首帧诊断只打一次（每次进 AOD 复位）。 */
    private var aodDiagLogged = false
    private var angleFrom = -360f
    private var angleTo = -360f
    private var animating = false

    private val ratio = floatArrayOf(0f, 1f, 0f)
    private val ratioFrom = floatArrayOf(0f, 1f, 0f)
    private val ratioTo = floatArrayOf(0f, 1f, 0f)
    private var ratioStart = 0L
    private var ratioDur = 0L

    /** 三界混合是否走 EASE（上游 `blend.setInterpolator(tr==2||tr==3 ? EASE : LINEAR)`）。 */
    private var ratioEase = false

    /**
     * **桌面份**固定使用的镜头参数（= 桌面稳态）。
     *
     * 两份 SC 内容不同（2026-11-08）：锁屏份跟着全局 [lens] / [ratio] 跑转场，
     * 桌面份永远按桌面稳态画全屏桌面图 —— 否则在 AOD 期间它会被裁成 0.22 的小圆。
     */
    private val deskState = AliveGlCore.LensState()

    /** 桌面份的固定三界比值：只取 launcher。 */
    private val DESK_RATIO = floatArrayOf(0f, 0f, 1f)

    private var lastTickAt = 0L

    @Volatile
    private var aodActive = false

    /** 系统给的压暗（1 = 原亮度，AOD 时 0.5 或 0）。 */
    private var dim = 1f

    private var showingDesktop = false

    /**
     * **屏幕上是哪一份**（`true` = 桌面份的 alpha 是 1）。
     *
     * 只在**真正换 alpha** 的地方改，和 [showingDesktop] 分开维护：
     * 后者来自 `KeyguardManager.isKeyguardLocked` 这种**猜测**（亮屏广播 / `resetAlpha`），
     * 会在「已经解锁到桌面」时误报 false（2026-11-09 真机日志：21:33:44.170 一条
     * `切态 → 1（tr=5）` 把 `mode` 拉回 1，而画面一直是桌面份）。
     * 进 AOD 的起点必须按**画面**判，不能按猜测判。
     */
    private var deskShown = false

    private var currentAlpha = 1f
    private var fadeTick: Runnable? = null

    /** AOD 退出后的「回锁屏」延迟裁决（见 [onExitAod]）。 */
    private var aodExitTick: Runnable? = null

    /**
     * **解锁转场进行中**（2026-11-08）。
     *
     * AOD→桌面要跑 1000ms 的 tr=2，期间 `ACTION_SCREEN_ON` 会插进来 ——
     * 它一看 `aodActive` 已经是 false 就直接 `changeMode(1, animate = false)`，
     * 把 tr=2 硬切成 tr=5，然后 1020ms 后我们照样换层 ⇒ 用户看到的就是「硬切」。
     * 这一位在转场期间把亮屏 / `resetAlpha` 全部挡掉。
     */
    @Volatile private var unlocking = false
    private var fadeDoneAt = 0L
    private var screenReceiver: BroadcastReceiver? = null

    private var expandedScale = 1.24f

    /** 模糊链缓存对应的半径（NaN = 没缓存）。转场期间逐帧变，会自然失效。 */
    private var blurCacheKey = Float.NaN

    /** 需要重画（参数变了 / 首帧 / 状态切换）。 */
    private var dirty = true

    /**
     * 两张照片要不要重新 cover 裁到靶上。
     *
     * 照片在渲染器生命周期内**不会变**，所以只需要做一次（2026-11-08 性能修复）。
     * 之前每帧都跑两个全屏 copy pass，AOD 60fps 下每帧 130~190ms。
     */
    private var needCopy = true

    private var framePosted = false

    /** GL 线程的 Choreographer（跟随 display vsync，见 [frameCallback]）。 */
    private var choreographer: Choreographer? = null
    private var swapLock = 0
    private var swapDesk = 0
    private var swapErr = 0
    private var slowLogged = 0

    fun isRunning(): Boolean = runningKey != null

    // ───────────────────────────── 启动 ─────────────────────────────

    fun start(ctx: Context, key: String, scope: Int) {
        val h = ensureHandler()
        // 样式也算「启动参数」：它只在 `startInternal` 里读一次属性，
        // 变了就必须重建（否则切了样式还在画上一种，2026-11-09 真机）。
        val wantStyle = HookRuntime.systemProperty(AliveFlag.PROP_STYLE)?.toIntOrNull() ?: 0
        h.post {
            if (runningKey == key && runningScope == scope && aodStyle == wantStyle) return@post
            if (runningKey != null) stopInternal("重启")
            runCatching { startInternal(ctx, key, scope) }
                .onFailure {
                    HookRuntime.log("$TAG 启动失败：${it.javaClass.simpleName}: ${it.message}")
                    stopInternal("启动失败")
                }
        }
    }

    fun stop() {
        handler?.post { stopInternal("外部关闭") }
    }

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("he-alive-gl").apply { start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    private fun startInternal(ctx: Context, key: String, scope: Int) {
        val t0 = SystemClock.elapsedRealtime()
        appCtx = ctx.applicationContext ?: ctx
        // Choreographer 绑定**当前线程**的 Looper —— 这里已经在 GL 线程上（start 是 post 进来的）
        choreographer = runCatching { Choreographer.getInstance() }.getOrNull()
        val dm = android.content.res.Resources.getSystem().displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        expandedScale = AliveGlCore.expandedScale(screenW, screenH)
        // 息屏样式：走属性（SystemUI 进程读不到模块私有目录的 meta.json）
        aodStyle = HookRuntime.systemProperty(AliveFlag.PROP_STYLE)?.toIntOrNull() ?: 0
        AliveMaskFrames.attach(openModuleAssets(ctx))
        AliveLiuguang.setAspect(screenH.toFloat() / screenW)
        AliveLiuguang.reset()
        maskFrame = -1
        // 先解 0/2/4/6 帧（量化后只播偶数帧）：出 AOD 落到 frame 0 时要立刻能画
        if (aodStyle == AliveStore.STYLE_LIUGUANG) {
            AliveMaskFrames.resetStats()
            AliveMaskFrames.prefetch(0, 4, 2)
            // 另一端（060 = 铺满）也要常驻：锁屏态的主体裁切框就是从它量的。
            // 不预取的话，AOD → 锁屏时 060 可能还没解出来，主体会先用 000 的小框
            // （2026-11-09 用户报「锁屏时主体只显示到框内」）。
            AliveMaskFrames.prefetch(AliveMaskFrames.FRAMES - 1, 4, -2)
        }

        // 一、读图（走 provider —— SystemUI 进程看不到模块私有目录）
        // 2026-11-08：锁屏 / 桌面是**两张图**；桌面缺失或「桌面与锁屏一致」时只用锁屏那张。
        val lockBmp = loadBitmap(ctx, key, "lock", screenW * 2)
        check(lockBmp != null) { "锁屏图读不出来（key=$key）" }
        photoAspect = lockBmp.width.toFloat() / lockBmp.height
        val deskBmp = loadBitmap(ctx, key, "desk", screenW * 2)
        sameLayer = deskBmp == null
        if (deskBmp != null) {
            deskAspect = deskBmp.width.toFloat() / deskBmp.height
        } else {
            deskAspect = photoAspect
        }
        HookRuntime.log(
            "$TAG 照片：锁屏 ${lockBmp.width}×${lockBmp.height}" +
                (if (deskBmp != null) " / 桌面 ${deskBmp.width}×${deskBmp.height}" else "（桌面同锁屏）"),
        )

        // 二、EGL
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize 失败" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) && num[0] > 0,
        ) { "eglChooseConfig 失败" }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )

        // 三、挂载目标（与光栅同一套：按 scope 建锁屏 / 桌面两条）
        val wantLock = scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        val wantDesk = scope != MaskPrefs.SPATIAL_SCOPE_LOCK
        for (locked in booleanArrayOf(true, false)) {
            if (locked && !wantLock) continue
            if (!locked && !wantDesk) continue
            val name = if (locked) "he-alive-lock" else "he-alive-desk"
            val sc = android.view.SurfaceControl.Builder()
                .setName(name)
                .setBufferSize(screenW, screenH)
                .build()
            val surface = Surface(sc)
            val es = EGL14.eglCreateWindowSurface(
                display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            mounts.add(Mount(locked, sc, surface, es))
        }
        check(mounts.isNotEmpty()) { "没有可挂载的目标（scope=$scope）" }
        check(EGL14.eglMakeCurrent(display, mounts[0].eglSurface, mounts[0].eglSurface, context)) {
            "eglMakeCurrent 失败"
        }

        // 四、程序 / 靶 / 纹理 / quad
        copyProg = buildProgram(AliveGlCore.VERT, AliveGlCore.COPY_FRAG)
        lensProg = buildProgram(AliveGlCore.VERT, AliveGlCore.LENS_FRAG)
        // 留光只在 `style == 1` 时用；建不出来不致命（会回落到球）
        liuguangProg = buildProgram(AliveGlCore.VERT, AliveGlCore.LIUGUANG_FRAG)
        // ⚠️ **两个样式两份独立的 blend shader**（2026-11-09 用户要求「两个样式的分支务必做清楚」）：
        //    启航照 fork2 `common_blend`、留光照 openalive `chromakey_blend`，
        //    分支口径本来就不同（aod-launcher 加权 vs 直接取、launcher 圆裁 vs 方相纸、
        //    三界是否压黑）。合并成一份的代价已经在真机上出现过：改留光把启航球扩弄坏。
        blendProg = buildProgram(
            AliveGlCore.VERT,
            if (aodStyle == AliveStore.STYLE_LIUGUANG) {
                AliveGlCore.BLEND_FRAG_LIUGUANG
            } else {
                AliveGlCore.BLEND_FRAG_QIHANG
            },
        )
        downProg = buildProgram(AliveGlCore.VERT, AliveGlCore.BLUR_DOWN_FRAG)
        gaussProg = buildProgram(AliveGlCore.VERT, AliveGlCore.BLUR_GAUSS_FRAG)
        upProg = buildProgram(AliveGlCore.VERT, AliveGlCore.BLUR_UP_FRAG)
        check(
            lensProg != 0 && blendProg != 0 && copyProg != 0 &&
                downProg != 0 && gaussProg != 0 && upProg != 0,
        ) { "shader 编译失败" }
        uCopy = Uni(copyProg)
        uLens = Uni(lensProg)
        uLiuguang = if (liuguangProg != 0) Uni(liuguangProg) else null
        uBlend = Uni(blendProg)
        uDown = Uni(downProg)
        uGauss = Uni(gaussProg)
        uUp = Uni(upProg)

        quad = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }
        quadScaled = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        photoT = createTarget(screenW, screenH)
        deskT = if (deskBmp != null) createTarget(screenW, screenH) else null
        aodT = createTarget(screenW, screenH)
        blurA = createTarget(screenW, screenH)
        blurB = createTarget(screenW, screenH)

        photoTex = uploadBitmap(lockBmp)
        lockBmp.recycle()
        // ⚠️ 没有桌面图时必须显式清 0：`deskTex` 是对象级字段，
        // 不清会沿用**上一轮已经 glDeleteTextures 的 id**，采样到未定义内容。
        deskTex = if (deskBmp != null) uploadBitmap(deskBmp) else 0
        deskBmp?.recycle()

        // 用户自选的 AOD 图（2026-11-09）：只有「留光 + 关『AOD 与锁屏一致』」才有 ——
        // provider 的 `aod` 槽在「跟随锁屏」时直接返回空，所以这里读到 null 就是「用锁屏图」。
        // 启航样式下 AOD 是球（源是锁屏图的模糊），这张图用不上，不读。
        val aodBmp = if (aodStyle == AliveStore.STYLE_LIUGUANG) {
            loadBitmap(ctx, key, "aod", screenW * 2)
        } else {
            null
        }
        aodPhotoTex = if (aodBmp != null) uploadBitmap(aodBmp) else 0
        if (aodBmp != null) {
            HookRuntime.log("$TAG AOD 源图：${aodBmp.width}×${aodBmp.height}（用户自选）")
            aodBmp.recycle()
        }

        // 五、初始态：按系统真值决定从锁屏还是桌面开始
        showingDesktop = readShowingDesktop(ctx)
        deskShown = showingDesktop
        mode = if (showingDesktop) 2 else 1
        AliveGlCore.stateFor(mode, expandedScale).copyInto(lens)
        // 桌面份的固定参数（永远按桌面稳态画，见 deskState 的注释）
        AliveGlCore.stateFor(2, expandedScale).copyInto(deskState)
        ratio[0] = 0f; ratio[1] = 0f; ratio[2] = 0f
        ratio[if (mode == 2) 2 else 1] = 1f
        ratioFrom[0] = ratio[0]; ratioFrom[1] = ratio[1]; ratioFrom[2] = ratio[2]
        ratioTo[0] = ratio[0]; ratioTo[1] = ratio[1]; ratioTo[2] = ratio[2]
        ratioDur = 0L
        dim = 1f
        currentAlpha = 1f
        lastTickAt = 0L
        animating = false
        aodActive = false
        swapLock = 0; swapDesk = 0; swapErr = 0; slowLogged = 0
        blurCacheKey = Float.NaN
        // ⚠️ **必须在这里复位**（2026-11-08 黑屏根因）：`needCopy` 只在声明处初始化过一次，
        // 而 `stopInternal` 会把 photoT / deskT 连同纹理全部删掉、`startInternal` 再重建。
        // 不复位就会「第二次应用起永远不发 copy」—— photoT 是刚建的空靶（全黑），
        // blend 采样它 ⇒ 整屏黑。表现就是「第一次应用正常，之后点应用就黑屏」
        // （进程重启后又好一次）。
        needCopy = true
        dirty = true

        runningKey = key
        runningScope = scope

        // 六、首帧：**所有**挂载目标都要画（没有 buffer ⇒ 不显示）
        drawEveryMount()
        HookRuntime.log(
            "$TAG 首帧完成：${mounts.size} 个挂载目标（${if (showingDesktop) "桌面" else "锁屏"}态），" +
                "耗时 ${SystemClock.elapsedRealtime() - t0}ms",
        )

        // 七、挂到壁纸 leash
        attachToLeash(scope)

        watchScreen(appCtx!!)
        AliveFlag.pushRendering(true)
        scheduleFrame()
        HookRuntime.log(
            "$TAG 渲染器已启动：key=$key scope=$scope 屏幕 ${screenW}×$screenH " +
                "expanded=${"%.2f".format(expandedScale)}",
        )
    }

    // ───────────────────────────── 挂载（与光栅逐条同构）─────────────────────────────

    private fun attachToLeash(scope: Int) {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal", false, javaClass.classLoader)
        val inst = wmgClass.getMethod("getInstance").invoke(null)
        val gw = wmgClass.getMethod(
            "getWallpaperByShowWhenLocked",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        val txClass = Class.forName(
            "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
        )
        val tx = txClass.getConstructor().newInstance()
        val reparent = txClass.getMethod(
            "reparent", android.view.SurfaceControl::class.java, android.view.SurfaceControl::class.java,
        )
        val show = txClass.getMethod("show", android.view.SurfaceControl::class.java)

        fun leashOf(locked: Boolean): Any? {
            val token = runCatching { gw.invoke(inst, 0, locked) }.getOrNull() ?: return null
            return runCatching { token.javaClass.getMethod("getLeash").invoke(token) }.getOrNull()
        }

        val leashes = HashMap<Boolean, Any?>()
        leashes[true] = leashOf(true)
        leashes[false] = leashOf(false)
        val hasLockMount = mounts.any { it.locked }
        val shared = leashes[false] == null && mounts.any { !it.locked } && leashes[true] != null
        sharedLeash = shared
        val dropDesk = shared && hasLockMount

        val kept = ArrayList<Mount>(mounts.size)
        for (m in mounts) {
            if (dropDesk && !m.locked) {
                HookRuntime.log("$TAG 共用 leash：桌面份丢弃")
                continue
            }
            val leash = leashes[m.locked] ?: leashes[true]
            if (leash == null) {
                // 两条都拿不到 = 这一份挂不上 ⇒ 那个态一定黑屏（2026-11-08 诊断）
                HookRuntime.log(
                    "$TAG 挂载跳过：showWhenLocked=${m.locked} 没有 leash" +
                        "（锁屏 leash=${leashes[true] != null}，桌面 leash=${leashes[false] != null}）",
                )
                continue
            }
            runCatching {
                reparent.invoke(tx, m.sc, leash)
                show.invoke(tx, m.sc)
                kept.add(m)
                HookRuntime.log(
                    "$TAG 挂载：showWhenLocked=${m.locked} leash=@" +
                        Integer.toHexString(System.identityHashCode(leash)),
                )
            }.onFailure {
                HookRuntime.log("$TAG 挂载失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        runCatching { txClass.getMethod("apply").invoke(tx) }

        if (kept.size != mounts.size) {
            val drop = mounts.filter { it !in kept }
            runCatching {
                val tx2 = txClass.getConstructor().newInstance()
                val remove = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
                for (m in drop) {
                    runCatching { remove.invoke(tx2, m.sc) }
                    runCatching { m.surface.release() }
                    display?.let { d ->
                        m.eglSurface?.let { es -> runCatching { EGL14.eglDestroySurface(d, es) } }
                    }
                    m.eglSurface = null
                }
                txClass.getMethod("apply").invoke(tx2)
            }
            mounts.clear()
            mounts.addAll(kept)
        }
        check(mounts.isNotEmpty()) { "全部目标挂载失败（scope=$scope）" }
    }

    // ───────────────────────────── 帧循环 ─────────────────────────────

    /**
     * 帧回调 —— **跟 display vsync 走**（2026-11-08 照 `alive分析/fork2` 的 `EglWorker`）。
     *
     * ⚠️ 之前是 `handler.postDelayed(frameTick, 16)`，那是**固定延迟**：
     * 实际间隔 = 渲染耗时(5~10ms) + 16ms + `eglSwapBuffers` 等 vsync(0~16ms) ≈ 30~40ms，
     * 上限只有 25~30fps —— 这就是「怎么看都不到 60」的全部原因。
     * fork 的注释写得很直白：`An extra 17ms sleep after eglSwapBuffers can miss the next vsync`。
     */
    private val frameCallback = Choreographer.FrameCallback {
        framePosted = false
        try {
            if (runningKey == null) return@FrameCallback
            val now = SystemClock.elapsedRealtime()
            val dtSec = if (lastTickAt == 0L) 0f else (now - lastTickAt) / 1000f
            lastTickAt = now
            advance(dtSec, now)
            if (dirty || animating || (aodActive && mode == 0 && lens.speed > 0.001f) ||
                AliveLiuguang.isHolding()
            ) {
                drawAllFrames()
                dirty = false
            }
            if (keepTicking()) scheduleFrame()
        } catch (t: Throwable) {
            HookRuntime.log("$TAG 帧循环异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 排下一帧。**三档**（用户 2026-11-08 定「转场 60 / AOD 稳态 40」）：
     *
     * | 档 | 条件 | 延迟 |
     * |---|---|---|
     * | 转场 | `animating` / `ratioDur` / 淡出在跑 | 0 = 每个 vsync（60fps） |
     * | AOD 自转 | 没转场但小球一直在转 | [AOD_FRAME_MS] = 25ms（40fps） |
     * | 真静止 | 上面都不满足 | 不排帧（停笔） |
     */
    private fun scheduleFrame() {
        if (framePosted) return
        val c = choreographer ?: return
        val delay = when {
            animating || ratioDur > 0L || fadeTick != null -> 0L
            // 留光的遮罩卡住（解码没跟上）：逐个 vsync 重试，别停笔
            AliveLiuguang.isHolding() -> 0L
            aodActive && mode == 0 && lens.speed > 0.001f -> AOD_FRAME_MS
            else -> return
        }
        framePosted = true
        if (delay <= 0L) c.postFrameCallback(frameCallback)
        else c.postFrameCallbackDelayed(frameCallback, delay)
    }

    /** 还有「会自己变的东西」才继续排帧；锁屏 / 桌面静止时停笔。 */
    private fun keepTicking(): Boolean {
        if (animating) return true
        if (ratioDur > 0L) return true
        if (fadeTick != null) return true
        // 留光（2026-11-09）：AOD 稳态是一张**静止相片**，没有自转 ⇒ 停笔。
        // 只有进出场那 0.5s 需要每 vsync 一帧。这是它比球省电的地方。
        // ⚠️ 遮罩卡住（[AliveLiuguang.isHolding]）时必须继续排帧重试。
        if (aodStyle == AliveStore.STYLE_LIUGUANG) {
            return AliveLiuguang.isRunning() || AliveLiuguang.isHolding()
        }
        if (aodActive && mode == 0 && lens.speed > 0.001f) return true
        return false
    }

    /**
     * 主体层 alpha（用户 2026-11-08 最终口径）。
     *
     * 主体**全程参与**小球的缩放过程，只在两端各留 `AOD_FADE_PROGRESS` 的渐变：
     *
     * | 阶段 | 几何 | alpha |
     * |---|---|---|
     * | 锁屏 → AOD（球缩小） | 跟 `lens` 全程 | `p ∈ [1-2g, 1-g]` 淡出，之后恒 0（球还要缩 g 段） |
     * | **桌面 → AOD（球缩小）** | — | **恒 0**（桌面本来没有主体，见下） |
     * | AOD 稳态 | 跟 `lens`（自转） | 0 |
     * | AOD → 锁屏（球放大） | 跟 `lens` 全程 | `p ∈ [0, g]` 淡入，之后恒 1 |
     * | AOD → 桌面 | — | 保持 0 |
     * | 锁屏 / 桌面 / 两者互转 | — | 1 |
     *
     * ⚠️ **桌面起点必须单独判**（2026-11-09 用户指出「桌面到 AOD 时不应该显示主体层」）：
     * 桌面上 `keyguard_foreground_layer` 是不可见的，本来就没有主体；而 `mode == 0` 那条
     * 只按缩放进度淡出 ⇒ 从桌面进 AOD 时那一层被系统抬起来，主体会「凭空冒出来再淡掉」。
     * 桌面态没有可以对齐的几何，整段恒 0 才是对的。
     */
    /**
     * 采样「这一时刻」的主体层几何（**任意线程**，2026-11-10）。
     *
     * 与 `advance()` 的转场分支**同一套公式**（`fromState → targetState`，按 `tracks` 插值），
     * 只是不写回 `lens`。AOD 稳态的自转是逐帧累加的（不可反推），那时主体 alpha 恒 0、
     * 根本看不见，所以直接拷当前 `lens` 即可。
     *
     * @param withMatrix 是否需要 `photoMatrix`（`invalidateSphere` 只要脏区，省一次矩阵）
     */
    internal fun sampleSubject(now: Long, out: SubjectSample, withMatrix: Boolean = true) {
        synchronized(subjLock) {
            val tr = tracks
            if (animating && tr != null) {
                val elapsed = (now - animStart).toFloat()
                for (i in 0 until 10) {
                    val to = targetOf(i)
                    if (to.isNaN()) {
                        // AOD 的 angle 不插值 ⇒ 沿用当前值
                        writeTo(out.lens, i, readLens(i))
                        continue
                    }
                    val tk = tr[i]
                    val t = if (tk.ms <= 0L) 1f else (elapsed / tk.ms).coerceIn(0f, 1f)
                    val e = tk.curve?.at(t) ?: AliveGlCore.DefaultCurve.at(t)
                    val from = fromOf(i)
                    writeTo(out.lens, i, from + (to - from) * e)
                }
            } else {
                lens.copyInto(out.lens)
            }
            out.alpha = subjectAlphaFor(out.lens.scale)
            out.w = screenW
            out.h = screenH
        }
        if (withMatrix) photoMatrix(out.w.toFloat(), out.h.toFloat(), out.lens, out.matrix)
    }

    /** 把 `value` 写进任意 [AliveGlCore.LensState]（下标口径与 `readLens` 一致）。 */
    private fun writeTo(s: AliveGlCore.LensState, i: Int, value: Float) {
        when (i) {
            0 -> s.blur = value; 1 -> s.scale = value; 2 -> s.distortion = value
            3 -> s.decorAlpha = value; 4 -> s.decorAngle = value; 5 -> s.x = value
            6 -> s.y = value; 7 -> s.speed = value; 8 -> s.ripple = value
            else -> s.angle = value
        }
    }

    private fun computeSubjectAlpha(now: Long): Float {
        // 留光：轨道已经算好了（含「桌面 → AOD 整段 0」的特例），这里只给诊断用
        if (aodStyle == AliveStore.STYLE_LIUGUANG) {
            if (mode == 2) return 0f
            return AliveLiuguang.sample(now).subjectAlpha
        }
        return subjectAlphaFor(lens.scale)
    }

    /** 主体 alpha 的**纯函数版**（GL 与 View 共用，同一个 `scale` 给同一个结果）。 */
    private fun subjectAlphaFor(scale: Float): Float {
        // 从桌面进 AOD：整段都不出现（转场结束后 `animating=false` 已在下面落到 0，这里管的是过程）
        if (mode == 0 && (aodFromDesktop || prevMode == 2)) return 0f
        if (!animating || animDur <= 0L) return if (mode == 0) 0f else 1f
        // **缩放进度**（0 = 起点大小，1 = 终点大小）。与曲线、轨道时长都解耦。
        val span = targetState.scale - fromState.scale
        val p = if (kotlin.math.abs(span) < 1e-4f) 1f
        else ((scale - fromState.scale) / span).coerceIn(0f, 1f)
        val g = AOD_FADE_PROGRESS
        return when {
            // 进 AOD：球缩到 (1-g) 时主体已经完全不见，剩下 g 行程它自己缩完
            mode == 0 -> ((1f - g - p) / g).coerceIn(0f, 1f)
            // 退 AOD：开头 g 段淡入（此时球还很小），之后恒 1 跟着放大
            prevMode == 0 && mode == 1 -> (p / g).coerceIn(0f, 1f)
            prevMode == 0 -> 0f
            else -> 1f
        }
    }

    private fun advance(dtSec: Float, now: Long) = synchronized(subjLock) {
        // 转场插值
        if (animating) {
            val elapsed = (now - animStart).toFloat()
            val tr = tracks
            if (tr != null) {
                for (i in 0 until 10) {
                    val tk = tr[i]
                    val to = targetOf(i)
                    if (to.isNaN()) continue          // AOD 的 angle：不插值
                    val t = if (tk.ms <= 0L) 1f else (elapsed / tk.ms).coerceIn(0f, 1f)
                    val e = tk.curve?.at(t) ?: AliveGlCore.DefaultCurve.at(t)
                    writeLens(i, fromOf(i) + (to - fromOf(i)) * e)
                }
                angleInterp(elapsed)
            }
            if (elapsed >= animDur) {
                for (i in 0 until 10) {
                    val to = targetOf(i)
                    if (!to.isNaN()) writeLens(i, to)
                }
                applyAngleTarget()
                animating = false
                tracks = null
                dirty = true
            }
        }
        // 三界比例
        if (ratioDur > 0L) {
            val raw = ((now - ratioStart).toFloat() / ratioDur).coerceIn(0f, 1f)
            val t = if (ratioEase) AliveGlCore.EASE_BLEND.at(raw) else raw
            for (i in 0 until 3) ratio[i] = ratioFrom[i] + (ratioTo[i] - ratioFrom[i]) * t
            dirty = true
            if (t >= 1f) {
                for (i in 0 until 3) ratio[i] = ratioTo[i]
                ratioDur = 0L
            }
        }
        // AOD 自转（只有启航效果、speed > 0 才转）
        if (aodActive && mode == 0 && lens.speed > 0.001f) {
            lens.angle -= dtSec * AliveGlCore.SPIN_DEG_PER_SEC * lens.speed
            while (lens.angle < -180f) lens.angle += 360f
            while (lens.angle > 180f) lens.angle -= 360f
            dirty = true
        }
    }

    // 字段读写（下标顺序与 LensState 一致，避免十次反射式 when 分散在各处）
    private fun readLens(i: Int): Float = when (i) {
        0 -> lens.blur; 1 -> lens.scale; 2 -> lens.distortion; 3 -> lens.decorAlpha
        4 -> lens.decorAngle; 5 -> lens.x; 6 -> lens.y; 7 -> lens.speed
        8 -> lens.ripple; else -> lens.angle
    }

    private fun writeLens(i: Int, v: Float) {
        when (i) {
            0 -> lens.blur = v; 1 -> lens.scale = v; 2 -> lens.distortion = v
            3 -> lens.decorAlpha = v; 4 -> lens.decorAngle = v; 5 -> lens.x = v
            6 -> lens.y = v; 7 -> lens.speed = v; 8 -> lens.ripple = v; else -> lens.angle = v
        }
    }

    private fun fromOf(i: Int): Float = when (i) {
        0 -> fromState.blur; 1 -> fromState.scale; 2 -> fromState.distortion
        3 -> fromState.decorAlpha; 4 -> fromState.decorAngle; 5 -> fromState.x
        6 -> fromState.y; 7 -> fromState.speed; 8 -> fromState.ripple; else -> fromState.angle
    }

    private fun targetOf(i: Int): Float = when (i) {
        0 -> targetState.blur; 1 -> targetState.scale; 2 -> targetState.distortion
        3 -> targetState.decorAlpha; 4 -> targetState.decorAngle; 5 -> targetState.x
        6 -> targetState.y; 7 -> targetState.speed; 8 -> targetState.ripple; else -> angleTo
    }

    private fun angleInterp(elapsed: Float) {
        if (angleTo.isNaN()) return
        val tk = tracks?.getOrNull(9) ?: return
        val t = if (tk.ms <= 0L) 1f else (elapsed / tk.ms).coerceIn(0f, 1f)
        val e = tk.curve?.at(t) ?: AliveGlCore.DefaultCurve.at(t)
        lens.angle = angleFrom + (angleTo - angleFrom) * e
    }

    private fun applyAngleTarget() {
        if (!angleTo.isNaN()) lens.angle = angleTo
    }

    /**
     * 切到某个态。`from` 取**当前值**（转场中途再切才能从半路续上），
     * 与上游 `System.arraycopy(values, 0, from, 0, SIZE)` 一致。
     *
     * ⚠️ **锁屏 ↔ 桌面不做转场**（2026-11-07）：这两层是同一张原图，
     * 上游那套 `tr=4` 会把 blur 从 0 拉到 16 跑 3840ms —— 视觉上什么都没变，
     * 却让帧循环连跑 3.8 秒（白耗电）。直接落值。
     */
    private fun changeMode(next: Int, animate: Boolean) = synchronized(subjLock) {
        if (mode == next) return
        prevMode = mode
        val betweenLockAndHome = mode != 0 && next != 0
        val type = AliveGlCore.transition(mode, next)
        // from = 当前值
        fromState.blur = lens.blur; fromState.scale = lens.scale
        fromState.distortion = lens.distortion; fromState.decorAlpha = lens.decorAlpha
        fromState.decorAngle = lens.decorAngle; fromState.x = lens.x
        fromState.y = lens.y; fromState.speed = lens.speed
        fromState.ripple = lens.ripple; fromState.angle = lens.angle

        AliveGlCore.stateFor(next, expandedScale).copyInto(targetState)

        val tr = AliveGlCore.tracks(type)
        tracks = tr
        animStart = SystemClock.elapsedRealtime()
        animDur = tr.maxOf { it.ms }
        angleFrom = lens.angle
        angleTo = targetState.angle
        if (!angleTo.isNaN()) {
            angleFrom = AliveGlCore.nearestRotation(angleFrom, angleTo)
            lens.angle = angleFrom
        }

        ratioFrom[0] = ratio[0]; ratioFrom[1] = ratio[1]; ratioFrom[2] = ratio[2]
        ratioTo[0] = if (next == 0) 1f else 0f
        ratioTo[1] = if (next == 1) 1f else 0f
        ratioTo[2] = if (next == 2) 1f else 0f
        ratioStart = animStart
        ratioDur = AliveGlCore.blendDuration(type)
        ratioEase = type == 2 || type == 3

        // 留光（2026-11-09）：走它自己的一套轨道（openalive 的 `PhotoStyle(1)`）。
        //
        // 官方时序（`FrameMotion.prepare` + `SceneMotion.prepare`）：
        // * **AOD 退出整体延迟 150ms**（`if (previous==0 && !interrupted) setStartDelay(150)`）；
        // * 几何 450ms / 遮罩 483ms / **三界混合 567ms**（`max(durations[tr], blendDuration)`）；
        // * 进 AOD 无延迟，几何 567 / 混合 530。
        //
        // 之前把几何、混合、换层钉成同一个 450ms 是错的：相片 450ms 就铺满了、
        // 混合却要 567ms，观感是「放大完又慢慢淡一下」；而少了 150ms 延迟，
        // 我们的动画会抢在系统解锁动画前面 —— 用户说的「动画还是不对」。
        if (aodStyle == AliveStore.STYLE_LIUGUANG && (next == 0 || prevMode == 0)) {
            if (animate && !betweenLockAndHome) {
                val exit = next != 0
                val delay = if (exit) AliveLiuguang.EXIT_DELAY_MS else 0L
                // `fromDesktop`：桌面 → AOD 整段不显示主体（桌面上本来就没有，
                // 否则一进来就是一张全亮的人像 = 用户报的「闪一下主体层」）
                AliveLiuguang.start(
                    next == 0, animStart, delayMs = delay,
                    fromDesktop = prevMode == 2 || aodFromDesktop,
                )
                val geom =
                    if (next == 0) AliveLiuguang.OPEN_TOTAL_MS else AliveLiuguang.CLOSE_TOTAL_MS
                val blend =
                    if (next == 0) AliveLiuguang.BLEND_OPEN_MS else AliveLiuguang.BLEND_EXIT_MS
                // 混合在延迟之后才开始（官方是整个 AnimatorSet 一起延迟）
                ratioStart = animStart + delay
                ratioDur = blend
                animDur = delay + maxOf(geom, blend)
            } else {
                AliveLiuguang.snapTo(next == 0, desktop = next == 2)
            }
        }

        mode = next
        if (animate && !betweenLockAndHome) {
            animating = true
        } else {
            for (i in 0 until 10) {
                val to = targetOf(i)
                if (!to.isNaN()) writeLens(i, to)
            }
            applyAngleTarget()
            for (i in 0 until 3) ratio[i] = ratioTo[i]
            ratioDur = 0L
            ratioEase = false
            animating = false
        }
        dirty = true
        HookRuntime.log(
            "$TAG 切态 → $next（tr=$type，时长 ${animDur}ms，混合 ${AliveGlCore.blendDuration(type)}ms）",
        )
        scheduleFrame()
    }

    // ───────────────────────────── AOD / 解锁 ─────────────────────────────

    /**
     * 时钟中心变了（`AodClockProbe` 量到新值）：如果**此刻正在 AOD**，立刻把球心拉过去。
     *
     * 时序上 AOD 视图往往比 `doWallpaperBlackAnim` 更早布局，所以多数情况下一进 AOD
     * `stateFor(0)` 已经拿到新值；这条只是兜住「测量晚于进入」的那一次。
     */
    fun onAodClockYChanged() {
        handler?.post {
            if (runningKey == null) return@post
            if (!aodActive || mode != 0) return@post
            val y = AliveGlCore.aodCenterY
            if (abs(lens.y - y) < 0.0005f) return@post
            lens.y = y
            targetState.y = y
            dirty = true
            forceDraw()
            scheduleFrame()
        }
    }

    /**
     * **Scrim 状态**（权威三态来源，2026-11-08 参考 `alive分析/fork` 的 `AliveHooks`）。
     *
     * hook 点：`ScrimController#updateScrims`，读字段 `mState`（`ScrimState` 枚举）。
     * 它比 `KeyguardManager.isKeyguardLocked` 准 —— 后者在 AOD 退出的瞬间仍然是 locked，
     * 分不出「回锁屏」还是「直接进桌面」，tr=2 永远跑不到（真机日志已证实）。
     *
     * @param state `String.valueOf(mState)`，只会用它做 `contains` 判断
     */
    fun onScrimState(state: String) {
        handler?.post {
            if (runningKey == null) return@post
            when {
                state.contains("AOD") || state.contains("PULSING") -> {
                    // ⚠️ 不能只看 `aodActive`：快速连按电源键时 `doWallpaperBlackAnim` 可能被跳过，
                    //    标志停在 true ⇒ 下一次进 AOD 直接 return ⇒ 用户看到「丢动画」。
                    //    改成「标志没置」**或**「态不在 AOD」都重新进一次（changeMode 自身幂等）。
                    if (!aodActive || mode != 0) {
                        HookRuntime.log("$TAG scrim=$state ⇒ 进 AOD（aodActive=$aodActive mode=$mode）")
                        enterAodInternal(1f)
                    }
                }
                state.contains("UNLOCKED") -> {
                    HookRuntime.log("$TAG scrim=$state ⇒ 解锁（mode=$mode）")
                    fadeInternal()
                }
                state.contains("KEYGUARD") -> {
                    // 回锁屏：结束解锁淡出，让 onFrame 重新推 scale/alpha
                    runCatching { AliveOverlay.endFade() }
                    // ⚠️ **灭屏期间（AOD 里）scrim 会瞬态报 KEYGUARD**（2026-11-09 用户报
                    //    「桌面到 AOD 偶发先到锁屏态再 AOD」）：进 AOD 的状态机本来是
                    //    KEYGUARD → AOD，真机日志里 `AOD 进入（从=桌面 tr=3）` 之后 86ms
                    //    就来了一条 `scrim=KEYGUARD ⇒ 回锁屏` ⇒ 这里把球**又弹回锁屏全屏**
                    //    （tr=0 1000ms），下一帧再重新收球。
                    //    物理判据：屏幕不亮就还在 AOD，KEYGUARD 只是过渡态，别改态。
                    if (!screenOn()) return@post
                    if (aodActive || mode == 0) {
                        HookRuntime.log("$TAG scrim=$state ⇒ 回锁屏")
                        aodActive = false
                        dim = 1f
                        changeMode(1, animate = true)
                        dirty = true
                        forceDraw()
                        scheduleFrame()
                    }
                }
            }
        }
    }

    /** [onEnterAod] 的主体（已在 handler 里）。 */
    private fun enterAodInternal(black: Float) {
        aodExitTick?.let { handler?.removeCallbacks(it) }
        aodExitTick = null
        // 快速连按电源键：把还在跑的「解锁交叉 / 锁屏淡出」掐掉 —— 它们每帧直接写 `ratio`
        // （(0,1-t,t) → (0,0,1)），会把已经在缩的球又拉回"桌面全屏"。
        fadeTick?.let { handler?.removeCallbacks(it) }
        fadeTick = null
        unlocking = false
        aodActive = true
        // 进 AOD 要跟小球缩放 ⇒ 必须结束解锁淡出，否则 scale 被 fading 挡住
        runCatching { AliveOverlay.endFade() }
        dim = 1f
        val fromDesktop = deskShown || mode == 2
        showingDesktop = false
        aodFromDesktop = fromDesktop
        if (fromDesktop && mode != 2) {
            val driftedMode = mode
            // **起点按物理画面归位**（2026-11-09「桌面到 AOD 偶发先到锁屏态」）。
            //
            // 内部 `mode` 会被 `scrim=KEYGUARD` 的瞬态拉回 1（此时屏幕上仍是**桌面份**，
            // `showingDesktop` 也还是 true）。这时如果直接 `changeMode(0)`：
            //   * `transition(1,0)` = tr=1（不是 tr=3）⇒ blur 从 0 起，不是从桌面的 16；
            //   * `ratio` 从 `(0,1,0)` 起 ⇒ 球里先出现**锁屏图**，球一边缩一边换图。
            // 两条合起来就是「先到锁屏态、再 AOD」。
            //
            // 桌面份画的是 [deskState]、与 `lens` 无关，所以这里把 lens 落成桌面态**屏幕上什么都不变**，
            // 只是让 `changeMode(0)` 拿到真正的桌面起点（tr=3 + ratio 从 (0,0,1) 起）。
            // ⚠️ 这与 v1.22.29 删掉的「静默归位到锁屏」正好相反 —— 那次是把**桌面**起点伪造成锁屏。
            mode = 2
            AliveGlCore.stateFor(2, expandedScale).copyInto(lens)
            ratio[0] = 0f; ratio[1] = 0f; ratio[2] = 1f
            HookRuntime.log("$TAG AOD 起点归位到桌面态（内部 mode=$driftedMode，屏幕上是桌面份）")
        }
        // ⚠️ **不能把态静默归位到锁屏**（2026-11-08 用户指出「桌面直接到 AOD 的动画没抄到」）。
        //    原来这里写 `mode = 1` + `stateFor(1)` 落值，等于把起点从**桌面态**伪造回锁屏态：
        //    `fromState.blur` 从 16 变成 0、`changeMode(0)` 也走 tr=1 而不是 tr=3。
        //    上游 `SceneMotion.transition(from,to)` 里 `from=2,to=0` 是明确的 **tr=3**；
        //    它的 lens 轨道虽然与 tr=1 共用（都是 650ms / COLLAPSE），但**起点是桌面**——
        //    差别就在 blur 16→22（不是 0→22）与 angle 的续接。
        //
        // 留光（2026-11-09）：起点也要落成**桌面态**（photo alpha = 0，内容框里是桌面壁纸）。
        // 不落的话「桌面 → AOD」第一帧照片 alpha 还是上次 AOD 留下的 1 ⇒ 一进来就是照片。
        if (fromDesktop && aodStyle == AliveStore.STYLE_LIUGUANG) {
            AliveLiuguang.snapTo(false, desktop = true)
        }
        HookRuntime.log(
            "$TAG AOD 进入（不压暗，dim=1.00，从=${if (fromDesktop) "桌面 tr=3" else "锁屏 tr=1"}）",
        )
        changeMode(0, animate = true)
        dirty = true
        aodDiagLogged = false
        // **先画一帧，再换层**（2026-11-09「先到锁屏态再 AOD」的另一半）：
        // 桌面态我们只画桌面份（`drawAllFrames` 只画可见那份），锁屏份的 buffer 里
        // **还停在上一次锁屏画面**。先 `applyAlphaBoth` 再画，SurfaceFlinger 可能先用旧 buffer
        // 提交那一帧 ⇒ 露出 1~2 帧旧锁屏。顺序反过来后，抬起来的就是刚画好的这一帧
        // （起点 ratio 仍是 `(0,0,1)`、球半径 1.24 ⇒ 画出来与桌面份逐像素同图）。
        forceDraw()
        applyAlphaBoth(1f, 0f)
        deskShown = false
        scheduleFrame()
        diagAod("进入")
    }

    /**
     * AOD 诊断（2026-11-09，排查「自定义 AOD 桌面→AOD 没有球」）。
     *
     * 三句话把「是不是没在画 / 画了但是黑的 / 参数不对」分开：
     * 进入时的拓扑、首帧的实际 uniform、1.2s 后的稳态（此时 tr 已跑完、只剩自转）。
     */
    private fun diagAod(tag: String) {
        val lock = mounts.count { it.locked }
        val desk = mounts.count { !it.locked }
        HookRuntime.log(
            "$TAG AOD诊断[$tag] mode=$mode prev=$prevMode aod=$aodActive " +
                "mounts=$lock/$desk shared=$sharedLeash same=$sameLayer " +
                "deskShown=$deskShown alpha=$currentAlpha dim=$dim " +
                "lens=(blur=${f(lens.blur)} scale=${f(lens.scale)} x=${f(lens.x)} y=${f(lens.y)} " +
                "decor=${f(lens.decorAlpha)}) ratio=[${f(ratio[0])},${f(ratio[1])},${f(ratio[2])}] " +
                "swap=${swapLock}/${swapDesk}/err$swapErr" + liuguangDiag(),
        )
        val h = handler ?: return
        if (tag == "进入") {
            h.postDelayed({
                if (runningKey == null || !aodActive) return@postDelayed
                diagAod("稳态")
            }, 1200L)
        }
    }

    /**
     * 留光专项诊断：这一串能一次分清「shader 编译失败 / 遮罩一帧没上传 / 遮罩卡在中间帧」。
     */
    private fun liuguangDiag(): String {
        if (aodStyle != AliveStore.STYLE_LIUGUANG) return ""
        return " 留光=(prog=$liuguangProg tex=$maskTex 已传帧=$maskFrame " +
            "已解=${AliveMaskFrames.decodedCount} run=${AliveLiuguang.isRunning()} " +
            "hold=${AliveLiuguang.isHolding()} subj=${f(subjectAlpha)} " +
            "pα=${f(AliveLiuguang.sample(SystemClock.elapsedRealtime()).photoAlpha)})"
    }

    private fun f(v: Float) = "%.2f".format(v)

    /** [fadeOutOnUnlock] 的主体（已在 handler 里，且已过 `fadeTick` / `screenOn` 检查）。 */
    private fun fadeInternal() {
        runCatching { AliveOverlay.fadeOutOnUnlock() }
        if (aodActive) {
            aodActive = false
            dim = 1f
        }
        // AOD 直接进桌面 ⇒ tr=2 球扩；两张图不同 ⇒ 0.25s 内容交叉
        if (mode == 0 || !sameLayer) {
            crossFadeOnUnlock(handler ?: return)
        }
    }

    fun onEnterAod(black: Float) {
        handler?.post {
            if (runningKey == null) return@post
            enterAodInternal(black)
        }
    }

    /**
     * 退出 AOD。
     *
     * ⚠️ **这里不再 `changeMode`**（2026-11-08）：真机日志证明
     * `doWallpaperBlackAnim(show=true)` 早于解锁动画，那一瞬 keyguard 仍是 locked，
     * 分不出「回锁屏」还是「直接进桌面」—— 在这里切 mode 会把 tr=2 永远挡掉。
     * 真正的三态交给 [onScrimState]（`ScrimController#updateScrims` 的 `mState`）。
     */
    fun onExitAod() {
        handler?.post {
            if (runningKey == null) return@post
            aodActive = false
            dim = 1f
            showingDesktop = false
            deskShown = false
            if (abs(currentAlpha - 1f) > 0.001f) applyAlpha(lockScs(), 1f)
            // 2026-11-08：这里**要主动回锁屏**（tr=0，650ms 球收拢）。
            // 之前留空是为了「分不清回锁屏还是直接进桌面」，现在用户已定「AOD→桌面也走
            // 0.15s 交叉」，所以先回锁屏是正确路径：随后 scrim=UNLOCKED 从锁屏态做交叉。
            // 不切的话，快速连按电源键会停在 mode=0（球态），下一次进 AOD `changeMode(0)` 早退
            // ⇒ 没有动画（用户报的「aod 到锁屏丢动画」）。
            if (mode == 0) changeMode(1, animate = true)
            HookRuntime.log("$TAG AOD 退出（回锁屏，等 scrim 裁决）")
            dirty = true
            forceDraw()
            scheduleFrame()
        }
    }

    /**
     * 解锁退场：alpha 渐隐到 0，落地后把显示态翻到桌面。
     * 与光栅同一条状态机（真值优先、幂等、800ms 锁存），差别只是这边没有景深主体。
     */
    fun fadeOutOnUnlock() {
        val h = handler ?: return
        h.post {
            if (runningKey == null) return@post
            // AOD 直接解锁不经过 onExitAod ⇒ 这里无条件复位
            if (dim < 0.999f) {
                dim = 1f
                dirty = true
                HookRuntime.log("$TAG 解锁：压暗复位 1.0")
            }
            aodActive = false
            if (mounts.none { it.locked }) {
                currentAlpha = 1f
                forceDraw()
                return@post
            }
            if (fadeTick != null || unlocking) return@post
            // 景深主体随解锁 0.1s 淡出（用户 2026-11-08 指定）
            runCatching { AliveOverlay.fadeOutOnUnlock() }
            if (!screenOn()) {
                HookRuntime.log("$TAG 解锁淡出跳过（灭屏触发）")
                return@post
            }
            // **AOD 直接进桌面**（2026-11-08）：取消「回锁屏」的延迟裁决，走 tr=2 球扩。
            // 这一条排在 sameLayer / sharedLeash 之前 —— 单图、共用 leash 时同样要跑。
            if (mode == 0) {
                aodExitTick?.let { h.removeCallbacks(it) }
                aodExitTick = null
                crossFadeOnUnlock(h)
                return@post
            }
            // 锁屏与桌面是两张不同的图 ⇒ 走**内容交叉**（时长 = 上游 `blendDuration(4)`，500ms）。
            // ⚠️ 这一条要排在 `sharedLeash` 之前：它只改 blend 的 ratio、**不动 alpha**，
            // 共用 leash 时同样成立（共用时改 alpha 会把桌面一起弄没）。
            if (!sameLayer) {
                crossFadeOnUnlock(h)
                return@post
            }
            if (sharedLeash) {
                HookRuntime.log("$TAG 解锁淡出跳过（共用 leash）")
                // ⚠️ 只对**留光**补改态（2026-11-09 用户要求「影响启航的改动先回退」）：
                //    `doWallpaperBlackAnim` 先来一条 `scrim=KEYGUARD` ⇒ `changeMode(1)` 开跑
                //    「AOD→锁屏」；几十毫秒后真正的 `scrim=UNLOCKED` 才到。原来这里直接
                //    return ⇒ `mode` 停在 1、`ratio` 走到 `(0,1,0)`，留光下画面变成
                //    **锁屏图**（= AOD 层的内容源）⇒「闪的那一下像 AOD 图」。
                //    启航不动它 —— 启航共用 leash 时的行为本来就是对的，别去碰。
                if (aodStyle == AliveStore.STYLE_LIUGUANG && !aodActive && mode != 2) {
                    changeMode(2, animate = true)
                    // ⚠️ ratio 必须对齐**留光转场还剩多久**（2026-11-09）：
                    //    `changeMode` 默认按 tr=5 走 500ms，而此刻「AOD→锁屏」的相片
                    //    可能还要 600ms 才放大铺满。ratio 先到 100% 会整屏切回 launcher，
                    //    相片还停在半大 ⇒ 又是一帧跳（用户「闪的像 AOD 图」）。
                    val remain = AliveLiuguang.remainingMs()
                    if (remain > 0L) {
                        ratioStart = SystemClock.elapsedRealtime()
                        ratioDur = remain
                        animDur = maxOf(animDur, remain)
                    }
                }
                if (screenOn()) forceDraw()
                return@post
            }
            val started = SystemClock.elapsedRealtime()
            var lastA = currentAlpha
            val r = object : Runnable {
                override fun run() {
                    val scs = lockScs()
                    if (scs.isEmpty() || runningKey == null) {
                        fadeTick = null
                        return
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val timeBased = (1f - elapsed.toFloat() / FADE_MS).coerceIn(0f, 1f)
                    val ext = readSystemWallpaperAlpha()?.takeIf { it > 0.02f && it < 0.999f }
                    val a = if (ext != null) minOf(lastA, ext) else minOf(lastA, timeBased)
                    lastA = a
                    applyAlpha(scs, a)
                    if (a > 0.01f) {
                        h.postDelayed(this, 16)
                    } else {
                        fadeTick = null
                        fadeDoneAt = SystemClock.elapsedRealtime()
                        showingDesktop = true
                        deskShown = true
                        // 桌面份接手：补一帧
                        if (screenOn()) forceDraw()
                    }
                }
            }
            fadeTick = r
            h.post(r)
        }
    }

    /**
     * **锁屏 → 桌面的内容交叉**（2026-11-08，两张图不同时）。
     *
     * 做法与「两份 SC 交叉 alpha」不同 —— 那样会叠加成一帧错内容。这里利用
     * blend shader 本来就有的 lock-launcher 融合分支：锁屏份把 `ratio` 从
     * `(0,1,0)` 拉到 `(0,0,1)`，画面里直接是「锁屏图淡出、桌面图淡入」。
     *
     * 收尾那一帧尤其关键：此刻锁屏份画的**已经是桌面图**，所以可以同帧把
     * 锁屏份 α→0、桌面份 α→1 —— 内容相同，切换无感，不会闪。
     */
    private fun crossFadeOnUnlock(h: Handler) {
        if (fadeTick != null || unlocking) return
        unlocking = true
        // 景深主体随解锁 0.1s 淡出（与下面的转场同时发生）
        runCatching { AliveOverlay.fadeOutOnUnlock() }
        // 桌面份先压到 0（它躺在锁屏份下面，等交叉结束再接手）
        applyAlpha(deskScs(), 0f)

        // **留光：AOD 退场没跑完就收到解锁**（偶发，取决于 `doWallpaperBlackAnim` 与
        // `scrim=UNLOCKED` 的间隔）。此时如果照常走「AOD→锁屏」再叠一层「锁屏→桌面」交叉，
        // 两个转场会同时写 `ratio` ⇒ 真机「一眨眼那种闪」（用户 2026-11-09 报，偶发）。
        //
        // 处理：**改道**——直接把目标改成桌面，让留光那套转场一口气跑完（它的几何/内容目标
        // 本来就是「锁屏/桌面同态」），`ratio` 也延到它跑完才到 100%。
        if (aodStyle == AliveStore.STYLE_LIUGUANG && AliveLiuguang.isRunning()) {
            val remain = AliveLiuguang.remainingMs() + 40L
            HookRuntime.log("$TAG 解锁：留光 AOD 转场未完成（剩 ${remain}ms）⇒ 改道桌面")
            changeMode(2, animate = true)
            // ratio 不能比相片转场短：短了会在相片还没铺满时就整屏变桌面（又是一闪）
            ratioStart = SystemClock.elapsedRealtime()
            ratioDur = remain
            animDur = maxOf(animDur, remain)
            dirty = true
            forceDraw()
            h.postDelayed({
                if (runningKey == null) {
                    unlocking = false
                    return@postDelayed
                }
                if (mode == 0 || aodActive) {
                    unlocking = false
                    return@postDelayed
                }
                showingDesktop = true
                deskShown = true
                dirty = true
                forceDraw()
                fadeDoneAt = SystemClock.elapsedRealtime()
                if (!sharedLeash) {
                    swapToDesktopLayer(h) { unlocking = false }
                } else {
                    unlocking = false
                }
                HookRuntime.log("$TAG 解锁完成（AOD→桌面·改道，mode=$mode）")
            }, remain)
            return
        }

        // **AOD 直接进桌面**（2026-11-08）：走 tr=2 的球扩动画（1000ms），
        // 不是 0.25s 交叉 —— 上游 `SceneMotion.lens()` 里 AOD→桌面本来就是独立一档。
        if (mode == 0) {            // 2026-11-08 用户最终口径：**AOD→桌面保留原来的 1000ms tr=2 球扩**
            // （只有「锁屏→桌面」用 0.15s 交叉；AOD 这条路球要真的长开）。
            // `blendDuration(2)` 也已经被拉到 1000ms，因此球扩与内容交叉同步。
            //
            // 留光（2026-11-09）：没有球，相片 450ms 就放大铺满 ⇒ 换层也必须跟着提前，
            // 否则铺满的相片会**停住 0.5 秒**再跳成桌面（用户报的「莫名晃一下」）。
            val lg = aodStyle == AliveStore.STYLE_LIUGUANG
            // 留光：150ms 起始延迟 + 567ms 混合跑完才换层（官方没有换层的概念，
            // 我们两份 Surface 必须等混合到 100% 再抬桌面份，差一帧都看得出来）
            val switchMs =
                if (lg) {
                    AliveLiuguang.EXIT_DELAY_MS + AliveLiuguang.BLEND_EXIT_MS + 40L
                } else {
                    AOD_TO_DESK_SWITCH_MS
                }
            HookRuntime.log(
                if (lg) "$TAG 解锁：AOD → 桌面，留光相片放大铺满（${switchMs - 40}ms）"
                else "$TAG 解锁：AOD → 桌面，跑 tr=2 球扩（1000ms）",
            )
            changeMode(2, animate = true)
            dirty = true
            forceDraw()
            h.postDelayed({
                if (runningKey == null) {
                    unlocking = false
                    return@postDelayed
                }
                // ⚠️ 这 1020ms 内又按灭屏进了 AOD（快速连按电源键）⇒ 本次换层**作废**：
                //    否则桌面份会在球缩到一半时被抬起来（用户 2026-11-09 真机日志里
                //    `灭屏` 后 31ms 就有这条「解锁完成」）。
                if (mode == 0 || aodActive) {
                    unlocking = false
                    HookRuntime.log("$TAG 解锁换层作废（期间又进了 AOD）")
                    return@postDelayed
                }
                // 换层（两份 Surface）。**留光走「先画一帧 + 先抬后压」，启航完全保持原样** ——
                // 用户 2026-11-09 明确要求「影响启航的改动先回退」：启航那套
                // （`applyAlphaBoth(0,1)` → `showingDesktop` → `forceDraw`）真机一直是好的，
                // 我按留光的问题去改它，反而把启航弄异常了。
                if (lg) {
                    showingDesktop = true
                    deskShown = true
                    dirty = true
                    forceDraw()
                    fadeDoneAt = SystemClock.elapsedRealtime()
                    if (sharedLeash) {
                        unlocking = false
                    } else {
                        swapToDesktopLayer(h) { unlocking = false }
                    }
                } else {
                    if (!sharedLeash) applyAlphaBoth(0f, 1f)
                    showingDesktop = true
                    deskShown = true
                    dirty = true
                    forceDraw()
                    unlocking = false
                }
                HookRuntime.log("$TAG 解锁完成（AOD→桌面，换层到桌面份，mode=$mode）")
            }, switchMs)
            return
        }

        // 交叉期间不要跑 changeMode 那套 ratio 动画，由这里逐帧直接写
        ratioDur = 0L
        val started = SystemClock.elapsedRealtime()
        var frames = 0
        val r = object : Runnable {
            override fun run() {
                if (runningKey == null) {
                    fadeTick = null
                    return
                }
                frames++
                val t = ((SystemClock.elapsedRealtime() - started).toFloat() / CROSS_MS)
                    .coerceIn(0f, 1f)
                ratio[0] = 0f
                ratio[1] = 1f - t
                ratio[2] = t
                dirty = true
                forceDraw()
                if (t < 1f) {
                    h.postDelayed(this, 16)
                    return
                }
                fadeTick = null
                fadeDoneAt = SystemClock.elapsedRealtime()
                // 换层：**留光「先设状态 + 画一帧，再换 alpha」；启航完全保持原样**
                // （用户 2026-11-09：「影响启航的改动先回退」）。
                if (aodStyle == AliveStore.STYLE_LIUGUANG) {
                    showingDesktop = true
                    deskShown = true
                    mode = 2
                    AliveGlCore.stateFor(2, expandedScale).copyInto(lens)
                    ratio[0] = 0f; ratio[1] = 0f; ratio[2] = 1f
                    dirty = true
                    forceDraw()
                    if (sharedLeash) {
                        unlocking = false
                    } else {
                        swapToDesktopLayer(h) { unlocking = false }
                    }
                } else {
                    unlocking = false
                    if (!sharedLeash) applyAlphaBoth(0f, 1f)
                    showingDesktop = true
                    deskShown = true
                    mode = 2
                    AliveGlCore.stateFor(2, expandedScale).copyInto(lens)
                    ratio[0] = 0f; ratio[1] = 0f; ratio[2] = 1f
                    dirty = true
                    forceDraw()
                }
                HookRuntime.log("$TAG 解锁交叉完成（${CROSS_MS}ms / $frames 帧，换层到桌面份）")
            }
        }
        fadeTick = r
        h.post(r)
    }

    fun resetAlpha() {
        handler?.post {
            if (runningKey == null) return@post
            runCatching { AliveOverlay.resetAlpha() }
            // 解锁转场进行中：别再改 mode（会把 tr=2 切成硬切）
            if (unlocking) return@post
            if (fadeTick != null) return@post
            if (fadeDoneAt > 0L &&
                SystemClock.elapsedRealtime() - fadeDoneAt < FADE_LATCH_MS
            ) {
                return@post
            }
            val locked = queryKeyguardLocked()
            if (locked != null) showingDesktop = !locked
            // **画面优先**（2026-11-09）：`isKeyguardLocked` 在「已解锁到桌面、面板还没收完」
            // 时会返回 true，照它 `changeMode(1)` 就把 `mode` 从 2 拉回 1 ——
            // 下一次桌面进 AOD 就退化成 tr=1 + ratio 从 `(0,1,0)` 起（先到锁屏态）。
            // [deskShown] 是我们自己换 alpha 时记的，只有它代表屏幕上真正是什么。
            if (deskShown) {
                if (!aodActive) changeMode(2, animate = true)
                if (screenOn()) forceDraw()
                return@post
            }
            if (!showingDesktop) {
                if (abs(currentAlpha - 1f) > 0.001f) {
                    applyAlpha(lockScs(), 1f)
                }
                if (!aodActive) changeMode(1, animate = true)
            } else if (!aodActive) {
                changeMode(2, animate = true)
            }
            if (screenOn()) forceDraw()
        }
    }

    private fun lockScs() = mounts.filter { it.locked }.map { it.sc }

    private fun deskScs() = mounts.filter { !it.locked }.map { it.sc }

    /**
     * **换层**（锁屏份 → 桌面份）：先抬桌面份，**下一帧**再压锁屏份。
     *
     * 为什么不用 [applyAlphaBoth] 一次性 0/1（2026-11-09 用户报「AOD 到桌面还会闪一下，
     * 非常快，一眨眼那种」）：
     *
     * 桌面份的 buffer 是刚刚 `forceDraw()` 画出来的，**fence 可能还没 signal**。
     * 同时把锁屏份压 0，SurfaceFlinger 那一帧就可能「两层都拿不到可用缓冲」
     * ⇒ 露出父层（系统桌面壁纸）= 一帧白闪。拆成两步后，任何一帧都至少有一层是实心的；
     * 中间那 1~2 帧两层同时 alpha=1，内容又都是同一张桌面图，完全看不出叠了。
     *
     * @param onDone 锁屏份真正压掉之后回调（调用方在此之前要保持 `unlocking = true`，
     *   否则 `resetAlpha` 会把 alpha 又抬回来）。
     */
    private fun swapToDesktopLayer(h: Handler, onDone: () -> Unit) {
        if (deskScs().isEmpty()) {
            onDone()
            return
        }
        applyAlpha(deskScs(), 1f)
        h.postDelayed({
            if (runningKey == null) {
                onDone()
                return@postDelayed
            }
            // 中间又灭屏进 AOD ⇒ 别把锁屏份压掉（那时它才是该显示的那份）
            if (mode != 0 && !aodActive) {
                applyAlpha(lockScs(), 0f)
            }
            onDone()
        }, LAYER_SWAP_MS)
    }

    /**
     * **同一帧**把锁屏份与桌面份的 alpha 一起换掉。
     *
     * 分成两次 [applyAlpha]（两个 Transaction）时，若中间被 SurfaceFlinger 拆成两帧提交，
     * 会出现「锁屏已 0、桌面还没 1」的一帧 ⇒ 用户看到锁屏壁纸**闪一下**
     * （2026-11-08 报的「锁屏→桌面过渡时中间闪一下」）。
     */
    private fun applyAlphaBoth(lockA: Float, deskA: Float) {
        currentAlpha = lockA
        dirty = true
        val l = lockScs()
        val d = deskScs()
        if (l.isEmpty() && d.isEmpty()) return
        runCatching {
            val txClass = Class.forName(
                "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
            )
            val tx = txClass.getConstructor().newInstance()
            val setAlpha = txClass.getMethod(
                "setAlpha", android.view.SurfaceControl::class.java, Float::class.javaPrimitiveType,
            )
            for (sc in l) runCatching { setAlpha.invoke(tx, sc, lockA) }
            for (sc in d) runCatching { setAlpha.invoke(tx, sc, deskA) }
            txClass.getMethod("apply").invoke(tx)
        }
    }

    private fun applyAlpha(scs: List<android.view.SurfaceControl>, a: Float) {        currentAlpha = a
        dirty = true
        if (scs.isEmpty()) return
        runCatching {
            val txClass = Class.forName(
                "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
            )
            val tx = txClass.getConstructor().newInstance()
            val setAlpha = txClass.getMethod(
                "setAlpha", android.view.SurfaceControl::class.java, Float::class.javaPrimitiveType,
            )
            for (sc in scs) runCatching { setAlpha.invoke(tx, sc, a) }
            txClass.getMethod("apply").invoke(tx)
        }
    }

    // ───────────────────────────── 屏幕广播 ─────────────────────────────

    private fun watchScreen(ctx: Context) {
        unregisterScreen()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        HookRuntime.log("$TAG 亮屏")
                        // 解锁转场（tr=2 / 交叉）期间不能插进来改 mode，否则会把转场切成硬切
                        if (!aodActive && !unlocking) {
                            val locked = queryKeyguardLocked()
                            if (locked != null) showingDesktop = !locked
                            // 同 [resetAlpha]：屏幕上还是桌面份就以桌面为准，别把 mode 拉回锁屏
                            changeMode(if (showingDesktop || deskShown) 2 else 1, animate = false)
                            resetAlpha()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // 灭屏不猜 AOD，等 doWallpaperBlackAnim 的真值
                        HookRuntime.log("$TAG 灭屏")
                    }
                }
            }
        }
        runCatching {
            ctx.registerReceiver(r, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            screenReceiver = r
        }
    }

    private fun unregisterScreen() {
        val r = screenReceiver ?: return
        runCatching { appCtx?.unregisterReceiver(r) }
        screenReceiver = null
    }

    private fun screenOn(): Boolean = runCatching {
        val ctx = appCtx ?: return true
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    private fun queryKeyguardLocked(): Boolean? = runCatching {
        (appCtx?.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
            ?.isKeyguardLocked
    }.getOrNull()

    private fun readShowingDesktop(ctx: Context): Boolean {
        val locked = runCatching {
            (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
                .isKeyguardLocked
        }.getOrNull() ?: return HookRuntime.systemProperty(SpatialFlag.PROP_KEYGUARD).trim() == "0"
        return !locked
    }

    private fun readSystemWallpaperAlpha(): Float? {
        val raw = HookRuntime.systemProperty(SpatialWallpaperAlpha.PROP_ALPHA).trim()
        if (raw.isEmpty()) return null
        val v = raw.toIntOrNull() ?: return null
        if (v < 0 || v > 1000) return null
        return v / 1000f
    }

    // ───────────────────────────── 绘制 ─────────────────────────────

    private fun activeMountIsLocked(): Boolean {
        if (mounts.none { !it.locked }) return true
        return !showingDesktop
    }

    /**
     * 一帧只画**当前可见的那一份**。
     *
     * ⚠️ 之前转场期间两份都画（2026-11-08 早先的写法），但桌面份用的是固定的 [deskState]
     * ＋ [DESK_RATIO]，内容**不随时间变**（首帧 `drawEveryMount()` 已经画好），
     * 而交叉期间它的 alpha 恒为 0 —— 重画纯属浪费。
     * 转场时每帧两个全屏 pass，叠加 `sharedLeash` 下 swap 等父层可见性，
     * 实测单帧 166~188ms ⇒ 150ms 的交叉只来得及出 1~2 帧，
     * 观感就是「锁屏壁纸中间闪一下」（用户 2026-11-08 报的第三条）。
     * 砍掉不可见那份后，交叉能稳定出帧。
     */
    private fun drawAllFrames() {
        val wantLocked = activeMountIsLocked()
        val group = mounts.filter { it.locked == wantLocked }.ifEmpty { mounts }
        for (m in group) drawMount(m)
    }

    private fun drawEveryMount() {
        for (m in mounts) drawMount(m)
    }

    private fun forceDraw() {
        if (display == null) return
        drawAllFrames()
    }

    /**
     * 主体 View 刚重建（`AliveOverlay.attach`）：让渲染器立刻补画一帧。
     *
     * 新 `SubjectView` 里 `pMatrix` / 几何全是默认值，而静止的锁屏帧循环已经停笔
     * （[keepTicking] 返回 false）⇒ 不补这一帧就会一直画默认几何（圆圈 + 单点采样）。
     */
    internal fun requestSubjectRefresh() {
        handler?.post {
            if (runningKey == null) return@post
            dirty = true
            forceDraw()
        }
    }

    private fun drawMount(m: Mount): Boolean {
        val d = display ?: return false
        val c = context ?: return false
        val es = m.eglSurface ?: return false
        val t0 = SystemClock.elapsedRealtime()
        if (!EGL14.eglMakeCurrent(d, es, es, c)) {
            swapErr++
            HookRuntime.log("$TAG eglMakeCurrent 失败（locked=${m.locked}）")
            return false
        }
        // 留光的遮罩没就绪 ⇒ **整帧不提交**（Surface 停在上一次的完整画面）。
        // 与官方 `MaskFrames.upload()==false` 时 `return` 一致：几何与遮罩共用一个采样。
        if (!drawScene(m.locked)) return false
        if (EGL14.eglSwapBuffers(d, es)) {
            if (m.locked) swapLock++ else swapDesk++
        } else {
            swapErr++
        }
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost > 60L && slowLogged < 6) {
            slowLogged++
            HookRuntime.log("$TAG 绘制慢 ${cost}ms（锁屏 $swapLock / 桌面 $swapDesk / 失败 $swapErr）")
        }
        return true
    }

    /**
     * 一帧的 pass 图（与上游 `renderer.rs::draw` 的顺序一致）：
     * 照片 → 照片靶；模糊 → AOD 靶；三界混合 → 当前 EGL surface。
     *
     * 2026-11-08 起锁屏 / 桌面是**两张图**：各自 cover 裁到自己的靶，
     * blend 分别绑 `uLock` 与 `uLauncher`；AOD 层仍以**锁屏图**为源（与上游
     * `targets[1]` 一致），所以小球里永远是锁屏画面。
     */
    /** 一张照片 → 它的 cover 裁剪靶（含 Y 翻转，与上游 copy pass 同义）。 */
    private fun copyTo(target: Target, tex: Int, srcAspect: Float) {
        bindTarget(target)
        useProgram(copyProg, uCopy, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uCopy!!.at("uTex"), 0)
        val screenAspect = screenW.toFloat() / screenH
        val cropX: Float
        val cropY: Float
        if (srcAspect > screenAspect) {
            cropX = screenAspect / srcAspect; cropY = 1f
        } else {
            cropX = 1f; cropY = srcAspect / screenAspect
        }
        GLES20.glUniform2f(uCopy!!.at("uCrop"), cropX, cropY)
        GLES20.glUniform1f(uCopy!!.at("uFlip"), 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 留光的 AOD pass：把当前遮罩帧合成成「一张相片」写进 `aodT`。
     *
     * 与球那套完全不同：**没有任何畸变/自转/装饰**，几何只有「相纸在屏幕上的
     * 中心 / 边长 / 角度」三个量，其余全在遮罩序列里（见 [AliveGlCore.LIUGUANG_FRAG]）。
     *
     * @return `false` = 这一帧的遮罩还没解出来，**整帧别画**（连 swap 一起跳过）。
     *   照官方 `MaskFrames.upload()` 返回 false 时 `return` 的语义：几何与遮罩共用
     *   一个采样，宁可掉这一帧，也不让形状和几何错位（2026-11-09 真机「相卡消失 /
     *   扭曲」就是错位画出来的）。
     */
    private fun drawLiuguang(a: Target, now: Long): Boolean {
        val uni = uLiuguang ?: return false
        val st = AliveLiuguang.sample(now)
        if (!ensureMaskTex()) return false
        // ⚠️ 只有**换帧**时才需要解压数据：稳态（AOD 停在 000）纹理里已经是这一帧，
        //    再去 cache 里要一次的话，LRU 淘汰后就白等一轮解码（真机会「静止画面闪黑」）。
        if (st.frame != maskFrame) {
            val buf = AliveMaskFrames.peek(st.frame)
            if (buf == null) {
                // 遮罩没就绪：冻住时间轴，下一帧还用同一个时刻重试
                AliveLiuguang.hold(now)
                return false
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            val ok = runCatching {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    AliveMaskFrames.SIZE, AliveMaskFrames.SIZE,
                    GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf,
                )
            }.isSuccess
            if (!ok) return false
            maskFrame = st.frame
        }

        bindTarget(a)
        useProgram(liuguangProg, uni, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // 内容源：用户自选的 AOD 图优先，否则锁屏图（两者都已 cover 到屏幕比例）
        val contentTex = if (aodPhotoTex != 0) aodPhotoTex else (photoT?.tex ?: return false)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, contentTex)
        GLES20.glUniform1i(uni.at("uPhoto"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glUniform1i(uni.at("uMask"), 1)
        // 桌面图：官方槽 7（`u_photo_alpha`）在退出到桌面时把内容换成它。
        //
        // ⚠️ **要按「非 AOD 那一界」选图**（官方 `aod_pass` 的 endpoint 判定：
        //    paired 且锁屏可见时绑 `targets[1]`，否则绑 `targets[3]`）：
        //    * 从桌面进 AOD / 退出到桌面 ⇒ 内容交叉目标是**桌面图**；
        //    * 从锁屏进 AOD / 退出到锁屏 ⇒ 目标是**锁屏图**。
        //    恒绑桌面图的话，AOD→锁屏 且两张图不同时，内容会淡成桌面图再硬切成锁屏图。
        val bgDesktop = mode == 2 || (mode == 0 && prevMode == 2)
        val bgTex = when {
            bgDesktop -> deskT?.tex ?: (photoT?.tex ?: contentTex)
            else -> photoT?.tex ?: contentTex
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
        GLES20.glUniform1i(uni.at("uLauncher"), 2)

        val aspect = screenH.toFloat() / screenW
        GLES20.glUniform1f(uni.at("uAspect"), aspect)
        GLES20.glUniform1f(uni.at("uScale"), st.scale)
        // ⚠️ pass 内的 y 与屏幕相反（写进 aodT 会翻一次）⇒ 屏幕 0.335 → 这里 0.665
        GLES20.glUniform2f(uni.at("uCenter"), 0.5f, 1f - st.centerY)
        // ⚠️ 取反：着色器里的旋转矩阵是「把屏幕点转进相纸局部」的逆变换，
        //    传 st.rot（−5°）真机出来是**顺时针**（2026-11-09 截图确认）⇒ 传 −st.rot。
        GLES20.glUniform1f(uni.at("uRot"), Math.toRadians(-st.rot.toDouble()).toFloat())
        // 官方 values[3]（contentScale）：AOD 1.0 / 锁屏-桌面 aspect/2.35
        GLES20.glUniform1f(uni.at("uContent"), st.content)
        // 纸背偏移（衬纸中心 − 内容中心，画布比例）：
        // 衬纸在画布上比内容框右移 47/720，不减掉的话采样坐标会掉到负数，
        // 被 `clamp` 压成一条边缘列 —— 真机就是「下层相卡全是横条纹」。
        AliveMaskFrames.peekGray(st.frame)?.let { gf ->
            GLES20.glUniform2f(uni.at("uRearOff"), gf.rearOff[0], gf.rearOff[1])
        }
        // 官方 values[7]（photo alpha）：AOD/锁屏 1、桌面 0 —— 内容框里换的是桌面壁纸
        GLES20.glUniform1f(uni.at("uPhotoAlpha"), st.photoAlpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // 这一帧真的出图了：释放冻结、推进时间轴（够了就收工）
        AliveLiuguang.settle(now)
        return true
    }

    /** 遮罩纹理：720×720 RGB、CLAMP + LINEAR（NPOT 不能 mipmap）。 */
    private fun ensureMaskTex(): Boolean {
        if (maskTex != 0) return true
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        if (id == 0) return false
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        val zero = java.nio.ByteBuffer.allocateDirect(
            AliveMaskFrames.SIZE * AliveMaskFrames.SIZE * 3,
        ).order(java.nio.ByteOrder.nativeOrder()).apply { position(0) }
        runCatching {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                AliveMaskFrames.SIZE, AliveMaskFrames.SIZE, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, zero,
            )
        }
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            HookRuntime.log("$TAG 留光遮罩纹理创建失败（err=$err）")
            GLES20.glDeleteTextures(1, ids, 0)
            return false
        }
        maskTex = id
        maskFrame = -1
        return true
    }

    /**
     * 画一帧。
     *
     * @return `false` = 这一帧不该提交（留光的遮罩还没解出来），调用方**不要 swap** ——
     *   让 Surface 停在上一次的完整画面上，等遮罩好了再一起出。
     */
    private fun drawScene(locked: Boolean): Boolean {
        val p = photoT ?: return false
        val a = aodT ?: return false
        val d = deskT
        // 锁屏份跟全局状态跑转场；桌面份永远按桌面稳态（见 [deskState]）
        val st = if (locked) lens else deskState
        val rt = if (locked) ratio else DESK_RATIO
        if (aodActive && !aodDiagLogged) {
            aodDiagLogged = true
            HookRuntime.log(
                "$TAG AOD诊断[首帧] locked=$locked 视口=${screenW}x$screenH " +
                    "st=(blur=${f(st.blur)} scale=${f(st.scale)} x=${f(st.x)} y=${f(st.y)} " +
                    "decor=${f(st.decorAlpha)}) rt=[${f(rt[0])},${f(rt[1])},${f(rt[2])}] " +
                    "tex=(photo=${photoT?.tex} desk=$deskTex aod=${aodT?.tex})",
            )
        }
        val aspect = screenH.toFloat() / screenW
        val recip = screenW.toFloat() / screenH
        // 一帧只取一次时钟：留光的 aod pass 与 blend 必须用**同一个采样时刻**，
        // 两次 `elapsedRealtime()` 差几毫秒就会让相纸和 launcher 的形状错开。
        val now = SystemClock.elapsedRealtime()

        // 1. 两张照片 → 各自的靶（cover 裁剪 + Y 翻转）。照片不会变 ⇒ 只做一次。
        if (needCopy) {
            copyTo(p, photoTex, photoAspect)
            if (d != null) copyTo(d, deskTex, deskAspect)
            // 靶也开 mipmap：AOD 的 `uZoom = 1.136` 是轻微缩小采样，
            // 没有 mipmap 时球内会有一层细密摩尔纹（切态时最明显）。
            mipmapTarget(p)
            if (d != null) mipmapTarget(d)
            needCopy = false
        }

        subjectAlpha = computeSubjectAlpha(now)

        // 1.5 锁屏景深主体跟随（2026-11-08）：**整套 lens 几何**传下去 ——
        //     主体要用和背景球同源的桶形畸变 + 圆形裁切，只传 scale 只能均匀缩放
        //     （真机表现就是「主体只会缩小消失，不参与变形」）。
        //     只在锁屏份推（桌面份不该有主体）。
        if (locked) {
            if (aodStyle == AliveStore.STYLE_LIUGUANG) {
                // 留光（2026-11-10 改）：**只 poke，不推值**。
                //
                // 推值的问题：主体画的是「GL 这一帧算好的状态」，但它要晚 1~2 帧才上屏
                // ⇒ 相对背景恒差 1~2 帧。留光出 AOD 是 450ms 内位移 0.165 屏高 + 缩放
                // 3.3 倍，一帧就是 ~16px 的错位（用户：「像两个层」）。
                //
                // 空间的渲染压力更大却能完全对齐，因为它在 `onDraw` 里读**实时姿态**：
                // 主体与背景各自的「采样 → 上屏」都是 1 帧，相位差抵消；掉帧时
                // 主体直接跳到最新状态（自愈），而不是重放旧值（累积滞后）。
                //
                // 这里只让 View 重绘，几何 / alpha / 遮罩由 `SubjectView.onDraw` 现场采样
                // （`AliveLiuguang.sampleInto` + `AliveMaskFrames.peekGray`）。
                // 帧循环静止时不会走到这里 ⇒ 稳态不重绘。
                AliveOverlay.onPaperFrame()
            } else {
                // 启航（2026-11-10）：**同样只 poke，不推值**。
                // 几何/alpha/`photoMatrix` 由 View 在 `onDraw` 里调
                // `AliveRenderer.sampleSubject(now)` 现取 —— 与留光同一套数据流，
                // 采样→上屏都是 1 帧，主体和球才真正同相。
                AliveOverlay.onQihangFrame()
            }
        }

        // 2. AOD 层 → aodT（源 = 锁屏图的模糊）
        //    桌面份的 ratio 恒为 (0,0,1)，blend 只采样 launcher ⇒ 这一路整段可省。
        //
        //    留光（2026-11-09）：换成相片 pass。只在「AOD 态或留光的进出场」画 ——
        //    锁屏稳态画它纯属浪费（`ratio[0] = 0`，blend 根本不采样 aodT）。
        // ⚠️ 判据必须带 `ratio[0]`，而且**阈值要和 shader 完全对齐**（2026-11-09 用户截图定位）：
        //    blend 里 `uRatio.x < 0.000001` 才算「aod 不参与」，其余情况**都会取 aod**。
        //    这里原来写 `> 0.0001f`，于是 `ratio[0] ∈ (1e-6, 1e-4]` 这一小段里：
        //      * 我们这边判成「留光不必画」⇒ 走回球分支，aodT 被 `blurSource()`（**整屏模糊图**）覆盖；
        //      * blend 那边仍判成「要取 aod」⇒ 直接把模糊图当画面。
        //    真机就是「1~2 帧桌面壁纸的模糊」（用户 21:19 截图），偶发（每帧 ratio 步进 ~0.03，
        //    落进这个窗口约 0.3%，一次解锁 35 帧 ≈ 10%）。
        //    阈值改成 `> 0f`：只要 ratio 还有一点点 aod 分量，就必须画相片。
        val liuguangPass = aodStyle == AliveStore.STYLE_LIUGUANG &&
            liuguangProg != 0 &&
            (mode == 0 || AliveLiuguang.isRunning() || ratio[0] > 0f)
        if (locked && liuguangPass) {
            if (!drawLiuguang(a, now)) return false
        } else if (locked) {
            val src = blurSource()
            bindTarget(a)
            useProgram(lensProg, uLens, quad)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src)
            GLES20.glUniform1i(uLens!!.at("uPhoto"), 0)
            GLES20.glUniform3f(uLens!!.at("uTranslation"), st.x, st.y, 0f)
            val m = FloatArray(16)
            photoMatrix(screenW.toFloat(), screenH.toFloat(), st, m)
            GLES20.glUniformMatrix4fv(uLens!!.at("uTextureMatrix"), 1, false, m, 0)
            GLES20.glUniform1f(uLens!!.at("uLensScale"), st.scale)
            GLES20.glUniform1f(uLens!!.at("uAspect"), aspect)
            GLES20.glUniform1f(uLens!!.at("uAspectRecip"), recip)
            // ⚠️ 上游是 distortion / scale（不是直接用 9）
            GLES20.glUniform1f(
                uLens!!.at("uDistortion"),
                if (st.scale > 1e-4f) st.distortion / st.scale else 0f,
            )
            GLES20.glUniform1f(uLens!!.at("uZoom"), 1f / (minOf(st.scale, 0.5f) * 4f))
            GLES20.glUniform1f(uLens!!.at("uDecorAlpha"), st.decorAlpha)
            GLES20.glUniform1f(uLens!!.at("uDarkStrength"), 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        // 3. 三界混合 → 屏幕
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        useProgram(blendProg, uBlend, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex)
        GLES20.glUniform1i(uBlend!!.at("uAod"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
        GLES20.glUniform1i(uBlend!!.at("uLock"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        // 桌面层：有独立桌面图就用它，否则沿用锁屏那张（= 上游「桌面与锁屏一致」）
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, d?.tex ?: p.tex)
        GLES20.glUniform1i(uBlend!!.at("uLauncher"), 2)
        GLES20.glUniform3f(uBlend!!.at("uRatio"), rt[0], rt[1], rt[2])
        GLES20.glUniform1f(uBlend!!.at("uDarkStrength"), 0f)
        GLES20.glUniform1i(uBlend!!.at("uSupportLockLauncher"), 1)
        // 留光：用 aod 层的 alpha 把非相片区域压黑（见 BLEND_FRAG）
        GLES20.glUniform1f(
            uBlend!!.at("uAodMask"),
            if (aodStyle == AliveStore.STYLE_LIUGUANG) 1f else 0f,
        )
        // ⚠️ **y 不能取负**（2026-11-08 用户指出「桌面圆心关于中心对称」）。
        //    实测口径：lens pass 写进 aodT 后整幅会翻 y（FBO 原点在左下），所以球在屏幕上的
        //    实际位置是 `0.5 - st.y`（vUv 空间，0=顶）：旧版 `AOD_Y=0.335` 时球落在 16.7%
        //    （`0.5-0.335=0.165`），与真机截图完全吻合。
        //    而 blend 的 `getLauncherColor()` 用 `(0.5 - uTranslation.y)` 算圆心，传 `-st.y`
        //    会得到 `0.5 + st.y` ⇒ 与球关于屏幕中心**镜像**。AOD→桌面时球从 35% 扩到全屏，
        //    放大中心却在 65%，于是看到两条同心带。
        //    传 `+st.y` 后：圆心 `0.5 - st.y`（与球一致）、采样平移 `vUv.y + st.y`
        //    （球心 0.5-st.y 正好采到照片中心 0.5），两处都对。
        //
        // **留光（2026-11-09）：launcher 的形状是方相纸，几何必须换成相纸自己的** ——
        //    `lens` 是启航轨道的参数（AOD 态 scale=0.22 / y=0.19），拿它去画方形会完全错位。
        //    相纸 state 的 `centerY` 是「从屏幕顶部的比例」，blend 要的是「相对中心的偏移、
        //    向上为正」（`getDist` 里 `0.5 - ty`），所以传 `0.5 - centerY`。
        var bScale = st.scale
        var bTx = st.x
        var bTy = st.y
        var bRot = 0f
        // 只有**锁屏份 + AOD 相关**才用相纸形状：桌面份恒按桌面稳态画（它的 ratio 是
        // (0,0,1)，用相纸的小 scale 会把它裁成一个方块）；锁屏/桌面稳态也必须整屏
        // （`uAodShape=0`），否则桌面图会被裁成中间一条、左右露黑。
        val lgShape = locked && aodStyle == AliveStore.STYLE_LIUGUANG &&
            (mode == 0 || AliveLiuguang.isRunning())
        if (lgShape) {
            val lg = AliveLiuguang.sample(now)
            bScale = lg.scale
            bTx = 0f
            bTy = 0.5f - lg.centerY
            bRot = Math.toRadians(-lg.rot.toDouble()).toFloat()
        }
        GLES20.glUniform2f(uBlend!!.at("uTranslation"), bTx, bTy)
        GLES20.glUniform1f(uBlend!!.at("uLensScale"), bScale)
        GLES20.glUniform1f(uBlend!!.at("uLensRot"), bRot)
        GLES20.glUniform1f(uBlend!!.at("uAodShape"), if (lgShape) 1f else 0f)
        GLES20.glUniform1f(uBlend!!.at("uAspect"), aspect)
        GLES20.glUniform1f(uBlend!!.at("uDim"), dim)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return true
    }


    /**
     * 模糊链：down → 横 → 纵 → up，返回最终纹理 id；`blur < 2` 直接返回原图。
     *
     * ⚠️ **结果按 blur 半径缓存**（2026-11-07）：AOD 静止时半径恒为 22，照片也不变，
     * 每帧重跑 4 个全屏 pass 会把 AOD 拖到 ~10fps（真机日志「绘制慢 66~140ms」）。
     * 转场期间半径逐帧插值 ⇒ 每帧都会重算，这是预期（只有那 1 秒）。
     */
    private fun blurSource(): Int {
        val p = photoT ?: return photoTex
        val a = blurA ?: return p.tex
        val b = blurB ?: return p.tex
        if (lens.blur < 2f) {
            blurCacheKey = Float.NaN
            return p.tex
        }
        if (lens.blur == blurCacheKey) return b.tex
        val k = AliveGlCore.kernel(lens.blur, AliveGlCore.BLUR_LIMIT)
        val s = 1f / k.factor
        val texMin = (1f - s) / 2f
        val texMax = (1f + s) / 2f

        // down
        bindTarget(a)
        useProgram(downProg, uDown, quadScaled, s)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
        GLES20.glUniform1i(uDown!!.at("uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 横
        bindTarget(b)
        useProgram(gaussProg, uGauss, quadScaled, s, s, texMin)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex)
        GLES20.glUniform1i(uGauss!!.at("uTex"), 0)
        GLES20.glUniform1fv(uGauss!!.at("uWeights"), k.weights.size, k.weights, 0)
        GLES20.glUniform1fv(uGauss!!.at("uOffsets"), k.offsets.size, k.offsets, 0)
        GLES20.glUniform1i(uGauss!!.at("uRadius"), k.weights.size)
        GLES20.glUniform1i(uGauss!!.at("uHorizontal"), 1)
        GLES20.glUniform1f(uGauss!!.at("uTexMin"), texMin)
        GLES20.glUniform1f(uGauss!!.at("uTexMax"), texMax)
        GLES20.glUniform1f(uGauss!!.at("uStepX"), 1f / screenW)
        GLES20.glUniform1f(uGauss!!.at("uStepY"), 1f / screenH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 纵
        bindTarget(a)
        useProgram(gaussProg, uGauss, quadScaled, s, s, texMin)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex)
        GLES20.glUniform1i(uGauss!!.at("uTex"), 0)
        GLES20.glUniform1fv(uGauss!!.at("uWeights"), k.weights.size, k.weights, 0)
        GLES20.glUniform1fv(uGauss!!.at("uOffsets"), k.offsets.size, k.offsets, 0)
        GLES20.glUniform1i(uGauss!!.at("uRadius"), k.weights.size)
        GLES20.glUniform1i(uGauss!!.at("uHorizontal"), 0)
        GLES20.glUniform1f(uGauss!!.at("uTexMin"), texMin)
        GLES20.glUniform1f(uGauss!!.at("uTexMax"), texMax)
        GLES20.glUniform1f(uGauss!!.at("uStepX"), 1f / screenW)
        GLES20.glUniform1f(uGauss!!.at("uStepY"), 1f / screenH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // up
        bindTarget(b)
        useProgram(upProg, uUp, quad, 1f, s, texMin)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex)
        GLES20.glUniform1i(uUp!!.at("uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        blurCacheKey = lens.blur
        return b.tex
    }

    // ───────────────────────────── GL helper ─────────────────────────────

    private fun createTarget(w: Int, h: Int): Target {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0,
        )
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(st == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO 不完整：0x${Integer.toHexString(st)}" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return Target(tex[0], fbo[0])
    }

    private fun bindTarget(t: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    /**
     * 绑定程序 + 顶点指针 + UV 变换。
     *
     * ⚠️ 降采样 / 高斯那三条要把 quad **缩到中心块**（`aPos × posScale`）；
     * 高斯与升采样还要把采样限定在中心块，靠 `uvScale` / `uvOffset`
     * （= 上游的 `u_texture_matrix`：`uv × s + (1-s)/2`）。
     */
    private fun useProgram(
        prog: Int,
        uni: Uni?,
        buffer: FloatBuffer?,
        posScale: Float = 1f,
        uvScale: Float = 1f,
        uvOffset: Float = 0f,
    ) {
        GLES20.glUseProgram(prog)
        uni?.let {
            GLES20.glUniform2f(it.at("uUvScale"), uvScale, uvScale)
            GLES20.glUniform2f(it.at("uUvOffset"), uvOffset, uvOffset)
        }
        val q = buffer ?: return
        if (posScale != 1f) {
            q.clear()
            var i = 0
            while (i < QUAD.size) {
                q.put(QUAD[i] * posScale)
                q.put(QUAD[i + 1] * posScale)
                q.put(QUAD[i + 2])
                q.put(QUAD[i + 3])
                q.put(QUAD[i + 4])
                i += 5
            }
        } else if (q === quadScaled) {
            q.clear()
            q.put(QUAD)
        }
        q.position(0)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        if (aPos >= 0) {
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, q)
        }
        if (aUv >= 0) {
            GLES20.glEnableVertexAttribArray(aUv)
            q.position(3)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, q)
            q.position(0)
        }
    }

    private fun uploadBitmap(bmp: Bitmap): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        // ⚠️ **必须走 mipmap**（2026-11-08 用户报「铺满态锯齿」的根因）：
        //    copy pass 要把 2000 宽的照片下采样到 1200（屏幕宽），只有 `GL_LINEAR` 时
        //    每个目标像素只取一个源点（等效点采样），细密纹理直接出锯齿/摩尔纹；
        //    而 lens 的 `uZoom = 0.5` 又把它放大 2 倍 —— 锯齿跟着放大。
        //    光栅那边看着干净不是因为写了抗锯齿，而是 Skia 的 `filterBitmap`
        //    默认 `kLinear + kMipmapLinear`（**缩小采样自带 mipmap**）。
        if (generateMipmapChecked()) {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR_MIPMAP_LINEAR,
            )
        } else {
            HookRuntime.log("$TAG 照片纹理：mipmap 不可用（NPOT），保持 LINEAR")
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return t[0]
    }

    /**
     * 给**当前已绑定**的 2D 纹理生成 mipmap；成功返回 true。
     *
     * ⚠️ 失败时调用方**必须**把 `MIN_FILTER` 留在 `GL_LINEAR`：mipmap 链不完整却用
     * `LINEAR_MIPMAP_*` 采样，纹理会判为不完整 ⇒ 采样全黑（又是一次「黑屏」）。
     * 2000×3555 / 1200×2670 都是 NPOT，ES2 上下文要靠 `GL_OES_texture_npot`，
     * 所以这里先清空历史错误再查本次结果。
     */
    private fun generateMipmapChecked(): Boolean {
        var guard = 0
        while (guard++ < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // 清掉之前残留的错误码，否则会把别人的错当成自己的
        }
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    /** 给渲染靶也开 mipmap（`photoT` / `deskT` 是 copy 的产物，只在 copy 后调用一次）。 */
    private fun mipmapTarget(t: Target) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex)
        if (generateMipmapChecked()) {
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR_MIPMAP_LINEAR,
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ───────────────────────────── 矩阵（照 SceneMatrices 逐行）─────────────────────────────

    private fun photoMatrix(w: Float, h: Float, st: AliveGlCore.LensState, out: FloatArray) {
        val proj = FloatArray(16)
        Matrix.orthoM(proj, 0, -w / 2f, w / 2f, -h / 2f, h / 2f, 1f, 3f)
        invert(proj)
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f, 0f)
        invert(view)

        val scl = FloatArray(16)
        Matrix.setIdentityM(scl, 0); Matrix.scaleM(scl, 0, w, h, 1f); invert(scl)
        val origin = FloatArray(16)
        Matrix.setIdentityM(origin, 0); Matrix.translateM(origin, 0, -w / 2f, -h / 2f, 0f); invert(origin)
        val unpivot = FloatArray(16)
        Matrix.setIdentityM(unpivot, 0); Matrix.translateM(unpivot, 0, 0f, 200f, 0f); invert(unpivot)
        val rot = FloatArray(16)
        Matrix.setRotateM(rot, 0, st.angle, 0f, 0f, 1f); invert(rot)
        val pivot = FloatArray(16)
        Matrix.setIdentityM(pivot, 0); Matrix.translateM(pivot, 0, 0f, -200f, 0f); invert(pivot)
        val trans = FloatArray(16)
        Matrix.setIdentityM(trans, 0); Matrix.translateM(trans, 0, w * st.x, h * st.y, 0f); invert(trans)

        val acc = FloatArray(16)
        val tmp = FloatArray(16)
        Matrix.setIdentityM(acc, 0)
        for (mm in arrayOf(scl, origin, unpivot, rot, pivot, trans, view, proj)) {
            Matrix.multiplyMM(tmp, 0, acc, 0, mm, 0)
            System.arraycopy(tmp, 0, acc, 0, 16)
        }
        // 上游 native 侧再乘 T(0,1) · S(1,-1)
        val flipS = FloatArray(16)
        Matrix.setIdentityM(flipS, 0); Matrix.scaleM(flipS, 0, 1f, -1f, 1f)
        Matrix.multiplyMM(tmp, 0, flipS, 0, acc, 0)
        val flipT = FloatArray(16)
        Matrix.setIdentityM(flipT, 0); Matrix.translateM(flipT, 0, 0f, 1f, 0f)
        Matrix.multiplyMM(out, 0, flipT, 0, tmp, 0)
    }

    private fun invert(m: FloatArray) {
        val r = FloatArray(16)
        if (!Matrix.invertM(r, 0, m, 0)) throw IllegalStateException("奇异矩阵")
        System.arraycopy(r, 0, m, 0, 16)
    }

    // ───────────────────────────── 读图 / 编译 / 停止 ─────────────────────────────

    /**
     * 从 provider 流式读照片。
     *
     * 渲染器跑在 SystemUI 进程，`ctx.filesDir` 是 **SystemUI 自己的**私有目录，
     * 拿不到模块的图（光栅 2026-11-04 踩过同一条）⇒ 必须走 `content://…/alive/<id>/photo`。
     */
    /**
     * 打开一张画面。`slot` = `lock` / `desk`（provider 按路径段区分，2026-11-08 双图）。
     *
     * 走 provider 的原因见 [AliveStore]：SystemUI 进程读不到模块的私有目录。
     */
    private fun openPhoto(ctx: Context, key: String, slot: String): java.io.InputStream? = runCatching {
        ctx.contentResolver.openInputStream(
            android.net.Uri.parse("content://$AUTHORITY/alive/$key/$slot"),
        )
    }.getOrNull()

    /**
     * 读一张槽位图，并**在 CPU 侧**把它 cover 裁到屏幕比例、缩到屏幕尺寸。
     *
     * ⚠️ 这一步是「铺满态锯齿」的正解（2026-11-08，照光栅 `RasterStore.import` 的做法）：
     * 光栅的图在**导入时**就已经是最终尺寸，GPU 全程 1:1 采样，所以它天然没有下采样锯齿
     * —— 跟 mipmap、跟抗锯齿算法都没关系。
     * 我们原来是 2000 宽的原图直接上传，再让 copy pass 用 `GL_LINEAR` 缩到 1200：
     * 那是**点采样**，丢 3/4 像素，细密纹理直接起锯齿/摩尔纹（mipmap 只能缓解，不能根治，
     * 而且 ES2 上 NPOT mipmap 是否可用还看驱动）。
     *
     * 缩放在这里做还有个好处：`photoAspect` 变成屏幕比例 ⇒ `copyTo` 的 crop 恒为 (1,1)，
     * 那个 pass 退化成 1:1 直通。
     */
    private fun loadBitmap(ctx: Context, key: String, slot: String, maxShort: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openPhoto(ctx, key, slot)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sample * 2) >= maxShort) sample *= 2
        val raw = openPhoto(ctx, key, slot)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return null
        return coverToScreen(raw)
    }

    /**
     * 居中 cover 裁剪到屏幕比例，再用 **Skia**（`createScaledBitmap(filter=true)`）缩到屏幕尺寸。
     *
     * 只在换壁纸时跑一次；Skia 的缩放是完整滤波（含 mipmap 语义），比 `GL_LINEAR`
     * 那次单点下采样高一个档。返回的位图可能**就是入参本身**（尺寸正好时），
     * 所以回收要用 `!==` 判断，别把正在用的图 recycle 了。
     */
    private fun coverToScreen(src: Bitmap): Bitmap {
        val dstW = screenW.coerceAtLeast(1)
        val dstH = screenH.coerceAtLeast(1)
        if (src.width == dstW && src.height == dstH) return src
        val srcAspect = src.width.toFloat() / src.height.coerceAtLeast(1)
        val dstAspect = dstW.toFloat() / dstH
        val cropW: Int
        val cropH: Int
        if (srcAspect > dstAspect) {
            // 源更宽 ⇒ 裁两侧
            cropH = src.height
            cropW = (src.height * dstAspect).toInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width / dstAspect).toInt().coerceIn(1, src.height)
        }
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val cropped = if (cropW == src.width && cropH == src.height) {
            src
        } else {
            runCatching { Bitmap.createBitmap(src, left, top, cropW, cropH) }.getOrNull() ?: return src
        }
        val scaled = runCatching {
            Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
        }.getOrNull()
        if (cropped !== src) cropped.recycle()
        if (scaled == null) return src
        if (scaled !== cropped && scaled !== src) src.recycle()
        return scaled
    }

    /**
     * 打开**模块 APK 的 assets**。
     *
     * 渲染器跑在 SystemUI 进程里，`ctx.assets` 是 SystemUI 自己的 —— 里面没有
     * `alive/masks/leave_light/NNN.rgbz`。模块的 assets 只能按已安装包路径
     * `addAssetPath` 挂进来。
     *
     * 失败返回 null（留光会回落到球，不影响启航）—— 只在启动时试一次。
     */
    private fun openModuleAssets(ctx: Context): android.content.res.AssetManager? = runCatching {
        val pkg = AUTHORITY.substringBeforeLast('.')
        val apk = ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
        val am = android.content.res.AssetManager::class.java
            .getDeclaredConstructor().newInstance()
        val add = android.content.res.AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
        add.isAccessible = true
        val cookie = add.invoke(am, apk) as Int
        if (cookie != 0) am else null
    }.onFailure {
        HookRuntime.log("$TAG 模块 assets 挂载失败：${it.message}")
    }.getOrNull()

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            HookRuntime.log("$TAG link 失败：${GLES20.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            HookRuntime.log("$TAG shader 编译失败：${GLES20.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }

    private fun stopInternal(reason: String) {
        val had = runningKey != null
        runningKey = null
        runningScope = -1
        aodActive = false
        unlocking = false
        fadeTick = null
        aodExitTick?.let { handler?.removeCallbacks(it) }
        aodExitTick = null
        framePosted = false
        choreographer?.removeFrameCallback(frameCallback)
        unregisterScreen()
        runCatching { AliveFlag.pushRendering(false) }
        // 景深主体层：撤掉我们的图（层本身还给系统）
        runCatching { AliveOverlay.onDisabled() }

        runCatching {
            if (mounts.isNotEmpty()) {
                val txClass = Class.forName(
                    "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
                )
                val tx = txClass.getConstructor().newInstance()
                val remove = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
                for (m in mounts) {
                    runCatching { remove.invoke(tx, m.sc) }
                    runCatching { m.surface.release() }
                    display?.let { d ->
                        m.eglSurface?.let { es -> runCatching { EGL14.eglDestroySurface(d, es) } }
                    }
                    m.eglSurface = null
                }
                runCatching { txClass.getMethod("apply").invoke(tx) }
            }
        }
        mounts.clear()

        runCatching { if (photoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(photoTex), 0) }
        photoTex = 0
        // ⚠️ `deskTex` 之前漏清 ⇒ 每次换壁纸泄漏一个全屏纹理 + 一个 FBO（≈25MB）
        runCatching { if (deskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(deskTex), 0) }
        deskTex = 0
        runCatching {
            if (aodPhotoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(aodPhotoTex), 0)
        }
        aodPhotoTex = 0
        runCatching { if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0) }
        maskTex = 0
        maskFrame = -1
        AliveMaskFrames.detach()
        AliveLiuguang.reset()
        for (t in listOfNotNull(photoT, deskT, aodT, blurA, blurB)) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0) }
            runCatching { GLES20.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0) }
        }
        photoT = null; deskT = null; aodT = null; blurA = null; blurB = null

        for (p in intArrayOf(
            copyProg, lensProg, liuguangProg, blendProg, downProg, gaussProg, upProg,
        )) {
            if (p != 0) runCatching { GLES20.glDeleteProgram(p) }
        }
        copyProg = 0; lensProg = 0; liuguangProg = 0; blendProg = 0
        downProg = 0; gaussProg = 0; upProg = 0
        uCopy = null; uLens = null; uLiuguang = null; uBlend = null
        uDown = null; uGauss = null; uUp = null
        quad = null
        quadScaled = null

        runCatching {
            val d = display
            if (d != null) {
                val c = context
                if (c != null) EGL14.eglDestroyContext(d, c)
                EGL14.eglTerminate(d)
            }
        }
        display = null
        context = null
        eglConfig = null
        lastTickAt = 0L
        if (had) HookRuntime.log("$TAG 渲染器已停止（$reason）")
    }

    private val QUAD = floatArrayOf(
        -1f, 1f, 0f, 0f, 0f,
        1f, 1f, 0f, 1f, 0f,
        -1f, -1f, 0f, 0f, 1f,
        1f, -1f, 0f, 1f, 1f,
    )
}
