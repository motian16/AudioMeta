package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.remote.LanDiscovery

/**
 * 「设置 - 网络遥控 - 地址列表」。
 *
 * 只做一件事：把**当前连上的这台设备**有哪些可用地址列出来，
 * 按网络类型分组（局域网 / IPv6 / 公网 IPv4），每条右边一个复制按钮。
 *
 * ## 地址是配对之后才拿到的
 *
 * 插件的发现广播**故意不带地址**（否则等于把"这台机器开在哪"广播给
 * 同网段所有人）。地址是配对成功后由插件通过 WebSocket 下发的。
 * 所以没配对成功时这一页只会提示去填配对码 —— 这不是 bug，是设计。
 */
@Composable
fun RemoteDevicesSection(settings: AppSettings, vm: SettingsViewModel) {
    val connection by ServiceLocator.remoteControl.state.collectAsState()
    val addresses by ServiceLocator.remoteControl.addresses.collectAsState()
    val activeEndpoint by ServiceLocator.remoteControl.activeEndpoint.collectAsState()
    val activeDeviceName by vm.activeDeviceName.collectAsState()
    val remotePrefs by vm.remotePrefs.collectAsState()
    val clipboard = LocalClipboardManager.current

    // 还没配对成功：只提示怎么拿到地址，不显示任何地址
    if (!connection.isLive) {
        SettingsGroup("地址列表") {
            SettingsRow(
                title = "需要先配对",
                summary = "配对成功后显示地址；先回上一页填配对码",
                onClick = { vm.openSection(SettingsSection.REMOTE) }
            )
        }
        SettingsNote(
            when {
                connection.needsToken -> "还没有填配对码。"
                connection is com.audiometa.app.data.prefs.RemoteConnectionState.Unauthorized ->
                    "配对码不对，检查电脑上的插件配置。"
                else -> "还没有连上电脑。点上面的「查找」扫描。"
            }
        )
        return
    }

    if (addresses.isEmpty()) {
        SettingsGroup("地址列表") {
            SettingsRow(
                title = "正在获取…",
                summary = "已配对，正在向插件查询可用地址",
                onClick = { ServiceLocator.remoteControl.requestAddresses() }
            )
        }
        return
    }

    // 按网络类型分组：局域网 → IPv6 → 公网
    val groups = listOf(
        "lan" to "局域网",
        "ipv6" to "IPv6",
        "pub" to "公网 IPv4"
    )

    val deviceTitle = activeDeviceName.ifBlank { "已连接设备" }

    for ((kind, title) in groups) {
        val items = addresses.filter { it.kind == kind }
        if (items.isEmpty()) continue

        // 只有顶部「局域网」组标题行右侧放开关：标记这台电脑的内网 IP 固定不变。
        // 不新增任何列表项 —— 开关就是这一组标题的一部分。
        val groupSummary = if (kind == "lan" && remotePrefs.fixedLan) {
            "$deviceTitle · 固定内网 IP · 开机直连"
        } else {
            "$deviceTitle · ${items.size} 个"
        }
        SettingsGroup(
            title = title,
            summary = groupSummary,
            trailing = if (kind == "lan") {
                {
                    // 已存的固定地址还在当前列表里就沿用，否则取第一条局域网地址
                    val host = remotePrefs.fixedLanHost.takeIf { stored ->
                        stored.isNotBlank() && items.any { it.host == stored }
                    } ?: items.first().host
                    Switch(
                        checked = remotePrefs.fixedLan,
                        onCheckedChange = { on -> vm.setFixedLan(on, host) },
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }
            } else null
        ) {
            items.forEachIndexed { index, addr ->
                if (index > 0) SettingsDivider()
                AddressRow(
                    address = "${addr.host}:${addr.port}",
                    // IPv6 很长，用等宽字体好认
                    mono = addr.kind == "ipv6",
                    current = activeEndpoint.contains(addr.host),
                    isPublic = addr.isPublic,
                    onCopy = { clipboard.setText(AnnotatedString("${addr.host}:${addr.port}")) }
                )
            }
        }
    }
}

/**
 * 一行地址：地址文本 + 复制按钮。
 *
 * 没有"使用"按钮 —— 一键切换地址会让用户搞不清当前连的是哪条。
 * 想换地址就复制出来填到「手动域名」里，行为明确。
 */
@Composable
private fun AddressRow(
    address: String,
    mono: Boolean,
    current: Boolean,
    isPublic: Boolean,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 只对需要解释的情况给一个字标注
            if (current || !isPublic) {
                Text(
                    if (current) "当前" else "非公网",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(
            onClick = onCopy,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "复制",
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
