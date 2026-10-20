package com.audiometa.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.DEFAULT_FALLBACK_LYRICS_DIR
import com.audiometa.app.domain.model.DEFAULT_LYRICS_PRIORITY
import com.audiometa.app.domain.model.CornerStyle
import com.audiometa.app.domain.model.DurationFilter
import com.audiometa.app.domain.model.FallbackLyrics
import com.audiometa.app.domain.model.DEFAULT_SOURCE_PRIORITY
import com.audiometa.app.domain.model.ImageMode
import com.audiometa.app.domain.model.LYRICS_TYPE_KEYS
import com.audiometa.app.domain.model.ThemeMode
import com.audiometa.app.domain.model.ThemePreset
import com.audiometa.app.domain.model.WriteMode
import com.audiometa.app.domain.model.formatSupportOf
import com.audiometa.app.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences

/*
 * 配置文件损坏时回到默认值，而不是让整个界面在组合期抛异常。
 *
 * DataStore 在文件被写坏（断电、存储满、跨版本写入中断）时默认抛
 * CorruptionException，`settings` 被 Compose 收集时就会直接把进程带走 ——
 * 用户看到的是"打开就闪退，重装才好"。
 */
private val Context.dataStore by preferencesDataStore(
    name = "audiometa_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** 源优先级编码：key1=id1,id2|key2=id3 */
private fun encodePriority(priority: Map<String, List<String>>): String =
    priority.entries
        .filter { it.value.isNotEmpty() }
        .joinToString("|") { (key, ids) -> key + "=" + ids.joinToString(",") }

private fun decodePriority(raw: String?): Map<String, List<String>> {
    if (raw.isNullOrBlank()) return DEFAULT_SOURCE_PRIORITY
    val parsed = raw.split('|')
        .mapNotNull { entry ->
            val index = entry.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val key = entry.substring(0, index)
            val ids = entry.substring(index + 1).split(',').filter { it.isNotBlank() }
            key to ids
        }
        .toMap()
    // 缺失的键补上默认值，保证升级后行为不变。
    return DEFAULT_SOURCE_PRIORITY + parsed
}

/** 插件默认排序编码：id1=10,id2=20 */
private fun encodePluginOrder(order: Map<String, Int>): String =
    order.entries.sortedBy { it.value }.joinToString(",") { it.key + "=" + it.value }

private fun decodePluginOrder(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',')
        .mapNotNull { entry ->
            val index = entry.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val id = entry.substring(0, index)
            val value = entry.substring(index + 1).toIntOrNull() ?: return@mapNotNull null
            id to value
        }
        .toMap()
}

