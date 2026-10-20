package com.audiometa.app.domain.tagrules

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 标签识别的单元测试。
 *
 * 分两段：
 *
 * 1. **没有规则**（[NoRulesTest]）—— 这一段是这次回归的护栏。规则只负责
 *    别名、说明、平台分组和值译名，**不决定哪些标签算存在**：文件里读到的
 *    每一个非标准标签都必须出现在结果里（没有规则时归到「未知标签」）。
 *    以前 `classify` 在没有规则时直接返回空表，元信息页因此一片空白。
 * 2. **有规则**（[TagRulesTest]）—— 直接读仓库目录里的
 *    `MetaAudio-Repo/rules/tag_rules.json`，验证"某个标签会被认成哪个平台、
 *    值有没有中文译名"。规则文件改了之后一定要跑这一段：规则表是纯文本，
 *    写错一个键名不会编译报错，只会静默地少识别一批标签。
 */
class NoRulesTest {

    /**
     * 进入"一份规则都没有"的状态。
     *
     * `TagRules` 是单例，同一台 JVM 里别的测试类可能已经装过规则，
     * 所以这里必须显式清一次（[TagRules.clearRules] 就是"删除本地规则"
     * 那个按钮走的同一条路）。
     */
    @Before
    fun clearRules() {
        TagRules.clearRules()
        assertFalse("这一段必须真的处于「没有规则」的状态", TagRules.isImported)
    }

    @After
    fun tearDown() {
        // 不留下状态：别的测试类可能依赖"当前没有规则"这个前提。
        TagRules.clearRules()
    }

    @Test
    fun `没有规则时标准键仍然不算未知标签`() {
        // 内置白名单是**代码**，不跟着规则文件走 —— 没有规则时它也必须生效，
        // 否则标题、艺术家、专辑会被当成"未知标签"刷满元信息页（这正是当初
        // 那次"没有规则就返回空表"的错误修法想解决的问题，现在用白名单解决）。
        assertTrue(TagRules.isStandardKey("TITLE"))
        assertTrue(TagRules.isStandardKey("artist"))
        assertTrue(TagRules.isStandardKey("ALBUMARTIST"))
        assertTrue(TagRules.isStandardKey("TRACKNUMBER"))
        assertTrue(TagRules.isStandardKey("TXXX:cover"))
        assertTrue(TagRules.isStandardKey("metadata_block_picture"))

        val entries = TagRules.classify(
            mapOf("TITLE" to "某首歌", "ARTIST" to "某人", "ALBUM" to "某张专辑"),
            includeStandard = false
        )
        assertTrue("标准标签不该出现在未知标签里：" + entries.map { it.key }, entries.isEmpty())
    }

    @Test
    fun `没有规则时随机未知键按**键名**分组`() {
        val entries = TagRules.classify(
            mapOf("SOME_PLATFORM_MARKER" to "abc123"),
            includeStandard = false
        )
        assertEquals("非标准标签必须被返回，不能因为没有规则就丢掉", 1, entries.size)
        val entry = entries.first()
        assertEquals("SOME_PLATFORM_MARKER", entry.key)
        // 没有规则 = 认不出来源，但标签本身照样存在。
        assertNull(entry.platform)
        assertNull(entry.tool)
        assertFalse(entry.isStandard)

        // 一份规则都没有时按键名分组（用户要求）：几十种键各自成一张卡，
        // 而不是全挤进一张「未知标签」。规则只负责给它加别名和说明。
        val summary = TagRules.summarize(entries).first()
        assertEquals("SOME_PLATFORM_MARKER", summary.group)
        // 没有规则就没有说明，界面据此显示"规则里还没有这个标签的说明"。
        assertNull(summary.keyInfo)
    }

    @Test
    fun `没有规则时混合标签只滤掉标准键`() {
        val entries = TagRules.classify(
            mapOf(
                "TITLE" to "歌名",
                "QMQuality" to "Premium-Master",
                "kghash" to "0123456789abcdef0123456789abcdef"
            ),
            includeStandard = false
        )
        assertEquals("应该只剩两个非标准标签", 2, entries.size)
        // 按小写排：String.sorted() 是 ASCII 序，大写字母会全排在小写前面。
        assertEquals(
            listOf("kghash", "qmquality"),
            entries.map { it.key.lowercase() }.sorted()
        )
        // 一个来源都认不出来，全部落到「未知标签」。
        assertTrue(entries.all { it.platform == null })
    }

