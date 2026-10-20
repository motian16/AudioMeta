package com.audiometa.app.ui.screen.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.util.MirrorProbe
import com.audiometa.app.ui.screen.settings.SettingsViewModel.PluginListInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 读一遍已装插件 manifest 里的顺序、能力与图标名（文件 IO，放到 IO 线程）。 */
internal fun SettingsViewModel.loadPluginInfo(plugins: List<PluginRecordEntity>) {
    viewModelScope.launch {
        val info = HashMap<String, PluginListInfo>()
        withContext(Dispatchers.IO) {
            plugins.forEach { record ->
                val key = record.pluginId + "@" + record.pluginVersion
                info[record.pluginId] = pluginInfoCache[key] ?: run {
                    // manifest 读不出来（文件损坏、被手工删了）就按"什么都没声明"处理：
                    // 排到最后，两页清单里都不出现 —— 它本来也跑不起来。
                    val manifest = runCatching {
                        ServiceLocator.plugins
                            .readManifest(java.io.File(record.installPath))
                    }.getOrNull()
                    PluginListInfo(
                        order = manifest?.order
                            ?: com.audiometa.app.plugin.model.PluginManifest.DEFAULT_ORDER,
                        capabilities = manifest?.capabilities.orEmpty(),
                        icon = manifest?.icon.orEmpty().ifBlank { "icon.png" }
                    ).also { pluginInfoCache[key] = it }
                }
            }
        }
        pluginInfo = info
    }
}

/**
 * 启用 / 停用单个源（插件页那个开关移植到设置里的同一套行为）。
 *
 * 插件记录和"源记录"各有一份 enabled，必须一起写 —— 只改一半的话，
 * 刮削时按插件记录挑选、按源记录取配置，会出现"开关是开的却不出结果"。
 */
internal fun SettingsViewModel.setPluginEnabled(pluginId: String, enabled: Boolean) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                val locator = ServiceLocator
                locator.pluginRepo.setPluginEnabled(pluginId, enabled)
                locator.pluginRepo.setSourceEnabled(pluginId, pluginId, enabled)
            }.onFailure {
                repoStatus = "开关没保存成功：" + (it.message ?: "未知错误")
            }
        }
    }
}

/** 删除单个源：删安装目录 + 插件记录（[PluginInstaller.uninstall]）。 */
internal fun SettingsViewModel.uninstallPlugin(pluginId: String) {
    viewModelScope.launch {
        val message = withContext(Dispatchers.IO) {
            runCatching { ServiceLocator.plugins.uninstall(pluginId) }
                .fold({ "已删除 " + pluginId }, { "删除失败：" + (it.message ?: "未知错误") })
        }
        repoStatus = message
        pendingUninstall = null
    }
}

internal fun SettingsViewModel.saveRepoUrl(url: String) {        val next = url.trim()
    editRepoDialog = false
    viewModelScope.launch {
        // 换了地址，之前记下的仓库名就作废了：留着会把上一个仓库的名字
        // 挂在新地址上，看起来像"订阅成功了但名字不对"。
        if (next != ServiceLocator.settings.settings.first().pluginRepoUrl) {
            ServiceLocator.settings.setPluginRepoName("")
        }
        ServiceLocator.settings.setPluginRepoUrl(next)
    }
}

/**
 * 手动安装：把文件选择器选中的 zip 交给 [PluginInstaller] 装上。
 *
 * 选文件这件事在界面层完成（先 MT 管理器、失败再系统选择器，见
 * 「手动安装」那一行），这里只负责拿到 Uri 之后的事：
 * 打开输入流（content:// 也好 file:// 也好都是一个读法），
 * 拷进临时目录安装，结果——装了几个、警告什么、为什么失败——写进 repoStatus。
 */
internal fun SettingsViewModel.importLocalPlugin(uri: android.net.Uri) {
    if (repoBusy) return
    repoBusy = true
    repoStatus = "正在导入…"
    viewModelScope.launch {
        val message = withContext(Dispatchers.IO) {
            runCatching {
                val stream = ServiceLocator.appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("打不开所选文件")
                stream.use { ServiceLocator.plugins.install(it) }
            }.fold(
                { report ->
                    if (report.manifests.isEmpty()) {
                        "没有安装任何插件：" + report.warnings.joinToString("；").ifBlank { "包里没有可识别的插件" }
                    } else {
                        "已安装 " + report.manifests.size + " 个插件" +
                            (report.warnings.takeIf { it.isNotEmpty() }
                                ?.joinToString("；", prefix = "，注意：").orEmpty())
                    }
                },
                { "导入失败：" + (it.message ?: "未知错误") }
            )
        }
        repoStatus = message
        repoBusy = false
    }
}

/**
 * 检查更新：拉 index.json，比较版本号，有新版才下载安装。
 */
internal fun SettingsViewModel.checkRepoUpdates(context: android.content.Context) {
    if (repoUrl.isBlank() || repoBusy) return
    repoBusy = true
    repoStatus = "正在检查更新…"
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            com.audiometa.app.plugin.install.PluginRepoUpdater.run(
                context = context,
                indexUrl = repoUrl,
                onProgress = { repoStatus = it }
            )
        }
        repoStatus = result
        repoBusy = false
    }
}

