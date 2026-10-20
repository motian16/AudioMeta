package com.audiometa.app.plugin.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * index.json 里"从哪儿下载、校验值是多少"的解析。
 *
 * 单独测它是因为：这些推导错了，用户看到的是"下载失败"，
 * 真正的原因却在解析这一步（相对路径拼错、或多认了一个 sha256 字段）。
 */
class RepoIndexUrlsTest {

    private val base = "https://raw.githubusercontent.com/motian16/Metainfo/main"

    private fun item(json: String) = JSONObject(json)

    @Test
    fun `file 相对 index_json 拼成完整地址`() {
        assertEquals(
            base + "/plugins/qq.zip",
            RepoIndexUrls.pluginUrl(item("""{"file":"plugins/qq.zip"}"""), base)
        )
        // 前导斜杠不该拼出双斜杠。
        assertEquals(
            base + "/plugins/qq.zip",
            RepoIndexUrls.pluginUrl(item("""{"file":"/plugins/qq.zip"}"""), base)
        )
    }

    @Test
    fun `file 写完整 url 时原样用`() {
        val url = "https://example.com/a.zip"
        assertEquals(url, RepoIndexUrls.pluginUrl(item("""{"file":"$url"}"""), base))
    }

    @Test
    fun `插件包写绝对 url 时原样使用并且是可下载的 zip`() {
        // 现在的发布方式：插件包放在 GitHub Release 里，index.json 直接写完整地址。
        val url = "https://github.com/motian16/Metainfo/releases/download/v1/" +
            "com.metaaudio.artistimage.qq.zip"
        val resolved = RepoIndexUrls.pluginUrl(item("""{"url":"$url"}"""), base)
        assertEquals(url, resolved)
        assertTrue(resolved.startsWith("http"))
        assertTrue(resolved.endsWith(".zip"))
    }

    @Test
    fun `相对 file 拼成 index_json 所在目录下的地址`() {
        // 用一份小的内存 index 测相对路径这条规则本身：真实仓库现在是 Release
        // 绝对地址，但相对写法仍然要支持（老仓库、自建仓库都还在用）。
        val index = JSONObject(
            """{"schema":1,"name":"渊の插件源","plugins":[
                 {"id":"com.metaaudio.artistimage.qq","file":"plugins/qq.zip"}
               ]}"""
        )
        val entry = index.getJSONArray("plugins").getJSONObject(0)
        assertEquals(base + "/plugins/qq.zip", RepoIndexUrls.pluginUrl(entry, base))
    }

    @Test
    fun `规则条目支持字符串写法`() {
        val rules = RepoIndexUrls.rules(item("""{"rules":"rules/tag_rules.json"}"""), base)
        assertEquals(base + "/rules/tag_rules.json", rules.url)
        // 老写法没有校验值：照旧能用（不校验）。
        assertEquals("", rules.sha256)
    }

    @Test
    fun `规则条目支持带 sha256 的对象写法`() {
        val rules = RepoIndexUrls.rules(
            item("""{"rules":{"file":"rules/tag_rules.json","sha256":"deadbeef"}}"""),
            base
        )
        assertEquals(base + "/rules/tag_rules.json", rules.url)
        assertEquals("deadbeef", rules.sha256)
    }

    @Test
    fun `规则校验值可以写在顶层`() {
        val rules = RepoIndexUrls.rules(
            item("""{"rules":"rules/tag_rules.json","rulesSha256":"cafe"}"""),
            base
        )
        assertEquals("cafe", rules.sha256)
    }

    @Test
    fun `没有规则字段时返回空`() {
        assertEquals(RepoIndexUrls.Rules(), RepoIndexUrls.rules(item("""{"schema":1}"""), base))
        assertEquals(RepoIndexUrls.Rules(), RepoIndexUrls.rules(item("""{"rules":[]}"""), base))
        assertEquals(RepoIndexUrls.Rules(), RepoIndexUrls.rules(item("""{"rules":null}"""), base))
    }

    @Test
    fun `仓库名取自 index_json 顶层的 name`() {
        // 插件页「插件仓库」那一行的标题就是它（例如「渊の插件源」）。
        assertEquals("渊の插件源", RepoIndexUrls.repoName(item("""{"name":" 渊の插件源 "}""")))
        // 老仓库没写 name：返回空串，界面退回显示订阅地址。
        assertEquals("", RepoIndexUrls.repoName(item("""{"schema":1}""")))
    }

