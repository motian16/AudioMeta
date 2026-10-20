package com.audiometa.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像候选推导 + sha256 校验的单元测试。
 *
 * 这两块都是**纯函数**（不碰网络、不碰 Android），所以能在电脑上直接测。
 * 值得测的原因是：候选顺序错了、hash 比较写错了，用户看到的表现都是
 * "下载失败" —— 而真正的原因在这些推导里，装到手机上试是试不出来的。
 */
class RemoteTextTest {

    private val rawIndex = "https://raw.githubusercontent.com/motian16/Metainfo/main/index.json"

    private val defaultProxies = listOf("", "https://gh-proxy.com/", "https://ghfast.top/")

    private fun urls(address: String, proxies: List<String> = defaultProxies): List<String> =
        RemoteText.candidates(address, proxies).map { it.url }

    @Test
    fun `raw 地址的候选顺序：原地址、四个 jsDelivr 入口、第三方中转`() {
        assertEquals(
            listOf(
                rawIndex,
                "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://gcore.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://testingcf.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://gh-proxy.com/" + rawIndex,
                "https://ghfast.top/" + rawIndex
            ),
            urls(rawIndex)
        )
    }

    @Test
    fun `raw 地址也拼第三方中转前缀`() {
        /*
         * 以前这里断言"raw 不拼中转"（理由是 jsDelivr 兜着够用）。
         * 现实否掉了这条理由：jsDelivr 会把分支名 `@main` 长期钉在旧提交上，
         * 用户因此拿到过期的 index.json（条目全 404）和上一版规则。
         * 中转是直连原站的，能拿到当前内容，所以必须留作候选。
         */
        val list = urls(rawIndex)
        assertTrue("raw 地址要保留中转候选：" + list, list.any { it.contains("gh-proxy.com") })
        assertTrue(list.any { it.contains("ghfast.top") })
    }

    @Test
    fun `api 地址的候选顺序：原地址、各中转前缀`() {
        val api = "https://api.github.com/repos/Replica0110/Lyrico-Plugins/releases/latest"
        assertEquals(
            listOf(
                api,
                "https://gh-proxy.com/" + api,
                "https://ghfast.top/" + api
            ),
            urls(api)
        )
    }

    @Test
    fun `github 页面地址既换 jsDelivr 也拼中转`() {
        val blob = "https://github.com/motian16/Metainfo/blob/main/plugins/qq.zip"
        assertEquals(
            listOf(
                blob,
                "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/plugins/qq.zip",
                "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/plugins/qq.zip",
                "https://gcore.jsdelivr.net/gh/motian16/Metainfo@main/plugins/qq.zip",
                "https://testingcf.jsdelivr.net/gh/motian16/Metainfo@main/plugins/qq.zip",
                "https://gh-proxy.com/" + blob,
                "https://ghfast.top/" + blob
            ),
            urls(blob)
        )
    }

    @Test
    fun `非 GitHub 地址只有原地址一个候选`() {
        val other = "https://example.com/index.json"
        assertEquals(listOf(other), urls(other))
    }

    @Test
    fun `jsDelivr 地址会把同一份内容在其它入口再试一遍`() {
        val cdn = "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json"
        assertEquals(
            listOf(
                cdn,
                "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://gcore.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://testingcf.jsdelivr.net/gh/motian16/Metainfo@main/index.json"
            ),
            urls(cdn)
        )
    }

    @Test
    fun `兼容 jsDelivr 的自建镜像地址也能换到公开入口`() {
        // 判据是 `…/gh/` 这段路径而不是域名：国内一堆加速站就是 jsDelivr 的兼容实现。
        val selfHosted = "https://jsd.example.com/gh/motian16/Metainfo@main/index.json"
        assertEquals(
            listOf(
                selfHosted,
                "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://gcore.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://testingcf.jsdelivr.net/gh/motian16/Metainfo@main/index.json"
            ),
            urls(selfHosted)
        )
    }

