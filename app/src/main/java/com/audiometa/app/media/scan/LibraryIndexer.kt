package com.audiometa.app.media.scan

import com.audiometa.app.data.db.LyricsCacheEntity
import com.audiometa.app.data.db.MetaTagEntity
import com.audiometa.app.data.repo.LyricsCacheRepository
import com.audiometa.app.data.repo.MetaTagRepository
import com.audiometa.app.data.repo.TrackRepository
import com.audiometa.app.media.image.ArtworkResolver
import com.audiometa.app.media.lyrics.LyricsParser
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 后台补全的进度。界面上只在设置页露一行，不需要按曲目刷。 */
data class IndexProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val currentTitle: String = "",
    val message: String = "",
    val finished: Boolean = false
)

/**
 * 扫描之后的后台补全。
 *
 * 第一阶段（[MediaScanner]）只保证"列表能立刻显示"，读的是标题/专辑/艺术家
 * 这类秒回的字段；歌词正文、翻译、曲风这些必须把标签结构完整解析一遍才能拿到，
 * 几百首就是几十秒。所以放到这里慢慢做：
 *
 * - 只处理还没进过索引的曲目（[TrackEntity.lyricsIndexedAt] 为 0），增量推进；
 * - 歌词正文顺手写进 `lyrics_cache` 表，搜索歌词时直接查表，不用读文件；
 * - **非标准标签同时按规则识别一遍**，结果写进 `meta_tags` 表 ——
 *   「音频库 - 元信息」页的数据就是这么来的。用户要求"在音乐库扫描时
 *   同时按照规则扫描元信息页的数据"，所以这一步不单独做，并在这里。
 *   标签原文是 [TagReader.read] 顺带带出来的，不额外读文件。
 * - 不碰 `updatedAt`，否则整个列表的封面缓存会一起失效、滚动掉帧。
 */
