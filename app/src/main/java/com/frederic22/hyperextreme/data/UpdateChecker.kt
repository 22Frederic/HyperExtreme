package com.frederic22.hyperextreme.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 检查更新（2026-11-16）。
 *
 * ## 数据源（用户 2026-11-16 定）
 *
 * | 优先级 | 源 | 请求 | 给什么 |
 * |---|---|---|---|
 * | 主 | **Gitee `config.json`** | 1 个 GET | `versionCode` / `versionName` |
 * | 兜底 | **GitHub `releases/latest`** | 1 个 GET（只读 302 的 `Location`） | tag 里的 `(code)` |
 *
 * **夸克网盘只做跳转目标，绝不探测**（非公开接口，随时会变；用户明确要求）。
 *
 * ## 为什么不用 `api.github.com`
 *
 * 未认证限流 **60 次/小时/IP**，而且是**按出口 IP 共享**的 —— 一个公共 WiFi 下几十个人就能耗光。
 * `github.com/.../releases/latest` 只返回 302，读 `Location` 头就够，**不消耗 API 额度**。
 *
 * ## Gitee 的坑
 *
 * raw 会 302 到 `raw.giteeusercontent.com/...?signature=…`（签名 URL），
 * `HttpURLConnection` 默认跟随重定向即可；实测 Android UA 也放行，无防盗链。
 *
 * 文件在**镜像库根目录**（`Frederic22/HyperExtreme`，2026-11-16 起不再单独开配置库 ——
 * 账号下只剩一个 92 字节的 JSON 是风控画像问题），文件名 `config.json`（全小写），
 * **分支是 `main`** —— 「库 / 分支 / 文件名」三处任一写错都是 404。
 *
 * ⚠️ `config.json` 是**在 Gitee 网页上直接加**的，不在本地仓库里：
 * **别用「重新导入」或 `push --force` 覆盖这个库**，否则文件会被删掉、更新检查当场断。
 * （用户 2026-11-16：「不同步不要紧，我也没打算同步」。）
 *
 * ## 版本判据
 *
 * **只看 `versionCode`**（用户 2026-11-16 定：可能出现"版本名不变、只改 code"的情况）。
 * GitHub 兜底解析不到 tag 里的 `(code)` 时**直接判「已是最新」**，不做名称比较。
 */
object UpdateChecker {

    private const val TAG = "HX-UPDATE"

    /** 主源：Gitee 镜像库根目录的 `config.json`（分支 `main`、文件名全小写 —— 三处都别改）。 */
    const val URL_GITEE =
        "https://gitee.com/Frederic22/HyperExtreme/raw/main/config.json"

    /** 兜底：GitHub 的 releases/latest（**网页地址**，不是 api.github.com）。 */
    private const val URL_GITHUB_LATEST =
        "https://github.com/22Frederic/HyperExtreme/releases/latest"

    /** 发布页（"选择平台"弹窗的三项，链接写死在模块内 —— 用户 2026-11-16 定）。 */
    const val RELEASE_QUARK = "https://pan.quark.cn/s/387bd3bae835"
    const val RELEASE_BAIDU = "https://pan.baidu.com/s/1YIsIfuvBsZwGOQDm40CAeA?pwd=4eid"
    const val RELEASE_GITHUB = "https://github.com/22Frederic/HyperExtreme/releases"

    private const val TIMEOUT_MS = 8000

    /** tag 末尾的 `(123456)`（`1.0Beta5(1050000)`）。 */
    private val TAG_CODE = Regex("\\((\\d+)\\)\\s*$")

    /** 检查结果。 */
    sealed interface Result {
        /** 有新版。[versionName] 用于展示（Gitee 给 `versionName`，GitHub 给 tag 去掉括号那段）。 */
        data class Newer(val versionName: String, val versionCode: Int) : Result

        /** 已是最新。 */
        data object Latest : Result

        /** 检测失败（无网 / 无权限 / 超时 / 配置格式不对）。 */
        data object Failed : Result
    }

