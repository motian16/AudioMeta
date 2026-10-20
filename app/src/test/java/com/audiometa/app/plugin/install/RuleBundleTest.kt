package com.audiometa.app.plugin.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则文件按组拆分之后的合并。
 *
 * 仓库里现在是：清单 `rules/rules.json`（版本 + files[]）+ `shared/` 公共表 +
 * `groups/<英文id>.json`。宿主把分片合成一份老格式规则再落盘，
 * 于是解析层完全不用知道"拆分"这件事。
 */
class RuleBundleTest {

    private val manifest = """
        {
          "schema": 2,
          "rulesVersion": 9,
          "source": "订阅仓库",
          "files": [
            { "file": "shared/standard.json", "sha256": "aa" },
            { "file": "groups/doujin.json", "sha256": "bb" },
            { "file": "groups/qqmusic.json", "sha256": "cc" }
          ]
        }
    """.trimIndent()

    @Test
    fun `清单能认出分片列表`() {
        assertTrue(RuleBundle.isManifest(manifest))
        val entries = RuleBundle.entries(manifest)
        assertEquals(3, entries.size)
        assertEquals("shared/standard.json", entries.first().file)
        assertEquals("cc", entries.last().sha256)
    }

    @Test
    fun `老的单文件规则不算清单`() {
        val legacy = """{"schema":2,"rulesVersion":8,"groups":[{"name":"QQ 音乐"}]}"""
        assertFalse("没有 files[] 的旧规则要照旧按单文件处理", RuleBundle.isManifest(legacy))
        assertTrue(RuleBundle.entries(legacy).isEmpty())
    }

    @Test
    fun `合并 数组拼接 对象合并 版本以清单为准`() {
        val standard = """{"standard":{"keys":["title","artist"]},"standardValues":{"encoder":["Lavf"]}}"""
        val doujin = """{"groups":[{"id":"doujin","name":"同人音乐"}]}"""
        val qq = """{"groups":[{"id":"qqmusic","name":"QQ 音乐"}],"rulesVersion":1,"hints":["提示"]}"""
        val merged = JSONObject(
            RuleBundle.merge(
                manifest,
                listOf(
                    "shared/standard.json" to standard,
                    "groups/doujin.json" to doujin,
                    "groups/qqmusic.json" to qq
                )
            )
        )
        assertEquals(2, merged.getJSONArray("groups").length())
        assertEquals("doujin", merged.getJSONArray("groups").getJSONObject(0).getString("id"))
        assertEquals(2, merged.getJSONObject("standard").getJSONArray("keys").length())
        assertEquals(1, merged.getJSONArray("hints").length())
        assertEquals("分片里的版本号不能盖掉清单的", 9, merged.getInt("rulesVersion"))
        assertEquals("source", "订阅仓库", merged.getString("source"))
    }

    @Test
    fun `缺分片不影响其它分片`() {
        val merged = JSONObject(
            RuleBundle.merge(
                manifest,
                listOf("groups/doujin.json" to """{"groups":[{"id":"doujin"}]}""")
            )
        )
        assertEquals(1, merged.getJSONArray("groups").length())
        assertEquals(9, merged.getInt("rulesVersion"))
    }

    @Test
    fun `坏 JSON 的那一片被跳过`() {
        val merged = JSONObject(
            RuleBundle.merge(
                manifest,
                listOf(
                    "groups/doujin.json" to "{ 这不是 JSON",
                    "groups/qqmusic.json" to """{"groups":[{"id":"qqmusic"}]}"""
                )
            )
        )
        assertEquals(1, merged.getJSONArray("groups").length())
    }

    // 「分片地址按清单所在目录解析」那个用例删了：拼接分片地址是当初下载器
    // 才需要的能力，而分片下载这条路（连同 RuleBundle.resolve）已经整体移除 ——
    // 更新现在只下载 MetaAudio-Subscription.zip，包里就是合并好的规则。
}
