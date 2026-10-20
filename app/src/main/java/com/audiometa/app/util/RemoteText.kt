package com.audiometa.app.util

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 拉一段文本 / 一段字节（GET），**一个地址试不通就换镜像再试**。
 *
 * 只认 `http(s)://…`（带镜像回退，见 [candidates]）；其余一律当无效地址 ——
 * 本地文件那条路已经按用户要求删掉了，规则与插件包都只从网络来。
 *
 * ## 为什么要有镜像
 *
 * 用户填的都是 GitHub 地址，而 `raw.githubusercontent.com` / `api.github.com`
 * 在国内多数网络下是 DNS 污染或直接黑洞 —— 表现就是"下载 index.json 失败"。
 * 普通用户不会去配代理，所以这里自己把"同一份内容还能从哪儿拿"列出来挨个试：
 *
 * - **jsDelivr 家族**（内置）：它就是 GitHub 的 CDN 镜像，`gh/user/repo@ref/path`
 *   与原文件逐字节一致，不需要用户配置；
 * - **第三方中转**（用户在「设置 - 插件 - 网络与镜像」里自己加的，形如 `https://gh-proxy.com/`）：
 *   只对 `github.com` / `api.github.com` 这类 jsDelivr 服务不了的地址启用。
 *   **它们是第三方中转站，转发的内容它有办法改**，所以 [Options.sha256] 才存在：
 *   仓库在 index.json 里写了 hash 就校验，不对就当这次尝试失败、换下一个镜像。
 *   （hash 只能挡住"改内容"，挡不住"连 index.json 一起改"——那需要签名，超出范围。）
 *
 * ## 超时
 *
 * 单次尝试 8 秒，整轮还有 30 秒总预算：候选可能有 6~8 个，不能让设置页一直转圈。
 */
object RemoteText {

    private const val TAG = "RemoteText"

    /**
     * 单次尝试的超时**默认值**。
     *
     * 原来是 15 秒：直连被墙时用户要盯着转圈 15 秒才看到失败，换镜像就要再等一次。
     * 8 秒对 index.json / 规则文件这种几十 KB 的文本够用；
     * **插件包要好几百 KB**，在中转站上 8 秒读不完，所以那里的调用方会传更大的值
     *（见 [Options.attemptTimeoutMs]）。
     */
    const val DEFAULT_ATTEMPT_TIMEOUT_MS = 8_000

    /** 一轮下载的总预算默认值。到点就不再开新的候选，界面最多等这么久。 */
    const val DEFAULT_TOTAL_BUDGET_MS = 30_000L

    /**
     * 建立连接的超时。
     *
     * 和"读正文"分开：连不上（DNS 被污染、被墙）本来就该**快点失败**换下一个候选，
     * 而正文可能要传几百 KB，得给足时间。以前两者共用一个超时 ——
     * 给正文留够 45 秒，就意味着连不上的候选也要挂满 45 秒，
     * 用户看到的是"点了更新之后半天没动静"。
     */
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** 最多试几个候选：镜像表是用户可编辑的，写长了也不该让一次下载发几十个请求。 */
    private const val MAX_CANDIDATES = 8

    /** 直连的"镜像键"。空串 = 用用户给的原地址，不经过任何中转。 */
    const val DIRECT = ""

    /** 试过后放弃时，错误信息里附一句"还有候选没试"，免得用户以为全试过了。 */
    private const val SKIPPED_NOTE = "其余候选未试（已用满总超时预算）"

    /** 直连在错误信息里的名字，例如「直连超时」。 */
    private const val LABEL_DIRECT = "直连"

    /** 失败原因：超时。 */
    const val DETAIL_TIMEOUT = "超时"

    /** 失败原因：下载到了内容，但 sha256 对不上。 */
    const val DETAIL_HASH_MISMATCH = "sha256 不符"

    /** 失败原因：拿到的是一个 HTML 页面（DNS 劫持 / 运营商插页的典型样子）。 */
    private const val DETAIL_HTML = "返回的是网页（疑似劫持）"