    @Test
    fun `规则里定义过的键仍然拿到别名和分组`() {
        // 导入一份最小规则：只描述 QMQuality 一个键。
        val text = """
            {
              "rulesVersion": 99,
              "source": "单测",
              "standard": { "keys": ["title", "artist", "album"] },
              "groups": [
                {
                  "name": "QQ 音乐",
                  "kind": "platform",
                  "keys": ["QMQuality"],
                  "keyInfo": {
                    "QMQuality": { "type": "QQ音乐音质", "alias": "QQ音乐音质", "description": "QQ 音乐的档位" }
                  },
                  "valueLabels": { "Premium-Master": "臻品母带" }
                }
              ]
            }
        """.trimIndent()
        assertTrue("最小规则应该解析成功", TagRules.loadFromText(text, "测试"))
        assertTrue(TagRules.isImported)

        // 规则写明的键：拿到别名、说明和平台分组。
        val info = TagRules.describe("QMQuality")
        assertEquals("QQ音乐音质", info?.alias)
        assertEquals("QQ 音乐的档位", info?.description)
        assertEquals("臻品母带", TagRules.valueLabel("QMQuality", "Premium-Master"))

        val entries = TagRules.classify(
            mapOf("QMQuality" to "Premium-Master", "SOMETHING_NEW" to "xyz"),
            includeStandard = false
        )
        assertEquals("规则没提到的键也要在，只是没有来源", 2, entries.size)
        val qq = entries.first { it.key == "QMQuality" }
        assertEquals("QQ 音乐", qq.platform)
        assertEquals("QQ 音乐", TagRules.summarize(listOf(qq)).first().group)

        val unknown = entries.first { it.key == "SOMETHING_NEW" }
        assertNull(unknown.platform)
        assertEquals(TagSummary.UNKNOWN_GROUP, TagRules.summarize(listOf(unknown)).first().group)

        // 规则里的标准表是**加法**：内置白名单不会被它挤掉。
        assertTrue(TagRules.isStandardKey("TITLE"))
        assertTrue(TagRules.isStandardKey("composer"))
        assertTrue(TagRules.classify(mapOf("TITLE" to "x"), includeStandard = false).isEmpty())
    }
}

/**
 * 有规则时的识别测试（原来的那一段，行为不变）。
 */
class TagRulesTest {

    /**
     * 规则文件已经**按组拆开**（清单 + shared/ + groups/&lt;英文id&gt;.json）。
     *
     * 这里走的是和宿主完全相同的合并逻辑（[com.audiometa.app.plugin.install.RuleBundle]）：
     * 于是单测既验证规则内容，也顺带验证"拆开的仓库能被合成一份可用的规则"。
     */
    private fun rulesText(): String {
        val manifest = rulesDir()?.let { File(it, "rules.json") } ?: return ""
        return com.audiometa.app.plugin.install.RuleBundle.merge(
            manifest.readText(),
            com.audiometa.app.plugin.install.RuleBundle.entries(manifest.readText()).mapNotNull { entry ->
                val file = File(rulesDir(), entry.file)
                if (file.isFile) entry.file to file.readText() else null
            }
        )
    }

    /**
     * 仓库里的 rules 目录。
     *
     * Gradle 跑单测时工作目录是**模块目录**（`app/`），所以要往上**两级**才到工程根，
     * 再上一级才是放各工程的目录。三种常见布局都列出来：
     *   - `../MetaAudio-Repo`   工程根旁边就放着仓库（Gradle 在 app/ 下跑时用 `../../`）
     *   - `MetaAudio-Repo`      从工程根跑，仓库就在里面
     *   - `../salt/MetaAudio-Repo`  本机布局：两个工程同在 `G:\Server\work\salt\` 下
     * **找不到就返回 null**，用例会自己跳过而不是误判失败。
     */
    private fun rulesDir(): File? = listOf(
        File("../../MetaAudio-Repo/rules"),
        File("../MetaAudio-Repo/rules"),
        File("MetaAudio-Repo/rules"),
        File("../../salt/MetaAudio-Repo/rules")
    ).firstOrNull { it.isDirectory }

