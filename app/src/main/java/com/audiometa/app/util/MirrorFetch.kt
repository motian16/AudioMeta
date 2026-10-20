package com.audiometa.app.util

import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * 带镜像回退的下载入口。**应用里所有"从网上拿东西"都从这里走。**
 *
 * [RemoteText] 本身不认识设置 —— 它只负责"给一个地址，列出候选，挨个试"。
 * 镜像表和"上次成功的镜像"存在 DataStore 里，这一层把它们接起来：
 *
 * 1. 把用户配的前缀和上次生效的镜像交给 [RemoteText]（上次那个排最前：
 *    国内直连一个被墙的域名要等满 8 秒超时，能省掉就省掉）；
 * 2. 成功后把生效的镜像与地址记回去，供「设置 - 插件 - 网络与镜像」显示。
 *
 * 下载哪些东西、要不要校验 sha256 由调用方决定：
 * 订阅更新只下两个文件 —— index.json 和它 `bundle.url` 指向的订阅包
 *（见 [com.audiometa.app.plugin.install.PluginRepoUpdater]）。
 */
object MirrorFetch {

    /**
     * 下载一段内容（文本或 zip 都走它）。[sha256] 非空时逐个候选校验。
     *
     * @param attemptTimeoutMs 单个候选的最长等待。**插件包要传大一点**：
     *   元信息源的包有几百 KB，用默认的 8 秒在中转站上经常还没传完就判超时，
     *   报出来只是"超时"，看着像被墙，其实是时间不够。
     * @param budgetMs 这一轮的总预算（默认 30 秒，适合几十 KB 的文本）。
     */
    suspend fun download(
        address: String,
        sha256: String? = null,
        attemptTimeoutMs: Int = RemoteText.DEFAULT_ATTEMPT_TIMEOUT_MS,
        budgetMs: Long = RemoteText.DEFAULT_TOTAL_BUDGET_MS
    ): RemoteText.Download {
        val settings = ServiceLocator.settings.settings.first()
        val result = RemoteText.download(
            address,
            RemoteText.Options(
                proxies = settings.mirrorPrefixes,
                preferred = settings.preferredMirror,
                sha256 = sha256,
                attemptTimeoutMs = attemptTimeoutMs,
                budgetMs = budgetMs
            )
        )
        remember(settings, address, result)
        return result
    }

    /**
     * 「测试连接」：把每个候选都试一遍并**如实回报**。
     *
     * 和真实下载用的是同一套候选与判据，所以这里显示"能通"的那条，
     * 就是待会儿真正会用到的那条（唯一的区别是这里不因为成功就提前停下）。
     */
    suspend fun test(
        address: String,
        sha256: String? = null,
        onAttempt: (RemoteText.Attempt) -> Unit = {}
    ): List<RemoteText.Attempt> {
        val settings = ServiceLocator.settings.settings.first()
        return RemoteText.attempts(
            address,
            RemoteText.testOptions(settings.mirrorPrefixes, settings.preferredMirror, sha256),
            onAttempt = onAttempt
        )
    }

    /**
     * 同一个地址**多拿几份**（每个候选各一份，最多 [maxSuccess] 份），交给调用方挑最新的那份。
     *
     * 为什么不能只信"第一个成功的"：镜像之间缓存不一致 —— jsDelivr 会把分支名
     * （`@main`）长期钉在旧提交上，同一个 `index.json` 在 cdn 上是几个月前那版、
     * 在 testingcf 上却是当前版本。index.json 与规则文件都走这里，
     * 由调用方按 `updatedAt` / `rulesVersion` 挑，而不是谁先回话就用谁。
     */
    suspend fun downloadAll(
        address: String,
        attemptTimeoutMs: Int = RemoteText.DEFAULT_ATTEMPT_TIMEOUT_MS,
        budgetMs: Long = RemoteText.DEFAULT_TOTAL_BUDGET_MS,
        maxSuccess: Int = 5
    ): List<RemoteText.FetchedContent> {
        val settings = ServiceLocator.settings.settings.first()
        val list = RemoteText.downloads(
            address,
            RemoteText.Options(
                proxies = settings.mirrorPrefixes,
                preferred = settings.preferredMirror,
                attemptTimeoutMs = attemptTimeoutMs,
                budgetMs = budgetMs
            ),
            maxSuccess = maxSuccess
        )
        // 记住这次生效的镜像：下次先试它（多份里第一份成功的那个）。
        list.firstOrNull()?.let { first ->
            runCatching {
                remember(
                    settings,
                    address,
                    RemoteText.Download(first.bytes, first.candidate.url, first.candidate.mirror)
                )
            }
        }
        return list
    }

    private fun options(settings: AppSettings, sha256: String?) = RemoteText.Options(
        proxies = settings.mirrorPrefixes,
        preferred = settings.preferredMirror,
        sha256 = sha256
    )

    /** 记住这次生效的镜像。 */
    private suspend fun remember(
        settings: AppSettings,
        address: String,
        result: RemoteText.Download
    ) {
        if (!result.ok) return
        if (!address.trim().startsWith("http", true)) return
        if (result.mirror != settings.preferredMirror) {
            ServiceLocator.settings.setPreferredMirror(result.mirror)
        }
        if (result.usedUrl.isNotBlank() && result.usedUrl != settings.lastWorkingUrl) {
            ServiceLocator.settings.setLastWorkingUrl(result.usedUrl)
        }
    }
}