    /** 失败原因：HTTP 200 但内容长度是 0。 */
    private const val DETAIL_EMPTY = "内容为空"

    /**
     * 内置的 jsDelivr 入口，顺序就是尝试顺序。
     *
     * 这几个域名在同一个网络下可达性并不总是一致（测试时 cdn 通、fastly 偶尔更稳），
     * 所以全都列上，谁先通用谁。用户可以加自己的前缀，但这四个不需要配置。
     */
    private val JSDELIVR_HOSTS = listOf(
        "cdn.jsdelivr.net",
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net",
        "testingcf.jsdelivr.net"
    )

    /**
     * 一个候选地址。
     *
     * @param url 真正去请求的地址。
     * @param mirror 命中的镜像前缀（[DIRECT] 表示直连）。它也是"记住上次成功的镜像"用的键：
     *   存进设置后，下次同一个前缀的候选会被排到最前面。
     * @param label 出错信息里给用户看的名字：直连 / cdn.jsdelivr.net / gh-proxy.com。
     */
    data class Candidate(
        val url: String,
        val mirror: String = DIRECT,
        val label: String = LABEL_DIRECT
    )

    /**
     * 一次尝试的结果（给「测试连接」用）。
     *
     * @param bytes 拿到多少字节（失败为 0）。
     * @param millis 这一次花了多久。
     */
    data class Attempt(
        val candidate: Candidate,
        val ok: Boolean,
        val detail: String = "",
        val bytes: Int = 0,
        val millis: Long = 0
    ) {
        /** 「直连超时」「cdn.jsdelivr.net 超时」「gh-proxy.com HTTP 502」这种一行说明。 */
        fun summary(): String {
            if (detail.isBlank()) return candidate.label
            // 中文名直接接上（直连超时）；域名这种 ASCII 名字后面留一个空格更好读
            //（cdn.jsdelivr.net 超时 / gh-proxy.com HTTP 502）。
            val space = if (candidate.label.lastOrNull()?.code?.let { it < 128 } == true) " " else ""
            return candidate.label + space + detail
        }
    }

    /**
     * 一次下载的结果。
     *
     * [ok] 为 false 时 [error] 是给用户看的完整原因（每个候选各失败在哪），
     * 不再是以前那句没有信息量的"失败"。
     */
    class Download(
        val bytes: ByteArray,
        /** 实际生效的地址（可能是推导出来的镜像地址）。 */
        val usedUrl: String = "",
        /** 实际生效的镜像前缀；[DIRECT] 表示直连。 */
        val mirror: String = DIRECT,
        val error: String = ""
    ) {
        val ok: Boolean get() = error.isEmpty()

        val text: String get() = String(bytes, Charsets.UTF_8)

        companion object {
            fun failure(error: String) = Download(ByteArray(0), error = error)
        }
    }

    /**
     * 下载参数。
     *
     * @param proxies 用户配的第三方中转前缀（有序）。空串那一项表示"直连"，是个占位符，
     *   直连本来就会第一个试。
     * @param preferred 上次成功的镜像键，非空时那个候选排到最前（默认直连优先）。
     * @param sha256 期望的 sha256；为空表示不校验（老 index.json 没有这个字段，必须照旧能用）。
     * @param attemptTimeoutMs 单个候选最长等多久。[RemoteText.DEFAULT_ATTEMPT_TIMEOUT_MS]
     *   适合几十 KB 的文本；插件包几百 KB，要传更大的值，否则中转站还没传完就判超时。
     * @param budgetMs 这一轮的总预算。
     */
    data class Options(
        val proxies: List<String> = emptyList(),
        val preferred: String = DIRECT,
        val sha256: String? = null,
        val attemptTimeoutMs: Int = DEFAULT_ATTEMPT_TIMEOUT_MS,
        val budgetMs: Long = DEFAULT_TOTAL_BUDGET_MS
    )

    @Volatile
    private var lastUsed: String? = null

    @Volatile
    private var lastFailure: String? = null

    // ------------------------------------------------------------------
    // 兼容旧调用：签名和行为都不变，只是失败前会多试几个镜像
    // ------------------------------------------------------------------

