package com.audiometa.app.domain.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红线：刮削不许改标题 / 艺术家 / 专辑。
 *
 * 用户报过一个数据破坏级的问题：给一位艺术家刮了图之后，这位艺术家名下
 * **所有歌曲的内嵌标题都变成了同一首歌**。三层原因叠在一起：
 * 写入侧会写这三个字段、详情页的刮削入口是覆盖策略、艺术家图会扩展到该艺术家的全部曲目。
 *
 * 这几条测试钉住"哪些字段受保护"，以及"谁带的头"是能被看见的。
 */
class ProtectedFieldsTest {

    @Test
    fun `标题 艺术家 专辑 受保护`() {
        assertTrue(ProtectedFields.isProtected("TITLE"))
        assertTrue(ProtectedFields.isProtected("ARTIST"))
        assertTrue(ProtectedFields.isProtected("ALBUM"))
        // 大小写和空格不该让红线失效。
        assertTrue(ProtectedFields.isProtected(" title "))
        assertTrue(ProtectedFields.isProtected("album"))
    }

    @Test
    fun `其它字段不受影响`() {
        // 这些正是刮削该写的：流派、音轨、碟号、日期、语言、版权、注释、作曲。
        for (key in listOf("GENRE", "TRACK", "DISC_NO", "YEAR", "LANGUAGE", "COPYRIGHT", "COMMENT", "COMPOSER")) {
            assertFalse(key + " 不该被红线挡住", ProtectedFields.isProtected(key))
        }
        // 专辑艺术家不是"专辑"：它仍然可以由元信息刮削写入。
        assertFalse(ProtectedFields.isProtected("ALBUM_ARTIST"))
    }

    @Test
    fun `带值的受保护字段能被点出来`() {
        val meta = TrackMeta(title = "演员", artist = "薛之谦", album = "绅士", genre = "流行")
        assertEquals(listOf("标题", "艺术家", "专辑"), ProtectedFields.protectedOf(meta))
        // 只有流派时什么都不该报（刮削写的是它）。
        assertEquals(emptyList<String>(), ProtectedFields.protectedOf(TrackMeta(genre = "流行")))
    }

    @Test
    fun `手动编辑里混进受保护字段时能挑出来`() {
        // 手动编辑（详情页元信息）**可以**改标题，这条路走的是 RawTagWriter；
        // 这个函数只是给界面提示用的，不参与拦截。
        val tags = mapOf("TITLE" to "新标题", "genre" to "摇滚")
        assertEquals(listOf("标题"), ProtectedFields.protectedIn(tags))
    }

    @Test
    fun `写入侧不得再写标题 艺术家 专辑（源码红线）`() {
        /*
         * 这条是"防回归"的：真正的风险不是保护清单写错，而是以后有人
         * 又在 applyMeta 里补回那三行 setText。单测跑在模块目录下，
         * 读源码文件即可；文件读不到就跳过，绝不让环境差异把测试弄红。
         */
        val source = readSource("src/main/java/com/audiometa/app/media/tag/TagWriter.kt") ?: return
        val applyMeta = source.substringAfter("private fun applyMeta", "")
            .substringBefore("private fun setCustomOrField", "")
        assertTrue("applyMeta 没找到，检查是不是改过结构", applyMeta.isNotBlank())
        // 用带逗号的完整调用形态匹配：`FieldKey.ALBUM` 是 `FieldKey.ALBUM_ARTIST`
        // 的前缀，只比字段名会把合法的专辑艺术家写入误判成违规。
        for (key in listOf("TITLE", "ARTIST", "ALBUM")) {
            assertFalse("刮削不许写 $key", applyMeta.contains("setText(tag, FieldKey.$key,"))
        }
        // 专辑艺术家仍然允许刮削写入（它不是"专辑"）。
        assertTrue(
            "ALBUM_ARTIST 应该照旧写入",
            applyMeta.contains("setText(tag, FieldKey.ALBUM_ARTIST,")
        )
    }

    @Test
    fun `组合刮削交出去的合并结果不带标题 艺术家 专辑（源码红线）`() {
        val source = readSource("src/main/java/com/audiometa/app/domain/usecase/ScrapePreview.kt") ?: return
        // 合并结果的生成点现在在 mergeMeta()：从函数头截到下一个成员之前。
        val merged = source.substringAfter("private fun mergeMeta", "")
            .substringBefore("\n    private fun ", "")
        assertTrue("合并结果没找到，检查是不是改过结构", merged.isNotBlank())
        for (key in listOf("put(\"title\"", "put(\"artist\"", "put(\"album\"")) {
            assertFalse("合并结果不许带 $key", merged.contains(key))
        }
    }

    /** 从几个可能的相对路径里找到源码文件；找不到返回 null（测试跳过）。 */
    private fun readSource(relative: String): String? = listOf(
        relative,
        "../$relative",
        "app/$relative"
    ).map { java.io.File(it) }.firstOrNull { it.isFile }?.readText()
}
