package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「功能提醒」弹窗 —— 切到**系统壁纸服务（免 Root）**模式时弹一次（2026-10-30）。
 *
 * 文案按用户 2026-10-31 给的原话，**一整段、不换行**（换行由 `WindowDialog` 自己折）。
 * 下面那行「以后不再弹出该弹窗」默认不勾；**勾了并点确认才写入** —— 用户明确定过
 * "他自己勾的，又不是我们逼他勾的"，所以没有恢复入口。
 *
 * 骨架照 [ClearCacheDialog]：`WindowDialog` + `TextButtonLike`，**常驻组合树、用 show 控制**
 * （条件包裹会让退出动画播不出来）。
 */
@Composable
internal fun MountModeWarnDialog(
    show: Boolean,
    noMoreWarn: Boolean,
    onNoMoreWarnChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "功能提醒",
        // 2026-10-31 改：按钮文案对齐实际（「跳转到服务页设置」/「恢复到系统默认壁纸」），
        // 补一句「锁屏时钟不支持柔光玻璃效果」；「点清除缓存会自动恢复默认壁纸」那句挪去
        // `ClearCacheDialog` 的分支文案里（按运行态分三档），这里不再重复。
        summary = "作为系统壁纸服务加载时，空间壁纸锁屏景深不可用，锁屏时钟不支持柔光玻璃效果，" +
            "生效范围请在点击“跳转到服务页设置”后在系统壁纸服务页进行选择。" +
            "如需关闭，请在锁屏编辑自行更换壁纸或点击“恢复到系统默认壁纸”。",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            // 整行可点：点文字和点勾选框是一个意思（照 RestartDialog 的做法）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNoMoreWarnChange(!noMoreWarn) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "以后不再弹出该弹窗",
                    style = MiuixTheme.textStyles.main,
                    modifier = Modifier.weight(1f),
                )
                Checkbox(
                    state = ToggleableState(noMoreWarn),
                    onClick = { onNoMoreWarnChange(!noMoreWarn) },
                )
            }
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
                    text = "确认",
                    primary = true,
                    onClick = {
                        onConfirm()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
