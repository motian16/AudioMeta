package com.audiometa.app.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLyricsTest {

    private val lyrics = RemoteLyrics(
        key = "k",
        lines = listOf(
            RemoteLyricLine(0, 1_000, "第一行", ""),
            RemoteLyricLine(1_000, 2_000, "第二行", "translation"),
            RemoteLyricLine(2_000, 3_000, "第三行", "")
        )
    )

    @Test
    fun `取当前位置的三行与翻译`() {
        val trio = lyrics.trioAt(1_200)
        assertEquals("第一行", trio.previous)
        assertEquals("第二行", trio.current)
        assertEquals("第三行", trio.next)
        assertEquals("translation", trio.translation)
    }

    @Test
    fun `位置在开头之前时落在第一行`() {
        val trio = lyrics.trioAt(0)
        assertEquals("", trio.previous)
        assertEquals("第一行", trio.current)
        assertEquals("第二行", trio.next)
    }

    @Test
    fun `空歌词返回空三行`() {
        val trio = RemoteLyrics("k", emptyList()).trioAt(5_000)
        assertEquals("", trio.current)
        assertEquals("", trio.previous)
        assertEquals("", trio.next)
    }
}
