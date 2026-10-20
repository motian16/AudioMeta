package com.audiometa.app.domain.tagrules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置归类兜底：规则文件还没更新时，这两组也要落在正确的地方。
 *
 * 用户点名：
 * - `VENDOR`（`reference libFLAC 1.3.1 20141125` 这类写入工具字符串）**归到「编码函数库」**，并带说明；
 * - `REPLAYGAIN_REFERENCE_LOUDNESS` / `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_TRACK_PEAK`
 *   属于**「回放增益」**，单独一组，组里按键盘出三个下级列表。
 */
class TagRulesBuiltInGroupTest {

    @Test
    fun `VENDOR 归到编码函数库`() {
        assertEquals(TagRules.ENCODER_GROUP, TagRules.builtInGroup("VENDOR"))
        assertEquals(TagRules.ENCODER_GROUP, TagRules.builtInGroup("vendor"))
        assertEquals(TagRules.ENCODER_GROUP, TagRules.builtInGroup("ENCODER"))
        assertEquals(TagRules.ENCODER_GROUP, TagRules.builtInGroup("ENCODED_BY"))
    }

    @Test
    fun `VENDOR 带说明`() {
        val info = TagRules.describe("VENDOR")
        assertNotNull("VENDOR 必须有说明（用户要求附上说明）", info)
        assertEquals(TagRules.ENCODER_GROUP, info!!.alias)
        assertTrue("说明里要提到 libFLAC：" + info.description, info.description.contains("libFLAC"))
    }

    @Test
    fun `三个回放增益键归到回放增益组`() {
        val keys = listOf(
            "REPLAYGAIN_REFERENCE_LOUDNESS",
            "REPLAYGAIN_TRACK_GAIN",
            "REPLAYGAIN_TRACK_PEAK"
        )
        keys.forEach { key ->
            assertEquals("$key 应该在回放增益组", TagRules.REPLAY_GAIN_GROUP, TagRules.builtInGroup(key))
            // 组内的别名：三个键各不相同（界面上要能分辨）。
            assertNotNull("$key 要有说明", TagRules.describe(key))
        }
        val aliases = keys.map { TagRules.describe(it)!!.alias }
        assertEquals("三个键的别名不能重复", 3, aliases.distinct().size)
    }

    @Test
    fun `回放增益组要按键盘出下级列表`() {
        assertTrue(TagRules.wantsKeyLists(TagRules.REPLAY_GAIN_GROUP))
        // 其它组不按键盘出列表（仍然按 10 首阈值合并）。
        assertTrue(!TagRules.wantsKeyLists(TagRules.ENCODER_GROUP))
        assertTrue(!TagRules.wantsKeyLists("QQ 音乐"))
    }

    @Test
    fun `普通键不被兜底收编`() {
        assertEquals(null, TagRules.builtInGroup("MOOD"))
        assertEquals(null, TagRules.builtInGroup("kghash"))
    }
}
