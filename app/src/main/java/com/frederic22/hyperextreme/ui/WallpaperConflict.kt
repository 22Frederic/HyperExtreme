package com.frederic22.hyperextreme.ui

import android.content.Context
import com.frederic22.hyperextreme.data.AliveControl
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterControl
import com.frederic22.hyperextreme.data.SpatialControl
import com.frederic22.hyperextreme.wallpaper.RasterServiceTarget
import com.frederic22.hyperextreme.wallpaper.RasterWallpaperService
import com.frederic22.hyperextreme.wallpaper.SpatialServiceMode
import com.frederic22.hyperextreme.wallpaper.SpatialServiceTarget
import com.frederic22.hyperextreme.wallpaper.SpatialWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 三条壁纸链（2026-11-04）。
 *
 * 三者**互斥**：都靠"盖住系统壁纸"工作，同时开就是互相遮挡 / 双份渲染。
 */
internal enum class WallpaperChain {
    PARALLAX,
    SPATIAL,
    RASTER,
    ALIVE,
}

/** 同一条链的两种挂载方式（只有空间 / 光栅有；视差只有 hook 一条）。 */
internal enum class WallpaperMode {
    HOOK,
    SERVICE,
}

/** 正在运行的那条链 + 它的挂载方式。 */
internal data class RunningWallpaper(
    val chain: WallpaperChain,
    val mode: WallpaperMode,
) {
    /** 弹窗文案里用的名字（用户 2026-11-04：正在运行的名字要对）。 */
    val label: String
        get() = when (chain) {
            WallpaperChain.PARALLAX -> "视差壁纸"
            WallpaperChain.SPATIAL -> "空间壁纸"
            WallpaperChain.RASTER -> "光栅壁纸"
            WallpaperChain.ALIVE -> "Alive壁纸"
        }
}

/**
 * 壁纸链互斥检测（2026-11-04）。
 *
 * ## 为什么需要
 *
 * 视差、空间、光栅都通过"压住系统壁纸"显示，同时开就会互相盖 / 双份渲染。
 * 之前只有**视差页检测空间 LSPosed** 这一条（2026-10-29），另外四个方向全是空的：
 * * 空间页不知道光栅；
 * * 光栅页什么都不知道；
 * * 三条链都各自新增了「系统壁纸服务」模式，也都没互相检测。
 *
 * ## 判据（都是"真的在跑"，不是"开关开着"）
 *
 * | 链 | HOOK | SERVICE |
 * |---|---|---|
 * | 视差 | `state.parallaxActive` | —— |
 * | 空间 | `prefs.spatialActive` | `WallpaperManager` 的壁纸 component 是它 |
 * | 光栅 | `rasterActiveId` 非空且 `mountMode == LSPOSED` | 同上 |
 */
internal object WallpaperConflict {

    /**
     * 所有**真的在跑**的链（2026-11-08）。
     *
     * 顺序就是优先级：视差 → 空间 → 光栅 → Alive。四链互斥，正常最多一条；
     * 脏数据（比如手动改过 prefs）下按这个顺序取第一条。
     */
    private fun detect(ctx: Context, state: MaskUiState): List<RunningWallpaper> =
        detect(ctx, state.parallaxActive)

