package com.audiometa.app.ui.screen.plugin

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.data.db.SourceRecordEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.plugin.install.PluginExportException
import com.audiometa.app.plugin.install.PluginInstallException
import com.audiometa.app.plugin.model.ConfigField
import com.audiometa.app.plugin.model.PluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class PluginConfigEditor(
    val pluginId: String,
    val name: String,
    val fields: List<ConfigField>,
    val values: Map<String, String>,
    /** 校验失败时显示在对话框里的提示。 */
    val error: String = ""
)

/**
 * 需要用户确认的一条结果。
 *
 * 插件页不再在顶部堆提示小字，导入/卸载/导出的结论改成一个可关闭的对话框，
 * 只有真正需要用户知道的事情（装了哪个版本、哪里失败）才会出现。
 */
data class PluginNotice(
    val title: String,
    val detail: String,
    val error: Boolean = false
)

data class PluginUiState(
    val plugins: List<PluginRecordEntity> = emptyList(),
    val sources: List<SourceRecordEntity> = emptyList(),
    val busy: Boolean = false,
    val notice: PluginNotice? = null,
    val editing: PluginConfigEditor? = null,
    val settings: AppSettings = AppSettings()
) {
    /**
     * 底栏「插件」页只列**元信息源**（用户要求）：声明了 `searchSongs` 的插件。
     *
     * 艺术家图源只提供图片，混在同一张表里既不好排优先级也没必要管 ——
     * 它们在「设置 - 插件 - 插件列表 - 艺术家图源」里单独一页。
     *
     * 判据用安装时写进库的能力表（[SourceRecordEntity.capabilitiesJson]），
     * 不重新去读每个插件的 manifest；读不出来的（老记录、损坏）当"能力未知"保留，
     * 免得插件从列表里凭空消失。
     */
    val metadataPlugins: List<PluginRecordEntity>
        get() = plugins.filter { plugin ->
            val caps = sources.firstOrNull { it.pluginId == plugin.pluginId }
                ?.declaredCapabilities()
                .orEmpty()
            caps.isEmpty() || ScrapeCapability.TRACK_META in caps
        }

    /**
     * 插件顺序：手动拖动过的在前（按拖动顺序），其余按插件包声明的 order 排，
     * 数字小的靠前；都没声明的按名字排。
     *
     * 这样导入一个插件包，列表立刻就是包内约定的顺序（QQ / 网易 / Apple Music /
     * 酷狗在前，汽水最后），不需要用户自己拖。
     */
    val orderedPlugins: List<PluginRecordEntity>
        get() = orderedPluginsFor(ScrapeCapability.TRACK_META)

    /**
     * 按能力筛选后的插件顺序。
     *
     * 显示顺序只影响列表，不影响刮削优先级（优先级来自标签规则里的 `sourcePriority`）。
     * 没有能力记录的老插件按"未知能力"保留，避免凭空消失。
     */
    fun orderedPluginsFor(capability: ScrapeCapability?): List<PluginRecordEntity> {
        val shown = plugins.filter { plugin ->
            if (capability == null) return@filter true
            val caps = (sources.firstOrNull { it.pluginId == plugin.pluginId }
                ?.declaredCapabilities()).orEmpty()
            caps.isEmpty() || capability in caps
        }
        val installed = shown.map { it.pluginId }.toSet()
        val explicit = settings.pluginOrder.filter { it in installed }
        val ranked = explicit.mapNotNull { id -> shown.firstOrNull { it.pluginId == id } }
        val rest = shown
            .filter { it.pluginId !in explicit }
            .sortedWith(
                compareBy(
                    { settings.pluginDefaultOrder[it.pluginId] ?: PluginManifest.DEFAULT_ORDER },
                    { it.pluginName.lowercase() }
                )
            )
        return ranked + rest
    }
}

class PluginViewModel(app: Application) : AndroidViewModel(app) {
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<PluginNotice?>(null)
    private val editing = MutableStateFlow<PluginConfigEditor?>(null)

