package com.audiometa.app.media.scan

import android.content.Context
import com.audiometa.app.data.repo.ArtistRepository
import com.audiometa.app.data.repo.LyricsCacheRepository
import com.audiometa.app.data.repo.MetaTagRepository
import com.audiometa.app.data.repo.TrackRepository
import com.audiometa.app.media.audio.SilenceProfileStore
import com.audiometa.app.media.image.ThumbnailCache
import com.audiometa.app.media.tag.MetaImageStore
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths

/**
 * 孤立数据清理。
 *
 * "孤立"= 当前曲库里已经没有任何东西引用它。扫描完之后跑一次最合适：
 * 那时曲目表刚更新过，删掉的目录、改过的封面留下的缩略图和缓存行，
 * 这一趟正好一起收走。
 *
 * 清理范围：
 * - 专辑（歌曲）封面缩略图（按**文件名匹配**有效 key，对不上的删）；
 * - 「元信息」页里图片类标签留下的图片文件；
 * - 歌词缓存行、标签识别行、静音档案里已经没有对应曲目的那些；
 * - `temp/` 目录里的残留文件。
 *
 * **不再清理"艺术家缩略图"**：艺术家图现在不生成、不落盘（实时读音频里的数据，
 * 见 [ThumbnailCache] 的说明），磁盘上根本没有这一类文件。以前这里还会清一遍
 * `artist_images/`，现在留着只会报一个恒为 0 的"头像 N 个"。
 *
 * 全程只读数据库 + 列目录，不读音频文件本身，所以扫完立刻跑也不慢。
 */
class OrphanCleaner(
    private val tracks: TrackRepository,
    /*
     * 艺术家仓库：**当前用不到了**。
     *
     * 以前这里用它算艺术家缩略图的有效 key，艺术家图不落盘之后就没有这一步了。
     * 参数之所以保留，是因为构造方在 `di/ServiceLocator`（不在本次改动范围内），
     * 删掉形参会连带改到那个文件；同理 `artists` 这个实例在别处仍然有用，
     * 只是本类不再需要它。保留成"永不使用"比留下假的调用更清楚。
     */
    @Suppress("unused") private val artists: ArtistRepository,
    private val lyricsCache: LyricsCacheRepository,
    private val metaTags: MetaTagRepository
) {

    /** @return 给日志/界面用的一句话结果。 */
    suspend fun clean(context: Context): String {
        val trackList = tracks.allOnce()
        val trackIds = trackList.map { it.id }
        val idSet = trackIds.toSet()

        /*
         * 每一步各自兜住异常。
         *
         * 调用方（[ScanService]）本来也套了 runCatching，但一步失败就整趟中断
         * 太亏了：孤儿缩略图删不掉，不该连累歌词缓存行和临时文件一起留着。
         * 而且 CancellationException 必须继续往上抛 —— 用户取消扫描时
         * 吞掉它会让这个协程卡在这里不结束。
         */
        val coverKeys = trackList.mapTo(HashSet()) { track ->
            ThumbnailCache.audioCoverKey(track.path, track.updatedAt, track.lastModified, track.fileSize)
        }
        val covers = step("封面缩略图") {
            ThumbnailCache.pruneOrphans(context, validKeys = coverKeys)
        }
        // 艺术家缩略图这一步整段去掉了：那一类不再落盘，磁盘上没有它的文件。
        // 真正要"失效"的是内存里那份解码结果，由界面在刷新后清（evictCoverMemoryCache）。

        // 元信息页里的图片
        val images = step("元信息图片") {
            MetaImageStore.prune(
                context,
                metaTags.all()
                    .map { it.value }
                    .filter { MetaImageStore.isImageValue(it) }
                    .map { MetaImageStore.fileNameOf(it) }
                    .toSet()
            )
        }

        // 各种缓存行
        stepUnit("歌词缓存行") { lyricsCache.pruneTo(trackIds) }
        stepUnit("标签识别行") { metaTags.pruneTo(trackIds) }
        stepUnit("静音档案") { SilenceProfileStore.prune(context, idSet) }

        // 临时文件 + 空目录
        val tempFiles = step("临时文件") {
            StoragePaths.tempDir(context).listFiles()?.count { it.delete() } ?: 0
        }
        stepUnit("空目录") { StoragePaths.pruneEmptyDirectories(context) }

        // 文案里不再出现"头像 N 个"：那个数字现在恒为 0，报出来只会让人以为清理漏了东西。
        val summary = "孤立数据已清理：封面 " + covers +
            "、图片 " + images + "、临时文件 " + tempFiles
        AppLog.i(TAG, summary)
        return summary
    }

    /**
     * 跑一步清理，失败只记日志。
     *
     * 失败返回 0 而不是抛出：这一趟是"顺手收拾"，任何一步出问题都不该
     * 影响扫描本身的结果（曲目索引这时候已经落库了）。
     */
    private suspend fun step(name: String, block: suspend () -> Int): Int =
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 取消要照常传上去，吞掉它会让这个协程卡住不结束。
            throw cancelled
        } catch (t: Throwable) {
            AppLog.w(TAG, "清理「$name」失败：" + t.shortDescription())
            0
        }

    /** 同上，只是这一步没有"删了几个"可报。 */
    private suspend fun stepUnit(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLog.w(TAG, "清理「$name」失败：" + t.shortDescription())
        }
    }

    private companion object {
        const val TAG = "OrphanCleaner"
    }
}
