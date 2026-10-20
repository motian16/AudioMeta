package com.audiometa.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词候选的默认偏好。
 *
 * 用户点名："默认是逐字+翻译的优先"。这里把两条规矩钉住：
 * 1. 类型顺序：逐字+翻译 → 逐字 → 逐行+翻译 → 逐行；
 * 2. **匹配度先分档**：不同源的分数只差零点零几时按类型偏好排，
 *    但"匹配度很低的错歌"不能因为带逐字翻译就压过正确那首。
 */
class LyricsRankingTest {

    @Test
    fun `逐字带翻译排最前 逐行不带翻译排最后`() {
        val order = listOf(
            LyricsRanking.preference(wordLevel = true, hasTranslation = true),
            LyricsRanking.preference(wordLevel = true, hasTranslation = false),
            LyricsRanking.preference(wordLevel = false, hasTranslation = true),
            LyricsRanking.preference(wordLevel = false, hasTranslation = false)
        )
        assertEquals("必须严格递增（越小越优先）", order.sorted(), order)
        assertEquals(4, order.distinct().size)
        assertEquals(0, order.first())
    }

    @Test
    fun `匹配度分档：同一首歌才算同档`() {
        // 同一首歌的不同源（0.9 以上）算同一档，档内才比类型偏好。
        assertEquals(LyricsRanking.scoreTier(0.97), LyricsRanking.scoreTier(0.91))
        // 明显不是同一首（分数低）掉档，排不到正确那首前面。
        assertTrue(LyricsRanking.scoreTier(0.97) > LyricsRanking.scoreTier(0.62))
        assertTrue(LyricsRanking.scoreTier(0.8) > LyricsRanking.scoreTier(0.4))
        assertTrue(LyricsRanking.scoreTier(0.4) == LyricsRanking.scoreTier(0.0))
    }
}
