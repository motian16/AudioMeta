package com.audiometa.app.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.prefs.RemoteTrack
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.media.image.ArtworkKind
import com.audiometa.app.media.image.ArtworkResolver
import com.audiometa.app.media.lyrics.LyricsParser
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 用**本机曲库**补上 PC 正在播放那首歌的信息：封面、歌词、时长。
 *
 * ## 为什么不让插件推封面 / 完整歌词
 *
 * 插件那边（宿主 dev20 的公开 API）**根本拿不到封面** ——
 * `PlaybackExtensionPoint.MediaItem` 只有 title/artist/album/albumArtist/path。
 * 曾尝试让插件直接读 PC 磁盘文件再走 WebSocket 传 base64（协议 v2），
 * 实测在真实环境里不稳定（宿主沙箱/路径/大帧传输任一环节都会失败），
 * 已移除该方案。而这个 App 本身就是音乐标签编辑器：曲库里有曲目、
 * 有内嵌封面、有歌词 —— **在本机解析既快又清晰，还不占网络**。
 * 歌词当前行仍由插件的 `state.lyric` 推送，作为本机没匹配到时的兜底。
 *
 * ## 歌词来源（按顺序，第一个有内容的就用）
 *
 * 用户的实际文件状况很杂，所以这里把所有可能的位置都试一遍：
 *   1. **音频文件内嵌歌词**（`TagReader.read().lyrics`）—— 最常见：
 *      FLAC/Vorbis 的 `LYRICS`、ID3 的 `USLT`、MP4 的 `©lyr`；
 *   2. **同目录同名 `.lrc`**（外挂歌词，和音频放在一起）；
 *   3. **曲库记录的 `lyricsPath`**（App 自己刮削或写过的那一份）；
 *   4. **兜底歌词目录**（`设置 - 刮削 - 兜底歌词` 导出的统一目录，
 *      保持原目录结构，见 `FallbackLyricsWriter`）。
 *
 * 翻译：内嵌歌词按约定用 `[translation]` 标记分隔，由 [LyricsParser]
 * 拆成 original / translation 两份时间轴。逐字（`<mm:ss.xx>`）也由它处理。
 * 有翻译时当前行会显示成「原文 / 译文」两行。
 *
 * ## 匹配策略
 *
 * PC 和手机是两个独立曲库，**文件路径对不上**（`C:\Music\x.flac` vs
 * `/storage/emulated/0/x.flac`），所以不能按路径匹配。顺序是：
 *   1. 文件名（去掉扩展名）—— 同一批文件同步过来时最准；
 *   2. 标题 + 艺术家（都归一化）；
 *   3. 只按标题（放宽，但要求唯一命中，避免张冠李戴）。
 *
 * 匹配不上就返回 [NowPlaying.matched] = false，界面用占位图 —— 不猜、不顶替。
 */
object RemoteTrackResolver {

    private const val TAG = "RemoteTrackResolver"

    /** 曲库索引。 */
    @Volatile
    private var index: Index? = null

    private class Index(
        val byFileStem: Map<String, TrackEntity>,
        val byTitleArtist: Map<String, TrackEntity>,
        val byTitle: Map<String, List<TrackEntity>>
    ) {
        /**
         * 索引有效吗。
         *
         * 空索引一律当"无效"：它多半是"数据库还没就绪 / 扫描刚结束"那一瞬间
         * 建出来的，缓存下来会表现成"本机永远没有这首歌"。
         */
        val isEmpty: Boolean
            get() = byFileStem.isEmpty() && byTitleArtist.isEmpty() && byTitle.isEmpty()
    }

    /** 索引重建的互斥锁：并发解析时只允许一个协程去建，其余等它的结果。 */
    /** 重建索引用的协程锁（见 [ensureIndex]：不能是 `synchronized`，那里有挂起点）。 */
    private val indexMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 内嵌/文件名读时长的缓存：文件路径 → 毫秒（0 表示确实读不到）。
     *
     * 为什么要单独缓一份：曲库那一行的 `durationMs` 经常是 0（MediaStore 来源、
     * 或扫描阶段只走了第一阶段没读时长），而**总时长只来自本机匹配** ——
     * 不补这一刀，界面右侧就一直是占位"拖动跳转"，用户看到的就是
     * "总时长消失了、进度条也不走"。补一次的结果按路径记住，同一首歌不会
     * 每次解析都去开一遍 MediaMetadataRetriever。
     */
    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 最近一次**成功匹配**到的曲目与结果。
     *
     * 两个用处：
     *   1. 界面（ViewModel）被重建、`_nowPlaying` 变空时，可以从这里把上一次的
     *      封面/时长/歌词**恢复**出来，不必重新解一遍图；
     *   2. 判断"当前这份匹配结果是不是还在讲这首歌" —— 断线时的空快照、
     *      刚换歌还没解析完的瞬间，都不能让界面把封面/时长擦掉。
     */
    @Volatile
    private var lastResolved: NowPlaying? = null

