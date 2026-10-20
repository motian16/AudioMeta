package com.audiometa.app.domain.usecase

/**
 * 歌词候选的默认偏好。
 *
 * 用户要求：**"默认是逐字+翻译的优先"** —— 同一首歌的候选里，逐字带翻译的最好
 * （能跟着唱，还能看译文），其次是逐字，再次是逐行带翻译，最后是逐行。
 */
object LyricsRanking {

    /** 类型偏好，越小越优先：逐字+翻译 → 逐字 → 逐行+翻译 → 逐行。 */
    fun preference(wordLevel: Boolean, hasTranslation: Boolean): Int = when {
        wordLevel && hasTranslation -> 0
        wordLevel -> 1
        hasTranslation -> 2
        else -> 3
    }

    /**
     * 匹配分的粗档（越大越靠前）。
     *
     * 排序里**先比匹配档，再比类型偏好**：不同源给出的分数常常只差零点零几，
     * 如果让类型压过分数，一个"匹配度很低的错歌"只要带逐字翻译就会排到正确那首前面 ——
     * 那比"少一行译文"严重得多。分档之后，同一首歌的候选之间才按类型偏好排。
     */
    fun scoreTier(score: Double): Int = when {
        score >= 0.9 -> 3
        score >= 0.75 -> 2
        score >= 0.5 -> 1
        else -> 0
    }
}
