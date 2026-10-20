package com.audiometa.app.media.image

import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.ImageMode
import com.audiometa.app.domain.model.LyricsItem
import com.audiometa.app.domain.model.OverwritePolicy
import com.audiometa.app.domain.model.PictureKind
import com.audiometa.app.domain.model.PicturePayload
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.domain.model.WriteMode
import com.audiometa.app.domain.model.formatSupportOf
import com.audiometa.app.media.tag.TagWriteException
import com.audiometa.app.media.tag.TagWriter
import com.audiometa.app.util.AppLog
import java.io.File
import java.security.MessageDigest

class ImageEmbedder(
    private val tagWriter: TagWriter = TagWriter(),
    private val transcoder: JpegTranscoder = JpegTranscoder()
) {
    data class EmbedRequest(
        val file: File,
        val artistBytes: ByteArray? = null,
        val artistMime: String = "image/jpeg",
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg",
        val lyrics: List<LyricsItem> = emptyList(),
        val meta: TrackMeta? = null,
        val existing: TrackMeta? = null,
        val mode: WriteMode = WriteMode.EMBED,
        val imageMode: ImageMode = ImageMode.CUSTOM,
        val imageQuality: Int = 100,
        val policy: OverwritePolicy = OverwritePolicy.FILL_MISSING,
        val keepModTime: Boolean = true
    )

    data class EmbedResult(
        val embedded: Boolean,
        val sidecarArtist: String? = null,
        val sidecarCover: String? = null,
        val sidecarLyrics: String? = null,
        val sidecarTranslation: String? = null,
        val artistHash: String? = null,
        val coverHash: String? = null,
        val notes: List<String> = emptyList()
    )

    fun embed(request: EmbedRequest): EmbedResult {
        val notes = mutableListOf<String>()
        val ext = AudioFormats.normalize(request.file.extension)
        val support = formatSupportOf(ext)

        val artistPrepared = request.artistBytes
            ?.let { prepare(it, request.artistMime, request.imageMode, request.imageQuality, PictureKind.ARTIST) }
        val coverPrepared = request.coverBytes
            ?.let { prepare(it, request.coverMime, request.imageMode, request.imageQuality, PictureKind.FRONT_COVER) }

        val artistHash = artistPrepared?.let { sha1(it.bytes) }
        val coverHash = coverPrepared?.let { sha1(it.bytes) }

        // 每个文件都要有自己的一份图片，只做同一文件内部的重复判定。
        val embedPictures = buildList {
            if (coverPrepared != null && support.cover) add(coverPrepared)
            if (artistPrepared != null && support.artistImage) add(artistPrepared)
            if (coverPrepared != null && artistPrepared != null && coverHash == artistHash && support.artistImage) {
                // 封面和艺术家图完全相同时只留一张，避免写两份一样的图。
                remove(coverPrepared)
            }
        }
        val embedLyrics = if (support.lyrics) request.lyrics else emptyList()
        val embedMeta = if (support.meta) request.meta else null

        val coverNeedsExternal = coverPrepared != null && !support.cover
        val artistNeedsExternal = artistPrepared != null && !support.artistImage
        val lyricsNeedExternal = request.lyrics.isNotEmpty() && !support.lyrics
        if (coverNeedsExternal || artistNeedsExternal || lyricsNeedExternal) {
            notes += "该格式不支持内嵌部分内容，已改走外置文件（" + support.summary + "）"
        }

        var embedded = false
        val wantEmbed = request.mode == WriteMode.EMBED || request.mode == WriteMode.BOTH
        val wantExternal = request.mode == WriteMode.SIDECAR || request.mode == WriteMode.BOTH
        val hasEmbedPayload = embedPictures.isNotEmpty() || embedLyrics.isNotEmpty() || embedMeta != null
        if (wantEmbed && hasEmbedPayload) {
            try {
                tagWriter.write(
                    file = request.file,
                    pictures = embedPictures,
                    lyrics = embedLyrics,
                    meta = embedMeta,
                    policy = request.policy,
                    existing = request.existing,
                    keepModTime = request.keepModTime
                )
                embedded = true
                AppLog.d(TAG, "已写入标签 " + request.file.name)
            } catch (e: TagWriteException) {
                if (e.message?.contains("不支持内嵌") == true) {
                    notes += e.message ?: "该格式不支持内嵌"
                    AppLog.w(TAG, "跳过内嵌 " + request.file.name + "：" + e.message)
                } else {
                    AppLog.e(TAG, "标签写入失败 " + request.file.name, e)
                    throw e
                }
            }
        }

        var sidecarArtist: String? = null
        var sidecarCover: String? = null
        var sidecarLyrics: String? = null
        var sidecarTranslation: String? = null
        val external = wantExternal || coverNeedsExternal || artistNeedsExternal || lyricsNeedExternal
        if (external) {
            val base = request.file.parentFile
            val stem = request.file.nameWithoutExtension
            if (base != null) {
                if (artistPrepared != null && (wantExternal || artistNeedsExternal)) {
                    sidecarArtist = writeBytes(base, stem + ".artist.jpg", artistPrepared.bytes)
                }
                if (coverPrepared != null && (wantExternal || coverNeedsExternal)) {
                    sidecarCover = writeBytes(base, stem + ".cover.jpg", coverPrepared.bytes)
                }
                val primary = request.lyrics.firstOrNull { !it.translated }
                val translated = request.lyrics.firstOrNull { it.translated }
                if (primary != null && (wantExternal || lyricsNeedExternal)) {
                    sidecarLyrics = writeText(base, stem + ".lrc", primary.content)
                }
                if (translated != null && (wantExternal || lyricsNeedExternal)) {
                    sidecarTranslation = writeText(base, stem + ".trans.lrc", translated.content)
                }
            }
        }

        return EmbedResult(
            embedded = embedded,
            sidecarArtist = sidecarArtist,
            sidecarCover = sidecarCover,
            sidecarLyrics = sidecarLyrics,
            sidecarTranslation = sidecarTranslation,
            artistHash = artistHash,
            coverHash = coverHash,
            notes = notes
        )
    }

    private fun writeBytes(dir: File, name: String, bytes: ByteArray): String? =
        runCatching {
            val out = File(dir, name)
            out.writeBytes(bytes)
            out.absolutePath
        }.onFailure { AppLog.e(TAG, "外置图片写入失败 " + name, it) }.getOrNull()

    private fun writeText(dir: File, name: String, text: String): String? =
        runCatching {
            val out = File(dir, name)
            out.writeText(text)
            out.absolutePath
        }.onFailure { AppLog.e(TAG, "外置歌词写入失败 " + name, it) }.getOrNull()

    private fun prepare(
        bytes: ByteArray,
        mime: String,
        mode: ImageMode,
        quality: Int,
        kind: PictureKind
    ): PicturePayload {
        val processed = when (mode) {
            ImageMode.CUSTOM -> transcoder.toJpeg(bytes, quality)
            ImageMode.ORIGINAL -> transcoder.original(bytes, mime)
        }
        return PicturePayload(
            bytes = processed.bytes,
            mime = processed.mime,
            kind = kind,
            description = if (kind == PictureKind.ARTIST) "Artist" else "Front Cover",
            width = processed.width,
            height = processed.height
        )
    }

    fun sha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ImageEmbedder"
    }
}