    fun fetch(address: String): String? =
        download(address).let { if (it.ok) it.text else null }

    /** 下载字节（插件 zip 用）。 */
    fun fetchBytes(address: String): ByteArray? =
        download(address).let { if (it.ok) it.bytes else null }

    /** 最后一次成功的地址（可能是镜像地址，例如 jsDelivr 那条）。 */
    fun lastUsedUrl(): String? = lastUsed

    /** 最后一次失败的原因；成功一次之后就清掉。 */
    fun lastError(): String? = lastFailure

    // ------------------------------------------------------------------
    // 候选推导
    // ------------------------------------------------------------------

    /**
     * 列出一个地址的所有候选，顺序就是尝试顺序。
     *
     * 规则：
     * 1. **原地址永远排第一**（默认顺序下）：它最可能就是用户想要的那一份；
     * 2. `raw.githubusercontent.com/<user>/<repo>/<ref>/<path>` →
     *    `https://cdn.jsdelivr.net/gh/<user>/<repo>@<ref>/<path>`，
     *    然后是 fastly / gcore / testingcf（同一个 `jsdelivr.net` 后缀）；
     * 3. `github.com/...`（`blob` / `raw` 形式）同样能换成 jsDelivr 的 gh 形式；
     *    换成 jsDelivr 之外的 `github.com` 页面地址与 `api.github.com` 接口地址
     *    jsDelivr 服务不了，只能靠第三方中转前缀；
     * 4. 用户填的**第三方中转前缀**（`prefix + 原地址`）排在最后 —— 它们是最后手段，
     *    而且内容经过第三方，所以校验（[Options.sha256]）比它们更可信；
     * 5. 用户填的前缀本身是 jsDelivr 形式（`…/gh/`）时按 gh 形式拼，不能直接接原地址；
     * 6. 如果用户填的**就是** jsDelivr 地址，就把同一份内容在其它几个 jsDelivr 入口上也试一遍。
     *
     * @param preferred 上次成功的镜像键（[Candidate.mirror]），非空时把它提到最前。
     */
    fun candidates(address: String, proxies: List<String> = emptyList()): List<Candidate> =
        candidates(address, proxies, DIRECT)

    fun candidates(address: String, proxies: List<String>, preferred: String): List<Candidate> {
        val trimmed = address.trim()
        if (!isHttp(trimmed)) return emptyList()
        val url = runCatching { URL(trimmed) }.getOrNull() ?: return emptyList()
        val host = url.host.orEmpty().lowercase(Locale.US)
        val out = mutableListOf<Candidate>()

        // 1. 原地址。
        out += Candidate(trimmed, DIRECT, LABEL_DIRECT)

        // 2~3. jsDelivr：把 GitHub 地址换成 gh 形式。
        val target = githubTarget(url)
        if (target != null) {
            JSDELIVR_HOSTS.forEach { hostName ->
                val prefix = "https://" + hostName + "/gh/"
                out += Candidate(prefix + target, prefix, hostName)
            }
        } else {
            // 用户填的本身就是 jsDelivr（或兼容它的 `…/gh/` 镜像）地址：
            // 同一份内容在其它几个入口上也试一遍 —— 这几个域名在同一个网络下
            // 可达性并不总是一致，cdn 被墙时 fastly 往往还通。
            val gh = ghPathOf(url)
            if (gh != null) {
                JSDELIVR_HOSTS.filter { it != host }.forEach { other ->
                    val prefix = "https://" + other + "/gh/"
                    out += Candidate(prefix + gh, prefix, other)
                }
            }
        }

        // 4~5. 第三方中转前缀。
        proxies.forEach { raw ->
            val prefix = normalizePrefix(raw)
            // 空串 = 直连（占位用），直连已经在第一个了。
            if (prefix.isEmpty()) return@forEach
            if (!isHttp(prefix)) {
                // 写错的前缀（少了 https://、写成一句说明…）直接跳过，
                // 不然会拼出一个必然报"no protocol"的候选，白白占一次尝试。
                AppLog.w(TAG, "忽略写法不对的镜像前缀：" + raw)
                return@forEach
            }
            val candidateUrl = when {
                // `…/gh/` 形式的前缀本质是"另一个同内容的 CDN"，只要地址能拆成 gh 形式
                // 就用得上 —— raw 地址也一样。注意它要的是 `user/repo@ref/path`，
                // 直接接原地址会拼出一串废地址。
                isJsdelivrStyle(prefix) -> target?.let { prefix + it } ?: return@forEach
                // 通用中转（gh-proxy 那种）只对 github.com 家族启用：jsDelivr 服务不了
                // 页面和 API 地址，那才是它们真正派上用场的地方。
                needsRelay(host) -> prefix + trimmed
                else -> return@forEach
            }
            out += Candidate(candidateUrl, prefix, hostLabel(prefix))
        }

        val unique = out.distinctBy { it.url }
        // 上次成功的镜像排最前 —— 但同时保持其余候选的相对顺序。
        val ordered =
            if (preferred.isBlank()) unique else unique.sortedByDescending { it.mirror == preferred }
        return ordered.take(MAX_CANDIDATES)
    }

