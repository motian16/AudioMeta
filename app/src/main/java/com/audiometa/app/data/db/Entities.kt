package com.audiometa.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["folderPath"]),
        Index(value = ["artist"]),
        Index(value = ["format"])
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val uri: String? = null,
    val folderPath: String,
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val trackNo: Int = 0,
    val year: Int = 0,
    val durationMs: Long = 0L,
    val format: String = "",
    val fileSize: Long = 0L,
    val lastModified: Long = 0L,
    val hasArtistImage: Boolean = false,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val hasTranslation: Boolean = false,
    val lyricsType: String = "none",
    val artistImagePath: String? = null,
    val coverPath: String? = null,
    val lyricsPath: String? = null,
    val scrapeState: Int = 0,
    val scrapeMessage: String = "",
    /**
     * 曲风，可以多个。用换行符分隔存一列：一首歌可能有多个曲风，
     * 单值字段装不下，而拆分一次足够便宜，不值得再开一张关联表。
     */
    val genres: String = "",
    /**
     * 歌词是否已经进过索引。0 表示后台还没处理，后台增量刷新只看这一列，
     * 所以不会反复重读已经处理过的文件。
     */
    val lyricsIndexedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 拆分后的曲风列表。
     *
     * **缓存住**：它以前是个每次访问都重新 split/trim/distinct 的 getter，
     * 而它在"列表每一行"和"每次曲风归组"里都会被读到 —— 滑页签时一屏几十行
     * 每行重拆一遍，白扔掉一大堆临时字符串。实体本身不可变，算一次就够。
     */
    val genreList: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        genres.split('\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** 主曲风：多个曲风时列表页只显示第一个。 */
    val primaryGenre: String get() = genreList.firstOrNull().orEmpty()

    companion object {
        /**
         * 曲风入库前的规范化：去空、去重、按换行拼接。
         *
         * 先做一次拆分：标签里多曲风常写成 "Rock/Pop"、"Rock; Pop"、"Rock、Pop"，
         * 当成一个曲风的话曲风页会冒出一堆"Rock/Pop"这种没人认识的条目。
         */
        fun joinGenres(values: Collection<String>): String {
            val split = values
                .flatMap { raw -> raw.split(GENRE_SPLIT) }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            return split.joinToString("\n")
        }

        private val GENRE_SPLIT = Regex("\\s*(?:/|／|、|;|；|\\||,|，)\\s*")
    }
}

@Entity(
    tableName = "folders",
    indices = [Index(value = ["path"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val parentPath: String = "",
    val name: String = "",
    val excluded: Boolean = false,
    val depth: Int = 0,
    val trackCount: Int = 0,
    val lastScanAt: Long = 0L
)

@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], unique = true)]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val imageLocalPath: String? = null,
    val imageSourceId: String? = null,
    val imageHash: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "plugin_records",
    indices = [Index(value = ["pluginId"], unique = true)]
)
data class PluginRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val installPath: String,
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = 1
)

@Entity(
    tableName = "source_records",
    primaryKeys = ["sourceId", "pluginId"]
)
data class SourceRecordEntity(
    val sourceId: String,
    val pluginId: String,
    val displayName: String,
    val iconPath: String = "",
    val enabled: Boolean = true,
    val priority: Int = 100,
    val apiVersion: Int = 1,
    val capabilitiesJson: String = "{}",
    val configJson: String = "{}",
    val lastUsedAt: Long = 0L,
    val failStreak: Int = 0
)

/**
 * 歌词搜索缓存。
 *
 * 搜索歌词要读每首歌的文件，几百首就是几百次标签解析，界面必然卡。
 * 所以歌词正文单独缓存在这里：扫描完成后由后台增量补全，
 * 搜索时只查这张表（LIKE 走内存索引，几毫秒出结果）。
 *
 * 表里同时存标题/艺术家，是为了命中后直接把结果行画出来，
 * 不必再回 tracks 表按 id 查一遍。
 */
@Entity(
    tableName = "lyrics_cache",
    indices = [Index(value = ["trackId"], unique = true)]
)
data class LyricsCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val title: String = "",
    val artist: String = "",
    /** 只留歌词正文，不含时间轴标记，能显著压缩体积。 */
    val lyrics: String = "",
    val translation: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 识别出来的未知/自定义标签，**逐条持久化**。
 *
 * 为什么要落库：之前"元信息"页的结果只存在内存里，关闭软件就没了，
 * 下次启动重新扫——用户看到的是"只显示 40 项"，因为只扫完第一批。
 * 落库之后打开页面直接读出结果，不需要任何预扫描。
 *
 * 键是 (trackId, key)：同一首歌的同一个标签只留一条，重新扫描时覆盖。
 */
@Entity(
    tableName = "meta_tags",
    indices = [Index(value = ["trackId"]), Index(value = ["key"])]
)
data class MetaTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** 标签键名，保留容器原始写法（`TXXX:FOO`、`----:com.apple.iTunes:BAR`）。 */
    val key: String,
    val value: String = "",
    /** 命中的平台名，未命中为空。 */
    val platform: String = "",
    /** 命中的转码工具，未命中为空。 */
    val tool: String = "",
    /** 启发式提示，用换行分隔。 */
    val hints: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 开头静音档案。
 *
 * 以前存在 `Android/data/<包名>/files/silence_profiles.json` 里，现在改成数据库表。
 * 理由是"数据尽量用数据库记录"：单文件 JSON 每次改动都要整份重写
 * （几百首就是几百 KB 的写放大），也享受不到事务和并发保护。
 *
 * 用 `trackId` 做主键而不是路径：路径会因为用户移动文件而变，
 * 而 trackId 跟着曲目走，曲目删了档案也自然该失效。
 */
@Entity(tableName = "silence_profiles")
data class SilenceProfileEntity(
    @PrimaryKey val trackId: Long,
    /** 播放时应该跳过的毫秒数。 */
    val skipMs: Int = 0,
    /** 检测到的开头静音长度。 */
    val silentHeadMs: Int = 0
)

@Entity(tableName = "scrape_logs")
data class ScrapeLogEntity(    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val sourceId: String,
    val capability: String,
    val status: String,
    val message: String = "",
    val costMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)