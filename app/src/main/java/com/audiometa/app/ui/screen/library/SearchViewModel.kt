package com.audiometa.app.ui.screen.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.media.audio.SilenceTarget
import com.audiometa.app.media.scan.ScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索维度。
 *
 * 顺序就是筛选按钮从左到右的顺序。默认选中**元信息**（用户要求）：
 * 这个搜索页现在的主要用途是查"哪首歌带某个自定义标签"——
 * 比如搜 `QMQUALITY` 找出所有 QQ 音乐来源的歌。
 *
 * 每次搜索**只查选中的那一项**——用户明确要求了这一点，
 * 免得搜"周杰伦"的时候一堆歌词里提到这三个字的结果混进来。
 */
enum class SearchFilter(val label: String, val hint: String) {
    META("元信息", "搜索自定义标签，如 QMQUALITY、kghash"),
    TITLE("标题", "搜索标题"),
    ARTIST("艺术家", "搜索艺术家"),
    ALBUM("专辑", "搜索专辑"),
    GENRE("流派", "搜索流派"),
    LYRICS("歌词", "搜索歌词");

    companion object {
        fun fromName(name: String?): SearchFilter =
            entries.firstOrNull { it.name == name } ?: META
    }
}

/**
 * 一条搜索结果。
 *
 * @param excerpt 命中说明：歌词搜索给歌词片段，元信息搜索给"键 值"的摘要。
 * @param score 排序权重，越大越靠前。元信息搜索用它实现"完全匹配优先"。
 */
