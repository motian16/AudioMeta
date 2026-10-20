package com.audiometa.app.data.prefs

/**
 * 「远程控制」的配置。
 *
 * 这一组配置控制的是**和 PC 上 Salt Player 插件之间的连接**，
 * 和刮削插件体系（[com.audiometa.app.plugin]）没有关系 —— 那个管的是标签数据源。
 *
 * 连接目标就是插件在 PC 上监听的 WebSocket 端口。插件同时绑在 0.0.0.0 上，
 * 所以填局域网地址（192.168.x.x）和 Tailscale 地址（100.x.y.z）都能用，
 * 这里不需要区分。
 */
data class RemoteControlPrefs(
    /** 总开关。关掉之后不建立任何连接。 */
    val enabled: Boolean = false,

    /** PC 的地址：域名或 IP。局域网填 192.168.x.x，Tailscale 填 100.x.y.z。 */
    val host: String = "",

    /**
     * 插件监听的端口，和插件配置里的 remote.port 保持一致。
     *
     * 走隧道时这里填的是**隧道对外暴露的端口**（HTTPS 是 443），不是插件本地的 52100。
     */
    val port: Int = DEFAULT_PORT,

    /**
     * 是否用 TLS（`wss://` 而不是 `ws://`）。
     *
     * 什么时候必须打开：
     * - 走 Cloudflare Tunnel 等反向隧道：对外只有 HTTPS，必须 `wss://`；
     * - 任何经过公网的连接都应该打开，否则指令和播放信息是明文的。
     *
     * 局域网直连保持关闭即可（插件本身不提供证书）。
     */
    val useTls: Boolean = false,

    /**
     * 是否在进入「控制」页时自动连接。
     *
     * 关掉的话只有手动点「连接」才连 —— 有些用户不希望 App 一打开就
     * 在后台保持一条长连接（费电，而且会唤醒网络）。
     */
    val autoConnect: Boolean = true,

    /**
     * 同步播放本地音频。
     *
     * 打开后：电脑上在放哪首，手机就用本机曲库里的同一首一起放，
     * 位置对齐一次（不持续追帧）。
     *
     * **默认关闭** —— 大多数时候用户只想用手机遥控电脑，
     * 不想手机也跟着出声（会变成两个音源）。想"手机也放"的人才打开它。
     *
     * 音频来源是**本机曲库**（按文件名 / 标题+艺术家匹配到的那一首）；
     * 匹配不到就什么都不做，不会去播别的歌。
     */
    val syncLocalAudio: Boolean = false,

    /** 是否发送应用层心跳。用于在没有播放事件时也能及时发现掉线。 */
    val heartbeat: Boolean = true,

    /**
     * 是否把电脑的局域网 IP 当作**固定内网 IP**。
     *
     * 用户在「网络遥控 → 地址列表」顶部的局域网一行上用开关确认：
     * 家里/公司给电脑做了静态 DHCP 绑定（或电脑设了静态 IP），
     * 那个 192.168.x.x 不会变。
     *
     * 打开后启动策略不再扫局域网，而是直接按固定顺序拨：
     *   1. 固定局域网 IP（若还填了指定域名，两者**并发**竞速）；
     *   2. IPv6；
     *   3. 公网 IPv4。
     * 三段都失败才判定电脑离线，等待用户手动重试 —— 启动最快、
     * 也不会在公司网络里乱发广播。
     */
    val fixedLan: Boolean = false,

    /**
     * 被标记为「固定」的那个局域网 IP（开关打开时一并记下）。
     * 空串表示只依赖设备库里记住的局域网地址。
     */
    val fixedLanHost: String = "",

    /**
     * 配对码。
     *
     * 插件侧首次运行会**随机生成**（形如 `SPW-7K2M9QX4`），
     * 显示在 Salt Player 的「插件配置 → 远程遥控 → 配对码」里。
     *
     * 所以这里**故意没有默认值** —— 用户必须去 PC 上看一眼再填进来。
     * 写死一个默认值等于没有口令：谁拿到插件包都知道默认是多少。
     */
    val token: String = ""
) {
    /** 实际要发给插件的配对码。 */
    val effectiveToken: String get() = token.trim()

    /** 还没填配对码。首次连接时界面要引导用户去 PC 上取。 */
    val needsTokenInput: Boolean get() = token.isBlank()

    val hasTarget: Boolean get() = host.isNotBlank() && port in 1..65535

    /** 供界面显示的地址，例如 `192.168.0.106:52100` 或 `wss://music.example.com:443`。 */
    val endpoint: String
        get() = if (!hasTarget) "" else "${if (useTls) "wss" else "ws"}://$host:$port"

    /** 不含端口的简短显示，给窄一点的界面用。 */
    val hostLabel: String get() = if (hasTarget) host else ""

    /**
     * 完整的 WebSocket URL。
     *
     * IPv6 字面量要加中括号，否则 URL 解析会把冒号当成端口分隔符。
     */
    fun url(): String {
        val bracketedHost = if (host.count { it == ':' } > 1) "[$host]" else host
        return "${if (useTls) "wss" else "ws"}://$bracketedHost:$port/"
    }

    companion object {
        /** 和插件 RemotePlugin.DEFAULT_PORT 保持一致。 */
        const val DEFAULT_PORT = 52100

        /** 走 TLS 时的默认端口。 */
        const val DEFAULT_TLS_PORT = 443

        /**
         * 明文 HTTP 的默认端口。
         *
         * 注意这是**域名输入框**的默认值，和插件本身监听的
         * [DEFAULT_PORT]（52100）是两回事：前者给"用 http:// 访问一个
         * 反向代理/隧道"的场景用，后者是局域网直连插件的端口。
         */
        const val DEFAULT_HTTP_PORT = 80

        /**
         * 把用户粘贴的各种写法归一成"纯主机名"。
         *
         * 用户很可能是从别处复制一整条 `ws://192.168.0.106:52100/` 过来的，
         * 直接当主机名用会连不上，所以写入前先洗干净。
         *
         * 输入示例 → 输出：
         *   `192.168.0.106`            → `192.168.0.106`
         *   `ws://192.168.0.106:52100` → `192.168.0.106`
         *   `http://host/path`         → `host`
         *   ` 100.64.0.1 `             → `100.64.0.1`
         *   `[::1]:52100`              → `::1`
         */
        fun sanitizeHost(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            val schemeEnd = s.indexOf("://")
            if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close > 0) return s.substring(1, close)
            }
            // 没有中括号的 IPv6 字面量不带端口，原样返回
            if (s.count { it == ':' } > 1) return s.trim()
            return s.substringBefore(':').trim()
        }

        /**
         * 从粘贴内容里顺手取出端口，取不到返回 null（表示保持原端口）。
         *
         * 特例：`wss://` 且没写端口时返回 [DEFAULT_TLS_PORT]。
         * 用户从 Cloudflare / 隧道面板复制过来的地址通常就是
         * `wss://music.example.com/` 这种不带端口的写法，
         * 如果这里回 null，端口会保留成 52100，连隧道必然失败。
         */
        fun extractPort(raw: String): Int? {
            var s = raw.trim()
            val schemeEnd = s.indexOf("://")
            val scheme = if (schemeEnd >= 0) s.substring(0, schemeEnd).lowercase() else ""
            if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')

            val parsed = if (s.startsWith("[") && s.contains("]")) {
                val after = s.substringAfter(']')
                if (!after.startsWith(":")) null
                else after.removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
            } else if (s.count { it == ':' } > 1) {
                null
            } else {
                val colon = s.lastIndexOf(':')
                if (colon <= 0 || colon == s.length - 1) null
                else s.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            }

            // 没写端口但明确是 wss:// → 用 443，不然隧道连不上。
            if (parsed == null && scheme == "wss") return DEFAULT_TLS_PORT
            return parsed
        }

        /**
         * 判断粘贴的地址是否要求 TLS。
         *
         * 返回 null 表示"没看出来"（用户只填了个 IP），此时不动用户的开关。
         */
        fun extractTls(raw: String): Boolean? {
            val s = raw.trim().lowercase()
            val schemeEnd = s.indexOf("://")
            if (schemeEnd <= 0) return null
            return when (s.substring(0, schemeEnd)) {
                "wss", "https" -> true
                "ws", "http" -> false
                else -> null
            }
        }
    }
}

