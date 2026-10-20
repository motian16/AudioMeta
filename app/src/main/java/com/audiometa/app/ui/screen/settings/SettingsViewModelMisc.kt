package com.audiometa.app.ui.screen.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.CornerStyle
import com.audiometa.app.domain.model.ImageMode
import com.audiometa.app.domain.model.ThemeMode
import com.audiometa.app.domain.model.ThemePreset
import com.audiometa.app.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 调试模式开关，控制只给排查问题用的项是否出现。 */
internal fun SettingsViewModel.setDebugMode(value: Boolean) = edit { ServiceLocator.settings.setDebugMode(value) }

internal fun SettingsViewModel.setQuery(value: String) {
    query.value = value
}

internal fun SettingsViewModel.openSection(value: SettingsSection?) {
    section.value = value
    if (value != null) query.value = ""
}

/**
 * 同步打开某一页（在组合期间调用）。
 *
 * 和 [openSection] 一样，只是名字点明"这是在组合里同步设值"，
 * 让调用方知道它必须发生在首帧之前。跳转相关的状态一并清掉，
 * 避免出现"分类换了但缓存页还开着"的中间态。
 */
internal fun SettingsViewModel.openSectionNow(value: SettingsSection) {
    cachePage = null
    subfolderRoot = null
    section.value = value
    query.value = ""
}

internal fun SettingsViewModel.setDynamicColor(value: Boolean) = edit { ServiceLocator.settings.setDynamicColor(value) }
internal fun SettingsViewModel.setThemeMode(mode: ThemeMode) = edit { ServiceLocator.settings.setThemeMode(mode) }
internal fun SettingsViewModel.setThemePreset(preset: ThemePreset) = edit { ServiceLocator.settings.setThemePreset(preset) }
internal fun SettingsViewModel.setCornerStyle(style: CornerStyle) = edit { ServiceLocator.settings.setCornerStyle(style) }

internal fun SettingsViewModel.setCustomPrimary(value: String) = edit { ServiceLocator.settings.setCustomPrimary(value) }
internal fun SettingsViewModel.setCustomBackground(value: String) = edit { ServiceLocator.settings.setCustomBackground(value) }
internal fun SettingsViewModel.setCustomSurface(value: String) = edit { ServiceLocator.settings.setCustomSurface(value) }

internal fun SettingsViewModel.setCollectAll(value: Boolean) = edit { ServiceLocator.settings.setCollectAll(value) }
internal fun SettingsViewModel.setConcurrency(value: Int) = edit { ServiceLocator.settings.setConcurrency(value) }

internal fun SettingsViewModel.setWriteEmbedded(value: Boolean) = edit { ServiceLocator.settings.setWriteEmbedded(value) }
internal fun SettingsViewModel.setWriteExternal(value: Boolean) = edit { ServiceLocator.settings.setWriteExternal(value) }
internal fun SettingsViewModel.setImageMode(mode: ImageMode) = edit { ServiceLocator.settings.setImageMode(mode) }
internal fun SettingsViewModel.setImageQuality(value: Int) = edit { ServiceLocator.settings.setImageQuality(value) }
internal fun SettingsViewModel.setKeepModTime(value: Boolean) = edit { ServiceLocator.settings.setKeepModTime(value) }

internal fun SettingsViewModel.setDebugLogging(value: Boolean) = edit { ServiceLocator.settings.setDebugLogging(value) }
internal fun SettingsViewModel.setDebugLevel(level: LogLevel) = edit { ServiceLocator.settings.setDebugLevel(level) }

/**
 * 刷新所有缓存文件。
 *
 * 一次把三类都做掉，而不是让用户逐个找：
 * - 缩略图：全删，下次显示时重新解码；
 * - 歌词搜索缓存：全清 + 把 `lyricsIndexedAt` 归零（后台会重新增量建）；
 * - 开头静音档案：全清 + 后台重新预计算。
 *
 * 缩略图只有**专辑封面**这一类：艺术家图不落盘（见 `ThumbnailCache` 的说明），
 * 它没有目录要重建，清掉内存里的解码结果就够 —— 下一次显示时会重新读音频文件，
 * 也就是"实时"。内存那份也要清，否则界面上还挂着旧的解码结果，
 * 和"缓存已清空"的提示对不上。
 *
 * 之所以"刷新缓存"和"重新扫描"要分开：前者不动曲目索引，几秒钟的事；
 * 后者要重新遍历整个目录。用户想修"封面不对/歌词搜不到"时，
 * 需要的通常只是前者。
 */
internal fun SettingsViewModel.refreshAllCaches() {
    viewModelScope.launch {
        val context = getApplication<Application>()
        withContext(Dispatchers.IO) {
            com.audiometa.app.media.image.ThumbnailCache.rebuild(context)
            com.audiometa.app.media.audio.SilenceProfileStore.clear(context)
            ServiceLocator.lyricsCache.clear()
            ServiceLocator.tracks.resetLyricsIndex()
        }
        com.audiometa.app.ui.component.evictCoverMemoryCache()
        cacheMessage.value = "缓存已清空，正在后台重建"
        // 重建走前台服务，切走应用也能跑完。
        runCatching { com.audiometa.app.media.scan.ScanService.enrich(context) }
    }
}

