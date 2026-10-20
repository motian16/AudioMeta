package com.audiometa.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, trackNo, title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderPath = :folder OR folderPath LIKE :prefix ORDER BY title COLLATE NOCASE")
    fun observeByFolder(folder: String, prefix: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks")
    suspend fun clear()

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    /**
     * 按 id 删（分块调用，见 [SQL_CHUNK]）。
     *
     * 「清理失效曲目」走的是它：`path NOT IN (…)` 无法分块（每一段都不知道
     * 别的段里还有没有有效路径），整份路径塞进一条 SQL 又会在几千首时
     * 抛 `too many SQL variables`，所以先在内存里算出失效的 id，再按块删。
     */
    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("UPDATE tracks SET scrapeState = :state, scrapeMessage = :message, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateScrapeState(id: Long, state: Int, message: String, updatedAt: Long)

    /**
     * 后台增量补全：只补"基础扫描没做"的那几项。
     *
     * 刻意不动 updatedAt：详情页的图片缓存键里带着它，
     * 一动整页封面就要重新解码，滚动会明显掉帧。
     */
    @Query(
        """UPDATE tracks SET hasArtistImage = :hasArtist, hasCover = :hasCover,
        hasLyrics = :hasLyrics, hasTranslation = :hasTranslation, lyricsType = :lyricsType,
        artistImagePath = :artistImagePath, coverPath = :coverPath, lyricsIndexedAt = :indexedAt
        WHERE id = :id"""
    )
    suspend fun applyEnrichment(
        id: Long,
        hasArtist: Boolean,
        hasCover: Boolean,
        hasLyrics: Boolean,
        hasTranslation: Boolean,
        lyricsType: String,
        artistImagePath: String?,
        coverPath: String?,
        indexedAt: Long
    )

    /**
     * 「这个文件刚被写过」时的整行重读。
     *
     * 和 [applyEnrichment] 的区别是**标签字段也一起写回**，而且**会动 updatedAt**：
     * - 用户手动改完元信息（例如专辑），列表和详情页读的都是这一行，
     *   只更新 hasCover/hasLyrics 这些状态位的话，界面上还是旧标题/旧专辑；
     * - updatedAt 是封面缓存的版本号（见 TrackCover.cacheKey），写完不换号
     *   就会一直命中改动前的图。
     *
     * 后台批量补全**不能**走这里：那会给整库每一首都换号，详情页封面集体重新解码，
     * 滑动明显掉帧（[applyEnrichment] 的注释里写过这个取舍）。
     */
    @Query(
        """UPDATE tracks SET title = :title, artist = :artist, albumArtist = :albumArtist,
        album = :album, trackNo = :trackNo, year = :year, genres = :genres,
        hasArtistImage = :hasArtist, hasCover = :hasCover, hasLyrics = :hasLyrics,
        hasTranslation = :hasTranslation, lyricsType = :lyricsType,
        artistImagePath = :artistImagePath, coverPath = :coverPath,
        lyricsIndexedAt = :indexedAt, updatedAt = :updatedAt
        WHERE id = :id"""
    )
    suspend fun refreshFromFile(
        id: Long,
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        trackNo: Int,
        year: Int,
        genres: String,
        hasArtist: Boolean,
        hasCover: Boolean,
        hasLyrics: Boolean,
        hasTranslation: Boolean,
        lyricsType: String,
        artistImagePath: String?,
        coverPath: String?,
        indexedAt: Long,
        updatedAt: Long
    )

    /** 后台增量刷新要处理的曲目：还没进过歌词索引的。 */
    @Query("SELECT * FROM tracks WHERE lyricsIndexedAt = 0 ORDER BY path COLLATE NOCASE")
    suspend fun needingLyricsIndex(): List<TrackEntity>
    /** 当前所有曲目的 id + 路径，后台任务用它和磁盘对照。 */
    @Query("SELECT id, path FROM tracks")
    suspend fun idPathPairs(): List<TrackIdPath>

    /** 静音预计算要用的轻量列表。 */
    @Query("SELECT id, path, title FROM tracks ORDER BY path COLLATE NOCASE")
    suspend fun silenceTargets(): List<TrackSilenceTarget>

    @Query("UPDATE tracks SET lyricsIndexedAt = :at WHERE id = :id")
    suspend fun markLyricsIndexed(id: Long, at: Long)

    /** 刷新全部缓存时用：把所有曲目重新标记为"待补全"。 */
    @Query("UPDATE tracks SET lyricsIndexedAt = 0")
    suspend fun resetLyricsIndex()

    @Query("UPDATE tracks SET genres = :genres WHERE id = :id")
    suspend fun updateGenres(id: Long, genres: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """UPDATE tracks SET hasArtistImage = :hasArtist, hasCover = :hasCover, hasLyrics = :hasLyrics,
        hasTranslation = :hasTranslation, lyricsType = :lyricsType, artistImagePath = :artistImagePath,
        coverPath = :coverPath, lyricsPath = :lyricsPath, title = :title, artist = :artist, album = :album,
        year = :year, trackNo = :trackNo, scrapeState = :state, scrapeMessage = :message, updatedAt = :updatedAt
        WHERE id = :id"""
    )
    suspend fun updateAfterScrape(
        id: Long,
        hasArtist: Boolean,
        hasCover: Boolean,
        hasLyrics: Boolean,
        hasTranslation: Boolean,
        lyricsType: String,
        artistImagePath: String?,
        coverPath: String?,
        lyricsPath: String?,
        title: String,
        artist: String,
        album: String,
        year: Int,
        trackNo: Int,
        state: Int,
        message: String,
        updatedAt: Long
    )
}

/** 批量操作的轻量投影，避免把整行都读出来。 */
data class TrackIdPath(val id: Long, val path: String)

/** 静音预计算只需要这三个字段。 */
data class TrackSilenceTarget(val id: Long, val path: String, val title: String)

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId LIMIT 1")
    suspend fun get(trackId: Long): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clear()

    /**
     * 歌词搜索。LIKE 对大小写不敏感（SQLite 默认行为），
     * 中文没有大小写问题，英文也能一并命中。
     */
    @Query(
        """SELECT * FROM lyrics_cache
        WHERE lyrics LIKE '%' || :needle || '%' OR translation LIKE '%' || :needle || '%'
        ORDER BY title COLLATE NOCASE LIMIT :limit"""
    )
    suspend fun search(needle: String, limit: Int = 300): List<LyricsCacheEntity>

    /**
     * 表里现有行的 trackId。清理时先在内存里和曲库比对，再按块删（见 [SQL_CHUNK]）。
     */
    @Query("SELECT trackId FROM lyrics_cache")
    suspend fun allTrackIds(): List<Long>

    @Query("DELETE FROM lyrics_cache WHERE trackId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM lyrics_cache")
    suspend fun count(): Int

    /** 统计占用时要把所有行读出来按正文长度估算。 */
    @Query("SELECT * FROM lyrics_cache")
    suspend fun all(): List<LyricsCacheEntity>
}