/**
 * 连接状态。
 *
 * [Connecting] 和 [Reconnecting] 分开，是因为界面上要显示的话术不同：
 * 前者是用户主动触发的，后者是掉线后自动重试，需要让用户知道"它在自己恢复"。
 */
sealed interface RemoteConnectionState {
    /** 没有目标地址，或用户关掉了开关。 */
    object Idle : RemoteConnectionState

    /** 正在建立连接（首次连接或用户手动点连接）。 */
    object Connecting : RemoteConnectionState

    /** 已连接。 */
    object Connected : RemoteConnectionState

    /** 掉线后等待重连，[attempt] 是第几次重试。 */
    data class Reconnecting(val attempt: Int, val reason: String? = null) :
        RemoteConnectionState

    /**
     * 需要配对码（或配对码不对）。
     *
     * 和 [Failed] 分开：这不是"连不上"，而是"连上了但插件不认你"，
     * 界面应该直接把输入框摆出来，而不是让用户去翻设置。
     */
    data class Unauthorized(val reason: String) : RemoteConnectionState

    /** 出错且不再自动重试（例如地址没填）。 */
    data class Failed(val reason: String) : RemoteConnectionState

    val label: String
        get() = when (this) {
            is Idle -> "未连接"
            is Connecting -> "连接中…"
            is Connected -> "已连接"
            is Reconnecting -> "重连中（第 $attempt 次）"
            is Unauthorized -> "需要配对码"
            is Failed -> "连接失败"
        }

