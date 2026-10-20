package com.audiometa.app.media.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译标签的判定。
 *
 * 规则：全中文歌词无需翻译；出现假名/谚文/足够多的拉丁字母才算需要翻译。
 */
class LyricsLanguageTest {

    @Test
    fun `中文歌词不需要翻译`() {
        val lyrics = """
            [ti:七里香]
            [00:00.00]窗外的麻雀 在电线杆上多嘴
            [00:05.00]你说这一句 很有夏天的感觉
            [00:10.00]手中的铅笔 在纸上来来回回
        """.trimIndent()
        assertFalse(LyricsLanguage.needsTranslation(lyrics))
        assertTrue(LyricsLanguage.isChineseOnly(lyrics))
    }

    @Test
    fun `逐字歌词的时间标签不会影响判定`() {
        val lyrics = "[00:00.00]<00:00.00>窗外的<00:00.50>麻雀<00:01.00>在电线杆上多嘴"
        assertFalse(LyricsLanguage.needsTranslation(lyrics))
    }

    @Test
    fun `日文歌词需要翻译`() {
        val lyrics = """
            [00:00.00]君の名前を呼んでいる
            [00:05.00]どこまでも続く空の下で
        """.trimIndent()
        assertTrue(LyricsLanguage.needsTranslation(lyrics))
        assertFalse(LyricsLanguage.isChineseOnly(lyrics))
    }

    @Test
    fun `英文歌词需要翻译`() {
        val lyrics = """
            [00:00.00]Yesterday, all my troubles seemed so far away
            [00:05.00]Now it looks as though they're here to stay
        """.trimIndent()
        assertTrue(LyricsLanguage.needsTranslation(lyrics))
    }

    @Test
    fun `韩文歌词需要翻译`() {
        assertTrue(LyricsLanguage.needsTranslation("[00:00.00]사랑해 그대여"))
    }

    @Test
    fun `中文里零星英文不算需要翻译`() {
        val lyrics = """
            [00:00.00]窗外的麻雀 在电线杆上多嘴
            [00:05.00]你说这一句 很有夏天的感觉
            [00:10.00]雨下整夜 我的爱溢出就像雨水
            [00:15.00]院子落叶 跟我的思念厚厚一叠
            [00:20.00]几句是非 也无法将我的热情冷却
            [00:25.00]你出现在我诗的每一页
            [00:30.00]feat. Jay
        """.trimIndent()
        assertFalse(LyricsLanguage.needsTranslation(lyrics))
    }

    @Test
    fun `空歌词不判定为需要翻译`() {
        assertFalse(LyricsLanguage.needsTranslation(""))
        assertFalse(LyricsLanguage.isChineseOnly(""))
    }

    @Test
    fun `纯音乐没有歌词不算需要翻译`() {
        assertFalse(LyricsLanguage.needsTranslation("[00:00.00]"))
    }
}