/**
 * 开头静音档案。
 *
 * 整表读进内存一次（几百行、每行几个数字，几十 KB），
 * 之后 `get` 走内存，`put` 写库 —— 播放时要频繁查，不能每次都过 SQL。
 */
@Dao
interface SilenceProfileDao {
    @Query("SELECT * FROM silence_profiles")
    suspend fun all(): List<SilenceProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SilenceProfileEntity>)

    /**
     * 表里现有行的 trackId，清理时先在内存里比对再按块删（见 [SQL_CHUNK]）。
     */
    @Query("SELECT trackId FROM silence_profiles")
    suspend fun allTrackIds(): List<Long>

    @Query("DELETE FROM silence_profiles WHERE trackId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM silence_profiles")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM silence_profiles")
    suspend fun count(): Int
}

/**
 * 未知/自定义标签。
 *
 * 逐条存，不是存成一大坨 JSON：这样"某个标签出现在哪些歌里"可以直接查，
 * 界面上的聚合也能用 SQL 完成，不必每次把几万条读进内存再分组。
 */
@Dao
interface MetaTagDao {
    @Query("SELECT * FROM meta_tags")
    fun observeAll(): Flow<List<MetaTagEntity>>

    @Query("SELECT * FROM meta_tags")
    suspend fun all(): List<MetaTagEntity>

    @Query("SELECT * FROM meta_tags WHERE trackId = :trackId")
    suspend fun forTrack(trackId: Long): List<MetaTagEntity>

