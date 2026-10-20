package com.audiometa.app.data.repo

import com.audiometa.app.data.db.AppDatabase
import com.audiometa.app.data.db.ArtistEntity
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.LyricsCacheEntity
import com.audiometa.app.data.db.MetaTagEntity
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.db.RemoteAddressEntity
import com.audiometa.app.data.db.RemoteDeviceEntity
import com.audiometa.app.data.db.SQL_CHUNK
import com.audiometa.app.data.db.ScrapeLogEntity
import com.audiometa.app.data.db.SourceRecordEntity
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.db.forEachChunk
import kotlinx.coroutines.flow.Flow

class TrackRepository(db: AppDatabase) {
    private val dao = db.trackDao()
    fun observeAll(): Flow<List<TrackEntity>> = dao.observeAll()    fun observeByFolder(folder: String): Flow<List<TrackEntity>> = dao.observeByFolder(folder, "$folder/%")
    suspend fun getById(id: Long) = dao.getById(id)

    /** 按 id 批量取；分块查，避免 id 太多踩到 SQLite 的绑定变量上限（见 [SQL_CHUNK]）。 */
    suspend fun getByIds(ids: List<Long>): List<TrackEntity> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<TrackEntity>()
        ids.chunked(SQL_CHUNK).forEach { out += dao.getByIds(it) }
        return out
    }
    suspend fun getByPath(path: String) = dao.getByPath(path)
    suspend fun upsert(track: TrackEntity): Long {
        val existing = dao.getByPath(track.path)
        return if (existing == null) dao.insert(track) else {
            dao.update(track.copy(id = existing.id))
            existing.id
        }
    }

    suspend fun upsertAll(tracks: List<TrackEntity>) {
        tracks.forEach { upsert(it) }
    }

    /** 扫描时用来判断文件是否变化，避免每次都重读标签。 */
    suspend fun allByPath(): Map<String, TrackEntity> = dao.all().associateBy { it.path }

    /** 一次性取全部曲目（不走 Flow）。搜索和后台任务用。 */
    suspend fun allOnce(): List<TrackEntity> = dao.all()

    /**
     * 清理已经不在磁盘上的曲目。seenPaths 为空时直接返回，
     * 避免存储卡临时未挂载就把整个曲库删掉。
     *
     * 实现上先在内存里算出失效的 id，再按块删：把整份 seenPaths 塞进
     * `path NOT IN (…)` 会在几千首（媒体库来源很常见）时抛
     * `too many SQL variables` —— 那是**未捕获**的异常，扫描会直接把应用带崩。
     */
    suspend fun deleteMissing(seenPaths: Collection<String>): Int {
        if (seenPaths.isEmpty()) return 0
        val seen = seenPaths.toHashSet()
        val stale = dao.idPathPairs().filterNot { it.path in seen }.map { it.id }
        return stale.forEachChunk { dao.deleteByIds(it) }
    }

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
        message: String
    ) = dao.updateAfterScrape(
        id, hasArtist, hasCover, hasLyrics, hasTranslation, lyricsType,
        artistImagePath, coverPath, lyricsPath, title, artist, album, year, trackNo,
        state, message, System.currentTimeMillis()
    )

    suspend fun clear() = dao.clear()
    suspend fun count() = dao.count()

    // ---- 后台增量补全 ----

    suspend fun needingLyricsIndex() = dao.needingLyricsIndex()
    suspend fun idPathPairs() = dao.idPathPairs()
    suspend fun silenceTargets() = dao.silenceTargets()

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
    ) = dao.applyEnrichment(
        id, hasArtist, hasCover, hasLyrics, hasTranslation, lyricsType,
        artistImagePath, coverPath, indexedAt
    )

    suspend fun markLyricsIndexed(id: Long, at: Long = System.currentTimeMillis()) =
        dao.markLyricsIndexed(id, at)

    /**
     * 文件被写过之后的整行重读（标签字段 + updatedAt 一起更新）。
     *
     * 见 [com.audiometa.app.data.db.TrackDao.refreshFromFile] 的说明：
     * 用户手动改元信息、刮削写盘、换图之后都必须走这条，否则界面上
     * 显示的还是改动前的那一行（用户报过"改了专辑不刷新"）。
     */
    suspend fun refreshFromFile(track: TrackEntity) = dao.refreshFromFile(
        id = track.id,
        title = track.title,
        artist = track.artist,
        albumArtist = track.albumArtist,
        album = track.album,
        trackNo = track.trackNo,
        year = track.year,
        genres = track.genres,
        hasArtist = track.hasArtistImage,
        hasCover = track.hasCover,
        hasLyrics = track.hasLyrics,
        hasTranslation = track.hasTranslation,
        lyricsType = track.lyricsType,
        artistImagePath = track.artistImagePath,
        coverPath = track.coverPath,
        indexedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    /** 刷新全部缓存：把所有曲目标回"待补全"，后台会重新建一遍。 */
    suspend fun resetLyricsIndex() = dao.resetLyricsIndex()

    suspend fun updateGenres(id: Long, genres: String) = dao.updateGenres(id, genres)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
}

