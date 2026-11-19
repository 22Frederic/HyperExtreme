package com.frederic22.hyperextreme.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.CheckboxColors
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole

/**
 * 项目里所有对话框的统一外壳（2026-11-18，miuix 0.9.5）。
 *
 * 只是 [GlassDialog] 的一层薄包装，做两件事：
 *  1. 把 `backdrop` 从 [LocalPageBackdrop] 取出来，调用方不用逐个传；
 *  2. 保持旧 `WindowDialog` 的参数习惯（`show` / `onDismissRequest` / `title` / `summary`
 *     + content slot），19 个调用点只改函数名。
 *
 * ## 为什么不直接写 GlassDialog
 *
 * `GlassDialog` 的 `backdrop` 是**必填**参数，19 处各写一遍容易漏；而且将来要整体回退成
 * 不透明弹窗、或统一换按钮样式，只改这一处。
 *
 * ## 按钮（2026-11-18 用户定）
 *
 * 内容区**继续用 [TextButtonLike]**（玻璃卡 + 无底色文字按钮）—— 这一轮只换外壳。
 * 官方 `GlassDialogButton` 是**填充按钮**，换过去要重排 `weight(1f)` 的布局，另开一轮。
 *
 * ## 玻璃背景
 *
 * `backdrop = null` 时（裸 Activity / 首帧还没上报 / 设备不支持 AGSL）自动退回
 * `GlassDialogDefaults.containerColor()` 的不透明底 —— 官方设计如此，不会崩。
 * 采样范围由 [LocalPageBackdrop] 保证：只含页面内容与底色，不含弹窗自身及其遮罩。
 */
@Composable
internal fun AppGlassDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    GlassDialog(
        onDismissRequest = onDismissRequest,
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = title,
        summary = summary,
        content = content,
    )
}

/**
 * 弹窗里 [top.yukonga.miuix.kmp.basic.Checkbox] 的配色（2026-11-18）。
 *
 * 玻璃弹窗底上，`CheckboxDefaults` 的默认未勾选底色是 `colorScheme.secondary`
 *（实灰），和玻璃面不搭 —— 用户实测「还是原来的」。
 * 这里把**未勾选**档换成与 [GlassDialogButtonRole.Normal] 按钮**同一个来源**的配色
 *（`onBackground @ 6%(浅)/14%(深)` + `onBackground @ 0.9` 前景），和按钮的淡底一致；
 * **勾选态保持默认**（`primary` 底 + `onPrimary` 前景），不动。
 *
 * ⚠️ 代价：未勾选的框会比原来淡很多，浅色玻璃上接近"只剩一个圆圈轮廓"。
 * 2026-11-18 用户定「先与按钮一致，不加描边」。
 *
 * 用法：`Checkbox(state = …, onClick = …, colors = appCheckboxColors())`
 */
@Composable
internal fun appCheckboxColors(): CheckboxColors {
    val normal = GlassDialogButtonDefaults.buttonColors(GlassDialogButtonRole.Normal)
    return CheckboxDefaults.checkboxColors(
        uncheckedBackgroundColor = normal.color,
        uncheckedForegroundColor = normal.contentColor,
    )
}