    @Test
    fun `多份 index_json 里挑 updatedAt 最新的那份`() {
        // 镜像缓存不一致时（jsDelivr 把 @main 钉在旧提交上），会同时拿到新旧两份，
        // 这里按仓库自己写的 updatedAt 比大小 —— ISO 日期比字典序就是比时间。
        assertEquals(
            1,
            RepoIndexUrls.newestIndexBy(listOf("2026-10-09", "2026-10-10"))
        )
        assertEquals(
            2,
            RepoIndexUrls.newestIndexBy(listOf("2026-08-01", "2026-10-09", "2026-10-10"))
        )
        // 老仓库没写 updatedAt（或全为空）时取第一份，行为跟以前一样。
        assertEquals(0, RepoIndexUrls.newestIndexBy(listOf("", "", "")))
        assertEquals(0, RepoIndexUrls.newestIndexBy(listOf("2026-10-10")))
        assertEquals(-1, RepoIndexUrls.newestIndexBy(emptyList()))
    }

    @Test
    fun `开头带 BOM 的 index_json 照样能解析`() {
        // 记事本 / Windows PowerShell 另存过的仓库文件会带 BOM，
        // 不先去掉的话 org.json 直接报"不是合法 JSON"，整次更新全废。
        val raw = "\uFEFF" + """{"name":"渊の插件源","plugins":[]}"""
        val root = JSONObject(RepoIndexUrls.stripBom(raw))
        assertEquals("渊の插件源", RepoIndexUrls.repoName(root))
        assertEquals(0, root.getJSONArray("plugins").length())
    }

    @Test
    fun `基准目录用用户填的地址而不是镜像地址`() {
        // 用镜像地址当基准会把插件包钉死在那条路上；用原地址才能再推导一遍候选，
        // 而"上次生效的镜像"由 MirrorFetch 记住，不会让直连白等超时。
        assertEquals(
            base,
            RepoIndexUrls.baseOf(
                "https://raw.githubusercontent.com/motian16/Metainfo/main/index.json",
                "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json"
            )
        )
        // 用户直接填本地文件时没有"候选"可言，只能用实际读到的那个路径。
        assertEquals(
            "/sdcard/repo",
            RepoIndexUrls.baseOf("/sdcard/repo/index.json", "/sdcard/repo/index.json")
        )
        // 只填到域名（没有路径）时退回实际下载成功的地址，免得拼出 "https:/" 这种东西。
        assertEquals(
            "https://cdn.jsdelivr.net/gh/u/r@main",
            RepoIndexUrls.baseOf("https://example.com", "https://cdn.jsdelivr.net/gh/u/r@main/index.json")
        )
    }

    @Test
    fun `仓库真实的 index_json 能解析出可下载的插件地址`() {
        // 这份用例只兜一件事：真实文件别解析不了。
        // **不断言它的具体形状**（插件几个、包放在 plugins/ 还是 Release 里都会变），
        // 地址怎么拼由上面那些内存用例负责测。
        val root = realIndex()
        val list = root.getJSONArray("plugins")
        assertTrue("插件列表不能是空的", list.length() >= 1)
        for (i in 0 until list.length()) {
            val entry = list.getJSONObject(i)
            // 合集包模式下的 index 只列元数据（没有 file/url），地址由 bundle 统一给出。
            if (!entry.has("file") && !entry.has("url")) continue
            val url = RepoIndexUrls.pluginUrl(entry, base)
            assertTrue("插件地址不能为空", url.isNotBlank())
            assertTrue("插件地址要以 http 开头：" + url, url.startsWith("http"))
        }
    }

    @Test
    fun `仓库真实的 index_json 是三份独立资源`() {
        // schema 2：元信息源（外链 Lyrico）、艺术家图源、标签规则各自带 sha256。
        val root = realIndex()
        val meta = RepoIndexUrls.meta(root, base)
        assertTrue("index.json 里必须有 meta", meta != null)
        assertTrue("元信息源要是 http：" + meta!!.url, meta.url.startsWith("http"))
        assertEquals("元信息源 sha256 必须是 64 位", 64, meta.sha256.length)
        assertTrue("meta 要列出上游插件 id", meta.pluginIds.isNotEmpty())

        val artist = RepoIndexUrls.artistImage(root, base)
        assertTrue("index.json 里必须有 artistImage", artist != null)
        assertTrue("艺术家图源要是 http：" + artist!!.url, artist.url.startsWith("http"))
        assertEquals("艺术家图源 sha256 必须是 64 位", 64, artist.sha256.length)

        val rules = RepoIndexUrls.rules(root, base)
        assertTrue("index.json 里必须有 rules", rules.url.startsWith("http"))
        assertEquals("规则 sha256 必须是 64 位", 64, rules.sha256.length)
    }