/**
 * 歌词搜索缓存。
 *
 * 单独一个仓库：它只服务「按歌词搜索」，和曲目索引的读写节奏完全不同
 * （增量补全写、搜索读），分开更容易看清楚谁在动它。
 */
/**
 * 未知/自定义标签的读写。
 *
 * 结果**逐条落库**，所以「元信息」页打开就能直接显示上次扫描的结果，
 * 不需要预扫描。这是修"重启后只显示 40 项"的关键：
 * 以前结果只在内存里，重启即丢，只能重新扫第一批。
 */
class MetaTagRepository(db: AppDatabase) {
    private val dao = db.metaTagDao()

    fun observeAll(): Flow<List<MetaTagEntity>> = dao.observeAll()
    suspend fun all(): List<MetaTagEntity> = dao.all()

    /** 写入某首歌识别出的标签，先清掉这首歌的旧记录再写，避免残留。 */
    suspend fun replaceForTrack(trackId: Long, rows: List<MetaTagEntity>) {
        dao.deleteForTrack(trackId)
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    suspend fun clear() = dao.clear()

    /** 已经扫过标签的曲目数。 */
    suspend fun scannedTrackCount(): Int = dao.scannedTrackCount()

    /**
     * 清理已经不在曲库里的记录。
     *
     * 不直接用 `trackId NOT IN (…)`：曲库上千首时那条 SQL 会踩到 SQLite 的
     * 绑定变量上限（见 [SQL_CHUNK]），先读出表里现有的 trackId 在内存里比对，
     * 再按块删，任何规模都不会出问题。
     */
    suspend fun pruneTo(ids: List<Long>) {
        if (ids.isEmpty()) {
            dao.clear()
            return
        }
        val alive = ids.toHashSet()
        dao.allTrackIds().filterNot { it in alive }.forEachChunk { dao.deleteByIds(it) }
    }
}

class LyricsCacheRepository(db: AppDatabase) {    private val dao = db.lyricsCacheDao()

    suspend fun get(trackId: Long) = dao.get(trackId)
    suspend fun save(entry: LyricsCacheEntity) = dao.upsert(entry)
    suspend fun delete(trackId: Long) = dao.delete(trackId)
    suspend fun clear() = dao.clear()
    suspend fun count() = dao.count()
    suspend fun search(needle: String, limit: Int = 300) = dao.search(needle, limit)

    /** 清理已经不在曲库里的缓存行（同上：先在内存里比对，再按块删）。 */
    suspend fun pruneTo(ids: List<Long>) {
        if (ids.isEmpty()) {
            dao.clear()
            return
        }
        val alive = ids.toHashSet()
        dao.allTrackIds().filterNot { it in alive }.forEachChunk { dao.deleteByIds(it) }
    }

    /**
     * 歌词缓存占用的字节数估算。
     *
     * 数据在 Room 里，没法像文件那样直接量。按正文长度乘系数估：
     * SQLite 的 TEXT 按 UTF-8 存，中文一个字 3 字节，再加一点行开销。
     * 目的是让用户对"这一项占多少"有个量级概念，不必精确到字节。
     */
    suspend fun totalBytes(): Long = dao.all().sumOf { entry ->
        ((entry.lyrics.length + entry.translation.length).toLong() * 3L) + 128L
    }
}

class FolderRepository(db: AppDatabase) {
    private val dao = db.folderDao()
    fun observeAll(): Flow<List<FolderEntity>> = dao.observeAll()
    suspend fun all() = dao.all()
    suspend fun upsert(folder: FolderEntity) {
        val existing = dao.getByPath(folder.path)
        if (existing == null) dao.insert(folder) else dao.insert(folder.copy(id = existing.id, excluded = existing.excluded))
    }

