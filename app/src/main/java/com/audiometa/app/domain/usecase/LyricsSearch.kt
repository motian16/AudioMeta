package com.audiometa.app.domain.usecase

import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.domain.model.lacksBasics
import com.audiometa.app.media.lyrics.LyricsLanguage
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.plugin.model.InstalledPlugin
import com.audiometa.app.plugin.model.PluginFunction
import com.audiometa.app.plugin.runtime.PluginRuntime
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 一条歌词候选。
 *
 * 一个源可能同时给出"逐字"和"逐行"两条 —— 所以候选是按 (源, 歌词类型) 铺开的，
 * 不是一个源一条。用户要的列表就是这样：每行一首歌的信息 + 歌词类型标签
 * （逐字 / 翻译、逐行 / 翻译）。
 */
data class LyricsCandidate(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val artist: String,
    val album: String,
    /** 这个文件对应的曲目路径，用来在列表左侧画封面。 */
    val audioPath: String,
    /** 服务器时间戳，用来让详情页的封面刷新。 */
    val stamp: Long = 0L,
    /** "word" 逐字 / "line" 逐行。 */
    val type: String,
    /** 带不带翻译。 */
    val hasTranslation: Boolean,
    val lyrics: String,
    val translation: String,
    /** 匹配度，越高越靠前。 */
    val score: Double
) {
    /** 列表右侧的类型标签。 */
    val typeLabel: String
        get() = com.audiometa.app.domain.model.lyricsTypeLabel(type) +
            if (hasTranslation) " / 翻译" else ""

    val isEmpty: Boolean get() = lyrics.isBlank()

    val wordLevel: Boolean get() = type == "word"
}

/**
 * 一次歌词搜索的结果。
 *
 * 除了候选，还带上"为什么没有结果"的实情 —— 界面以前只有一句
 * "可能是没有启用带 getLyrics 能力的插件源"，用户根本没法判断到底是哪种情况：
 * 一个源都没有？源都问了但都没这首歌？还是源调用直接报错了。
 *
 * @param sources 这次要问的源名（已启用 + 声明了 getLyrics）。
 *   **必须是插件解析后的名字**：manifest 里常写 `@plugin.name`，
 *   直接拿 manifest.name 显示出来就是用户看到的那个"占位符"。
 * @param failures 每个失败源的原因（源名 → 一句话），超时/异常都在里面。
 * @param finished 全部源都回来了。搜索是**边搜边报**的：每回来一个源就推一次，
 *   界面因此不用等最慢的那个（Apple 第一次要先抓 token，要好几秒）。
 */
data class LyricsSearchResult(
    val candidates: List<LyricsCandidate> = emptyList(),
    val sources: List<String> = emptyList(),
    val failures: List<Pair<String, String>> = emptyList(),
    val finished: Boolean = false
)

/**
 * 歌词搜索。
 *
 * 和元信息/艺术家图不同，歌词**不需要**按基础标签分组去比对 ——
 * 歌词本身就是文本，用户看一眼就知道对不对，所以直接把各源的结果铺成一张列表。
 *
 * 排序（用户指定）：**默认逐字+翻译优先**。匹配度先分粗档：
 * 先比"是不是同一首歌"，再比类型偏好 —— 否则一首错歌只要带逐字翻译，
 * 就会排到正确那首前面。
 */
class LyricsSearch {

    suspend fun search(trackId: Long): List<LyricsCandidate> = searchResult(trackId).candidates

    /** 全部源都回来之后的最终结果。 */
    suspend fun searchResult(trackId: Long): LyricsSearchResult = searchStream(trackId).last()

