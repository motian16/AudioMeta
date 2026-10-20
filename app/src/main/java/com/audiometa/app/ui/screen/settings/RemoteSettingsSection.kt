package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.data.prefs.RemoteControlPrefs
import com.audiometa.app.di.ServiceLocator

/**
 * 「设置 - 网络遥控」。
 *
 * ## 未启用时只显示一个开关
 *
 * 关着的时候这一页**只有顶部那个开关**，不摆用不上的选项。
 *
 * ## 启用后的页面结构（从上到下）
 *
 * 总开关 → 连接状态卡 → 查找/地址列表按钮 → 说明小字 → 配对码
 * → 手动指定地址 → 行为 → 电脑端端口说明。
 *
 * 每个功能板块一张低对比度灰卡（[SettingsGroup] 的默认底色），模块之间
 * 留白均匀；分组标题加粗、说明小字降透明度（[RemoteNote]），主次分明。
 * 排版走现代播放器的干净简约风：深色背景、柔和圆角、开关与文字全部保留。
 */
@Composable
fun RemoteSettingsSection(settings: AppSettings, vm: SettingsViewModel) {
    val remote by vm.remotePrefs.collectAsState()
    val context = LocalContext.current
    // 保活服务是一条静音状态通知；Android 13+ 需要通知权限才看得见（不给也能跑）。
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    fun setEnabled(value: Boolean) {
        vm.setRemoteEnabled(value)
        if (value && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------- 未启用：只有一个开关 ----------------
    if (!remote.enabled) {
        SettingsGroup {
            SettingsSwitchRow(
                title = "启用网络遥控",
                checked = false,
                onChecked = { setEnabled(it) }
            )
        }
        return
    }

    val connection by ServiceLocator.remoteControl.state.collectAsState()
    val activeEndpoint by ServiceLocator.remoteControl.activeEndpoint.collectAsState()
    val activeKind by ServiceLocator.remoteControl.activeKind.collectAsState()
    val activeDeviceName by vm.activeDeviceName.collectAsState()
    val scanning by vm.remoteScanning.collectAsState()
    val devices by vm.remoteDevices.collectAsState()
    val lastError by ServiceLocator.remoteControl.lastError.collectAsState()

    // ---------------- 顶部总开关 ----------------
    SettingsGroup {
        SettingsSwitchRow(
            title = "启用网络遥控",
            checked = true,
            onChecked = { setEnabled(it) }
        )
    }

    // ---------------- 连接状态：已连接设备信息卡片 ----------------
    SettingsGroup(title = "连接状态") {
        SettingsRow(
            title = when (val c = connection) {
                is RemoteConnectionState.Connected -> activeDeviceName.ifBlank { "已连接" }
                is RemoteConnectionState.Unauthorized -> "需要配对码"
                is RemoteConnectionState.Failed -> "连接失败"
                else -> if (c.isBusy) "连接中…" else "未连接"
            },
            summary = when (val c = connection) {
                is RemoteConnectionState.Connected -> buildString {
                    if (activeKind.isNotBlank()) append(activeKind).append(" · ")
                    append(activeEndpoint.ifBlank { remote.endpoint })
                }
                is RemoteConnectionState.Unauthorized -> c.reason
                is RemoteConnectionState.Failed -> c.reason
                else -> lastError.ifBlank { "点「查找」扫描" }
            }
        ) {
            Text(
                connection.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (connection.isLive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ---------------- 查找设备 / 地址列表：并排按钮 ----------------
    // 「查找」是一次性的（约 2 秒），已连接时不会打断连接。
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { vm.refreshRemote() },
                enabled = !scanning,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (scanning) "查找中…" else "查找设备")
            }
            OutlinedButton(
                onClick = { vm.openSection(SettingsSection.DEVICES) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (devices.isEmpty()) "地址列表" else "地址列表（${devices.size}）")
            }
        }
    }
    RemoteNote(
        "同一 Wi-Fi 下自动发现，通常不用手填地址。" +
            "通常不需要手填地址。"
    )

    // ---------------- 配对码 ----------------
    SettingsGroup(
        title = "配对码",
        summary = "电脑端插件配置页里的配对码"
    ) {
        SettingsTextField(
            label = "配对码",
            value = remote.token,
            supporting = "电脑端开启免配对后会自动带入",
            onValueChange = { vm.setRemoteToken(it) }
        )
    }

    // ---------------- 指定地址：一行三段 ----------------
    /*
     * 和插件的实际形态对齐：插件是裸 WebSocket，默认端口 52100（不是 80），
     * 所以非加密档显示 ws + 52100；只有走隧道/公网 TLS 时才是 wss + 443。
     */
    SettingsGroup(
        title = "手动指定地址",
        summary = "自动发现失败或跨网段时使用"
    ) {
        DomainInputRow(
            useTls = remote.useTls,
            host = remote.host,
            port = remote.port,
            onTls = { vm.setRemoteTls(it) },
            onHost = { vm.setRemoteHost(it) },
            onPort = { vm.setRemotePort(it) }
        )
    }

    // ---------------- 行为 ----------------
    // 「同步播放本机音频」不放在这里：控制页左上角有同样的快速开关，
    // 设置页只留连接相关的行为项，避免两处重复。
    SettingsGroup(title = "行为") {
        SettingsSwitchRow(
            title = "自动连接",
            summary = "启动时优先连上次的电脑，失败再扫描局域网",
            checked = remote.autoConnect,
            onChecked = { vm.setRemoteAutoConnect(it) }
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "心跳保活",
            summary = "定时发心跳，减少被网络设备掐断",
            checked = remote.heartbeat,
            onChecked = { vm.setRemoteHeartbeat(it) }
        )
        SettingsDivider()
        SettingsRow(
            title = "后台保活",
            summary = "允许忽略电池优化，减少后台连接被系统清理",
            onClick = {
                com.audiometa.app.util.BatteryOptimization.requestIgnore(context)
            }
        ) {
            Text(
                "去设置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // ---------------- 电脑端插件说明 ----------------
    SettingsGroup(title = "电脑端插件配置对照") {
        SettingsNote("插件默认端口 52100 / 52101，可在电脑端配置页修改")
    }
}

/**
 * 遥控页专用的说明小字：比全局 [SettingsNote] 再降一档透明度，
 * 让它退到背景里去 —— 主内容一眼可见，说明文字"需要时才读"。
 */
@Composable
private fun RemoteNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)
    )
}

/**
 * 地址输入：`[协议] [主机/域名] [端口]` 一行三段。
 *
 * 协议按钮点一下在 ws / wss 间切换（插件是 WebSocket 服务，不是 http）：
 *   - ws（默认）：局域网直连用，端口留空走插件默认 **52100**；
 *   - wss：走隧道/公网 TLS 时用，端口留空走 **443**。
 */
@Composable
private fun DomainInputRow(
    useTls: Boolean,
    host: String,
    port: Int,
    onTls: (Boolean) -> Unit,
    onHost: (String) -> Unit,
    onPort: (Int) -> Unit
) {
    // 用户有没有手动填过端口。空 = 跟随协议默认值。
    var portText by rememberSaveable { mutableStateOf("") }
    val defaultPort = if (useTls) RemoteControlPrefs.DEFAULT_TLS_PORT
    else RemoteControlPrefs.DEFAULT_PORT

    // 协议一改，如果用户没填端口，就跟着换默认端口
    LaunchedEffect(useTls) {
        if (portText.isBlank()) onPort(defaultPort)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 协议：点一下切换
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onTls(!useTls) }
                .padding(horizontal = 12.dp, vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (useTls) "wss" else "ws",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            singleLine = true,
            placeholder = {
                Text(
                    "主机 / 域名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(5)
                portText = digits
                // 框里是空的 → 回到协议默认端口；填了就按填的来
                onPort(digits.toIntOrNull() ?: defaultPort)
            },
            singleLine = true,
            placeholder = {
                Text(
                    // 占位就写默认端口，用户一眼知道不填会用什么
                    if (useTls) "443" else RemoteControlPrefs.DEFAULT_PORT.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(72.dp)
        )
    }
}
