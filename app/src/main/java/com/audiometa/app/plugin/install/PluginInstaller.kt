package com.audiometa.app.plugin.install

import android.content.Context
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.db.SourceRecordEntity
import com.audiometa.app.data.repo.PluginRepository
import com.audiometa.app.plugin.model.InstalledPlugin
import com.audiometa.app.plugin.model.PluginManifest
import com.audiometa.app.plugin.runtime.PluginRuntime
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class PluginInstallException(message: String) : Exception(message)

/** 版本比较结果。 */
enum class VersionDecision {
    /** 全新安装。 */
    NEW,

    /** 已存在同版本：覆盖安装（修好损坏的文件，重装不会失败）。 */
    OVERWRITE,

    /** 已存在更高版本：跳过，避免用旧包把新包顶回去。 */
    DOWNGRADE_SKIPPED
}

data class InstallReport(
    val manifests: List<PluginManifest>,
    val warnings: List<String>,
    /** 本次是升级安装的插件 id。 */
    val upgraded: Set<String> = emptySet(),
    /** 本次是覆盖安装（同版本）的插件 id。 */
    val overwritten: Set<String> = emptySet(),
    /** 因为已装更高版本而跳过的插件 id。 */
    val skipped: Set<String> = emptySet()
)

/**
 * 安装 Lyrico 格式的插件包。
 *
 * 一个 zip 里可以放多个插件，每个插件是包含 manifest.json 的一个目录；
 * 也支持 manifest.json 直接位于包根目录的单个插件。
 */
