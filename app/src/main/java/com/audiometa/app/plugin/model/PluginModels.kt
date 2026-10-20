package com.audiometa.app.plugin.model

import com.audiometa.app.domain.model.ScrapeCapability
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从系统语言标签推出所有可能的资源键。
 * 例如 zh-Hans-CN -> [zh-Hans-CN, zh-Hans, zh]，这样才能命中插件的 zh-Hans.json。
 */
fun localeCandidates(tag: String): List<String> {
    val normalized = tag.trim().replace('_', '-')
    if (normalized.isBlank()) return emptyList()
    val parts = normalized.split('-')
    val out = linkedSetOf<String>()
    out += normalized
    if (parts.size >= 2) out += parts[0] + "-" + parts[1]

    // 中文要补上带书写系统的键。
    // 系统的 toLanguageTag() 可能是 "zh-CN"（没有书写系统），
    // 而插件资源普遍叫 "zh-Hans.json"。不补这一步就会退到英文，
    // 于是「QQ音乐」显示成 "QQ Music"。
    if (parts[0].equals("zh", ignoreCase = true)) {
        val region = parts.getOrNull(1)?.uppercase().orEmpty()
        val traditional = region in setOf("TW", "HK", "MO")
        val script = if (traditional) "zh-Hant" else "zh-Hans"
        out += script
        if (region.isNotBlank()) out += script + "-" + region
        if (traditional) out += "zh-Hant-HK"
    }

    out += parts[0]
    return out.toList()
}

/**
 * 插件能力。
 *
 * `searchCovers` 现在**启用**：详情页的「专辑图」入口会调用它挑封面。
 * `getLyrics` 同样启用；`searchCovers` 的结果和 `searchSongs` 同形（带 `picUrl`），
 * 宿主取其中一张图写进文件封面。
 */
enum class PluginCapability(val key: String) {
    SEARCH_SONGS("searchSongs"),
    SEARCH_COVERS("searchCovers"),
    GET_LYRICS("getLyrics"),

    /** 艺术家图片。Lyrico 原版没有这个能力，是本项目扩展的，格式与其余能力一致。 */
    SEARCH_ARTIST_IMAGES("searchArtistImages");

    /** 映射到应用暴露给用户的能力。应用不再支持的能力返回 null。 */
    fun scrapeCapability(): ScrapeCapability? = when (this) {
        SEARCH_SONGS -> ScrapeCapability.TRACK_META
        SEARCH_ARTIST_IMAGES -> ScrapeCapability.ARTIST_IMAGE
        GET_LYRICS -> ScrapeCapability.LYRICS
        SEARCH_COVERS -> ScrapeCapability.COVER
    }

    companion object {
        fun fromKey(key: String): PluginCapability? = entries.firstOrNull { it.key == key }
    }
}

/** 插件入口函数名，宿主按这些名字调用全局函数。 */
object PluginFunction {
    const val SEARCH_SONGS = "searchSongs"
    const val SEARCH_COVERS = "searchCovers"
    const val GET_LYRICS = "getLyrics"
    const val SEARCH_ARTIST_IMAGES = "searchArtistImages"

    fun of(capability: ScrapeCapability): String? = when (capability) {
        ScrapeCapability.ARTIST_IMAGE -> SEARCH_ARTIST_IMAGES
        ScrapeCapability.TRACK_META -> SEARCH_SONGS
        // 曲风没有专属入口：曲风就在 searchSongs 返回的歌曲对象里。
        ScrapeCapability.GENRE -> SEARCH_SONGS
        ScrapeCapability.LYRICS -> GET_LYRICS
        ScrapeCapability.COVER -> SEARCH_COVERS
    }
}

enum class ConfigFieldType {
    SWITCH,
    NUMBER,
    TEXT,
    PASSWORD,
    DROPDOWN,
    MARKDOWN;

    companion object {
        fun fromKey(key: String): ConfigFieldType = when (key.lowercase()) {
            "switch" -> SWITCH
            "number" -> NUMBER
            "password" -> PASSWORD
            "dropdown", "select", "enum" -> DROPDOWN
            "markdown", "notice" -> MARKDOWN
            else -> TEXT
        }
    }
}