    suspend fun setExcludedTree(path: String, excluded: Boolean) = dao.setExcludedTree(path, "$path/%", excluded)
    suspend fun updateCount(path: String, count: Int) = dao.updateCount(path, count, System.currentTimeMillis())
    suspend fun clear() = dao.clear()
}

class ArtistRepository(db: AppDatabase) {
    private val dao = db.artistDao()
    fun observeAll(): Flow<List<ArtistEntity>> = dao.observeAll()

    /** 一次性取全部（算缩略图有效 key 时用，不走 Flow）。 */
    suspend fun allOnce(): List<ArtistEntity> = dao.all()

    suspend fun upsert(artist: ArtistEntity) = dao.upsert(artist)
    suspend fun getByName(name: String) = dao.getByName(name)
}

class PluginRepository(db: AppDatabase) {
    private val plugins = db.pluginDao()
    private val sources = db.sourceDao()
    fun observePlugins(): Flow<List<PluginRecordEntity>> = plugins.observeAll()
    fun observeSources(): Flow<List<SourceRecordEntity>> = sources.observeAll()
    suspend fun plugins() = plugins.all()
    suspend fun sources() = sources.all()
    suspend fun enabledSources() = sources.enabled()
    suspend fun getPlugin(pluginId: String) = plugins.get(pluginId)
    suspend fun savePlugin(record: PluginRecordEntity) = plugins.insert(record)
    suspend fun deletePlugin(pluginId: String) {
        plugins.delete(pluginId)
        sources.deleteByPlugin(pluginId)
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = plugins.setEnabled(pluginId, enabled)
    suspend fun saveSource(source: SourceRecordEntity) = sources.insert(source)
    suspend fun deleteMissingSources(pluginId: String, keep: List<String>) {
        if (keep.isEmpty()) sources.deleteByPlugin(pluginId) else sources.deleteMissing(pluginId, keep)
    }

    suspend fun setSourceEnabled(pluginId: String, sourceId: String, enabled: Boolean) =
        sources.setEnabled(pluginId, sourceId, enabled)

    suspend fun setPriority(pluginId: String, sourceId: String, priority: Int) =
        sources.setPriority(pluginId, sourceId, priority)

    suspend fun setConfig(pluginId: String, sourceId: String, config: String) =
        sources.setConfig(pluginId, sourceId, config)

    suspend fun getSource(pluginId: String, sourceId: String) = sources.get(pluginId, sourceId)
    suspend fun setFailStreak(pluginId: String, sourceId: String, streak: Int) =
        sources.setFailStreak(pluginId, sourceId, streak, System.currentTimeMillis())
}

class LogRepository(db: AppDatabase) {
    private val dao = db.scrapeLogDao()
    suspend fun insert(log: ScrapeLogEntity) = dao.insert(log)
    suspend fun recent(limit: Int = 500) = dao.recent(limit)
    suspend fun clear() = dao.clear()

    /** 清理旧日志，避免表无限增长。返回删掉的条数。 */
    suspend fun deleteOlderThan(cutoff: Long): Int = dao.deleteOlderThan(cutoff)
}

/**
 * 记住过的 SPW 设备与它们的地址。
 *
 * 存在的意义：**断网/换网之后不用重新扫描**。
 * 手机从 Wi-Fi 切到移动网络时，局域网地址不可达了，
 * 但库里还存着上次拿到的公网地址 —— 直接用它重连，用户无感。
 *
 * 数据不产生孤立的做法见 [com.audiometa.app.data.db.RemoteDeviceEntity] 的注释：
 * 地址行外键挂在设备下行、级联删除、每轮扫描整体替换。
 */
class RemoteDeviceRepository(db: AppDatabase) {
    private val dao = db.remoteDeviceDao()

    suspend fun devices(): List<RemoteDeviceEntity> = dao.devices()

    suspend fun addressesOf(deviceId: Long): List<RemoteAddressEntity> = dao.addressesOf(deviceId)

    /**
     * 保存一轮扫描的结果。
     *
     * @param device 设备行（deviceKey 用「插件 ID + 设备名」，换 IP 不会产生新行）
     * @param addresses 这次报出来的地址；**整体替换**旧的那批
     */
    suspend fun save(
        device: RemoteDeviceEntity,
        addresses: List<RemoteAddressEntity>
    ): Long = dao.replaceDeviceWithAddresses(device, addresses)

    /** 清掉很久没出现的设备（连同地址）。 */
    suspend fun pruneStale() = dao.pruneStale()
}
