package com.frederic22.hyperextreme.ui.nav

import androidx.compose.ui.unit.dp

/**
 * 浮页（`GlassBottomSheet`）内容的底部留白。
 *
 * 由来：这个值原来等于悬浮底栏距屏幕底的距离，浮页照着它留一截，免得最后一张卡片贴边。
 * 底栏 2026-11-19 换成 miuix 的 `GlassNavigationBar` 之后，底栏位置改由组件的
 * `defaultWindowInsetsPadding`（系统导航条 inset + 官方浮动边距）决定，**不再引用这个值**；
 * 这里只保留浮页需要的那截留白，数值沿用旧视觉。
 */
internal val NavBarBottomGap = 26.dp