    /** 曲目快照的稳定标识，和 ViewModel 去重用的键保持一致口径。 */
    fun keyOf(track: RemoteTrack): String =
        track.id + "|" + track.path + "|" + track.title + "|" + track.artist

    /** 上一次成功匹配的结果（没匹配过就是 null）。 */
    fun lastMatched(): NowPlaying? = lastResolved

    /** 当前这首歌的封面与歌词状态，供界面直接使用。 */
    data class NowPlaying(
        val cover: Bitmap? = null,
        /** 当前时间点的原文行。 */
        val lyricLine: String = "",
        /** 当前时间点的翻译行；没有翻译时为空。 */
        val lyricTranslation: String = "",
        /** 本地是否匹配到了这首歌。false 时界面显示占位图。 */
        val matched: Boolean = false,
        /** 本机曲库里的时长，能用来画真实进度条。0 表示本机也没有。 */
        val durationMs: Long = 0L,
        /** 匹配到的是哪个文件，排查用。 */
        val localPath: String = "",
        /** 歌词是从哪里读到的，显示给用户看（内嵌 / 外挂 / 兜底 / 无）。 */
        val lyricSource: LyricSource = LyricSource.NONE,
        /**
         * 这份结果是**为哪首歌**解析出来的（[keyOf] 的值）。
         *
         * 界面用它判断"现在显示的封面/时长是不是这首歌的"：刚换歌、解析还没
         * 回来，或者断线收到空快照时，`_nowPlaying` 里可能还是上一首的结果 ——
         * 那时候要显示占位图，而**不能**显示"本机曲库里没有这首歌"这种
         * 断言（本机其实有，只是还没解析完/只是断线了）。
         */
        val trackKey: String = ""
    ) {
        val hasLyrics: Boolean
            get() = lyricLine.isNotBlank() || lyricTranslation.isNotBlank()
    }

    /** 歌词来源，界面上给个小标注，用户就知道为什么有的歌没有歌词。 */
    enum class LyricSource(val label: String) {
        EMBEDDED("内嵌"),
        SIDECAR("外挂"),
        FALLBACK("兜底"),
        NONE("无")
    }

    /** 曲库变了（重新扫描）之后调用，下次解析会重建索引。 */
    fun invalidate() {
        index = null
        resetLyrics()
        // 曲库重扫后时长/封面都可能变，缓存的"上次匹配"要一起作废，
        // 否则界面会继续拿旧的时长画进度条。
        lastResolved = null
        durationCache.clear()
    }

    /**
     * 解析当前曲目。**必须在 IO 线程调用**（要读文件、查数据库）。
     *
     * 封面 / 完整歌词 / 时长全部来自**本机曲库匹配**（匹配策略见类注释）。
     * 匹配不上返回 [NowPlaying.matched] = false，界面用占位图和插件推的
     * 当前行歌词兜底 —— 不猜、不顶替。
     *
     * **匹配到但封面读不出来**时仍然返回 `matched = true`：那是"有这首歌、
     * 只是没有封面"，界面该显示音符占位图，而**不是**"本机曲库里没有这首歌"
     * —— 后者是一句明确的断言，说错了用户就会以为曲库有问题。
     */
    suspend fun resolve(
        context: Context,
        track: RemoteTrack
    ): NowPlaying {
        if (!track.hasTrack) {
            // 空快照（刚断线、刚重连、宿主还没推 state）：不猜任何东西，
            // 返回"没匹配到"。**注意这里只是清歌词缓存**，不动 lastResolved
            // （那是给界面恢复用的），也不清界面已经拿到的封面/时长。
            //
            // 桌面小组件也走这个函数：它按 trackKey 缓存，空曲目给空结果
            // 才会画出"待机"形态，而不是把上一首的封面/进度条留在桌面上。
            resetLyrics()
            return NowPlaying()
        }

        val key = keyOf(track)
        val local = findLocal(track)
        if (local == null) {
            // 本机确实没有这首歌：清掉歌词缓存，防止 trioAt 拿上一首的缓存顶上。
            // 但**不**清 lastResolved 的"上一首匹配结果"里的封面 —— 界面那边
            // 会用 trackKey 判断它是不是这首歌的，不会画错。
            resetLyrics()
            return NowPlaying(trackKey = key)
        }

        // 关键：读取当前行之前必须**先**把歌词装进缓存 ——
        // originalLineAt 只查缓存、不读文件（它每秒都会被调一次）。
        ensureLyrics(context, local)

        val duration = durationOf(local)
        val result = NowPlaying(
            cover = loadCover(local),
            lyricLine = originalLineAt(0L),
            lyricTranslation = translationLineAt(0L),
            matched = true,
            durationMs = duration,
            localPath = local.path,
            lyricSource = currentSource,
            trackKey = key
        )
        lastResolved = result
        return result
    }

