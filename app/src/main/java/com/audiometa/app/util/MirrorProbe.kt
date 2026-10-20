package com.audiometa.app.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * 单个镜像的连通测试（「设置 - 插件 - 网络与镜像」里那个「测试」用）。
 *
 * 只回答一个问题：**这个镜像多久回话**。所以判据比 [RemoteText] 松 ——
 * 任何 HTTP 状态码都算通：连得上、答得快才是排序要的信息，
 * 内容对不对是真正下载时 [RemoteText] 的事。只有连不上、或者超时才返回 null。
 */
object MirrorProbe {

    /** 每个镜像的测试超时（6 秒）：一轮要测六个，等太久用户没耐心。 */
    const val TIMEOUT_MS = 6_000

    /**
     * 测一个中转前缀，返回耗时毫秒；超时或连不上返回 null。
     *
     * 配了要下载的地址（[target]）时按"前缀 + 原地址"拼出真实下载会用的地址，
     * 所以测出来的快慢就是下载时的快慢；没配就只探前缀本身。
     */
    fun latency(prefix: String, target: String = ""): Long? {
        val address = probeUrl(prefix, target) ?: return null
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "AudioMeta")
            connection.setRequestProperty("Accept", "*/*")
            connection.connect()
            // 读超时按**剩下的**时间算：连上之后对端一直不回话的话，
            // 一次尝试的总时长也不超过 6 秒（否则连 6 秒 + 读 6 秒就是 12 秒）。
            val left = TIMEOUT_MS - (System.currentTimeMillis() - started)
            if (left <= 0) return null
            connection.readTimeout = left.toInt().coerceAtLeast(1)
            // 拿到响应头就算一次往返，不读正文：这里测的是"快不快"，不是内容。
            connection.responseCode
            System.currentTimeMillis() - started
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 真正去请求的地址；前缀本身不是 http 地址就返回 null（不测它）。 */
    private fun probeUrl(prefix: String, target: String): String? {
        val base = prefix.trim()
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return null
        val normalized = if (base.endsWith("/")) base else base + "/"
        val address = target.trim()
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            return normalized
        }
        // 拼法和下载时一致：先问 RemoteText 这一条前缀会拼出什么地址
        // （`…/gh/` 那种 CDN 前缀的拼法与通用中转不同），问不出来就按通用中转拼。
        return RemoteText.candidates(address, listOf(normalized))
            .firstOrNull { it.mirror.trimEnd('/') == normalized.trimEnd('/') }
            ?.url
            ?: (normalized + address)
    }
}