    /** 仓库里真实的 index.json（Gradle 跑单测时工作目录是模块目录 app/）。 */
    private fun realIndex(): JSONObject {
        val candidates = listOf(
            java.io.File("../../MetaAudio-Repo/index.json"),
            java.io.File("../MetaAudio-Repo/index.json"),
            java.io.File("MetaAudio-Repo/index.json"),
            java.io.File("../../salt/MetaAudio-Repo/index.json")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 MetaAudio-Repo/index.json，当前目录：" + java.io.File(".").absolutePath)
        return JSONObject(RepoIndexUrls.stripBom(file.readText()))
    }

    @Test
    fun `bundle 字段的两种写法都能解析成绝对地址`() {
        // 合集包是"一次订阅只下一次"的唯一来源，写错了 App 只会说"没有提供合集包"，
        // 所以两种写法都钉一下。
        // 这里复用文件顶部那个 base，别遮蔽它 —— 否则后面的人会以为测试用的是另一个地址。
        val short = JSONObject("""{"bundle":"bundle.zip"}""")
        assertEquals(base + "/bundle.zip", RepoIndexUrls.bundle(short, base)?.url)

        val full = JSONObject(
            """{"bundle":{"file":"bundle.zip","sha256":"abc123"}}"""
        )
        assertEquals(base + "/bundle.zip", RepoIndexUrls.bundle(full, base)?.url)
        assertEquals("abc123", RepoIndexUrls.bundle(full, base)?.sha256)

        // 直接给完整地址（合集放 Release 时用）
        val absolute = JSONObject(
            """{"bundle":{"url":"https://example.com/x/b.zip"}}"""
        )
        assertEquals("https://example.com/x/b.zip", RepoIndexUrls.bundle(absolute, base)?.url)

        // 没写就是 null：调用方据此直接回一句"这个订阅源没有提供合集包"
        assertNull(RepoIndexUrls.bundle(JSONObject("{}"), base))
        assertNull(RepoIndexUrls.bundle(JSONObject("""{"bundle":""}"""), base))
    }

    @Test
    fun `bundle 里的规则字段两种写法都能解析`() {
        // 规则也在订阅包里（一次订阅只下一个 zip）：包里那一份的路径由
        // bundle.rules 公布，写错了 App 会当"包里没有规则"并如实汇报，
        // 所以这里钉死。（校验值跟着整包走，不再单独解析 rulesSha256。）
        val releaseUrl = "https://github.com/motian16/Metainfo/releases/download/v2/" +
            "MetaAudio-Subscription.zip"
        val text = JSONObject(
            """{"bundle":{"url":"$releaseUrl","sha256":"aa",
                 "rules":"rules/tag_rules.json","rulesSha256":"bb"}}"""
        )
        val bundle = RepoIndexUrls.bundle(text, base)
        assertEquals(releaseUrl, bundle?.url)
        assertEquals("aa", bundle?.sha256)
        assertEquals("rules/tag_rules.json", bundle?.rules)

        // 对象写法（和 rules 条目同一套字段名）。
        val objectStyle = JSONObject(
            """{"bundle":{"file":"b.zip","rules":{"file":"rules/tag_rules.json","sha256":"cc"}}}"""
        )
        assertEquals("rules/tag_rules.json", RepoIndexUrls.bundle(objectStyle, base)?.rules)

        // 老仓库（只有 bundle 路径、没有规则字段）：路径是空串。
        assertEquals("", RepoIndexUrls.bundle(JSONObject("""{"bundle":"bundle.zip"}"""), base)?.rules)
    }

    @Test
    fun `图标条目只取整批校验值`() {
        // 平台图标（元信息页每个分组左边那张图）跟着订阅包走：包里是 icons/<名字>.png。
        // App 不再按 files[] 里的地址逐个下载，所以只解析整批的 sha256 ——
        // 它用来判断"本地有没有这一批图"。
        val icons = RepoIndexUrls.icons(
            item(
                """{"icons":{"sha256":"aa","files":[
                     {"file":"icon/bilibili.png","sha256":"bb"},
                     {"file":"https://example.com/x/qq.png"}
                   ]}}"""
            )
        )
        assertEquals("aa", icons.sha256)
    }

    @Test
    fun `没有 icons 字段时就是没有图标`() {
        // 老仓库没有这一节：界面继续用占位图，不能因为少了它报错。
        assertEquals("", RepoIndexUrls.icons(item("""{"schema":1}""")).sha256)
        assertEquals("", RepoIndexUrls.icons(item("""{"icons":{}}""")).sha256)
    }

    @Test
    fun `仓库真实的 index_json 公布了平台图标`() {
        // 「元信息」页的哔哩哔哩分组要有图标，靠的就是订阅里这一批；
        // 整批 sha256 是"这一批图换没换"的判据。
        val icons = RepoIndexUrls.icons(realIndex())
        assertEquals("图标整批 sha256 要是 64 位", 64, icons.sha256.length)
    }
}
