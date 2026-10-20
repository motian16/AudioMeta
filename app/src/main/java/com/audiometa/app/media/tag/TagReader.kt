package com.audiometa.app.media.tag

import android.media.MediaMetadataRetriever
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.util.AppLog
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.Artwork
import java.io.File

class TagReader {

    init {
        // 读内嵌图会走 jaudiotagger 的 Artwork 实现，必须先切到 Android 模式，
        // 否则它会去加载 javax.imageio。见 [JaudioTagger]。
        JaudioTagger.ensureAndroidMode()
    }

    /**
     * 读出文件里的**全部**标签，键 → 值。
     *
     * 这是"未知标签"功能的基础：基础字段（标题、专辑、歌词…）只覆盖了一小部分，
     * 平台私有标签（酷狗的 kghash、QQ 的 QMQuality、网易云的 NCM 残留…）
     * 只能靠遍历整个标签表才拿得到。
     *
     * 文本字段直接取字符串；**图片字段**交给 [imageSink] 落盘后记一个标记值 ——
     * 有些自定义标签的内容本身就是一张图，那种值不能在界面上当文字显示。
     * 键名保留容器的原始写法（`TXXX:FOO`、`----:com.apple.iTunes:BAR`），
     * 归一化和来源识别交给 [com.audiometa.app.domain.tagrules.TagRules]。
     */
    fun readAllTags(
        file: File,
        imageSink: ((ByteArray, String) -> String?)? = null
    ): Map<String, String> {
        if (!canReadTags(file)) return emptyMap()
        val fromLibrary = runCatching {
            collectFields(AudioFileIO.read(file).tag, imageSink, file)
        }.getOrElse {
            logReadFailure(file, it)
            emptyMap()
        }
        if (fromLibrary.isNotEmpty()) return fromLibrary
        // 解析库读不出来（或这个文件根本没有标签结构）时，FLAC 仍然自己解一遍：
        // Vorbis Comment 是明文，读到多少算多少，总比整首歌的标签全丢好。
        return readRawVorbisComments(file)
    }

    /**
     * 一个标签字段在**文件里的真实键名**。
     *
     * ID3v2 的 `getId()` 只给帧号（"TXXX"），**描述不在里面** —— 一个文件里
     * 十个 TXXX 帧（major_brand / minor_version / compatible_brands / hasVideo…）
     * 会全被并成同一个键 "TXXX"，值用换行拼成一坨。用户报的"键名只显示一个、
     * 格式不对"就是这个原因。这里把帧体里的描述接上去，得到 `TXXX:major_brand`。
     *
     * MP4 的自由原子（`----:com.apple.iTunes:xxx`）和 Vorbis Comment 本来就是
     * 完整键名，不用补。
     */
    private fun keyOf(field: org.jaudiotagger.tag.TagField): String {
        val base = runCatching { field.getId() }.getOrNull()?.trim().orEmpty()
        if (base.isEmpty()) return ""
        val description = descriptionOf(field)
        if (description.isBlank()) return base
        // 描述已经拼在键名里了（某些容器会这样）就不要重复。
        if (base.endsWith(":" + description)) return base
        return base + ":" + description
    }

    /** 帧体里的描述。只有带"描述-值"结构的帧才有（TXXX / WXXX / COMM / USLT / APIC）。 */
    private fun descriptionOf(field: org.jaudiotagger.tag.TagField): String {
        val body = runCatching {
            (field as? org.jaudiotagger.tag.id3.AbstractTagFrame)?.body
        }.getOrNull() ?: return ""
        return runCatching {
            when (body) {
                is org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX -> body.description
                is org.jaudiotagger.tag.id3.framebody.FrameBodyWXXX -> body.description
                is org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM -> body.description
                is org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT -> body.description
                is org.jaudiotagger.tag.id3.framebody.FrameBodyAPIC -> body.description
                else -> ""
            }?.trim().orEmpty()
        }.getOrDefault("")
    }

    /**
     * 记录一次标签读取失败 —— 但**只记真看不懂的**。
     *
     * 有两类失败是"正常的"：容器本身没有解析器（扩展名已经在 [canReadTags] 挡掉了，
     * 偶尔仍会漏），以及扩展名是 mp4 但里面装的是视频。这两种都在预期之内，
     * 记成 DEBUG 只会把运行日志刷满 —— 用户上一次贴过来的日志里，
     * 一大半就是这些行，把真正的问题淹掉了。
     */
    private fun logReadFailure(file: File, error: Throwable) {
        val message = error.message.orEmpty()
        val expected = message.contains("No Reader associated with this extension") ||
            message.contains("video files are not supported")
        if (expected) return
        AppLog.d(TAG, "读取全部标签失败 " + file.name + "：" + message)
    }

