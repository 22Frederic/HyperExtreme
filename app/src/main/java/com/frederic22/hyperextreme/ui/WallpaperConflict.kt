package com.frederic22.hyperextreme.ui

import android.content.Context
import com.frederic22.hyperextreme.data.AliveControl
import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterControl
import com.frederic22.hyperextreme.data.RasterFlag
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
 * 默认**互斥**：都靠"盖住系统壁纸"工作，同时开就是互相遮挡 / 双份渲染。
 * 允许的共存组合只有一种：**仅桌面的桌面链（光栅 / 空间）+ Alive 仅锁屏** ——
 * 两条链各占一条 leash 的不同态。Alive「全局 + 桌面留空」桌面同样没有 Alive 份，
 * 在 [detect] 里按仅锁屏归一，也能共存。
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

/** 正在运行的那条链 + 它的挂载方式 / 生效范围。 */
internal data class RunningWallpaper(
    val chain: WallpaperChain,
    val mode: WallpaperMode,
    val scope: Int = MaskPrefs.SPATIAL_SCOPE_GLOBAL,
    /** Alive 全局档桌面留空（`DESK_NONE`）：桌面没有 Alive 份，[scope] 已按仅锁屏归一。 */
    val deskEmpty: Boolean = false,
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
     * 顺序就是优先级：视差 → 空间 → 光栅 → Alive。默认互斥，允许的共存组合见 [coexist]；
     * 脏数据（比如手动改过 prefs）下按这个顺序全部返回，由调用方决定怎么显示。
     */
    private fun detect(ctx: Context, state: MaskUiState): List<RunningWallpaper> {
        val prefs = MaskPrefs(ctx)
        val out = ArrayList<RunningWallpaper>(4)
        if (state.parallaxActive) {
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
            out.add(
                RunningWallpaper(
                    WallpaperChain.RASTER,
                    WallpaperMode.HOOK,
                    runningScope(
                        RasterFlag.PROP_ACTIVE, RasterFlag.PROP_SCOPE, prefs.rasterScope,
                    ),
                ),
            )
        }
        if (SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java)) {
            out.add(RunningWallpaper(WallpaperChain.RASTER, WallpaperMode.SERVICE))
        }
        // Alive 只有 HOOK 一条链（要 AOD 动画，系统壁纸服务那条路做不了）。
        if (prefs.aliveActiveId.isNotEmpty()) {
            // **全局档桌面留空**（2026-11-17）：桌面没有 Alive 份，按仅锁屏参与共存。
            // 判据走属性通道（SystemUI 真正在用的 `deskMode`），拿不到就当全局、不放行。
            val aliveScope = runningScope(
                AliveFlag.PROP_ACTIVE, AliveFlag.PROP_SCOPE, prefs.aliveScope,
            )
            val deskEmpty = systemProperty(AliveFlag.PROP_ACTIVE)?.trim() == "1" &&
                systemProperty(AliveFlag.PROP_DESK_MODE)?.trim()?.toIntOrNull() ==
                AliveFlag.DESK_NONE
            out.add(
                RunningWallpaper(
                    WallpaperChain.ALIVE,
                    WallpaperMode.HOOK,
                    if (deskEmpty) MaskPrefs.SPATIAL_SCOPE_LOCK else aliveScope,
                    deskEmpty = deskEmpty,
                ),
            )
        }
        return out
    }

    /**
     * 生效中的链在 SystemUI 里**真正用着的范围**（属性通道）。
     *
     * 页面上的下拉框会先写 prefs，要等下一次「应用」才 push —— 拿 prefs 会把
     * 「刚改成仅桌面但还没重新应用」误判成已生效，从而错误放行共存。
     */
    private fun runningScope(activeProp: String, scopeProp: String, fallback: Int): Int {
        if (systemProperty(activeProp)?.trim() != "1") return fallback
        return systemProperty(scopeProp)?.trim()?.toIntOrNull()?.takeIf { it in 0..2 } ?: fallback
    }

    /**
     * 除 [self] 之外正在运行的链；没有返回 null。
     *
     * 同一 chain 的两种模式一起排除 —— 页面自己不需要被自己拦住。
     */
    fun running(
        ctx: Context,
        state: MaskUiState,
        self: WallpaperChain,
        selfScope: Int = MaskPrefs.SPATIAL_SCOPE_GLOBAL,
        selfMode: WallpaperMode = WallpaperMode.HOOK,
    ): RunningWallpaper? = detect(ctx, state).firstOrNull {
        it.chain != self && !coexist(self, selfScope, selfMode, it)
    }

    /**
     * 允许的共存组合：**仅桌面的桌面链（光栅 / 空间）+ Alive 仅锁屏**（都是 LSPosed 挂载）。
     *
     * 两者的层分别挂在桌面 / 锁屏 leash 上；系统只有一条 leash 时由两个渲染器
     * 按锁屏态互补 alpha（见 `RasterRenderer.setDeskOnlyAlpha` / `SpatialRenderer.setDeskOnlyAlpha`
     * / `AliveRenderer` 的仅锁屏档）。Alive 全局档桌面留空在 [detect] 里已归一成仅锁屏。
     */
    private fun coexist(
        self: WallpaperChain,
        selfScope: Int,
        selfMode: WallpaperMode,
        other: RunningWallpaper,
    ): Boolean {
        if (selfMode != WallpaperMode.HOOK || other.mode != WallpaperMode.HOOK) return false
        val selfDesktop = self == WallpaperChain.RASTER || self == WallpaperChain.SPATIAL
        val otherDesktop =
            other.chain == WallpaperChain.RASTER || other.chain == WallpaperChain.SPATIAL
        return when {
            selfDesktop && selfScope == MaskPrefs.SPATIAL_SCOPE_DESKTOP ->
                other.chain == WallpaperChain.ALIVE && other.scope == MaskPrefs.SPATIAL_SCOPE_LOCK
            self == WallpaperChain.ALIVE && selfScope == MaskPrefs.SPATIAL_SCOPE_LOCK ->
                otherDesktop && other.scope == MaskPrefs.SPATIAL_SCOPE_DESKTOP
            else -> false
        }
    }

    /**
     * **所有正在运行的那几条**（主页「当前运行」卡用，2026-11-17）。
     *
     * 与 [running] 共用同一份 [detect]：主页与冲突弹窗对「在不在跑」永远同一个答案。
     * 允许共存的组合会返回两条。
     */
    fun currentAll(ctx: Context, state: MaskUiState): List<RunningWallpaper> =
        detect(ctx, state)

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
