package com.audiometa.app.plugin.install

import android.content.Context
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.tagrules.TagRules
import com.audiometa.app.media.image.PlatformIcons
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.MirrorFetch
import com.audiometa.app.util.RemoteText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件仓库更新。
 *
 * ## 一次订阅更新 = 只下那一个 zip
 *
 * 用户要求：更新时**只下载** `index.bundle.url` 指向的 `MetaAudio-Subscription.zip`，
 * 元信息源、艺术家图源、标签规则、平台图标全在这一个包里，装完就完事。
 * 以前"包里没有就按 `plugins[].url` / `rules.file` / `icons.files[].url` 逐个下载"
 * 那几条路已经**全部删掉**：包里缺什么就如实告诉用户缺什么，不再偷偷回退到单文件下载。
 * 仓库没提供合集包（老仓库、或写漏了 `bundle`）时也只有一句话：
 * 「这个订阅源没有提供合集包（MetaAudio-Subscription.zip），无法更新」。
 *
 * 仓库里放一个 `index.json`，形如：
 * ```json
 * {
 *   "schema": 1,
 *   "name": "渊の插件源",
 *   "rules": { "file": "rules/tag_rules.json", "sha256": "…" },
 *   "icons": { "sha256": "…" },
 *   "bundle": {
 *     "url": "https://github.com/…/releases/download/v2/MetaAudio-Subscription.zip",
 *     "sha256": "…",
 *     "rules": "rules/tag_rules.json"
 *   },
 *   "plugins": [
 *     { "id": "com.qqmusic.source", "name": "QQ音乐", "versionName": "0.4.2" },
 *     { "id": "com.metaaudio.artistimage.qq", "name": "QQ 音乐 艺术家图片",
 *       "versionName": "1.0.0" }
 *   ]
 * }
 * ```
 * `plugins[]` 里**不再需要** `url` / `sha256` —— 那些字段以前是给"逐个下载"用的，
 * 现在只从包内的 `plugins/<同文件名>` 取；版本比较仍然用 `versionName`（见 [isNewer]），
 * 数据库里存的是它，也是用户在插件列表里看到的那串。
 *
 * ## 要不要下，比的是哈希
 *
 * `bundle.sha256` 是那个 zip 的 sha256。上次装成功的整包哈希记在设置里
 *（`repo_bundle_sha256`，见 [RepoIndexUrls.Bundle.sha256]）。点「更新订阅」时：
 *
 * 1. 拉 `index.json`（这是唯一一次"小请求"，任何更新判断都得先知道仓库现在是什么）；
 * 2. `bundle.sha256` 和本地记下的**一致**，并且本地该装的都在
 *    （`plugins[]` 里每个插件都在、规则文件在、图标在，见 [localResourcesComplete]）
 *    → 直接报告「已是最新」，**一个字节都不下载**；
 * 3. 不一致（或本地没有记录、或本地缺件）才下整包；下完**再校验一次 sha256**，
 *    对不上就报失败、不安装、也不记哈希 —— 下次点还会重新下。
 *
 * 第 2 步里为什么还看"本地该装的都在"：哈希是"上次装过哪一版包"的记录，
 * 不是"本地文件还在不在"的证明（用户清过数据、手动删过文件时哈希照样相等）。
 * 多这一眼才不会出现"报告已是最新、实际插件是缺的"。
 *
 * ## 包里缺件时
 *
 * 不再有兜底路线：包里没有某个插件就报「订阅包里没有 xxx」，没有规则文件就报
 * 「订阅包里没有标签识别规则」，一张 png 都没有就报「订阅包里没有平台图标」。
 * 失败的那一份不会被记哈希，所以下次点还会重新下一遍整包。
 */
object PluginRepoUpdater {

    /**
     * 单个插件的更新结果。
     *
     * 界面靠 [newest] 把那一行的按钮显示成「最新」并置灰 ——
     * 用户要的是"点一下就知道这个源要不要更新"。
     */
    data class OneResult(
        /** 给用户看的一句话。 */
        val message: String,
        /** 这次装上了新版本。 */
        val updated: Boolean = false,
        /** 本地已经是最新的（或者仓库里根本没有更新的东西）。 */
        val newest: Boolean = false
    )

    /** index.json 的解析结果；失败时 [error] 里是给用户看的原因。 */
    private class RepoIndex(
        val schema: Int = 1,
        val entries: org.json.JSONArray? = null,
        /** 插件包所在目录（**实际下载成功的那个** index.json 所在目录）。 */
        val base: String = "",
        /** 仓库自己写的名字（index.json 顶层的 `name`），没写就是空串。 */
        val name: String = "",
        /** 仓库公布的标签识别规则（没写就是空的那一份）。 */
        val rules: RepoIndexUrls.Rules = RepoIndexUrls.Rules(),
        /** 仓库公布的**合集包**（没写就是 null，这次更新直接报"没有合集包"）。 */
        val bundle: RepoIndexUrls.Bundle? = null,
        /** schema 2：元信息源（上游 Lyrico-Plugins.zip）。 */
        val meta: RepoIndexUrls.Source? = null,
        /** schema 2：艺术家图源。 */
        val artistImage: RepoIndexUrls.Source? = null,
        /** 仓库公布的**平台图标**（现在只用到整批的 sha256）。 */
        val icons: RepoIndexUrls.Icons = RepoIndexUrls.Icons(),
        val error: String = ""
    ) {
        /** 这份订阅里到底有没有规则：`rules` 没写、但订阅包里有，也算有。 */
        val hasRules: Boolean
            get() = rules.url.isNotBlank() || !bundle?.rules.isNullOrBlank()
    }

