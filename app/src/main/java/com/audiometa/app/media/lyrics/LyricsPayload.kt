package com.audiometa.app.media.lyrics

import org.json.JSONObject

/**
 * 插件 `getLyrics` 返回的一条结果 → 可以写进文件的 LRC 文本。
 *
 * **三种形状都要认**（这是"搜不到歌词"的真正原因）：
 *
 * 1. **结构化**（酷狗、Apple 的一部分、网易的部分结果）：
 *    `{ "type": "structured", "tags": {...}, "original": [[起,止,[[起,止,字],…]],…],
 *       "translated": [[起,止,"译文"],…] }` —— 没有 `lyrics` 字段；
 * 2. **TTML**（Apple 的 `rawTtml`）：一整份 `<tt>…</tt>` 的 XML；
 * 3. **纯文本**（QQ、网易）：`{ "type": "word"|"line", "lyrics": "…", "translation": "…" }`。
 *
 * 以前只认第 3 种，于是 1、2 两种在"搜索歌词"和刮削里都被静默丢掉 ——
 * 用户看到的是"四个源都搜不到歌词"，而插件其实把歌词好好返回了。
 */
object LyricsPayload {

    /**
     * @param original 带 `[ti:]/[ar:]` 头的 LRC 正文（逐字歌词是增强 LRC）。
     * @param translation 翻译（没有就是空串）。
     * @param wordLevel 逐字还是逐行。
     */
    class Texts(val original: String, val translation: String, val wordLevel: Boolean)

    fun parse(item: JSONObject): Texts? {
        val tags = tagsOf(item)
        // 1. 结构化
        structured(item, tags)?.let { return it }
        // 2. Apple 的 TTML
        val ttml = item.optString("rawTtml").ifBlank { item.optString("ttml") }
        if (ttml.isNotBlank()) {
            TtmlLyrics.parse(ttml)?.let { parsed ->
                val lrc = StructuredLyrics.withTags(
                    StructuredLyrics.toLrc(parsed.original, parsed.isWordLevel),
                    tags.ifEmpty { parsed.tags }
                )
                if (lrc.isNotBlank()) return Texts(lrc, "", parsed.isWordLevel)
            }
        }
        // 3. 纯文本（老写法）
        val plain = item.optString("lyrics").ifBlank { item.optString("lyric") }
        if (plain.isBlank()) return null
        val wordLevel = item.optString("type").equals("word", true) || WORD_LEVEL.containsMatchIn(plain)
        val translation = item.optString("translation").ifBlank { item.optString("trans") }
        return Texts(StructuredLyrics.withTags(plain, tags), translation, wordLevel)
    }

    /** 顺手把 `[ti:]/[ar:]/[al:]` 头也带上（插件给了就用它）。 */
    fun tagsOf(item: JSONObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        item.optJSONObject("tags")?.let { tags ->
            tags.keys().forEach { key -> out[key] = tags.optString(key) }
        }
        // 有些源把标题/艺术家直接放在顶层。
        listOf("ti" to "title", "ar" to "artist", "al" to "album").forEach { (tag, key) ->
            if (out[tag].isNullOrBlank()) {
                item.optString(key).takeIf { it.isNotBlank() }?.let { out[tag] = it }
            }
        }
        return out
    }

    private fun structured(item: JSONObject, tags: Map<String, String>): Texts? {
        val parsed = StructuredLyrics.parse(item) ?: return null
        if (!parsed.hasContent) return null
        val wordLevel = parsed.isWordLevel
        val original = StructuredLyrics.withTags(
            StructuredLyrics.toLrc(parsed.original, wordLevel),
            tags.ifEmpty { parsed.tags }
        )
        if (original.isBlank()) return null
        val translation = if (parsed.translated.isEmpty()) {
            item.optString("translation").ifBlank { item.optString("trans") }
        } else {
            StructuredLyrics.toLrc(parsed.translated, false)
        }
        return Texts(original, translation, wordLevel)
    }

    private val WORD_LEVEL = Regex("<\\d{1,3}:\\d{1,2}")
}