    /**
     * 同上，但视差状态由调用方给（2026-11-25）。
     *
     * 存在的理由：`HomeCover` 在 **IO 线程**解析封面，那边不能读 Compose 的 [MaskUiState]
     * （快照状态只在主线程语义下可靠），从 prefs 取一个布尔再传进来即可 —— 两者本来就同源
     * （`MaskUiState.parallaxActive` 初值就是 `prefs.parallaxBgActive`）。
     */
    private fun detect(ctx: Context, parallaxActive: Boolean): List<RunningWallpaper> {
        val prefs = MaskPrefs(ctx)
        val out = ArrayList<RunningWallpaper>(4)
        if (parallaxActive) {
            out.add(RunningWallpaper(WallpaperChain.PARALLAX, WallpaperMode.HOOK))
        }
        if (prefs.spatialActive) {
            out.add(RunningWallpaper(WallpaperChain.SPATIAL, WallpaperMode.HOOK))
        }
        if (SpatialServiceMode.isRunning(ctx, SpatialWallpaperService::class.java)) {
            out.add(RunningWallpaper(WallpaperChain.SPATIAL, WallpaperMode.SERVICE))
        }
        if (prefs.rasterMountMode == MaskPrefs.RASTER_MOUNT_LSPOSED &&
            prefs.rasterActiveId.isNotEmpty()
        ) {
            out.add(RunningWallpaper(WallpaperChain.RASTER, WallpaperMode.HOOK))
        }
        if (SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java)) {
            out.add(RunningWallpaper(WallpaperChain.RASTER, WallpaperMode.SERVICE))
        }
        // Alive 只有 HOOK 一条链（要 AOD 动画，系统壁纸服务那条路做不了）。
        if (prefs.aliveActiveId.isNotEmpty()) {
            out.add(RunningWallpaper(WallpaperChain.ALIVE, WallpaperMode.HOOK))
        }
        return out
    }

    /**
     * 除 [self] 之外正在运行的链；没有返回 null。
     *
     * 同一 chain 的两种模式一起排除 —— 页面自己不需要被自己拦住。
     */
    fun running(ctx: Context, state: MaskUiState, self: WallpaperChain): RunningWallpaper? =
        detect(ctx, state).firstOrNull { it.chain != self }

    /**
     * **当前正在运行的那一条**（主页「当前运行」卡用，2026-11-08）。
     *
     * 与 [running] 共用同一份 [detect]：主页与冲突弹窗对「在不在跑」永远同一个答案。
     */
    fun current(ctx: Context, state: MaskUiState): RunningWallpaper? =
        current(ctx, state.parallaxActive)

    /** 同上，视差状态由调用方给（IO 线程用，见 [detect] 的重载说明）。 */
    fun current(ctx: Context, parallaxActive: Boolean): RunningWallpaper? =
        detect(ctx, parallaxActive).firstOrNull().also {
            lastKnown = it
            lastKnownValid = true
        }

    /**
     * **组合期用**：有上一次的结果就直接给，没有才真的检测一次（2026-11-25）。
     *
     * 为什么要它：主页被 pager 销毁重建时（功能页 ↔ 设置页会把主页踢出/拉进组合树），
     * `remember { current(...) }` 会在**主线程组合期**重新跑一遍 [detect] —— 服务模式下
     * 那是 4 次 `WallpaperManager#getWallpaperInfo` 的跨进程调用，一次翻页就能掉帧。
     * 运行状态不会因为"翻了个页"而变，读缓存足够；真要看最新值的地方（ON_RESUME）
     * 仍然走 [current] 强制检测。
     */
    fun cached(ctx: Context, state: MaskUiState): RunningWallpaper? =
        if (lastKnownValid) lastKnown else current(ctx, state)

    /** [cached] 的缓存（进程内）。主线程与 IO 线程都会写，用 `@Volatile` 兜可见性。 */
    @Volatile
    private var lastKnown: RunningWallpaper? = null

    @Volatile
    private var lastKnownValid = false

    /**
     * 关闭指定那条链（幂等）。
     *
     * **suspend**：服务模式走 `WallpaperManager.clear()`，同步 binder 要几百毫秒，
     * 必须放 IO 线程（空间页 2026-10-31 踩过"点一下卡一下"）。
     */
    suspend fun disable(ctx: Context, state: MaskUiState, target: RunningWallpaper) {
        when (target.chain) {
            WallpaperChain.PARALLAX -> state.disableParallax()
            WallpaperChain.SPATIAL -> if (target.mode == WallpaperMode.SERVICE) {
                withContext(Dispatchers.IO) {
                    SpatialServiceMode.disable(ctx) { SpatialServiceTarget.clear(it) }
                }
            } else {
                SpatialControl.disable(ctx)
            }
            WallpaperChain.RASTER -> if (target.mode == WallpaperMode.SERVICE) {
                withContext(Dispatchers.IO) {
                    SpatialServiceMode.disable(ctx) { RasterServiceTarget.clear(it) }
                }
            } else {
                // 冲突关闭保持**静默**（用户点的是"关闭并应用"，紧接着会有自己的 toast）。
                RasterControl.disable(ctx, null)
            }
            WallpaperChain.ALIVE -> AliveControl.disable(ctx, null)
        }
    }

    /** 弹窗正文：「当前**XX**已生效，应用**YY**需先关闭」。 */
    fun summary(other: RunningWallpaper, self: WallpaperChain): String {
        val selfLabel = when (self) {
            WallpaperChain.PARALLAX -> "视差壁纸"
            WallpaperChain.SPATIAL -> "空间壁纸"
            WallpaperChain.RASTER -> "光栅壁纸"
            WallpaperChain.ALIVE -> "Alive壁纸"
        }
        return "当前${other.label}已生效，应用${selfLabel}需先关闭"
    }
}