// ------------------------------------------------------------------
// 网络与镜像（设置 - 插件，在「手动安装」下面）
// ------------------------------------------------------------------

/** 镜像表：空行是旧版的"直连"占位，不显示也不测（直连本来就第一个试）。 */
private fun SettingsViewModel.mirrorList(settings: AppSettings): List<String> =
    settings.mirrorPrefixes.map { it.trim() }.filter { it.isNotBlank() }

/**
 * 加一个中转前缀。
 *
 * 只填域名（`gh-proxy.com`）也认：补上 `https://` 和结尾的斜杠 ——
 * 这两步和 [RemoteText] 内部的归一化是同一种写法。
 */
internal fun SettingsViewModel.addMirror(value: String) {
    val raw = value.trim()
    if (raw.isEmpty()) return
    val withScheme =
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
        else "https://" + raw
    val normalized = if (withScheme.endsWith("/")) withScheme else withScheme + "/"
    val current = mirrorList(state.value.settings)
    if (normalized in current) return
    edit { ServiceLocator.settings.setMirrorPrefixes(current + normalized) }
}

/** 删掉一个中转前缀；删的正好是"优先用的"那个就一并忘掉。 */
internal fun SettingsViewModel.removeMirror(prefix: String) {
    val current = mirrorList(state.value.settings)
    if (prefix !in current) return
    val preferred = state.value.settings.preferredMirror.trim()
    // 连同这一行这轮的测试结果一起丢掉：否则删掉正在测的那个再把它加回来，
    // 行里会一直停在「测试中…」。
    _mirrorTestResults.value = _mirrorTestResults.value - prefix
    edit {
        ServiceLocator.settings.setMirrorPrefixes(current - prefix)
        if (preferred.isNotBlank() && preferred.trimEnd('/') == prefix.trimEnd('/')) {
            ServiceLocator.settings.setPreferredMirror("")
        }
    }
}

/** 指定优先用的镜像：以后的下载先试它。 */
internal fun SettingsViewModel.preferMirror(prefix: String) {
    val normalized = if (prefix.endsWith("/")) prefix else prefix + "/"
    edit { ServiceLocator.settings.setPreferredMirror(normalized) }
}

/** 恢复默认镜像表（六个内置中转）。 */
internal fun SettingsViewModel.resetMirrorPrefixes() = edit {
    ServiceLocator.settings.setMirrorPrefixes(com.audiometa.app.data.prefs.SettingsStore.DEFAULT_MIRROR_PREFIXES)
}

/**
 * 「测试」：表里的镜像**同时**发出去，各自计时、各自 6 秒超时。
 *
 * 并发而不是一条一条来：一轮的等待时间从"六个加起来"变成"最慢的那条"
 * （最多 6 秒），用户点完几乎立刻能看到全部结果。谁先回来谁先显示
 * （每测完一条就更新一次结果），不用等齐了再一起冒出来。
 *
 * 整个循环跑在 [viewModelScope] 上，不是界面的协程：离开「设置 - 插件」
 * 这一页、甚至切到别的标签，测试都照常跑完 —— 回来时看到的还是进行中的状态，
 * 结果也不会丢（所以这里**没有** DisposableEffect 去取消它）。
 *
 * 全部测完之后按耗时从快到慢重排，超时的沉到最后（一样快慢的保持原顺序），
 * 新顺序写回设置，下次下载就按它试。写回之后列表顺序就变了，
 * 界面上那几行会自己滑到新位置。
 */
internal fun SettingsViewModel.testMirrors() {
    if (_mirrorTestRunning.value) return
    val settings = state.value.settings
    val mirrors = mirrorList(settings)
    if (mirrors.isEmpty()) return
    // 测的就是下载真正要用的那个地址：订阅的 index.json。
    // 还没订阅时 MirrorProbe 只探前缀本身（见 probeUrl），一样能比出快慢。
    val target = settings.pluginRepoUrl.trim()
    _mirrorTestRunning.value = true
    _mirrorTestResults.value = mirrors.associateWith { MirrorTestResult(pending = true) }
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                mirrors.forEach { mirror ->
                    launch {
                        val millis = MirrorProbe.latency(mirror, target)
                        // 六条同时回来，这里必须是原子的读-改-写，否则会丢结果。
                        _mirrorTestResults.update {
                            it + (mirror to MirrorTestResult(millis = millis))
                        }
                    }
                }
            }
            // 以结果表重排（它就是这一轮的真实成绩），并以库里**当前**的表为准：
            // 测试期间用户加过、删过的项都算数；没测过的新项排在后面，
            // 并且保持它们原来的相对顺序。
            val done = _mirrorTestResults.value
            val current = mirrorList(ServiceLocator.settings.settings.first())
            val sorted = current.sortedWith(
                compareBy({ done[it]?.millis == null }, { done[it]?.millis ?: 0L })
            )
            if (sorted != current) ServiceLocator.settings.setMirrorPrefixes(sorted)
        } finally {
            _mirrorTestRunning.value = false
        }
    }
}

/**
 * 「网络与镜像」里一个镜像这一轮的状态。
 *
 * @param pending 正在测它（行里显示「测试中…」）。
 * @param millis 往返耗时（毫秒）；null 表示超时或者连不上。
 */
data class MirrorTestResult(
    val pending: Boolean = false,
    val millis: Long? = null
)