    /**
     * jaudiotagger 到底认不认这个扩展名。
     *
     * 它只对固定几个容器有解析器（mp3 / flac / ogg / m4a / wav / wma / aiff / dsf / dff / ra）。
     * 曲库扫描允许的格式比这宽得多（aac、mid、awb、ape、wv…），拿这些文件去调
     * `AudioFileIO.read` 只会抛 "No Reader associated with this extension"，
     * 几百首就是几百条没有意义的报错。这里直接按格式短路：
     * **读不了就不读**，第一阶段的 MediaMetadataRetriever 已经把基础字段拿到了。
     */
    fun canReadTags(file: File): Boolean {
        val ext = AudioFormats.normalize(file.extension)
        return ext in FULL_TAG_FORMATS
    }

    /**
     * 把一个已经解析好的标签表摊平成"键 → 值"。
     *
     * [readAllTags] 和 [read] 都走这里：[read] 本来就要把标签结构完整解析一遍，
     * 顺手把原文带出来，比之后再读一次文件便宜得多（几百首就是几百次重复 IO）。
     */
    private fun collectFields(
        tag: Tag?,
        imageSink: ((ByteArray, String) -> String?)?,
        file: File? = null
    ): Map<String, String> {
        // FLAC / Ogg 的 Vorbis Comment 要**自己解一遍**，把解析库可能漏掉的字段补上
        //（用户拿 MediaInfo 对比过，文件里明明有的字段软件没读出来）。
        //
        // 但这里只解**一次**：以前它在上面的循环里每遇到一个文本字段就重新把
        // 整个 FLAC 头读一遍、再建一张新 map，一个 40 个字段的 flac 就是 40 次
        // 全量重读 + 40 张临时 map。扫描是 4 个文件并发跑的，几百首下来
        // 就是持续不断的分配压力，堆紧张的机器上直接 OOM。
        val rawVorbis = if (file != null) readRawVorbisComments(file) else emptyMap()
        if (tag == null) return rawVorbis
        val out = LinkedHashMap<String, String>()
        val iterator = tag.fields
        var count = 0
        while (iterator.hasNext()) {
            val field = iterator.next() ?: continue
            // 单个文件最多认这么多个标签。畸形/被写坏的文件里字段数可以是任意的，
            // 没有上限时"全读进内存"这件事就是没有边界的。
            if (count >= MAX_TAGS_PER_FILE) {
                AppLog.w(TAG, "标签数量超过 " + MAX_TAGS_PER_FILE + " 个，其余忽略：" + (file?.name ?: ""))
                break
            }
            val key = keyOf(field)
            if (key.isBlank()) continue
            count++

            if (runCatching { field.isBinary }.getOrDefault(false)) {
                // 二进制字段：只可能是图片。标准封面/艺术家图那几帧不用管 ——
                // 它们本来就会被白名单过滤掉，落盘纯属浪费。
                if (imageSink == null) continue
                if (com.audiometa.app.domain.tagrules.TagRules.isStandardKey(key)) continue
                val (bytes, mime) = imageBytesOf(field) ?: continue
                val marker = imageSink(bytes, mime) ?: continue
                out[key] = marker
                continue
            }

            // TagTextField 才是文本；其余（MP4 的 atom 之类）用 toString 兜底。
            val value = when (field) {
                is org.jaudiotagger.tag.TagTextField -> runCatching { field.content }.getOrNull()
                else -> runCatching { field.toString() }.getOrNull()
            }?.trim().orEmpty().take(MAX_VALUE_CHARS)
            // 值空的标签也留着（用户要求："有的只有键，没有值，也显示出来"），
            // 要不要显示由上层决定 —— 这里丢掉的话上层根本没机会知道它存在。
            out[key] = if (out.containsKey(key)) out[key] + "\n" + value else value
        }

        // 只补不收：库已经给出的键一律不动。
        if (rawVorbis.isNotEmpty()) {
            val existing = out.keys.mapTo(HashSet()) { it.lowercase() }
            rawVorbis.forEach { (key, value) ->
                if (key.lowercase() !in existing) out[key] = value
            }
        }
        return out
    }