    /**
     * **同一时刻只允许一个请求在飞**（用户狂点、自动 + 手动撞车）。
     *
     * 撞上就返回 [Result.Failed] —— 调用方（手动）会弹"检测更新失败"，
     * 虽然文案不精确，但概率极低（一次请求 < 1s），比多线程同时打 Gitee 好。
     */
    private val inFlight = AtomicBoolean(false)

    /**
     * 本机**实际安装**的 `versionCode`。
     *
     * 用 `PackageManager` 而不是 `BuildConfig`：项目没开 `buildFeatures.buildConfig`
     * （AGP 8 默认关），而且这个值是"装上去的那个包"的真实版本，比编译期常量更贴合"要不要更新"。
     */
    fun localVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    /**
     * 同步检查 —— **必须在 IO 线程调用**（`HttpURLConnection` 会阻塞）。
     *
     * @param localCode 本机 versionCode，见 [localVersionCode]
     */
    fun check(localCode: Int): Result {
        if (!inFlight.compareAndSet(false, true)) {
            Log.i(TAG, "已有一次检查在飞，本次忽略")
            return Result.Failed
        }
        try {
            gitee(localCode)?.let {
                Log.i(TAG, "Gitee → $it")
                return it
            }
            github(localCode)?.let {
                Log.i(TAG, "GitHub 兜底 → $it")
                return it
            }
            Log.i(TAG, "两个源都没拿到结果")
            return Result.Failed
        } finally {
            inFlight.set(false)
        }
    }

    // ────────────────────────────── 主源 ──────────────────────────────

    /** 读 Gitee 的 `config.json`；网络/格式失败都返回 null（交给 GitHub 兜底）。 */
    private fun gitee(localCode: Int): Result? = runCatching {
        val text = get(URL_GITEE) ?: return@runCatching null
        val o = JSONObject(text)
        val code = o.optInt("versionCode", -1)
        if (code <= 0) {
            Log.w(TAG, "Gitee 配置里没有有效的 versionCode")
            return@runCatching null
        }
        val name = o.optString("versionName", "").ifEmpty { "新版本" }
        if (code > localCode) Result.Newer(name, code) else Result.Latest
    }.getOrNull()

    // ────────────────────────────── 兜底 ──────────────────────────────

    /**
     * 只读 302 的 `Location`，从 `/releases/tag/1.0Beta5(1050000)` 拿 tag。
     *
     * 解析不到 `(code)` ⇒ **直接判已是最新**（用户 2026-11-16 定，不做名称比较）。
     */
    private fun github(localCode: Int): Result? = runCatching {
        val conn = open(URL_GITHUB_LATEST, followRedirect = false) ?: return@runCatching null
        try {
            val status = conn.responseCode
            if (status !in 300..399) {
                Log.w(TAG, "GitHub latest 没有返回 302（$status）")
                return@runCatching null
            }
            val loc = conn.getHeaderField("Location") ?: return@runCatching null
            val tag = loc.substringAfterLast('/').trim()
            val m = TAG_CODE.find(tag) ?: run {
                Log.i(TAG, "GitHub tag 里没有 (code)：$tag ⇒ 按已是最新处理")
                return@runCatching Result.Latest
            }
            val remote = m.groupValues[1].toIntOrNull() ?: return@runCatching Result.Latest
            val name = tag.replace(TAG_CODE, "").trim().ifEmpty { "新版本" }
            if (remote > localCode) Result.Newer(name, remote) else Result.Latest
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    // ────────────────────────────── HTTP ──────────────────────────────

    /** GET 一个文本（失败返回 null，**不抛**）。 */
    private fun get(url: String): String? = runCatching {
        val conn = open(url, followRedirect = true) ?: return@runCatching null
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET $url → ${conn.responseCode}")
                return@runCatching null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    private fun open(url: String, followRedirect: Boolean): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = followRedirect
            requestMethod = "GET"
            // 有些 CDN 对空 UA 更严格；带上自己的名字，也方便对方识别流量来源
            setRequestProperty("User-Agent", "HyperExtreme-UpdateChecker")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
    }.getOrNull()
}
