package com.frederic22.hyperextreme.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 系统「息屏显示」的总开关（2026-11-10）。
 *
 * ## 口径与系统自己完全一致
 *
 * 反编译实证（`com.miui.keyguard.shortcuts.manager.ShortcutManager#aodEnable`、
 * `com.miui.keyguard.editor.utils.MiuiSettingsUtils#aodEnable`）：
 *
 * ```java
 * aodEnable(ctx)     = Settings.Secure.getInt(cr, "doze_always_on", 0) == 1
 * fullAodEnable(ctx) = aodEnable(ctx) && Settings.Secure.getInt(cr, "full_screen_aod_on", 0) == 1
 * ```
 *
 * 也就是说：
 *
 * | 设置项 | 含义 | 取值 |
 * |---|---|---|
 * | `doze_always_on` | **总开关**（设置页里那个「息屏显示」） | 0 关 / 1 开 |
 * | `full_screen_aod_on` | **息屏样式**（不是开关） | 0 自定义 / 1 与锁屏一致 |
 *
 * ⚠️ 别用 `aod_show_style` 判开关：它是「显示模式」（0 关闭 / 1 定时 / 2 ? / 3 智能），
 * 总开关关掉时它**仍保留上次的选择**（真机截图：开关是关的，显示模式还写着「智能显示」）。
 *
 * ⚠️ 也别只看 `full_screen_aod_on`：它只表示「样式是不是与锁屏一致」，
 * 总开关关着时它同样可以是 0/1 —— 用户 2026-11-10 的原话是「AOD 就自定义和与锁屏一致
 * 两个状态」，那是**样式**的两个状态，不是开/关。
 */
object AodState {

    /** 总开关（设置页的「息屏显示」）。 */
    const val KEY_ENABLED = "doze_always_on"

    /** 息屏样式：0 自定义 / 1 与锁屏一致。 */
    const val KEY_FULL_SCREEN = "full_screen_aod_on"

    fun enabled(cr: ContentResolver): Boolean = runCatching {
        Settings.Secure.getInt(cr, KEY_ENABLED, 0) == 1
    }.getOrDefault(false)

    fun enabled(ctx: Context): Boolean = enabled(ctx.contentResolver)

    /** 息屏样式是不是「与锁屏一致」（自定义模式为 false；总开关关着时无意义）。 */
    fun fullScreen(ctx: Context): Boolean = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, KEY_FULL_SCREEN, 0) == 1
    }.getOrDefault(false)

    /**
     * 监听总开关变化（主线程回调）。
     *
     * 返回值由调用方保活 —— 被 GC 掉 observer 就静默失效了。
     */
    fun observe(ctx: Context, onChange: (Boolean) -> Unit): ContentObserver? = runCatching {
        val cr = ctx.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange(enabled(cr))
            }
        }
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_ENABLED), false, obs)
        obs
    }.getOrNull()
}