/** 只重跑后台补全（不清已有的缓存），用于"新加了歌但歌词/静音没算上"。 */
internal fun SettingsViewModel.reindexBackground() {
    val context = getApplication<Application>()
    cacheMessage.value = "已在后台开始补全"
    runCatching { com.audiometa.app.media.scan.ScanService.enrich(context) }
}

internal fun SettingsViewModel.dismissCacheMessage() {
    cacheMessage.value = ""
}

// ---------------- 缓存 ----------------

internal fun SettingsViewModel.openCachePage() {
    cachePage = CachePage.LIST
    loadCacheStats()
}

internal fun SettingsViewModel.closeCachePage() {
    cachePage = null
    cacheMessage.value = ""
}

/** 重算各缓存的大小 / 条数。 */
internal fun SettingsViewModel.loadCacheStats() {
    viewModelScope.launch {
        cacheStatsLoading = true
        val context = getApplication<Application>()
        val stats = withContext(Dispatchers.IO) {
            val covers = com.audiometa.app.media.image.ThumbnailCache.stats(context)
            CacheStats(
                audioCoverFiles = covers.files,
                audioCoverBytes = covers.bytes,
                // 这里不再统计"艺术家缩略图"：艺术家图已经不做磁盘缓存
                // （只生成专辑封面缩略图，艺术家图实时读音频里的数据，见 ThumbnailCache）。
                // 老代码会去统计 artist_images/ 目录，那个目录现在恒为空，
                // 填进界面就是一个永远不动的 0 —— 比不显示更容易让人误会。
                lyricsRows = ServiceLocator.lyricsCache.count(),
                lyricsBytes = ServiceLocator.lyricsCache.totalBytes(),
                silenceProfiles = com.audiometa.app.media.audio.SilenceProfileStore.size(context),
                silenceBytes = com.audiometa.app.media.audio.SilenceProfileStore.bytes(context),
                trackCount = ServiceLocator.tracks.count()
            )
        }
        cacheStats = stats
        cacheStatsLoading = false
    }
}

/**
 * 刷新某一种缓存 —— **增量**，不是清空。
 *
 * 用户明确要求："这里面的刷新目前是清空缓存，而不是增量刷新数据，
 * 全量刷新数据是设置音乐目录的重新扫描"。所以这里只做两件事：
 *
 * 1. **清掉失效项**（孤立文件、已经不存在的曲目留下的行）；
 * 2. **补上缺的**（把还没索引过的交给后台增量补全）。
 *
 * 有效的缓存一律原样留着 —— 全量重建是「设置 - 音乐库 - 重新扫描」的事。
 */