    /**
     * 这首歌的时长：先用曲库那一行的值，为 0 就**就地补一次**。
     *
     * 为什么必须补：宿主（Salt Player dev20）不推总时长，界面上右侧的总时长
     * **只可能来自本机曲库的 `durationMs`**。而曲库里这一行是 0 的情况很常见
     * —— MediaStore 来源的曲目、扫描时只走了第一阶段没读时长的文件。
     * 结果是"本来手机上有这首歌、也匹配到了，右侧却什么都不显示、进度条也不动"。
     *
     * 用 MediaMetadataRetriever 直接读容器头（和 `TagReader.readBasic` 第一阶段
     * 同一条路，毫秒级），拿到的值按路径缓存，同一首歌不会再解析第二次。
     */
    private fun durationOf(local: TrackEntity): Long {
        if (local.durationMs > 0) return local.durationMs
        val path = local.path
        if (path.isBlank()) return 0L
        durationCache[path]?.let { return it }

        val measured = probeDurationMs(path)
        durationCache[path] = measured
        if (measured <= 0L) {
            AppLog.d(TAG, "本机也没能读到时长：" + path)
        } else {
            AppLog.d(TAG, "曲库时长缺失，已从文件读出：" + measured + "ms（" + path + "）")
        }
        return measured
    }

