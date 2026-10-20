package com.audiometa.app.plugin.runtime

import android.content.Context
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths
import org.json.JSONObject
import java.io.File

/**
 * 插件可用的键值缓存（`Platform.cache`）。
 *
 * **为什么是一个 JSON 文件而不是 SharedPreferences**：
 * 插件 API 是**同步**的（`Platform.cache.get()` 直接返回字符串），
 * 而 DataStore 全是 suspend —— 用不了。SharedPreferences 能用，
 * 但它固定在私有目录 `shared_prefs/`，用户访问不到；
 * 用户要求非数据库数据都放到 `Android/data/<包名>/files/`，所以改成自己管一个文件。
 *
 * 数据量很小（插件只拿来缓存 token、时间戳这类东西），整份读进内存、
 * 写的时候整份落盘完全够用，不需要数据库。
 *
 * 文件：`files/插件缓存.json`
 */
object PluginCacheStore {

    private const val FILE_NAME = "插件缓存.json"
    private const val EXPIRY_SUFFIX = ".__expires"

    /** 旧版本用的 SharedPreferences 名字。只在升级时读一次。 */
    private const val LEGACY_PREFS = "audiometa_plugin_cache"

    private var loaded = false
    private val memory = mutableMapOf<String, String>()

    private fun file(context: Context): File = File(StoragePaths.externalRoot(context), FILE_NAME)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true

        // 1. 先读已有文件
        runCatching {
            val target = file(context)
            if (target.isFile && target.length() > 0L) {
                val root = JSONObject(target.readText())
                root.keys().forEach { key -> memory[key] = root.optString(key) }
            }
        }.onFailure { AppLog.d(TAG, "读取插件缓存失败：" + it.message) }

        // 2. 升级路径：把旧 SharedPreferences 里的内容搬过来，然后清掉旧的
        runCatching {
            val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            if (prefs.all.isNotEmpty()) {
                prefs.all.forEach { (key, value) ->
                    if (!memory.containsKey(key) && value is String) memory[key] = value
                }
                prefs.edit().clear().apply()
                persist(context)
                AppLog.i(TAG, "已把插件缓存从 SharedPreferences 迁移到 " + FILE_NAME)
            }
        }.onFailure { AppLog.d(TAG, "迁移旧插件缓存失败：" + it.message) }
    }

    @Synchronized
    fun get(context: Context, key: String): String {
        ensureLoaded(context)
        val expiresAt = memory[key + EXPIRY_SUFFIX]?.toLongOrNull() ?: 0L
        val value = memory[key] ?: return ""
        if (expiresAt in 1 until System.currentTimeMillis()) {
            memory.remove(key)
            memory.remove(key + EXPIRY_SUFFIX)
            persist(context)
            return ""
        }
        return value
    }

    @Synchronized
    fun set(context: Context, key: String, value: String, ttlMs: Long) {
        ensureLoaded(context)
        memory[key] = value
        if (ttlMs > 0) {
            memory[key + EXPIRY_SUFFIX] = (System.currentTimeMillis() + ttlMs).toString()
        } else {
            memory.remove(key + EXPIRY_SUFFIX)
        }
        persist(context)
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        ensureLoaded(context)
        memory.remove(key)
        memory.remove(key + EXPIRY_SUFFIX)
        persist(context)
    }

    private fun persist(context: Context) {
        runCatching {
            val target = file(context)
            if (memory.isEmpty()) {
                // 空了就把文件删掉，别留一个内容为 {} 的无意义文件。
                target.delete()
                return@runCatching
            }
            val root = JSONObject()
            memory.forEach { (key, value) -> root.put(key, value) }
            // 先写临时文件再改名，中途被杀不会留下半个损坏的 JSON。
            val temp = File(StoragePaths.ensuredDir(context, StoragePaths.DIR_TEMP), FILE_NAME + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.writeText(root.toString())
                temp.delete()
            }
        }.onFailure { AppLog.d(TAG, "写入插件缓存失败：" + it.message) }
    }

    /** 清空缓存（设置里的"清空插件缓存"用）。 */
    @Synchronized
    fun clear(context: Context) {
        memory.clear()
        loaded = true
        runCatching { file(context).delete() }
    }

    fun size(context: Context): Int {
        ensureLoaded(context)
        // 不把过期时间戳算成有效条目。
        return memory.keys.count { !it.endsWith(EXPIRY_SUFFIX) }
    }

    private const val TAG = "PluginCacheStore"
}
