package com.audiometa.app.media.image

import android.content.Context
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * **专辑封面**的磁盘缩略图缓存。
 *
 * 目录结构：
 *
 * ```
 * <externalFilesDir>/thumbnails/
 *     audio_covers/     音频（专辑）封面缩略图   ← 现在只有这一类会落盘
 *     artist_images/    历史遗留目录，启动时删掉（见 [maintain]），不再写入
 *     temp/             原子写入的临时文件
 * ```
 *
 * ★ **磁盘缩略图只保留「歌曲专辑封面」一类**（用户要求："图片类只生成歌曲专辑封面的
 * 缩略图，艺术家图是实时读取音频内数据"）。
 *
 * 为什么艺术家图不做磁盘缓存：它的 key 只能绑**艺术家名**（同一位艺术家共享一张图，
 * 不能一首歌存一份），而音频文件里的图换掉之后艺术家名并不变 —— 磁盘上那份旧缩略图
 * 于是永远命中，"文件里明明换了头像、界面还是旧图"。宁可变一次实时解码：
 * 艺术家图现在一律走 [com.audiometa.app.ui.component.TrackCover] 的实时分支，
 * 不读盘、不写盘，只把解码结果放进内存 LRU。
 *
 * 剩下的约定：
 *
 * 1. **key 绑文件本身**：`sha1("audio|<path>|<stamp>|<mtime>|<size>")` —— 标签被改过
 *    （stamp/mtime/size 变了）缓存自然失效。
 * 2. **原子写入**：先写 `temp/` 再 rename，中途被杀不会留下半个损坏的 JPEG。
 * 3. **容量上限 + LRU 淘汰**：专辑封面 100 MB，超了按 lastModified 从旧到新删。
 */
object ThumbnailCache {

    private const val ROOT_DIR = com.audiometa.app.util.StoragePaths.DIR_THUMBNAILS
    private const val AUDIO_DIR = "audio_covers"

    /**
     * 历史目录名。
     *
     * 只为 [maintain] 里那次**一次性**删除而保留：老版本给每张艺术家图都存过缩略图，
     * 升级之后那些文件再也没人读写，不清就一直占着磁盘。既然历史 API 已经删干净，
     * 这个名字就只剩"认出旧垃圾"这一个用途。
     */
    private const val ARTIST_DIR = "artist_images"

    private const val TEMP_DIR = "temp"

    /** 容量上限。只有专辑封面一类占磁盘，所以只有一个上限。 */
    private const val AUDIO_MAX_BYTES = 100L * 1024 * 1024

    /** 缩略图根目录：`files/缩略图/`。 */
    fun baseDirectory(context: Context): File = StoragePaths.dir(context, ROOT_DIR)

    /**
     * 专辑封面缩略图的目录。
     *
     * [create] 为 false 时**不创建目录** —— 只做事统计、清理这类只读操作时
     * 不该凭空造出空文件夹。
     */
    fun directory(context: Context, create: Boolean = true): File {
        val dir = StoragePaths.dir(context, ROOT_DIR, AUDIO_DIR)
        if (create && !dir.exists()) dir.mkdirs()
        return dir
    }

    /** 历史遗留的艺术家图目录。只用来删，不用来读写。 */
    private fun legacyArtistDirectory(context: Context): File =
        StoragePaths.dir(context, ROOT_DIR, ARTIST_DIR)

    private fun tempDirectory(context: Context): File =
        StoragePaths.ensuredDir(context, ROOT_DIR, TEMP_DIR)

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 音频封面的 key。
     *
     * **必须带上 [stamp]**（曲目记录里那个"最近一次写入时间"）。
     * 只绑文件 mtime 是不够的：设置里"保持文件修改时间"开着时，写完标签
     * mtime 会被还原成原值，key 不变 → 界面永远读旧图。用户报的
     * "改完之后封面不刷新"就是这个。
     *
     * mtime 和大小也一起进 key，这样在应用外面改过文件同样能失效。
     */
    fun audioCoverKey(
        path: String,
        stamp: Long,
        lastModified: Long,
        size: Long
    ): String = sha1("audio-v2|$path|$stamp|$lastModified|$size")

