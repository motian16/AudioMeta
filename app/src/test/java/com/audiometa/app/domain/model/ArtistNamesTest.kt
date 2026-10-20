package com.audiometa.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合作写法的艺术家名拆分与归属判断。
 *
 * 回归用：艺术家图是"一位艺术家一张图，写进这位艺术家的所有歌"，
 * 而很多歌的 artist 字段写着好几位艺术家（`A/B`、`A feat. B`、`A & B`、`A、B`…）。
 * 以前这里比的是**整串相等**，这些合作曲一律被漏掉 —— 用户在艺术家页看得到那首歌，
 * 却只有一部分歌带上了头像。
 */
class ArtistNamesTest {

    @Test
    fun `常见的合作写法都能拆开`() {
        assertEquals(listOf("周杰伦", "费玉清"), ArtistNames.of("周杰伦/费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistNames.of("周杰伦、费玉清"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A feat. B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A ft. B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A & B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A; B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A, B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A with B"))
        // 全角写法（中文输入法下很常见）。
        assertEquals(listOf("A", "B"), ArtistNames.of("A／B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A＆B"))
        assertEquals(listOf("A", "B"), ArtistNames.of("A；B"))
    }

    @Test
    fun `单个艺术家名原样返回`() {
        assertEquals(listOf("周杰伦"), ArtistNames.of("周杰伦"))
        assertEquals(listOf("Jay Chou"), ArtistNames.of("  Jay Chou  "))
        // 名字里有 feat/ft 这种字母组合的不能被误切。
        assertEquals(listOf("Feather"), ArtistNames.of("Feather"))
        assertEquals(listOf("Within Temptation"), ArtistNames.of("Within Temptation"))
        // 空字段不编名字出来，交给界面显示「未知艺术家」。
        assertEquals(emptyList<String>(), ArtistNames.of("   "))
    }

    @Test
    fun `合作曲算在这几位艺术家名下`() {
        assertTrue(ArtistNames.matches("周杰伦/费玉清", "周杰伦"))
        assertTrue(ArtistNames.matches("周杰伦/费玉清", "费玉清"))
        assertTrue(ArtistNames.matches("A feat. B", "B"))
        assertTrue(ArtistNames.matches("A & B", "A"))
        assertTrue(ArtistNames.matches("A、B", "B"))
        assertTrue(ArtistNames.matches("A; B", "A"))
    }

    @Test
    fun `大小写和标点差异不算差别`() {
        assertTrue(ArtistNames.matches("jay chou", "Jay Chou"))
        assertTrue(ArtistNames.matches("Jay Chou", "jay chou"))
        // 归一化会去掉结尾的点和多余空格。
        assertTrue(ArtistNames.matches("周杰伦. ", "周杰伦"))
    }

    @Test
    fun `组合名整体相同也算`() {
        // "A & B" 本身就是一个组合名时，拆开谁都对不上，整串必须能比中。
        assertTrue(ArtistNames.matches("Simon & Garfunkel", "Simon & Garfunkel"))
        // 但 "simon and garfunkel" 是另一种写法，归属判断只认相等，不硬凑。
        assertFalse(ArtistNames.matches("Simon & Garfunkel", "simon and garfunkel"))
    }

    @Test
    fun `不能把不相关的艺术家算进来`() {
        // 只比相等：包含判断会把 Jay 算成 Jay-Z 的歌，那张头像就写错文件了。
        assertFalse(ArtistNames.matches("Jay-Z", "Jay"))
        assertFalse(ArtistNames.matches("ABBA", "A"))
        assertFalse(ArtistNames.matches("周杰伦", "周杰"))
        // 目标名是空的时不能"谁都属于"。
        assertFalse(ArtistNames.matches("周杰伦", "   "))
    }
}
