package com.audiometa.app.domain.usecase

import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.domain.model.lacksBasics
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.util.AppLog
import com.audiometa.app.plugin.model.PluginFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 一条搜索结果，供用户在单首刮削时挑选。
 *
 * raw 保存插件返回的原始 JSON：选中后原样交回 ScrapeService，
 * 下游的封面/艺术家图/元信息/歌词解析全部复用，不用为"手动指定"再写一套。
 */
data class ScrapeCandidate(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val artist: String,
    val album: String,
    val score: Double,
    val previewUrl: String,
    val subtitle: String,
    val raw: String,
    /** 图片实际像素尺寸；拿不到时是 0。 */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
) {
    /** 右下角角标：300×300；拿不到就留空。 */
    val resolutionLabel: String
        get() = if (imageWidth > 0 && imageHeight > 0) imageWidth.toString() + "×" + imageHeight else ""
}

/**
 * 内部约定：用户在图格里点了哪张图，就把那张图的地址钉在候选 JSON 的这个键上。
 *
 * 为什么需要专门钉一个键：网格里显示的是 [ScrapeCandidate.previewUrl]（由 `CandidateSearch.previewOf`
 * 解析），而服务端写文件时是**另一套实现**（`ScrapeService.urlsOf`）从候选 JSON 里重新解析地址。
 * 两套解析只要有一点点差别（字段回退顺序不同、`imageUrls[0]` 是空串、插件换了字段名），
 * 就会出现"点的是一张、写进去的是另一张"，甚至解析不出地址而**静默退回自动搜索** ——
 * 用户看到的就是"手动选中后嵌入的却是排名第一的那个源的图"。
 * 把用户看到的那张钉进候选、服务端优先用它，"看到哪张就写哪张"就不用再指望两边解析永远一致。
 *
 * 这个键只在宿主内部流转（写进候选 JSON，随 intent 传到服务）：`MetaFields.fromSong`
 * 之类的解析只认自己那几个字段名，不认识它会直接忽略，所以不会污染元信息 / 歌词那条链路。
 */
const val PICKED_IMAGE_URL_KEY = "__picked_image_url"

/**
 * 把"用户在图格里看到的那张图"钉进候选 JSON，交给上层（`startWithCandidate`）带进服务。
 *
 * 以 [ScrapeCandidate.previewUrl] 为准而不是 `imageUrls[0]`：网格加载图片、探分辨率用的都是
 * previewUrl，钉它才是真正的"看到哪张就写哪张"。
 *
 * 地址为空（网格里显示的是占位图标，本来就没有图可点）或 JSON 解析不动时**原样返回** ——
 * 这时服务端仍按老规则自己解析，不会因为多了这一步反而写不进去。
 */
fun ScrapeCandidate.pinPickedImageUrl(): ScrapeCandidate {
    val url = previewUrl.trim()
    if (url.isBlank()) return this
    val json = runCatching { JSONObject(raw) }.getOrNull()
    if (json == null) {
        AppLog.w("CandidateSearch", "候选 JSON 解析失败，钉不进用户点选的图片地址：" + sourceId)
        return this
    }
    if (json.optString(PICKED_IMAGE_URL_KEY).isNotBlank()) return this
    runCatching { json.put(PICKED_IMAGE_URL_KEY, url) }.getOrNull() ?: return this
    return copy(raw = json.toString())
}

/** 只做搜索、不写文件。 */
class CandidateSearch {