data class AppSettings(
    // 外观
    val dynamicColor: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    /** 封面与头像的边角样式，可在圆形/圆角/方形之间切换。 */
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,

    // 音频库的界面状态，持久化保存：重启后保持上次的样子。
    val libraryMode: String = "SONGS",
    val gridPreset: String = "STANDARD",
    val artistFilter: String = "ALL",
    /** 艺术家网格的形状：RECT 矩形（默认）/ CIRCLE 圆形。与封面边角样式各自独立。 */
    val artistShape: String = "RECT",
    /** 自定义颜色，形如 FFRRVVBB；为空表示用预设。 */
    val customPrimary: String = "",
    val customBackground: String = "",
    val customSurface: String = "",

    // 引导与权限
    val onboardingDone: Boolean = false,

    // 音乐库
    val roots: List<String> = emptyList(),
    val rootUris: List<String> = emptyList(),
    val excluded: Set<String> = emptySet(),
    val formats: Set<String> = AudioFormats.ALL_SET,
    /**
     * 时长过滤。**默认跳过 30 秒以下**（用户要求）：
     * 铃声、提示音、语音碎片这类短文件不该进曲库。
     */
    val durationFilter: DurationFilter = DurationFilter.SKIP_UNDER_30,
    val customMinSeconds: Int = 30,
    val excludeSubdirs: Boolean = false,

    // 刮削
    /** 每种内容用哪些源、什么顺序。键见 PriorityKey。 */
    val sourcePriority: Map<String, List<String>> = DEFAULT_SOURCE_PRIORITY,
    /** 插件列表的全局顺序（拖动排序得到），未列出的接在最后。 */
    val pluginOrder: List<String> = emptyList(),
    /**
     * 插件包自己声明的默认排序：pluginId → order，数字越小越靠前。
     *
     * 导入时就按它排，用户不用手动拖；拖动排序（[pluginOrder]）优先于它。
     */
    val pluginDefaultOrder: Map<String, Int> = emptyMap(),
    val lyricsPriority: List<String> = DEFAULT_LYRICS_PRIORITY,
    /** 下载歌词时是否一并取翻译。用户要求默认开（"默认逐字+翻译优先"）。 */
    val downloadTranslation: Boolean = true,
    val fallbackLyrics: FallbackLyrics = FallbackLyrics.NONE,
    val fallbackDir: String = DEFAULT_FALLBACK_LYRICS_DIR,
    val collectAll: Boolean = false,
    val concurrency: Int = 2,

    // 写入
    val writeEmbedded: Boolean = true,
    val writeExternal: Boolean = false,
    val imageMode: ImageMode = ImageMode.CUSTOM,
    val imageQuality: Int = 100,
    val keepModTime: Boolean = true,

    // 调试
    val debugLogging: Boolean = false,
    val debugLevel: LogLevel = LogLevel.INFO,

    /**
     * 播放时跳过开头静音。
     *
     * 跳过时间不是临场算的，是后台预计算好存起来的（见 SilenceProfileStore），
     * 所以开关关掉/打开都是零延迟。
     */
    val skipSilentIntro: Boolean = true,

    /** 曲库页三个页签的选择记忆：歌曲 / 艺术家 / 曲风。 */
    val libraryTab: String = "SONGS",
    /** 搜索页上次停在哪个筛选（标题/艺术家/专辑/曲风/歌词）。 */
    val searchFilter: String = "TITLE",

    // 曲库各页的布局。歌曲页可以切成列表，艺术家和曲风页只调列数。
    val songsAsGrid: Boolean = false,
    val artistsAsGrid: Boolean = true,
    val genresAsGrid: Boolean = true,

    /**
     * 每页独立的布局配置，键是 [LibraryMode] 的名字。
     *
     * 存成一个 JSON 字符串而不是十几个独立偏好项：这样加一种排序字段、
     * 加一个页签都不用改存储层。用户明确要求"每页状态独立且持久化存储，
     * 重启软件保持上次设置的状态"。
     */
    val tabLayouts: String = "",

    /**
     * 「元信息」标签识别已经覆盖到第几代曲目索引。
     *
     * 标签识别并进曲库后台补全之后，老库里所有曲目的 `lyricsIndexedAt` 都不为 0，
     * 补全不会再碰它们，「元信息」页就会一直是空的。所以升到这一代时
     * 把索引标记整体清零一次，让后台按新的流程重新过一遍。
     * 这是个一次性的迁移开关，正常跑过一次就不会再触发。
     */
    val metaScanEpoch: Int = 0,

    /**
     * 调试模式。
     *
     * 开启后才出现「元信息复制」这类只给排查问题用的项。
     * 普通用户看不到它们，界面也就不用为这些一次性功能留位置。
     */
    val debugMode: Boolean = false,

    /**
     * 内置的"默认排除"规则是否已经跑过。
     *
     * 用户要求：这些目录**只在首次扫描时**自动排除，之后即使被手动取消勾选，
     * 也不会再自动加回去。所以用一个一次性标记记住。
     */
    val autoExcludeDone: Boolean = false,

    /**
     * 用户手动取消勾选过的"内置默认排除"目录。
     *
     * 记下来是为了"此后不会再自动排除"：取消过一次的路径永远不再自动勾上。
     */
    val autoExcludeKeep: Set<String> = emptySet(),

    /**
     * 曲库来源，**二选一**（用户要求）：
     *
     * - [SOURCE_FOLDERS]：只用「音乐库」里手动添加的固定文件夹；
     * - [SOURCE_MEDIA_STORE]：用 Android 媒体库（系统扫出来的音频所在目录）。
     *
     * 两个是互斥的，不是叠加：媒体库模式下不再看用户加的目录，
     * 避免"我明明没加这个目录，它却进来了"。
     */
    val librarySource: String = SettingsStore.SOURCE_FOLDERS,

    /** 插件仓库地址（指向 index.json）。 */
    val pluginRepoUrl: String = "",

    /**
     * 插件仓库 index.json 里写的仓库名（例如「渊の插件源」）。
     *
     * 拉取成功时记下来，插件页那一行就显示它而不是一串地址；
     * 还没拉到过（或仓库没写 name）时是空串，界面退回显示地址。
     */
    val pluginRepoName: String = "",

    /**
     * 第三方中转前缀（有序），空串那一项表示"直连"。
     *
     * 用户填的 GitHub 地址在国内多半连不上，[com.audiometa.app.util.MirrorFetch]
     * 会按这份表把候选地址挨个试。顺序就是试的顺序，
     * 「设置 - 插件 - 网络与镜像」里测一遍之后会按实测快慢重排。
     */
    val mirrorPrefixes: List<String> = SettingsStore.DEFAULT_MIRROR_PREFIXES,

    /**
     * 上次成功的镜像键（[com.audiometa.app.util.RemoteText.Candidate.mirror]），空串 = 直连。
     *
     * 记它是为了**下次先试它**：国内直连一个被 DNS 污染的域名要等满超时才换下一个，
     * 记住之后第二次开始几乎立刻就能拿到内容。
     */
    val preferredMirror: String = "",

    /**
     * 上次真正下载成功的那个地址（可能是指向 jsDelivr 的镜像地址）。
     *
     * 只用于「设置 - 插件 - 网络与镜像」里给用户看"到底是哪条通的"，不参与选路。
     */
    val lastWorkingUrl: String = "",

    /**
     * 上一次从订阅仓库装好的那个**合集包**（`MetaAudio-Subscription.zip`）的 sha256
     *（index.json 里 `bundle.sha256`）。
     *
     * 订阅更新**只下这一个包**，要不要下就看它：点「更新订阅」时先拉 index.json，
     * 这一份和仓库公布的**一致**就不再下任何东西（见
     * [com.audiometa.app.plugin.install.PluginRepoUpdater]）。记的是"上次装成功的是哪一版包"，
     * 校验不过或包里缺件的那次**不会**写进来，所以下次点还会重新下一遍。
     *
     * 以前这里按规则、图标各记一份（`repo_rules_sha256` / `repo_icons_sha256`）——
     * 那两个键是给"逐个下载"看的，现在整包只有一个哈希，它们没有任何读者了，已删。
     */
    val repoBundleSha256: String = "",

    /**
     * 上次装好的订阅包的 **md5**。
     *
     * 「更新订阅」第一步就比它（仓库在 index.json 里公布 `bundle.md5`）：
     * md5 一样就说明订阅没变，一个字节都不下；不一样才去下整包，
     * 下完再用 sha256 校验内容。md5 只是"要不要下"的快速判断，不承担完整性校验。
     */
    val repoBundleMd5: String = "",

    /**
     * 远程控制（连 PC 上的 Salt Player 插件）。
     *
     * 单独一组，和刮削插件体系无关 —— 那个管标签数据源，这个管播放器遥控。
     */
    val remote: RemoteControlPrefs = RemoteControlPrefs()
) {
    fun minDurationMs(): Long = when (durationFilter) {
        DurationFilter.NONE -> 0L
        DurationFilter.SKIP_UNDER_30 -> 30_000L
        DurationFilter.SKIP_UNDER_60 -> 60_000L
        DurationFilter.CUSTOM -> customMinSeconds.coerceAtLeast(0) * 1000L
    }

    /**
     * 由"内嵌 / 同时写外置文件"推导实际写入方式。
     * 格式本身不支持内嵌时自动降级为只写外置文件。
     */
    fun effectiveWriteMode(ext: String): WriteMode {
        val embed = writeEmbedded && formatSupportOf(ext).anyEmbedded
        return when {
            embed && writeExternal -> WriteMode.BOTH
            embed -> WriteMode.EMBED
            else -> WriteMode.SIDECAR
        }
    }

    /** 歌词优先级：丢掉不支持的键，并补齐缺失的候选。 */
    fun normalizedLyricsPriority(): List<String> {
        val kept = lyricsPriority.filter { it in LYRICS_TYPE_KEYS }.distinct()
        val missing = LYRICS_TYPE_KEYS.filter { it !in kept }
        return kept + missing
    }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val themeMode = stringPreferencesKey("theme_mode")
        val themePreset = stringPreferencesKey("theme_preset")
        val cornerStyle = stringPreferencesKey("corner_style")
        val libraryMode = stringPreferencesKey("library_mode")
        val gridPreset = stringPreferencesKey("grid_preset")
        val artistFilter = stringPreferencesKey("artist_filter")
        val artistShape = stringPreferencesKey("artist_shape")
        val customPrimary = stringPreferencesKey("custom_primary")
        val customBackground = stringPreferencesKey("custom_background")
        val customSurface = stringPreferencesKey("custom_surface")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val roots = stringPreferencesKey("roots")
        val rootUris = stringPreferencesKey("root_uris")
        val excluded = stringSetPreferencesKey("excluded")
        val formats = stringSetPreferencesKey("formats")
        val durationFilter = stringPreferencesKey("duration_filter")
        val customMinSeconds = intPreferencesKey("custom_min_seconds")
        val excludeSubdirs = booleanPreferencesKey("exclude_subdirs")
        val sourcePriority = stringPreferencesKey("source_priority")
        val pluginOrder = stringPreferencesKey("plugin_order")
        val pluginDefaultOrder = stringPreferencesKey("plugin_default_order")
        val lyricsPriority = stringPreferencesKey("lyrics_priority")
        val downloadTranslation = booleanPreferencesKey("download_translation")
        val fallbackLyrics = stringPreferencesKey("fallback_lyrics")
        val fallbackDir = stringPreferencesKey("fallback_dir")
        val collectAll = booleanPreferencesKey("collect_all")
        val concurrency = intPreferencesKey("concurrency")
        val writeEmbedded = booleanPreferencesKey("write_embedded")
        val writeExternal = booleanPreferencesKey("write_external")
        val imageMode = stringPreferencesKey("image_mode")
        val imageQuality = intPreferencesKey("image_quality")
        val keepModTime = booleanPreferencesKey("keep_mod_time")
        val debugLogging = booleanPreferencesKey("debug_logging")
        val debugLevel = stringPreferencesKey("debug_level")
        val skipSilentIntro = booleanPreferencesKey("skip_silent_intro")
        val libraryTab = stringPreferencesKey("library_tab")
        val searchFilter = stringPreferencesKey("search_filter")
        val songsAsGrid = booleanPreferencesKey("songs_as_grid")
        val artistsAsGrid = booleanPreferencesKey("artists_as_grid")
        val genresAsGrid = booleanPreferencesKey("genres_as_grid")
        val mirrorPrefixes = stringPreferencesKey("mirror_prefixes")
        val preferredMirror = stringPreferencesKey("preferred_mirror")
        val lastWorkingUrl = stringPreferencesKey("last_working_url")
        val repoBundleSha256 = stringPreferencesKey("repo_bundle_sha256")

    /** 上次装好的订阅包的 md5（见 [AppSettings.repoBundleMd5]）。 */
    val repoBundleMd5 = stringPreferencesKey("repo_bundle_md5")
        val metaScanEpoch = intPreferencesKey("meta_scan_epoch")
        val debugMode = booleanPreferencesKey("debug_mode")
        val autoExcludeDone = booleanPreferencesKey("auto_exclude_done")
        val autoExcludeKeep = stringPreferencesKey("auto_exclude_keep")
        val librarySource = stringPreferencesKey("library_source")
        val pluginRepoUrl = stringPreferencesKey("plugin_repo_url")
        val pluginRepoName = stringPreferencesKey("plugin_repo_name")
        val tabLayouts = stringPreferencesKey("tab_layouts")

        // 远程控制（连 PC 上的 Salt Player 插件）
        val remoteEnabled = booleanPreferencesKey("remote_enabled")
        val remoteHost = stringPreferencesKey("remote_host")
        val remotePort = intPreferencesKey("remote_port")
        val remoteTls = booleanPreferencesKey("remote_tls")
        val remoteAutoConnect = booleanPreferencesKey("remote_auto_connect")
        val remoteHeartbeat = booleanPreferencesKey("remote_heartbeat")
        val remoteToken = stringPreferencesKey("remote_token")
        val remoteSyncLocalAudio = booleanPreferencesKey("remote_sync_local_audio")
        val remoteFixedLan = booleanPreferencesKey("remote_fixed_lan")
        val remoteFixedLanHost = stringPreferencesKey("remote_fixed_lan_host")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { error ->
            // 读盘失败时给默认值：设置读不出来不该是闪退，最多是主题/开关回到默认。
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { p ->
        AppSettings(
            dynamicColor = p[Keys.dynamicColor] ?: false,
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[Keys.themeMode] } ?: ThemeMode.DARK,
            themePreset = ThemePreset.fromName(p[Keys.themePreset]),
            cornerStyle = CornerStyle.fromName(p[Keys.cornerStyle]),
            libraryMode = p[Keys.libraryMode] ?: "SONGS",
            gridPreset = p[Keys.gridPreset] ?: "STANDARD",
            artistFilter = p[Keys.artistFilter] ?: "ALL",
            artistShape = p[Keys.artistShape] ?: "RECT",
            customPrimary = p[Keys.customPrimary].orEmpty(),
            customBackground = p[Keys.customBackground].orEmpty(),
            customSurface = p[Keys.customSurface].orEmpty(),
            onboardingDone = p[Keys.onboardingDone] ?: false,
            roots = p[Keys.roots].orEmpty().split('\n').filter { it.isNotBlank() },
            rootUris = p[Keys.rootUris].orEmpty().split('\n').filter { it.isNotBlank() },
            excluded = p[Keys.excluded] ?: emptySet(),
            formats = p[Keys.formats] ?: AudioFormats.ALL_SET,
            durationFilter = DurationFilter.entries.firstOrNull { it.name == p[Keys.durationFilter] }
                ?: DurationFilter.NONE,
            customMinSeconds = p[Keys.customMinSeconds] ?: 30,
            excludeSubdirs = p[Keys.excludeSubdirs] ?: false,
            sourcePriority = decodePriority(p[Keys.sourcePriority]),
            pluginOrder = p[Keys.pluginOrder].orEmpty().split(',').filter { it.isNotBlank() },
            pluginDefaultOrder = decodePluginOrder(p[Keys.pluginDefaultOrder]),
            lyricsPriority = p[Keys.lyricsPriority]?.split('|')?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_LYRICS_PRIORITY,
            downloadTranslation = p[Keys.downloadTranslation] ?: true,
            fallbackLyrics = FallbackLyrics.fromKey(p[Keys.fallbackLyrics]),
            fallbackDir = p[Keys.fallbackDir]?.takeIf { it.isNotBlank() } ?: DEFAULT_FALLBACK_LYRICS_DIR,
            collectAll = p[Keys.collectAll] ?: false,
            concurrency = (p[Keys.concurrency] ?: 2).coerceIn(1, 4),
            writeEmbedded = p[Keys.writeEmbedded] ?: true,
            writeExternal = p[Keys.writeExternal] ?: false,
            imageMode = ImageMode.entries.firstOrNull { it.name == p[Keys.imageMode] } ?: ImageMode.CUSTOM,
            imageQuality = (p[Keys.imageQuality] ?: 100).coerceIn(1, 100),
            keepModTime = p[Keys.keepModTime] ?: true,
            debugLogging = p[Keys.debugLogging] ?: false,
            debugLevel = LogLevel.fromName(p[Keys.debugLevel]),
            skipSilentIntro = p[Keys.skipSilentIntro] ?: true,
            libraryTab = p[Keys.libraryTab] ?: "SONGS",
            searchFilter = p[Keys.searchFilter] ?: "TITLE",
            songsAsGrid = p[Keys.songsAsGrid] ?: false,
            artistsAsGrid = p[Keys.artistsAsGrid] ?: true,
            genresAsGrid = p[Keys.genresAsGrid] ?: true,
            // 每行一个前缀，顺序就是尝试顺序。
            // 老版本存过空行（那时它是"直连"占位），照旧保留：对拼接没有影响。
            mirrorPrefixes = p[Keys.mirrorPrefixes]?.split('\n')?.map { it.trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: SettingsStore.DEFAULT_MIRROR_PREFIXES,
            preferredMirror = p[Keys.preferredMirror].orEmpty(),
            lastWorkingUrl = p[Keys.lastWorkingUrl].orEmpty(),
            repoBundleSha256 = p[Keys.repoBundleSha256].orEmpty(),
        repoBundleMd5 = p[Keys.repoBundleMd5].orEmpty(),
            debugMode = p[Keys.debugMode] ?: false,
            autoExcludeDone = p[Keys.autoExcludeDone] ?: false,
            librarySource = p[Keys.librarySource] ?: SettingsStore.SOURCE_FOLDERS,
            pluginRepoUrl = p[Keys.pluginRepoUrl].orEmpty(),
            pluginRepoName = p[Keys.pluginRepoName].orEmpty(),
            autoExcludeKeep = p[Keys.autoExcludeKeep].orEmpty()
                .split('\n').filter { it.isNotBlank() }.toSet(),
            metaScanEpoch = p[Keys.metaScanEpoch] ?: 0,
            tabLayouts = p[Keys.tabLayouts].orEmpty(),
            remote = RemoteControlPrefs(
                enabled = p[Keys.remoteEnabled] ?: false,
                host = p[Keys.remoteHost].orEmpty().trim(),
                // 端口越界就退回默认值，避免用户填了 70000 之后连接必失败。
                port = (p[Keys.remotePort] ?: RemoteControlPrefs.DEFAULT_PORT)
                    .takeIf { it in 1..65535 } ?: RemoteControlPrefs.DEFAULT_PORT,
                autoConnect = p[Keys.remoteAutoConnect] ?: true,
                useTls = p[Keys.remoteTls] ?: false,
                heartbeat = p[Keys.remoteHeartbeat] ?: true,
                syncLocalAudio = p[Keys.remoteSyncLocalAudio] ?: false,
                // 配对码没有默认值：插件首次运行随机生成，用户必须去 PC 上看。
                token = p[Keys.remoteToken].orEmpty().trim(),
                fixedLan = p[Keys.remoteFixedLan] ?: false,
                fixedLanHost = p[Keys.remoteFixedLanHost].orEmpty().trim()
            )
        )
    }

    suspend fun setDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = value }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.themeMode] = mode.name }
    suspend fun setThemePreset(preset: ThemePreset) = context.dataStore.edit { it[Keys.themePreset] = preset.name }
    suspend fun setCornerStyle(style: CornerStyle) = context.dataStore.edit { it[Keys.cornerStyle] = style.name }
    suspend fun setLibraryMode(value: String) = context.dataStore.edit { it[Keys.libraryMode] = value }
    suspend fun setGridPreset(value: String) = context.dataStore.edit { it[Keys.gridPreset] = value }
    suspend fun setArtistFilter(value: String) = context.dataStore.edit { it[Keys.artistFilter] = value }
    suspend fun setArtistShape(value: String) = context.dataStore.edit { it[Keys.artistShape] = value }
    suspend fun setCustomPrimary(value: String) = context.dataStore.edit { it[Keys.customPrimary] = value }
    suspend fun setCustomBackground(value: String) = context.dataStore.edit { it[Keys.customBackground] = value }
    suspend fun setCustomSurface(value: String) = context.dataStore.edit { it[Keys.customSurface] = value }

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.onboardingDone] = done }

    suspend fun setRoots(paths: List<String>, uris: List<String>) = context.dataStore.edit {
        it[Keys.roots] = paths.joinToString("\n")
        it[Keys.rootUris] = uris.joinToString("\n")
    }

    suspend fun addRoot(path: String, uri: String) = context.dataStore.edit {
        val paths = it[Keys.roots].orEmpty().split('\n').filter { s -> s.isNotBlank() }.toMutableList()
        val uris = it[Keys.rootUris].orEmpty().split('\n').filter { s -> s.isNotBlank() }.toMutableList()
        if (path.isNotBlank() && path !in paths) paths += path
        if (uri.isNotBlank() && uri !in uris) uris += uri
        it[Keys.roots] = paths.joinToString("\n")
        it[Keys.rootUris] = uris.joinToString("\n")
    }

    suspend fun removeRoot(path: String) = context.dataStore.edit {
        val paths = it[Keys.roots].orEmpty().split('\n').filter { s -> s.isNotBlank() && s != path }
        it[Keys.roots] = paths.joinToString("\n")
    }

    suspend fun setExcluded(excluded: Set<String>) = context.dataStore.edit { it[Keys.excluded] = excluded }

    suspend fun toggleExcluded(path: String, exclude: Boolean) = context.dataStore.edit {
        val current = it[Keys.excluded]?.toMutableSet() ?: mutableSetOf()
        if (exclude) current += path else current -= path
        it[Keys.excluded] = current
    }

    suspend fun setFormats(formats: Set<String>) = context.dataStore.edit { it[Keys.formats] = formats }
    suspend fun setDurationFilter(filter: DurationFilter) = context.dataStore.edit { it[Keys.durationFilter] = filter.name }
    suspend fun setCustomMinSeconds(seconds: Int) = context.dataStore.edit { it[Keys.customMinSeconds] = seconds }
    suspend fun setExcludeSubdirs(value: Boolean) = context.dataStore.edit { it[Keys.excludeSubdirs] = value }

    suspend fun setSourcePriority(priority: Map<String, List<String>>) = context.dataStore.edit {
        it[Keys.sourcePriority] = encodePriority(priority)
    }

    suspend fun setPluginOrder(ids: List<String>) = context.dataStore.edit {
        it[Keys.pluginOrder] = ids.joinToString(",")
    }

    /** 导入插件时记下插件包声明的默认排序，并把它从手动顺序里摘掉（默认排序优先于旧记录）。 */
    suspend fun applyPluginDefaultOrder(order: Map<String, Int>) = context.dataStore.edit { prefs ->
        val current = decodePluginOrder(prefs[Keys.pluginDefaultOrder]).toMutableMap()
        current.putAll(order)
        prefs[Keys.pluginDefaultOrder] = encodePluginOrder(current)

        val explicit = prefs[Keys.pluginOrder].orEmpty().split(',').filter { it.isNotBlank() }
        val filtered = explicit.filterNot { it in order.keys }
        if (filtered != explicit) prefs[Keys.pluginOrder] = filtered.joinToString(",")
    }

    suspend fun setLyricsPriority(order: List<String>) = context.dataStore.edit {
        it[Keys.lyricsPriority] = order.joinToString("|")
    }

    suspend fun setDownloadTranslation(value: Boolean) = context.dataStore.edit { it[Keys.downloadTranslation] = value }
    suspend fun setFallbackLyrics(value: FallbackLyrics) = context.dataStore.edit { it[Keys.fallbackLyrics] = value.key }
    suspend fun setFallbackDir(value: String) = context.dataStore.edit { it[Keys.fallbackDir] = value }
    suspend fun setCollectAll(value: Boolean) = context.dataStore.edit { it[Keys.collectAll] = value }
    suspend fun setConcurrency(value: Int) = context.dataStore.edit { it[Keys.concurrency] = value.coerceIn(1, 4) }

    suspend fun setWriteEmbedded(value: Boolean) = context.dataStore.edit { it[Keys.writeEmbedded] = value }
    suspend fun setWriteExternal(value: Boolean) = context.dataStore.edit { it[Keys.writeExternal] = value }
    suspend fun setImageMode(mode: ImageMode) = context.dataStore.edit { it[Keys.imageMode] = mode.name }
    suspend fun setImageQuality(value: Int) = context.dataStore.edit { it[Keys.imageQuality] = value.coerceIn(1, 100) }
    suspend fun setKeepModTime(value: Boolean) = context.dataStore.edit { it[Keys.keepModTime] = value }

    suspend fun setDebugLogging(value: Boolean) = context.dataStore.edit { it[Keys.debugLogging] = value }
    suspend fun setDebugLevel(level: LogLevel) = context.dataStore.edit { it[Keys.debugLevel] = level.name }

    suspend fun setSkipSilentIntro(value: Boolean) = context.dataStore.edit { it[Keys.skipSilentIntro] = value }
    suspend fun setLibraryTab(value: String) = context.dataStore.edit { it[Keys.libraryTab] = value }
    suspend fun setSearchFilter(value: String) = context.dataStore.edit { it[Keys.searchFilter] = value }
    suspend fun setSongsAsGrid(value: Boolean) = context.dataStore.edit { it[Keys.songsAsGrid] = value }
    suspend fun setArtistsAsGrid(value: Boolean) = context.dataStore.edit { it[Keys.artistsAsGrid] = value }
    suspend fun setGenresAsGrid(value: Boolean) = context.dataStore.edit { it[Keys.genresAsGrid] = value }

    /** 每行一个前缀，按原样存（空行 = 直连，是有意义的一项）。 */
    suspend fun setMirrorPrefixes(prefixes: List<String>) = context.dataStore.edit {
        it[Keys.mirrorPrefixes] = prefixes.joinToString("\n")
    }

    suspend fun setPreferredMirror(value: String) =
        context.dataStore.edit { it[Keys.preferredMirror] = value }

    suspend fun setLastWorkingUrl(value: String) =
        context.dataStore.edit { it[Keys.lastWorkingUrl] = value }

    /**
     * 记下这次从订阅仓库装好的那个合集包（见 [repoBundleSha256] / [repoBundleMd5]）。
     *
     * **只有整包校验通过、而且插件/规则/图标都装上了才写**：不然下次点「更新订阅」
     * 会以为"已经是最新"，缺的那些永远补不上。
     */
    suspend fun setRepoBundle(sha256: String, md5: String = "") = context.dataStore.edit {
        it[Keys.repoBundleSha256] = sha256
        it[Keys.repoBundleMd5] = md5
    }

    suspend fun setDebugMode(value: Boolean) = context.dataStore.edit { it[Keys.debugMode] = value }

    suspend fun setAutoExcludeDone(value: Boolean) =
        context.dataStore.edit { it[Keys.autoExcludeDone] = value }

    suspend fun setPluginRepoUrl(value: String) =
        context.dataStore.edit { it[Keys.pluginRepoUrl] = value }

    /** 记下 index.json 里的仓库名；换仓库时由调用方清空（见 SettingsViewModel.saveRepoUrl）。 */
    suspend fun setPluginRepoName(value: String) =
        context.dataStore.edit { it[Keys.pluginRepoName] = value }

    suspend fun setLibrarySource(value: String) =
        context.dataStore.edit { it[Keys.librarySource] = value }

    // ---------------- 远程控制 ----------------

    suspend fun setRemoteEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteEnabled] = value }

    /**
     * 主机地址。存之前统一 trim 并剥掉可能被粘进来的 scheme/路径/端口，
     * 见 [RemoteControlPrefs.sanitizeHost]。
     */
    suspend fun setRemoteHost(value: String) =
        context.dataStore.edit { it[Keys.remoteHost] = RemoteControlPrefs.sanitizeHost(value) }

    suspend fun setRemotePort(value: Int) = context.dataStore.edit {
        it[Keys.remotePort] = value.coerceIn(1, 65535)
    }

    suspend fun setRemoteAutoConnect(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteAutoConnect] = value }

    suspend fun setRemoteHeartbeat(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteHeartbeat] = value }

    suspend fun setRemoteTls(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteTls] = value }

    suspend fun setRemoteToken(value: String) =
        context.dataStore.edit { it[Keys.remoteToken] = value.trim() }

    suspend fun setRemoteSyncLocalAudio(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteSyncLocalAudio] = value }

    suspend fun setRemoteFixedLan(value: Boolean) =
        context.dataStore.edit { it[Keys.remoteFixedLan] = value }

    suspend fun setRemoteFixedLanHost(value: String) =
        context.dataStore.edit {
            it[Keys.remoteFixedLanHost] = RemoteControlPrefs.sanitizeHost(value)
        }

    // ---------------- 缓存 ----------------

    suspend fun setMetaScanEpoch(value: Int) = context.dataStore.edit { it[Keys.metaScanEpoch] = value }

    suspend fun setTabLayouts(value: String) = context.dataStore.edit { it[Keys.tabLayouts] = value }

    suspend fun markAutoExcludeKept(path: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.autoExcludeKeep].orEmpty()
            .split('\n').filter { it.isNotBlank() }.toMutableSet()
        current += path
        prefs[Keys.autoExcludeKeep] = current.joinToString("\n")
    }

    companion object {
        /**
         * APK 内置的"默认排除"目录名。
         *
         * 只按**文件夹名**匹配（不写死完整路径），因为用户的实际路径前缀
         * 各不相同。首次扫描时命中的目录会自动勾上排除，用户随时可以取消；
         * 取消过的那条以后不会再自动勾。
         */
        val BUILT_IN_EXCLUDE_NAMES = setOf(
            "Recordings",              // 系统录音机
            "recordings",
            "CallRecord", "callrecord", // 通话录音
            "Voice Recorder", "voice recorder",
            "Notifications", "notifications",
            "Alarms", "alarms",
            "Ringtones", "ringtones",
            "Android"
        )

        /**
         * 当前这一代「元信息」标签识别。
         *
         * 每次改了标签识别要覆盖的字段范围（或者像这次一样把识别并进曲库补全），
         * 就把这个数 +1：老库会在下次启动时把索引标记整体清零一次，
         * 后台按新流程重新过一遍所有曲目。**只做一次**，不会反复重扫。
         *
         * 2：标准标签改成一枚"连值一起判"（编码函数库写了 baimusic 这类第三方名字
         *    时要列出来），并补上 MP4 原子名 —— 老库里这些标签没被收进来过，
         *    所以整体重扫一次。
         */
        const val META_SCAN_EPOCH = 2

        /** 曲库来源：固定文件夹。 */
        const val SOURCE_FOLDERS = "FOLDERS"

        /** 曲库来源：Android 媒体库。 */
        const val SOURCE_MEDIA_STORE = "MEDIA_STORE"

        /**
         * 默认的镜像表：内置的 GitHub 中转，顺序就是尝试顺序。
         *
         * **不写直连**：候选里原地址永远第一个试，老版本那个空串只是个占位符。
         * 表里的顺序会被「设置 - 插件 - 网络与镜像」的「测试」按实测快慢重排。
         * 它们是**第三方中转**，内容有被改的可能，所以仓库公布 sha256 时会逐字校验。
         *
         * 2026-10 换掉了两个已经不能用的：
         * - `ghproxy.cn`：不管要哪个文件都回它自己的一张网页（实测 6990 字节，
         *   而 index.json 只有 4669 字节），对 Release 资产尤其如此；
         * - `gh.ddlc.top` / `hub.gitmirror.com`：限流或直接连不上。
         *
         * 换上去的四个都**实测过能转发 Release 资产**（订阅包就是从那下的）：
         * 现在的「更新订阅」第一步就是去 github.com 的 Release 拿那个 1.6 MB 的整包，
         * jsDelivr 服务不了 Release，只能靠这些中转兜底。
         */
        val DEFAULT_MIRROR_PREFIXES = listOf(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://hk.gh-proxy.com/",
            "https://gh.llkk.cc/",
            "https://cdn.gh-proxy.com/",
            "https://ghproxy.net/"
        )

        /**
         * 老版本的默认镜像表。
         *
         * 只用来判断"用户动过没有"：存下来的值正好是其中一份，说明从来没改过，
         * 就补成新的默认表（见 [com.audiometa.app.App]）。用户自己加过删过的一张都不动。
         */
        val LEGACY_MIRROR_PREFIXES = listOf(
            // 最早的两个。
            listOf("https://gh-proxy.com/", "https://ghfast.top/"),
            // 后来那六个（含已经不能用的 ghproxy.cn / gh.ddlc.top / hub.gitmirror.com）。
            listOf(
                "https://gh-proxy.com/",
                "https://ghproxy.cn/",
                "https://ghfast.top/",
                "https://gh.llkk.cc/",
                "https://gh.ddlc.top/",
                "https://hub.gitmirror.com/"
            )
        )
    }
}