    /**
     * 把 GitHub 地址换成 jsDelivr 的 gh 路径：`<user>/<repo>@<ref>/<path>`。
     *
     * 只认这两种（别的形式 jsDelivr 没有等价内容）：
     *   `raw.githubusercontent.com/<user>/<repo>/<ref>/<path...>`
     *   `github.com/<user>/<repo>/{blob,raw}/<ref>/<path...>`
     */
    private fun githubTarget(url: URL): String? {
        val host = url.host.orEmpty().lowercase(Locale.US)
        val parts = url.path.orEmpty().split('/').filter { it.isNotBlank() }
        return when {
            host == "raw.githubusercontent.com" || host == "raw.github.com" ->
                if (parts.size < 4) null
                else parts[0] + "/" + parts[1] + "@" + parts[2] + "/" + parts.drop(3).joinToString("/")

            host == "github.com" || host == "www.github.com" ->
                if (parts.size < 5 || (parts[2] != "blob" && parts[2] != "raw")) null
                else parts[0] + "/" + parts[1] + "@" + parts[3] + "/" + parts.drop(4).joinToString("/")

            else -> null
        }
    }

    /**
     * 这些地址才值得动用第三方中转。
     *
     * 原本只认 `github.com` 家族，理由是"raw 地址有 jsDelivr 兜着，不必再经第三方"。
     * 这条理由**被现实否掉了**：jsDelivr 会把分支名（`@main`）长期钉在旧提交上，
     * 不同入口还可能一份新一份旧 —— 用户遇到过"更新订阅装上的还是上一版规则、
     * 插件条目全 404"，就是因为只有 jsDelivr 兜底、而它给的是过期内容。
     * 所以 raw 地址现在也走中转：它直连原站，能拿到当前内容。
     */
    private fun needsRelay(host: String): Boolean =
        host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host == "raw.github.com" ||
            host == "objects.githubusercontent.com" || host == "codeload.github.com"

    /** `…/gh/<user>/<repo>@<ref>/<path>` 里 `/gh/` 之后的部分；不是这种写法就是 null。 */
    private fun ghPathOf(url: URL): String? {
        val path = url.path.orEmpty()
        val index = path.indexOf("/gh/")
        if (index < 0) return null
        return path.substring(index + "/gh/".length).takeIf { it.isNotBlank() }
    }

    private fun isHttp(address: String): Boolean =
        address.startsWith("http://", true) || address.startsWith("https://", true)