    suspend fun search(trackId: Long, capability: ScrapeCapability): List<ScrapeCandidate> =
        withContext(Dispatchers.IO) {
            val locator = ServiceLocator
            val track = locator.tracks.getById(trackId) ?: return@withContext emptyList()
            val file = File(track.path)
            if (!file.exists()) return@withContext emptyList()
            val existing = runCatching { TagReader().read(file) }.getOrDefault(TrackMeta())
            // 【死锁】三项基础数据一项都没有：没有可用的搜索关键词，
            // 不向任何插件源发起搜索，直接返回空结果。
            if (existing.lacksBasics()) {
                AppLog.i("CandidateSearch", "trackId=" + trackId + " 音频内没有标题/艺术家/专辑基础数据，不进行网络搜索")
                return@withContext emptyList()
            }
            val settings = locator.settings.settings.first()
            val functionName = PluginFunction.of(capability) ?: return@withContext emptyList()
            val locale = Locale.getDefault().toLanguageTag().ifBlank { "en" }

            val plugins = locator.plugins.loadEnabled()
                .filter { capability in it.manifest.scrapeCapabilities() }
            if (plugins.isEmpty()) return@withContext emptyList()

            val key = when (capability) {
                ScrapeCapability.ARTIST_IMAGE -> PriorityKey.ARTIST_IMAGE
                ScrapeCapability.TRACK_META -> PriorityKey.TRACK_META
                // 曲风在 searchSongs 的结果里，和元信息共用一份源优先级。
                ScrapeCapability.GENRE -> PriorityKey.TRACK_META
                ScrapeCapability.LYRICS -> PriorityKey.LYRICS
                // 专辑图和元信息是同一批「按歌搜」的源，共用一份优先级，
                // 不为它单开一条设置（用户那边只有一条可拖动的源顺序就够）。
                ScrapeCapability.COVER -> PriorityKey.TRACK_META
            }
            val configured = settings.sourcePriority[key].orEmpty()
            // 显示顺序（拖动排序）与刮削优先级无关：这里只认标签规则里的 sourcePriority。
            val order = configured
            val ranked = order.mapNotNull { id -> plugins.firstOrNull { it.pluginId == id } }
            val ordered = ranked + plugins.filter { p -> ranked.none { it.pluginId == p.pluginId } }

            val want = TrackMatcher.Want(
                title = existing.title,
                artist = existing.artist,
                album = existing.album,
                fileName = file.name,
                durationMs = existing.durationMs
            )
            // 艺术家图是按艺术家搜的：关键词只用歌手名，合唱取**首位**艺术家。
            val primaryArtist = com.audiometa.app.domain.model.ArtistNames
                .of(existing.artist).firstOrNull()?.trim().orEmpty()
                .ifBlank { existing.artist }
            val artistWant = TrackMatcher.Want(
                title = primaryArtist,
                artist = primaryArtist,
                album = "",
                fileName = file.name,
                durationMs = 0L
            )

            val out = mutableListOf<ScrapeCandidate>()
            // 各源**并发**搜索。
            //
            // 之前这里是 ordered.forEach { ... }，一个源搜完才轮到下一个：
            // 有 5 个源、每个源 2 秒的话，挑封面要等 10 秒才出结果。
            // 和 ScrapeService.runSearches 一样改成 async + awaitAll，
            // 并且加了单源超时——某个源连不上时不该把整个列表拖住。
            val results = kotlinx.coroutines.coroutineScope {
                ordered.map { plugin ->
                    async(Dispatchers.IO) {
                        val array = runCatching {
                            val config = JSONObject(plugin.configJson.ifBlank { "{}" })
                            val song = JSONObject()
                                .put("id", "local-song")
                                .put("title", existing.title)
                                .put("artist", existing.artist)
                                .put("album", existing.album)
                                .put("duration", existing.durationMs)
                            val artistOnly = capability == ScrapeCapability.ARTIST_IMAGE
                            val request = JSONObject()
                                .put(
                                    "keyword",
                                    if (artistOnly) {
                                        primaryArtist
                                    } else {
                                        TrackMatcher.buildQuery(existing.title, existing.artist, file.name)
                                    }
                                )
                                .put("title", existing.title)
                                .put("artist", existing.artist)
                                .put("album", existing.album)
                                .put("page", 1)
                                .put("pageSize", 10)
                                .put("separator", "/")
                                .put("config", config)
                                .put("song", song)
                            kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                                locator.runtime.asArray(
                                    locator.runtime.invoke(plugin, functionName, request, locale)
                                )
                            }
                        }.getOrNull()
                        plugin to array
                    }
                }.awaitAll()
            }

