package com.audiometa.app.remote

import android.content.Context
import android.net.wifi.WifiManager
import com.audiometa.app.data.prefs.RemoteControlPrefs
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网自动发现：监听插件发来的 UDP 广播，省掉手输 IP。
 *
 * 和插件侧 [com.example.spwremote.DiscoveryService] 对应。流程：
 *   1. 绑定 UDP 发现端口；
 *   2. 发一条 `{"t":"probe"}` 广播 —— 插件收到会**立刻单播回应**，
 *      所以不用赌周期性广播（5 秒一次）的相位，进页面通常几百毫秒就有结果；
 *   3. 在时间窗内收集所有 beacon，按地址类型排序返回。
 *
 * 地址优先级（[DiscoveredEndpoint.priority]）：
 *   lan  (0) 同网段直连，延迟最低
 *   ipv6 (1) 全局 IPv6，很多家宽都有，公网可直达且不用端口映射
 *   pub  (2) 公网 IPv4，需要路由器把端口映射进来才通，最后试
 */
object LanDiscovery {

    private const val TAG = "SpwDiscovery"
    private const val MAX_PACKET = 4 * 1024

    /** 一次发现的结果。 */
    data class Result(
        /** 找到的插件（按 beacon 里的 plugin id 去重）。 */
        val devices: List<DiscoveredDevice>,
        /** 扫描过程中出现的异常，用于界面提示。 */
        val error: String? = null
    ) {
        val isEmpty: Boolean get() = devices.isEmpty()
    }

    data class DiscoveredDevice(
        val name: String,
        val pluginId: String,
        val version: String,
        val wsPort: Int,
        val endpoints: List<DiscoveredEndpoint>,
        /** 广播包来自哪个地址；广播里不带地址时就用它连。 */
        val seenFrom: String,
        /** 插件是否要求配对。默认 true（新插件都要求）。 */
        val requiresAuth: Boolean = true,
        /**
         * 插件在广播里交接的配对码。
         *
         * 「局域网免配对」开启时才有值。有了它，手机发现设备后可以直接连上，
         * 用户不用手打配对码；仍然会把它存下来并显示在设置里，
         * 方便用户告诉第二台设备。
         */
        val handedToken: String? = null
    ) {
        /** 按优先级排好序的连接候选。 */
        val orderedEndpoints: List<DiscoveredEndpoint>
            get() = endpoints.sortedBy { it.priority }

        /** 最适合先试的那一个。 */
        val best: DiscoveredEndpoint? get() = orderedEndpoints.firstOrNull()
    }

    data class DiscoveredEndpoint(val host: String, val kind: String) {
        val priority: Int
            get() = when (kind) {
                "lan" -> 0
                "ipv6" -> 1
                "pub" -> 2
                else -> 3
            }

        val label: String
            get() = when (kind) {
                "lan" -> "局域网"
                "ipv6" -> "IPv6"
                "pub" -> "公网"
                else -> kind
            }

        /**
         * 这个地址是不是公网可路由的。
         *
         * 界面用它决定要不要标「非公网」—— 用户看这两个字就知道
         * "这个地址只能在家里的网用"。
         *
         * 插件报 `kind=lan` 的直接算私有；`kind=pub` 的还要再验一遍
         * （插件那边的探测依赖外部服务，偶尔会返回运营商内网地址）。
         * IPv6 按前缀判断唯一本地地址 fc00::/7。
         */
        val isPublic: Boolean
            get() = when (kind) {
                "lan" -> false
                "pub" -> isPublicIpv4(host)
                "ipv6" -> !isUniqueLocalIpv6(host)
                else -> false
            }
    }

