package com.audiometa.app.media.lyrics

/**
 * TTML 歌词（Apple Music 插件返回的 `rawTtml`）→ [StructuredLyrics.Parsed]。
 *
 * Apple 的插件**不给 LRC 文本**，给的是 iTunes 风格的 TTML：
 *
 * ```xml
 * <tt ...><body><div begin="21.168" end="39.290">
 *   <p begin="21.168" end="23.795" itunes:key="L1">
 *     <span begin="21.168" end="21.563">简单</span><span begin="21.563" end="21.905">点</span>
 *   </p>
 * </div></body></tt>
 * ```
 *
 * 行是 `<p>`、字是 `<span>`，时间可以写成 `21.168`（秒）、`1:02.353`、
 * 甚至 `1:02:03.456`。**以前宿主完全不认这种格式**（只认 LRC 字符串和
 * `original` 数组），所以 Apple 的歌词在"搜索歌词"和刮削里都是静默丢掉 ——
 * 用户看到的就是"四个源都搜不到歌词"。
 */
object TtmlLyrics {

    private val LINE = Regex(
        "<p\\b([^>]*)>(.*?)</p>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val WORD = Regex(
        "<span\\b([^>]*)>(.*?)</span>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val TAG = Regex("<[^>]*>")
    private val BEGIN = Regex("\\bbegin\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val END = Regex("\\bend\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /**
     * 解析一份 TTML；一行都没解析出来时返回 null（宁可当没有，也别给一份空歌词）。
     */
    fun parse(ttml: String): StructuredLyrics.Parsed? {
        if (ttml.isBlank()) return null
        val lines = mutableListOf<StructuredLyrics.Line>()
        for (match in LINE.findAll(ttml)) {
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val start = timeMs(BEGIN.find(attrs)?.groupValues?.get(1))
            val end = timeMs(END.find(attrs)?.groupValues?.get(1))
            val words = WORD.findAll(body).mapNotNull { word ->
                val text = plain(word.groupValues[2])
                if (text.isEmpty()) return@mapNotNull null
                val wordAttrs = word.groupValues[1]
                StructuredLyrics.Word(
                    startMs = timeMs(BEGIN.find(wordAttrs)?.groupValues?.get(1)),
                    endMs = timeMs(END.find(wordAttrs)?.groupValues?.get(1)),
                    text = text
                )
            }.toList()
            val safeEnd = if (end > start) end else start
            if (words.isNotEmpty()) {
                lines += StructuredLyrics.Line(start, safeEnd, words)
            } else {
                // 整行一个词的写法（没有 <span>）：正文放在 p 里。
                val text = plain(body)
                if (text.isNotEmpty()) {
                    lines += StructuredLyrics.Line(
                        start, safeEnd, listOf(StructuredLyrics.Word(start, safeEnd, text))
                    )
                }
            }
        }
        if (lines.isEmpty()) return null
        return StructuredLyrics.Parsed(
            original = lines,
            translated = emptyList(),
            romanization = emptyList(),
            tags = emptyMap()
        )
    }

    /** `21.168` / `1:02.353` / `1:02:03.456` 一律换成毫秒。 */
    fun timeMs(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        if (!text.contains(':')) {
            val seconds = text.toDoubleOrNull() ?: return 0L
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }
        var seconds = 0.0
        for (part in text.split(':')) {
            seconds = seconds * 60 + (part.trim().toDoubleOrNull() ?: 0.0)
        }
        return (seconds * 1000).toLong().coerceAtLeast(0L)
    }

    /** 去掉标签、还原实体，得到一行纯文本。 */
    private fun plain(raw: String): String = TAG.replace(raw, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .trim()
}
