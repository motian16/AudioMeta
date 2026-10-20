package com.audiometa.app.media.audio

import android.content.Context
import com.audiometa.app.data.db.SilenceProfileEntity
import com.audiometa.app.data.db.forEachChunk
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * 静音跳过时间的持久化。
 *
 * **数据存在数据库里**（`silence_profiles` 表），不再是一个独立的 JSON 文件。
 *
 * 为什么改：用户要求"数据尽量用数据库记录"。原来的做法有一串实际问题 ——
 * 每写一首就要把整份 JSON 重写一遍（几百首就是几百 KB 的写放大）、
 * 没有事务保护、脏读时可能读到写了一半的文件、表结构重建数据库时它反而是个
 * 游离在外的孤儿文件。
 *
 * 但**内存镜像保留**：播放时要频繁查（每次换歌都查一次），
 * 不能每次都过一趟 SQL。库里几百行、每行几个整数，一次读进内存只有几十 KB。
 *
 * 键用 [SilenceProfileEntity.trackId] 而不是路径：路径会随用户移动文件变化，
 * 而 trackId 跟着曲目走，曲目删了档案自然该失效。
 */
object SilenceProfileStore {

    /** 旧版本的 JSON 文件名。只在升级时读一次，导入完就删掉。 */
    private const val LEGACY_FILE_NAME = "silence_profiles.json"

    /** 内存镜像。key 是 trackId。 */
    private val memory = LinkedHashMap<Long, SilenceProfile>()
    private var loaded = false

    /** 旧版本的 JSON 文件。只在升级时读一次，导入完就删掉。 */
    private fun legacyFile(context: Context): File =
        File(com.audiometa.app.util.StoragePaths.externalRoot(context), LEGACY_FILE_NAME)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val rows = runBlocking { ServiceLocator.database.silenceProfileDao().all() }
            rows.forEach { row ->
                memory[row.trackId] = SilenceProfile(
                    skipMs = row.skipMs,
                    silentHeadMs = row.silentHeadMs,
                    analyzed = true
                )
            }
            AppLog.d(TAG, "静音档案已从数据库载入 " + rows.size + " 条")
        }.onFailure { AppLog.d(TAG, "读取静音档案失败：" + it.message) }

        // 升级路径：老版本把档案存在 JSON 文件里，导入一次然后删掉。
        importLegacyFile(context)
    }

    /**
     * 把旧版 JSON 里的档案导入数据库。
     *
     * 只在文件存在时跑一次。旧的键是**音频路径**，要经过一次
     * "路径 → trackId" 的换算；换算不到的条目直接丢弃（那首歌已经不在库里了）。
     */
    private fun importLegacyFile(context: Context) {
        val file = legacyFile(context)
        if (!file.isFile || file.length() == 0L) return
        runCatching {
            val root = JSONObject(file.readText())
            if (root.length() == 0) {
                file.delete()
                return@runCatching
            }
            val idByPath = runBlocking {
                ServiceLocator.tracks.idPathPairs().associate { it.path to it.id }
            }
            val rows = mutableListOf<SilenceProfileEntity>()
            root.keys().forEach { path ->
                val id = idByPath[path] ?: return@forEach
                val entry = root.optJSONObject(path) ?: return@forEach
                val skip = entry.optInt("skip", 0)
                val head = entry.optInt("head", 0)
                if (skip <= 0 && head <= 0) return@forEach
                rows += SilenceProfileEntity(trackId = id, skipMs = skip, silentHeadMs = head)
                memory[id] = SilenceProfile(skipMs = skip, silentHeadMs = head, analyzed = true)
            }
            if (rows.isNotEmpty()) {
                runBlocking { ServiceLocator.database.silenceProfileDao().upsertAll(rows) }
            }
            file.delete()
            AppLog.i(TAG, "已把旧的静音档案 JSON 导入数据库：" + rows.size + " 条，原文件已删除")
        }.onFailure {
            AppLog.w(TAG, "导入旧的静音档案失败（保留原文件）：" + it.message)
        }
    }

    /** 取一首歌的跳过时间；没算过就是 [SilenceProfile.NONE]。 */
    fun get(context: Context, trackId: Long): SilenceProfile {
        ensureLoaded(context)
        return memory[trackId] ?: SilenceProfile.NONE
    }

    fun put(context: Context, trackId: Long, profile: SilenceProfile) {
        synchronized(this) {
            ensureLoaded(context)
            memory[trackId] = profile
            runCatching {
                runBlocking {
                    ServiceLocator.database.silenceProfileDao()
                        .upsertAll(
                            listOf(
                                SilenceProfileEntity(
                                    trackId = trackId,
                                    skipMs = profile.skipMs,
                                    silentHeadMs = profile.silentHeadMs
                                )
                            )
                        )
                }
            }.onFailure { AppLog.d(TAG, "写静音档案失败：" + it.message) }
        }
    }

    /** 批量写入：后台一轮算完几百首，一次事务写完。 */
    fun putAll(context: Context, values: Map<Long, SilenceProfile>) {
        if (values.isEmpty()) return
        synchronized(this) {
            ensureLoaded(context)
            memory.putAll(values)
            runCatching {
                runBlocking {
                    ServiceLocator.database.silenceProfileDao().upsertAll(
                        values.map { (id, profile) ->
                            SilenceProfileEntity(
                                trackId = id,
                                skipMs = profile.skipMs,
                                silentHeadMs = profile.silentHeadMs
                            )
                        }
                    )
                }
            }.onFailure { AppLog.d(TAG, "批量写静音档案失败：" + it.message) }
        }
    }

    /** 曲库清理后丢掉已经不存在的条目。 */
    fun prune(context: Context, aliveIds: Set<Long>) {
        synchronized(this) {
            ensureLoaded(context)
            val removed = memory.keys.filterNot { it in aliveIds }
            if (removed.isEmpty()) return
            removed.forEach { memory.remove(it) }
            runCatching {
                runBlocking {
                    val dao = ServiceLocator.database.silenceProfileDao()
                    // 按块删：曲库上千首时 `trackId NOT IN (…)` 会踩 SQLite 的
                    // 绑定变量上限（见 SQL_CHUNK），那样这一步会静默失败、档案越堆越多。
                    dao.allTrackIds().filterNot { it in aliveIds }
                        .forEachChunk { dao.deleteByIds(it) }
                }
            }.onFailure { AppLog.d(TAG, "清理静音档案失败：" + it.message) }
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            memory.clear()
            loaded = true
            runCatching {
                runBlocking { ServiceLocator.database.silenceProfileDao().clear() }
            }.onFailure { AppLog.d(TAG, "清空静音档案失败：" + it.message) }
            // 顺手把旧版本的 JSON 文件也删掉，免得它一直留在目录里。
            runCatching { legacyFile(context).delete() }
        }
    }

    /** 已经算过多少首。设置里的缓存统计用。 */
    fun size(context: Context): Int {
        synchronized(this) {
            ensureLoaded(context)
            return memory.size
        }
    }

    /**
     * 这一项占用的字节数估算。
     *
     * 数据在数据库里，没法像文件那样直接量。每行是 1 个 Long + 2 个 Int
     * 加 SQLite 的行开销，按 60 字节估算 —— 目的是让用户对量级有概念。
     */
    fun bytes(context: Context): Long = size(context).toLong() * 60L

    private const val TAG = "SilenceProfileStore"
}
