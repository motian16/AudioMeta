package com.audiometa.app.media.lyrics

/**
 * 从歌词正文判断需不需要翻译。
 *
 * 判定标准很朴素，但足够稳：看歌词里有没有表音文字（假名、谚文、拉丁字母）。
 * - 全中文（汉字为主，含少量标点/数字）→ 中文歌，本来就不需要翻译；
 * - 出现平假名/片假名 → 日文歌；出现韩文 → 韩文歌；拉丁字母占比不低 → 英文歌。
 *
 * 这里只看"要不要给用户一个【翻译】缺口"，不参与任何写入逻辑，
 * 所以宁可保守：拿不准就当作需要翻译。
 */
object LyricsLanguage {

    /** 日文假名。 */
    private val KANA = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")

    /** 韩文谚文。 */
    private val HANGUL = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF]")

    private val LATIN = Regex("[A-Za-z]")

    private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    /**
     * LRC 时间戳和头部标签。
     * 逐字歌词还会带 <mm:ss.xx>，一起清掉，避免把数字和字母算进正文。
     */
    private val LRC_NOISE = Regex("\\[[^\\]]*]|<[^>]*>")

    /** 去掉时间戳/标签后的正文。 */
    fun plainText(lyrics: String): String =
        lyrics.replace(LRC_NOISE, " ").replace(Regex("\\s+"), " ").trim()

    /** 这段歌词是不是"本来就无需翻译"（中文）。 */
    fun needsTranslation(lyrics: String): Boolean {
        val text = plainText(lyrics)
        if (text.isBlank()) return false
        if (KANA.containsMatchIn(text)) return true
        if (HANGUL.containsMatchIn(text)) return true
        val han = HAN.findAll(text).count()
        val latin = LATIN.findAll(text).count()
        if (latin == 0) return false
        // 汉字占压倒性多数时，零星的英文（feat. / 语气词）不构成"需要翻译"。
        if (han >= 8 && latin * 4 < han) return false
        return latin >= 4
    }

    /** 中文歌词的标签样式用这个：不需要翻译。 */
    fun isChineseOnly(lyrics: String): Boolean {
        val text = plainText(lyrics)
        if (text.isBlank()) return false
        if (KANA.containsMatchIn(text) || HANGUL.containsMatchIn(text)) return false
        return !needsTranslation(lyrics)
    }
}