    /**
     * 边搜边报：每个源一回来就推一次。
     *
     * 四个源本来就是**并发**跑的（一条 async 一个源），但快慢差很多：
     * Apple 第一次要先抓开发者 token（要下几 MB），QQ 要签名 + 两次请求。
     * 等齐再显示，用户就得盯着转圈好几秒 —— "速度很慢"主要来自这里。
     * 现在先推"要搜哪几个源"，谁回来就把谁的候选加上去。
     */
    fun searchStream(trackId: Long): Flow<LyricsSearchResult> = channelFlow {
        val prepared = withContext(Dispatchers.IO) { prepare(trackId) } ?: return@channelFlow
        // 【死锁】三项基础数据一项都没有：不问任何一个源，直接把原因推给界面。
        if (prepared.existing.lacksBasics()) {
            trySend(
                LyricsSearchResult(
                    sources = prepared.names,
                    failures = listOf(
                        "基础标签" to "音频内没有标题/艺术家/专辑，不进行网络搜索（请在详情页手动补全）"
                    ),
                    finished = true
                )
            )
            return@channelFlow
        }
        trySend(LyricsSearchResult(sources = prepared.names))
        if (prepared.plugins.isEmpty()) {
            trySend(LyricsSearchResult(sources = prepared.names, finished = true))
            return@channelFlow
        }

        // 多个源并发写累加器，读改写和推送都要保证线程安全。
        val lock = Any()
        val candidates = mutableListOf<LyricsCandidate>()
        val failures = mutableListOf<Pair<String, String>>()
        var completed = 0

        coroutineScope {
            // 每个源一条 async：**并发搜**，谁先回来谁先推。
            prepared.plugins.map { plugin ->
                async(Dispatchers.IO) {
                    val outcome = runOne(plugin, prepared)
                    val snapshot = synchronized(lock) {
                        candidates += outcome.candidates
                        outcome.failure?.let { failures += it }
                        completed++
                        LyricsSearchResult(
                            candidates = sortCandidates(candidates, prepared.rankOf),
                            sources = prepared.names,
                            failures = failures.toList(),
                            finished = completed == prepared.plugins.size
                        )
                    }
                    trySend(snapshot)
                }
            }.awaitAll()
        }
    }

    /** 并发搜索需要的上下文（读文件、挑源、算关键词）。 */
    private class Prepared(
        val plugins: List<InstalledPlugin>,
        val names: List<String>,
        val rankOf: Map<String, Int>,
        val track: TrackEntity,
        val existing: TrackMeta,
        val want: TrackMatcher.Want,
        val fileName: String,
        val locale: String,
        val runtime: PluginRuntime
    )

    private suspend fun prepare(trackId: Long): Prepared? {
        val locator = ServiceLocator
        val track = locator.tracks.getById(trackId) ?: return null
        val file = File(track.path)
        if (!file.exists()) return null
        val existing = runCatching { TagReader().read(file) }.getOrDefault(TrackMeta())
        val settings = locator.settings.settings.first()

        val plugins = locator.plugins.loadEnabled()
            .filter { ScrapeCapability.LYRICS in it.manifest.scrapeCapabilities() }
        // 源顺序：设置里的歌词优先级；没排到的接在后面。
        val configured = settings.sourcePriority[PriorityKey.LYRICS].orEmpty()
        val ranked = configured.mapNotNull { id -> plugins.firstOrNull { it.pluginId == id } }
        val ordered = ranked + plugins.filter { p -> ranked.none { it.pluginId == p.pluginId } }

        return Prepared(
            plugins = ordered,
            // 插件**解析后**的名字（i18n）：manifest 里写的是 `@plugin.name`。
            names = ordered.map { it.name },
            rankOf = ordered.withIndex().associate { (index, plugin) -> plugin.pluginId to index },
            track = track,
            existing = existing,
            want = TrackMatcher.Want(
                title = existing.title.ifBlank { track.title },
                artist = existing.artist.ifBlank { track.artist },
                album = existing.album.ifBlank { track.album },
                fileName = file.name,
                durationMs = existing.durationMs
            ),
            fileName = file.name,
            locale = Locale.getDefault().toLanguageTag().ifBlank { "en" },
            runtime = locator.runtime
        )
    }

    /** 一个源的结果；失败时 [failure] 非空（界面会把它显示出来）。 */
    private class Outcome(
        val candidates: List<LyricsCandidate> = emptyList(),
        val failure: Pair<String, String>? = null
    )