data class ConfigOption(val value: String, val label: String, val summary: String = "")

/** 条件显示：当 key 字段等于 value 时才显示本项。 */
data class ConfigDependency(val key: String, val value: String)

data class ConfigField(
    val key: String,
    val title: String,
    val summary: String,
    val group: String,
    val type: ConfigFieldType,
    val defaultValue: String,
    val required: Boolean,
    val options: List<ConfigOption>,
    val dependency: ConfigDependency?
) {
    /** markdown 只用于展示说明，不产生配置值。 */
    val isDisplayOnly: Boolean get() = type == ConfigFieldType.MARKDOWN
}

/**
 * 插件清单。一个目录一个插件，对应包里的一个源。
 *
 * localeTexts 是已加载的 i18n 文本；manifest 里以 @ 开头的文案都用它解析。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val author: String,
    val description: String,
    val apiVersion: Int,
    val minHostApiVersion: Int,
    /**
     * 导入后默认是否启用，来自插件包 manifest 的 defaultEnabled。
     *
     * **null = 插件包没声明这个字段**（旧包都不声明）。这时由安装器回落到内置的
     * 默认启用名单 [com.audiometa.app.plugin.install.PluginInstaller]；
     * 声明了就按插件包说的算，客户端不再替它决定。
     */
    val defaultEnabled: Boolean?,
    val includeDirs: List<String>,
    val entry: String,
    val icon: String,
    /**
     * 插件包自己声明的默认排序。数字越小越靠前。
     *
     * 导入后插件列表就按它排，不需要用户手动拖：
     * QQ / 网易 / Apple Music / 酷狗 排在前面，汽水最后。
     * 没写这个字段的插件给 [DEFAULT_ORDER]，排在声明过的后面。
     */
    val order: Int,
    val capabilities: Set<PluginCapability>,
    val configFields: List<ConfigField>,
    val defaultLocale: String,
    val localeResources: Map<String, String>,
    val localeTexts: Map<String, Map<String, String>>,
    val directoryName: String
) {
    fun scrapeCapabilities(): Set<ScrapeCapability> {
        val declared = capabilities.mapNotNull { it.scrapeCapability() }.toMutableSet()
        // 曲风没有自己的插件入口：声明了「按歌搜」（元信息）的源就能给曲风
        // （`searchSongs` 的返回里本来就有 genre），所以这里自动补上。
        if (ScrapeCapability.TRACK_META in declared) declared += ScrapeCapability.GENRE
        return declared
    }

    /** 把 @key 解析成当前语言下的文案；找不到就退到默认语言，再退到原值。 */
    fun resolve(text: String, locale: String): String {
        val raw = text.trim()
        if (!raw.startsWith("@")) return text
        val key = raw.substring(1)
        return lookup(key, locale) ?: lookup(key, defaultLocale) ?: text
    }

    private fun lookup(key: String, locale: String): String? {
        localeCandidates(locale).forEach { candidate ->
            localeTexts[candidate]?.get(key)?.let { return it }
        }
        return null
    }

    /** 供 UI 使用：把 @key 文案全部替换掉。 */
    fun localized(locale: String): PluginManifest = copy(
        name = resolve(name, locale),
        description = resolve(description, locale),
        configFields = configFields.map { field ->
            field.copy(
                title = resolve(field.title, locale),
                summary = resolve(field.summary, locale),
                group = resolve(field.group, locale),
                defaultValue = resolve(field.defaultValue, locale),
                options = field.options.map { option ->
                    option.copy(
                        label = resolve(option.label, locale),
                        summary = resolve(option.summary, locale)
                    )
                }
            )
        }
    )

    fun validate() {
        require(id.isNotBlank()) { "manifest 缺少 id" }
        require(entry.isNotBlank()) { "manifest 缺少 entry" }
        require(capabilities.isNotEmpty()) { "插件 " + id + " 没有声明任何能力" }
        require(minHostApiVersion <= HOST_API_VERSION) {
            "插件 " + id + " 需要宿主 API " + minHostApiVersion + "，高于当前支持的 " + HOST_API_VERSION
        }
    }

    companion object {
        /** 宿主支持的插件 API 版本上限。 */
        const val HOST_API_VERSION = 5

        /** 没声明 order 的插件排在这里，也就是排在所有声明过的源后面。 */
        const val DEFAULT_ORDER = 1000

        fun parse(
            text: String,
            directoryName: String,
            localeTexts: Map<String, Map<String, String>>
        ): PluginManifest {
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalArgumentException("manifest.json 不是合法 JSON")
            }
            val capabilities = json.optJSONArray("capabilities")?.toStringList().orEmpty()
                .mapNotNull { PluginCapability.fromKey(it) }
                .toSet()
            val i18n = json.optJSONObject("i18n")
            val defaultLocale = i18n?.optString("defaultLocale")?.ifBlank { "en" } ?: "en"
            val resources = mutableMapOf<String, String>()
            i18n?.optJSONObject("resources")?.let { obj ->
                obj.keys().forEach { key -> resources[key] = obj.optString(key) }
            }
            val fields = mutableListOf<ConfigField>()
            json.optJSONArray("configFields")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val options = mutableListOf<ConfigOption>()
                    item.optJSONArray("options")?.let { opts ->
                        for (j in 0 until opts.length()) {
                            val opt = opts.optJSONObject(j) ?: continue
                            options += ConfigOption(
                                value = opt.optString("value"),
                                label = opt.optString("label"),
                                summary = opt.optString("summary")
                            )
                        }
                    }
                    val match = item.optJSONObject("dependency")?.optJSONObject("match")
                    val dependency = match?.let {
                        ConfigDependency(key = it.optString("key"), value = it.optString("value"))
                    }
                    fields += ConfigField(
                        key = item.optString("key"),
                        title = item.optString("title"),
                        summary = item.optString("summary"),
                        group = item.optString("group"),
                        type = ConfigFieldType.fromKey(item.optString("type", "text")),
                        defaultValue = item.opt("defaultValue")?.toString().orEmpty(),
                        required = item.optBoolean("required", false),
                        options = options,
                        dependency = dependency
                    )
                }
            }
            val id = json.optString("id").ifBlank { directoryName }
            val manifest = PluginManifest(
                id = id,
                name = json.optString("name").ifBlank { id },
                versionCode = json.optInt("versionCode", 1),
                versionName = json.optString("versionName").ifBlank { "1.0" },
                author = json.optString("author"),
                description = json.optString("description"),
                apiVersion = json.optInt("apiVersion", 1),
                minHostApiVersion = json.optInt("minHostApiVersion", 1),
                defaultEnabled = if (json.has("defaultEnabled")) json.optBoolean("defaultEnabled") else null,
                includeDirs = json.optJSONArray("includeDirs")?.toStringList().orEmpty(),
                entry = json.optString("entry").ifBlank { "source.js" },
                icon = json.optString("icon").ifBlank { "icon.png" },
                order = json.optInt("order", DEFAULT_ORDER),
                capabilities = capabilities,
                configFields = fields,
                defaultLocale = defaultLocale,
                localeResources = resources,
                localeTexts = localeTexts,
                directoryName = directoryName
            )
            manifest.validate()
            return manifest
        }
    }
}

/** 已安装并可运行的插件。 */
data class InstalledPlugin(
    val pluginId: String,
    val installDir: String,
    val manifest: PluginManifest,
    /** 按加载顺序排好的脚本：先是 includeDirs 里的模块，最后是入口。 */
    val sources: List<Pair<String, String>>,
    val iconPath: String,
    val configJson: String
) {
    /**
     * 显示用的插件名。
     * manifest 里的 name 常写成 @plugin.name，必须走 i18n 解析，
     * 否则界面和日志里会直接显示 "@/plugin/name" 这种原始键。
     */
    val name: String
        get() = manifest.resolve(manifest.name, java.util.Locale.getDefault().toLanguageTag().ifBlank { "en" })
}

private fun JSONArray.toStringList(): List<String> {
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        val value = optString(i)
        if (value.isNotBlank()) out += value
    }
    return out
}
