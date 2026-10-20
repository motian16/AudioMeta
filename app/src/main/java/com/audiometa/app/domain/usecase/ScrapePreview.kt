package com.audiometa.app.domain.usecase

import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.media.tag.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 弹窗顶部那一块**只读**的基础标签（用户要求：这一块不参与修改）。 */
data class BasicTag(val label: String, val value: String)

/**
 * 弹窗下半部分的一行：要写的内容。
 *
 * 用户要求**双列对比**：左列是文件里现在的值，右列是这次刮削要写的值；
 * 两边一样时右列显示「相同」，不用把同一句话写两遍。
 */
data class PlanRow(
    val label: String,
    /** 文件里当前的值（没有就是空串）。 */
    val currentValue: String,
    val newValue: String,
    val same: Boolean
) {
    /** 右列显示的文字：一样就写「相同」。 */
    val display: String get() = if (same) "相同" else newValue

    /** 左列显示的文字。 */
    val current: String get() = currentValue.ifBlank { "（空）" }
}

/**
 * 组合刮削的预览结果。
 *
 * [entries] 是确认后真正要写的内容：(能力, 插件 id, 候选 JSON)。
 * [noResult] 非空表示"没有可用结果"，界面直接显示这句话而不是一堆空行。
 */
data class ScrapePlan(
    val basic: List<BasicTag> = emptyList(),
    val rows: List<PlanRow> = emptyList(),
    val capabilities: Set<ScrapeCapability> = emptySet(),
    val entries: List<Triple<String, String, String>> = emptyList(),
    val noResult: String = ""
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * 只搜不写：把组合刮削会得到的内容收集起来给用户过目。
 *
 * 用户要求的三条核心规则（这一版按新需求重写）：
 *
 * 1. **只匹配三项全部符合的结果**。用文件里的**标题 + 专辑 + 艺术家**
 *    去比每一条候选，三项都必须一致（大小写/空白不敏感，艺术家允许
 *    `A/B`、`A feat. B` 这类写法里的任意一位对上）。对不上的候选**整条丢弃**，
 *    绝不"取最像的一条"写进文件。
 * 2. **音频没有内嵌信息就不联网**。标题 / 专辑 / 艺术家只要有一项是空的，
 *    直接返回"不进行网络搜索"——缺一项就没法做三项匹配，搜了也只能猜。
 * 3. **源顺序默认 Apple Music → QQ**，可在「设置 - 刮削 - 组合刮削优先级」
 *    里手动拖动。专辑图、艺术家图、曲风都按这一份顺序取。
 */
class ScrapePreview {

    private val search = CandidateSearch()

    suspend fun preview(
        trackId: Long,
        capabilities: Set<ScrapeCapability>
    ): ScrapePlan = withContext(Dispatchers.IO) {
        val locator = ServiceLocator
        val track = locator.tracks.getById(trackId)
            ?: return@withContext ScrapePlan(noResult = "找不到这首歌")
        val file = File(track.path)
        val existing = runCatching { TagReader().read(file) }.getOrDefault(TrackMeta())
        val settings = locator.settings.settings.first()

        // 顶部只读的基础标签：以**文件里现有的**为准（弹窗不修改它们）。
        val title = existing.title.ifBlank { track.title }.trim()
        val artist = existing.artist.ifBlank { track.artist }.trim()
        val album = existing.album.ifBlank { track.album }.trim()
        val basic = listOf(
            BasicTag("标题", title),
            BasicTag("专辑", album),
            BasicTag("艺术家", artist)
        )

        /*
         * 【硬规则】三项基础信息缺任何一项：不做任何网络搜索。
         *
         * 组合刮削的匹配标准是"三项全中"，缺一项时匹配注定是猜的；
         * 用户明确要求"音频无内嵌信息不进行网络搜索"，这里就是那道闸门。
         */
        if (title.isBlank() || artist.isBlank() || album.isBlank()) {
            return@withContext ScrapePlan(
                basic = basic,
                noResult = "这首歌的内嵌信息不完整（标题 / 专辑 / 艺术家三项缺项）。" +
                    "三项齐全才能做匹配，所以不进行网络搜索 —— 请先在下方「元信息」里补全。"
            )
        }

        val rows = mutableListOf<PlanRow>()
        val entries = mutableListOf<Triple<String, String, String>>()
        val used = mutableSetOf<ScrapeCapability>()

        /** 这条候选的三项是不是和文件里完全一致。 */
        fun strictMatch(candidate: ScrapeCandidate): Boolean =
            sameText(candidate.title, title) &&
                sameText(candidate.album, album) &&
                artistMatches(candidate.artist, artist)

        // ---- 专辑图：三项全部对上的候选里，取顺序最靠前、且真的带图的那一条 ----
        if (ScrapeCapability.COVER in capabilities) {
            val best = byPriority(
                runCatching { search.search(trackId, ScrapeCapability.COVER) }
                    .getOrDefault(emptyList())
                    .filter { it.previewUrl.isNotBlank() && strictMatch(it) },
                settings.sourcePriority[PriorityKey.TRACK_META].orEmpty()
            ).firstOrNull()
            if (best != null) {
                used += ScrapeCapability.COVER
                entries += Triple(ScrapeCapability.COVER.key, best.sourceId, best.raw)
                rows += PlanRow(
                    label = "专辑图",
                    currentValue = if (existing.hasCover) "已有封面" else "",
                    newValue = best.album.ifBlank { best.title } +
                        (if (best.sourceName.isBlank()) "" else "（" + best.sourceName + "）"),
                    same = false
                )
            } else {
                // same = false：让右列显示"没搜到"，而不是被显示层替换成「相同」。
                rows += PlanRow("专辑图", currentValue = "", newValue = "没有三项都匹配的封面", same = false)
            }
        }

        // ---- 艺术家图片：按艺术家搜，只接受歌手名对得上的那一条 ----
        if (ScrapeCapability.ARTIST_IMAGE in capabilities) {
            val best = byPriority(
                runCatching { search.search(trackId, ScrapeCapability.ARTIST_IMAGE) }
                    .getOrDefault(emptyList())
                    .filter { artistMatches(it.artist.ifBlank { it.title }, artist) },
                settings.sourcePriority[PriorityKey.ARTIST_IMAGE].orEmpty()
            ).firstOrNull()
            if (best != null) {
                used += ScrapeCapability.ARTIST_IMAGE
                entries += Triple(ScrapeCapability.ARTIST_IMAGE.key, best.sourceId, best.raw)
                rows += PlanRow(
                    label = "艺术家图片",
                    currentValue = if (existing.hasArtistImage) "已有艺术家图" else "",
                    newValue = best.title + if (best.sourceName.isBlank()) "" else "（" + best.sourceName + "）",
                    same = existing.hasArtistImage
                )
            } else {
                rows += PlanRow("艺术家图片", currentValue = "", newValue = "没有匹配到这位艺术家", same = false)
            }
        }

        // ---- 曲风：三项全对上的候选里，按固定顺序 Apple → QQ → 网易 → 酷狗取 ----
        if (ScrapeCapability.GENRE in capabilities) {
            val genre = pickGenre(trackId, title, artist, album, settings)
            if (genre != null) {
                used += ScrapeCapability.GENRE
                // 只交一个 genre 字段出去：写入侧不会再碰别的字段。
                val only = JSONObject().put("genre", genre.second)
                entries += Triple(ScrapeCapability.GENRE.key, genre.first.sourceId, only.toString())
            }
            rows += PlanRow(
                label = "曲风",
                currentValue = existing.genre,
                newValue = genre?.second ?: "没有三项都匹配的曲风",
                // 没搜到时要显示"没有三项都匹配的曲风"，不能被显示层换成「相同」。
                same = genre != null && existing.genre.isNotBlank() && existing.genre == genre.second
            )
        }

        // ---- 元信息（批量刮削用；详情页已不出这个入口）----
        if (ScrapeCapability.TRACK_META in capabilities) {
            val all = runCatching { search.search(trackId, ScrapeCapability.TRACK_META) }
                .getOrDefault(emptyList())
                .filter { strictMatch(it) }
            val parsed = all.mapNotNull { candidate ->
                val meta = runCatching { MetaFields.fromSong(JSONObject(candidate.raw)) }.getOrNull()
                if (meta == null || !meta.hasAnyField()) null else candidate to meta
            }
            val ordered = orderByPriority(parsed, settings.sourcePriority[TRACK_META_KEY].orEmpty())
            val pickedSource = ordered.firstOrNull()?.first
            if (ordered.isEmpty()) {
                rows += PlanRow("元信息", currentValue = "", newValue = "没有三项都匹配的结果", same = false)
            } else {
                used += ScrapeCapability.TRACK_META
                val merged = mergeMeta(ordered)
                if (pickedSource != null) {
                    entries += Triple(ScrapeCapability.TRACK_META.key, pickedSource.sourceId, merged.toString())
                }
                MetaMergePolicy.PREVIEW_FIELDS.forEach { (field, label) ->
                    val value = fieldValue(field, ordered.first().second)
                    if (value.isNotBlank()) {
                        rows += PlanRow(label, currentValue = "", newValue = value, same = false)
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            return@withContext ScrapePlan(
                basic = basic,
                rows = rows,
                noResult = "没有可写入的内容：没有找到标题、专辑、艺术家三项都符合的结果。" +
                    "不把别的歌的信息写进来，请手动核对文件标签后重试。"
            )
        }
        ScrapePlan(
            basic = basic,
            rows = rows,
            capabilities = used,
            entries = entries
        )
    }

    /**
     * 曲风：在**三项全对上**的候选里按固定顺序取。
     *
     * 顺序是用户指定的 Apple Music → QQ → 网易 → 酷狗；这份顺序来自
     * [MetaMergePolicy.FIELD_SOURCE_ORDER]，不再读用户设置的源优先级
     * （和以前的做法一致，避免两处顺序自相矛盾）。
     */
    private suspend fun pickGenre(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        settings: AppSettings
    ): Pair<ScrapeCandidate, String>? {
        val candidates = runCatching { search.search(trackId, ScrapeCapability.GENRE) }
            .getOrDefault(emptyList())
            .filter {
                sameText(it.title, title) && sameText(it.album, album) &&
                    artistMatches(it.artist, artist)
            }
        if (candidates.isEmpty()) return null
        // 用户设置的源顺序决定同一短名内部的先后。
        val priority = settings.sourcePriority[TRACK_META_KEY].orEmpty()
        fun rankOf(c: ScrapeCandidate): Int =
            priority.indexOf(c.sourceId).let { if (it < 0) priority.size + 100 else it }
        val ordered = candidates.sortedBy { rankOf(it) }
        val fixed = MetaMergePolicy.FIELD_SOURCE_ORDER["genre"].orEmpty()
        fixed.forEach { short ->
            ordered.firstOrNull { MetaMergePolicy.shortName(it.sourceId) == short }
                ?.let { c ->
                    val genre = MetaMergePolicy.cleanGenre(genreOf(c))
                    if (genre.isNotBlank()) return c to genre
                }
        }
        ordered.forEach { c ->
            val genre = MetaMergePolicy.cleanGenre(genreOf(c))
            if (genre.isNotBlank()) return c to genre
        }
        return null
    }

    /** 曲风字段：插件普遍用 `genre`，少数用 `style` / `genres`。 */
    private fun genreOf(candidate: ScrapeCandidate): String {
        val json = runCatching { JSONObject(candidate.raw) }.getOrNull() ?: return ""
        val fields = json.optJSONObject("fields")
        listOf("genre", "style", "genres", "genreName").forEach { key ->
            val raw = json.opt(key) ?: fields?.opt(key)
            if (raw == null) return@forEach
            if (raw is org.json.JSONArray) {
                val list = (0 until raw.length()).mapNotNull { i ->
                    raw.optString(i).takeIf { s -> s.isNotBlank() }
                }
                if (list.isNotEmpty()) return list.joinToString("/")
            }
            val text = raw.toString().trim()
            if (text.isNotBlank()) return text
        }
        return ""
    }

    /** 元信息：按字段取内容最长的那个源，平手才算源优先级。 */
    private fun mergeMeta(ordered: List<Pair<ScrapeCandidate, TrackMeta>>): JSONObject {
        fun pick(selector: (TrackMeta) -> String): String =
            ordered.map { selector(it.second).trim() }
                .filter { it.isNotBlank() }
                .maxByOrNull { it.length }
                .orEmpty()
        fun pickNumber(selector: (TrackMeta) -> Int): String =
            ordered.map { selector(it.second) }
                .filter { it > 0 }
                .maxOrNull()
                ?.toString()
                .orEmpty()
        return JSONObject()
            .put("id", "merged-plan")
            .put("album_artist", pick { it.albumArtist })
            .put("track", pickNumber { it.trackNo })
            .put("disc", pickNumber { it.discNo })
            .put("date", pick { it.date })
            .put("genre", MetaMergePolicy.cleanGenre(pick { it.genre }))
            .put("language", pick { it.language })
            .put("copyright", pick { it.copyright })
            .put("comment", pick { it.comment })
            .put("composer", pick { it.composer })
    }

    private fun fieldValue(field: String, meta: TrackMeta): String = when (field) {
        "genre" -> meta.genre
        "trackNo" -> meta.trackNo.takeIf { it > 0 }?.toString().orEmpty()
        "discNo" -> meta.discNo.takeIf { it > 0 }?.toString().orEmpty()
        "date" -> meta.date.ifBlank { meta.year.takeIf { y -> y > 0 }?.toString().orEmpty() }
        "language" -> meta.language
        "copyright" -> meta.copyright
        "comment" -> meta.comment
        "composer" -> meta.composer
        else -> ""
    }

    private fun orderByPriority(
        items: List<Pair<ScrapeCandidate, TrackMeta>>,
        priority: List<String>
    ): List<Pair<ScrapeCandidate, TrackMeta>> {
        val ranked = priority.mapNotNull { id -> items.firstOrNull { it.first.sourceId == id } }
        return ranked + items.filter { item -> ranked.none { it.first.sourceId == item.first.sourceId } }
    }

    /**
     * 按设置的源优先级排序（默认 **Apple Music → QQ** → 网易 → 酷狗）。
     *
     * 三项都已经严格匹配之后，剩下的差别只有"哪个源"—— 这里就按用户设定的顺序取，
     * 而不是按 TrackMatcher 的分数：分数是"像不像"，三项全中之后已经不构成区别。
     * 表里没有的源排在后面，保持插件自身顺序。
     */
    private fun byPriority(
        list: List<ScrapeCandidate>,
        priority: List<String>
    ): List<ScrapeCandidate> {
        fun rankOf(id: String): Int =
            priority.indexOf(id).let { if (it < 0) priority.size + 100 else it }
        return list.sortedBy { rankOf(it.sourceId) }
    }

    /** 两项文本是不是同一句：忽略大小写、空白和常见标点差异。 */
    private fun sameText(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotBlank() && x == y
    }

    /**
     * 艺术家是不是同一位。
     *
     * 源里的合作曲常写成 `A/B`、`A feat. B`、`A & B`，本地文件里可能是 `A`、
     * 也可能是 `A/B`，所以两边都按分隔符拆开、只要**有一位重合**就算符合 ——
     * 这仍然是"三项都符合"，不是"看起来最像"。
     */
    private fun artistMatches(candidate: String, local: String): Boolean {
        fun partsOf(text: String): Set<String> =
            text.split('/', '&', ',', '、', '；', ';')
                .flatMap { it.split(Regex("(?i)\\s+(feat\\.?|ft\\.?|with)\\s+")) }
                .map { norm(it) }
                .filter { it.isNotBlank() }
                .toSet()
        val wanted = partsOf(local)
        if (wanted.isEmpty()) return false
        return partsOf(candidate).any { it in wanted }
    }

    private fun norm(text: String): String =
        text.lowercase()
            .replace(Regex("[\\s\\u00a0]+"), "")
            .replace(Regex("[\\p{Punct}，。！？、：；“”‘’（）《》【】·—…]"), "")

    private companion object {
        /** 「元信息」/「组合」在设置里的优先级键。 */
        const val TRACK_META_KEY = com.audiometa.app.domain.model.PriorityKey.TRACK_META
    }
}
