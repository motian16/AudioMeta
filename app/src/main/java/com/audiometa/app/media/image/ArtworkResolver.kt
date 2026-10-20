package com.audiometa.app.media.image

import com.audiometa.app.util.AppLog
import org.jaudiotagger.audio.AudioFileIO
import java.io.File

/** 要找哪种图。 */
enum class ArtworkKind { COVER, ARTIST }

/**
 * 找一首歌的图片。**先看外置同名文件，再读内嵌图。**
 *
 * 之前只认外置路径（TrackEntity.artistImagePath），内嵌写入时那个字段是 null，
 * 于是刮削明明成功、标签状态也显示 ✓，界面却一直显示占位图。
 */
object ArtworkResolver {

    private val COVER_SUFFIXES = listOf(
        ".cover.jpg", ".cover.jpeg", ".cover.png", ".cover.webp",
        ".jpg", ".jpeg", ".png", ".webp"
    )

    // 艺术家图只认专用文件名，避免把封面当成艺术家图。
    private val ARTIST_SUFFIXES = listOf(
        ".artist.jpg", ".artist.jpeg", ".artist.png", ".artist.webp"
    )

    /** ID3/FLAC 的图片类型：3 = 封面，8 = 艺术家。 */
    private const val TYPE_FRONT_COVER = 3
    private const val TYPE_ARTIST = 8

    fun externalArtwork(file: File, kind: ArtworkKind): File? {
        val parent = file.parentFile ?: return null
        val stem = file.nameWithoutExtension
        val suffixes = if (kind == ArtworkKind.ARTIST) ARTIST_SUFFIXES else COVER_SUFFIXES
        suffixes.forEach { suffix ->
            val candidate = File(parent, stem + suffix)
            if (candidate.isFile && candidate.length() > 0L) return candidate
        }
        return null
    }

    /** 外置封面文件路径；没有就返回 null。扫描第一阶段只做这件事，不碰文件内容。 */
    fun externalCoverPath(file: File): String? = externalArtwork(file, ArtworkKind.COVER)?.absolutePath

    /** 外置艺术家图路径；没有就返回 null。 */
    fun externalArtistPath(file: File): String? = externalArtwork(file, ArtworkKind.ARTIST)?.absolutePath

    fun embeddedArtwork(file: File, kind: ArtworkKind): ByteArray? = runCatching {
        val tag = AudioFileIO.read(file).tag ?: return@runCatching null
        val artworks = tag.artworkList ?: return@runCatching null
        if (artworks.isEmpty()) return@runCatching null

        val wanted = if (kind == ArtworkKind.ARTIST) TYPE_ARTIST else TYPE_FRONT_COVER
        val exact = artworks.firstOrNull { runCatching { it.pictureType }.getOrDefault(-1) == wanted }
        // 封面兜底：任何不是艺术家图的图都算封面。
        val fallback = if (kind == ArtworkKind.COVER) {
            artworks.firstOrNull { runCatching { it.pictureType }.getOrDefault(-1) != TYPE_ARTIST }
                ?: artworks.firstOrNull()
        } else {
            null
        }
        (exact ?: fallback)?.binaryData?.takeIf { it.isNotEmpty() }
    }.onFailure {
        AppLog.d(TAG, "读取内嵌图片失败 " + file.name + "：" + it.message)
    }.getOrNull()

    /** 解析成可直接解码的来源。 */
    fun resolve(file: File, kind: ArtworkKind): Source? {
        externalArtwork(file, kind)?.let { return Source.External(it.absolutePath) }
        embeddedArtwork(file, kind)?.let { return Source.Bytes(it) }
        // 艺术家图没有就没有，不拿封面临时代替——那正是之前串位的原因。
        return null
    }

    /** 判断有没有图，不返回数据。 */
    fun hasAny(file: File, kind: ArtworkKind): Boolean = resolve(file, kind) != null

    /**
     * 从字节头猜图片类型。
     *
     * 艺术家图要在「不同歌曲之间复用」时得带上正确的 mime，
     * 否则写进 FLAC / Ogg 的 METADATA_BLOCK_PICTURE 会标错类型。
     */
    fun mimeOf(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            bytes.size >= 8 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            bytes.size >= 12 && at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57 -> "image/webp"
            bytes.size >= 6 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            else -> "image/jpeg"
        }
    }

    sealed interface Source {
        data class External(val path: String) : Source
        data class Bytes(val data: ByteArray) : Source {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && data.contentEquals(other.data))

            override fun hashCode(): Int = data.contentHashCode()
        }
    }

    private const val TAG = "ArtworkResolver"
}
