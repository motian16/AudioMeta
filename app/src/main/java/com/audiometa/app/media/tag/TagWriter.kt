package com.audiometa.app.media.tag

import android.util.Base64
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.LyricsItem
import com.audiometa.app.domain.model.OverwritePolicy
import com.audiometa.app.domain.model.PictureKind
import com.audiometa.app.domain.model.PicturePayload
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.util.AppLog
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagCoverField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TagWriteException(message: String) : Exception(message)

class TagWriter {
    init {
        // 兜底：确保 jaudiotagger 处于 Android 模式，否则会去解析 javax.imageio。
        JaudioTagger.ensureAndroidMode()
    }
    fun supportsEmbed(ext: String): Boolean {
        val n = AudioFormats.normalize(ext)
        return n !in AudioFormats.NO_EMBED
    }

    /**
     * 安全写入：先复制到临时文件，改完临时文件再替换原文件。
     * 失败时原文件保持不动。
     */
    fun write(
        file: File,
        pictures: List<PicturePayload> = emptyList(),
        lyrics: List<LyricsItem> = emptyList(),
        meta: TrackMeta? = null,
        /**
         * 逐项编辑时直接写的原始键值。
         *
         * 详情页的"手动编辑某一项"走这条路：它按 FieldKey 精确改写单个字段
         * （不经过 TrackMeta 的整体合并），曲风这类多值字段也在这里处理。
         */
        rawTags: Map<String, String> = emptyMap(),
        policy: OverwritePolicy = OverwritePolicy.FILL_MISSING,
        existing: TrackMeta? = null,
        keepModTime: Boolean = true
    ) {
        if (!file.exists()) throw TagWriteException("文件不存在: ${file.absolutePath}")
        val ext = AudioFormats.normalize(file.extension)
        if (!supportsEmbed(ext) && pictures.isNotEmpty()) {
            throw TagWriteException("该格式不支持内嵌")
        }
        val parent = file.parentFile ?: throw TagWriteException("无法定位父目录")
        // 临时文件必须保留原扩展名：jaudiotagger 是按扩展名挑解析器的，
        // 之前用 ".tmp_meta" 会直接报 "No Reader associated with this extension:tmp_meta"。
        val tempName = if (ext.isBlank()) {
            file.name + ".metatmp"
        } else {
            file.nameWithoutExtension + ".metatmp." + ext
        }
        val temp = File(parent, tempName)
        val backupStamp = file.lastModified()
        try {
            Files.copy(file.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val audio = AudioFileIO.read(temp)
            ensureTag(audio, ext)
            val tag = audio.tag ?: throw TagWriteException("无法创建标签")
            if (meta != null) applyMeta(tag, meta, policy, existing)
            if (rawTags.isNotEmpty()) applyRawTags(tag, rawTags)
            pictures.forEach { applyPicture(tag, ext, it, policy, existing) }
            if (lyrics.isNotEmpty()) applyLyrics(tag, ext, lyrics, policy, existing)
            audio.commit()
            // ATOMIC_MOVE 部分文件系统不支持，回退成普通替换，
            // 避免整次写入因为一个可选优化而失败。
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.FileSystemException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (keepModTime) file.setLastModified(backupStamp)
        } catch (e: TagWriteException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            if (!file.exists() || file.length() == 0L) {
                throw TagWriteException("写入中断且原文件异常: " + describe(e))
            }
            throw TagWriteException(describe(e))
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * 把整条异常链写出来。
     * 有些异常没有 message（例如 UnsupportedOperationException()），只看 message
     * 会得到「写入失败」这种毫无信息量的报错，根本没法定位。
     */
    private fun describe(e: Throwable): String = generateSequence(e) { it.cause }
        .take(6)
        .joinToString(" <- ") { t ->
            val name = t.javaClass.name
            val msg = t.message
            if (msg.isNullOrBlank()) name else name + ": " + msg
        }

    private fun ensureTag(audio: AudioFile, ext: String) {
        if (audio.tag != null) return
        audio.tag = when {
            ext in AudioFormats.ID3_PICTURE -> org.jaudiotagger.tag.id3.ID3v23Tag()
            else -> audio.createDefaultTag()
        }
    }

    /**
     * 逐项编辑：按字段键直接改写。
     *
     * 只在 `rawTags` 里出现的字段才动，没提到的字段保持原样——
     * 这一点很重要：详情页"只改流派"不该顺手把别的字段也重写一遍。
     *
     * 值传空字符串表示删除该字段（用户把内容清空的意图）。
     *
     * ⚠️ 目前只支持 [FieldKey] 里有的标准字段。
     * jaudiotagger 的 `Tag` 接口里 `createField` 只接受 `FieldKey` 枚举，
     * 没有"用任意键名建字段"的通用方法——要写平台私有标签（kghash 之类）
     * 得按容器分别实现（ID3 走 frameMap、FLAC 走 VorbisComment、MP4 走 atom）。
     * 那是独立的一块工作，不在这里假装支持：不认识的键会被跳过并记日志。
     */
    private fun applyRawTags(tag: Tag, rawTags: Map<String, String>) {
        rawTags.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isBlank()) return@forEach
            val fieldKey = runCatching { FieldKey.valueOf(key.uppercase()) }.getOrNull()
            if (fieldKey == null) {
                AppLog.d(TAG, "跳过非标准字段 $key（当前只支持标准字段的逐项编辑）")
                return@forEach
            }
            val value = rawValue.trim()
            runCatching {
                if (value.isEmpty()) {
                    tag.deleteField(fieldKey)
                } else {
                    runCatching { tag.deleteField(fieldKey) }
                    tag.setField(fieldKey, value)
                }
            }.onFailure {
                AppLog.d(TAG, "写入字段 " + key + " 失败：" + it.message)
            }
        }
    }

    private fun applyMeta(tag: Tag, meta: TrackMeta, policy: OverwritePolicy, existing: TrackMeta?) {
        /*
         * 【红线】标题 / 艺术家 / 专辑**永远不由刮削改写**。
         *
         * 用户报过一个严重问题：给某位艺术家刮了艺术家图之后，这位艺术家名下
         * **所有歌曲的内嵌标题都变成了同一首歌**。原因就在这里 —— 以前这三行是写的，
         * 而详情页的刮削入口是"覆盖"策略（forceOverwrite），于是每首歌都被写进了
         * 这次搜索匹配到的那首的标题；艺术家图会扩展到该艺术家的全部曲目，
         * 所以整批文件一起被改坏。
         *
         * 组合刮削的确认弹窗上也写着「基础标签（只读，不会修改）」，界面和写入
         * 现在终于是同一套规则：**只有详情页「元信息」区域的手动编辑**
         * （走 [rawTags]，见 RawTagWriter）才能改这三个字段。
         */
        if (meta.title.isNotBlank() || meta.artist.isNotBlank() || meta.album.isNotBlank()) {
            val dropped = com.audiometa.app.domain.model.ProtectedFields.protectedOf(meta)
            AppLog.d(TAG, "刮削不改" + dropped.joinToString("/") + "，已忽略（要改请在详情页的元信息里手动改）")
        }
        setText(tag, FieldKey.ALBUM_ARTIST, meta.albumArtist, policy, existing?.albumArtist)
        if (meta.trackNo > 0) {
            setText(tag, FieldKey.TRACK, meta.trackNo.toString(), policy, existing?.trackNo?.takeIf { it > 0 }?.toString())
        }
        if (meta.discNo > 0) {
            setText(tag, FieldKey.DISC_NO, meta.discNo.toString(), policy, existing?.discNo?.takeIf { it > 0 }?.toString())
        }
        if (meta.year > 0) {
            setText(tag, FieldKey.YEAR, meta.year.toString(), policy, existing?.year?.takeIf { it > 0 }?.toString())
        }
        // 完整日期单独写：FLAC / Ogg 只认 DATE 这类自由字段，ID3 才有 TDRC。
        if (meta.date.isNotBlank()) {
            setCustomOrField(tag, FieldKey.YEAR, "DATE", meta.date, policy, existing?.date)
        }
        setText(tag, FieldKey.GENRE, meta.genre, policy, existing?.genre)
        setText(tag, FieldKey.LANGUAGE, meta.language, policy, existing?.language)
        setText(tag, FieldKey.COMPOSER, meta.composer, policy, existing?.composer)
        setText(tag, FieldKey.LYRICIST, meta.lyricist, policy, existing?.lyricist)
        setText(tag, FieldKey.CONDUCTOR, meta.conductor, policy, existing?.conductor)
        setText(tag, FieldKey.COMMENT, meta.comment, policy, existing?.comment)
        setText(tag, FieldKey.COPYRIGHT, meta.copyright, policy, existing?.copyright)
        setText(tag, FieldKey.ISRC, meta.isrc, policy, existing?.isrc)
        setText(tag, FieldKey.BPM, meta.bpm, policy, existing?.bpm)
    }

    /**
     * 优先当自由字段写（DATE 这类 Vorbis/FLAC 的标准键），
     * 失败再退回 FieldKey。ID3 的自由字段写法不一定支持，所以要留退路。
     */
    private fun setCustomOrField(
        tag: Tag,
        key: FieldKey,
        customKey: String,
        value: String,
        policy: OverwritePolicy,
        current: String?
    ) {
        if (canSkip(policy, current)) return
        if (putCustomText(tag, customKey, value)) return
        runCatching {
            tag.deleteField(key)
            tag.setField(key, value)
        }
    }

    private fun canSkip(policy: OverwritePolicy, current: String?): Boolean {
        val has = !current.isNullOrBlank()
        return when (policy) {
            OverwritePolicy.SKIP_EXISTING -> has
            OverwritePolicy.FILL_MISSING -> has
            OverwritePolicy.FORCE -> false
        }
    }

    private fun setText(tag: Tag, key: FieldKey, value: String, policy: OverwritePolicy, current: String?) {
        if (value.isBlank()) return
        if (canSkip(policy, current)) return
        runCatching {
            tag.deleteField(key)
            tag.setField(key, value)
        }
    }

    private fun applyPicture(
        tag: Tag,
        ext: String,
        picture: PicturePayload,
        policy: OverwritePolicy,
        existing: TrackMeta?
    ) {
        val exists = when (picture.kind) {
            PictureKind.ARTIST -> existing?.hasArtistImage == true
            PictureKind.FRONT_COVER -> existing?.hasCover == true
        }
        if (policy != OverwritePolicy.FORCE && exists) return

        val type = if (picture.kind == PictureKind.ARTIST) 8 else 3
        when {
            // MP4/M4A 没有"艺术家图"的标准位置，交给旁路文件，不硬塞自定义 atom。
            ext in AudioFormats.MP4_PICTURE && picture.kind == PictureKind.ARTIST -> Unit
            ext in AudioFormats.MP4_PICTURE -> writeMp4Cover(tag, picture)
            ext in AudioFormats.VORBIS_PICTURE -> writeVorbisPicture(tag, picture, type)
            ext in AudioFormats.APE_PICTURE -> writeApePicture(tag, picture)
            else -> writeStandardArtwork(tag, picture, type)
        }
    }

    private fun writeStandardArtwork(tag: Tag, picture: PicturePayload, type: Int) {
        // jaudiotagger 没有「只删某一张图」的接口：
        //   deleteArtworkField() 清空**整个**图片字段；
        //   setField(Artwork)     整体替换。
        // 所以写艺术家图(8)时如果直接清空，会把刚写好的封面(3)一起删掉。
        // 正确做法：取出现有图片里类型不同的那些保留，清空后连同新图一起 addField。
        val kept = runCatching { tag.artworkList }.getOrNull().orEmpty().mapNotNull { art ->
            val otherType = runCatching { art.pictureType }.getOrDefault(-1)
            val bytes = runCatching { art.binaryData }.getOrNull()
            if (otherType < 0 || otherType == type || bytes == null || bytes.isEmpty()) {
                null
            } else {
                AudioMetaArtwork(
                    data = bytes,
                    mime = runCatching { art.mimeType }.getOrDefault("image/jpeg").ifBlank { "image/jpeg" },
                    description = runCatching { art.description }.getOrDefault(""),
                    pictureType = otherType,
                    width = runCatching { art.width }.getOrDefault(0),
                    height = runCatching { art.height }.getOrDefault(0)
                )
            }
        }

        // 用自己的 Artwork：ArtworkFactory 的两个实现都会在 setImageFromData() 上翻车
        // （StandardArtwork 要 ImageIO，AndroidArtwork 是空桩）。
        val fresh = AudioMetaArtwork(
            data = picture.bytes,
            mime = picture.mime,
            description = picture.description,
            pictureType = type,
            width = picture.width,
            height = picture.height
        )

        runCatching { tag.deleteArtworkField() }
        // 封面(3) 排在艺术家图(8) 前面，符合通用约定。
        (kept + fresh)
            .sortedBy { if (it.pictureType == 8) 1 else 0 }
            .forEach { art -> runCatching { tag.addField(art) } }
    }

    /**
     * FlacTag 和 VorbisCommentTag 没有共同父类，但各自都支持自定义文本字段。
     * Tag 接口本身没有 addField(String, String)，所以必须按具体类型分派。
     */
    private fun putCustomText(tag: Tag, key: String, value: String): Boolean = when (tag) {
        is FlacTag -> {
            runCatching { tag.deleteField(key) }
            runCatching { tag.addField(key, value) }.isSuccess
        }
        is VorbisCommentTag -> {
            runCatching { tag.deleteField(key) }
            runCatching { tag.addField(key, value) }.isSuccess
        }
        else -> false
    }

    private fun writeVorbisPicture(tag: Tag, picture: PicturePayload, type: Int) {
        if (tag is FlacTag) {
            writeStandardArtwork(tag, picture, type)
            return
        }
        val block = FlacPicture.encode(
            pictureType = type,
            mime = picture.mime,
            description = picture.description,
            width = picture.width,
            height = picture.height,
            data = picture.bytes
        )
        val b64 = Base64.encodeToString(block, Base64.NO_WRAP)
        if (!putCustomText(tag, "METADATA_BLOCK_PICTURE", b64)) {
            writeStandardArtwork(tag, picture, type)
        }
    }

    private fun writeMp4Cover(tag: Tag, picture: PicturePayload) {
        val mp4 = tag as? Mp4Tag
        if (mp4 == null) {
            writeStandardArtwork(tag, picture, 3)
            return
        }
        // Mp4TagCoverField 的单参构造会从数据里判断 JPEG/PNG。
        val field = Mp4TagCoverField(picture.bytes)
        runCatching { mp4.setField(field) }.onFailure {
            writeStandardArtwork(tag, picture, 3)
        }
    }

    private fun writeApePicture(tag: Tag, picture: PicturePayload) {
        val key = if (picture.kind == PictureKind.ARTIST) "Cover Art (Artist)" else "Cover Art (Front)"
        val nameBytes = "Artist".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(nameBytes.size + 1 + picture.bytes.size)
        System.arraycopy(nameBytes, 0, payload, 0, nameBytes.size)
        payload[nameBytes.size] = 0
        System.arraycopy(picture.bytes, 0, payload, nameBytes.size + 1, picture.bytes.size)
        runCatching {
            tag.deleteField(key)
            tag.setField(tag.createField(FieldKey.COVER_ART, String(payload, Charsets.ISO_8859_1)))
        }.onFailure {
            writeStandardArtwork(tag, picture, if (picture.kind == PictureKind.ARTIST) 8 else 3)
        }
    }

    private fun applyLyrics(
        tag: Tag,
        ext: String,
        lyrics: List<LyricsItem>,
        policy: OverwritePolicy,
        existing: TrackMeta?
    ) {
        if (policy != OverwritePolicy.FORCE && existing?.hasLyrics == true) return
        val primary = lyrics.firstOrNull { !it.translated } ?: lyrics.firstOrNull() ?: return
        val translated = lyrics.firstOrNull { it.translated }
        when {
            ext in AudioFormats.MP4_PICTURE -> {
                runCatching { tag.setField(FieldKey.LYRICS, primary.content) }
            }
            ext in AudioFormats.FLAC_PICTURE -> {
                putCustomText(tag, "LYRICS", primary.content)
                if (translated != null) putCustomText(tag, "LYRICS_TRANSLATION", translated.content)
            }
            ext in AudioFormats.VORBIS_PICTURE -> {
                putCustomText(tag, "LYRICS", primary.content)
                if (translated != null) putCustomText(tag, "LYRICS_TRANSLATION", translated.content)
            }
            else -> {
                runCatching {
                    tag.deleteField(FieldKey.LYRICS)
                    tag.setField(FieldKey.LYRICS, primary.content)
                }
            }
        }
    }

    private companion object {
        const val TAG = "TagWriter"
    }
}
