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
import com.frederic22.hyperextreme.data.UpdateChecker
import top.yukonga.miuix.kmp.theme.LocalDismissState

/**
 * 「模块发布页」的三项（顺序 = 弹窗里的显示顺序，用户 2026-11-16 指定）。
 *
 * 链接写死在 [UpdateChecker] 里 —— 用户明确「GitHub 与网盘的链接不会换，写在模块内就行」，
 * 所以不走 Gitee 配置，省掉一个可能不同步的字段。
 */
internal val ReleaseEntries = listOf(
    "打开夸克网盘" to UpdateChecker.RELEASE_QUARK,
    "打开百度网盘" to UpdateChecker.RELEASE_BAIDU,
    "打开Github Release" to UpdateChecker.RELEASE_GITHUB,
)

/**
 * 「发现新版本」弹窗（2026-11-16）。
 *
 * 正文是用户给的原话模板：`v{versionName}({versionCode})已发布，点击“确认”打开模块发布页。`
 *
 * 点「确认」不直接跳转，而是再弹一个「选择平台」（[PlatformDialog]，三个网盘 / GitHub）——
 * 因为大陆用户多半要走网盘，GitHub 反而可能打不开。
 *
 * 结构照 [FeatureConflictDialog]：`WindowDialog` 的官方 `summary` 位 + `SpaceBetween` 两个按钮。
 */
@Composable
internal fun UpdateFoundDialog(
    show: Boolean,
    versionName: String,
    versionCode: Int,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppGlassDialog(
        title = "发现新版本",
        summary = "v$versionName($versionCode)已发布，点击“确认”打开模块发布页。",
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

/**
 * 「检测更新失败」弹窗（2026-11-16）。
 *
 * 判据是**统一失败**：真没网 / 没给 `INTERNET` 权限 / 连接超时 / 配置格式不对，
 * 全部落到这里（用户 2026-11-16 定：「无论是真没网还是没给权限还是 ping 超时，都弹检测更新失败」）。
 *
 * 只有**手动**检测才弹；自动检测失败静默（否则墙内网络不稳时每次开 App 都吃一个弹窗）。
 *
 * 单按钮「知道了」右对齐 —— 照 [PlatformDialog] 的收尾写法（没有第二个动作）。
 */
@Composable
internal fun UpdateFailedDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    AppGlassDialog(
        title = "检测更新失败",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButtonLike(
                    text = "知道了",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