internal fun SettingsViewModel.refreshCache(kind: CacheKind) {
    val context = getApplication<Application>()
    viewModelScope.launch {
        val label = withContext(Dispatchers.IO) {
            when (kind) {
                CacheKind.AUDIO_COVERS -> {
                    val valid = ServiceLocator.tracks.allOnce()
                        .mapTo(HashSet()) { track ->
                            com.audiometa.app.media.image.ThumbnailCache.audioCoverKey(
                                track.path, track.updatedAt, track.lastModified, track.fileSize
                            )
                        }
                    val removed = com.audiometa.app.media.image.ThumbnailCache
                        .pruneOrphans(context, valid)
                    "专辑封面：清理 $removed 个失效缩略图，其余保留"
                }
                CacheKind.ARTIST_IMAGES -> {
                    /*
                     * 艺术家图现在没有磁盘缓存，因此这一类"没有失效缩略图可清"。
                     *
                     * 以前这里算一遍有效 key、再 prune 掉 artist_images/ 里的孤儿文件；
                     * 那个目录已经不再产生（启动时还会被 ThumbnailCache.maintain() 删掉），
                     * 再清一遍只会稳定返回 0。
                     *
                     * 这个按钮仍然有意义：清掉内存里的解码结果，下一次组合就会
                     * 重新从音频文件里读 —— 用户点"刷新"想要的正是这个效果
                     * （比如刚在文件管理器里换过外置 .artist.jpg）。
                     * 内存清理在下面统一做（evictCoverMemoryCache）。
                     */
                    "艺术家图片：实时读取、不落盘，已清内存缓存（下次显示时重新读取）"
                }
                CacheKind.LYRICS -> {
                    // 增量：只处理还没进过索引的曲目，不重置全库。
                    val pending = ServiceLocator.tracks.needingLyricsIndex().size
                    var removedRows = 0
                    ServiceLocator.lyricsCache.pruneTo(
                        ServiceLocator.tracks.idPathPairs().map { it.id }
                    )
                    com.audiometa.app.media.scan.ScanService.enrich(context)
                    "歌词缓存：已清理失效行，$pending 首待补全（后台继续）"
                }
                CacheKind.SILENCE -> {
                    val targets = ServiceLocator.tracks.silenceTargets().map {
                        com.audiometa.app.media.audio.SilenceTarget(it.id, it.path, it.title)
                    }
                    val missing = targets.count {
                        !com.audiometa.app.media.audio.SilenceProfileStore.get(context, it.id).analyzed
                    }
                    com.audiometa.app.media.audio.SilenceProfileStore
                        .prune(context, targets.map { it.id }.toSet())
                    com.audiometa.app.media.scan.ScanService.enrich(context)
                    "开头静音档案：$missing 首待重算（后台继续）"
                }
                CacheKind.ORPHANS -> {
                    // 一次性把所有"没人引用的东西"清掉：孤立封面缩略图、
                    // 孤立元信息图片、孤立缓存行、临时文件。
                    val trackIds = ServiceLocator.tracks.idPathPairs().map { it.id }
                    val tracks = ServiceLocator.tracks.allOnce()

                    val coverKeys = tracks.mapTo(HashSet()) { track ->
                        com.audiometa.app.media.image.ThumbnailCache.audioCoverKey(
                            track.path, track.updatedAt, track.lastModified, track.fileSize
                        )
                    }
                    val covers = com.audiometa.app.media.image.ThumbnailCache
                        .pruneOrphans(context, coverKeys)
                    // 这里不再清"艺术家缩略图"：艺术家图不落盘（见 ThumbnailCache），
                    // 磁盘上没有对应文件，旧代码那个统计值恒为 0。

                    ServiceLocator.lyricsCache.pruneTo(trackIds)
                    ServiceLocator.metaTags.pruneTo(trackIds)
                    com.audiometa.app.media.audio.SilenceProfileStore.prune(context, trackIds.toSet())

                    val images = com.audiometa.app.media.tag.MetaImageStore.prune(
                        context,
                        ServiceLocator.metaTags.all()
                            .map { it.value }
                            .filter { com.audiometa.app.media.tag.MetaImageStore.isImageValue(it) }
                            .map { com.audiometa.app.media.tag.MetaImageStore.fileNameOf(it) }
                            .toSet()
                    )
                    val tempDir = com.audiometa.app.util.StoragePaths.tempDir(context)
                    val tempFiles = tempDir.listFiles()?.count { it.delete() } ?: 0
                    "孤立数据已清理：封面缩略图 " + covers +
                        " 个、元信息图片 " + images + " 个、临时文件 " + tempFiles + " 个"
                }
            }
        }
        /*
         * 图片类要连带清掉内存里的解码结果，否则当前界面还显示旧图。
         *
         * 对 ARTIST_IMAGES 来说这一步就是它的**全部**清理动作：艺术家图不落盘，
         * 内存 LRU 是它唯一的缓存，清掉之后下一次组合会重新从音频文件里解出来。
         */
        if (kind == CacheKind.AUDIO_COVERS || kind == CacheKind.ARTIST_IMAGES ||
            kind == CacheKind.ORPHANS
        ) {
            com.audiometa.app.ui.component.evictCoverMemoryCache()
        }
        cacheMessage.value = label
        loadCacheStats()
    }
}

/** 缓存页面的状态：列表 / 某一项详情（先简化成一页）。 */
enum class CachePage { LIST }

/**
 * 可单独刷新的缓存种类。
 *
 * 每一类都只有一个「刷新」按钮：刷新是**增量**的，只清失效项、补缺的，
 * 有效的缓存原样保留，不提供清空（清空后不重建，界面会一直显示占位图）。
 *
 * [ARTIST_IMAGES] 是个特例：艺术家图没有磁盘缓存，"刷新"对它等于清一次内存里的
 * 解码结果，下一次显示时重新读音频。枚举成员**刻意保留**（不删），
 * 免得改动别处引用它的文件。
 */
enum class CacheKind(val title: String) {
    /** 音频（专辑）封面缩略图 —— 现在唯一会落盘的一类。 */
    AUDIO_COVERS("专辑封面"),
    /** 艺术家图片：实时读音频里的数据，不生成磁盘缩略图。 */
    ARTIST_IMAGES("艺术家图片"),
    /** 按歌词搜索用的正文缓存。 */
    LYRICS("歌词缓存"),
    /** 开头静音跳过时间的预计算结果。 */
    SILENCE("开头静音档案"),
    /** 孤立数据：没人引用的缩略图、图片、缓存行与临时文件。 */
    ORPHANS("孤立数据")
}

/**
 * 缓存统计。
 *
 * 顶部总计只报曲库有多少首歌：缩略图文件数、歌词行数、静音档案数
 * 口径不同（文件数 / 行数 / 条目数），加起来没有意义。
 *
 * 缩略图只有**专辑封面**这一项：艺术家图不落盘（见 `ThumbnailCache`），
 * 所以这里没有"艺术家缩略图文件数"字段 —— 留着它只会是个恒为 0 的数字。
 */
data class CacheStats(
    val audioCoverFiles: Int = 0,
    val audioCoverBytes: Long = 0L,
    val lyricsRows: Int = 0,
    val lyricsBytes: Long = 0L,
    val silenceProfiles: Int = 0,
    val silenceBytes: Long = 0L,
    val trackCount: Int = 0
)