    /**
     * 直接按 FLAC 容器格式扫出 VORBIS_COMMENT 里的全部键值。
     *
     * 格式（都是小端）：
     * ```
     * "fLaC"
     * 若干元数据块：1 字节（最后一块标志 << 7 | 类型），3 字节大端长度，然后是数据
     * 类型 4 = VORBIS_COMMENT：
     *     u32 vendor 长度 + vendor
     *     u32 条目数
     *     每条：u32 长度 + "KEY=value"（UTF-8）
     * ```
     * 目的是**读全**：解析库对自定义字段的处理不完全可靠，自己扫一遍最保险。
     */
    internal fun readRawVorbisComments(file: File): Map<String, String> = runCatching {
        if (AudioFormats.normalize(file.extension) != "flac") return@runCatching emptyMap()
        java.io.RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) != 4) return@runCatching emptyMap()
            if (String(magic, Charsets.US_ASCII) != "fLaC") return@runCatching emptyMap()

            var last = false
            while (!last) {
                val header = raf.read()
                if (header < 0) break
                last = (header and 0x80) != 0
                val type = header and 0x7F
                val lenBytes = ByteArray(3)
                if (raf.read(lenBytes) != 3) break
                val length = ((lenBytes[0].toInt() and 0xFF) shl 16) or
                    ((lenBytes[1].toInt() and 0xFF) shl 8) or
                    (lenBytes[2].toInt() and 0xFF)
                if (type != 4) {
                    raf.seek(raf.filePointer + length)
                    continue
                }
                val data = ByteArray(length)
                if (raf.read(data) != length) break
                return@use parseVorbisBlock(data)
            }
            emptyMap()
        }
    }.getOrDefault(emptyMap())

    private fun parseVorbisBlock(data: ByteArray): Map<String, String> {
        var pos = 0
        fun readU32(): Int {
            if (pos + 4 > data.size) return -1
            val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        val vendorLength = readU32()
        if (vendorLength < 0) return emptyMap()
        pos += vendorLength
        val count = readU32()
        if (count < 0) return emptyMap()

        val out = LinkedHashMap<String, String>()
        repeat(count) {
            val length = readU32()
            if (length < 0 || pos + length > data.size) return out
            val text = String(data, pos, length, Charsets.UTF_8)
            pos += length
            val eq = text.indexOf('=')
            if (eq <= 0) return@repeat
            val key = text.substring(0, eq).trim()
            val value = text.substring(eq + 1).trim()
            if (key.isBlank() || value.isBlank()) return@repeat
            out[key] = if (out.containsKey(key)) out[key] + "\n" + value else value
        }
        return out
    }

    /**
     * 从一个二进制字段里取出图片字节。
     *
     * jaudiotagger 的图片字段在不同容器里长得不一样（ID3 是 Artwork，
     * Vorbis 是 METADATA_BLOCK_PICTURE 解出来的字节），统一按 [Artwork] 处理，
     * 拿不到就当它不是图片。
     */
    private fun imageBytesOf(field: Any): Pair<ByteArray, String>? = runCatching {
        val artwork = field as? Artwork ?: return@runCatching null
        val bytes = artwork.binaryData ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null
        if (!looksLikeImage(bytes)) return@runCatching null
        bytes to artwork.mimeType.orEmpty().ifBlank { sniffMime(bytes) }
    }.getOrNull()

    /** 只认真正的图片文件头，避免把指纹之类的大块二进制当成图片存下来。 */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        fun at(i: Int): Int = bytes[i].toInt() and 0xFF
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> true                    // JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> true    // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true    // GIF
            at(0) == 0x42 && at(1) == 0x4D -> true                     // BMP
            at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 -> true    // WEBP
            else -> false
        }
    }

    private fun sniffMime(bytes: ByteArray): String {
        fun at(i: Int): Int = bytes[i].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 -> "image/gif"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            at(8) == 0x57 && at(9) == 0x45 -> "image/webp"
            else -> "image/jpeg"
        }
    }

    fun read(
        file: File,
        imageSink: ((ByteArray, String) -> String?)? = null
    ): TrackMeta {
        // jaudiotagger 不认的格式（aac / mid / awb / ape…）直接退回"没有标签"，
        // 别去撞那个必然失败的解析器，也别在日志里刷一堆没意义的报错。
        if (!canReadTags(file)) return untagged(file)
        val meta = runCatching { readTagged(file, imageSink) }.getOrElse {
            logReadFailure(file, it)
            // 解析库失败也要把 FLAC 的 Vorbis Comment 自己解出来 ——
            // 「元信息」页靠的就是这一份，不能因为库读不动就整首歌都没有自定义标签。
            untagged(file).copy(rawTags = readRawVorbisComments(file))
        }
        return if (meta.durationMs > 0) meta else meta.copy(durationMs = fallbackDuration(file))
    }

    /**
     * 第一阶段（扫描）只读这些：能立刻把列表画出来的最小集合。
     *
     * 走 MediaMetadataRetriever 而不走 jaudiotagger：后者为了读几个字段
     * 要把整个标签结构解析一遍，几百首要几十秒，第一阶段就不"快"了。
     * Retriever 是系统实现，读这几个键基本是毫秒级。
     *
     * 曲风也在这里读：Retriever 从 API 10 起就有 METADATA_KEY_GENRE，
     * 不需要为了曲风把第一阶段拖慢。
     */
    fun readBasic(file: File): TrackMeta {
        val base = runCatching { readBasicViaRetriever(file) }.getOrNull()
        // Retriever 不认识的容器（ape / wv / tta 之类）退回完整解析，
        // 这些格式一般量不大，慢一点也只影响第一阶段里的一小部分。
        if (base == null) return readBasicViaFullParse(file)
        if (!base.looksBroken()) {
            return base.copy(title = base.title.ifBlank { file.nameWithoutExtension })
        }
        // Retriever 读出来是坏的（见 looksBroken 的说明），
        // 用 jaudiotagger 的完整解析结果**逐字段替换坏掉的那些**，
        // 其余字段仍然用 Retriever 的值（比如时长，Retriever 更省事）。
        val full = read(file)
        return base.copy(
            title = repair(base.title, full.title).ifBlank { file.nameWithoutExtension },
            artist = repair(base.artist, full.artist),
            album = repair(base.album, full.album),
            albumArtist = repair(base.albumArtist, full.albumArtist)
        )
    }

    /** [value] 是纯问号就换成 [fallback]，否则保留原值。 */
    private fun repair(value: String, fallback: String): String {
        val t = value.trim()
        val broken = t.isNotEmpty() && t.all { it == '?' || it == '\uFFFD' }
        return if (broken) fallback.trim() else value
    }

    private fun readBasicViaFullParse(file: File): TrackMeta {
        val full = read(file)
        return full.copy(
            language = "",
            composer = "",
            lyricist = "",
            conductor = "",
            comment = "",
            copyright = "",
            isrc = "",
            bpm = "",
            discNo = 0
        )
    }

    /**
     * 这份元信息是不是明显坏了（需要退回完整解析）。
     *
     * 判据：标题、艺术家、专辑里**任何一项**是纯问号/替换字符。
     *
     * 只看"是否非空"是不够的 —— 坏值恰恰是非空的。
     * 某些 ID3v2.3 的 Latin-1 文本帧里存了中文时，
     * MediaMetadataRetriever 会把每个中文字符换成 `?` 而不是返回空，
     * 于是旧判断 `title.isNotBlank() || artist.isNotBlank()` 放过了它。
     * 曲库里的表现就是：某首 mp3 在艺术家页显示成 `???`，
     * 点进歌曲详情页却正常 —— 因为详情页是 jaudiotagger 现读的，没走这份坏数据；
     * 而后续"补全"阶段只更新解析状态位，不回写标题/艺术家，
     * 所以坏值一旦入库就再也不会被纠正。
     */
    private fun TrackMeta.looksBroken(): Boolean {
        fun broken(text: String): Boolean {
            val t = text.trim()
            // 空字符串不算坏：那是"没读到"，会在下面按字段补。
            if (t.isEmpty()) return false
            return t.all { it == '?' || it == '\uFFFD' }
        }
        return broken(title) || broken(artist) || broken(album)
    }

    private fun readBasicViaRetriever(file: File): TrackMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            fun key(k: Int): String =
                retriever.extractMetadata(k)?.trim().orEmpty()
            val title = key(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .ifBlank { key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }
            val album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val year = key(MediaMetadataRetriever.METADATA_KEY_YEAR)
                .take(4).filter { it.isDigit() }
            TrackMeta(
                title = title.ifBlank { file.nameWithoutExtension },
                artist = artist.ifBlank { guessArtist(file) },
                album = album,
                albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNo = key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    .substringBefore('/').trim().toIntOrNull() ?: 0,
                year = year.takeIf { it.length == 4 }?.toIntOrNull() ?: 0,
                // 多曲风在标签里常写成 "Rock/Pop" 或 "Rock; Pop"，解析时先摊平，
                // 后续入库会按换行分隔存成多值。
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readTagged(
        file: File,
        imageSink: ((ByteArray, String) -> String?)? = null
    ): TrackMeta {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val header = audio.audioHeader
        val lyrics = readLyrics(tag)
        val translation = readTranslation(tag)
        val artworks = runCatching { tag?.artworkList.orEmpty() }.getOrDefault(emptyList())
        val hasArtist = artworks.any { isArtistArtwork(it) }
        // 只有非"艺术家图"的图片才算封面。之前把任意图片都当作封面，
        // 结果只带艺术家图的文件也会被判定成"已有封面"，刮削时被跳过。
        val hasCover = artworks.any { !isArtistArtwork(it) }
        // 日期：不同容器写法不一样，按优先级挨个试。
        val rawDate = firstNonBlank(
            field(tag, FieldKey.YEAR),
            field(tag, FieldKey.ALBUM_YEAR),
            customField(tag, "DATE"),
            customField(tag, "YEAR"),
            customField(tag, "TDRC"),
            customField(tag, "ORIGINALDATE"),
            customField(tag, "ORIGINALYEAR"),
            customField(tag, "©day")
        )
        return TrackMeta(
            title = field(tag, FieldKey.TITLE).ifBlank { file.nameWithoutExtension },
            artist = field(tag, FieldKey.ARTIST),
            albumArtist = firstNonBlank(
                field(tag, FieldKey.ALBUM_ARTIST),
                customField(tag, "ALBUMARTIST"),
                customField(tag, "ALBUM ARTIST"),
                customField(tag, "aART")
            ),
            album = firstNonBlank(field(tag, FieldKey.ALBUM), customField(tag, "©alb")),
            trackNo = firstNonBlank(field(tag, FieldKey.TRACK), customField(tag, "TRACKNUMBER"), customField(tag, "trkn"))
                .substringBefore('/').trim().toIntOrNull() ?: 0,
            discNo = firstNonBlank(
                field(tag, FieldKey.DISC_NO),
                customField(tag, "DISCNUMBER"),
                customField(tag, "DISC"),
                customField(tag, "disk")
            ).substringBefore('/').trim().toIntOrNull() ?: 0,
            year = rawDate.take(4).filter { it.isDigit() }.takeIf { it.length == 4 }?.toIntOrNull() ?: 0,
            date = rawDate,
            genre = firstNonBlank(field(tag, FieldKey.GENRE), customField(tag, "©gen")),
            language = firstNonBlank(
                field(tag, FieldKey.LANGUAGE),
                customField(tag, "LANGUAGE"),
                customField(tag, "lang")
            ),
            composer = firstNonBlank(field(tag, FieldKey.COMPOSER), customField(tag, "©wrt")),
            lyricist = firstNonBlank(field(tag, FieldKey.LYRICIST), customField(tag, "TEXT")),
            conductor = field(tag, FieldKey.CONDUCTOR),
            comment = firstNonBlank(
                field(tag, FieldKey.COMMENT),
                customField(tag, "COMMENT"),
                customField(tag, "DESCRIPTION"),
                customField(tag, "©cmt")
            ),
            copyright = firstNonBlank(field(tag, FieldKey.COPYRIGHT), customField(tag, "cprt")),
            isrc = firstNonBlank(field(tag, FieldKey.ISRC), customField(tag, "ISRC")),
            bpm = firstNonBlank(field(tag, FieldKey.BPM), customField(tag, bpmKey(tag))),
            durationMs = runCatching { (header?.trackLength?.toLong() ?: 0L) * 1000L }.getOrDefault(0L),
            hasArtistImage = hasArtist,
            hasCover = hasCover,
            hasLyrics = lyrics.isNotBlank(),
            hasTranslation = translation.isNotBlank() || looksLikeTranslation(lyrics),
            lyricsType = lyricsTypeOf(lyrics),
            lyrics = lyrics,
            translation = translation,
            // 顺带把全部标签原文带出去：「元信息」页的扫描直接用这一份，
            // 不再为了拿平台私有标记把每个文件重读一遍。
            rawTags = collectFields(tag, imageSink, file)
        )
    }

    /**
     * BPM 的自由字段名。
     *
     * MP3 的 ID3 里是 TBPM，直接当自由字段名读没有意义（getFirst 只按帧 id 找），
     * 所以只在确实需要时才去试，避免每次都白跑一趟。
     */
    private fun bpmKey(tag: Tag?): String =
        if (tag is org.jaudiotagger.tag.id3.AbstractID3Tag) "TBPM" else "TEMPO"

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun untagged(file: File): TrackMeta = TrackMeta(
        title = file.nameWithoutExtension,
        artist = guessArtist(file),
        lyricsType = "none"
    )

    private fun field(tag: Tag?, key: FieldKey): String {
        if (tag == null) return ""
        return runCatching { tag.getFirst(key).orEmpty() }.getOrDefault("")
    }

    /** 读取非标准字段，例如部分工具写入的独立翻译轨。 */
    private fun customField(tag: Tag?, key: String): String {
        if (tag == null) return ""
        return runCatching { tag.getFirst(key).orEmpty() }.getOrDefault("")
    }

    private fun readLyrics(tag: Tag?): String {
        val direct = field(tag, FieldKey.LYRICS)
        if (direct.isNotBlank()) return direct
        for (key in listOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")) {
            val value = customField(tag, key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun readTranslation(tag: Tag?): String {
        for (key in listOf(
            "LYRICS_TRANSLATION",
            "LYRICS_TRANS",
            "LYRICS_ZH",
            "LYRICS_TXX",
            "TRANSLATEDLYRICS"
        )) {
            val value = customField(tag, key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun looksLikeTranslation(lyrics: String): Boolean =
        lyrics.contains("[tr:") || lyrics.contains("[translation]")

    private fun lyricsTypeOf(lyrics: String): String = when {
        lyrics.isBlank() -> "none"
        lyrics.contains("<") && lyrics.contains(">") -> "word"
        lyrics.contains("[") -> "line"
        else -> "plain"
    }

    private fun isArtistArtwork(artwork: Artwork): Boolean {
        val desc = artwork.description.orEmpty()
        return artwork.pictureType == 8 ||
            desc.equals("Artist", true) ||
            desc.contains("Cover Art (Artist)", true)
    }

    private fun fallbackDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun guessArtist(file: File): String {
        val name = file.nameWithoutExtension
        val parts = name.split(" - ", " – ", limit = 2)
        return if (parts.size == 2) parts[0].trim() else ""
    }

    private companion object {
        const val TAG = "TagReader"

        /**
         * 单个文件最多读进来多少个标签。
         *
         * 正常文件几十个顶天了；这个上限是防"畸形/被写坏的文件里字段数任意大"
         * 把整张表拉进内存。到了上限只记一条日志，已经读到的照常返回。
         */
        const val MAX_TAGS_PER_FILE = 512

        /**
         * 单个标签值最长保留多少字符。
         *
         * 歌词、内嵌 base64 之类可以到几 MB，而这一份是**整个标签表**，
         * 扫描时 4 个文件并发，一条超大值乘上并发数就足以打爆堆。
         * 界面上一行显示不了这么多，搜索也只匹配前缀，所以截断是安全的。
         */
        const val MAX_VALUE_CHARS = 16_384

        /**
         * jaudiotagger 真正有解析器的扩展名。
         *
         * 来源是它的 `AudioFileIO.prepareReadersAndWriters()` 注册表：
         * Ogg / Flac / MP3 / Mp4(m4a,m4b,mp4,m4p) / Wav / Asf(wma) /
         * Aiff(aif,aiff,aifc) / Dsf / Dff / Real(ra)。
         *
         * 曲库允许的格式比这宽得多，不在这个表里的一律不去调 jaudiotagger：
         * 调了必然抛 "No Reader associated with this extension"，
         * 用户日志里那批 aac / mid / awb 的报错就是这么来的。
         */
        val FULL_TAG_FORMATS = setOf(
            "mp3", "flac", "ogg", "oga",
            "m4a", "m4b", "mp4", "m4p",
            "wav", "wma", "asf",
            "aif", "aiff", "aifc",
            "dsf", "dff", "ra"
        )
    }
}