class PluginInstaller(
    private val context: Context,
    private val repository: PluginRepository,
    private val runtime: PluginRuntime,
    /** 用来记录插件包声明的默认排序。 */
    private val settings: com.audiometa.app.data.prefs.SettingsStore
) {
    /**
     * 插件源码目录：`Android/data/<包名>/files/插件/`。
     *
     * 放外部目录而不是私有目录 `files/`：私有目录用户完全访问不到，
     * 出问题时看不到里面有什么；插件是纯文本 JS，放在外部目录
     * 用户可以直接查看、手动删除。
     *
     * 注意这里**不主动 mkdirs** —— 没装插件时不该留下一个空的"插件"文件夹。
     * 真正的创建发生在 [installFiles] 写文件时。
     */
    private val rootDir: File
        get() = com.audiometa.app.util.StoragePaths.pluginsRoot(context)

    suspend fun install(zipFile: File): InstallReport {
        if (!zipFile.exists() || zipFile.length() == 0L) {
            throw PluginInstallException("插件包不存在或为空")
        }
        return ZipFile(zipFile).use { zip -> installZip(zip) }
    }

    suspend fun install(stream: InputStream): InstallReport {
        // 临时 zip 也放到外部目录的「临时」下，和插件本体在同一个可见位置。
        val temp = File(
            com.audiometa.app.util.StoragePaths.ensuredDir(context, StoragePaths.DIR_TEMP),
            "插件导入-" + System.currentTimeMillis() + ".zip"
        )
        try {
            stream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            return install(temp)
        } finally {
            temp.delete()
        }
    }

    private suspend fun installZip(zip: ZipFile): InstallReport {
        val entries = zip.entries().toList()
        if (entries.any { isUnsafe(it.name) }) {
            throw PluginInstallException("插件包包含非法路径")
        }
        val manifestEntries = entries.filter {
            !it.isDirectory && it.name.substringAfterLast('/').equals("manifest.json", true)
        }
        if (manifestEntries.isEmpty()) {
            throw PluginInstallException("插件包里没有找到 manifest.json")
        }

        val installed = mutableListOf<PluginManifest>()
        val warnings = mutableListOf<String>()
        val upgraded = mutableSetOf<String>()
        val overwritten = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val failures = mutableListOf<String>()

        for (manifestEntry in manifestEntries) {
            val prefix = manifestEntry.name.substringBeforeLast('/', "")
            val dirName = prefix.substringAfterLast('/').ifBlank { "plugin" }
            val raw = zip.getInputStream(manifestEntry).reader(Charsets.UTF_8).readText()
            val localeTexts = readLocales(zip, entries, prefix, raw)
            // 单个插件出问题不拖垮整包：剩下的照样装，失败的最后一起报。
            val parsed = runCatching { PluginManifest.parse(raw, dirName, localeTexts) }
            val manifest = parsed.getOrNull()
            if (manifest == null) {
                val error = parsed.exceptionOrNull()
                failures += dirName + "：" + (error?.message ?: "manifest 解析失败")
                continue
            }
            // 唯一性：同版本覆盖安装，更高版本升级，更低版本跳过。
            val decision = decideVersion(manifest)
            if (decision == VersionDecision.DOWNGRADE_SKIPPED) {
                skipped += manifest.id
                continue
            }
            if (decision == VersionDecision.OVERWRITE) {
                overwritten += manifest.id
            } else if (repository.getPlugin(manifest.id) != null) {
                upgraded += manifest.id
            }
            val written = runCatching { installFiles(zip, entries, prefix, manifest.id) }
            val target = written.getOrNull()
            if (target == null) {
                val error = written.exceptionOrNull()
                failures += manifest.localized(deviceLocale()).name + "：" +
                    (error?.message ?: "写入文件失败")
                continue
            }
            register(target, manifest)
            installed += manifest
            warnings += validateInstalled(target, manifest)
        }

        if (installed.isEmpty() && failures.isNotEmpty()) {
            throw PluginInstallException(failures.joinToString("；"))
        }
        return InstallReport(installed, warnings + failures, upgraded, overwritten, skipped)
    }

    /**
     * 版本决策。
     *
     * versionName 是主依据；数据库里存的就是它，也是用户在插件页看到的那串。
     */
    private suspend fun decideVersion(incoming: PluginManifest): VersionDecision {
        val existing = repository.getPlugin(incoming.id) ?: return VersionDecision.NEW
        val compare = compareVersions(existing.pluginVersion, incoming.versionName)
        return when {
            compare < 0 -> VersionDecision.NEW
            compare == 0 -> VersionDecision.OVERWRITE
            else -> VersionDecision.DOWNGRADE_SKIPPED
        }
    }

    private fun installFiles(zip: ZipFile, entries: List<ZipEntry>, prefix: String, id: String): File {
        // 到这里才真正创建目录（上面 rootDir 不 mkdirs）。
        val root = rootDir.apply { if (!exists()) mkdirs() }
        val target = File(root, id)
        val staging = File(root, ".暂存-" + id + "-" + System.currentTimeMillis())
        staging.mkdirs()
        try {
            entries
                .filter { !it.isDirectory && inPrefix(it.name, prefix) }
                .forEach { entry ->
                    val relative = relativeName(entry.name, prefix)
                    if (relative.isBlank()) return@forEach
                    val out = File(staging, relative)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw PluginInstallException("写入插件文件失败（" + id + "）：" + e.message)
        }
        return target
    }

    private suspend fun register(dir: File, manifest: PluginManifest) {
        val localized = manifest.localized(deviceLocale())
        val previous = repository.getSource(manifest.id, manifest.id)
        repository.savePlugin(
            PluginRecordEntity(
                pluginId = manifest.id,
                pluginName = localized.name,
                pluginVersion = manifest.versionName,
                installPath = dir.absolutePath,
                enabled = previous?.enabled ?: defaultEnabledFor(manifest),
                schemaVersion = manifest.apiVersion
            )
        )
        val caps = JSONObject()
        manifest.scrapeCapabilities().forEach { caps.put(it.key, true) }
        val sourceRecord = SourceRecordEntity(
            sourceId = manifest.id,
            pluginId = manifest.id,
            displayName = localized.name,
            iconPath = File(dir, manifest.icon).absolutePath,
            enabled = previous?.enabled ?: defaultEnabledFor(manifest),
            // priority 用我们指定的顺序：QQ → 网易 → Apple Music → 酷我。
            // 插件包自己声明的 order 不参与，避免以后换了包顺序就乱。
            priority = preferredOrder(manifest.id),
            apiVersion = manifest.apiVersion,
            capabilitiesJson = caps.toString(),
            // 安装时把默认值写进去，否则用户没打开过配置页时插件拿到的是空配置。
            configJson = previous?.configJson ?: defaultConfigJson(localized),
            lastUsedAt = previous?.lastUsedAt ?: 0L,
            failStreak = previous?.failStreak ?: 0
        )
        repository.saveSource(sourceRecord)
        // 默认排序记进设置，插件列表按它排；同时把这一步的插件从「手动顺序」里摘掉，
        // 之后用户自己拖动才会重新写入手动顺序。
        settings.applyPluginDefaultOrder(mapOf(manifest.id to preferredOrder(manifest.id)))
    }

    private fun defaultConfigJson(manifest: PluginManifest): String {        val json = JSONObject()
        manifest.configFields
            .filterNot { it.isDisplayOnly }
            .forEach { field ->
                if (field.defaultValue.isNotBlank()) json.put(field.key, field.defaultValue)
            }
        return json.toString()
    }

    private fun validateInstalled(dir: File, manifest: PluginManifest): List<String> {
        val plugin = buildPlugin(dir, manifest, "{}") ?: return listOf("插件文件不完整")
        return runCatching { runtime.validate(plugin, deviceLocale()) }
            .getOrElse { listOf("加载检查失败：" + (it.message ?: it.javaClass.simpleName)) }
            .onEach { AppLog.w(TAG, manifest.id + "：" + it) }
    }

    /**
     * 源在我们的固定排序：QQ → 网易 → Apple Music → 酷我。
     *
     * 不读插件包声明的 order：那是由包决定的，换了包顺序就会变，
     * 而"元信息组合搜索按这个顺序取每项内容最多的源"依赖它稳定。
     */
    private fun preferredOrder(pluginId: String): Int = PREFERRED_ORDER[pluginId] ?: 1000

    /**
     * 导入后默认是否启用。
     *
     * 插件包在 manifest 里声明了 `defaultEnabled` 就按它说的算 —— 这样以后从仓库
     * 装的新插件（比如只提供艺术家图片的那几个）可以自己声明"装完就开"，
     * 不必每加一个源就回来改这里的名单。
     *
     * 没声明（null）才回落到 [DEFAULT_ENABLED_IDS]：上游那几个老包都没这个字段，
     * 走白名单能保证它们的默认行为一个字都不变。
     */
    private fun defaultEnabledFor(manifest: PluginManifest): Boolean =
        manifest.defaultEnabled ?: (manifest.id in DEFAULT_ENABLED_IDS)

    suspend fun uninstall(pluginId: String) {
        val record = repository.getPlugin(pluginId)
        repository.deletePlugin(pluginId)
        val dir = record?.installPath?.let { File(it) } ?: File(rootDir, pluginId)
        if (dir.exists()) dir.deleteRecursively()
        // 卸载完如果"插件"目录空了就删掉它 —— 不留空文件夹。
        val root = rootDir
        if (root.isDirectory) {
            val remaining = root.listFiles()
            if (remaining != null && remaining.isEmpty()) runCatching { root.delete() }
        }
    }

    suspend fun loadEnabled(): List<InstalledPlugin> {
        val plugins = repository.plugins().filter { it.enabled }
        return plugins.mapNotNull { record ->
            /*
             * 插件记录和"源记录"各有一个 enabled 标记（插件页的开关会一起写），
             * 但历史数据、半途失败的写入都可能让两者不一致。以前只要源记录是关的
             * 就整个跳过 —— 用户看到的正是"我明明都启用了，歌词却搜不到、
             * 某个源就是不出结果"。现在**以插件记录为准，并顺手把源记录修好**，
             * 不让一份不一致的旧数据把源静默吞掉。
             */
            val source = repository.getSource(record.pluginId, record.pluginId)
            if (source != null && !source.enabled) {
                AppLog.w(
                    TAG,
                    "源 " + record.pluginId + " 的启用标记和插件不一致（插件开、源关），按插件的为准并修复"
                )
                runCatching { repository.saveSource(source.copy(enabled = true)) }
                    .onFailure { AppLog.w(TAG, "修复源启用标记失败：" + it.message) }
            }
            load(record.pluginId)
        }
    }

    suspend fun load(pluginId: String): InstalledPlugin? {
        val record = repository.getPlugin(pluginId) ?: return null
        val dir = File(record.installPath)
        if (!dir.isDirectory) return null
        val manifest = readManifest(dir) ?: return null
        val config = repository.getSource(pluginId, pluginId)?.configJson ?: "{}"
        return buildPlugin(dir, manifest, config)
    }

    fun readManifest(dir: File): PluginManifest? {
        val file = File(dir, "manifest.json")
        if (!file.isFile) return null
        val raw = file.readText()
        val localeTexts = readLocalesFromDisk(dir, raw)
        return runCatching { PluginManifest.parse(raw, dir.name, localeTexts) }.getOrNull()
    }

    private fun buildPlugin(dir: File, manifest: PluginManifest, configJson: String): InstalledPlugin? {
        val sources = collectSources(dir, manifest)
        if (sources.isEmpty()) return null
        return InstalledPlugin(
            pluginId = manifest.id,
            installDir = dir.absolutePath,
            manifest = manifest,
            sources = sources,
            iconPath = File(dir, manifest.icon).absolutePath,
            configJson = configJson
        )
    }

    /** 先加载 includeDirs 里的模块（按文件名排序），最后加载入口。 */
    private fun collectSources(dir: File, manifest: PluginManifest): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        manifest.includeDirs.forEach { include ->
            val moduleDir = File(dir, include)
            if (!moduleDir.isDirectory) return@forEach
            moduleDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".js", true) }
                ?.sortedBy { it.name.lowercase(Locale.US) }
                ?.forEach { file ->
                    runCatching { list += (include + "/" + file.name) to file.readText() }
                }
        }
        val entryFile = File(dir, manifest.entry)
        if (!entryFile.isFile) return emptyList()
        list += manifest.entry to entryFile.readText()
        return list
    }

    private fun readLocales(
        zip: ZipFile,
        entries: List<ZipEntry>,
        prefix: String,
        rawManifest: String
    ): Map<String, Map<String, String>> {
        val resources = localeResources(rawManifest) ?: return emptyMap()
        val out = mutableMapOf<String, Map<String, String>>()
        resources.forEach { (locale, path) ->
            val name = joinPrefix(prefix, path)
            val entry = entries.firstOrNull { !it.isDirectory && it.name == name } ?: return@forEach
            val text = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
            parseLocale(text)?.let { out[locale] = it }
        }
        return out
    }

    private fun readLocalesFromDisk(dir: File, rawManifest: String): Map<String, Map<String, String>> {
        val resources = localeResources(rawManifest) ?: return emptyMap()
        val out = mutableMapOf<String, Map<String, String>>()
        resources.forEach { (locale, path) ->
            val file = File(dir, path)
            if (!file.isFile) return@forEach
            parseLocale(file.readText())?.let { out[locale] = it }
        }
        return out
    }

    private fun localeResources(rawManifest: String): Map<String, String>? = runCatching {
        val json = JSONObject(rawManifest)
        val resources = json.optJSONObject("i18n")?.optJSONObject("resources") ?: return null
        val out = mutableMapOf<String, String>()
        resources.keys().forEach { key -> out[key] = resources.optString(key) }
        out
    }.getOrNull()

    private fun parseLocale(text: String): Map<String, String>? = runCatching {
        val json = JSONObject(text)
        val out = mutableMapOf<String, String>()
        json.keys().forEach { key -> out[key] = json.optString(key) }
        out
    }.getOrNull()

    private fun deviceLocale(): String = Locale.getDefault().toLanguageTag().ifBlank { "en" }

    private fun joinPrefix(prefix: String, path: String): String =
        if (prefix.isBlank()) path else prefix + "/" + path

    private fun inPrefix(name: String, prefix: String): Boolean =
        prefix.isBlank() || name == prefix || name.startsWith(prefix + "/")

    private fun relativeName(name: String, prefix: String): String =
        if (prefix.isBlank()) name else name.removePrefix(prefix).trimStart('/')

    private fun isUnsafe(name: String): Boolean =
        name.contains("..") || name.startsWith("/") || name.contains('\\')

    companion object {
        private const val TAG = "PluginInstaller"

        /**
         * **没在 manifest 里声明 `defaultEnabled` 的**插件走这张名单：这四个源默认开，
         * 其它保持关闭，由用户自己开。声明过的插件不看这张表（见 [defaultEnabledFor]）。
         */
        private val DEFAULT_ENABLED_IDS = setOf(
            "com.qqmusic.source",
            "com.neteasecloudmusic.source",
            "com.applemusic.source",
            "com.kugou.source"
        )

        /**
         * 源在我们这边的固定排序：QQ → 网易 → Apple Music → 酷我。
         *
         * 不读插件包声明的 order：那是由包决定的，换了包顺序就会变，
         * 而"元信息组合搜索按这个顺序取每项内容最多的源"依赖它稳定。
         */
        private val PREFERRED_ORDER = mapOf(
            "com.qqmusic.source" to 10,
            "com.neteasecloudmusic.source" to 20,
            "com.applemusic.source" to 30,
            "com.kugou.source" to 40
        )
    }
}

/**
 * 比较已安装版本与待安装版本。
 *
 * 只按 versionName 比：数据库里存的就是它，用户看到的插件版本也是它。
 * "1.0" 与 "1.0.0" 视为同版本，落后面的零不参与比较。
 *
 * @param installedVersion 已安装的 versionName
 * @return <0 待安装更新；0 同版本；>0 已安装更新
 */
internal fun compareVersions(installedVersion: String, incomingVersionName: String): Int {
    val a = parseVersion(installedVersion)
    val b = parseVersion(incomingVersionName)
    if (a == null || b == null) return installedVersion.trim().compareTo(incomingVersionName.trim())
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}

/** "0.4.2" / "0.4.2-beta" → [0, 4, 2]；解析不出来返回 null。 */
private fun parseVersion(raw: String): List<Int>? {
    val head = raw.trim().substringBefore('-').substringBefore('+').trim()
    if (head.isBlank()) return null
    val out = mutableListOf<Int>()
    for (part in head.split('.')) {
        val digits = part.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        out += digits.toIntOrNull() ?: return null
    }
    return out
}
