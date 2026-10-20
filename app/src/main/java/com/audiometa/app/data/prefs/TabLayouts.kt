package com.audiometa.app.data.prefs

import com.audiometa.app.ui.screen.library.ArtistSort
import com.audiometa.app.ui.screen.library.GenreSort
import com.audiometa.app.ui.screen.library.SortField
import com.audiometa.app.ui.screen.library.TabLayout
import org.json.JSONObject

/**
 * 每页布局配置的读写。
 *
 * 存成一个 JSON 对象而不是十几个独立偏好项：
 *
 * ```json
 * { "SONGS": { "g": false, "s": "ADDED", "d": true },
 *   "ARTIST": { "g": true, "s": "COUNT", "d": true } }
 * ```
 *
 * 好处是加一个页签、加一种排序字段都不用动存储层。
 * 解析失败时退回默认值 —— 坏配置不该让曲库打不开。
 *
 * **列数不在这里**：每行个数是全局设置（见 `AppSettings.gridPreset`），
 * 歌曲 / 艺术家 / 曲风三页共用一份，用户明确要求"全局生效"。
 * 旧版本每页存的 `c` 键读出来后直接忽略。
 */
object TabLayouts {

    /**
     * 所有页签的默认布局。
     *
     * 艺术家页和曲风页默认都按**数量**排（用户要求），艺术家/曲风都是网格，
     * 歌曲页默认是列表。
     */
    private fun defaultFor(mode: String): TabLayout = when (mode) {
        "SONGS" -> TabLayout(asGrid = false, sortKey = SortField.MODIFIED.name, sortDescending = true)
        "ARTIST" -> TabLayout(asGrid = true, sortKey = ArtistSort.COUNT.name, sortDescending = true)
        "GENRE" -> TabLayout(asGrid = true, sortKey = GenreSort.COUNT.name, sortDescending = true)
        else -> TabLayout()
    }

    /** 读出某个页签的布局；没有存过就用默认值。 */
    fun read(json: String, mode: String): TabLayout {
        val fallback = defaultFor(mode)
        if (json.isBlank()) return fallback
        return runCatching {
            val root = JSONObject(json)
            val obj = root.optJSONObject(mode) ?: return fallback
            TabLayout(
                asGrid = obj.optBoolean("g", fallback.asGrid),
                // 空字符串交给各页的 fromName 解析成该页默认值。
                sortKey = obj.optString("s").ifBlank { fallback.sortKey },
                sortDescending = obj.optBoolean("d", fallback.sortDescending)
            )
        }.getOrDefault(fallback)
    }

    /** 写回某个页签的布局，保留其它页签的设置。 */
    fun write(json: String, mode: String, layout: TabLayout): String = runCatching {
        val root = if (json.isBlank()) JSONObject() else JSONObject(json)
        root.put(
            mode,
            JSONObject()
                .put("g", layout.asGrid)
                .put("s", layout.sortKey)
                .put("d", layout.sortDescending)
        )
        root.toString()
    }.getOrDefault(json)

    /**
     * 兼容旧版本：以前是三个布尔项 + 一个全局列数。
     * 首次升级时把它们读进新的每页配置，用户不必重设一遍。
     */
    fun migrateLegacy(
        songsAsGrid: Boolean,
        artistsAsGrid: Boolean,
        genresAsGrid: Boolean
    ): String = runCatching {
        JSONObject()
            .put("SONGS", JSONObject().put("g", songsAsGrid).put("s", SortField.MODIFIED.name).put("d", true))
            .put("ARTIST", JSONObject().put("g", artistsAsGrid).put("s", ArtistSort.COUNT.name).put("d", true))
            .put("GENRE", JSONObject().put("g", genresAsGrid).put("s", GenreSort.COUNT.name).put("d", true))
            .toString()
    }.getOrDefault("")
}
