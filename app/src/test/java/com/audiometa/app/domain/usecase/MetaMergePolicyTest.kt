package com.audiometa.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合刮削的字段优先表。
 *
 * 这张表以前**一次都没生效过**：它写在 ScrapeService 里，却拿插件 id
 * （`com.applemusic.source`）去比表里的短名（`apple`），`indexOf` 永远 -1，
 * 于是所有字段都退化成"按用户设置的源优先级"—— 用户看到的正是"流派没有优先用 Apple"。
 *
 * 这里把短名归一化和排序规则钉住：规则文件、插件 id 以后怎么改，这几条都不能坏。
 */
class MetaMergePolicyTest {

    @Test
    fun `插件 id 归一化成表里的短名`() {
        assertEquals("apple", MetaMergePolicy.shortName("com.applemusic.source"))
        assertEquals("qq", MetaMergePolicy.shortName("com.qqmusic.source"))
        assertEquals("netease", MetaMergePolicy.shortName("com.neteasecloudmusic.source"))
        assertEquals("kugou", MetaMergePolicy.shortName("com.kugou.source"))
        assertEquals("musicbrainz", MetaMergePolicy.shortName("com.musicbrainz.source"))
        // 认不出来的插件不能抛，退回 id 最后一段。
        assertEquals("source", MetaMergePolicy.shortName("com.example.source"))
    }

    @Test
    fun `流派优先 Apple`() {
        // 用户点名："apple music的流派是最全的，优先am"。
        val apple = MetaMergePolicy.orderOf("genre", "com.applemusic.source", globalRank = 3)
        val qq = MetaMergePolicy.orderOf("genre", "com.qqmusic.source", globalRank = 0)
        assertTrue("Apple 必须排在全局优先级更高的 QQ 前面", apple < qq)
    }

    @Test
    fun `专辑名与音轨号也优先 Apple 艺术家名网易排最后`() {
        // 实测结论：Apple 的专辑名、专辑内序号最准；网易会把艺术家名返回成英文。
        assertTrue(
            MetaMergePolicy.orderOf("album", "com.applemusic.source", 2) <
                MetaMergePolicy.orderOf("album", "com.qqmusic.source", 0)
        )
        assertTrue(
            MetaMergePolicy.orderOf("trackNo", "com.applemusic.source", 2) <
                MetaMergePolicy.orderOf("trackNo", "com.qqmusic.source", 0)
        )
        assertTrue(
            MetaMergePolicy.orderOf("artist", "com.neteasecloudmusic.source", 0) >
                MetaMergePolicy.orderOf("artist", "com.qqmusic.source", 3)
        )
    }

    @Test
    fun `没进专属表的源按用户设置的源优先级排`() {
        // 表里没有的字段：谁有值就用谁的，次序完全听用户的源优先级。
        assertEquals(
            MetaMergePolicy.orderOf("language", "com.qqmusic.source", 0),
            MetaMergePolicy.orderOf("language", "com.applemusic.source", 0)
        )
        assertTrue(
            MetaMergePolicy.orderOf("language", "com.qqmusic.source", 0) <
                MetaMergePolicy.orderOf("language", "com.applemusic.source", 1)
        )
    }

    @Test
    fun `预览要对比的字段里包含用户点名的那几项`() {
        val labels = MetaMergePolicy.PREVIEW_FIELDS.map { it.second }
        for (want in listOf("流派", "音轨", "碟号", "日期", "语言", "版权", "注释", "作曲")) {
            assertTrue("组合刮削弹窗要显示「$want」：" + labels, want in labels)
        }
    }
}
