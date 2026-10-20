package com.audiometa.app.di

import android.content.Context
import com.audiometa.app.data.db.AppDatabase
import com.audiometa.app.data.prefs.SelectionStore
import com.audiometa.app.data.prefs.SettingsStore
import com.audiometa.app.data.repo.ArtistRepository
import com.audiometa.app.data.repo.FolderRepository
import com.audiometa.app.data.repo.LogRepository
import com.audiometa.app.data.repo.LyricsCacheRepository
import com.audiometa.app.data.repo.PluginRepository
import com.audiometa.app.data.repo.TrackRepository
import com.audiometa.app.media.audio.SilenceIndexer
import com.audiometa.app.media.image.ImageEmbedder
import com.audiometa.app.media.lyrics.FallbackLyricsWriter
import com.audiometa.app.media.lyrics.LyricsMatcher
import com.audiometa.app.media.scan.LibraryIndexer
import com.audiometa.app.media.scan.MediaScanner
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.media.tag.TagWriter
import com.audiometa.app.plugin.install.PluginExporter
import com.audiometa.app.plugin.install.PluginInstaller
import com.audiometa.app.plugin.runtime.HostApi
import com.audiometa.app.plugin.runtime.PluginRuntime
import kotlinx.coroutines.launch

object ServiceLocator {
    lateinit var appContext: Context
        private set

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val selection: SelectionStore by lazy { SelectionStore() }
    val tracks: TrackRepository by lazy { TrackRepository(database) }
    val folders: FolderRepository by lazy { FolderRepository(database) }
    val artists: ArtistRepository by lazy { ArtistRepository(database) }
    val pluginRepo: PluginRepository by lazy { PluginRepository(database) }

    /** 记住过的 SPW 设备与地址（换网后不用重新扫描）。 */
    val remoteDevices: com.audiometa.app.data.repo.RemoteDeviceRepository by lazy {
        com.audiometa.app.data.repo.RemoteDeviceRepository(database)
    }

    val logs: LogRepository by lazy { LogRepository(database) }
    val lyricsCache: LyricsCacheRepository by lazy { LyricsCacheRepository(database) }

    /** 未知/自定义标签。结果落库，「元信息」页打开即可显示，不需要预扫描。 */
    val metaTags: com.audiometa.app.data.repo.MetaTagRepository by lazy {
        com.audiometa.app.data.repo.MetaTagRepository(database)
    }
    val tagReader: TagReader by lazy { TagReader() }
    val tagWriter: TagWriter by lazy { TagWriter() }
    val embedder: ImageEmbedder by lazy { ImageEmbedder(tagWriter) }
    val lyricsMatcher: LyricsMatcher by lazy { LyricsMatcher() }
    val fallbackLyrics: FallbackLyricsWriter by lazy { FallbackLyricsWriter() }

    val runtime: PluginRuntime by lazy {
        PluginRuntime { client, _ -> HostApi(appContext, client, appVersion, userAgent()) }
    }
    val plugins: PluginInstaller by lazy { PluginInstaller(appContext, pluginRepo, runtime, settings) }
    val exporter: PluginExporter by lazy { PluginExporter(appContext, pluginRepo) }
    val scanner: MediaScanner by lazy { MediaScanner(tracks, folders, tagReader) }

    /** 版本名，例如 1.1。「关于」页显示它。 */
    val versionName: String get() = appVersion

    /** 版本号（versionCode），显示成 1.1(4) 方便比对。 */
    val versionCode: Long
        get() = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(1L)

    /**
     * 扫描之后的后台补全：歌词正文、搜索缓存，以及「元信息」页的标签识别。
     *
     * 标签识别并进这里而不是单独跑一遍：用户要求"在音乐库扫描时同时按照规则
     * 扫描元信息页的数据"，而这里本来就把标签结构完整解析了一遍。
     */
    val indexer: LibraryIndexer by lazy { LibraryIndexer(tracks, lyricsCache, metaTags, tagReader) }

    /** 开头静音的预计算，排在歌词补全之后。 */
    val silenceIndexer: SilenceIndexer by lazy { SilenceIndexer(appContext) }