    /**
     * 判断 IPv4 是否公网。
     *
     * 覆盖 RFC1918 私有段、运营商级 NAT（100.64/10）、回环、链路本地、
     * 以及其它保留段。只要落在这些里面就返回 false —— 保守一点，
     * 宁可多标几个「非公网」，也不要把内网地址说成公网。
     */
    fun isPublicIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        val (a, b) = o[0] to o[1]
        return when {
            a == 10 -> false                      // 10/8 私有
            a == 172 && b in 16..31 -> false      // 172.16/12 私有
            a == 192 && b == 168 -> false         // 192.168/16 私有
            a == 100 && b in 64..127 -> false     // 100.64/10 运营商 NAT（CGNAT）
            a == 127 -> false                     // 回环
            a == 169 && b == 254 -> false         // 链路本地
            a == 0 -> false
            a >= 224 -> false                     // 组播 / 保留
            a == 192 && b == 0 -> false           // 192.0.0/24、192.0.2/24
            a == 198 && b in 18..19 -> false      // 基准测试网段
            a == 198 && b == 51 -> false          // 192.0.2 类似的文档段
            a == 203 && b == 0 -> false           // 文档段
            else -> true
        }
    }

    /** fc00::/7 唯一本地地址，外网不可路由。 */
    fun isUniqueLocalIpv6(host: String): Boolean {
        val h = host.lowercase()
        return h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe80") || h == "::1" || h.startsWith("::")
    }

    /**
     * 扫描一轮。
     *
     * @param durationMs 收集时间窗。默认 2.2 秒 —— probe 的回应通常在 100ms 内到，
     *        剩下的时间留给"插件只做周期广播、没响应 probe"的情况。
     *        这是**一次性**扫描，不会持续转圈（用户明确要求过）。
     * @param udpPort 发现端口，要和插件配置里的 remote.udpPort 一致。
     */
    suspend fun scan(
        context: Context,
        udpPort: Int = UDP_PORT,
        durationMs: Long = 2_200L
    ): Result = withContext(Dispatchers.IO) {
        val devices = LinkedHashMap<String, DiscoveredDevice>()
        var error: String? = null

        // 某些机型（尤其是开了省电模式的三星/小米）会丢掉发给本进程的广播包，
        // 拿一个 MulticastLock 能显著提高收到率。失败也不致命。
        val multicastLock = acquireMulticastLock(context)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(udpPort))
                soTimeout = 250
            }

            sendProbe(socket, udpPort)

            val deadline = System.currentTimeMillis() + durationMs
            val buf = ByteArray(MAX_PACKET)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    error = t.message
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                val parsed = parseBeacon(text, packet.address?.hostAddress.orEmpty()) ?: continue

                // 同一个插件可能从多个网卡被收到（比如同时有虚拟网卡），
                // 合并端点而不是互相覆盖。
                val existing = devices[parsed.pluginId]
                devices[parsed.pluginId] = if (existing == null) {
                    parsed
                } else {
                    existing.copy(
                        endpoints = (existing.endpoints + parsed.endpoints).distinctBy { it.host }
                    )
                }
            }
        } catch (t: Throwable) {
            error = when {
                t.message?.contains("EADDRINUSE", true) == true ->
                    "发现端口被占用，无法扫描（可改端口或手动填写地址）"
                else -> t.message ?: t.javaClass.simpleName
            }
            AppLog.w(TAG, "扫描失败: ${t.message}")
        } finally {
            runCatching { socket?.close() }
            runCatching { multicastLock?.release() }
        }

        Result(devices.values.toList(), error)
    }

    /** 向所有可能的广播地址发探测包。 */
    private fun sendProbe(socket: DatagramSocket, udpPort: Int) {
        val payload = """{"t":"probe"}""".toByteArray(Charsets.UTF_8)

        // 定向广播地址（x.y.z.255）比 255.255.255.255 更容易穿过家用路由器，
        // 所以两个都发。Android 上拿不到 InterfaceAddress.broadcast（被裁掉了），
        // 这里自己按 /24 推算。
        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val parts = (addr.hostAddress ?: continue).split('.')
                        if (parts.size == 4) {
                            runCatching {
                                targets.add(InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255"))
                            }
                        }
                    }
                }
            }
        }
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }

        for (t in targets) {
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, t, udpPort))
            }
        }
        AppLog.d(TAG, "已向 ${targets.size} 个广播地址发送探测")
    }

    /** 解析一条 beacon；不是我们的包返回 null。 */
    private fun parseBeacon(text: String, from: String): DiscoveredDevice? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("t") != "spw-remote") return null

        val wsPort = json.optInt("wsPort", 0).takeIf { it in 1..65535 } ?: return null
        val pluginId = json.optString("plugin").ifBlank { "spw-remote" }

        /*
         * 新版本插件的广播**不带地址列表**（地址是配对成功后才下发的，
         * 免得把"这台机器开在哪"广播给同网段所有人）。
         * 这种情况下用**收到包的来源地址**当连接地址 ——
         * 同网段必然可达，首次配对足够用。
         *
         * 仍然兼容老插件：它广播里带 eps，就照着解析。
         */
        val epsArray = json.optJSONArray("eps")
        val eps = ArrayList<DiscoveredEndpoint>(4)
        if (epsArray != null) {
            for (i in 0 until epsArray.length()) {
                val o = epsArray.optJSONObject(i) ?: continue
                val host = o.optString("host").trim()
                val kind = o.optString("kind").trim()
                if (host.isNotEmpty()) eps.add(DiscoveredEndpoint(host, kind))
            }
        }
        if (eps.isEmpty() && from.isNotBlank()) {
            eps.add(DiscoveredEndpoint(from, "lan"))
        }

        return DiscoveredDevice(
            name = json.optString("name").ifBlank { "Salt Player" },
            pluginId = pluginId,
            version = json.optString("version"),
            wsPort = wsPort,
            endpoints = eps.distinctBy { it.host },
            seenFrom = from,
            requiresAuth = json.optBoolean("auth", true),
            // 免配对模式下插件会把配对码放在广播里，用它就能直接连，
            // 用户一个字符都不用打
            handedToken = json.optString("token").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    /** 发现端口默认值，和插件 RemotePlugin.DEFAULT_UDP_PORT 一致。 */
    const val UDP_PORT = 52101

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("spw-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
}

/**
 * 把发现到的设备转成"可尝试的连接候选"。
 *
 * 顺序：先插件自己报的地址（按 lan → ipv6 → pub 优先级），
 * 最后补上"发广播来的那个地址" —— 有些环境下 beacon 里的网卡列表
 * 可能不包含与手机真正互通的那一个，用来源地址兜底往往能救回来。
 */
fun LanDiscovery.DiscoveredDevice.toCandidates(): List<RemoteControlPrefs> {
    val out = ArrayList<RemoteControlPrefs>()

    for (ep in orderedEndpoints) {
        out.add(
            RemoteControlPrefs(
                enabled = true,
                host = ep.host,
                port = wsPort,
                // 插件给的是明文 ws 端口（除非用户自己架了反代），保持非 TLS
                useTls = false
            )
        )
    }

    if (seenFrom.isNotBlank() && out.none { it.host == seenFrom }) {
        out.add(
            RemoteControlPrefs(
                enabled = true,
                host = seenFrom,
                port = wsPort,
                useTls = false
            )
        )
    }

    return out.distinctBy { it.host to it.port }
}