    @Before
    fun load() {
        val text = rulesText()
        assertTrue("内置标签规则解析失败", TagRules.loadFromText(text, "测试"))
    }

    @Test
    fun `组内的值译名不许套到别的键上`() {
        /*
         * 用户报的 bug：`TRACKTOTAL: 4` / `DISCTOTAL: 1`（专辑分轨/碟数）在元信息页
         * 被判成 Apple Music 的年龄分级（4 = 仅限成人、1 = 家长建议），
         * 而这些歌根本不是 Apple 下载的。
         *
         * 原因：Apple Music 那一组的 valueLabels 被当成了**全局**值译名。
         * 现在它们只对那一组认领的键（contentrating）生效。
         */
        // 认领的键上照旧有译名。
        assertEquals("仅限成人", TagRules.displayValue("contentrating", "4"))
        // 别人家的键上有同样的值 → 不能借用 Apple 的译名。
        for (key in listOf("TRACKTOTAL", "DISCTOTAL", "totaltracks", "totaldiscs")) {
            val shown = TagRules.displayValue(key, "4")
            assertTrue(
                "$key 的值 4 不该显示成 Apple 分级，实际显示：" + shown,
                shown != "仅限成人"
            )
        }
    }

    @Test
    fun `QMQuality 归到 QQ 音乐`() {
        val entries = TagRules.classify(mapOf("QMQuality" to "Premium-Master"), includeStandard = false)
        assertEquals(1, entries.size)
        assertEquals("QQ 音乐", entries.first().platform)
    }

    @Test
    fun `QMQuality 的值有中文译名`() {
        val label = TagRules.valueLabel("QMQuality", "Premium-Master")
        assertEquals("臻品母带", label)
        // 界面显示用译名（不拼原值）：右列很窄，拼上原值必然被截断。
        assertEquals("臻品母带", TagRules.displayValue("QMQuality", "Premium-Master"))
    }

    @Test
    fun `没有准确译名的值按类型取别名`() {
        // 用户要求："如原值为长的字符串，自动根据类型取个别名，
        // 如符合哈希值的就显示哈希值，实在没有的才显示原始字符串"。
        assertEquals("哈希值", TagRules.displayValue("kghash", "0123456789ABCDEF0123456789abcdef"))
        assertEquals("UUID", TagRules.displayValue("x", "ff8ca75f-2d68-52eb-85d6-1580486025a4"))
        assertEquals("JSON", TagRules.displayValue("extend", "{\"ALBUMID\":123}"))
        assertEquals("UMID", TagRules.displayValue("umid", "0x060A2B3401010105"))
        assertEquals("站点 URL", TagRules.displayValue("woar", "https://youtu.be/abcdefg"))
        // mora 那类值：数字是编号，TFCC 开头的是录音代码 —— 列表右侧显示的就是这些短别名。
        assertEquals("编号", TagRules.displayValue("mora-key", "10006001"))
        assertEquals("录音代码", TagRules.displayValue("mora-key", "TFCC86625f01H"))
    }

