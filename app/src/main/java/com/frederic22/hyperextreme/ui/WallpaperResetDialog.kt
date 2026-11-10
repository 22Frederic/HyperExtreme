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
 * 「壁纸重置提示」弹窗（2026-11-10，用户要求）。
 *
 * ## 为什么要有这一弹
 *
 * Alive 的解锁动画（AOD→桌面 tr=2 球扩 / 锁屏→桌面内容交叉）画在**锁屏份**上，
 * 而锁屏份挂在「锁屏壁纸窗口」里：
 *
 * * 系统锁屏壁纸走 `MiuiKeyguardPictorialWallpaper`（开景深）时，该窗口解锁即被收走
 *   ⇒ 动画画给空气（日志里 28~31 帧一帧不少，屏幕上看不见）；
 * * 系统与锁屏是**两张不同壁纸**时是两条独立 leash，换层时桌面份 buffer 陈旧 ⇒ 露系统壁纸。
 *
 * 两种状态都属于「壁纸组合不干净」。这里不去改挂载逻辑，而是**每次应用前把壁纸
 * 重置成系统默认**（与服务模式「恢复默认」同一条 `clear(FLAG_SYSTEM or FLAG_LOCK)`），
 * 让系统只剩一条共用 leash，从根上规避。
 *
 * ## 交互
 *
 * 无条件弹（不判断当前组合），因为判断本身要 root 读 `wallpaper_info.xml`，
 * 而重置的代价只是「壁纸变回系统默认」——用户在锁屏编辑里另建一个组合即可。
 * 确认按钮用**警示色**（`danger`），与「删除此搭配？」同级，提示这是破坏性操作。
 */
@Composable
internal fun WallpaperResetDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "壁纸重置提示",
        summary = "每次应用Alive壁纸时，都会重置到系统默认壁纸，" +
            "建议在锁屏编辑专门新建一个壁纸组合防止原有壁纸被替换。如确定，请点击继续。",
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
                    text = "继续",
                    danger = true,
                    onClick = {
                        onConfirm()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
