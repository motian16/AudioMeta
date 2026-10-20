package com.audiometa.app.ui.screen.settings

import com.audiometa.app.data.prefs.RemoteControlPrefs
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.remote.LanDiscovery
import com.audiometa.app.remote.RemoteTrackResolver

/**
 * 设置 PC 地址。会顺手解析粘贴进来的端口与协议。
 *
 * 用户很可能直接粘贴 `ws://192.168.0.106:52100/` 或
 * `wss://music.example.com/` 这种整条地址，所以：
 *   - `wss://` 且没写端口 → 端口自动填 443；
 *   - `wss://` / `https://` → 自动打开 TLS 开关；
 *   - 主机名剥掉 scheme 与路径。
 */
internal fun SettingsViewModel.setRemoteHost(raw: String) {
    val port = RemoteControlPrefs.extractPort(raw)
    val tls = RemoteControlPrefs.extractTls(raw)
    mutateRemotePrefs(pushToClient = true) {
        it.copy(
            host = RemoteControlPrefs.sanitizeHost(raw),
            port = port ?: it.port,
            useTls = tls ?: it.useTls
        )
    }
    edit {
        ServiceLocator.settings.setRemoteHost(raw)
        if (port != null) ServiceLocator.settings.setRemotePort(port)
        if (tls != null) ServiceLocator.settings.setRemoteTls(tls)
    }
}

internal fun SettingsViewModel.setRemotePort(value: Int) =
    mutateRemotePrefs(pushToClient = true) { it.copy(port = value.coerceIn(1, 65535)) }
        .also { edit { ServiceLocator.settings.setRemotePort(value) } }

/**
 * 从发现结果里选一个地址来连。
 *
 * 和用户手输地址等价，只是不用手打 —— 所以内部就是把 host/port 落盘，
 * 再触发一次连接。
 */
internal fun SettingsViewModel.setRemoteHostWithPort(host: String, port: Int) {
    mutateRemotePrefs(pushToClient = true) {
        it.copy(
            host = RemoteControlPrefs.sanitizeHost(host),
            port = port.coerceIn(1, 65535),
            useTls = false
        )
    }
    edit {
        ServiceLocator.settings.setRemoteHost(host)
        ServiceLocator.settings.setRemotePort(port)
        ServiceLocator.settings.setRemoteTls(false)
    }
}

internal fun SettingsViewModel.setRemoteTls(value: Boolean) =
    mutateRemotePrefs(pushToClient = true) { it.copy(useTls = value) }
        .also { edit { ServiceLocator.settings.setRemoteTls(value) } }

internal fun SettingsViewModel.setRemoteEnabled(value: Boolean) {
    // 固定内网 IP 模式由 refreshRemote 三段编排发起连接，
    // 不能先 push 给客户端 —— apply() 会对手填域名直接 connect()，抢掉并发竞速。
    val fixedMode = value && remotePrefsValue.fixedLan
    mutateRemotePrefs(pushToClient = !fixedMode) { it.copy(enabled = value) }
    edit { ServiceLocator.settings.setRemoteEnabled(value) }
    // 开关打开时用前台服务把进程钉住；关掉时一起停（通知随之消失）。
    if (value) {
        com.audiometa.app.remote.RemoteControlService.start(getApplication())
    } else {
        com.audiometa.app.remote.RemoteControlService.stop(getApplication())
    }
    if (value) {
        // 打开开关：固定内网 IP 模式走三段编排（并发/IPv6/公网，全失败判离线）；
        // 普通模式填了地址就直连。两者都**不主动扫局域网**
        // （扫描要用户自己点「查找」）。
        val cfg = remotePrefsValue
        if (cfg.fixedLan) {
            refreshRemote()
        } else if (cfg.hasTarget) {
            ServiceLocator.remoteControl.apply(cfg)
            ServiceLocator.remoteControl.connect()
        }
    } else {
        // 关掉时清干净：断开连接、清掉扫描结果和提示
        disconnectRemote()
        _remoteDevices.value = emptyList()
        clearScanMessage()
    }
}

internal fun SettingsViewModel.setRemoteAutoConnect(value: Boolean) {
    mutateRemotePrefs(pushToClient = false) { it.copy(autoConnect = value) }
    edit { ServiceLocator.settings.setRemoteAutoConnect(value) }
}

internal fun SettingsViewModel.setRemoteHeartbeat(value: Boolean) {
    mutateRemotePrefs(pushToClient = true) { it.copy(heartbeat = value) }
    edit { ServiceLocator.settings.setRemoteHeartbeat(value) }
}

/**
 * 地址列表顶部「固定内网 IP」开关。
 *
 * 打开时把当前局域网分组的地址一并记下（[host]）：下次启动不再扫局域网，
 * 直接按「固定局域网 IP（与指定域名并发）→ IPv6 → 公网」三段拨，
 * 全失败判定电脑离线。关闭后回到普通的"记住地址 → 扫局域网"编排。
 *
 * 只存开关和地址，不动当前连接 —— 策略下次启动（或手动刷新）生效。
 */
internal fun SettingsViewModel.setFixedLan(enabled: Boolean, host: String) {
    val clean = RemoteControlPrefs.sanitizeHost(host)
    mutateRemotePrefs(pushToClient = false) {
        it.copy(
            fixedLan = enabled,
            // 打开才更新地址；关闭时保留，重新打开不用再选
            fixedLanHost = if (enabled) clean else it.fixedLanHost
        )
    }
    edit {
        ServiceLocator.settings.setRemoteFixedLan(enabled)
        if (enabled) ServiceLocator.settings.setRemoteFixedLanHost(clean)
    }
}

