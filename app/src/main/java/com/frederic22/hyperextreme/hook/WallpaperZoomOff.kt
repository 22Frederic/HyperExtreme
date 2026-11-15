package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.RasterFlag
import com.frederic22.hyperextreme.data.SpatialFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * **关掉系统对壁纸 leash 的 zoom-out**（2026-11-15，跑在 `com.miui.miwallpaper` 进程）。
 *
 * ## 为什么
 *
 * `WallpaperService.Engine#shouldZoomOutWallpaper()` 返回 true 时，系统在「锁屏 → 桌面」
 * 会对**壁纸 leash** 做一次 zoom-out（`setMatrix` 缩放）。我们的 GL / Alive 层都是 leash 的
 * **子层**，会跟着一起缩；而系统壁纸自己那条 buffer 链上有一个 `Wallpaper Transform wrapper`
 * 中间层做反向补偿 —— 结果就是**只有我们这一层在动**。
 *
 * 真机表现（用户 2026-11-15）：「Alive 进桌面时，桌面 leash 有极小幅度但能被感知的摆动缩放」。
 *
 * ## 做法
 *
 * 只在我们**某条链真的生效时**返回 false（不影响用户平时看到的系统原生壁纸缩放）：
 *
 * | 类 | 对应 |
 * |---|---|
 * | `MiuiKeyguardPictorialWallpaper$KeyguardEngine` | 锁屏景深壁纸 |
 * | `ImageWallpaper$DesktopEngine` | 桌面壁纸 |
 *
 * 两者都 `extends UniversalWallpaper$UniversalEngine`，方法体都是
 * `return EngineService.H()`（dexdump 核过），所以直接 hook 方法返回 false 即可 ——
 * 不用碰 `EngineService`，也不依赖它的混淆名。
 */
internal object WallpaperZoomOff {

    private const val CLS_PICTORIAL =
        "com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper\$KeyguardEngine"
    private const val CLS_IMAGE =
        "com.miui.miwallpaper.wallpaperservice.ImageWallpaper\$DesktopEngine"
    private const val METHOD = "shouldZoomOutWallpaper"

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        var ok = 0
        for (name in listOf(CLS_PICTORIAL, CLS_IMAGE)) {
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            val m = cls.declaredMethods.firstOrNull {
                it.name == METHOD && it.parameterCount == 0
            } ?: continue
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 我们没在显示时完全不干预 —— 保证原生缩放体验不变
                        if (!anyChainActive()) return chain.proceed()
                        return false
                    }
                })
                ok++
            }.onFailure {
                HookRuntime.log("壁纸 zoom-off 挂 $name.$METHOD 失败：$it")
            }
        }
        if (ok > 0) {
            HookRuntime.log(
                "壁纸 zoom-off 已挂上 $ok 个 $METHOD（任一链生效时返回 false，消除进桌面时的摆动缩放）",
            )
        } else {
            HookRuntime.log("壁纸 zoom-off 未挂上（没找到 $METHOD）")
        }
        return ok > 0
    }

    /**
     * 任一条链生效中吗。
     *
     * 调用频率极低（每次解锁 / 进桌面一次），**不缓存** —— 三种状态切换很快，
     * 缓存反而容易读到旧值。三条链互斥，最多三次 `getprop`。
     */
    private fun anyChainActive(): Boolean =
        on(SpatialFlag.PROP_ACTIVE) || on(RasterFlag.PROP_ACTIVE) || on(AliveFlag.PROP_ACTIVE)

    private fun on(key: String) = HookRuntime.systemProperty(key).trim() == "1"
}