    /**
     * 增量清理：删掉**不再对应任何有效 key** 的缩略图文件。
     *
     * 这才是"刷新"该有的语义 —— 只清失效的，有效的原样留着。
     * 以前用的是 [rebuild]（整目录清空），用户明确指出那不是刷新、是清空。
     *
     * @param validKeys 当前曲库里所有仍然有效的 key。
     * @return 删掉的文件数。
     */
    fun pruneOrphans(context: Context, validKeys: Set<String>): Int {
        var removed = 0
        runCatching {
            val keep = validKeys.mapTo(HashSet()) { "$it.jpg" }
            directory(context, create = false).listFiles()?.forEach { file ->
                if (file.name !in keep && file.delete()) removed++
            }
            purgeTemp(context)
        }
        return removed
    }

    /** 读一份专辑封面缩略图；没有或读不出来返回 null。 */
    fun read(context: Context, key: String): ByteArray? {
        val file = File(directory(context), "$key.jpg")
        if (!file.isFile || file.length() == 0L) return null
        // 记一次访问时间，LRU 淘汰靠它——被读过的图应该是"最近用过"的。
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 写一份专辑封面缩略图。 */
    fun write(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val target = File(directory(context), "$key.jpg")
            // 原子写入：先写 temp，再 rename。
            val tmp = File(tempDirectory(context), "$key.jpg.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            trim(context)
        }.onFailure { AppLog.d(TAG, "写缩略图缓存失败：" + it.message) }
    }

    /**
     * 重建专辑封面缓存。
     *
     * "重建"的含义（按用户要求）：清掉全部缓存文件、清理 temp 残留、
     * 做一次容量淘汰；下次显示时按**当前文件**重新生成。
     *
     * 刻意不做"扫一遍找出失效项"的精细清理：重建的语义就是按当前状态重来，
     * 保留旧的会让改了标签的歌继续显示旧图。孤立文件（对应曲目已被删掉）
     * 也在这个过程中一并消失。
     *
     * @return 删掉的文件数
     */
    fun rebuild(context: Context): Int {
        var removed = 0
        runCatching {
            directory(context, create = false).listFiles()?.forEach { file ->
                if (file.delete()) removed++
            }
            purgeTemp(context)
            trim(context)
        }
        AppLog.i(TAG, "已重建专辑封面缓存：删除 $removed 个文件")
        return removed
    }

    /** 清空 temp 目录。启动时和重建后调用，防止残留临时文件堆积。 */
    fun purgeTemp(context: Context) {
        runCatching { tempDirectory(context).listFiles()?.forEach { it.delete() } }
    }

    /** 统计专辑封面缩略图的文件数与占用字节数。 */
    fun stats(context: Context): CacheStats {
        val files = runCatching {
            directory(context, create = false).listFiles()?.filter { it.isFile }.orEmpty()
        }.getOrDefault(emptyList())
        return CacheStats(files = files.size, bytes = files.sumOf { it.length() })
    }

    data class CacheStats(val files: Int, val bytes: Long)

    /** 超出上限时按访问时间从旧到新删。 */
    fun trim(context: Context) {
        runCatching {
            val files = directory(context, create = false).listFiles()?.filter { it.isFile }.orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= AUDIO_MAX_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= AUDIO_MAX_BYTES) return@forEach
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    /**
     * 启动时的维护：清 temp、专辑封面做一次 LRU 淘汰、删掉历史遗留的艺术家图目录。
     *
     * 删 `thumbnails/artist_images/` 是一次性的**清老数据**，不是"这类缓存还在"：
     * 老版本给每张艺术家图都存过缩略图，升级之后那些文件再也没人读、没人清，
     * 会一直占着几十 MB。删一次就够 —— 艺术家图现在只进内存 LRU，磁盘上没有
     * 任何分支会再建出这个目录，它不会复生。
     */
    fun maintain(context: Context) {
        purgeTemp(context)
        trim(context)
        runCatching { legacyArtistDirectory(context).deleteRecursively() }
    }

    /** 缩略图总占用（只算专辑封面）。 */
    fun totalBytes(context: Context): Long = stats(context).bytes

    /** 缩略图文件总数。口径同 [totalBytes]。 */
    fun fileCount(context: Context): Int = stats(context).files

    private const val TAG = "ThumbnailCache"
}
