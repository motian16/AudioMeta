package com.audiometa.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索词推导与匹配打分。
 * 规则来自需求：xx - xx 按「xx xx」搜；其它按整个名字；有标签用「标题 歌手」；
 * 批量取分数最高，单首按分数排序供选择。
 */
class TrackMatcherTest {

    @Test
    fun `横杠分隔的文件名按空格拼接`() {
        assertEquals("4u Capo Productions", TrackMatcher.queryFromFileName("4u - Capo Productions.flac"))
        assertEquals("4u Capo Productions", TrackMatcher.queryFromFileName("4u-Capo Productions.flac"))
        assertEquals("4u Capo Productions", TrackMatcher.queryFromFileName("4u -Capo Productions.flac"))
        assertEquals("巴赫旧约 汪苏泷", TrackMatcher.queryFromFileName("巴赫旧约 - 汪苏泷.flac"))
        assertEquals("巴赫旧约 汪苏泷", TrackMatcher.queryFromFileName("巴赫旧约-汪苏泷.mp3"))
    }

    @Test
    fun `没有分隔符时整个名字作为搜索词`() {
        assertEquals("巴赫旧约", TrackMatcher.queryFromFileName("巴赫旧约.flac"))
    }

    @Test
    fun `开头的音轨号会被去掉`() {
        assertEquals("巴赫旧约", TrackMatcher.queryFromFileName("01. 巴赫旧约.flac"))
        assertEquals("巴赫旧约", TrackMatcher.queryFromFileName("03_巴赫旧约.flac"))
        assertEquals("巴赫旧约", TrackMatcher.queryFromFileName("12 巴赫旧约.flac"))
    }

    @Test
    fun `有标签时用标题加歌手`() {
        assertEquals("巴赫旧约 汪苏泷", TrackMatcher.buildQuery("巴赫旧约", "汪苏泷", "无关文件名.flac"))
    }

    @Test
    fun `没有标签时退回文件名`() {
        assertEquals("巴赫旧约 汪苏泷", TrackMatcher.buildQuery("", "", "巴赫旧约 - 汪苏泷.flac"))
    }

    @Test
    fun `只有歌手时用歌手`() {
        assertEquals("汪苏泷", TrackMatcher.buildQuery("", "汪苏泷", "随便.flac"))
    }

    @Test
    fun `批量刮削取匹配度最高的候选`() {
        val want = TrackMatcher.Want(
            title = "巴赫旧约",
            artist = "汪苏泷",
            album = "慢慢懂",
            fileName = "巴赫旧约 - 汪苏泷.flac",
            durationMs = 240_000L
        )
        val candidates = listOf(
            TrackMatcher.Candidate("晴天", "周杰伦", "叶惠美", 269_000L),
            TrackMatcher.Candidate("巴赫旧约", "汪苏泷", "慢慢懂", 241_000L),
            TrackMatcher.Candidate("巴赫旧约", "别的歌手", "别的专辑", 200_000L)
        )
        assertEquals(1, TrackMatcher.bestIndex(candidates, want))
    }

    @Test
    fun `单首刮削的结果按分数从高到低`() {
        val want = TrackMatcher.Want(title = "巴赫旧约", artist = "汪苏泷", fileName = "巴赫旧约 - 汪苏泷.flac")
        val ranked = TrackMatcher.rank(
            listOf(
                TrackMatcher.Candidate("晴天", "周杰伦"),
                TrackMatcher.Candidate("巴赫旧约", "汪苏泷")
            ),
            want
        )
        assertEquals(1, ranked.first().index)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `完全匹配的候选分数最高且接近满分`() {
        val want = TrackMatcher.Want(
            title = "巴赫旧约",
            artist = "汪苏泷",
            album = "慢慢懂",
            fileName = "巴赫旧约 - 汪苏泷.flac"
        )
        val exact = TrackMatcher.score(TrackMatcher.Candidate("巴赫旧约", "汪苏泷", "慢慢懂"), want)
        val other = TrackMatcher.score(TrackMatcher.Candidate("晴天", "周杰伦", "叶惠美"), want)
        assertTrue("完全匹配应高于不匹配：$exact vs $other", exact > other)
        assertTrue("完全匹配应接近满分，实际 $exact", exact > 0.9)
    }

    @Test
    fun `版本噪声不会抬高分数`() {
        val want = TrackMatcher.Want(title = "巴赫旧约", artist = "汪苏泷", fileName = "巴赫旧约 - 汪苏泷.flac")
        val clean = TrackMatcher.score(TrackMatcher.Candidate("巴赫旧约", "汪苏泷"), want)
        val noisy = TrackMatcher.score(TrackMatcher.Candidate("巴赫旧约 (Official Video)", "汪苏泷"), want)
        assertTrue("带版本噪声不该比干净的更高：$noisy vs $clean", noisy <= clean + 0.001)
    }

    @Test
    fun `空候选列表返回负一`() {
        val want = TrackMatcher.Want(title = "x", artist = "y", fileName = "x - y.flac")
        assertEquals(-1, TrackMatcher.bestIndex(emptyList(), want))
    }
}