    /** @return 给用户看的一句话结果。 */
    suspend fun run(
        context: Context,
        indexUrl: String,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val index = loadIndex(indexUrl)
        if (index.error.isNotBlank()) return@withContext index.error
        val list = index.entries ?: return@withContext "index.json 里没有 plugins 字段"

        // schema 2：三份独立资源（元信息源 / 艺术家图源 / 标签规则）。
        if (index.schema >= 2 && index.meta != null && index.artistImage != null) {
            return@withContext runSplit(context, index, onProgress)
        }

        val bundle = index.bundle
        if (bundle == null || bundle.url.isBlank()) {
            return@withContext "这个订阅源没有提供合集包（MetaAudio-Subscription.zip），无法更新"
        }

        val installed = ServiceLocator.pluginRepo.plugins().associateBy { it.pluginId }
        val current = ServiceLocator.settings.settings.first()

        // 先比 md5（没变就一个字节都不下），再确认本地该装的都在，就直接收工。
        if (bundleUnchanged(bundle, current) &&
            localResourcesComplete(context, index, installed)
        ) {
            return@withContext "已经是最新版本（订阅包没有变化）"
        }

        val fetched = fetchBundle(context, bundle, onProgress)
        val bundleFiles = when (fetched) {
            is BundleFetch.Ok -> fetched.files
            is BundleFetch.Failed -> return@withContext fetched.message
        }

        val iconsNote = installIcons(context, index, bundleFiles, onProgress)
        val rulesNote = installRules(context, index, bundleFiles, onProgress)
        val pluginSummary = installPluginsFromBundle(context, index, bundleFiles, installed, onProgress)

        // 全都装上了才记哈希：任何一份缺件都不记，下次点还会重新下一遍整包。
        val complete = bundleFiles.isNotEmpty() && pluginSummary.missing.isEmpty() &&
            rulesNote.isBlank() && iconsNote.isBlank()
        if (complete) rememberBundleHash(bundle.sha256, bundle.md5)
        else AppLog.w(TAG, "订阅包这一轮没装全，不记哈希：缺件 " + pluginSummary.missing.size + " 个")

        val notes = buildList {
            if (rulesNote.isNotBlank()) add(rulesNote)
            if (iconsNote.isNotBlank()) add(iconsNote)
        }.joinToString("；")
        pluginSummary.text + if (notes.isBlank()) "" else "；" + notes
    }