    /** 从音频文件读时长（毫秒）；读不到就 0。 */
    private fun probeDurationMs(path: String): Long {
        val file = File(path)
        if (!file.isFile) return 0L
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            // 容器不被系统支持（ape/wv/tta 之类）不是故障，记 DEBUG 就够
            AppLog.d(TAG, "读时长失败：" + path + "：" + t.message)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------------- 歌词：解析与取值 ----------------

    private var lyricSourcePath: String? = null
    private var originalLines: List<LyricsParser.Line> = emptyList()
    private var translationLines: List<LyricsParser.Line> = emptyList()

    @Volatile
    private var currentSource: LyricSource = LyricSource.NONE

    private fun resetLyrics() {
        lyricSourcePath = null
        originalLines = emptyList()
        translationLines = emptyList()
        currentSource = LyricSource.NONE
    }

    /** 歌词三行 + 当前翻译。数据类放外面，便于界面直接解构。 */
    data class LyricTrio(
        val previous: String = "",
        val current: String = "",
        val next: String = "",
        val translation: String = ""
    )

    /** 本机匹配到的文件里有没有可用歌词。界面据此决定要不要用 PC 下发的歌词。 */
    fun hasLyrics(): Boolean = originalLines.isNotEmpty()

    /**
     * 取某个播放位置对应的**歌词三行**（上一行 / 当前行 / 下一行）+ 当前翻译。
     *
     * 用户要求不要只显示当前行 —— 只看一行看不出上下文。
     * 纯内存查表，界面每秒调一次也没有压力。
     */
    fun trioAt(positionMs: Long): LyricTrio {
        val lines = originalLines
        if (lines.isEmpty()) return LyricTrio()

        // 找当前行：startMs <= position 的最后一个（时间轴可能不严格有序，所以扫全）
        var index = -1
        var bestStart = Long.MIN_VALUE
        for (i in lines.indices) {
            val s = lines[i].startMs
            if (s <= positionMs && s >= bestStart) {
                bestStart = s
                index = i
            }
        }
        if (index < 0) {
            // 还没到第一行：把第一行当作"下一行"显示，让用户知道马上就唱
            return LyricTrio(next = lines.firstOrNull()?.text.orEmpty())
        }

        return LyricTrio(
            previous = lines.getOrNull(index - 1)?.text.orEmpty(),
            current = lines[index].text,
            next = lines.getOrNull(index + 1)?.text.orEmpty(),
            translation = translationLineAt(positionMs)
        )
    }

    /**
     * 当前原文行。界面每秒调一次，所以**不能读文件** —— 只查已解析好的缓存。
     */
    fun originalLineAt(positionMs: Long): String = lineAt(originalLines, positionMs)

    /** 当前翻译行；没有翻译时返回空串。 */
    fun translationLineAt(positionMs: Long): String = lineAt(translationLines, positionMs)

    private fun lineAt(lines: List<LyricsParser.Line>, positionMs: Long): String {
        if (lines.isEmpty()) return ""
        // 找"开始时间 <= position 的最后一行"。
        // 有意线性扫（歌词最多几百行，每秒一次完全够），
        // 而且能正确处理乱序时间轴。
        var found: LyricsParser.Line? = null
        for (line in lines) {
            if (line.startMs <= positionMs && (found == null || line.startMs >= found.startMs)) {
                found = line
            }
        }
        return found?.text.orEmpty()
    }

    /**
     * 按上面说的四个来源依次找歌词，解析并缓存。
     *
     * 返回实际用到的来源，界面显示用。
     */
    private fun ensureLyrics(context: Context, track: TrackEntity): LyricSource {
        if (lyricSourcePath == track.path) return currentSource
        lyricSourcePath = track.path
        originalLines = emptyList()
        translationLines = emptyList()
        currentSource = LyricSource.NONE

        val raw = readLyricsRaw(context, track) ?: return LyricSource.NONE
        if (raw.isBlank()) return LyricSource.NONE

        val parsed = runCatching { LyricsParser.parse(raw) }.getOrNull()
        if (parsed == null) {
            AppLog.d(TAG, "歌词解析失败：" + track.path)
            return LyricSource.NONE
        }

        originalLines = parsed.original
        translationLines = parsed.translation
        if (originalLines.isEmpty() && translationLines.isEmpty()) {
            currentSource = LyricSource.NONE
        }
        return currentSource
    }

    /**
     * 按顺序试四个来源，返回原始歌词文本（可能是 LRC、也可能是纯文本）。
     *
     * 每一步都会设置 [currentSource]，这样界面能告诉用户歌词是从哪来的。
     */
    private fun readLyricsRaw(context: Context, track: TrackEntity): String? {
        val audio = File(track.path)

        // 1) 内嵌歌词
        runCatching {
            if (audio.isFile) {
                val embedded = ServiceLocator.tagReader.read(audio).lyrics
                if (embedded.isNotBlank()) {
                    currentSource = LyricSource.EMBEDDED
                    return embedded
                }
            }
        }.onFailure { AppLog.d(TAG, "读内嵌歌词失败：" + it.message) }

        // 2) 同目录同名 .lrc（外挂歌词）
        runCatching {
            if (audio.isFile) {
                val stem = audio.name.substringBeforeLast('.')
                val sidecar = File(audio.parentFile, "$stem.lrc")
                if (sidecar.isFile) {
                    val text = sidecar.readText()
                    if (text.isNotBlank()) {
                        currentSource = LyricSource.SIDECAR
                        return text
                    }
                }
            }
        }.onFailure { AppLog.d(TAG, "读外挂歌词失败：" + it.message) }

        // 3) 曲库记录里的歌词文件路径
        runCatching {
            track.lyricsPath?.takeIf { it.isNotBlank() }?.let { p ->
                val f = File(p)
                if (f.isFile) {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        currentSource = LyricSource.SIDECAR
                        return text
                    }
                }
            }
        }.onFailure { AppLog.d(TAG, "读曲库歌词路径失败：" + it.message) }

        // 4) 兜底歌词目录
        runCatching {
            val settings = kotlinx.coroutines.runBlocking {
                runCatching { ServiceLocator.settings.settings.first() }.getOrNull()
            } ?: return@runCatching
            val dir = settings.fallbackDir
            if (dir.isBlank()) return@runCatching
            val target = ServiceLocator.fallbackLyrics.targetFile(track.path, dir)
            if (target.isFile) {
                val text = target.readText()
                if (text.isNotBlank()) {
                    currentSource = LyricSource.FALLBACK
                    return text
                }
            }
        }.onFailure { AppLog.d(TAG, "读兜底歌词失败：" + it.message) }

        currentSource = LyricSource.NONE
        return null
    }