class LibraryIndexer(
    private val trackRepository: TrackRepository,
    private val lyricsCache: LyricsCacheRepository,
    private val metaTags: MetaTagRepository,
    private val tagReader: TagReader = TagReader()
) {
    val progress = MutableStateFlow(IndexProgress())
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    /** 是否正在跑。扫描页和搜索页用它避免重复启动。 */
    val isRunning: Boolean get() = running.get()

    /**
     * 增量刷新。
     *
     * [limit] 用于"搜到一半先出结果"的场景：先处理一小批，剩下的下一轮继续。
     */
    suspend fun refresh(limit: Int = Int.MAX_VALUE) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        cancelled.set(false)
        try {
            // 曲库变小过（删了歌）时先清掉孤儿缓存行。
            pruneOrphans()

            val pending = trackRepository.needingLyricsIndex()
            if (pending.isEmpty()) {
                progress.value = IndexProgress(
                    running = false, finished = true, message = "歌词缓存已是最新"
                )
                return@withContext
            }
            val queue = if (pending.size > limit) pending.take(limit) else pending
            val done = AtomicInteger(0)
            progress.value = IndexProgress(
                running = true,
                total = pending.size,
                done = 0,
                message = "正在后台补全歌词与流派"
            )
            val semaphore = Semaphore(PARALLELISM)
            queue.chunked(CHUNK).forEach { chunk ->
                if (cancelled.get()) return@forEach
                coroutineScope {
                    chunk.map { track ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!cancelled.get()) indexOne(track)
                                val current = done.incrementAndGet()
                                progress.value = progress.value.copy(
                                    done = current,
                                    currentTitle = track.title
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
            progress.value = progress.value.copy(
                running = false,
                finished = true,
                currentTitle = "",
                message = buildString {
                    append(if (cancelled.get()) "已暂停，" else "补全完成，")
                    append("本轮处理 " + done.get() + " 首")
                    if (pending.size > queue.size) append("，剩余 " + (pending.size - queue.size) + " 首待处理")
                }
            )
        } finally {
            running.set(false)
        }
    }

    /**
     * 手动刷新某一首：重读它的标签、重建歌词缓存、重算开头静音。
     *
     * 详情页上点标签状态时调用——用户此时的意思就是"这个文件我刚改过，重读一遍"。
     * 只处理这一首，不会牵动整个曲库。
     *
     * **`full = true` 是刻意的**：这个入口只在"文件真的被写过"之后被调用
     * （手动改元信息、刮削写入、换封面/艺术家图）。这时候必须把标签字段
     * （标题/艺术家/专辑/音轨/年份/曲风）也写回曲目表并换掉 updatedAt ——
     * 只更新 hasCover/hasLyrics 这些状态位的话，列表和详情页读到的还是
     * 改动前那一行（用户报过"改了专辑要退出重进才显示"）。
     * 后台批量补全走的是 `full = false`，见 [indexOne] 的说明。
     */
    suspend fun refreshTrack(trackId: Long) = withContext(Dispatchers.IO) {
        val track = trackRepository.getById(trackId) ?: return@withContext
        runCatching { indexOne(track, full = true) }
    }

    /**
     * 单曲补全。
     *
     * 重复处理是安全的：只覆盖"缺的"信息，已有外置图不会被内嵌图顶掉。
     *
     * @param full 是否把标签字段一起写回曲目表并刷新 updatedAt。
     *   默认 false（后台批量补全）：批量时给每一首都换 updatedAt 会让
     *   详情页的封面缓存集体失效、整库重新解码，滑动明显掉帧。
     */
    private suspend fun indexOne(track: TrackEntity, full: Boolean = false) {
        val file = File(track.path)
        if (!file.isFile) {
            // 文件没了，标记成已处理，免得每轮都白跑一次。
            trackRepository.markLyricsIndexed(track.id)
            return
        }
        val meta: TrackMeta = runCatching { tagReader.read(file, imageSink) }.getOrElse { TrackMeta() }

        // 外置图优先：用户明确要求有外置封面图时默认读外置。
        val externalCover = ArtworkResolver.externalCoverPath(file)
        val externalArtist = ArtworkResolver.externalArtistPath(file)

        // 艺术家图的判定要多花一次解析（要确认标签里到底有没有类型 8 的图），
        // 没写过艺术家图的歌这里会一直为 false，不会误判成"有图"。
        val hasEmbeddedArtist = meta.hasArtistImage

        // 曲风：可能在基础扫描时漏了（多值字段尤其容易），这里补一次。
        // 多值字段用换行符存一列，见 [TrackEntity.joinGenres]。
        // `full` 时以文件为准（文件里删了曲风就跟着清空）；后台补全读不到就保留原值。
        val fromFile = TrackEntity.joinGenres(listOf(meta.genre))
        val genres = if (full) fromFile else fromFile.ifBlank { track.genres }

        if (full) {
            // 整行按文件重读：文件是唯一真相，标签被改成什么样就写回什么样。
            trackRepository.refreshFromFile(
                track.copy(
                    title = meta.title,
                    artist = meta.artist,
                    albumArtist = meta.albumArtist,
                    album = meta.album,
                    trackNo = meta.trackNo,
                    year = meta.year,
                    genres = genres,
                    hasArtistImage = externalArtist != null || hasEmbeddedArtist,
                    hasCover = externalCover != null || meta.hasCover,
                    hasLyrics = meta.hasLyrics,
                    hasTranslation = meta.hasTranslation,
                    lyricsType = meta.lyricsType,
                    artistImagePath = externalArtist,
                    coverPath = externalCover
                )
            )
        } else {
            trackRepository.applyEnrichment(
                id = track.id,
                hasArtist = externalArtist != null || hasEmbeddedArtist,
                hasCover = externalCover != null || meta.hasCover,
                hasLyrics = meta.hasLyrics,
                hasTranslation = meta.hasTranslation,
                lyricsType = meta.lyricsType,
                artistImagePath = externalArtist ?: track.artistImagePath,
                coverPath = externalCover ?: track.coverPath,
                indexedAt = System.currentTimeMillis()
            )
        }

        if (genres.isNotBlank() && genres != track.genres) {
            trackRepository.updateGenres(track.id, genres)
        }

        // 歌词正文入缓存，搜索走这张表。
        if (meta.hasLyrics || meta.translation.isNotBlank()) {
            lyricsCache.save(
                LyricsCacheEntity(
                    trackId = track.id,
                    title = track.title.ifBlank { meta.title },
                    artist = track.artist.ifBlank { meta.artist },
                    lyrics = plainText(meta.lyrics),
                    translation = plainText(meta.translation)
                )
            )
        }

        // 非标准标签：按规则识别后落库，「元信息」页直接读这张表。
        // 文件读不出标签（或全是标准标签）时也写一次空结果 ——
        // 这样"这首歌已经扫过"是确定的，不会每轮补全都重来一遍。
        val entries = if (meta.rawTags.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                com.audiometa.app.domain.tagrules.TagRules.classify(
                    meta.rawTags,
                    includeStandard = false,
                    // 值空的标签也留下（用户要求"有的只有键，没有值，也显示出来"）。
                    keepBlankValues = true
                )
            }.getOrDefault(emptyList())
        }
        runCatching {
            metaTags.replaceForTrack(
                trackId = track.id,
                rows = entries.map { entry ->
                    MetaTagEntity(
                        trackId = track.id,
                        key = entry.key,
                        value = entry.value.take(2000),
                        platform = entry.platform.orEmpty(),
                        tool = entry.tool.orEmpty(),
                        hints = entry.hints.joinToString("\n")
                    )
                }
            )
        }.onFailure { AppLog.d(TAG, "标签识别结果落库失败：" + it.message) }
    }

    private suspend fun pruneOrphans() {
        val ids = trackRepository.idPathPairs().map { it.id }
        lyricsCache.pruneTo(ids)
        // 曲目被删掉/移走后，它名下的标签识别结果也该一起清掉，
        // 否则「元信息」页会一直显示一个已经不存在的来源。
        runCatching { metaTags.pruneTo(ids) }
            .onFailure { AppLog.d(TAG, "清理失效标签记录失败：" + it.message) }
        // 标签被改过之后，之前落盘的"图片标签"里会有一部分没人引用了 —— 一并清掉，
        // 否则那个目录只会越来越大。
        runCatching {
            val keep = metaTags.all()
                .map { it.value }
                .filter { com.audiometa.app.media.tag.MetaImageStore.isImageValue(it) }
                .map { com.audiometa.app.media.tag.MetaImageStore.fileNameOf(it) }
                .toSet()
            val removed = com.audiometa.app.media.tag.MetaImageStore
                .prune(com.audiometa.app.di.ServiceLocator.appContext, keep)
            if (removed > 0) AppLog.d(TAG, "清理失效的元信息图片 " + removed + " 张")
        }.onFailure { AppLog.d(TAG, "清理元信息图片失败：" + it.message) }
    }

    /**
     * 图片类自定义标签的落盘口子。
     *
     * 标签里有些值是图片（封面之外的插图、厂牌 logo、自定义 atom…）。
     * 那种值不能当字符串存库，交给 [com.audiometa.app.media.tag.MetaImageStore]
     * 落成文件，库里只留一个 `IMG:<sha1>` 标记。
     */
    private val imageSink: (ByteArray, String) -> String? = { bytes, mime ->
        com.audiometa.app.media.tag.MetaImageStore
            .store(com.audiometa.app.di.ServiceLocator.appContext, bytes, mime)
    }

    /**
     * LRC 正文转纯文本。
     *
     * 不入库时间轴：搜索只需要文字，`[00:12.34]` 这类标记既占体积又会让
     * "搜 00:1" 这种没意义的查询命中一堆结果。逐字歌词的 `<..>` 一并去掉。
     */
    private fun plainText(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { LyricsParser.parse(raw) }.getOrNull()
        if (parsed != null && parsed.hasOriginal) {
            val text = parsed.original.joinToString("\n") { it.text }
            if (text.isNotBlank()) return text
        }
        // 解析不出结构（纯文本歌词）就手工去标记。
        return raw
            .replace(Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]"), "")
            .replace(Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>"), "")
            .replace(Regex("^\\[[a-zA-Z_]+:.*]$", RegexOption.MULTILINE), "")
            .trim()
    }

    companion object {
        private const val PARALLELISM = 3
        private const val CHUNK = 16
        private const val TAG = "LibraryIndexer"
    }
}