    /** 前缀统一补一个结尾斜杠：用户写 `https://gh-proxy.com` 也能拼对。 */
    private fun normalizePrefix(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith("/")) trimmed else trimmed + "/"
    }

    /**
     * 这是不是"jsDelivr 形式"的前缀。
     *
     * 判据是 **`…/gh/` 这段路径**，不是域名：jsDelivr 和兼容它的一堆镜像
     * （自建反代、国内加速站）都长这样，填进来的是"另一个同内容的 CDN"，
     * 而不是 gh-proxy 那种"前缀 + 完整原地址"的中转。两者拼法完全不同。
     */
    private fun isJsdelivrStyle(prefix: String): Boolean {
        val lower = prefix.lowercase(Locale.US)
        return lower.contains("/gh/") || lower.endsWith("/gh")
    }

    /** 出错信息里显示的名字：前缀的域名。 */
    private fun hostLabel(prefix: String): String =
        runCatching { URL(prefix).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: prefix

    /**
     * 镜像键（[Candidate.mirror]）→ 给用户看的名字。
     *
     * 设置里存的是前缀本身（如 `https://gh-proxy.com/`），界面上显示域名更短也更清楚。
     */
    fun mirrorLabel(mirror: String): String =
        if (mirror.isBlank()) LABEL_DIRECT else hostLabel(mirror)

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 下载：按 [candidates] 的顺序挨个试，第一个拿回可用内容的就是它。
     *
     * 内容可用的判据：HTTP 2xx、非空、不是 HTML 页面、sha256（如果给了）对得上。
     */
    fun download(address: String, options: Options = Options()): Download {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return Download.failure("地址是空的")
        // 非 http(s) 的地址（本地路径、写错的地址）直接判失败：
        // 规则和插件包都只从网络来，本地导入那条路已经去掉了。
        if (!isHttp(trimmed)) return Download.failure("不是有效的地址：" + trimmed)

        val list = candidates(trimmed, options.proxies, options.preferred)
        if (list.isEmpty()) return Download.failure("地址无法解析：" + trimmed)

        val expected = normalizeHash(options.sha256)
        val run = runCandidates(
            list,
            expected,
            options.budgetMs,
            options.attemptTimeoutMs,
            stopOnSuccess = true
        )
        val success = run.success
        if (success != null) {
            lastUsed = run.used?.url
            lastFailure = null
            AppLog.i(TAG, "下载成功（" + (run.used?.label ?: "") + "）：" + (run.used?.url ?: ""))
            return Download(success.bytes ?: ByteArray(0), run.used?.url.orEmpty(), run.used?.mirror ?: DIRECT)
        }
        val error = failureText(run.attempts, expected != null, run.skipped)
        lastFailure = error
        return Download.failure(error)
    }

    /**
     * 把每个候选都试一遍并如实返回结果（「测试连接」用）。
     *
     * 和 [download] 走同一套候选与判据，只是**不因为成功就停下** ——
     * 用户要看的正是"哪几条通、哪几条不通"。
     *
     * @param onAttempt 每试完一条回调一次。逐条冒出来比"等二三十秒再一次性列出"
     *   好得多：卡住的那条本来就要等满超时，用户至少能看着进度。
     */
    fun attempts(
        address: String,
        options: Options = Options(),
        onAttempt: (Attempt) -> Unit = {}
    ): List<Attempt> {
        val trimmed = address.trim()
        if (!isHttp(trimmed)) return emptyList()
        val list = candidates(trimmed, options.proxies, options.preferred)
        return runCandidates(
            list,
            normalizeHash(options.sha256),
            options.budgetMs,
            options.attemptTimeoutMs,
            stopOnSuccess = false,
            onAttempt = onAttempt
        ).attempts
    }

    /**
     * 把候选挨个试，**成功拿到的内容都留着**（最多 [maxSuccess] 份），交给调用方挑。
     *
     * 为什么需要它：镜像/CDN 的缓存并不一致。jsDelivr 会把分支名（`@main`）长期钉在
     * 旧提交上，加查询参数也击不穿；同一份 `index.json` 在 cdn 上是几个月前那版、
     * 在 testingcf 上却是当前版本。只看"谁先回 200"就会拿到过期内容 ——
     * 用户报的"首次更新失败（条目全 404）+ 规则装成旧版本"根子就在这里。
     *
     * 调用方拿这批内容自己比"哪份更新"（index.json 比 `updatedAt`、
     * 规则文件比 `rulesVersion`），而不是信第一个回话的。
     */
    fun downloads(
        address: String,
        options: Options = Options(),
        maxSuccess: Int = 3
    ): List<FetchedContent> {
        val trimmed = address.trim()
        if (!isHttp(trimmed)) return emptyList()
        val list = candidates(trimmed, options.proxies, options.preferred)
        if (list.isEmpty()) return emptyList()
        val expected = normalizeHash(options.sha256)
        val deadline = System.currentTimeMillis() + options.budgetMs.coerceAtLeast(1_000L)
        val out = mutableListOf<FetchedContent>()
        for (candidate in list) {
            if (out.size >= maxSuccess) break
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val timeout = minOf(options.attemptTimeoutMs.toLong(), left).toInt().coerceAtLeast(500)
            val started = System.currentTimeMillis()
            val fetched = fetchBytes(candidate.url, timeout, expected)
            val elapsed = System.currentTimeMillis() - started
            if (fetched.ok) {
                out += FetchedContent(candidate, fetched.bytes ?: ByteArray(0), elapsed)
            } else {
                AppLog.w(TAG, "候选失败（" + candidate.label + "）：" + fetched.detail + " " + candidate.url)
            }
        }
        return out
    }

    /** [downloads] 拿到的一份内容。 */
    class FetchedContent(
        val candidate: Candidate,
        val bytes: ByteArray,
        val millis: Long = 0
    ) {
        val text: String get() = String(bytes, Charsets.UTF_8)

        /** 这份内容自己的 sha256（挑完再校验用）。 */
        fun sha256(): String = sha256Hex(bytes)
    }

    /** 每个候选都试一遍（实测哪个镜像能用）时，单次要快一点，不然一圈下来太慢。 */
    fun testOptions(
        proxies: List<String>,
        preferred: String = DIRECT,
        sha256: String? = null,
        attemptTimeoutMs: Int = 6_000
    ): Options = Options(
        proxies = proxies,
        preferred = preferred,
        sha256 = sha256,
        attemptTimeoutMs = attemptTimeoutMs,
        budgetMs = 20_000L
    )

    /** 一轮候选尝试的结果。 */
    private class Run(
        val success: Fetched?,
        val used: Candidate?,
        val attempts: List<Attempt>,
        /** 因为总预算用完而没试的候选个数。 */
        val skipped: Int
    )

    private fun runCandidates(
        list: List<Candidate>,
        expected: String?,
        budgetMs: Long,
        attemptTimeoutMs: Int,
        stopOnSuccess: Boolean,
        onAttempt: (Attempt) -> Unit = {}
    ): Run {
        val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(1_000L)
        val attempts = mutableListOf<Attempt>()
        var success: Fetched? = null
        var used: Candidate? = null
        for (candidate in list) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            // 单次上限既不超总预算，也不超调用方给的这次的上限。
            val timeout = minOf(attemptTimeoutMs.toLong(), left).toInt().coerceAtLeast(500)
            val started = System.currentTimeMillis()
            val fetched = fetchBytes(candidate.url, timeout, expected)
            val elapsed = System.currentTimeMillis() - started
            val attempt =
                Attempt(candidate, fetched.ok, fetched.detail, fetched.bytes?.size ?: 0, elapsed)
            attempts += attempt
            onAttempt(attempt)
            if (fetched.ok) {
                success = fetched
                used = candidate
                if (stopOnSuccess) break
            } else {
                // 每个候选都记一条：用户看到的失败原因就是从这里拼出来的。
                AppLog.w(TAG, "候选失败（" + candidate.label + "）：" + fetched.detail + " " + candidate.url)
            }
        }
        return Run(success, used, attempts, (list.size - attempts.size).coerceAtLeast(0))
    }

    /** 一次尝试拿到的字节；[bytes] 为 null 表示失败，[detail] 是原因。 */
    private class Fetched(val bytes: ByteArray?, val detail: String = "") {
        val ok: Boolean get() = bytes != null
    }

    private fun fetchBytes(url: String, timeoutMs: Int, expected: String?): Fetched {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url, timeoutMs)
            val bytes = connection.inputStream.use { it.readBytes() }
            when {
                bytes.isEmpty() -> Fetched(null, DETAIL_EMPTY)
                // 被劫持时常见的样子：HTTP 200 + 一个 HTML 页面。
                // index.json / zip / 规则文件都不可能是网页，所以这一条不会误伤。
                looksLikeHtml(bytes) -> Fetched(null, DETAIL_HTML)
                expected != null && !matches(bytes, expected) -> Fetched(null, DETAIL_HASH_MISMATCH)
                else -> Fetched(bytes)
            }
        } catch (e: Exception) {
            Fetched(null, detailOf(e))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun open(address: String, timeoutMs: Int): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        // 连不上就快点失败：换下一个候选比在这里干等有用。
        connection.connectTimeout = minOf(timeoutMs, CONNECT_TIMEOUT_MS)
        // 读正文单独给时间：中转站上几百 KB 的插件包几秒读不完很正常。
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        // GitHub 上有些地址会 302 到 CDN，不跟就会拿到一段空内容。
        connection.setRequestProperty("User-Agent", "AudioMeta")
        connection.setRequestProperty("Accept", "*/*")
        val code = connection.responseCode
        if (code !in 200..299) {
            runCatching { connection.disconnect() }
            error("HTTP " + code)
        }
        return connection
    }

    /** 把异常翻成用户看得懂的一句话。 */
    private fun detailOf(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> DETAIL_TIMEOUT
        is java.net.UnknownHostException -> "DNS 解析失败"
        is java.net.ConnectException -> "连接被拒绝"
        is javax.net.ssl.SSLException -> "TLS 握手失败"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .lowercase(Locale.US)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    /**
     * 全部候选都失败时给用户看的那句话。
     *
     * 必须点名"试了哪些、各自错在哪"，例如
     * 「直连超时；cdn.jsdelivr.net 超时；gh-proxy.com HTTP 502」——
     * 以前只有一句"失败"，用户和我们都不知道卡在哪一步。
     */
    private fun failureText(attempts: List<Attempt>, hashConfigured: Boolean, skipped: Int): String {
        if (attempts.isEmpty()) return "没有可用的候选地址"
        val detail = attempts.joinToString("；") { it.summary() } +
            if (skipped > 0) "；" + SKIPPED_NOTE else ""
        // 配了 hash 又出现对不上：按用户要求明确说成"可能被中间人篡改"。
        // 后面补一句镜像缓存的提醒：CDN 上的文件和 index.json 不是同时刷新的
        // （jsDelivr 对分支地址有缓存），刚更新完仓库时这一条**会**误报，
        // 不加这句用户会以为真被攻击了。
        val mismatched = hashConfigured && attempts.any { it.detail == DETAIL_HASH_MISMATCH }
        return if (mismatched) {
            "下载内容校验失败，可能被中间人篡改（若仓库刚更新过，" +
                "镜像缓存可能还没同步，过一会儿再试）：" + detail
        } else {
            detail
        }
    }

    // ------------------------------------------------------------------
    // sha256
    // ------------------------------------------------------------------

    /** 字节的 sha256（小写十六进制）。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    /** 内容对不对得上期望的 sha256（大小写无关）。 */
    fun matches(bytes: ByteArray, expectedSha256: String): Boolean {
        val expected = normalizeHash(expectedSha256) ?: return true
        return sha256Hex(bytes).equals(expected, ignoreCase = true)
    }

    /**
     * 把仓库里写的 sha256 归一化；**写法不对就当没写**。
     *
     * 为什么宽容：一个字符写错（少一位、混进中文、写成 `sha256:` 前缀形式）如果判成
     * "校验失败"，结果就是所有人永远下载不了 —— 而 hash 本身防不住"把 index.json
     * 一起改掉"的攻击，它不是签名，没必要为格式问题把功能锁死。认不出来的写法会记一条警告日志。
     */
    fun normalizeHash(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val hex = text.lowercase(Locale.US).removePrefix("sha256:").trim()
        if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            AppLog.w(TAG, "sha256 写法不对，已跳过校验：" + text)
            return null
        }
        return hex
    }
}
