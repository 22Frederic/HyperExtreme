package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「Alive壁纸应用提示」弹窗（2026-11-16）—— AOD 没开、用户点「应用」时弹。
 *
 * 原来的做法是一句 `Toast`（「AOD未开启，请先开启AOD再使用」），用户只能自己去翻系统设置。
 * 现在给一个「跳转」直达系统**「息屏」一级页**（入口见
 * [com.frederic22.hyperextreme.data.AodState.openSettings]）。
 *
 * 骨架与 [FeatureConflictDialog] 完全一致：正文走 `WindowDialog` 的 `summary`（官方正文位），
 * 按钮区 `SpaceBetween` + 两个 `TextButtonLike`（「跳转」用 `primary` = 主题色）。
 *
 * ⚠️ 与其它弹窗一样**常驻组合树、用 `show` 控制** —— 条件包裹会让退出动画播不出来。
 *
 * 用户 2026-11-16 定：跳转后**不**自动重试应用、回来也**不**自动感知 AOD 已开 ——
 * 用户自己回来再点一次即可（`AodState.enabled` 每次点击都现读，状态天然正确）。
 */
@Composable
internal fun AodRequiredDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onJump: () -> Unit,
) {
    WindowDialog(
        title = "Alive壁纸应用提示",
        summary = "AOD未开启，请先开启AOD再使用。你可以点击“跳转”快速到达系统息屏设置页。",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "跳转",
                    primary = true,
                    onClick = {
                        onJump()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