    /**
     * schema 2：分别下载元信息源、艺术家图源、标签规则，逐份校验后安装。
     *
     * 三份资源的 sha256 合成一个"订阅版本"存在原来记合集哈希的位置：
     * 三份都没变就一个字节都不下；任何一份缺件都不记，下次会重试。
     */
    private suspend fun runSplit(
        context: Context,
        index: RepoIndex,
        onProgress: (String) -> Unit
    ): String {
        val meta = index.meta ?: return "index.json 缺少 meta"
        val artist = index.artistImage ?: return "index.json 缺少 artistImage"
        val rules = index.rules
        if (rules.url.isBlank()) return "index.json 缺少 rules"

        val combined = RemoteText.sha256Hex(
            ((meta.sha256 + "|" + artist.sha256 + "|" + rules.sha256).toByteArray(Charsets.UTF_8))
        )
        val current = ServiceLocator.settings.settings.first()
        val installed = ServiceLocator.pluginRepo.plugins()
        val expectedIds = buildList {
            addAll(meta.pluginIds)
            val array = index.entries
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val localComplete = expectedIds.isNotEmpty() &&
            expectedIds.all { id -> installed.any { it.pluginId == id } } &&
            TagRules.isImported
        if (current.repoBundleSha256.trim() == combined && localComplete) {
            return "已经是最新版本（三份资源都没有变化）"
        }

        onProgress("下载元信息源…")
        val metaBytes = downloadSplit(meta, "元信息源") ?: return splitError ?: "元信息源下载失败"
        onProgress("安装元信息源…")
        val metaReport = runCatching {
            ServiceLocator.plugins.install(ByteArrayInputStream(metaBytes))
        }.getOrElse {
            AppLog.w(TAG, "元信息源安装失败：" + it.message)
            return "元信息源安装失败：" + (it.message ?: "未知错误")
        }

        onProgress("下载艺术家图源…")
        val artistBytes = downloadSplit(artist, "艺术家图源") ?: return splitError ?: "艺术家图源下载失败"
        onProgress("安装艺术家图源…")
        val artistReport = runCatching {
            ServiceLocator.plugins.install(ByteArrayInputStream(artistBytes))
        }.getOrElse {
            AppLog.w(TAG, "艺术家图源安装失败：" + it.message)
            return "艺术家图源安装失败：" + (it.message ?: "未知错误")
        }
        val icons = runCatching { iconsInZip(artistBytes) }.getOrDefault(emptyMap())
        val iconCount = if (icons.isEmpty()) 0 else PlatformIcons.install(context, icons)

        onProgress("下载标签规则…")
        val rulesSource = RepoIndexUrls.Source(url = rules.url, sha256 = rules.sha256)
        val rulesBytes = downloadSplit(rulesSource, "标签规则") ?: return splitError ?: "标签规则下载失败"
        val rulesInstalled = runCatching {
            TagRules.installRules(context, String(rulesBytes, Charsets.UTF_8), "订阅仓库")
        }.getOrDefault(false)
        if (rulesInstalled) applyRuleSourcePriority()

        val complete = metaReport.manifests.isNotEmpty() &&
            artistReport.manifests.isNotEmpty() &&
            rulesInstalled
        if (complete) rememberBundleHash(combined, "")
        else AppLog.w(TAG, "拆分订阅这一轮没装全，不记哈希（元信息 ${metaReport.manifests.size}，" +
            "艺术家 ${artistReport.manifests.size}，规则 $rulesInstalled）")

        return buildString {
            append("元信息源 ").append(metaReport.manifests.size).append(" 个")
            append("，艺术家图源 ").append(artistReport.manifests.size).append(" 个")
            if (iconCount > 0) append("，图标 ").append(iconCount).append(" 张")
            append(if (rulesInstalled) "，规则已更新" else "，规则未更新")
        }
    }

    /** 规则里带的源优先级 / 默认关闭：更新订阅后立即生效。 */
    private suspend fun applyRuleSourcePriority() {
        runCatching {
            val book = TagRules.ruleBook
            if (book.sourcePriority.isNotEmpty()) {
                ServiceLocator.settings.setSourcePriority(book.sourcePriority)
            }
            book.disabledSources.forEach { pluginId ->
                ServiceLocator.pluginRepo.setPluginEnabled(pluginId, false)
                ServiceLocator.pluginRepo.sources()
                    .filter { it.pluginId == pluginId }
                    .forEach { source ->
                        ServiceLocator.pluginRepo.setSourceEnabled(pluginId, source.sourceId, false)
                    }
            }
            if (book.sourcePriority.isNotEmpty() || book.disabledSources.isNotEmpty()) {
                AppLog.i(TAG, "标签规则里的源优先级已应用：" +
                    book.sourcePriority.keys.joinToString("/") +
                    "，默认关闭 " + book.disabledSources.joinToString("/"))
            }
        }.onFailure {
            AppLog.w(TAG, "应用规则源优先级失败（不影响本次安装）：" + it.message)
        }
    }

    /** 下载一份拆分资源；失败原因写进 [splitError]，供调用方直接展示。 */
    private var splitError: String? = null

    private suspend fun downloadSplit(
        source: RepoIndexUrls.Source,
        label: String
    ): ByteArray? {
        splitError = null
        val result = MirrorFetch.download(
            source.url,
            sha256 = source.sha256.takeIf { it.isNotBlank() },
            attemptTimeoutMs = SPLIT_ATTEMPT_TIMEOUT_MS,
            budgetMs = SPLIT_BUDGET_MS
        )
        if (!result.ok) {
            AppLog.w(TAG, label + "下载失败：" + result.error)
            splitError = "下载" + label + "失败：" + result.error
            return null
        }
        val expected = source.sha256.trim()
        if (expected.isNotEmpty()) {
            val actual = RemoteText.sha256Hex(result.bytes)
            if (!actual.equals(expected, ignoreCase = true)) {
                AppLog.w(TAG, label + "校验值对不上：公布 " + expected + "，实际 " + actual)
                splitError = label + "的校验值对不上（可能被篡改，或镜像缓存还没同步）"
                return null
            }
        }
        if (source.size > 0 && result.bytes.size.toLong() != source.size) {
            splitError = label + "大小不对：公布 " + source.size + "，实际 " + result.bytes.size
            return null
        }
        return result.bytes
    }

    /** 从艺术家图源 zip 里取出 `icons/<name>.png`（图标跟着这一份走，保持总共只下三份）。 */
    private fun iconsInZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = entry.name.replace('\\', '/')
                    if (path.startsWith("icons/") && path.endsWith(".png", ignoreCase = true)) {
                        out[path.substringAfterLast('/')] = zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
        return out
    }

    /**
     * 拉取并解析 index.json。任何一步失败都把原因写进 [RepoIndex.error]。
     *
     * **多拿几份、挑最新的那份**：镜像之间的缓存并不一致（jsDelivr 把分支名钉在旧提交上，
     * 同一个 index.json 在不同入口可能是几个月前那版）。只信第一个回 200 的，
     * 就会拿着过期条目去更新。这里按 index.json 自己的 `updatedAt` 挑最新的一份。
     */
    private suspend fun loadIndex(indexUrl: String): RepoIndex {
        val fetched = MirrorFetch.downloadAll(
            indexUrl,
            budgetMs = INDEX_BUDGET_MS,
            maxSuccess = INDEX_CANDIDATES
        )
        if (fetched.isEmpty()) {
            return RepoIndex(error = "下载 index.json 失败：所有候选都不通（检查网络或换镜像）")
        }

        var bestList = mutableListOf<Pair<org.json.JSONObject, String>>()
        var updatedAts = mutableListOf<String>()
        var parsedAny = false
        var invalidReason = ""
        for (item in fetched) {
            // 开头那个 BOM 要先去掉：仓库文件用记事本或 Windows PowerShell 另存过就会带上它，
            // 而 org.json 见到 BOM 直接判"不是合法 JSON" —— 整次更新因此全废，
            // 报出来却是"index.json 不是合法 JSON"，看着像文件写错了。
            val root = runCatching { JSONObject(RepoIndexUrls.stripBom(item.text)) }.getOrNull()
            if (root == null) {
                invalidReason = "index.json 不是合法 JSON"
                continue
            }
            if (root.optJSONArray("plugins") == null) {
                invalidReason = "index.json 里没有 plugins 字段"
                continue
            }
            parsedAny = true
            bestList += root to item.candidate.url
            updatedAts += root.optString("updatedAt")
        }
        if (bestList.isEmpty()) {
            return RepoIndex(
                error = if (parsedAny) invalidReason
                else "index.json 的每一份都解析失败（" + invalidReason + "）"
            )
        }
        val pick = RepoIndexUrls.newestIndexBy(updatedAts)
        val root = bestList[pick].first
        val list = root.optJSONArray("plugins")
            ?: return RepoIndex(error = "index.json 里没有 plugins 字段")
        // 基准目录取**用户填的那个地址**（见 [RepoIndexUrls.baseOf]）：这样相对路径写法的
        // `bundle.file` 也能走一遍完整的候选推导（直连 → 各镜像 → 中转），
        // 而不会因为"index.json 是从某个镜像拿到的"就被钉死在那条路上。
        val base = RepoIndexUrls.baseOf(indexUrl, bestList[pick].second)
        val name = RepoIndexUrls.repoName(root)
        rememberRepoName(name)
        AppLog.i(
            TAG,
            "index.json 共取到 ${fetched.size} 份，采用 updatedAt=" + updatedAts.getOrElse(pick) { "" } +
                " 的那份（" + bestList[pick].second + "）"
        )
        return RepoIndex(
            schema = root.optInt("schema", 1),
            entries = list,
            base = base,
            name = name,
            rules = RepoIndexUrls.rules(root, base),
            bundle = RepoIndexUrls.bundle(root, base),
            meta = RepoIndexUrls.meta(root, base),
            artistImage = RepoIndexUrls.artistImage(root, base),
            icons = RepoIndexUrls.icons(root)
        )
    }

    /**
     * 订阅包有没有变：**先比 md5**（用户要求"更新前先比对 md5"），
     * 仓库没写 md5 时退回比 sha256（老仓库照样能用）。
     *
     * md5 一样就说明上次装的就是仓库现在这一份，不必再拉 2MB；
     * 真正的完整性校验放在下载之后用 sha256 做（见 [fetchBundle]）。
     */
    private fun bundleUnchanged(
        bundle: RepoIndexUrls.Bundle,
        current: com.audiometa.app.data.prefs.AppSettings
    ): Boolean {
        val publishedMd5 = bundle.md5.trim()
        if (publishedMd5.isNotEmpty()) {
            return current.repoBundleMd5.trim().equals(publishedMd5, ignoreCase = true)
        }
        val publishedSha = bundle.sha256.trim()
        // 两个都没公布：没法判断，只能当"变了"，照旧下一次包。
        if (publishedSha.isEmpty()) return false
        return current.repoBundleSha256.trim().equals(publishedSha, ignoreCase = true)
    }

    /**
     * 本地该装的都在不在。
     *
     * 哈希一致只说明"上次装的就是这一版包"，不说明本地文件还在（用户清过数据、
     * 手动删过插件目录时哈希照样相等）。所以还要看一眼：
     * - `plugins[]` 里每个插件都在，而且本地版本不比仓库里的旧；
     * - 仓库写了规则、本地也确实有规则文件；
     * - 仓库公布了图标，本地至少有一张图。
     *
     * 只要有一项不满足就当"要更新"，下一次整包 —— 宁可多下一次，也不要报告
     * "已经是最新"而实际缺件。
     */
    private fun localResourcesComplete(
        context: Context,
        index: RepoIndex,
        installed: Map<String, com.audiometa.app.data.db.PluginRecordEntity>
    ): Boolean {
        val list = index.entries ?: return false
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val local = installed[id] ?: return false
            // 本地版本比仓库里的旧 → 还没装上（"更新"按钮点了才有意义）。
            if (isNewer(local.pluginVersion, item)) return false
        }
        if (index.hasRules && !hasInstalledRules(context, bundledRulesSha(index))) return false
        if (index.icons.sha256.isNotBlank() && !PlatformIcons.hasIconsOnDisk(context)) return false
        return true
    }

