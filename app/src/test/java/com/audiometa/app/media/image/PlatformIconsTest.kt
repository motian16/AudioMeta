package com.audiometa.app.media.image

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 平台图标与**仓库**的对应关系。
 *
 * 「音频库 - 元信息」页每个分组左边那张图靠两件事对上：
 * 分组名 →（[PlatformIcons.keyFor] 别名表）→ 图标文件名 → 仓库 `icon/` 里的那张 png。
 * 这两步任何一步断了，界面就只剩占位图 —— 而且不会报任何错，只能靠人肉发现。
 *
 * 所以这里直接拿仓库里的真文件来测（和 `RepoIndexUrlsTest` 一样，两个仓库在
 * 工作目录里是并排的）：
 *
 * 1. `icon/` 下每张图，别名表都得认得出它的名字；
 * 2. 规则里那些**有图可对**的分组名（哔哩哔哩、QQ 音乐、编码函数库…），
 *    都要能落到一张真实存在的图上。
 *
 * 这两条正是用户报的那个问题：图在仓库里，App 却不显示。
 */
class PlatformIconsTest {

    private fun repoRoot(): File {
        val candidates = listOf(
            File("../../MetaAudio-Repo"),
            File("../MetaAudio-Repo"),
            File("MetaAudio-Repo"),
            File("../../salt/MetaAudio-Repo")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 MetaAudio-Repo，当前目录：" + File(".").absolutePath)
    }

    /** 仓库 `icon/` 下的图标文件名（不含扩展名）。 */
    private fun iconKeys(): Set<String> {
        val dir = File(repoRoot(), "icon")
        // 仓库把图标并进订阅包之后就没有 icon/ 了：这一组用例只在有目录时才有意义。
        org.junit.Assume.assumeTrue("仓库里没有 icon/ 目录，跳过图标一致性检查", dir.isDirectory)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", true) }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            .orEmpty()
    }

    /** 规则里所有分组的名字。 */
    private fun groupNames(): List<String> {
        val dir = File(repoRoot(), "rules/groups")
        assertTrue("仓库里要有 rules/groups/：" + dir.absolutePath, dir.isDirectory)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.sortedBy { it.name }
            ?.flatMap { file ->
                val groups = JSONObject(file.readText()).optJSONArray("groups") ?: return@flatMap emptyList()
                (0 until groups.length()).mapNotNull { groups.optJSONObject(it)?.optString("name") }
            }
            .orEmpty()
    }

    @Test
    fun `仓库里每张平台图标，别名表都认得出`() {
        // 认不出就意味着"图装进 App 了却没有分组用它" —— 文件名和别名表对不上。
        for (key in iconKeys()) {
            assertTrue(
                "icon/" + key + ".png 在别名表里没有对应项（PlatformIcons.ALIASES）",
                PlatformIcons.keyFor(key) != null
            )
        }
    }

    @Test
    fun `有图可对的分组名都能落到一张真实存在的图上`() {
        val keys = iconKeys()
        // 这些分组名在规则里就是这么写的；每一条都该显示自己的图标。
        val expect = listOf(
            "QQ 音乐" to "qq",
            "网易云音乐" to "netease",
            "酷狗音乐" to "kugou",
            "Apple Music / iTunes" to "apple",
            "苹果语音备忘录" to "apple-notes",
            "MusicBrainz" to "musicbrainz",
            "AcoustID" to "musicbrainz",
            "mora" to "mora",
            "哔哩哔哩" to "bilibili",
            "同人音乐" to "touhou",
            "编码函数库" to "ffmpeg",
            "FFmpeg" to "ffmpeg"
        )
        for ((label, key) in expect) {
            assertEquals("分组「" + label + "」该对到 " + key, key, PlatformIcons.keyFor(label))
            assertTrue("仓库 icon/ 里缺 " + key + ".png", key in keys)
        }
    }

    @Test
    fun `规则里这些分组确实存在，别名表的键没有写错`() {
        // 规则那边把分组名改了（例如「哔哩哔哩」改成「B站」），上面那条用例就白测了 ——
        // 这里对着真实规则文件再确认一次：别名表里能对到图的那几个名字都得真的在用。
        val names = groupNames().toSet()
        assertTrue("规则里没有读到任何分组", names.isNotEmpty())
        for (label in listOf("QQ 音乐", "网易云音乐", "酷狗音乐", "哔哩哔哩", "同人音乐", "编码函数库", "mora")) {
            assertTrue(
                "规则里已经没有这个分组名了：" + label + "（别名表该跟着改）",
                label in names
            )
        }
    }
}