            for ((plugin, array) in results) {
                if (array == null) continue

                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val title = pick(item, "title", "name", "song")
                    val artist = pick(item, "artist", "singer", "artistName")
                    val album = pick(item, "album", "albumName")
                    // 曲风：这个源没给曲风的候选直接不进列表 —— 选它写不进任何东西。
                    if (capability == ScrapeCapability.GENRE &&
                        pick(item, "genre", "style", "genres").isBlank()
                    ) {
                        continue
                    }
                    // 艺术家图的结果里 name 就是歌手名，匹配时按歌手算。
                    val score = if (capability == ScrapeCapability.ARTIST_IMAGE) {
                        TrackMatcher.score(
                            TrackMatcher.Candidate(title = artist.ifBlank { title }, artist = artist),
                            artistWant,
                            i
                        )
                    } else {
                        TrackMatcher.score(TrackMatcher.Candidate(title, artist, album), want, i)
                    }
                    out += ScrapeCandidate(
                        sourceId = plugin.pluginId,
                        sourceName = plugin.name,
                        title = title.ifBlank { "(无标题)" },
                        artist = artist,
                        album = album,
                        score = score,
                        previewUrl = previewOf(item, capability),
                        subtitle = subtitleOf(item, capability),
                        raw = item.toString()
                    )
                }
            }
            // 三基项严格匹配后才允许出现在结果列表里（艺术家图按名字单独处理）。
            val filtered = if (capability == ScrapeCapability.ARTIST_IMAGE) out else out.filter {
                it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank() &&
                    strictText(it.title, want.title) &&
                    strictText(it.album, want.album) &&
                    com.audiometa.app.domain.model.ArtistNames.matches(it.artist, want.artist)
            }
            filtered.sortedByDescending { it.score }
        }

    /** 单个源的搜索上限。连不上的源不该把结果列表拖住。 */
    private companion object {
        const val SOURCE_TIMEOUT_MS = 8_000L
    }

    /** 标题/专辑的严格比较：去首尾空格、忽略大小写。 */
    private fun strictText(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    private fun pick(item: JSONObject, vararg keys: String): String {        val fields = item.optJSONObject("fields")
        keys.forEach { key ->
            item.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun previewOf(item: JSONObject, capability: ScrapeCapability): String = when (capability) {
        // 艺术家图优先用 imageUrls 的第一项（插件的约定是大图在前）。
        ScrapeCapability.ARTIST_IMAGE ->
            item.optJSONArray("imageUrls")?.optString(0)?.takeIf { it.isNotBlank() }
                ?: pick(item, "imageUrl", "artist_image_url", "artistImageUrl")
        /*
         * 专辑图：插件的 `searchCovers` 返回的是**带图地址的歌曲列表**
         * （QQ/网易/Apple/酷狗的 `picUrl` 就是这个字段名），
         * 所以既认 `picUrl`，也认封面这一类的别的写法。
         */
        ScrapeCapability.COVER ->
            pick(item, "picUrl", "coverUrl", "cover", "albumCover", "albumCoverUrl", "imageUrl")
                .ifBlank { item.optJSONArray("imageUrls")?.optString(0).orEmpty() }
        // 元信息不显示图片。
        else -> ""
    }

    /**
     * 副标题。
     *
     * 元信息这里列出"这条候选提供了哪些字段"，用户挑的时候一眼能看出谁的资料更全。
     */
    private fun subtitleOf(item: JSONObject, capability: ScrapeCapability): String = when (capability) {
        ScrapeCapability.TRACK_META -> {
            val labels = listOf(
                "date" to "日期",
                "year" to "年份",
                "genre" to "流派",
                "language" to "语言",
                "lang" to "语言",
                "composer" to "作曲",
                "lyricist" to "作词",
                "copyright" to "版权",
                "comment" to "注释",
                "description" to "注释"
            )
            val present = labels
                .filter { (key, _) -> pick(item, key).isNotBlank() }
                .map { it.second }
                .distinct()
            if (present.isEmpty()) "没有可用字段" else present.joinToString(" · ")
        }
        ScrapeCapability.ARTIST_IMAGE -> pick(item, "artist", "singer", "artistName")
        // 曲风：一行直接给出这个源给到的曲风，用户挑的就是"要哪个源的风"。
        ScrapeCapability.GENRE -> {
            val genre = pick(item, "genre", "style", "genres")
            if (genre.isBlank()) "这个源没有曲风" else "曲风：" + MetaMergePolicy.cleanGenre(genre)
        }
        // 专辑图：一行写清楚"这是哪首歌的封面"，分辨率由网格角标补。
        ScrapeCapability.COVER -> listOf(
            pick(item, "artist", "singer", "artistName"),
            pick(item, "album", "albumName")
        ).filter { it.isNotBlank() }.joinToString(" · ")
        // 歌词不展示副标题（类型和翻译另有标签行），这里统一按歌手名给一句。
        ScrapeCapability.LYRICS -> pick(item, "artist", "singer", "artistName")
    }
}
