package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/**
 * 底栏选中页与 [PagerState] 的联动 —— **miuix 官方 example 的 `MainPagerState` 同款**
 * （`example/shared/src/commonMain/kotlin/AppContent.kt:1042`）。
 *
 * ## 为什么不直接用 `pagerState.currentPage`
 *
 * 它在滚动途中会**逐页经过中间页**：点底栏从第 1 页切到第 4 页时，`currentPage` 会依次变成
 * 2、3、4；手指跨页滑动同理。直接拿它驱动底栏，选中块会"先弹到中间页、再弹到终点"，
 * 观感就是抽一下。
 *
 * 这里按官方示例分两路：
 * 1. **[animateToPage]**：底栏发起的导航**先定住** [selectedPage]，导航期间屏蔽 `currentPage`
 *    的中间状态；只有没走到目标（被手势打断等）才在收尾时回读。
 * 2. **[syncPage]**：用户手指滑页（非导航中）才把 `currentPage` 同步回来。
 *
 * `syncPage()` 由调用方在 `LaunchedEffect(pagerState.currentPage)` 里挂 —— 与官方示例一致。
 */
@Stable
internal class AppPagerState(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    /** 底栏当前高亮的页。导航中不受 `pagerState.currentPage` 影响。 */
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    /** 是否正由底栏发起的导航在跑。 */
    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    /**
     * 切到第 [target] 页：立刻把选中页钉到目标（底栏的选中块当场起飞），
     * 再用 [springAnimateToPage]（`PagerNavigationSpringSpec`）把 pager 滚过去。
     *
     * 连续点击时先取消上一次导航；`finally` 里用 job 身份判断，避免旧 job 收尾时
     * 把新导航的 `isNavigating` 清掉。
     */
    fun animateToPage(target: Int) {
        if (target == selectedPage) return

        navJob?.cancel()

        selectedPage = target
        isNavigating = true

        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(target)
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    // 没走到目标（被手势打断 / 被取消）⇒ 退回 pager 的真实页。
                    if (pagerState.currentPage != target) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    /** 手指滑页结束后同步选中页；导航中不覆盖。 */
    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
internal fun rememberAppPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): AppPagerState = remember(pagerState, coroutineScope) {
    AppPagerState(pagerState, coroutineScope)
}
