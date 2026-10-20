package com.audiometa.app.domain.usecase

import com.audiometa.app.domain.model.TrackMeta
import org.json.JSONObject

/**
 * 插件返回的一条歌曲 JSON → [TrackMeta]。
 *
 * 字段名各源写法不同（title / track_number / disc_number / language…），
 * 所以每个字段都给一串候选键，先看 `fields` 再看顶层。
 */
object MetaFields {

    fun fromSong(song: JSONObject): TrackMeta {
        val fields = song.optJSONObject("fields") ?: JSONObject()
        fun pick(vararg keys: String): String {
            keys.forEach { key ->
                fields.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                song.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        val date = pick("date", "year", "originaldate", "originalyear")
        val trackRaw = pick("track", "trackNo", "track_number", "tracknumber")
        val discRaw = pick("disc", "discNo", "disc_number", "discnumber", "disk")
        return TrackMeta(
            title = pick("title"),
            artist = pick("artist"),
            albumArtist = pick("album_artist", "albumArtist"),
            album = pick("album"),
            trackNo = trackRaw.substringBefore('/').trim().toIntOrNull() ?: 0,
            discNo = discRaw.substringBefore('/').trim().toIntOrNull() ?: 0,
            // year 只取前四位，date 保留完整日期（有的源给 yyyy-MM-dd）。
            year = date.take(4).filter { it.isDigit() }.takeIf { it.length == 4 }?.toIntOrNull() ?: 0,
            date = date,
            genre = pick("genre"),
            language = pick("language", "lang", "lyrics_language"),
            composer = pick("composer"),
            lyricist = pick("lyricist"),
            conductor = pick("conductor"),
            comment = pick("comment", "description", "subtitle", "desc"),
            copyright = pick("copyright"),
            isrc = pick("isrc"),
            bpm = pick("bpm", "tempo")
        )
    }

    /**
     * 把一份 TrackMeta 摊平成「标签 → 值」，顺序就是详情页要显示的顺序。
     * 空值不进入结果，所以界面天然做到「没有的信息就不显示」。
     */
    fun displayPairs(meta: TrackMeta, trackNoOfTotal: Int = 0, discNoOfTotal: Int = 0): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun put(label: String, value: String) {
            if (value.isNotBlank()) out += label to value
        }
        // 用户要求：这一项叫「标题」，不再叫「歌曲名」（和详情页的元信息区保持一致）。
        put("标题", meta.title)
        put("艺术家", meta.artist)
        put("专辑艺术家", meta.albumArtist)
        put("专辑", meta.album)
        if (meta.trackNo > 0) {
            put(
                "音轨号",
                if (trackNoOfTotal > 0) meta.trackNo.toString() + "/" + trackNoOfTotal else meta.trackNo.toString()
            )
        }
        if (meta.discNo > 0) {
            put(
                "碟号",
                if (discNoOfTotal > 0) meta.discNo.toString() + "/" + discNoOfTotal else meta.discNo.toString()
            )
        }
        put("日期", meta.date.ifBlank { if (meta.year > 0) meta.year.toString() else "" })
        put("流派", meta.genre)
        put("语言", meta.language)
        put("作曲", meta.composer)
        put("作词", meta.lyricist)
        put("指挥", meta.conductor)
        put("注释", meta.comment)
        put("版权", meta.copyright)
        put("ISRC", meta.isrc)
        put("BPM", meta.bpm)
        return out
    }
}
