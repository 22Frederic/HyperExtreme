package com.frederic22.hyperextreme.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.platformDynamicColors

/** 界面配色模式。深浅色切换；是否额外用系统的 Monet 动态色由「启用Monet颜色」开关决定。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    fun toColorSchemeMode(): ColorSchemeMode = when (this) {
        SYSTEM -> ColorSchemeMode.System
        LIGHT -> ColorSchemeMode.Light
        DARK -> ColorSchemeMode.Dark
    }

    companion object {
        fun of(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * 建一个随 [mode] / [monet] 变化的 `ThemeController`。
 *
 * 为什么不能就地复用：`ThemeController` 的 `colorSchemeMode` / `lightColors` / `darkColors`
 * 都是 **val**，换深浅色或换配色方案只能换实例（这条从最开始就是这么写的）。
 *
 * [monet] 打开时用 miuix 的 `platformDynamicColors` 读**系统动态色**（Android 12+ 由壁纸取色，
 * 即 `android.R.color.system_accent*` / `system_neutral*` 那一套，miuix 再按自己的调色板重算）；
 * 关掉时走 miuix 自带配色 —— 与加这个开关之前**逐像素一致**，老用户升级后零变化。
 */
@Composable
fun rememberThemeController(mode: ThemeMode, monet: Boolean): ThemeController {
    // `platformDynamicColors` 是 @Composable，不能塞进 remember 的 block 里（block 不是组合上下文），
    // 所以先在上面算好；开关关着时连算都不算（它要做整套 Monet 色调映射，没必要白跑）。
    val light = if (monet) platformDynamicColors(dark = false) else null
    val dark = if (monet) platformDynamicColors(dark = true) else null
    return remember(mode, monet) {
        if (light != null && dark != null) {
            ThemeController(
                colorSchemeMode = mode.toColorSchemeMode(),
                lightColors = light,
                darkColors = dark,
            )
        } else {
            ThemeController(colorSchemeMode = mode.toColorSchemeMode())
        }
    }
}
