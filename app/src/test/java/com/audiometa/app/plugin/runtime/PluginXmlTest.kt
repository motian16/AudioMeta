package com.audiometa.app.plugin.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Platform.xml.*` 的最小行为测试（Apple 插件的 TTML 本地化就靠这四个操作）。 */
class PluginXmlTest {

    private val ttml = """
        <tt xml:lang="en" xmlns:itunes="http://itunes.apple.com/ttml">
          <body><div>
            <p itunes:key="1">Hello</p>
            <translation xml:lang="zh-Hans" type="replacement"><text for="1">你好</text></translation>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `读根属性`() {
        val attrs = JSONObject(
            PluginXml.handle(JSONObject().put("op", "rootAttributes").put("xml", ttml).toString())
        )
        assertEquals("en", attrs.optString("xml:lang"))
    }

    @Test
    fun `按标签和属性查找 translation`() {
        val query = JSONObject()
            .put("op", "find")
            .put("xml", ttml)
            .put("tag", "translation")
            .put("attrs", JSONObject().put("xml:lang", "zh-Hans"))
        val array = org.json.JSONArray(PluginXml.handle(query.toString()))
        assertEquals(1, array.length())
        val item = array.getJSONObject(0)
        assertEquals("zh-Hans", item.getJSONObject("attrs").optString("xml:lang"))
        val child = item.getJSONArray("children").getJSONObject(0)
        assertEquals("1", child.getJSONObject("attrs").optString("for"))
        assertEquals("你好", child.optString("text"))
    }

    @Test
    fun `替换子节点文本并更新根属性`() {
        val request = JSONObject()
            .put("op", "replaceChildren")
            .put("xml", ttml)
            .put("targetTag", "p")
            .put("keyAttr", "itunes:key")
            .put(
                "replacements",
                JSONObject().put("1", JSONObject().put("mode", "text").put("value", "你好"))
            )
            .put("rootAttributes", JSONObject().put("xml:lang", "zh-Hans"))
        val output = PluginXml.handle(request.toString())
        assertTrue("替换后的文本应出现：" + output, output.contains("你好"))
        assertFalse("原文本应被替换：" + output, output.contains("Hello"))
        assertTrue("根语言应更新：" + output, output.contains("xml:lang=\"zh-Hans\""))
    }

    @Test
    fun `删除匹配的元素`() {
        val request = JSONObject()
            .put("op", "remove")
            .put("xml", ttml)
            .put("tag", "translation")
            .put("attrs", JSONObject().put("xml:lang", "zh-Hans"))
        val output = PluginXml.handle(request.toString())
        assertFalse(output.contains("<translation"))
        assertTrue(output.contains("Hello"))
    }
}