    val state = combine(
        ServiceLocator.pluginRepo.observePlugins(),
        ServiceLocator.pluginRepo.observeSources(),
        busy,
        notice,
        editing,
        ServiceLocator.settings.settings
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val plugins = values[0] as List<PluginRecordEntity>
        @Suppress("UNCHECKED_CAST")
        val sources = values[1] as List<SourceRecordEntity>
        PluginUiState(
            plugins = plugins.sortedWith(compareBy({ it.pluginName.lowercase() }, { it.pluginVersion })),
            sources = sources,
            busy = values[2] as Boolean,
            notice = values[3] as PluginNotice?,
            editing = values[4] as PluginConfigEditor?,
            settings = values[5] as AppSettings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PluginUiState())

    fun importZip(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        ServiceLocator.plugins.install(input)
                    } ?: throw PluginInstallException("无法读取所选文件")
                }
            }
            busy.value = false
            result.fold(
                onSuccess = { report -> notice.value = installNotice(report) },
                onFailure = {
                    notice.value = PluginNotice(
                        title = "安装失败",
                        detail = it.toUserMessage("安装失败"),
                        error = true
                    )
                }
            )
        }
    }

    /**
     * 安装/升级结果。
     *
     * 插件唯一性：同版本覆盖安装，更高版本升级，更低版本跳过。
     * 三种情况都要明确告诉用户，否则「装了没反应」和「装坏了」分不清。
     */
    private fun installNotice(report: com.audiometa.app.plugin.install.InstallReport): PluginNotice {
        if (report.manifests.isEmpty()) {
            val skipped = report.skipped.joinToString("、")
            return if (skipped.isNotBlank()) {
                PluginNotice(
                    title = "已跳过",
                    detail = skipped + "：本机已是相同或更高版本，无需重复安装。"
                )
            } else {
                PluginNotice(
                    title = "没有可安装的插件",
                    detail = report.warnings.joinToString("；").ifBlank { "插件包里没有可安装的内容。" },
                    error = report.warnings.isNotEmpty()
                )
            }
        }
        val lines = report.manifests.map { manifest ->
            val name = manifest.localized(locale()).name + " " + manifest.versionName
            when (manifest.id) {
                in report.upgraded -> name + "（升级）"
                in report.overwritten -> name + "（同版本覆盖安装）"
                else -> name + "（新装）"
            }
        }.toMutableList()
        if (report.skipped.isNotEmpty()) {
            lines += "已跳过：" + report.skipped.joinToString("、") + "（本机版本更高）"
        }
        if (report.warnings.isNotEmpty()) {
            lines += "警告：" + report.warnings.joinToString("；")
        }
        return PluginNotice(
            title = "已安装 " + report.manifests.size + " 个插件",
            detail = lines.joinToString("\n"),
            error = report.warnings.isNotEmpty()
        )
    }

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.plugins.uninstall(pluginId) }
            }
            busy.value = false
            result.fold(
                onSuccess = {
                    notice.value = PluginNotice(title = "已卸载", detail = pluginId)
                },
                onFailure = {
                    notice.value = PluginNotice(
                        title = "卸载失败",
                        detail = it.toUserMessage("卸载失败"),
                        error = true
                    )
                }
            )
        }
    }

    fun export(pluginId: String, destination: Uri) {
        viewModelScope.launch {
            busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { ServiceLocator.exporter.export(pluginId, destination) }
            }
            busy.value = false
            result.fold(
                onSuccess = { notice.value = PluginNotice(title = "已导出", detail = it) },
                onFailure = {
                    notice.value = PluginNotice(
                        title = "导出失败",
                        detail = it.toUserMessage("导出失败"),
                        error = true
                    )
                }
            )
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            ServiceLocator.pluginRepo.setPluginEnabled(pluginId, enabled)
            ServiceLocator.pluginRepo.setSourceEnabled(pluginId, pluginId, enabled)
        }
    }

    /** 拖动结束后保存顺序。顺序在「指定刮削」时决定先用哪个源。 */
    fun setPluginOrder(ids: List<String>) {
        viewModelScope.launch { ServiceLocator.settings.setPluginOrder(ids) }
    }

    /**
     * 当前顺序：显式排过的在前，其余按安装顺序接上。
     *
     * 拖动排序由 [setPluginOrder] 直接接收整份新顺序，
     * 不再需要"移动一项"的接口。
     */
    fun orderedPluginIds(): List<String> = state.value.orderedPlugins.map { it.pluginId }

    fun openOptions(pluginId: String) {
        viewModelScope.launch {
            val plugin = withContext(Dispatchers.IO) { ServiceLocator.plugins.load(pluginId) }
            if (plugin == null) {
                notice.value = PluginNotice(
                    title = "插件不可用",
                    detail = "插件文件缺失，可以重新导入。",
                    error = true
                )
                return@launch
            }
            val localized = plugin.manifest.localized(locale())
            val saved = runCatching { JSONObject(plugin.configJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val values = localized.configFields
                .filterNot { it.isDisplayOnly }
                .associate { field ->
                    val stored = if (saved.has(field.key)) saved.opt(field.key)?.toString().orEmpty() else ""
                    field.key to stored.ifBlank { field.defaultValue }
                }
            editing.value = PluginConfigEditor(
                pluginId = pluginId,
                name = localized.name,
                fields = localized.configFields,
                values = values
            )
        }
    }

    fun updateOption(key: String, value: String) {
        val current = editing.value ?: return
        editing.value = current.copy(values = current.values + (key to value), error = "")
    }

    fun saveOptions() {
        val editor = editing.value ?: return
        // 只有「当前可见」的必填项为空才算缺失。
        // 之前的条件写反了，导致被 dependency 隐藏的必填项（例如 Apple 的
        // media_user_token）永远判定为缺失，点保存关不掉窗口。
        val missing = editor.fields.filterNot { it.isDisplayOnly }.filter { field ->
            field.required && isVisible(field, editor.values) && editor.values[field.key].isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            editing.value = editor.copy(error = "请填写必填项：" + missing.joinToString("、") { it.title })
            return
        }
        viewModelScope.launch {
            val json = JSONObject()
            editor.values.forEach { (key, value) -> json.put(key, value) }
            ServiceLocator.pluginRepo.setConfig(editor.pluginId, editor.pluginId, json.toString())
            editing.value = null
        }
    }

    fun closeOptions() {
        editing.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun locale(): String = Locale.getDefault().toLanguageTag().ifBlank { "en" }
}

/** 依赖条件不满足的字段不显示，也不参与必填校验。 */
fun isVisible(field: ConfigField, values: Map<String, String>): Boolean {
    val dependency = field.dependency ?: return true
    return values[dependency.key] == dependency.value
}

private fun Throwable.toUserMessage(prefix: String): String {
    val detail = when (this) {
        is PluginInstallException, is PluginExportException -> message
        else -> message
    }.orEmpty().ifBlank { javaClass.simpleName }
    return prefix + "：" + detail
}

fun SourceRecordEntity.declaredCapabilities(): List<ScrapeCapability> = runCatching {
    val json = JSONObject(capabilitiesJson.ifBlank { "{}" })
    ScrapeCapability.entries.filter { json.optBoolean(it.key, false) }
}.getOrDefault(emptyList())
