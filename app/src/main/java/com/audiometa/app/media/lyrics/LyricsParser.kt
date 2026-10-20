package com.audiometa.app.media.lyrics

/**
 * 把写进文件的 LRC 文本解析回可展示的行。
 *
 * 支持两种时间轴：
 * - 逐行：`[mm:ss.xx]歌词`
 * - 逐字：`[mm:ss.xx]<mm:ss.xx>字<mm:ss.xx>字`
 *
 * 翻译歌词按约定放在 `[translation]` 标记之后，没有标记就整段当作原文。
 */
object LyricsParser {

    data class Word(val startMs: Long, val text: String)

    data class Line(
        val startMs: Long,
        val words: List<Word>,
        /** 这一行是否有逐字时间轴。 */
        val wordLevel: Boolean
    ) {
        val text: String get() = words.joinToString("") { it.text }
    }

    data class Parsed(
        val original: List<Line>,
        val translation: List<Line>,
        val tags: Map<String, String>
    ) {
        val hasOriginal: Boolean get() = original.isNotEmpty()
        val hasTranslation: Boolean get() = translation.isNotEmpty()
        val isWordLevel: Boolean get() = original.any { it.wordLevel }
    }

    private val tagLine = Regex("^\\[([a-zA-Z_]+):(.*)]$")
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")

    private const val TRANSLATION_MARKER = "[translation]"

    fun parse(raw: String): Parsed {
        if (raw.isBlank()) return Parsed(emptyList(), emptyList(), emptyMap())

        val translationIndex = raw.indexOf(TRANSLATION_MARKER, ignoreCase = true)
        val primary = if (translationIndex >= 0) raw.substring(0, translationIndex) else raw
        val translated = if (translationIndex >= 0) {
            raw.substring(translationIndex + TRANSLATION_MARKER.length)
        } else {
            ""
        }

        val tags = mutableMapOf<String, String>()
        val original = parseLines(primary, tags)
        val translation = parseLines(translated, null)
        return Parsed(original, translation, tags)
    }

    private fun parseLines(raw: String, tags: MutableMap<String, String>?): List<Line> {
        val out = mutableListOf<Line>()
        raw.split('\n').forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            // 头部标签：[ti:]、[ar:]、[al:]、[by:] 之类。
            tagLine.matchEntire(line)?.let { match ->
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2].trim()
                if (key !in setOf("ti", "ar", "al", "by", "offset") || value.isEmpty()) {
                    // 不是时间戳的行，当成普通标签丢掉。
                }
                if (value.isNotEmpty()) tags?.put(key, value)
                return@forEach
            }
            val lineTimes = timeTag.findAll(line).toList()
            if (lineTimes.isEmpty()) {
                // 没有时间戳的纯文本行也保留，按顺序排列即可。
                if (line.isNotBlank()) {
                    out += Line(0L, listOf(Word(0L, line)), wordLevel = false)
                }
                return@forEach
            }
            val start = timeOf(lineTimes.first().groupValues)
            val body = line.substring(lineTimes.last().range.last + 1)
            val wordTimes = wordTag.findAll(body).toList()
            if (wordTimes.size < 2) {
                // 整行一个时间戳：逐行歌词。
                val text = wordTag.replace(body, "").trim()
                out += Line(start, listOf(Word(start, text)), wordLevel = false)
            } else {
                val words = mutableListOf<Word>()
                wordTimes.forEachIndexed { index, match ->
                    val wordStart = timeOf(match.groupValues)
                    val from = match.range.last + 1
                    val to = wordTimes.getOrNull(index + 1)?.range?.first ?: body.length
                    val text = body.substring(from.coerceAtMost(body.length), to.coerceAtMost(body.length))
                    words += Word(wordStart, text)
                }
                out += Line(start, words, wordLevel = true)
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** mm:ss(.xx) → 毫秒。 */
    private fun timeOf(groups: List<String>): Long {
        val minutes = groups.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = groups.getOrNull(2)?.toLongOrNull() ?: 0L
        val fractionRaw = groups.getOrNull(3).orEmpty()
        val fraction = when (fractionRaw.length) {
            0 -> 0L
            1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
            2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
            else -> fractionRaw.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1000L + fraction
    }
}
