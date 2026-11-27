package com.frederic22.hyperextreme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.frederic22.hyperextreme.data.HomeCover
import com.frederic22.hyperextreme.data.MaskPrefs

/**
 * 启动遮罩（2026-10-29，照 HyperCeiler 的 `SplashActivity` 抄）。
 *
 * ## 为什么必须有一个「空 Activity」
 *
 * Android 12+ 的系统 SplashScreen 在**应用绘制首帧时立刻关闭**，它不等 launcher 的
 * 应用打开动画（HyperOS 那套图标展开/放大，约 400~500ms）结束。结果就是：
 * 动画还没播完，主界面已经顶上来了 —— 观感上「直接就是界面」，没有过渡。
 *
 * 这里放一个**不调用 `setContentView`** 的 Activity：它的窗口内容就是主题的
 * `windowBackground`（= `@color/launch_bg`，浅色白 / 深色黑，见 `values-night`）。
 * 窗口一旦创建就会**一直存在**，直到下一个 Activity 的窗口准备好接管为止，
 * 所以在系统动画期间、以及 MainActivity 的 starting window 期间，屏幕上是连续的纯色遮罩，
 * 直到主界面真正画出第一帧才过渡过去。
 *
 * ## 与 HyperCeiler 完全同构
 *
 * 只做三件事：`super.onCreate` → `startActivity(MainActivity)` → `finish()`。
 * * 不设布局（窗口 = windowBackground）
 * * 不设转场动画（交给系统默认那套，和二级页的策略一致，见 `MaskDetailActivity` 的注释）
 * * 不做延迟 / 不做条件等待（窗口的存活期本身就是「遮罩时长」）
 *
 * ⚠️ 不要给它加 `noHistory`：那会在 `onStop` 时立刻把窗口摘掉，遮罩就断了。
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动预热（2026-11-25）：主页封面的「解析 + 解码」提前到这里跑。
        //
        // 背景：`MainActivity` 的首帧被主动压到进程创建后 400ms（见
        // `MainActivity.holdFirstFrameForLaunchAnimation`），遮罩期间界面本来就在等 ——
        // 正好用这段空窗把首次解码做掉。不预热的话，第一次进模块（或刚换过封面）那一次
        // 仍会闪一帧 `home_sample`：解码要几十~上百毫秒，赶不上放行那一刻。
        //
        // ⚠️ 这里只是**发起**：`HomeCover.request` 内部是 IO 线程 + 单飞合并，
        // 本方法立刻返回，不影响遮罩窗口的启动时序。
        runCatching { HomeCover.request(this, MaskPrefs(this).homeBgFollowApp) }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