    // ---------------- 曲库匹配 ----------------

    /**
     * 读音频文件的内嵌「标题 / 艺术家 / 专辑」。
     *
     * **为什么需要这一步**：曲库索引里的 `title`/`artist` 可能还是空的
     * （用户装了 App 但还没重扫标签），而 PC 推过来的标题是从它自己曲库读的、
     * 通常是准的。只靠 DB 字段匹配就会大面积失败 —— 用户看到的就是
     * "本地明明有这首歌，却说没有"。
     *
     * 所以匹配失败时退一步：现场读这首歌的内嵌标签再比一次。
     * 结果按路径缓存，同一首歌不会反复读盘。
     */
    private val tagCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) =
            size > 200
    }

    /** 取某个曲目文件的可匹配键（小写）：标题、艺术家、专辑、文件名。 */
    private fun keysOf(track: TrackEntity): List<String> {
        synchronized(tagCache) { tagCache[track.path] }?.let { return it }

        val file = File(track.path)
        val embedded = runCatching {
            if (file.isFile) ServiceLocator.tagReader.read(file) else null
        }.getOrNull()

        val keys = ArrayList<String>(6)
        fun add(s: String?) {
            val v = normalize(s.orEmpty())
            if (v.isNotBlank()) keys.add(v)
        }
        // 内嵌标签优先（更接近 PC 侧读到的值）
        add(embedded?.title)
        add(embedded?.artist)
        add(embedded?.album)
        // 数据库字段兜底
        add(track.title)
        add(track.artist)
        add(track.album)
        // 文件名永远可用
        add(track.path.substringAfterLast('/').substringBeforeLast('.'))
        add(track.path.substringAfterLast('\\').substringBeforeLast('.'))

        val distinct = keys.distinct()
        synchronized(tagCache) { tagCache[track.path] = distinct }
        return distinct
    }

    /**
     * 兜底匹配：拿 PC 侧的标题/艺术家，去每个曲目的「内嵌+DB+文件名」键里找。
     *
     * 只在主索引（数据库字段）没命中时才跑，所以正常情况不会付这份代价。
     */
    private suspend fun findLocalByEmbedded(track: RemoteTrack): TrackEntity? {
        val title = normalize(track.title)
        if (title.isBlank()) return null
        val artist = normalize(track.artist)

        val all = runCatching { ServiceLocator.tracks.allOnce() }.getOrNull() ?: return null

        // 先找「标题 + 艺术家」都对上的
        if (artist.isNotBlank()) {
            all.firstOrNull { t ->
                val keys = keysOf(t)
                title in keys && artist in keys
            }?.let { return it }
        }
        // 只对标题 —— 但要求唯一命中，避免同名不同版本认错
        val byTitle = all.filter { title in keysOf(it) }
        if (byTitle.size == 1) return byTitle.first()
        if (byTitle.size > 1) {
            val album = normalize(track.album)
            if (album.isNotBlank()) {
                byTitle.firstOrNull { album in keysOf(it) }?.let { return it }
            }
        }
        return null
    }

    private suspend fun findLocal(track: RemoteTrack): TrackEntity? {
        val idx = ensureIndex()
        if (idx == null) {
            AppLog.w(
                TAG,
                "曲库索引不可用，退回逐文件匹配（较慢，可能是数据库还没就绪或曲库为空）：" +
                    track.title
            )
        }

        if (idx != null) {
            val stem = track.path
                .substringAfterLast('\\')
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .trim()
                .lowercase()
            if (stem.isNotBlank()) {
                idx.byFileStem[stem]?.let { return it }
            }

            if (track.title.isNotBlank()) {
                idx.byTitleArtist[normalize(track.title) + "|" + normalize(track.artist)]?.let {
                    return it
                }
                idx.byTitle[normalize(track.title)]?.let { candidates ->
                    if (candidates.size == 1) return candidates.first()
                    val album = normalize(track.album)
                    if (album.isNotBlank()) {
                        candidates.firstOrNull { normalize(it.album) == album }?.let { return it }
                    }
                }
            }
        }

        // 主索引没命中：读内嵌标签再试一次
        return findLocalByEmbedded(track)
    }

    /**
     * 曲库索引：没有（或被 [invalidate] 清了）就重建。
     *
     * 以前的写法有三个坑，表现都是"本地明明有这首歌却说没有"：
     *   1. `runCatching { ... }.getOrNull()` —— 重建失败时静默返回 null，
     *      调用方只能退化成"逐个文件读内嵌标签"的全库扫描（很慢），
     *      而且失败一次之后**每次**解析都要再赔一遍；
     *   2. 重建出来的索引是**空**的（数据库还没写完、扫描刚结束的一瞬间）
     *      也会被当成有效索引缓存下来，之后的解析全部落空；
     *   3. 并发解析（小组件 + 控制页 + 重连对齐可能同时跑）会各自重建一遍索引。
     *
     * 现在：空结果不缓存（下次还会重建），失败也记 [AppLog.w] 并允许下次重试，
     * 重建过程用一把锁串起来。
     */
    private suspend fun ensureIndex(): Index? {
        index?.takeIf { !it.isEmpty }?.let { return it }
        /*
         * 这里用 [Mutex] 而不是 `synchronized`：重建索引要 `await` 一次数据库查询
         * （`allOnce()` 是 suspend），而**挂起点不允许出现在 `synchronized` 块里**
         * —— Kotlin 直接编译报错（"suspension point is inside a critical section"），
         * 就算能编过也会在持锁期间阻塞整个线程。协程锁才是对的。
         */
        return indexMutex.withLock {
            // 双检：等锁的这段时间里别的协程可能已经建好了
            index?.takeIf { !it.isEmpty }?.let { return@withLock it }
            // 失败（null）：不缓存任何东西，让下一次解析再试。
            // 这里用 `?: return` 而不是可空变量 —— 下面要读它的统计值。
            val built = runCatching {
                val all = ServiceLocator.tracks.allOnce()

                val byStem = HashMap<String, TrackEntity>(all.size)
                val byTa = HashMap<String, TrackEntity>(all.size)
                val byTitle = HashMap<String, MutableList<TrackEntity>>(all.size)

                for (t in all) {
                    val stem = t.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
                    if (stem.isNotBlank()) byStem.putIfAbsent(stem, t)

                    if (t.title.isNotBlank()) {
                        byTa.putIfAbsent(normalize(t.title) + "|" + normalize(t.artist), t)
                        byTitle.getOrPut(normalize(t.title)) { ArrayList(2) }.add(t)
                    }
                }

                Index(byStem, byTa, byTitle)
            }.onFailure {
                AppLog.w(TAG, "构建本地曲库索引失败（本次回退到逐文件匹配）：" + it.message)
            }.getOrNull() ?: return@withLock null

            if (built.isEmpty) {
                // 空索引：**不缓存**。刚扫描完/数据库还没落盘时会短暂是空的，
                // 缓存下来就等于"本地永远没有这首歌"。
                AppLog.w(TAG, "曲库查询返回了空索引（曲库可能是空的，或数据库还没就绪），本次不缓存")
                return@withLock null
            }
            AppLog.i(TAG, "本地曲库索引已重建（${built.byTitle.size} 个标题）")
            index = built
            built
        }
    }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    // ---------------- 封面 ----------------

    /**
     * 取封面。
     *
     * 优先用曲库已经缓存好的封面文件，否则现场从音频文件里解内嵌图或
     * 同目录外置图。都没有就返回 null，界面用占位图。
     */
    private fun loadCover(track: TrackEntity): Bitmap? = runCatching {
        track.coverPath?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile) {
                BitmapFactory.decodeFile(p)?.let { return it }
            }
        }
        if (!track.hasCover && track.coverPath.isNullOrBlank()) {
            // 曲库说没有封面，就不用去读文件了 —— 省掉大量 IO
            return null
        }

        val file = File(track.path)
        if (!file.isFile) return null
        when (val src = ArtworkResolver.resolve(file, ArtworkKind.COVER)) {
            is ArtworkResolver.Source.External -> BitmapFactory.decodeFile(src.path)
            is ArtworkResolver.Source.Bytes ->
                BitmapFactory.decodeByteArray(src.data, 0, src.data.size)
            null -> null
        }
    }.onFailure {
        AppLog.d(TAG, "取封面失败：" + it.message)
    }.getOrNull()
}