    private suspend fun runOne(plugin: InstalledPlugin, prepared: Prepared): Outcome {
        val want = prepared.want
        val request = JSONObject()
            .put("keyword", TrackMatcher.buildQuery(want.title, want.artist, prepared.fileName))
            .put("title", want.title)
            .put("artist", want.artist)
            .put("album", want.album)
            .put("duration", want.durationMs)
            .put("config", JSONObject(plugin.configJson.ifBlank { "{}" }))
            .put(
                "song",
                JSONObject().put("id", "local-song").put("title", want.title)
                    .put("artist", want.artist).put("album", want.album)
                    .put("duration", want.durationMs)
            )
        var failure: String = ""
        val array = runCatching {
            withTimeout(SOURCE_TIMEOUT_MS) {
                prepared.runtime.asArray(
                    prepared.runtime.invoke(plugin, PluginFunction.GET_LYRICS, request, prepared.locale)
                )
            }
        }.onFailure { error ->
            // 以前这里静默吞掉：用户只看到"没有搜到歌词"。
            failure = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "超时（" + SOURCE_TIMEOUT_MS / 1000 + " 秒没返回）"
            } else {
                error.message ?: error.javaClass.simpleName
            }
            AppLog.w(TAG, plugin.name + " 执行 getLyrics 失败：" + failure)
        }.getOrNull() ?: return Outcome(failure = plugin.name to failure.ifBlank { "调用失败" })

        return Outcome(
            candidates = parse(plugin.pluginId, plugin.name, array, prepared.track, prepared.existing, want)
                .filter {
                    it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank() &&
                        it.title.trim().equals(want.title.trim(), ignoreCase = true) &&
                        it.album.trim().equals(want.album.trim(), ignoreCase = true) &&
                        com.audiometa.app.domain.model.ArtistNames.matches(it.artist, want.artist)
                }
        )
    }

    private fun sortCandidates(
        items: List<LyricsCandidate>,
        rankOf: Map<String, Int>
    ): List<LyricsCandidate> = items.sortedWith(
        // 1. 先比"是不是同一首歌"（匹配档）；2. 逐字+翻译优先；
        // 3. 同档同类型里匹配度高的在前；4. 最后按用户设置的源优先级。
        compareByDescending<LyricsCandidate> { LyricsRanking.scoreTier(it.score) }
            .thenBy { LyricsRanking.preference(it.wordLevel, it.hasTranslation) }
            .thenByDescending { it.score }
            .thenBy { rankOf[it.sourceId] ?: Int.MAX_VALUE }
    )

    private fun parse(
        pluginId: String,
        pluginName: String,
        array: org.json.JSONArray,
        track: TrackEntity,
        existing: TrackMeta,
        want: TrackMatcher.Want
    ): List<LyricsCandidate> {
        val out = mutableListOf<LyricsCandidate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            /*
             * 三种返回形状都交给 [com.audiometa.app.media.lyrics.LyricsPayload]：
             * 结构化（酷狗/Apple 的 original 数组）、TTML（Apple 的 rawTtml）、
             * 纯文本（QQ/网易的 lyrics）。以前只认最后一种，
             * 于是另外两种源的歌词被静默丢掉 —— 表现就是"每个源都搜不到歌词"。
             */
            val texts = com.audiometa.app.media.lyrics.LyricsPayload.parse(item) ?: continue
            if (texts.original.isBlank()) continue
            val tags = com.audiometa.app.media.lyrics.LyricsPayload.tagsOf(item)
            val title = item.optString("title").ifBlank { tags["ti"].orEmpty() }.ifBlank { want.title }
            val artist = item.optString("artist").ifBlank { tags["ar"].orEmpty() }.ifBlank { want.artist }
            val album = item.optString("album").ifBlank { tags["al"].orEmpty() }
            out += LyricsCandidate(
                sourceId = pluginId,
                sourceName = pluginName,
                title = title,
                artist = artist,
                album = album,
                audioPath = track.path,
                stamp = track.updatedAt,
                type = if (texts.wordLevel) "word" else "line",
                hasTranslation = texts.translation.isNotBlank(),
                lyrics = texts.original,
                translation = texts.translation,
                score = TrackMatcher.score(
                    TrackMatcher.Candidate(
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = 0L
                    ),
                    want
                )
            )
        }
        return out
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L

        /** 日志标签：源调用失败时记在它下面（以前这些失败是被静默吞掉的）。 */
        const val TAG = "LyricsSearch"
    }
}

/** 给列表用的语言判断：中文歌词不需要翻译。 */
fun lyricsNeedsTranslation(candidate: LyricsCandidate): Boolean =
    LyricsLanguage.needsTranslation(candidate.lyrics)
