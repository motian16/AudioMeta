package com.audiometa.app.util

import android.content.Context
import android.net.Uri
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.media.image.ArtworkKind
import com.audiometa.app.media.image.ArtworkResolver
import com.audiometa.app.media.image.ImageEmbedder
import com.audiometa.app.media.image.ThumbnailCache
import com.audiometa.app.ui.component.evictCoverMemoryCache
import com.audiometa.app.domain.model.OverwritePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面 / 艺术家图的读取、导出与手动替换。
 *
 * 详情页点开大图后要在两处用到：
 * - 保存到相册（/storage/emulated/0/Pictures/AudioMeta/）
 * - 从相册选一张新的艺术家图写回音频文件
 */
object ArtworkTools {

    /** 结果文案由调用方决定怎么展示（本项目不再弹横幅，所以只返回文本 + 是否成功）。 */
    data class Result(val ok: Boolean, val message: String)

    /** 取一张图的原始字节；没有返回 null。 */
    suspend fun loadBytes(audioPath: String, kind: ArtworkKind): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(audioPath)
            if (!file.isFile) return@runCatching null
            when (val source = ArtworkResolver.resolve(file, kind)) {
                is ArtworkResolver.Source.External -> File(source.path).readBytes()
                is ArtworkResolver.Source.Bytes -> source.data
                null -> null
            }
        }.getOrNull()
    }

    /** 存进相册目录。 */
    suspend fun saveToAlbum(
        context: Context,
        audioPath: String,
        kind: ArtworkKind,
        displayName: String
    ): Result = withContext(Dispatchers.IO) {
        val bytes = loadBytes(audioPath, kind)
            ?: return@withContext Result(false, "没有找到可保存的图片")
        val where = if (kind == ArtworkKind.ARTIST) "艺术家图" else "封面"
        val path = ImageStore.saveToAlbum(context, displayName + "-" + label(kind), bytes)
        if (path == null) {
            Result(false, where + "保存失败，检查存储空间后重试")
        } else {
            Result(true, where + "已保存到 " + path)
        }
    }

    /**
     * 用相册里选中的图片替换艺术家图。
     *
     * 走 ImageEmbedder 而不是直接改字节：格式差异（ID3 / FLAC / Vorbis / APE）、
     * 压缩、以及"别把封面一起删掉"的处理都在那里，和刮削是同一条写入链路。
     *
     * **默认写进这位艺术家名下的每一首歌**（用户明确要求）：
     * 艺术家图是"一位艺术家一张"，只写在当前这一首上的话，切到同一歌手的
     * 别的歌就会看到旧头像甚至占位图。
     */
    suspend fun replaceArtistImage(
        context: Context,
        track: TrackEntity,
        uri: Uri
    ): Result = withContext(Dispatchers.IO) {
        val loaded = ImageStore.read(context, uri)
            ?: return@withContext Result(false, "读取所选图片失败")

        val artistName = track.artist.trim()
        val targets = if (artistName.isEmpty()) {
            listOf(track)
        } else {
            ServiceLocator.tracks.allOnce().filter { it.artist.trim() == artistName }
                .ifEmpty { listOf(track) }
        }

        // 「保持文件修改时间」跟随设置，和刮削走同一条规则 ——
        // 以前这里写死 true，用户关掉设置后发现手动换图也不改时间，
        // 会误以为"文件根本没被写"。
        val settings = ServiceLocator.settings.settings.first()

        var written = 0
        var lastError: String? = null
        targets.forEach { target ->
            val file = File(target.path)
            if (!file.isFile) return@forEach
            val result = runCatching {
                ServiceLocator.embedder.embed(
                    ImageEmbedder.EmbedRequest(
                        file = file,
                        artistBytes = loaded.bytes,
                        artistMime = loaded.mime,
                        // 手动选图就是要替换，不受"只补缺失"策略影响。
                        policy = OverwritePolicy.FORCE,
                        keepModTime = settings.keepModTime
                    )
                )
            }
            result.fold(
                onSuccess = { embed ->
                    if (embed.artistHash != null || embed.sidecarArtist != null) {
                        written++
                        refreshTrackData(target)
                    } else {
                        lastError = "这个格式无法内嵌艺术家图（" + file.extension + "）"
                    }
                },
                onFailure = { lastError = it.message ?: it.javaClass.simpleName }
            )
        }

        // 图换了，磁盘缩略图必须整类重建：封面缩略图的 key 绑的是文件本身，
        // 不重建的话文件写进去了界面还是旧图。
        ThumbnailCache.rebuild(context)
        evictCoverMemoryCache()

        when {
            written > 0 -> Result(true, "艺术家图已写入 " + written + " 首")
            lastError != null -> Result(false, lastError!!)
            else -> Result(false, "没有可写入的曲目")
        }
    }

    /**
     * 写完文件之后把这首歌的标签重新读一遍。
     *
     * 用户要求："所有对某项歌曲进行的写入操作，都会重新读取一次标签，刷新缓存数据"。
     * 读标签、重建歌词缓存、刷新"元信息"页的自定义标签，都在
     * [com.audiometa.app.media.scan.LibraryIndexer.refreshTrack] 里。
     */
    private suspend fun refreshTrackData(track: TrackEntity) {
        runCatching { ServiceLocator.indexer.refreshTrack(track.id) }
    }

    /** 文件名里用的后缀，避免封面和艺术家图重名互相覆盖。 */
    private fun label(kind: ArtworkKind): String =
        if (kind == ArtworkKind.ARTIST) "artist" else "cover"
}