    /**
     * 远程控制用的**进程级**作用域。
     *
     * 连接、退避重连、心跳、配对码落库都挂在这里：它跟随进程，不跟随任何
     * Activity/ViewModel —— 用户切 Tab、进后台、关屏幕都不会把它取消。
     *
     * 声明在 [remoteControl] **之前**：后者在首次访问时就要用它，
     * 排在后面会让"属性还没初始化"这种时序问题变得可能。
     */
    private val remoteScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * 远程控制客户端：连 PC 上 Salt Player 的 SPW 插件，遥控播放。
     *
     * 全局单例：连接是进程级资源，界面切来切去不该反复建连断连。
     * 配置由 [com.audiometa.app.App] 或「控制」页推给它（见 [RemoteControlClient.apply]）。
     *
     * 它的**重连与心跳协程显式跑在进程级作用域上**（上面那个 [remoteScope]）：
     * 传界面/ViewModel 的作用域会让"App 进后台 → 界面销毁 → 再也没人重连"，
     * 那正是用户报的后台断连。
     */
    val remoteControl: com.audiometa.app.remote.RemoteControlClient by lazy {
        com.audiometa.app.remote.RemoteControlClient(
            onReconnectExhausted = {
                /*
                 * 连通过之后又反复掉线、指数退避重连也全部失败 —— 大概率是电脑
                 * 换 IP 了。这里只记一条日志：客户端**不会停止重连**（退避封顶后
                 * 一直按最大间隔重试），用户也可以从控制页的重连提示看出原因。
                 */
                com.audiometa.app.util.AppLog.i(
                    "RemoteControl",
                    "重连连续失败已达一轮，触发重新发现设备（重连会继续按最大间隔重试）"
                )
            },
            onTokenHandoff = { token ->
                // 局域网免配对拿到的配对码直接落 DataStore，重启后不再需要手打
                remoteScope.launch {
                    runCatching { settings.setRemoteToken(token) }
                        .onFailure {
                            com.audiometa.app.util.AppLog.w(
                                "RemoteControl",
                                "配对码落库失败：" + it.message
                            )
                        }
                }
            },
            parentScope = remoteScope
        )
    }

    /**
     * 本地播放器，专供「同步播放音频」用。
     *
     * 和曲库试听那份是**同一个类、独立实例**：遥控同步时不想打断
     * 用户正在曲库里试听的另一首歌。
     *
     * 用一个进程级的作用域：这个播放器的生命周期跟随进程，
     * 不该跟着某个界面走（用户切到别的 Tab 时它还要继续放）。
     *
     * `lowPriority = true`：同步播放是从属行为，**不申请音频焦点**，
     * 不会把其他应用正在放的声音压低；别的应用开始出声时通过
     * onExternalConflict 自动让位（关闭同步开关）。
     */
    val localPlayer: com.audiometa.app.media.audio.TrackPlayer by lazy {
        com.audiometa.app.media.audio.TrackPlayer(
            appContext,
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
            ),
            lowPriority = true
        )
    }

    /** 孤立数据清理：没人引用的缩略图、图片、缓存行、临时文件。 */
    val orphanCleaner: com.audiometa.app.media.scan.OrphanCleaner by lazy {
        com.audiometa.app.media.scan.OrphanCleaner(tracks, artists, lyricsCache, metaTags)
    }

    /**
     * 按音频路径查歌手名，带内存缓存。
     *
     * 艺术家图片的缓存键要绑"艺术家名"（同一位艺术家共一张图），
     * 而封面组件手上只有路径。查一次就记下来：曲目索引的歌手名在本次会话里
     * 不会变，重复查纯属浪费。
     *
     * 查不到返回 null，调用方退回用路径当键。
     */
    private val artistNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun artistNameForPath(path: String): String? {
        artistNameCache[path]?.let { return it.ifBlank { null } }
        val name = runCatching {
            kotlinx.coroutines.runBlocking { tracks.getByPath(path)?.artist?.trim() }.orEmpty()
        }.getOrDefault("")
        artistNameCache[path] = name
        return name.ifBlank { null }
    }

    /**
     * 版本名，直接读安装包的 manifest（build.gradle.kts 里的 versionName）。
     *
     * 这里**不再硬写版本号**：读不到（理论上不会发生）才退回当前版本的字面量，
     * 免得用户看到的版本和包里的对不上。
     */
    private val appVersion: String
        get() = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "1.2"

    private fun userAgent(): String = "AudioMeta/" + appVersion + " (Android)"

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
