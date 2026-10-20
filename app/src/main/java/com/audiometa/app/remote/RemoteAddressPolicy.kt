package com.audiometa.app.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.audiometa.app.data.db.RemoteAddressEntity
import com.audiometa.app.data.prefs.RemoteControlPrefs

/**
 * 按**当前网络类型**挑选该连哪个地址。
 *
 * 用户的使用场景是两种：
 *   - 在家里：手机和电脑同一个 Wi-Fi → 用**局域网地址**直连，最快；
 *   - 出门：手机走移动网络 → 局域网地址根本不可达，必须用**广域网地址**
 *     （公网 IPv4，或全局 IPv6 —— 后者很多家宽都有且不需要端口映射）。
 *
 * 另外用户明确要求：**指定了域名就只用域名**，不再去试这些地址。
 *
 * 优先级：
 *   1. 指定了域名 → 只连它（这是用户明确的选择，尊重它）；
 *   2. Wi-Fi → lan → ipv6 → pub；
 *   3. 移动网络 / 其他 → pub → ipv6 → lan（局域网放最后只当兜底，
 *      因为有些"移动网络"其实是热点，热点下局域网地址反而可用）。
 *
 * 读的是系统网络类型，不看 IP 段 —— VPN / 热点 / 双卡这些情况
 * 靠 IP 段猜会错，系统给的才是真的。
 */
object RemoteAddressPolicy {

    /** 当前网络类型。 */
    enum class Transport { WIFI, CELLULAR, OTHER }

    fun currentTransport(context: Context): Transport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Transport.OTHER
        val network = cm.activeNetwork ?: return Transport.OTHER
        val caps = cm.getNetworkCapabilities(network) ?: return Transport.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            // 以太网/蓝牙等按"像局域网"处理
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.WIFI
            else -> Transport.OTHER
        }
    }

    /**
     * 把库里的地址排成"该按什么顺序试"。
     *
     * @param specifiedDomain 用户填的域名/IP；非空时**只返回它**。
     */
    fun order(
        context: Context,
        addresses: List<RemoteAddressEntity>,
        specifiedDomain: RemoteControlPrefs?
    ): List<RemoteControlPrefs> {
        // 指定了域名就只用它
        if (specifiedDomain != null && specifiedDomain.hasTarget) {
            return listOf(specifiedDomain)
        }

        val transport = currentTransport(context)
        val rank: (RemoteAddressEntity) -> Int = when (transport) {
            Transport.WIFI -> { a ->
                when (a.kind) {
                    "lan" -> 0
                    "ipv6" -> 1
                    "pub" -> 2
                    else -> 3
                }
            }
            Transport.CELLULAR -> { a ->
                when (a.kind) {
                    "pub" -> 0
                    "ipv6" -> 1
                    // 移动网络下局域网地址几乎必然不可达，放最后当兜底
                    "lan" -> 3
                    else -> 4
                }
            }
            Transport.OTHER -> { a ->
                when (a.kind) {
                    "lan" -> 0
                    "ipv6" -> 1
                    "pub" -> 2
                    else -> 3
                }
            }
        }

        return addresses
            .sortedBy(rank)
            .map { a ->
                RemoteControlPrefs(
                    enabled = true,
                    host = a.host,
                    port = a.port,
                    useTls = false
                )
            }
            .distinctBy { it.host to it.port }
    }
}
