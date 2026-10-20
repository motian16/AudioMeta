package com.audiometa.app.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词解析：详情页的底部歌词窗口直接用它排版。
 *
 * 写进文件的 LRC 有两种时间轴（逐行 / 逐字），翻译按约定放在
 * `[translation]` 标记之后。
 */
class LyricsParserTest {

    @Test
    fun `逐行歌词按时间戳拆行`() {
        val raw = """
            [ti:七里香]
            [ar:周杰伦]
            [00:00.00]窗外的麻雀 在电线杆上多嘴
            [00:05.50]你说这一句 很有夏天的感觉
        """.trimIndent()
        val parsed = LyricsParser.parse(raw)
        assertEquals(2, parsed.original.size)
        assertFalse(parsed.isWordLevel)
        assertEquals("窗外的麻雀 在电线杆上多嘴", parsed.original[0].text)
        assertEquals(0L, parsed.original[0].startMs)
        assertEquals(5_500L, parsed.original[1].startMs)
        // 头部标签单独收起来，不会被当成歌词行。
        assertEquals("七里香", parsed.tags["ti"])
        assertEquals("周杰伦", parsed.tags["ar"])
    }

    @Test
    fun `逐字歌词用增强 LRC 解析出每个字`() {
        val raw = "[00:12.00]<00:12.00>窗外的<00:12.50>麻雀<00:13.00>在电线杆上多嘴"
        val parsed = LyricsParser.parse(raw)
        assertEquals(1, parsed.original.size)
        assertTrue(parsed.isWordLevel)
        val line = parsed.original[0]
        assertEquals(3, line.words.size)
        assertEquals("窗外的", line.words[0].text)
        assertEquals("麻雀", line.words[1].text)
        assertEquals(12_000L, line.words[0].startMs)
        assertEquals(12_500L, line.words[1].startMs)
        assertEquals("窗外的麻雀在电线杆上多嘴", line.text)
    }

    @Test
    fun `翻译段按 translation 标记拆出来`() {
        val raw = """
            [00:00.00]君の名前を呼んでいる
            [00:05.00]どこまでも続く空の下で
            [translation]
            [00:00.00]呼喊着你的名字
            [00:05.00]在无尽的天空之下
        """.trimIndent()
        val parsed = LyricsParser.parse(raw)
        assertEquals(2, parsed.original.size)
        assertEquals(2, parsed.translation.size)
        assertTrue(parsed.hasTranslation)
        assertEquals("呼喊着你的名字", parsed.translation[0].text)
        // 标记本身不能混进歌词。
        assertFalse(parsed.original.any { it.text.contains("translation") })
    }

    @Test
    fun `逐字加翻译的组合`() {
        val raw = """
            [00:00.00]<00:00.00>Hello<00:00.50> world
            [translation]
            [00:00.00]你好 世界
        """.trimIndent()
        val parsed = LyricsParser.parse(raw)
        assertTrue(parsed.isWordLevel)
        assertTrue(parsed.hasTranslation)
        assertEquals(2, parsed.original[0].words.size)
        assertEquals("你好 世界", parsed.translation[0].text)
    }

    @Test
    fun `没有时间戳的纯文本也能显示`() {
        val parsed = LyricsParser.parse("第一行\n第二行")
        assertEquals(2, parsed.original.size)
        assertEquals("第一行", parsed.original[0].text)
    }

    @Test
    fun `空歌词不炸`() {
        val parsed = LyricsParser.parse("")
        assertFalse(parsed.hasOriginal)
        assertFalse(parsed.hasTranslation)
    }

    @Test
    fun `只有头部标签没有正文时算没有歌词`() {
        val parsed = LyricsParser.parse("[ti:纯音乐]\n[ar:某某]")
        assertFalse(parsed.hasOriginal)
    }

    @Test
    fun `时间戳支持三位毫秒和点号分隔`() {
        val parsed = LyricsParser.parse("[01:02.345]hello\n[01:03.05]world")
        assertEquals(62_345L, parsed.original[0].startMs)
        assertEquals(63_050L, parsed.original[1].startMs)
    }
}