    /**
     * 判断"本地那份规则是不是已经是订阅里这一份"用的校验值。
     *
     * App 落盘的是**订阅包里合并好的那份** `rules/tag_rules.json`，
     * 所以优先用它自己的 sha256（`bundle.rulesSha256`）；
     * 老仓库没公布时才退回顶层 `rules.sha256`（分片清单的哈希，兜底用）。
     */
    private fun bundledRulesSha(index: RepoIndex): String =
        index.bundle?.rulesSha256?.takeIf { it.isNotBlank() } ?: index.rules.sha256.trim()

    /**
     * 本地那份标签规则在不在。
     *
     * 仓库公布了 `rules.sha256` 时**按内容校验**：文件被删、被换成别的内容都算缺。
     * 没公布校验值（老写法）时只看文件在不在。
     */
    private fun hasInstalledRules(context: Context, expected: String): Boolean {
        val file = com.audiometa.app.util.StoragePaths.tagRulesFile(context)
        if (!file.isFile || file.length() == 0L) return false
        val sha = expected.trim()
        if (sha.isEmpty()) return true
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        return RemoteText.sha256Hex(bytes).equals(sha, ignoreCase = true)
    }

    /**
     * 把订阅里的插件装上。
     *
     * **只从包里取**：`plugins/<index.json 里那条 url 的末段>`，取不到就如实报缺，
     * 不再按条目里的 `url` 单独下载。插件包自己在 `PluginInstaller` 里判断
     * "同版本覆盖 / 更高升级 / 更低跳过"（那部分不动）。
     */
    private suspend fun installPluginsFromBundle(
        context: Context,
        index: RepoIndex,
        bundleFiles: Map<String, ByteArray>,
        installed: Map<String, com.audiometa.app.data.db.PluginRecordEntity>,
        onProgress: (String) -> Unit
    ): PluginInstallSummary {
        val list = index.entries ?: return PluginInstallSummary("index.json 里没有 plugins 字段")
        val names = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val name = item.optString("name", id)
            if (!isNewer(installed[id]?.pluginVersion, item)) {
                skipped += name
                continue
            }
            val local = pluginBytes(bundleFiles, index, item)
            if (local == null) {
                // 包里没有这一个：这是**如实汇报**，不是走另一条路去把它下回来。
                missing += name
                AppLog.w(TAG, "订阅包里没有这个插件：" + id)
                continue
            }
            onProgress("正在安装 " + name + "…")
            val error = installBytes(context, local.second, local.first)
            if (error == null) names += name else reasons += name + "：" + error
        }