/**
 * 有没有"待打开的目标页"。
 *
 * 设置页用它判断"这次挂载是跳转进来的吗" —— 是的话就跳过
 * resetTicket 的复位，否则会把目标页打回主页。
 */
internal fun SettingsViewModel.pendingSection(): SettingsSection? = pendingSection

/**
 * 连到发现出来的某台设备。
 *
 * 候选按类型优先级依次试：局域网 → IPv6 → 公网 IPv4。
 */
internal fun SettingsViewModel.connectToDevice(device: LanDiscovery.DiscoveredDevice) {
    rememberDeviceNames(listOf(device))
    // 插件在广播里交接了配对码就用它（局域网免配对），否则用手上已有的
    val token = device.handedToken ?: remotePrefsValue.effectiveToken
    if (device.handedToken != null && device.handedToken != remotePrefsValue.token) {
        // 存下来：下次直接连、并且设置页里能看到它（方便告诉第二台设备）
        mutateRemotePrefs(pushToClient = false) { it.copy(token = device.handedToken) }
        edit { ServiceLocator.settings.setRemoteToken(device.handedToken) }
    }

    val candidates = device.orderedEndpoints.map { ep ->
        RemoteControlPrefs(
            enabled = true,
            host = ep.host,
            port = device.wsPort,
            useTls = false,
            token = token,
            heartbeat = remotePrefsValue.heartbeat,
            syncLocalAudio = remotePrefsValue.syncLocalAudio
        ) to ep.label
    }
    if (candidates.isEmpty()) {
        _remoteScanMessage.value = "这台设备没有报出任何可用地址"
        return
    }
    _activeDeviceName.value = device.name
    _remoteScanMessage.value = "正在连接 ${device.name}…"
    _remoteCandidate.value = candidates.first().first.endpoint
    ServiceLocator.remoteControl.connectCandidates(candidates)

    // 等结果；结束后把提示标记为过期 —— 小字不该在页面上常驻
    watchConnectionResult()
}

/**
 * 连到发现出来的某台设备的**某一个具体地址**。
 *
 * 和 [connectToDevice] 的区别：那个会按优先级把该设备所有地址都试一遍，
 * 这个只试用户点的那一个 —— 用户既然点名了这个地址，就照他说的做。
 */
internal fun SettingsViewModel.connectToDeviceAt(device: LanDiscovery.DiscoveredDevice, endpoint: LanDiscovery.DiscoveredEndpoint) {
    rememberDeviceNames(listOf(device))
    _activeDeviceName.value = device.name
    _remoteScanMessage.value = "正在连接 ${endpoint.label} 地址…"
    _remoteCandidate.value = "${endpoint.host}:${device.wsPort}"

    val token = device.handedToken ?: remotePrefsValue.effectiveToken

    val cfg = RemoteControlPrefs(
        enabled = true,
        host = endpoint.host,
        port = device.wsPort,
        useTls = false,
        token = token,
        heartbeat = remotePrefsValue.heartbeat,
        syncLocalAudio = remotePrefsValue.syncLocalAudio
    )
    // 记住用户的选择，下次断线重连会回到同一个地址
    mutateRemotePrefs(pushToClient = false) {
        it.copy(host = endpoint.host, port = device.wsPort, token = token)
    }
    edit {
        ServiceLocator.settings.setRemoteHost(endpoint.host)
        ServiceLocator.settings.setRemotePort(device.wsPort)
    }

    ServiceLocator.remoteControl.apply(cfg)
    ServiceLocator.remoteControl.connect()
    watchConnectionResult()
}

/** 连接状态区显示用：当前连的是哪台设备。 */
internal fun SettingsViewModel.deviceNameFor(endpoint: String): String {
    if (endpoint.isBlank()) return ""
    deviceNameByHost[endpoint]?.let { return it }
    val host = endpoint.substringAfter("://").substringBeforeLast(':')
    return deviceNameByHost[host].orEmpty()
}

internal fun SettingsViewModel.connectRemote() {
    val cfg = remotePrefsValue
    if (cfg.hasTarget) {
        ServiceLocator.remoteControl.apply(cfg)
        ServiceLocator.remoteControl.connect()
    } else {
        refreshRemote()
    }
}

internal fun SettingsViewModel.disconnectRemote() {
    remoteJob?.cancel()
    _remoteScanning.value = false
    ServiceLocator.remoteControl.disconnect()
}

/** 让插件重发一次当前状态。 */
internal fun SettingsViewModel.remoteRequestState() = ServiceLocator.remoteControl.requestState()

/** 清除一次性错误提示。 */
internal fun SettingsViewModel.clearRemoteError() {
    ServiceLocator.remoteControl.clearLastError()
}

/**
 * 从 DataStore 回流同步配置。
 *
 * **只在内容真的不同时才写入**：DataStore 每次发新值都会走到这里，
 * 如果无条件写 MutableStateFlow，界面就会收到一串"值没变但对象是新的"
 * 通知，白白重组。
 */
internal fun SettingsViewModel.syncRemotePrefs(prefs: RemoteControlPrefs) {
    if (prefs == remotePrefsValue) return
    remotePrefsValue = prefs
    _remotePrefsFlow.value = prefs
}

/** 曲库重新扫描后让解析缓存失效。 */
internal fun SettingsViewModel.invalidateRemoteArtifacts() {
    RemoteTrackResolver.invalidate()
    lastResolveKey = ""
}