    val isBusy: Boolean get() = this is Connecting || this is Reconnecting
    val isLive: Boolean get() = this is Connected

    /** 是否需要用户输入配对码。 */
    val needsToken: Boolean get() = this is Unauthorized
}

/**
 * PC 侧正在播放的曲目快照。
 *
 * 字段和插件的 `state` 消息一一对应。
 *
 * 宿主 dev21+ 的 `MediaItem` 已带 `duration`，插件会放在 `state.duration` 里；
 * 旧宿主/旧插件没有这个字段时是 0，界面再退回本机曲库匹配到的时长。
 * 封面和完整歌词不在 state 里，走单独的 `trackExtra`（见 RemoteControlClient）。
 */
data class RemoteTrack(
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val path: String = "",
    val positionMs: Long = 0L,
    /**
     * 宿主侧曲目 id。
     *
     * **可能为空**：老宿主（dev20）没有公开接口给这个值，插件是反射读内部字段
     * 拿到的，宿主升级或字段改名就会变空。所以它是"锦上添花"，不能当主键依赖。
     */
    val id: String = "",
    /** 当前歌词行的纯文本，来自 `onLyricsLineUpdated`。没有歌词时为空。 */
    val lyricLine: String = "",
    /** PC 侧曲目总时长（毫秒）；0 表示旧宿主/旧插件没提供。 */
    val durationMs: Long = 0L
) {
    val hasTrack: Boolean get() = title.isNotBlank() || path.isNotBlank()

    /**
     * 用来在本地曲库里找同一首歌的候选键。
     *
     * 宿主和手机是两个独立的曲库，文件路径通常对不上（PC 是 `C:\Music\...`，
     * 手机是 `/storage/...`），所以先按 **id** 试（如果插件给得出来），
     * 再退回**标题 + 艺术家**。
     */
    val matchKeys: List<String>
        get() = buildList {
            if (id.isNotBlank()) add("id:$id")
            if (title.isNotBlank()) add("ta:" + title.trim().lowercase() + "|" + artist.trim().lowercase())
            if (path.isNotBlank()) add("path:" + path.substringAfterLast('\\').substringAfterLast('/').lowercase())
        }

    companion object {
        val EMPTY = RemoteTrack()
    }
}
