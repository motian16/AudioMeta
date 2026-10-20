package com.audiometa.app.media.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lyrico 插件返回的结构化歌词，转换成可写入的 LRC 文本。
 *
 * original:     [[lineStartMs, lineEndMs, [[wordStartMs, wordEndMs, text], ...]], ...]
 * translated:   [[lineStartMs, lineEndMs, "text"], ...] 或 null
 * romanization: 同上
 */
object StructuredLyrics {

    data class Word(val startMs: Long, val endMs: Long, val text: String)

    data class Line(val startMs: Long, val endMs: Long, val words: List<Word>) {
        val text: String get() = words.joinToString("") { it.text }

        /** 有逐字时间轴（不是整行一个词）才算逐字歌词。 */
        val hasWordTiming: Boolean
            get() = words.size > 1 || (words.size == 1 && words[0].startMs != startMs)
    }

    data class Parsed(
        val original: List<Line>,
        val translated: List<Line>,
        val romanization: List<Line>,
        val tags: Map<String, String>
    ) {
        val hasContent: Boolean get() = original.isNotEmpty()
        val isWordLevel: Boolean get() = original.any { it.hasWordTiming }
    }

    fun parse(obj: JSONObject): Parsed? {
        val original = parseOriginal(obj.optJSONArray("original"))
        if (original.isEmpty()) return null
        val tags = mutableMapOf<String, String>()
        obj.optJSONObject("tags")?.let { t ->
            t.keys().forEach { key -> tags[key] = t.optString(key) }
        }
        return Parsed(
            original = original,
            translated = parsePlain(obj.optJSONArray("translated")),
            romanization = parsePlain(obj.optJSONArray("romanization")),
            tags = tags
        )
    }

    private fun parseOriginal(array: JSONArray?): List<Line> {
        if (array == null) return emptyList()
        val out = mutableListOf<Line>()
        for (i in 0 until array.length()) {
            val line = array.optJSONArray(i) ?: continue
            val start = line.optLong(0)
            val end = line.optLong(1)
            val words = mutableListOf<Word>()
            line.optJSONArray(2)?.let { wordArray ->
                for (j in 0 until wordArray.length()) {
                    val word = wordArray.optJSONArray(j) ?: continue
                    words += Word(
                        startMs = word.optLong(0),
                        endMs = word.optLong(1),
                        text = word.optString(2)
                    )
                }
            }
            if (words.isEmpty()) continue
            out += Line(start, if (end > start) end else start, words)
        }
        return out
    }

    private fun parsePlain(array: JSONArray?): List<Line> {
        if (array == null) return emptyList()
        val out = mutableListOf<Line>()
        for (i in 0 until array.length()) {
            val line = array.optJSONArray(i) ?: continue
            val start = line.optLong(0)
            val end = line.optLong(1)
            val text = line.optString(2)
            if (text.isBlank()) continue
            out += Line(start, if (end > start) end else start, listOf(Word(start, end, text)))
        }
        return out
    }

    /** 逐字歌词用增强 LRC：行首一个时间戳，词前再加 <mm:ss.xx>。 */
    fun toLrc(lines: List<Line>, wordLevel: Boolean): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        lines.forEach { line ->
            if (sb.isNotEmpty()) sb.append('\n')
            if (wordLevel && line.hasWordTiming) {
                sb.append('[').append(bare(line.startMs)).append(']')
                line.words.forEach { word ->
                    sb.append('<').append(bare(word.startMs)).append('>').append(word.text)
                }
            } else {
                sb.append('[').append(bare(line.startMs)).append(']').append(line.text)
            }
        }
        return sb.toString()
    }

    /** 追加 LRC 头部标签，例如 [ti:]、[ar:]、[al:]。 */
    fun withTags(lrc: String, tags: Map<String, String>): String {
        val header = buildString {
            fun put(field: String, key: String) {
                val value = tags[key].orEmpty()
                if (value.isNotBlank()) append('[').append(field).append(':').append(value).append(']').append('\n')
            }
            put("ti", "ti")
            put("ar", "ar")
            put("al", "al")
            put("by", "by")
        }
        return if (header.isBlank()) lrc else header + lrc
    }

    private fun bare(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val minutes = total / 60000
        val seconds = (total % 60000) / 1000
        val centis = (total % 1000) / 10
        return pad(minutes) + ":" + pad(seconds) + "." + pad(centis)
    }

    private fun pad(value: Long): String = if (value < 10) "0" + value else value.toString()
}