    /** 同一首歌的同一个标签只留一条。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MetaTagEntity>)

    @Query("DELETE FROM meta_tags WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: Long)

    /**
     * 表里现有行的 trackId，清理时先在内存里比对再按块删（见 [SQL_CHUNK]）。
     */
    @Query("SELECT DISTINCT trackId FROM meta_tags")
    suspend fun allTrackIds(): List<Long>

    @Query("DELETE FROM meta_tags WHERE trackId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM meta_tags")
    suspend fun clear()

    @Query("SELECT COUNT(DISTINCT trackId) FROM meta_tags")
    suspend fun scannedTrackCount(): Int
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY path COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY path COLLATE NOCASE")
    suspend fun all(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("SELECT * FROM folders WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): FolderEntity?

    @Query("UPDATE folders SET excluded = :excluded WHERE path = :path OR path LIKE :prefix")
    suspend fun setExcludedTree(path: String, prefix: String, excluded: Boolean)

    @Query("UPDATE folders SET trackCount = :count, lastScanAt = :at WHERE path = :path")
    suspend fun updateCount(path: String, count: Int, at: Long)

    @Query("DELETE FROM folders")
    suspend fun clear()
}

@Dao
interface ArtistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artist: ArtistEntity): Long

    @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ArtistEntity?

    @Query("SELECT * FROM artists")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists")
    suspend fun all(): List<ArtistEntity>
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugin_records ORDER BY pluginName COLLATE NOCASE")
    fun observeAll(): Flow<List<PluginRecordEntity>>

    @Query("SELECT * FROM plugin_records")
    suspend fun all(): List<PluginRecordEntity>

    @Query("SELECT * FROM plugin_records WHERE pluginId = :pluginId LIMIT 1")
    suspend fun get(pluginId: String): PluginRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PluginRecordEntity): Long

    @Query("DELETE FROM plugin_records WHERE pluginId = :pluginId")
    suspend fun delete(pluginId: String)

    @Query("UPDATE plugin_records SET enabled = :enabled WHERE pluginId = :pluginId")
    suspend fun setEnabled(pluginId: String, enabled: Boolean)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_records ORDER BY priority ASC, displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceRecordEntity>>

    @Query("SELECT * FROM source_records ORDER BY priority ASC, displayName COLLATE NOCASE")
    suspend fun all(): List<SourceRecordEntity>

    @Query("SELECT * FROM source_records WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun enabled(): List<SourceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SourceRecordEntity)

    @Query("DELETE FROM source_records WHERE pluginId = :pluginId")
    suspend fun deleteByPlugin(pluginId: String)

    @Query("DELETE FROM source_records WHERE pluginId = :pluginId AND sourceId NOT IN (:keep)")
    suspend fun deleteMissing(pluginId: String, keep: List<String>)

    @Query("UPDATE source_records SET enabled = :enabled WHERE pluginId = :pluginId AND sourceId = :sourceId")
    suspend fun setEnabled(pluginId: String, sourceId: String, enabled: Boolean)

    @Query("UPDATE source_records SET priority = :priority WHERE pluginId = :pluginId AND sourceId = :sourceId")
    suspend fun setPriority(pluginId: String, sourceId: String, priority: Int)

    @Query("UPDATE source_records SET configJson = :config WHERE pluginId = :pluginId AND sourceId = :sourceId")
    suspend fun setConfig(pluginId: String, sourceId: String, config: String)

    @Query("UPDATE source_records SET failStreak = :streak, lastUsedAt = :at WHERE pluginId = :pluginId AND sourceId = :sourceId")
    suspend fun setFailStreak(pluginId: String, sourceId: String, streak: Int, at: Long)

    @Query("SELECT * FROM source_records WHERE pluginId = :pluginId AND sourceId = :sourceId LIMIT 1")
    suspend fun get(pluginId: String, sourceId: String): SourceRecordEntity?
}

@Dao
interface ScrapeLogDao {
    @Insert
    suspend fun insert(log: ScrapeLogEntity): Long

    @Query("SELECT * FROM scrape_logs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<ScrapeLogEntity>

    @Query("DELETE FROM scrape_logs")
    suspend fun clear()

    /** 清理旧日志，避免表无限增长。 */
    @Query("DELETE FROM scrape_logs WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM scrape_logs")
    suspend fun count(): Int
}