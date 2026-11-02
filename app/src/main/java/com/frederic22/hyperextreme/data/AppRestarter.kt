package com.frederic22.hyperextreme.data

import android.app.ActivityManager
import android.content.Context

/** 息屏与锁屏编辑 —— 同时是本模块的作用域进程。 */
const val AodPackageName = "com.miui.aod"

/** 系统界面 —— 第二个作用域（锁屏「自定义锁屏」按钮那条链跑在它里面）。 */
const val SystemUiPackageName = "com.android.systemui"

/**
 * 系统桌面 —— 「打开多任务和文件夹时隐藏状态栏」的触发源。
 *
 * 模块**不往这个进程装载任何东西**（触发信号由桌面自己经 binder 发给 SystemUI，生效动作在
 * SystemUI 侧，见 `hook/RecentsStatusBar`）；它出现在重启列表里 = "重启桌面进程本身"
 * （多任务状态/桌面上出怪状时的恢复手段）。
 */
const val MiuiHomePackageName = "com.miui.home"

/**
 * 系统壁纸（miwallpaper）—— 壁纸层与视差主体/背景的**实际渲染进程**。
 *
 * 我们的视差两层是挂在壁纸 leash（`WallpaperWindowToken`）下的子 SurfaceControl，
 * 而那一层的实际内容由 miwallpaper 提供。**单独重启 SystemUI 后，旧进程留下的层
 * 不会被清理干净**，会出现「桌面层级固定在重启前的样子、模块怎么调都没反应」，
 * 只有重启 miwallpaper（壁纸层整体重建、其下子层一并销毁）才恢复 —— 所以它必须
 * 出现在重启列表里。
 */
const val MiuiWallpaperPackageName = "com.miui.miwallpaper"

/** 一个可重启的目标：界面上的显示名 + 包名。 */
data class RestartTarget(val label: String, val packageName: String)

/** 顶栏刷新按钮弹出的多选列表（顺序就是界面上的顺序）。 */
val RestartTargets = listOf(
    RestartTarget("息屏与锁屏编辑", AodPackageName),
    RestartTarget("系统界面", SystemUiPackageName),
    RestartTarget("系统桌面", MiuiHomePackageName),
    RestartTarget("壁纸", MiuiWallpaperPackageName),
)

/**
 * 重启目标应用（= 结束它的进程，系统下次需要时会自己拉起来）。
 *
 * 为什么需要它：编辑器和 SystemUI 会把主体图 / 遮罩缓存在**内存**里
 * （`WallpaperInfo.subjectBitmap` 那类瞬态字段），换了文件之后它们可能还在用旧的。
 * 结束进程是最干脆的清缓存方式 —— 比在界面上"关景深再开一次"省事得多。
 *
 * 两条路：
 * * 有 root → `pidof` 取 pid，再 `kill -15` 发信号（**不是 `am force-stop`**，原因见 [restartWithRoot]）；
 * * 没有 root → `ActivityManager.killBackgroundProcesses()` 尽力而为（只对后台进程有效，
 *   目标应用若带前台服务就杀不掉）。
 *
 * **"未在运行"也算失败**（用户指定）：重启这个动作没真正发生，就不该报成功。
 */
object AppRestarter {

    /**
     * 一次重启的结果。
     *
     * 只给"成没成 + 失败原因" —— 界面按「xx重启失败，原因xx」拼句子，
     * 应用名由界面层自己配（它手里有 [RestartTargets] 的显示名），这里只出原因。
     */
    data class Result(val ok: Boolean, val reason: String? = null)

    fun restart(context: Context, target: String): Result =
        if (RootShell.isAvailable()) restartWithRoot(target) else restartWithoutRoot(context, target)

    /**
     * 用 **`kill -15`**，不用 `am force-stop`。
     *
     * 真机实测（2026-09-29）：`am force-stop com.android.systemui` **结束不了 SystemUI 的进程**
     * —— 命令返回 0，`pidof` 复查却依然有该进程（原因未深究：既可能是系统对 persistent 应用
     * 拒绝了 force-stop，也可能是杀掉后立刻拉起了新的，两者对"我们要不要用它"结论相同）。
     * 换成 `kill -15` 后进程确实被结束、系统随即重新拉起 —— 做法对齐 HyperChanger 的
     * `SystemUiRestarter`（`pidof` + `kill -15`）。
     *
     * 判定用 **`/proc/<pid>` 是否还在**，而不是"`pidof` 还有没有输出"：
     * SystemUI 是常驻进程，被杀掉之后系统会**立刻拉起一个新进程**，
     * 只看"还在不在"必然把成功误判成失败（这正是上一版的问题）；看"原来是哪几个 pid、它们还在不在"才准。
     */
    private fun restartWithRoot(target: String): Result {
        // 连子进程一起处理：aod 有两个进程名 —— 主进程 `com.miui.aod` 和编辑器子进程
        // `com.miui.aod:keyguardeditor`。用户开着锁屏编辑器时往往只有子进程在，
        // 只查主进程名会漏（真机反馈过）。对 SystemUI 来说第二个名字匹配不到，无害。
        val names = "$target $target:keyguardeditor"
        val script = buildString {
            append("before=\$(pidof ").append(names).append("); ")
            append("if [ -z \"\$before\" ]; then echo 'state=not_running'; exit 0; fi; ")
            append("kill_err=\$(kill -15 \$before 2>&1); kill_rc=\$?; ")
            append("sleep 1; ")
            append("alive=0; for p in \$before; do [ -d /proc/\$p ] && alive=1; done; ")
            append("echo \"state=done kill_rc=\$kill_rc alive=\$alive kill_err=[\$kill_err]\"")
        }
        val result = RootShell.run(script)
        val output = result.output
        return when (output.value("state")) {
            "not_running" -> Result(false, "未在运行")
            "done" -> {
                val killRc = output.value("kill_rc")?.toIntOrNull()
                when {
                    output.value("alive") == "0" -> Result(true)
                    killRc != null && killRc != 0 ->
                        Result(false, output.bracketed("kill_err") ?: "结束进程失败（退出码 $killRc）")
                    else -> Result(false, "进程仍在运行")
                }
            }
            // 脚本没跑到 `state=`，说明 su 这一层就出问题了。
            else -> Result(false, "执行 su 失败（${output.trim().ifBlank { "退出码 ${result.exitCode}" }}）")
        }
    }

    private fun restartWithoutRoot(context: Context, target: String): Result {
        val manager = context.getSystemService(ActivityManager::class.java)
            ?: return Result(false, "拿不到 ActivityManager")
        val requested = runCatching { manager.killBackgroundProcesses(target) }.isSuccess
        return if (requested) Result(true) else Result(false, "没有 root，无法结束进程")
    }

    /** 从脚本输出里取 `key=value` 的 value。 */
    private fun String.value(key: String): String? =
        Regex("$key=(\\S+)").find(this)?.groupValues?.get(1)

    /** 从脚本输出里取 `key=[value]` 的 value（用于可能带空格的错误信息）。 */
    private fun String.bracketed(key: String): String? =
        Regex("$key=\\[([^\\]]*)]").find(this)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