    @Test
    fun `记住的镜像排到最前`() {
        val candidates = RemoteText.candidates(
            rawIndex, defaultProxies,
            preferred = "https://fastly.jsdelivr.net/gh/"
        )
        assertEquals(
            "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
            candidates.first().url
        )
        // 其余候选的相对顺序不变。
        assertEquals(rawIndex, candidates[1].url)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
            candidates[2].url
        )
    }

    @Test
    fun `候选数量有上限`() {
        // 镜像表是用户可编辑的，写长了也不该让一次下载发几十个请求。
        val blob = "https://github.com/motian16/Metainfo/blob/main/index.json"
        val many = listOf("") + (1..7).map { "https://p$it.example/" }
        val candidates = RemoteText.candidates(blob, many)
        assertEquals(8, candidates.size)
        // 原地址和 4 个 jsDelivr 一定在里面，被截掉的是排在最后的那几个中转。
        assertEquals(blob, candidates.first().url)
        assertTrue(candidates.any { it.url.contains("cdn.jsdelivr.net") })
    }

    @Test
    fun `前缀不写结尾斜杠也能拼对`() {
        val api = "https://api.github.com/repos/x/y/releases/latest"
        assertEquals(
            listOf(api, "https://gh-proxy.com/" + api),
            urls(api, listOf("https://gh-proxy.com"))
        )
    }

    @Test
    fun `jsDelivr 形式的前缀按 gh 形式拼`() {
        // 用户把前缀填成 `https://mirror.example/gh/` 时，它要的是 user/repo@ref/path，
        // 直接接原地址会拼出一串废地址（只有同内容的 CDN 才这么用）。
        assertEquals(
            listOf(
                rawIndex,
                "https://cdn.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://fastly.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://gcore.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://testingcf.jsdelivr.net/gh/motian16/Metainfo@main/index.json",
                "https://mirror.example/gh/motian16/Metainfo@main/index.json"
            ),
            urls(rawIndex, listOf("https://mirror.example/gh/"))
        )

        // 拆不出 gh 形式的地址（api.github.com 没有等价内容）就跳过这个前缀。
        val api = "https://api.github.com/repos/x/y/releases/latest"
        assertEquals(listOf(api), urls(api, listOf("https://mirror.example/gh/")))
    }

    @Test
    fun `本地路径没有候选`() {
        assertTrue(RemoteText.candidates("/sdcard/tag_rules.json", defaultProxies).isEmpty())
        assertTrue(RemoteText.candidates("file:///sdcard/tag_rules.json", defaultProxies).isEmpty())
    }

    @Test
    fun `镜像键翻成给用户看的名字`() {
        assertEquals("直连", RemoteText.mirrorLabel(""))
        assertEquals("cdn.jsdelivr.net", RemoteText.mirrorLabel("https://cdn.jsdelivr.net/gh/"))
        assertEquals("gh-proxy.com", RemoteText.mirrorLabel("https://gh-proxy.com/"))
    }

    @Test
    fun `失败信息按候选逐个说清楚`() {
        val direct = RemoteText.Attempt(
            RemoteText.Candidate(rawIndex, RemoteText.DIRECT, "直连"), false, RemoteText.DETAIL_TIMEOUT
        )
        val cdn = RemoteText.Attempt(
            RemoteText.Candidate("u", "https://cdn.jsdelivr.net/gh/", "cdn.jsdelivr.net"),
            false, RemoteText.DETAIL_TIMEOUT
        )
        val proxy = RemoteText.Attempt(
            RemoteText.Candidate("u", "https://gh-proxy.com/", "gh-proxy.com"), false, "HTTP 502"
        )
        assertEquals("直连超时", direct.summary())
        assertEquals("cdn.jsdelivr.net 超时", cdn.summary())
        assertEquals("gh-proxy.com HTTP 502", proxy.summary())
    }

    @Test
    fun `sha256 比较大小写无关`() {
        val bytes = "hello".toByteArray()
        val hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(hash, RemoteText.sha256Hex(bytes))
        assertTrue(RemoteText.matches(bytes, hash))
        assertTrue(RemoteText.matches(bytes, hash.uppercase()))
        assertTrue(RemoteText.matches(bytes, "sha256:" + hash))
        assertFalse(RemoteText.matches(bytes, "0".repeat(64)))
    }

    @Test
    fun `没写 sha256 时不校验`() {
        val bytes = "whatever".toByteArray()
        assertNull(RemoteText.normalizeHash(""))
        assertNull(RemoteText.normalizeHash(null))
        // 期望值为空 → 一律算通过（老 index.json 没有这个字段，必须照旧能用）。
        assertTrue(RemoteText.matches(bytes, ""))
    }

    @Test
    fun `写法不对的 sha256 当没写`() {
        // 少一位 / 混了非十六进制字符：判成"校验失败"会让所有人永远下载不了，
        // 而 hash 不是签名，没必要为格式问题把功能锁死。
        assertNull(RemoteText.normalizeHash("abc123"))
        assertNull(RemoteText.normalizeHash("z".repeat(64)))
        assertEquals(
            "a".repeat(64),
            RemoteText.normalizeHash("  " + "A".repeat(64) + "  ")
        )
    }
}
