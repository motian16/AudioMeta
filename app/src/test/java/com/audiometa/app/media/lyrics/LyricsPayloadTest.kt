package com.audiometa.app.media.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 `getLyrics` 三种返回形状的解析。
 *
 * 这一段是"每个源都搜不到歌词"的护栏：酷狗给的是 `original` 数组、
 * Apple 给的是 `rawTtml`，两者都**没有** `lyrics` 字段。
 * 宿主以前只认 `lyrics` 字符串，于是这两家的歌词被静默丢掉。
 */
class LyricsPayloadTest {

    @Test
    fun `纯文本形状（QQ 网易）`() {
        val item = JSONObject()
            .put("type", "word")
            .put("lyrics", "[00:01.00]<00:01.00>你<00:01.50>好")
            .put("translation", "[00:01.00]Hello")
        val texts = LyricsPayload.parse(item)
        assertNotNull(texts)
        assertTrue("有 <mm:ss> 标记就算逐字", texts!!.wordLevel)
        assertTrue(texts.original.contains("<00:01.00>你"))
        assertEquals("[00:01.00]Hello", texts.translation)
    }

    @Test
    fun `结构化形状（酷狗）`() {
        // 形状取自真机抓到的酷狗返回：type=structured，字段是 tags/original/translated/romanization。
        val item = JSONObject()
            .put("type", "structured")
            .put("tags", JSONObject().put("ti", "演员").put("ar", "薛之谦").put("al", "绅士"))
            .put(
                "original",
                JSONArray()
                    // 一行多个词才算逐字（整行一个词的写法按逐行处理，见下面的 TTML 用例）。
                    .put(
                        JSONArray().put(21168).put(23795).put(
                            JSONArray()
                                .put(JSONArray().put(21168).put(21563).put("简单"))
                                .put(JSONArray().put(21563).put(23795).put("点"))
                        )
                    )
                    .put(
                        JSONArray().put(24967).put(28775).put(
                            JSONArray()
                                .put(JSONArray().put(24967).put(25759).put("说话"))
                                .put(JSONArray().put(25759).put(28775).put("的方式"))
                        )
                    )
            )
            .put("translated", JSONArray().put(JSONArray().put(21168).put(23795).put("Simple")))
        val texts = LyricsPayload.parse(item)
        assertNotNull("结构化歌词必须解析出来", texts)
        assertTrue(texts!!.wordLevel)
        assertTrue("LRC 头要带上标题：" + texts.original, texts.original.contains("[ti:演员]"))
        assertTrue(texts.original.contains("[00:21.16]<00:21.16>简单<00:21.56>点"))
        assertEquals("[00:21.16]Simple", texts.translation)
    }

    @Test
    fun `TTML 形状（Apple 的 rawTtml）`() {
        val item = JSONObject()
            .put("type", "rawTtml")
            .put("tags", JSONObject().put("ti", "演员").put("ar", "薛之谦"))
            .put(
                "rawTtml",
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div begin="21.168" end="39.290">
                  <p begin="21.168" end="23.795" itunes:key="L1"><span begin="21.168" end="21.563">简单</span><span begin="21.563" end="21.905">点</span></p>
                  <p begin="1:02.353" end="1:07.406" itunes:key="L2"><span begin="1:02.353" end="1:03.109">该</span><span begin="1:03.109" end="1:03.424">配合</span></p>
                </div></body></tt>
                """.trimIndent()
            )
        val texts = LyricsPayload.parse(item)
        assertNotNull("TTML 歌词必须解析出来", texts)
        assertTrue("逐字：每个 span 一个时间", texts!!.wordLevel)
        assertTrue(texts.original.contains("[00:21.16]<00:21.16>简单<00:21.56>点"))
        // 1:02.353 这种 mm:ss.mmm 也要认。
        assertTrue("分秒格式要换算成毫秒：" + texts.original, texts.original.contains("[01:02.35]"))
    }

    @Test
    fun `三种形状都没有时返回 null`() {
        assertNull(LyricsPayload.parse(JSONObject().put("type", "structured")))
        assertNull(LyricsPayload.parse(JSONObject().put("rawTtml", "")))
        assertNull(LyricsPayload.parse(JSONObject().put("lyrics", "")))
    }

    @Test
    fun `TTML 时间戳支持秒 分秒 时分秒`() {
        assertEquals(21_168L, TtmlLyrics.timeMs("21.168"))
        assertEquals(62_353L, TtmlLyrics.timeMs("1:02.353"))
        assertEquals(3_723_456L, TtmlLyrics.timeMs("1:02:03.456"))
        assertEquals(0L, TtmlLyrics.timeMs(null))
        assertEquals(0L, TtmlLyrics.timeMs("乱写"))
    }

    @Test
    fun `TTML 解析不出来时返回 null 而不是空歌词`() {
        assertNull(TtmlLyrics.parse("<tt><body></body></tt>"))
        assertNull(TtmlLyrics.parse(""))
        val parsed = TtmlLyrics.parse("<tt><body><p begin=\"1.0\" end=\"2.0\">整行文字</p></body></tt>")
        assertNotNull("没有 span 的整行写法也要认", parsed)
        assertEquals("整行文字", parsed!!.original.first().text)
        assertFalse("整行不算逐字", parsed.isWordLevel)
    }
}