        val summary = when {
            names.isNotEmpty() && reasons.isEmpty() ->
                "已更新 " + names.size + " 个插件：" + names.joinToString("、") +
                    if (skipped.isNotEmpty()) "（其余 " + skipped.size + " 个已是最新）" else ""
            names.isNotEmpty() -> "更新 " + names.size + " 个，失败 " + reasons.size + " 个" +
                firstReason(reasons)
            reasons.isNotEmpty() -> "更新失败 " + reasons.size + " 个" + firstReason(reasons)
            missing.isNotEmpty() -> "这次订阅包里没有要更新的插件"
            else -> "已经是最新版本（" + skipped.size + " 个插件）"
        }
        val missingNote = if (missing.isEmpty()) "" else
            "；这次订阅包里没有 " + missing.joinToString("、")
        return PluginInstallSummary(summary + missingNote, missing)
    }

    /** 一次插件安装的汇总：给用户看的话 + 包里缺的那些（缺件不记哈希）。 */
    private class PluginInstallSummary(val text: String, val missing: List<String> = emptyList())

    /**
     * 订阅包里的那一个插件包。
     *
     * 键是**文件名**（`com.qqmusic.source-0.4.2.zip`），所以先按条目 `url` 的末段找；
     * 老 index.json 里没有 `url`（现在不再需要它了）时，退回按 `id` 加 `.zip` 猜一次 ——
     * 仓库打包时用的就是这个名字。
     *
     * @return 文件名 to 字节；**null 表示包里没有这一个**（调用方如实报缺）。
     */
    private fun pluginBytes(
        bundleFiles: Map<String, ByteArray>,
        index: RepoIndex,
        item: JSONObject
    ): Pair<String, ByteArray>? {
        val url = RepoIndexUrls.pluginUrl(item, index.base)
        val key = url.substringBefore('?').substringAfterLast('/')
        bundleFiles[key]?.let { return key to it }
        val id = item.optString("id").trim()
        if (id.isNotBlank()) {
            val guess = id + ".zip"
            bundleFiles[guess]?.let { return guess to it }
        }
        return null
    }

    /**
     * 记下这次装好的整包校验值（md5 用来判断"要不要下"，sha256 用来核对内容）。
     *
     * 下次点「更新订阅」就靠 md5 判断"一个字节都不用下"。
     */
    private suspend fun rememberBundleHash(sha256: String, md5: String = "") {
        runCatching { ServiceLocator.settings.setRepoBundle(sha256.trim(), md5.trim()) }
            .onFailure { AppLog.w(TAG, "订阅包校验值保存失败：" + it.message) }
    }

    /**
     * 把订阅里的平台图标装进 App（「元信息」页每个分组左边那张图）。
     *
     * **只从包里取**（`icons/` 目录里的 png，和解压出来的插件、规则在同一次下载里）；
     * 包里一张 png 都没有就如实说"这次订阅包里没有平台图标"，不再按地址逐个下。
     *
     * 一张都没装上（图不是 png、写盘失败）时**什么都不记**：下次点更新还会再试，
     * 而不是记成"已经装好了"。
     *
     * @return 给用户看的一句话；没缺件时返回空串。
     */
    private fun installIcons(
        context: Context,
        index: RepoIndex,
        bundleFiles: Map<String, ByteArray>,
        onProgress: (String) -> Unit
    ): String {
        if (index.icons.sha256.isBlank() && !bundleFiles.keys.any { it.endsWith(".png", true) }) {
            // 仓库压根没公布图标：没什么可说的（老仓库、或这个源就不带图）。
            return ""
        }
        val fromBundle = bundleFiles.filterKeys { it.endsWith(".png", true) }
        if (fromBundle.isEmpty()) return "这次订阅包里没有平台图标"
        onProgress("正在更新平台图标…")
        val written = PlatformIcons.install(context, fromBundle)
        if (written <= 0) return "订阅包里的平台图标装不上（不是 png 或写盘失败）"
        return ""
    }

    /**
     * 把订阅包里的标签识别规则装进 App。
     *
     * 规则**只能这么来**：App 里既没有手填规则地址的输入框，也不能从本地文件导入
     *（用户明确要求把那两条路都去掉）。包里的那一份是**合并好的完整规则**，
     * 拿到就直接落盘 —— 不再解析分片清单、不再为三十多个分片各发一次请求。
     *
     * @return 给用户看的一句话；订阅里本来就没有规则、或者已经装好了，返回空串。
     */
    private suspend fun installRules(
        context: Context,
        index: RepoIndex,
        bundleFiles: Map<String, ByteArray>,
        onProgress: (String) -> Unit
    ): String {
        if (!index.hasRules) return ""
        val expected = bundledRulesSha(index)
        if (expected.isNotEmpty() && hasInstalledRules(context, expected)) return ""

        // 包里那份规则的键：仓库在 `bundle.rules` 里写明了就用它；没写就按订阅公布的
        // 规则地址、以及约定的默认名字各试一次（订阅包内部一律是相对路径，取末段）。
        val keys = listOf(index.bundle?.rules.orEmpty(), index.rules.url, DEFAULT_BUNDLE_RULES)
            .map { it.substringBefore('?').substringAfterLast('/') }
            .filter { it.isNotBlank() }
        val bytes = keys.firstNotNullOfOrNull { bundleFiles[it] }
            ?: return "这次订阅包里没有标签识别规则"

        val text = String(bytes, Charsets.UTF_8)
        onProgress("正在更新标签识别规则…")
        val installed = runCatching {
            com.audiometa.app.domain.tagrules.TagRules.installRules(context, text, "订阅仓库")
        }.getOrDefault(false)
        if (!installed) return "订阅包里的标签识别规则不是有效文件，保留原来那份"
        applyRuleSourcePriority()
        val version = com.audiometa.app.domain.tagrules.TagRules.ruleInfo.version
        return "标签识别规则已更新（" + version + " 版）"
    }

    /**
     * 把 index.json 里的仓库名记进设置：插件页那一行要显示「渊の插件源」这种名字，
     * 而不是一长串地址，而且重启之后还得记得（用户不用每次进来都等一次检查更新）。
     *
     * 仓库没写 `name` 时写空串：换了一个不写名字的仓库，界面就退回显示地址，
     * 而不是继续挂着上一个仓库的名字。
     */
    private suspend fun rememberRepoName(name: String) {
        runCatching { ServiceLocator.settings.setPluginRepoName(name) }
            .onFailure { AppLog.w(TAG, "仓库名保存失败：" + it.message) }
    }

    /**
     * 把一段插件包字节装进 App。
     *
     * 字节只有一个来源：订阅包解压出来的那一条。先落成临时文件再交给安装器
     *（安装器按文件路径装），装完删掉临时文件。
     *
     * @return 出错原因；**null 表示装好了**。
     */
    private suspend fun installBytes(context: Context, bytes: ByteArray, name: String): String? {
        if (bytes.isEmpty()) return "内容为空"
        val temp = File(
            com.audiometa.app.util.StoragePaths.ensuredDir(
                context, com.audiometa.app.util.StoragePaths.DIR_TEMP
            ),
            name
        )
        if (!runCatching { temp.writeBytes(bytes) }.isSuccess) return "临时文件写入失败"
        val ok = runCatching {
            ServiceLocator.plugins.install(temp).manifests.isNotEmpty()
        }.getOrElse {
            AppLog.e(TAG, "插件安装失败：" + name, it)
            false
        }
        runCatching { temp.delete() }
        return if (ok) null else "插件包安装失败"
    }

    /** 订阅包下载的结果：拿到了文件表，或者一句给用户看的失败原因。 */
    private sealed interface BundleFetch {
        class Ok(val files: Map<String, ByteArray>) : BundleFetch
        class Failed(val message: String) : BundleFetch
    }

    /**
     * 下载、校验并解开仓库的**合集包**（`MetaAudio-Subscription.zip`）。
     *
     * 这是整个更新流程里**唯一**的下载（除了那张 4 KB 的 index.json）。
     * 整包按仓库公布的 sha256 校验，对不上就报失败、不安装、不记哈希。
     *
     * zip 的解析规则**没有动**：只认 `plugins/`、`rules/`、`icons/` 三个前缀，
     * 带 `..` 的条目直接跳过（合集是外部内容，不能允许它逃出临时目录），
     * 键统一取**文件名**（`index.json` 里的 url 末段就是它）。
     */
    private suspend fun fetchBundle(
        context: Context,
        bundle: RepoIndexUrls.Bundle,
        onProgress: (String) -> Unit
    ): BundleFetch {
        onProgress("正在下载订阅包（元信息源、艺术家图源、标签规则）…")
        // 一条订阅里近十个插件包加一份规则：逐个下载就是十来次"直连 → 各镜像 → 中转"，
        // 在国内网络下慢得离谱。整包一次下完，请求数从十几次降到 1 次。
        val result = MirrorFetch.download(
            bundle.url,
            sha256 = bundle.sha256.takeIf { it.isNotBlank() },
            // 合集是几十个小包的合并，比单个包大得多，时间和尝试次数都给足
            attemptTimeoutMs = BUNDLE_ATTEMPT_TIMEOUT_MS,
            budgetMs = BUNDLE_BUDGET_MS
        )
        if (!result.ok) {
            AppLog.w(TAG, "订阅包下载失败：" + result.error)
            return BundleFetch.Failed("下载订阅包失败：" + result.error)
        }
        // 下载层按候选逐个校验过；这里再对最终这份字节核一次，免得"某个候选没配校验值"
        // 之类的组合悄悄放进来一份对不上的内容。
        val expected = bundle.sha256.trim()
        if (expected.isNotEmpty()) {
            val actual = RemoteText.sha256Hex(result.bytes)
            if (!actual.equals(expected, ignoreCase = true)) {
                AppLog.w(TAG, "订阅包校验值对不上：公布 " + expected + "，实际 " + actual)
                return BundleFetch.Failed("订阅包的校验值对不上（可能被篡改，或镜像缓存还没同步）")
            }
        }

        val tempDir = File(
            com.audiometa.app.util.StoragePaths.ensuredDir(
                context, com.audiometa.app.util.StoragePaths.DIR_TEMP
            ),
            "bundle_" + System.currentTimeMillis()
        )
        return try {
            if (!tempDir.mkdirs()) return BundleFetch.Failed("订阅包解压失败：临时目录建不出来")
            val zip = File(tempDir, "bundle.zip")
            if (!runCatching { zip.writeBytes(result.bytes) }.isSuccess) {
                return BundleFetch.Failed("订阅包解压失败：临时文件写不进去")
            }

            val out = LinkedHashMap<String, ByteArray>()
            val entries = runCatching { java.util.zip.ZipFile(zip) }.getOrNull()
                ?: return BundleFetch.Failed("订阅包不是有效的 zip")
            entries.use { zf ->
                val it = zf.entries()
                while (it.hasMoreElements()) {
                    val e = it.nextElement()
                    if (e.isDirectory) continue
                    // 只取我们认识的目录，避免把仓库里其他东西也读进来
                    val path = e.name.replace('\\', '/')
                    if (!path.startsWith("plugins/") && !path.startsWith("rules/") &&
                        !path.startsWith("icons/")
                    ) {
                        continue
                    }
                    // 目录遍历防护：合集是外部内容，不能允许 ../ 逃出去
                    if (path.contains("..")) continue
                    val bytes = runCatching { zf.getInputStream(e).use { s -> s.readBytes() } }
                        .getOrNull() ?: continue
                    // 键用**文件名**：index.json 里的 url 末尾就是它
                    out[path.substringAfterLast('/')] = bytes
                }
            }
            if (out.isEmpty()) {
                AppLog.w(TAG, "订阅包里 plugins/ rules/ icons/ 一个文件都没有")
                return BundleFetch.Failed("订阅包里没有可安装的内容（plugins/、rules/、icons/ 都是空的）")
            }
            AppLog.i(TAG, "订阅包解开 " + out.size + " 个文件")
            BundleFetch.Ok(out)
        } catch (t: Throwable) {
            AppLog.w(TAG, "订阅包解压失败：" + (t.message ?: t.javaClass.simpleName))
            BundleFetch.Failed("订阅包解压失败：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /** 结果里附上失败原因，**但要短**：九个源一起失败时，全文能到几千字。 */
    private fun firstReason(reasons: List<String>): String {
        if (reasons.isEmpty()) return ""
        val brief = reasons.take(3).joinToString("；") { reason ->
            val short = reason.replace('\n', ' ').trim()
            if (short.length <= MAX_REASON_IN_REPORT) short else short.take(MAX_REASON_IN_REPORT) + "…"
        }
        val more = if (reasons.size > 3) "；另有 " + (reasons.size - 3) + " 个也失败" else ""
        return "（" + brief + more + "）"
    }

    /**
     * 结果里每个失败源最多留多少字。
     *
     * 一条下载失败的原因本身可能两三百字（列了试过哪些镜像、各自错在哪），
     * 九个源一起失败就是几千字的一行 —— 界面放不下，用户看到的是被截断、
     * 挤成一团的乱码。这里只留头一段；完整原因照样写进日志（见 [run] 里的 AppLog）。
     */
    private const val MAX_REASON_IN_REPORT = 70

    /** 合集的单次尝试上限（毫秒）：订阅包 1.6 MB 上下，中转站上要给它读完的时间。 */
    private const val BUNDLE_ATTEMPT_TIMEOUT_MS = 60_000

    /** 合集的总预算（毫秒）：直连不通时还要把几个镜像都试一遍，一次 60 秒，留够四轮。 */
    private const val BUNDLE_BUDGET_MS = 240_000L

    /** 拆分资源单份（元信息源约 1.9MB、艺术家图源约 180KB、规则 38KB）。 */
    private const val SPLIT_ATTEMPT_TIMEOUT_MS = 60_000

    /** 三份资源各自的总预算。 */
    private const val SPLIT_BUDGET_MS = 180_000L

    /**
     * 订阅包里规则文件约定的位置。
     *
     * 仓库在 `bundle.rules` 里写明了就用它；那一条缺失时按这个名字兜底
     *（建立约定是为了让"仓库只改了包、忘了写字段"这种情况也能装上规则）。
     */
    private const val DEFAULT_BUNDLE_RULES = "rules/tag_rules.json"

    /**
     * 仓库里的这一条是不是比本地的新。
     *
     * 比的是 **versionName**（[compareVersions] 就是安装器判断"升级还是覆盖"用的那个
     * 比较器），因为数据库里存的就是 versionName。
     *
     * 以前这里比 versionCode：仓库里的 versionCode 是插件自己的编号（QQ 是 8），
     * 而本地只有 versionName，只能把它解析成 "0.4.2" → 402，两个数根本不是一个量纲 ——
     * 结果是 `8 > 402` 永远不成立，整个更新功能一次都不会触发
     *（用户点「更新订阅」看到的永远是"已经是最新版本"）。
     *
     * 仓库只给 versionCode、没给 versionName 时才退回按编号比（老行为，兜底用）。
     */
    private fun isNewer(installedVersion: String?, item: JSONObject): Boolean {
        if (installedVersion == null) return true
        val remoteName = item.optString("versionName")
        if (remoteName.isNotBlank()) return compareVersions(installedVersion, remoteName) < 0
        val remoteCode = item.optInt("versionCode", 0)
        return remoteCode > 0 && remoteCode > parseCode(installedVersion)
    }

    /** versionName 形如 "0.4.2" → 42，只用来在没有 versionCode 时兜底比较。 */
    private fun parseCode(version: String): Int =
        version.split('.').take(3).joinToString("") { part ->
            (part.filter { it.isDigit() }.toIntOrNull() ?: 0).toString().padStart(2, '0')
        }.toIntOrNull() ?: 0

    private const val TAG = "PluginRepoUpdater"

    /**
     * index.json 各取几份来挑最新的。
     *
     * 镜像之间的缓存并不一致：jsDelivr 会把分支名（`@main`）长期钉在旧提交上，
     * 同一个 `index.json` 在 cdn 上是几个月前那版、在 testingcf 上却是当前版本。
     * 只取一份就会拿到过期内容（用户报的"首次更新失败、规则装成旧版"就是这个）。
     * 多取几份的代价很小（index 只有几 KB），换来的是第一次更新就能成功。
     */
    private const val INDEX_CANDIDATES = 5

    /** 这个预算是"多拿几份"的，比单份下载宽一些。 */
    private const val INDEX_BUDGET_MS = 60_000L
}

/**
 * index.json 里"从哪儿下载、校验值是多少"的推导。
 *
 * 抽成纯函数是为了能在电脑上直接测：这些规则（相对路径、完整 url、规则条目两种写法）
 * 一旦错了，用户看到的是"下载失败"，而真正的原因在解析这一步。
 */
internal object RepoIndexUrls {
    /** 标签规则条目。`rules` 既可以是字符串（老写法），也可以是带 sha256 的对象。 */
    data class Rules(val url: String = "", val sha256: String = "")

    /**
     * 平台图标条目（「元信息」页每个分组左边那张图）。
     *
     * ```json
     * "icons": { "sha256": "…" }
     * ```
     * 这些图跟着**订阅包**走（包 `icons/` 目录里的 png），App 不会再为它们发请求，
     * 所以现在只用得上整批的 sha256：判断"本地有没有这一批图"。
     * 一张张地址的 `files[]` 已经不读了 —— 那条路是单文件下载，已按用户要求删掉。
     */
    data class Icons(val sha256: String = "")

    /**
     * 平台图标条目：只取整批校验值。
     *
     * 以前这里还会解析 `files[]`（每条的名字 + 地址 + 校验值），那是"订阅包里没有图
     * 就按地址逐个下"用的；单文件下载删掉之后它没有任何读者，所以一并删了 ——
     * 留着只会让人以为还有一条兜底路线。也不需要 index.json 的目录了：
     * 不再拼任何图标地址。
     */
    fun icons(root: JSONObject): Icons {
        val obj = root.optJSONObject("icons") ?: return Icons()
        return Icons(sha256 = obj.optString("sha256").trim())
    }

    /**
     * 合集包条目。
     *
     * 仓库把全部插件包与规则压成一个 zip（内含 `plugins/`、`rules/`、`icons/`），
     * App 下这一个文件就够更新整份订阅。index.json 里这样写：
     * ```json
     * "bundle": {
     *   "url": "https://github.com/…/releases/download/v2/MetaAudio-Subscription.zip",
     *   "sha256": "…",
     *   "rules": "rules/tag_rules.json"
     * }
     * ```
     * `sha256` 就是**这一次更新要不要下载**的判据：和本地记下的那份一致就不下。
     * `rules` 是**包内那份规则的路径**（相对路径，取末段当键）：写明了 App 就照着取。
     * 仓库里并排写的 `rulesSha256` 已经不读了（单文件校验那条路删了，整包的 sha256
     * 才是唯一判据），留着它也不会被用到。
     *
     * **不写就没有合集**，这次更新直接回一句"这个订阅源没有提供合集包" ——
     * 没有兜底的逐文件下载了（用户要求删掉）。
     */
    data class Bundle(
        val url: String = "",
        /** 整包的 sha256：下载完之后校验内容用。 */
        val sha256: String = "",
        /**
         * 整包的 md5：**更新前先比它**。
         *
         * 和 sha256 的分工：md5 只回答"要不要下载"（快、字符串比对），
         * sha256 回答"下到的文件对不对"（下载后校验）。
         */
        val md5: String = "",
        /** 包内规则文件的路径（例如 `rules/tag_rules.json`）；没写就是空串。 */
        val rules: String = "",
        /**
         * 包内那份规则的**内容**校验值。
         *
         * 也就是 App 落盘的那一份（合并后的完整规则）的 sha256。
         * 以前这里不读 `bundle.rulesSha256`，判断"规则是不是已经装好了"时
         * 拿 `rules.sha256`（**分片清单**的哈希）去和一个合并后的文件比 ——
         * 两者必然不等，于是每次点「更新订阅」都会被判成"规则还没装"，重下整包。
         * 用户要的是"每次更新都拿到最新订阅"，读对它之后"已经是最新"才真的成立。
         */
        val rulesSha256: String = ""
    )

    /**
     * 多份 index.json 里挑最新的那份，返回下标。
     * 镜像之间的缓存不一致（jsDelivr 会把 `@main` 钉在旧提交上），所以下载时会多取几份，
     * 由这里按仓库自己写的 `updatedAt` 比大小。ISO 日期字符串直接比字典序就是比时间。
     * 都没写（或全为空）时取第一份 —— 老仓库没有这个字段，行为要和以前一样。
     */
    fun newestIndexBy(updatedAt: List<String>): Int {
        if (updatedAt.isEmpty()) return -1
        var best = 0
        var bestValue = updatedAt[0].trim()
        for (index in 1 until updatedAt.size) {
            val value = updatedAt[index].trim()
            if (value > bestValue) {
                best = index
                bestValue = value
            }
        }
        return best
    }

    /**
     * 插件包的文件名（用来在订阅包里取那一个条目）。
     *
     * 三种写法都认：
     * - `file` 相对 index.json（一直以来的写法）；
     * - `file` 直接写完整 url；
     * - 单独的 `url` 字段。
     *
     * 这个地址**已经不用来下载了**（订阅包是唯一的下载来源），只用来算包内文件名：
     * 仓库把插件打包进 zip 时用的就是它末尾那个名字。
     */
    fun pluginUrl(item: JSONObject, baseUrl: String): String {
        val absolute = item.optString("url").trim()
        if (isHttp(absolute)) return absolute
        val file = item.optString("file").trim()
        if (file.isEmpty()) return absolute
        if (isHttp(file)) return file
        return join(baseUrl, file)
    }

    /**
     * 仓库自己写的名字（index.json 顶层的 `name`，例如「渊の插件源」）。
     *
     * 纯展示用：插件页那一行的标题就是它。老仓库没写这个字段时返回空串，
     * 界面退回显示订阅地址 —— 少了它不该影响任何功能。
     */
    fun repoName(root: JSONObject): String = root.optString("name").trim()

    /**
     * 去掉开头的 BOM。
     *
     * 仓库文件被记事本 / Windows PowerShell 里的 `ConvertTo-Json | Set-Content` 写过，
     * 开头就会多一个 U+FEFF。它不是 JSON 里的空白字符，`JSONObject` 见到它直接抛
     * "不是合法 JSON"，于是整个更新流程都断了，报错却指向文件内容。
     */
    fun stripBom(text: String): String = text.trimStart('\uFEFF')

    /**
     * 订阅包地址的基准目录。
     *
     * 用**用户填的那个地址**（而不是"实际下载成功的镜像地址"）：
     * 这样 `bundle.file` 写相对路径时也能走一遍完整的候选推导
     *（直连 → jsDelivr → 中转）。镜像不会因此被浪费 —— 上次生效的镜像由
     * MirrorFetch 记住，下一次下载会把它排到最前。
     */
    fun baseOf(indexUrl: String, usedUrl: String): String {
        val trimmed = indexUrl.trim()
        val hasPath = runCatching { java.net.URL(trimmed).path.orEmpty().isNotBlank() }
            .getOrDefault(false)
        val source = if (isHttp(trimmed) && hasPath) trimmed else usedUrl
        return source.substringBeforeLast('/')
    }

    /** 规则条目：地址解析成绝对地址，校验值可缺省。 */
    fun rules(root: JSONObject, baseUrl: String): Rules {
        val obj = root.optJSONObject("rules")
        val raw = root.opt("rules")
        val path = when {
            obj != null -> obj.optString("file").ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("path") }.trim()
            raw is String -> raw.trim()
            // 其它写法（数组、数字）一律当没写，免得拼出一个莫名其妙的地址。
            else -> ""
        }
        if (path.isEmpty()) return Rules()
        // 这一份的校验值仍然读；顶层的 rulesSha256 简写也认（老仓库只改一行也能用）。
        val sha = obj?.optString("sha256").orEmpty()
            .ifBlank { root.optString("rulesSha256") }
            .trim()
        return Rules(url = if (isHttp(path)) path else join(baseUrl, path), sha256 = sha)
    }

    /**
     * 拆分订阅（schema 2）里的一份资源：`meta` / `artistImage`。
     *
     * 既认对象也认字符串；`url` / `file` / `path` 三种写法都行，
     * sha256 用来下载后校验，缺省时只是不校验（不推荐）。
     */
    data class Source(
        val url: String = "",
        val sha256: String = "",
        val version: String = "",
        val size: Long = 0L,
        /** `meta` 里公布的上游插件 id，用来判断"是不是都装上了"。 */
        val pluginIds: List<String> = emptyList()
    )

    fun meta(root: JSONObject, baseUrl: String): Source? = source(root, "meta", baseUrl)

    fun artistImage(root: JSONObject, baseUrl: String): Source? =
        source(root, "artistImage", baseUrl)

    private fun source(root: JSONObject, key: String, baseUrl: String): Source? {
        val obj = root.optJSONObject(key)
        val raw = root.opt(key)
        val path = when {
            obj != null -> obj.optString("url").ifBlank { obj.optString("file") }
                .ifBlank { obj.optString("path") }.trim()
            raw is String -> raw.trim()
            else -> ""
        }
        if (path.isEmpty()) return null
        val sha = obj?.optString("sha256").orEmpty().trim()
        val version = obj?.optString("version").orEmpty().trim()
        val size = obj?.optLong("size", 0L) ?: 0L
        val ids = buildList {
            val array = obj?.optJSONArray("plugins") ?: return@buildList
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        return Source(
            url = if (isHttp(path)) path else join(baseUrl, path),
            sha256 = sha,
            version = version,
            size = size,
            pluginIds = ids
        )
    }

    /**
     * 合集条目：和 [rules] 同一套地址解析规则。
     *
     * `bundle` 可以写成字符串（只有路径，最省事）或对象：
     * ```json
     * "bundle": "bundle.zip"
     * "bundle": { "file": "bundle.zip", "sha256": "…" }
     * "bundle": { "url": "https://…/bundle.zip", "sha256": "…" }
     * ```
     * **没写就返回 null**，调用方直接告诉用户"这个订阅源没有提供合集包"。
     */
    fun bundle(root: JSONObject, baseUrl: String): Bundle? {
        val obj = root.optJSONObject("bundle")
        val raw = root.opt("bundle")
        val path = when {
            obj != null -> obj.optString("file").ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("path") }.trim()
            raw is String -> raw.trim()
            else -> ""
        }
        if (path.isEmpty()) return null
        val sha = obj?.optString("sha256").orEmpty().trim()
        // 包内那份规则：字符串（`"rules": "rules/tag_rules.json"`）和对象
        //（`"rules": {"file": "…", "sha256": "…"}`）两种写法都认。校验值不再需要了
        //（整包的 sha256 才是判据），这里只取路径。
        val rulesObj = obj?.optJSONObject("rules")
        val rulesRaw = obj?.opt("rules")
        val rulesPath = when {
            rulesObj != null -> rulesObj.optString("file").ifBlank { rulesObj.optString("url") }
                .ifBlank { rulesObj.optString("path") }.trim()
            rulesRaw is String -> rulesRaw.trim()
            else -> ""
        }
        /*
         * 包内规则的内容校验值。
         *
         * 三种写法都认：`bundle.rulesSha256`（本仓库现在的写法）、
         * `bundle.rules.sha256`（把 rules 写成对象时）、`bundle.rulesSha256` 缺省时为空串 ——
         * 空串表示"仓库没公布"，调用方退回用顶层 `rules.sha256`。
         */
        val rulesSha = rulesObj?.optString("sha256").orEmpty()
            .ifBlank { obj?.optString("rulesSha256").orEmpty() }
            .trim()
        return Bundle(
            url = if (isHttp(path)) path else join(baseUrl, path),
            sha256 = sha,
            md5 = obj?.optString("md5").orEmpty().trim(),
            rules = rulesPath,
            rulesSha256 = rulesSha
        )
    }

    private fun join(baseUrl: String, path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun isHttp(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)
}