    @Test
    fun `实在认不出来的值显示原文并截断`() {
        // 短原文原样显示。
        assertEquals("A/B", TagRules.displayValue("x", "A/B"))
        // 长原文截断（界面还会再按宽度省略一次，这里是兜底）。
        // 刻意用中文：拉丁字母的长串会先命中"加密元数据残留"那条规则。
        val long = "很长的一段没有任何类型特征的描述文字".repeat(10)
        val shown = TagRules.displayValue("x", long)
        assertTrue("长值应该被截断，实际长度 " + shown.length, shown.length < long.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `全景声档位也有译名`() {
        assertEquals("臻品全景声 5.1", TagRules.valueLabel("QMQuality", "Premium-Atmos-51"))
        assertEquals("臻品全景声 2.0", TagRules.valueLabel("QMQuality", "Premium-Atmos-2"))
    }

    @Test
    fun `按平台名合并时 QMQuality 落在 QQ 音乐那一组`() {
        val summary = TagRules.summarize(
            listOf(
                TagEntry("QMQuality", "Premium-Master", platform = "QQ 音乐"),
                TagEntry("QMQuality", "Premium-Atmos-2", platform = "QQ 音乐")
            )
        ).first()
        assertEquals("QQ 音乐", summary.group)
        assertEquals(2, summary.count)
        assertEquals(2, summary.valueCount)
        assertTrue(summary.values.contains("臻品母带"))
        assertTrue(summary.values.contains("臻品全景声 2.0"))
    }

    @Test
    fun `不在规则里的标签归到未知标签组`() {
        val summary = TagSummary(key = "SOMETHING_WEIRD", count = 1)
        assertEquals("未知标签", summary.group)
        assertEquals(TagSummary.UNKNOWN_GROUP, summary.group)
    }

    @Test
    fun `专业音频工作站的字段按键名命中工具`() {
        // UMID / ORIGINATOR_REFERENCE 的值里没有工具名，只能靠键名匹配。
        val entries = TagRules.classify(
            mapOf("UMID" to "0x060A2B3401010105", "ORIGINATOR_REFERENCE" to "abc"),
            includeStandard = false
        )
        assertTrue(entries.all { it.tool != null })
    }

    @Test
    fun `酷狗的私有标签能认出来`() {
        val entries = TagRules.classify(
            mapOf(
                "kghash" to "0123456789abcdef0123456789abcdef",
                "quality" to "viper_clear"
            ),
            includeStandard = false
        )
        assertTrue(entries.all { it.platform == "酷狗音乐" })
        // 蝰蛇音效体系里 viper_clear 是「蝰蛇超清」音质档位。
        // 旧文档写的是「清澈人声」，那是音效预设的名字，不是音质档位，已更正。
        assertEquals("蝰蛇超清", TagRules.valueLabel("quality", "viper_clear"))
    }

    @Test
    fun `规则里写了说明的标签能说清自己是什么`() {
        // 用户要求："你只显示值是没用的，你要说明该标签大概是什么。"
        val info = TagRules.describe("QMQuality")
        assertNotNull(info)
        assertTrue(info!!.type.contains("QQ"))
        assertEquals("QQ音乐音质", info.alias)
        assertTrue(info.description.contains("母带"))

        // 没写说明的键老实返回 null，界面据此显示"规则里还没有这个标签的说明"。
        assertNull(TagRules.describe("A_KEY_NOBODY_KNOWS"))
    }

    @Test
    fun `标准标签不算未知标签`() {
        val entries = TagRules.classify(
            mapOf(
                "TITLE" to "某首歌",
                "ARTIST" to "某人",
                "QMQuality" to "Premium-Master"
            ),
            includeStandard = false
        )
        assertEquals(1, entries.size)
        assertEquals("QMQuality", entries.first().key)
    }

    @Test
    fun `规则里的平台都有名字`() {
        val entries = TagRules.classify(
            mapOf(
                "audioQuality" to "LOSSLESS",
                "SOURCE_PLATFORM" to "SoundCloud",
                "MUSICBRAINZ_TRACKID" to "abc",
                "QOBUZ_TRACK_ID" to "123"
            ),
            includeStandard = false
        )
        assertTrue(entries.all { !it.platform.isNullOrBlank() })
        assertNotNull(TagRules.ruleInfo.source)
        assertTrue("规则版本应该已经升到 4 以上", TagRules.ruleInfo.version >= 4)
    }

    @Test
    fun `显示键名去掉容器前缀`() {
        // 用户拿 MediaInfo 对照过：那边显示的就是最后一段。
        // ID3 的 TXXX 帧如果不接上描述，一个文件里十个自定义帧会全并成"TXXX"一个键。
        assertEquals("major_brand", TagRules.displayKey("TXXX:major_brand"))
        assertEquals("voice-memo-uuid", TagRules.displayKey("----:com.apple.iTunes:voice-memo-uuid"))
        assertEquals("QMQUALITY", TagRules.displayKey("QMQUALITY"))
    }

    @Test
    fun `mora 的一类 UUID 键名共用一个说明`() {
        val a = TagRules.describe("45B1D925-1448-5784-B4DA-B89901050A13")
        val b = TagRules.describe("FF8CA75F-2D68-52EB-85D6-1580486025A4")
        assertNotNull("UUID 形式的键名应该由正则规则命中", a)
        // 共用一个介绍 —— 不再为每个 UUID 各写三行。
        assertEquals(a, b)
        assertTrue(a!!.description.contains("mora"))
    }

    @Test
    fun `EVENT 的值按正则取中文名`() {
        // C75 = 第 75 届 Comic Market。
        assertEquals("Comic Market 75（第 75 届）", TagRules.valueLabel("EVENT", "C75"))
        // 别的键同名值不该套用这条规则。
        assertNull(TagRules.valueLabel("SOMETHING_ELSE", "C75"))
    }

    @Test
    fun `键名和值都有中文别名`() {
        // 用户要求："值和键名都有中文别名"。
        assertEquals("容器主品牌", TagRules.describe("TXXX:major_brand")?.alias)
        assertEquals("ISO 基础媒体格式第 5 版", TagRules.valueLabel("major_brand", "iso5"))
        // 图片组没有键名说明（判定只看值是不是图片），未知键必须老实返回 null，
        // 否则界面上会给所有没规则的键编一段说明。
        assertNull(TagRules.describe("A_KEY_NOBODY_KNOWS"))
    }

    @Test
    fun `mora 的值按类型复用一份说明`() {
        // 用户要求："每个 UUID 字符串底下的介绍是针对其值做出的介绍，对值进行复用级匹配"。
        val number = TagRules.valueInfo("45B1D925-1448-5784-B4DA-B89901050A13", "10006001")
        assertNotNull(number)
        assertEquals("编号", number!!.alias)
        val code = TagRules.valueInfo("8E90F26B-372A-5C8C-BB05-1EC0F36EE60C", "TFCC86625f01H")
        assertEquals("录音代码", code!!.alias)
        // 同类型的值共用同一条说明，不为每个 UUID 各写一份。
        assertEquals(
            TagRules.valueInfo("FF8CA75F-2D68-52EB-85D6-1580486025A4", "78055413"),
            TagRules.valueInfo("BE242671-3D48-5AC8-B762-7D2DB4F584B8", "10956868")
        )
    }

    @Test
    fun `图片值统一归到未知图片组`() {
        val entries = TagRules.classify(
            mapOf("APIC:Booklet" to TagRules.IMAGE_VALUE_PREFIX + "abc.jpg"),
            includeStandard = false
        )
        assertEquals(1, entries.size)
        val summary = TagRules.summarize(entries).first()
        assertEquals(TagSummary.IMAGE_GROUP, summary.group)
        assertTrue(summary.isImage)
    }

    @Test
    fun `只有键没有值的标签也保留`() {
        val raw = mapOf("SOME_CUSTOM_KEY" to "")
        assertTrue(
            "默认应该丢掉空值",
            TagRules.classify(raw, includeStandard = false).isEmpty()
        )
        val kept = TagRules.classify(raw, includeStandard = false, keepBlankValues = true)
        assertEquals(1, kept.size)
        assertEquals("SOME_CUSTOM_KEY", kept.first().key)
    }
}

/**
 * 规则 v6（schema 2）新增内容的护栏。
 *
 * 这些断言对应的是用户逐条点名的要求，规则文件是纯文本 —— 改错了不会编译报错，
 * 只会静默地少认一批标签，所以每一条都钉在这里。
 */
class TagRulesV6Test {

    /** 规则文件已按组拆分，这里用和宿主相同的合并逻辑读进来。 */
    private fun rulesText(): String {
        val dir = listOf(
            File("../../MetaAudio-Repo/rules"),
            File("../MetaAudio-Repo/rules"),
            File("MetaAudio-Repo/rules"),
            File("../../salt/MetaAudio-Repo/rules")
        )
            .firstOrNull { it.isDirectory } ?: return ""
        val manifest = File(dir, "rules.json")
        if (!manifest.isFile) return ""
        return com.audiometa.app.plugin.install.RuleBundle.merge(
            manifest.readText(),
            com.audiometa.app.plugin.install.RuleBundle.entries(manifest.readText()).mapNotNull { entry ->
                val file = File(dir, entry.file)
                if (file.isFile) entry.file to file.readText() else null
            }
        )
    }

    @Before
    fun load() {
        assertTrue(TagRules.loadFromText(rulesText(), "测试"))
    }

    @After
    fun clear() {
        TagRules.clearRules()
    }

    @Test
    fun `每组可以指定列表右侧固定显示的键`() {
        // 用户要求：一组里有多个键名时，右侧要稳定显示最关键的那个。
        assertEquals("quality", TagRules.rightKey("酷狗音乐"))
        assertEquals("organization", TagRules.rightKey("mora"))
        assertEquals("event", TagRules.rightKey("同人音乐"))
        // 规则里没写这一组的 rightKey 时返回 null，界面退回"按别名去重"的老行为。
        assertNull(TagRules.rightKey("没有这个组"))
    }

    @Test
    fun `mora 的 ORGANIZATION 右侧显示值本身而不是厂牌`() {
        // 用户要求："这个的别名就是值了，不是厂牌" —— 值级说明里不能再给 alias，
        // 否则右侧会显示成「厂牌」两个字。
        val info = TagRules.valueInfo("ORGANIZATION", "JASRAC")
        assertNotNull(info)
        assertEquals("", info!!.alias)
        assertEquals("JASRAC", TagRules.displayValue("ORGANIZATION", "JASRAC"))
        assertEquals("TOY'S FACTORY", TagRules.displayValue("ORGANIZATION", "TOY'S FACTORY"))
    }

    @Test
    fun `mora 的 GENRENUMBER 与 MOOD 也算 mora`() {
        // 用户要求：除固定长度的 UUID 键名外，这两个普通键名也要认。
        val entries = TagRules.classify(
            mapOf("GENRENUMBER" to "12345", "MOOD" to "ポップ"),
            includeStandard = false
        )
        assertEquals(2, entries.size)
        assertTrue("两个键都该归到 mora：" + entries.map { it.key + "=" + it.platform },
            entries.all { it.platform == "mora" })
    }

    @Test
    fun `值为展会标记时新增同人音乐组`() {
        // 用户要求：值为「例大祭 SP」或 C75 时加入本组。
        val komiket = TagRules.classify(mapOf("EVENT" to "C75"), includeStandard = false).first()
        assertEquals("同人音乐", komiket.platform)

        val reitaisai = TagRules.classify(mapOf("EVENT" to "例大祭 SP"), includeStandard = false).first()
        assertEquals("同人音乐", reitaisai.platform)
        // 列表右侧显示的就是这条值（EVENT 是这一组的 rightKey）。
        assertEquals("例大祭 SP", TagRules.displayValue("EVENT", "例大祭 SP"))
        assertEquals("Comic Market 75（第 75 届）", TagRules.displayValue("EVENT", "C75"))
    }

    @Test
    fun `没有值的键也按规则分组`() {
        // 用户要求："无值的也按规则进行分组" —— 空值不能因此掉进「未知标签」。
        val entries = TagRules.classify(
            mapOf(
                "TXXX:HASVIDEO" to "",
                "TXXX:LASTTIMESTAMP" to "",
                "TXXX:METADATACREATOR" to "",
                "TXXX:MAJOR_BRAND" to "isom",
                "TXXX:QUALITY" to "",
                "ORGANIZATION" to ""
            ),
            includeStandard = false,
            keepBlankValues = true
        )
        assertEquals(6, entries.size)
        assertEquals("哔哩哔哩", entries.first { it.key.endsWith("HASVIDEO") }.platform)
        assertEquals("哔哩哔哩", entries.first { it.key.endsWith("LASTTIMESTAMP") }.platform)
        assertEquals("哔哩哔哩", entries.first { it.key.endsWith("METADATACREATOR") }.platform)
        assertEquals("哔哩哔哩", entries.first { it.key.endsWith("MAJOR_BRAND") }.platform)
        assertEquals("酷狗音乐", entries.first { it.key.endsWith("QUALITY") }.platform)
        assertEquals("mora", entries.first { it.key == "ORGANIZATION" }.platform)
        // 按组归并时也都落在各自的分组里，不会进「未知标签」。
        assertTrue(
            TagRules.summarize(entries).none { it.group == TagSummary.UNKNOWN_GROUP }
        )
    }

    @Test
    fun `人话版规则里带着右侧固定键和键名说明`() {
        val book = TagRules.ruleBook
        assertTrue("规则页要有分组", book.groups.isNotEmpty())
        val mora = book.groups.first { it.name == "mora" }
        assertEquals("organization", mora.rightKey)
        assertTrue("mora 组要列出键名说明", mora.keys.any { it.key == "organization" })
        assertTrue("GENRENUMBER 也要在列表里", mora.keys.any { it.key == "genrenumber" })
        val doujin = book.groups.first { it.name == "同人音乐" }
        assertEquals("event", doujin.rightKey)
        assertTrue("全局值别名要能被规则页读到", book.valueAliases.isNotEmpty())
        assertTrue("规则页要能看到标准值", book.standardValues.isNotEmpty())
    }

    @Test
    fun `基础标签里的流派和歌词不会被列出来`() {
        // 用户给的"基础标签"清单里包含流派和歌词，各容器的写法都得算。
        for (key in listOf("GENRE", "TCON", "©gen", "LYRICS", "USLT", "©lyr", "UNSYNCEDLYRICS")) {
            assertTrue("$key 属于基础标签", TagRules.isStandardKey(key))
        }
        assertTrue(
            TagRules.classify(
                mapOf("GENRE" to "Pop", "©lyr" to "某段歌词", "USLT" to "某段歌词"),
                includeStandard = false
            ).isEmpty()
        )
    }

    @Test
    fun `基础标签之外的一律列出来`() {
        // 用户要求：标准表只留基础标签（外加各容器写法、软件自己写入的指挥、
        // 编码函数库那几个"连值一起判"的键），别的都归"基础标签之外的信息"。
        val shouldList = listOf(
            "TOTALTRACKS", "TOTALDISCS", "DISCTOTAL", "DISCID",
            "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_GAIN",
            "SORTTITLE", "ARTISTSORT", "ALBUMSORT",
            "ORIGINALDATE", "ORIGINALYEAR", "RELEASEDATE",
            "PUBLISHER", "LABEL", "MEDIA", "COMPILATION", "RATING", "GROUPING", "SUBTITLE",
            "PRODUCER", "ENGINEER", "MIXER", "REMIXER", "ARRANGER",
            "BARCODE", "UPC", "ASIN", "CATALOGNUMBER",
            "©prd", "©swr", "©dir", "©aut", "©grp",
            "com.android.version", "com.android.manufacturer",
            "TSSE", "ENCODERSETTINGS"
        )
        for (key in shouldList) {
            assertFalse("$key 属于基础标签之外的信息，应该列出来", TagRules.isStandardKey(key))
        }

        val entries = TagRules.classify(
            mapOf(
                "TITLE" to "某首歌",
                "TOTALTRACKS" to "12",
                "REPLAYGAIN_TRACK_GAIN" to "-3.50 dB",
                "©prd" to "Produced by X",
                "com.android.version" to "13",
                "TSSE" to "Lavf58.29.100"
            ),
            includeStandard = false
        )
        assertEquals("只有 TITLE 被滤掉：" + entries.map { it.key }, 5, entries.size)
        assertTrue(entries.any { it.key == "TOTALTRACKS" })
        // 编码信息类不再算标准字段，但仍归到「编码函数库」那一组。
        assertEquals("编码函数库", entries.first { it.key == "TSSE" }.tool)
    }

    @Test
    fun `编码函数库的值不合标准时才列出来`() {
        // 标准值：不列（MediaInfo 里的"编码函数库"是 Lavf / LAME / libFLAC 这类）。
        assertTrue(TagRules.isStandardTag("ENCODEDBY", "Lavf58.29.100"))
        assertTrue(TagRules.isStandardTag("ENCODER", "LAME3.100"))
        assertTrue(TagRules.isStandardTag("©too", "libFLAC 1.3.3"))
        assertTrue(TagRules.isStandardTag("TENC", "iTunes 12.9.5.7"))
        // 第三方平台塞的私货：列出来。
        assertFalse(TagRules.isStandardTag("ENCODEDBY", "baimusic"))

        val entries = TagRules.classify(
            mapOf(
                "TITLE" to "某首歌",
                "ENCODER" to "Lavf58.29.100",
                "ENCODEDBY" to "baimusic"
            ),
            includeStandard = false
        )
        assertEquals("只剩 baimusic 那一条：" + entries.map { it.key }, 1, entries.size)
        val entry = entries.first()
        assertEquals("ENCODEDBY", entry.key)
        assertEquals("编码函数库", entry.tool)
        assertEquals("编码函数库", TagRules.summarize(entries).first().group)
        assertEquals("编码函数库", TagRules.describe("ENCODEDBY")?.alias)
    }
}
