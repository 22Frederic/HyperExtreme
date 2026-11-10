package com.frederic22.hyperextreme.data

/**
 * Alive 壁纸开关的跨进程传递（2026-11-07）。
 *
 * 与 [SpatialFlag] / [RasterFlag] 同一套路：SystemUI 里读模块 prefs 不可靠
 * （`getRemotePreferences` 是陈旧快照），所以只认**系统属性**。`debug.` 前缀不持久化，
 * 重启后靠 `App` 启动时补推。
 *
 * 三条一起推、一起读：渲染器装配时要拿到一致的一组（active / key / scope）。
 *
 * ⚠️ 与光栅的属性**完全独立**（`alive_*` vs `raster_*`）。
 */
object AliveFlag {

    /** 1 = 生效，0 = 关闭。 */
    const val PROP_ACTIVE = "debug.hyperextreme.alive_active"

    /** 正在应用的卡 id（`files/alive/<id>/` 的目录名）。关闭时置空。 */
    const val PROP_KEY = "debug.hyperextreme.alive_key"

    /**
     * 生效范围：与空间 / 光栅同值域（0 锁屏 / 1 桌面 / 2 全局）。
     *
     * Alive 目前**没有界面开关**，固定推 [MaskPrefs.SPATIAL_SCOPE_GLOBAL]：
     * 锁屏、桌面、AOD 都要有画面，缺任何一条都会在切换时露系统壁纸。
     */
    const val PROP_SCOPE = "debug.hyperextreme.alive_scope"

    /**
     * 息屏样式：0 = 启航（圆球）、1 = 留光（相片）。见 `AliveStore.STYLE_*`。
     *
     * 渲染器在 SystemUI 进程里读不到模块私有目录的 meta.json，所以样式也必须走属性。
     * 与 active / key / scope 一起推 —— 单独推会在重启渲染器时出现「先用旧样式画一帧」。
     */
    const val PROP_STYLE = "debug.hyperextreme.alive_style"

    /**
     * **图片版本**（`AliveItem.imageVer`，文件名拼串的哈希）—— 2026-11-09 补。
     *
     * 换图（锁屏 / 桌面 / AOD / 主体）时卡的 **id 不变**，只靠 `PROP_KEY` 判不出变化，
     * 渲染器就不会重启 ⇒ 新图永远进不了 GL（真机表现：「自己导入的 AOD 图无效」）。
     * 每次应用都推一个新哈希，轮询纳入重启判据。
     */
    const val PROP_VER = "debug.hyperextreme.alive_ver"

    /** 渲染器是否**真的**在跑（1 = 已挂上并出过帧）。排查「点了应用但没反应」用。 */
    const val PROP_RENDERING = "debug.hyperextreme.alive_rendering"

    /**
     * 当前这张卡**有没有锁屏景深主体**（1 = 有）。
     *
     * `SpatialForegroundHider` 靠它决定怎么处理 `keyguard_foreground_layer`：
     * 没有主体 ⇒ 整层压掉（否则旧壁纸的人浮在新图上）；有主体 ⇒ **保留这一层**，
     * 把里面的 `deducted_image_view` 换成我们的主体（见 `AliveOverlay`）。
     */
    const val PROP_DEPTH = "debug.hyperextreme.alive_depth"

    /** 异步写属性；root 不可用时静默失败（界面状态仍然正确，只是不生效）。 */
    fun push(
        active: Boolean,
        key: String?,
        scope: Int = MaskPrefs.SPATIAL_SCOPE_GLOBAL,
        depth: Boolean = false,
        style: Int = AliveStore.STYLE_QIHANG,
        ver: String = "",
    ) {
        val script = "setprop $PROP_ACTIVE ${if (active) 1 else 0}; " +
            "setprop $PROP_KEY ${key.orEmpty()}; " +
            "setprop $PROP_SCOPE $scope; " +
            "setprop $PROP_DEPTH ${if (active && depth) 1 else 0}; " +
            "setprop $PROP_STYLE ${if (active) style else 0}; " +
            "setprop $PROP_VER ${if (active) ver else ""}"
        Thread {
            val r = runCatching { RootShell.run(script) }.getOrNull()
            if (r == null || !r.ok) {
                android.util.Log.w("HyperExtreme", "AliveFlag: setprop 失败（${r?.output?.take(160) ?: "异常"}）")
            }
        }.start()
    }

    /** 渲染器自报"正在出帧"（只用于诊断）。 */
    internal fun pushRendering(on: Boolean) {
        Thread {
            runCatching { RootShell.run("setprop $PROP_RENDERING ${if (on) 1 else 0}") }
        }.start()
    }
}