data class SearchHit(
    val track: TrackEntity,
    val excerpt: String = "",
    val score: Int = 0
)

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.TITLE,
    val hits: List<SearchHit> = emptyList(),
    val searched: Boolean = false,
    /** 歌词缓存还没建完时为 true，界面提示"结果可能不全"。 */
    val lyricsIndexing: Boolean = false,
    val lyricsIndexMessage: String = "",
    val settings: AppSettings = AppSettings()
) {
    val hasQuery: Boolean get() = query.isNotBlank()
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.TITLE)

    init {
        viewModelScope.launch {
            val saved = ServiceLocator.settings.settings.first()
            filter.value = SearchFilter.fromName(saved.searchFilter)
        }
    }

    /** 防抖任务。每敲一个字都查一次数据库，中文输入法下会连查十几次。 */
    private var searchJob: Job? = null

    private val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    private val searched = MutableStateFlow(false)

    val state = combine(
        query,
        filter,
        hits,
        searched,
        ServiceLocator.settings.settings,
        ServiceLocator.indexer.progress
    ) { values ->
        val q = values[0] as String
        val f = values[1] as SearchFilter
        @Suppress("UNCHECKED_CAST")
        val result = values[2] as List<SearchHit>
        val done = values[3] as Boolean
        val settings = values[4] as AppSettings
        val indexProgress = values[5] as com.audiometa.app.media.scan.IndexProgress
        SearchUiState(
            query = q,
            filter = f,
            hits = result,
            searched = done,
            lyricsIndexing = indexProgress.running,
            lyricsIndexMessage = indexProgress.message,
            settings = settings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        query.value = value
        searchJob?.cancel()
        if (value.isBlank()) {
            hits.value = emptyList()
            searched.value = false
            return
        }
        searchJob = viewModelScope.launch {
            // 150ms 防抖：够快，连续输入时又不会每按一下都查一次。
            delay(150)
            runSearch(value, filter.value)
        }
    }

    fun setFilter(value: SearchFilter) {
        filter.value = value
        viewModelScope.launch { ServiceLocator.settings.setSearchFilter(value.name) }
        val q = query.value
        if (q.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(q, value) }
        }
        // 切到歌词时顺手触发一次后台增量刷新：
        // 缓存可能还没建完，用户点进来就开始补，边补边能搜到。
        if (value == SearchFilter.LYRICS) ensureLyricsIndex()
    }

    /**
     * 触发后台增量刷新缓存。
     *
     * 走 ScanService 的前台服务而不是直接开协程：用户切走应用时
     * 协程会被挂起，几百首歌要补很久；前台服务能一直跑完。
     */
    fun ensureLyricsIndex() {
        if (ServiceLocator.indexer.isRunning) return
        ScanService.enrich(getApplication())
    }

    private suspend fun runSearch(needle: String, filter: SearchFilter) {
        val trimmed = needle.trim()
        if (trimmed.isBlank()) {
            hits.value = emptyList()
            searched.value = false
            return
        }
        val result = withContext(Dispatchers.IO) {
            when (filter) {
                SearchFilter.LYRICS -> searchLyrics(trimmed)
                SearchFilter.META -> searchMeta(trimmed)
                else -> searchTracks(trimmed, filter)
            }
        }
        // 输入框内容可能在查询期间又变了，这一批就作废。
        if (query.value.trim() != trimmed || this.filter.value != filter) return
        hits.value = result
        searched.value = true
    }

    /**
     * 搜自定义标签（元信息维度）。
     *
     * 读的是**文件里的标签**，不是数据库——自定义标签没有入库，
     * 只有 `TagReader.readAllTags` 才拿得到。
     *
     * 排序按"匹配度"（用户要求）：
     *   100  键名**完全相同**（搜 QMQUALITY 命中键 QMQUALITY）
     *    80  键名以查询词开头（QMQUALITY 命中 QMQUALITY_EXTRA）
     *    60  键名包含查询词
     *    40  值完全等于查询词
     *    20  值包含查询词
     * 同分时按键名短的排前面，这样 `QMQUALITY` 一定排在 `QMQUALITY 1` 前面。
     */
    private suspend fun searchMeta(needle: String): List<SearchHit> {
        val all = ServiceLocator.tracks.allOnce()
        val reader = ServiceLocator.tagReader
        val out = mutableListOf<SearchHit>()
        all.forEach { track ->
            val file = java.io.File(track.path)
            if (!file.isFile) return@forEach
            val raw = runCatching { reader.readAllTags(file) }.getOrDefault(emptyMap())
            if (raw.isEmpty()) return@forEach

            var best = 0
            var bestKey = ""
            var bestValue = ""
            raw.forEach { (key, value) ->
                val score = metaScore(key, value, needle)
                if (score > best) {
                    best = score
                    bestKey = key
                    bestValue = value
                }
            }
            if (best > 0) {
                out += SearchHit(
                    track = track,
                    excerpt = bestKey + " = " + bestValue.take(60),
                    score = best
                )
            }
        }
        return out.sortedWith(
            compareByDescending<SearchHit> { it.score }
                .thenBy { it.excerpt.length }
                .thenBy { it.track.title.lowercase() }
        )
    }

    /** 单条标签的匹配分。0 表示不匹配。 */
    private fun metaScore(key: String, value: String, needle: String): Int {
        val k = key.lowercase()
        val n = needle.lowercase()
        return when {
            k == n -> 100
            k.startsWith(n) -> 80
            k.contains(n) -> 60
            value.equals(needle, true) -> 40
            value.contains(needle, true) -> 20
            else -> 0
        }
    }

    private suspend fun searchTracks(needle: String, filter: SearchFilter): List<SearchHit> {
        val all = ServiceLocator.tracks.allOnce()
        return all.filter { track ->
            when (filter) {
                SearchFilter.TITLE -> track.title.contains(needle, true)
                SearchFilter.ARTIST -> track.artist.contains(needle, true) ||
                    track.albumArtist.contains(needle, true)
                SearchFilter.ALBUM -> track.album.contains(needle, true)
                // 曲风是"任一命中"：一首歌可能有多个曲风。
                SearchFilter.GENRE -> track.genreList.any { it.contains(needle, true) }
                SearchFilter.LYRICS, SearchFilter.META -> false
            }
        }.map { SearchHit(it) }
    }

    /**
     * 歌词搜索：查缓存表，不读文件。
     *
     * 这是"搜索要快"的关键。几百首歌如果每次都去解析标签，一次搜索要好几秒；
     * 缓存表里只有正文，LIKE 扫一遍是毫秒级。
     */
    private suspend fun searchLyrics(needle: String): List<SearchHit> {
        val cached = ServiceLocator.lyricsCache.search(needle)
        if (cached.isEmpty()) {
            // 缓存里没有：可能是还没补全，也可能确实没有。
            // 顺手触发一次增量刷新，用户过一会儿再搜就有了。
            ensureLyricsIndex()
            return emptyList()
        }
        val byId = ServiceLocator.tracks.allOnce().associateBy { it.id }
        return cached.mapNotNull { entry ->
            val track = byId[entry.trackId] ?: return@mapNotNull null
            SearchHit(track, excerptOf(entry.lyrics.ifBlank { entry.translation }, needle))
        }
    }

    /** 截取命中位置前后的一小段，让用户知道这条为什么被搜出来。 */
    private fun excerptOf(text: String, needle: String, radius: Int = 24): String {
        val index = text.indexOf(needle, ignoreCase = true)
        if (index < 0) return ""
        val from = (index - radius).coerceAtLeast(0)
        val to = (index + needle.length + radius).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return prefix + text.substring(from, to).replace('\n', ' ').trim() + suffix
    }

    /** 让搜索页也能发起静音预计算（用户从搜索页点进详情页时马上就有跳过头）。 */
    fun ensureSilenceProfiles() {
        if (ServiceLocator.silenceIndexer.isRunning) return
        viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) {
                ServiceLocator.tracks.silenceTargets().map {
                    SilenceTarget(it.id, it.path, it.title)
                }
            }
            ServiceLocator.silenceIndexer.refresh(targets)
        }
    }
}
