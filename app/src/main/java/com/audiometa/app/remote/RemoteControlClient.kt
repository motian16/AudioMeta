package com.audiometa.app.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.data.prefs.RemoteControlPrefs
import com.audiometa.app.data.prefs.RemoteTrack
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程控制客户端：连 PC 上 Salt Player 的 SPW 插件，遥控播放。
 *
 * 协议见插件仓库 `salt-remote-plugin/README.md`，这里只做客户端一半：
 *
 *   发出：`{"t":"hello"}` / `{"t":"ping","ts":..}` /
 *         `{"t":"cmd","id":..,"action":"play|pause|next|prev|seek","pos":..}`
 *   收到：`hello` / `state` / `ack` / `pong` / `netinfo` / `error`
 *
 * 为什么用 OkHttp：它**已经在本项目的依赖里**（4.12.0），自带 WebSocket 客户端，
 * 所以这个功能不需要引入任何新库。服务端那一半（PC 插件）因为宿主 JRE 被裁剪过、
 * 没有 jdk.httpserver，是纯 java.base 手写的 —— 两边没有共同的第三方依赖。
 *
 * 连接策略分两种：
 *   - [apply] / [connect]：按**用户配置的地址**连（手动指定 IP:端口，或隧道域名）；
 *   - [connectCandidates]：按**一组候选**依次快速尝试（局域网自动发现会给出
 *      多个候选：局域网 → IPv6 → 公网），第一个握手成功的就是它。
 *
 * ## 进程级常驻（"后台老断连"的根因之一）
 *
 * 连接状态、socket、重连/心跳协程**全部活在进程级作用域**里（构造参数 [scope]，
 * 由 `ServiceLocator` 注入进程级 SupervisorJob），和任何界面/ViewModel 的
 * 生命周期无关：Activity 进后台、屏幕关掉、用户切到别的 Tab，连接都还在。
 * 之前重连循环挂在界面侧（ViewModel 的 viewModelScope）上，界面一销毁就没人
 * 再拨号了 —— 用户看到的就是"退到后台待一会儿就断了，再也回不来"。
 *
 * ## 掉线判定与重连
 *
 * 三种掉线来源都要能自己恢复，且**永不放弃**（除非用户明确断开/关开关）：
 *   1. socket 被对端关闭 / 网络错误 → `onClosed` / `onFailure` → [scheduleReconnect]；
 *   2. **半开连接**：链路已经没了但 socket 层面察觉不到。心跳会记录最后一次
 *      `pong` 的时刻，超时（[PONG_TIMEOUT_MS]）就当成掉线主动重连；
 *   3. 退避重连本身：指数退避，封顶 [MAX_BACKOFF_MS]，之后一直按这个间隔重试
 *      —— 不再像以前那样重连到第 4 次就彻底停手、等用户手动刷新。
 *
 * ## 断线时**不清**曲目快照
 *
 * 掉线时 `_track` 保留最后一帧（见 `onClosed`/`onFailure`/`disconnect`）：
 * 清成 EMPTY 会让界面把本机匹配到的封面/时长/歌词一起丢掉，还会让本地同步
 * 播放停掉 —— 用户报的"断连中断同步音频播放"就是这个链条。
 *
 * 线程模型：OkHttp 回调在它自己的线程池上，状态通过 StateFlow 暴露，界面 collect 即可。
 */
class RemoteControlClient(
    private val log: (String) -> Unit = { AppLog.d(TAG, it) },
    /**
     * 连续重连都失败之后回调一次。
     *
     * 用途：电脑的 IP 变了（DHCP 续约、换网、休眠唤醒）时，
     * 死盯着旧地址重连永远不会成功。拿到这个回调就去**重新扫描局域网**，
     * 把新地址找回来 —— 这是"经常断连"最常见的成因。
     *
     * 由 App 层注入，避免这个类依赖 discovery。
     *
     * 注意语义：这**不是**"放弃"信号。回调之后退避循环继续跑（按最大间隔），
     * 所以即使用户没看到这次重扫，连接照样会自愈。
     */
    private val onReconnectExhausted: () -> Unit = {},
    /**
     * 插件把配对码交接过来时回调一次。
     *
     * 「局域网免配对」模式下，插件会在 `netinfo` 里带上配对码；
     * App 收到就存进设置，用户不用手打。
     */
    private val onTokenHandoff: ((String) -> Unit)? = null,
    /**
     * 重连/心跳协程跑在哪个作用域。
     *
     * 默认自带一个进程级作用域（`SupervisorJob` + IO），调用方也可以显式传入
     * 一个存活更久的（`ServiceLocator` 传的就是进程级那个）。**绝不能传界面级
     * 作用域** —— 那正是"进后台就断连"的老毛病。
     *
     * 参数叫 `parentScope` 而不是 `scope`：和下面那个属性**同名**的话，
     * 属性初始化式里的 `scope ?: …` 会让编译器把它推成可空类型，
     * 类体内所有 `scope.launch` 就都报"可空接收者不允许调用"。
     * 换个名字，属性本身永远是 `CoroutineScope`（非空）。
     */
    parentScope: CoroutineScope? = null
) {
    private val scope: CoroutineScope =
        parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Idle)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    private val _track = MutableStateFlow(RemoteTrack.EMPTY)
    val track: StateFlow<RemoteTrack> = _track.asStateFlow()

    /**
     * PC 下发的封面（已压缩成 JPEG 并由插件 base64 编码）。
     *
     * **只在手机本机曲库匹配不到封面时使用**（界面侧决定，见 ControlScreen）。
     * 换曲时清空，避免新歌短暂显示上一首的封面。
     */
    private val _pcCover = MutableStateFlow<Bitmap?>(null)
    val pcCover: StateFlow<Bitmap?> = _pcCover.asStateFlow()

    /** PC 下发的完整歌词时间轴；本机曲库有歌词时界面优先用本机的。 */
    private val _pcLyrics = MutableStateFlow<RemoteLyrics?>(null)
    val pcLyrics: StateFlow<RemoteLyrics?> = _pcLyrics.asStateFlow()

    /**
     * 先于 `state` 到达的 `trackExtra`。
     *
     * 换曲时插件把 state 和封面/歌词分两条发；极少数情况下 WebSocket 的
     * 调度顺序会反过来。先按 key 暂存，等对应 state 到了再应用，
     * 否则这一首的封面/歌词会被当成"旧曲目"丢掉、要等下一首才恢复。
     */
    private var pendingExtra: Pair<String, JSONObject>? = null

    /** 收到过的最后一条错误文本，界面上作为一次性提示显示。 */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    /** 当前实际连上的地址（可能是自动发现选出来的，和用户填的不一定一样）。 */
    private val _activeEndpoint = MutableStateFlow("")
    val activeEndpoint: StateFlow<String> = _activeEndpoint.asStateFlow()

    /** 自动发现选中的连接方式说明，例如「局域网」。 */
    private val _activeKind = MutableStateFlow("")
    val activeKind: StateFlow<String> = _activeKind.asStateFlow()

    /**
     * 最近一次**掉线原因**（人话）。
     *
     * 用户要求"能看出为什么断了"，所以除了写 [AppLog]，这里还留一份给界面显示：
     * 心跳超时 / 服务端关闭 / 连接被拒绝的具体翻译。
     */
    private val _disconnectReason = MutableStateFlow("")
    val lastDisconnectReason: StateFlow<String> = _disconnectReason.asStateFlow()

    /**
     * 连接代次：每配对成功一次 +1。
     *
     * 用途：断线重连之后，"对齐本地同步播放 / 重新解析本机封面"这类动作要**再来一遍**
     * （断线期间宿主可能已经换歌了）。界面只盯曲目字段的话，同一首歌重连后字段没变，
     * 就永远不会再对齐一次。
     */
    private val _connectionEpoch = MutableStateFlow(0)
    val connectionEpoch: StateFlow<Int> = _connectionEpoch.asStateFlow()

    /**
     * 插件**配对成功之后**下发的完整地址列表。
     *
     * 广播里不带地址（免得把"这台机器开在哪"告诉同网段所有人），
     * 所以配对成功时客户端会主动问一次 `netinfo`，结果放这里给界面用。
     * 它是空的就说明"还没鉴权成功，看不到地址"——这是刻意的。
     */
    private val _addresses = MutableStateFlow<List<RemoteAddress>>(emptyList())
    val addresses: StateFlow<List<RemoteAddress>> = _addresses.asStateFlow()

    /** 插件侧报出来的一个可用地址。 */
    data class RemoteAddress(
        val host: String,
        val kind: String,
        val port: Int
    ) {
        val label: String
            get() = when (kind) {
                "lan" -> "局域网"
                "ipv6" -> "IPv6"
                "pub" -> "公网"
                else -> kind
            }

        val isPublic: Boolean
            get() = when (kind) {
                "pub" -> LanDiscovery.isPublicIpv4(host)
                "ipv6" -> !LanDiscovery.isUniqueLocalIpv6(host)
                else -> false
            }
    }

    private val http = OkHttpClient.Builder()
        // 连接超时给短一点：目标不可达时用户不该等几十秒，
        // 而且候选竞速需要"快速失败"才能尽快换下一个。
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket 长连接，禁用读超时
        .writeTimeout(6, TimeUnit.SECONDS)
        // OkHttp 自己的协议级 ping：即使应用层没有事件，也能发现半开连接。
        // 20 秒是"能发现半开"和"别太费电"之间的折中；真正确认掉线还要看
        // 应用层 pong 超时（见 heartbeatJob）。
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var prefs: RemoteControlPrefs = RemoteControlPrefs()

    /** 竞速/重连时使用的候选列表；为空表示按 [prefs] 连。 */
    @Volatile
    private var candidates: List<Pair<RemoteControlPrefs, String>> = emptyList()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var manuallyClosed = false

    /**
     * 本次连接生命周期里是否**配对成功过**。
     *
     * 只用来决定话术（"首次连接失败" vs "掉线重连"）和触发重扫的时机，
     * **不再**决定"要不要继续重连"：两种情况都一直重连（见 [scheduleReconnect]），
     * 否则用户在后台待一会儿回来就是死的，非得手点刷新。
     *
     * [disconnect] 时复位：用户手动断开/关闭开关后再连，算新的一轮。
     */
    @Volatile
    private var hasAuthedOnce = false

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var msgCounter = 0

    @Volatile
    private var reconnectAttempt = 0

    /** 最近一次收到任何回包的墙钟时刻。心跳超时判定用。 */
    @Volatile
    private var lastPongAt: Long = 0L

    /** 这一轮连接有没有见过 pong。没见过就不断言掉线（老插件可能不认 ping）。 */
    @Volatile
    private var pongSeen = false

    init {
        // 首次连接成功时，把该次使用的 prefs 记为当前配置，
        // 这样断线重连会回到同一个（已验证可用的）地址。
        scope.launch {
            state.collect { s ->
                if (s is RemoteConnectionState.Connected) reconnectAttempt = 0
            }
        }
    }

    // ---------------- 对外：配置与连接 ----------------

    /**
     * 当前已经推给客户端的配置。
     *
     * 给 ViewModel 用来判断"连接参数到底变没变" —— 上一版闪烁的根因就是
     * 配置对象每次都是新的、无条件重连。有了它才能只对真正的变化做反应。
     */
    fun appliedPrefs(): RemoteControlPrefs? = appliedPrefsValue

    @Volatile
    private var appliedPrefsValue: RemoteControlPrefs? = null

    /** 由界面/服务在配置变化时调用。地址或开关一变就按新配置重连。 */
    fun apply(newPrefs: RemoteControlPrefs) {
        val old = prefs
        prefs = newPrefs
        appliedPrefsValue = newPrefs
        candidates = emptyList() // 手动配置优先，丢掉发现出来的候选

        // 明文连公网地址时留一条提示（不改行为，只在排查日志里能看见）。
        warnIfPlaintextPublic(newPrefs)

        val targetChanged = old.host != newPrefs.host ||
            old.port != newPrefs.port ||
            old.useTls != newPrefs.useTls
        val enabledChanged = old.enabled != newPrefs.enabled

        if (!newPrefs.enabled) {
            if (enabledChanged || _state.value != RemoteConnectionState.Idle) {
                log("远程控制已关闭")
                disconnect()
            }
            return
        }

        if (!newPrefs.hasTarget) {
            // 没有手动地址不等于不能连：控制页会自动走局域网发现。
            if (_state.value.isLive || _state.value.isBusy) disconnect(keepState = false)
            _state.value = RemoteConnectionState.Idle
            return
        }

        when {
            targetChanged -> {
                reconnectAttempt = 0
                log("地址已改为 ${newPrefs.endpoint}，重新连接")
                connect()
            }
            // 开关从关到开、且用户允许自动连接 —— 这时候 _state 可能已经是 Idle
            // （比如 App 重启后刚读回设置），所以光看状态变化是不够的，
            // 必须显式判一次 autoConnect，否则"重启后再进控制页"永远不会自动连。
            enabledChanged && newPrefs.autoConnect &&
                !_state.value.isLive && !_state.value.isBusy -> connect()
            old.heartbeat != newPrefs.heartbeat -> restartHeartbeat()
            // 掉线重连中推来同样的配置：不打断退避循环，但要保证重试循环真的在跑。
            // （以前这里什么都不做，界面侧一旦停止推进就永久停在 Reconnecting。）
            _state.value is RemoteConnectionState.Reconnecting && reconnectJob?.isActive != true ->
                scheduleReconnect(_disconnectReason.value.ifBlank { "连接已断开" })
        }
    }

    /**
     * 按一组候选依次快速尝试，第一个握手成功的生效。
     *
     * 用"依次快速尝试"而不是"并发全部连接"：手机同时开 5 条 WebSocket
     * 会更费电，而且插件的连接上限是 8，并发会把 PC 侧的配额占满。
     * 每个候选给 [PER_CANDIDATE_TIMEOUT_MS]，失败立刻换下一个。
     *
     * 固定内网 IP 的启动策略要"域名和局域网同时拨"，所以另提供
     * [concurrent] = true 的并发竞速：同批候选同时连接，列表顺序就是
     * 优先级（靠前的先成功就用它），其余 socket 立刻关掉。
     *
     * @param list (配置, 该地址的类型说明)
     * @param concurrent true=同批并发竞速；false=依次尝试
     */
    fun connectCandidates(
        list: List<Pair<RemoteControlPrefs, String>>,
        concurrent: Boolean = false
    ) {
        if (list.isEmpty()) return
        list.firstOrNull()?.let { warnIfPlaintextPublic(it.first) }
        manuallyClosed = false
        hasAuthedOnce = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        candidates = list
        log(
            (if (concurrent) "并发尝试 " else "依次尝试 ") +
                "${list.size} 个候选地址"
        )
        scope.launch {
            if (concurrent) runCandidatesConcurrent(list) else runCandidates()
        }
    }

    private suspend fun runCandidates() {
        val list = candidates
        if (list.isEmpty()) return

        for ((index, candidate) in list.withIndex()) {
            if (manuallyClosed) return
            val (cfg, kind) = candidate
            _state.value = RemoteConnectionState.Connecting
            val ok = tryCandidate(cfg, PER_CANDIDATE_TIMEOUT_MS)
            if (ok) {
                prefs = cfg
                _activeKind.value = kind
                log("候选 ${index + 1}/${list.size} 连接成功：${cfg.endpoint}（$kind）")
                return
            }
            log("候选 ${index + 1}/${list.size} 失败：${cfg.endpoint}（$kind）")
        }

        // 全部失败：不再停在 Failed 干等用户操作 —— 继续按退避重试，
        // 这样电脑稍后开机、插件稍后启动都能自己连上。
        candidates = emptyList()
        // 但"需要配对码"的提示不能被覆盖 —— 否则配对输入框会闪没。
        if (_state.value is RemoteConnectionState.Unauthorized) return
        scheduleReconnect("没有可连接的地址（试了 ${list.size} 个）")
    }

    /**
     * 同批候选**并发竞速**：所有 socket 同时拨，第一个配对成功的赢。
     *
     * 赢家选择按**列表顺序**（不是鉴权返回的物理先后）：调用方负责把
     * 优先级高的放前面（固定局域网 → 域名）。赢家接管共享 [socket]，
     * 之后它的断线/重连走常规生命周期；输家立刻 cancel。
     *
     * 全部失败就进入退避重连（不再永久 Failed）—— 调用方据此进入下一地址段
     * （v6 / 公网）；三段都失败时由退避循环继续兜着。
     */
    private suspend fun runCandidatesConcurrent(
        list: List<Pair<RemoteControlPrefs, String>>
    ) {
        data class Attempt(val cfg: RemoteControlPrefs, val kind: String, val ws: WebSocket)

        val attempts = coroutineScope {
            list.map { (cfg, kind) ->
                async(Dispatchers.IO) {
                    val (ok, ws) = tryDetachedCandidate(cfg, PER_CANDIDATE_TIMEOUT_MS)
                    if (ok && ws != null) {
                        Attempt(cfg, kind, ws)
                    } else {
                        // 失败/超时的 socket 立刻关掉，别在后台裸奔占插件连接配额
                        runCatching { ws?.cancel() }
                        null
                    }
                }
            }.awaitAll()
        }

        val succeeded = attempts.filterNotNull()
        // awaitAll 保持列表顺序：优先级靠前的先成功就用它
        val winner = succeeded.firstOrNull()
        succeeded.filter { it !== winner }.forEach { runCatching { it.ws.cancel() } }

        if (manuallyClosed || winner == null) {
            candidates = emptyList()
            // 配对码不对/缺失时保留 Unauthorized，别把配对输入框冲掉
            if (!manuallyClosed &&
                _state.value !is RemoteConnectionState.Unauthorized
            ) {
                scheduleReconnect("没有可连接的地址（并发试了 ${list.size} 个）")
            }
            return
        }

        // 赢家接管共享连接
        runCatching { socket?.cancel() }
        socket = winner.ws
        candidates = emptyList()
        prefs = winner.cfg
        _activeEndpoint.value = winner.cfg.endpoint
        _activeKind.value = winner.kind
        _lastError.value = ""
        log("并发竞速成功：${winner.cfg.endpoint}（${winner.kind}）")
        startHeartbeat()
        // 收编后重新问地址列表（detached 时 authOk 发的 netinfo 请求用的是旧 socket）
        requestAddresses()
    }

    /**
     * 并发竞速用的单次尝试：socket **detached**（不碰共享 [socket]，
     * 不和兄弟候选互相 cancel），配对成功与否都把 socket 交回编排层。
     */
    private suspend fun tryDetachedCandidate(
        cfg: RemoteControlPrefs,
        timeoutMs: Long
    ): Pair<Boolean, WebSocket?> {
        if (!cfg.hasTarget || manuallyClosed) return false to null
        val latch = java.util.concurrent.CountDownLatch(1)
        val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val ws = openSocketFor(
            cfg = cfg,
            onOpened = { },
            onFailed = { latch.countDown() },
            onAuthed = {
                succeeded.set(true)
                latch.countDown()
            },
            detached = true
        ) ?: return false to null

        withTimeoutOrNull(timeoutMs) {
            withContextIo { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
        return succeeded.get() to ws
    }

    /**
     * 由上层明确判定"电脑离线"。
     *
     * 用在**启动扫描**里：3 秒预算内既没连上、也没发现在线设备时，直接把状态
     * 置成 [RemoteConnectionState.Failed]，控制页就能立刻显示"电脑不在线"，
     * 而不是让用户对着一个"重连中（第 N 次）"猜。
     *
     * 只改状态、不停止重连：退避循环如果已经在跑，就让它继续尝试 ——
     * 电脑稍后开机，界面会自己跳回"已连接"。
     */
    fun markOffline(reason: String) {
        if (_state.value.isLive) return
        val text = reason.ifBlank { "电脑不在线" }
        _lastError.value = text
        _disconnectReason.value = text
        _state.value = RemoteConnectionState.Failed(text)
        log("判定离线：$text")
    }

    /**
     * 明文 `ws://` 连非内网地址时提醒一句。
     *
     * 插件本身不提供 TLS，公网场景应该走隧道（`wss://`）或 Tailscale。
     * 这里**不改行为**（否则会打断已经在用的配置），只在日志里留一条可排查的痕迹 ——
     * "安全性增加"的第一步是让危险配置看得见。
     */
    private fun warnIfPlaintextPublic(cfg: RemoteControlPrefs) {
        if (cfg.useTls || !cfg.hasTarget) return
        if (isPrivateHost(cfg.host)) return
        log("安全提示：正在用明文 ws:// 连接非内网地址 ${cfg.host}，建议改用 wss:// 或隧道")
    }

    /** 本机 / 内网 / CGNAT / 内网域名都算"不是公网直连"。 */
    private fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isBlank()) return true
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".home.arpa")
        ) {
            return true
        }
        // IPv6 字面量：链路本地/ULA 是内网；全局 IPv6 也可能是用户自己的家宽，
        // 这里不做误报。
        if (h.contains(':')) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 100 && b in 64..127) || // CGNAT / Tailscale
            (a == 169 && b == 254)
    }

    /** 尝试一个候选：等**配对成功**（不是仅仅 socket 打开）才算通过。 */
    private suspend fun tryCandidate(cfg: RemoteControlPrefs, timeoutMs: Long): Boolean {
        if (!cfg.hasTarget) return false
        return withTimeoutOrNull(timeoutMs) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val succeeded = java.util.concurrent.atomic.AtomicBoolean(false)

            openSocketFor(
                cfg = cfg,
                onOpened = { /* socket 打开还不算数，配对码可能不对 */ },
                onFailed = { latch.countDown() },
                onAuthed = {
                    succeeded.set(true)
                    latch.countDown()
                }
            )

            withContextIo { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            succeeded.get()
        } ?: false
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /** 主动连接（用户点「连接」，或配置刚打开时）。 */
    fun connect() {
        if (!prefs.enabled) return
        if (!prefs.hasTarget) {
            _state.value = RemoteConnectionState.Failed("还没有填写 PC 地址")
            return
        }
        manuallyClosed = false
        hasAuthedOnce = false
        candidates = emptyList()
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        log("开始连接 ${prefs.endpoint}")
        openSocket(firstAttempt = true)
    }

    /**
     * 网络恢复、或前台服务被系统重新拉起时，立刻重试一次。
     *
     * 退避循环平时最长会睡 60 秒；用户从 Wi-Fi 切回 4G、或息屏后回到前台时，
     * 让连接**马上**重试，而不是等下一个退避窗口。
     * 已经连上/正在配对时什么都不做，避免把正常连接打断。
     */
    fun nudge() {
        if (manuallyClosed || !prefs.enabled) return
        if (_state.value.isLive || _state.value is RemoteConnectionState.Unauthorized) return
        // 正在拨号时不要插一条新的尝试；Reconnecting 才需要把它从退避里叫醒。
        if (_state.value is RemoteConnectionState.Connecting) return
        if (!prefs.hasTarget && candidates.isEmpty()) return
        reconnectJob?.cancel()
        reconnectJob = null
        log("网络恢复/服务唤醒，立即重试连接")
        if (candidates.isNotEmpty()) {
            scope.launch { runCandidates() }
        } else {
            openSocket(firstAttempt = false)
        }
    }

    /** 断开并停止自动重连。 */
    fun disconnect(keepState: Boolean = true) {        log("主动断开连接（用户操作）")
        manuallyClosed = true
        hasAuthedOnce = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        runCatching { socket?.close(1000, "client closed") }
        socket = null
        candidates = emptyList()
        /*
         * 这里**刻意不把 _track 清成 EMPTY**。
         *
         * 清空会让界面把"本机匹配到的封面/时长/歌词"一起丢掉（见
         * SettingsViewModel.resolveLocalArtifacts 的空快照分支），并且会触发
         * 本地同步播放停下来。用户明确要求："断线/重连/切歌过程中本机正在同步
         * 播放的音频必须继续播"。留着最后一帧快照，重连成功后宿主的 state 推送
         * 会把它覆盖成最新值，界面在这期间显示"未连接"就够了。
         */
        _activeEndpoint.value = ""
        _activeKind.value = ""
        _disconnectReason.value = "已断开"
        if (!keepState) {
            _state.value = RemoteConnectionState.Idle
        } else if (_state.value.isLive || _state.value.isBusy) {
            _state.value = RemoteConnectionState.Idle
        }
    }

    /** 不再需要时释放。 */
    fun shutdown() {
        disconnect(keepState = false)
    }

    // ---------------- 播放控制 ----------------

    fun play() = sendCommand(ACTION_PLAY, null)
    fun pause() = sendCommand(ACTION_PAUSE, null)
    fun next() = sendCommand(ACTION_NEXT, null)
    fun previous() = sendCommand(ACTION_PREV, null)

    /** 跳转到指定位置（毫秒）。 */
    fun seekTo(positionMs: Long) = sendCommand(ACTION_SEEK, positionMs.coerceAtLeast(0))

    /** 请求一次状态刷新（重发 hello，服务端会回一条 state）。 */
    fun requestState() {
        val ws = socket ?: return
        runCatching { ws.send(JSONObject().put("t", "hello").toString()) }
    }

    /** 主动问一次完整地址列表（只在已配对时有意义）。 */
    fun requestAddresses() {
        val ws = socket ?: return
        if (!_state.value.isLive) return
        runCatching { ws.send(JSONObject().put("t", "netinfo").toString()) }
    }

    /** 清掉一次性错误提示。 */
    fun clearLastError() {
        _lastError.value = ""
    }

    private fun sendCommand(action: String, posMs: Long?) {
        val ws = socket
        if (ws == null || !_state.value.isLive) {
            _lastError.value = "还没连接上，指令没发出去"
            return
        }
        val json = JSONObject().apply {
            put("t", "cmd")
            put("id", ++msgCounter)
            put("action", action)
            if (posMs != null) put("pos", posMs)
        }
        val ok = runCatching { ws.send(json.toString()) }.getOrDefault(false)
        if (!ok) _lastError.value = "指令发送失败"
    }

    // ---------------- 内部：连接 ----------------

    private fun openSocket(firstAttempt: Boolean) {
        if (manuallyClosed) return
        val p = prefs
        if (!p.hasTarget) {
            _state.value = RemoteConnectionState.Failed("还没有填写 PC 地址")
            return
        }
        _state.value = if (firstAttempt) {
            RemoteConnectionState.Connecting
        } else {
            RemoteConnectionState.Reconnecting(
                attempt = reconnectAttempt,
                reason = _disconnectReason.value
            )
        }
        openSocketFor(p, onOpened = {}, onFailed = {})
    }

    /**
     * 建立一条 WebSocket。竞速（[tryCandidate]/[tryDetachedCandidate]）
     * 和常规连接都走这里。
     *
     * @param onOpened 握手成功
     * @param onFailed 失败或关闭（两者都会触发，保证竞速的 latch 一定会被放行）
     * @param detached true=并发竞速里的试探连接：**不碰**共享 [socket]，
     *   不和兄弟候选互相取消；由 [runCandidatesConcurrent] 决定留用谁
     * @return 新建的 socket；已手动关闭时返回 null
     */
    private fun openSocketFor(
        cfg: RemoteControlPrefs,
        onOpened: () -> Unit,
        onFailed: () -> Unit,
        onAuthed: () -> Unit = {},
        detached: Boolean = false
    ): WebSocket? {
        if (manuallyClosed) {
            onFailed()
            return null
        }
        // 常规连接先关掉旧连接，避免地址变更时两条连接并存。
        // detached 的试探连接互不干预，由竞速编排统一收编/关闭。
        if (!detached) {
            runCatching { socket?.cancel() }
            socket = null
        }

        val url = cfg.url()
        log("连接 $url${if (detached) "（并发试探）" else ""}")
        val request = Request.Builder().url(url).build()
        val listener = Listener(cfg, onOpened, onFailed, onAuthed, detached)
        val ws = http.newWebSocket(request, listener)
        if (!detached) socket = ws
        return ws
    }

    /**
     * 安排一次指数退避重连。
     *
     * **不再有"重连到第几次就停手"**：退避封顶 [MAX_BACKOFF_MS] 之后按这个间隔
     * 无限重试（只有用户手动断开/关开关才会停）。以前退到第 4 次就停手，用户
     * 进后台待一会儿再回来看到的就是"一直是断的，非得手点刷新"。
     *
     * 每退避到 [RESCAN_AFTER_ATTEMPTS] 次通知上层**重扫一次局域网**（电脑换 IP 时
     * 死盯旧地址永远不会成功），然后继续重试。
     */
    private fun scheduleReconnect(reason: String?) {
        if (manuallyClosed || !prefs.enabled) return
        val why = reason.orEmpty().ifBlank { "连接已断开" }
        _disconnectReason.value = why
        reconnectAttempt += 1
        val attempt = reconnectAttempt

        /*
         * 电脑的 IP 变了之后，无论退避多久都连不上 —— 这时候要做的是
         * **重新发现**，而不是继续等。所以每到 RESCAN_AFTER_ATTEMPTS 次就
         * 通知上层重扫一次；重试本身不停止（上层只会记日志/重扫）。
         */
        if (attempt % RESCAN_AFTER_ATTEMPTS == 0) {
            log("连续 $attempt 次重连失败（$why），触发一次重新发现设备")
            runCatching { onReconnectExhausted() }
        }

        _state.value = RemoteConnectionState.Reconnecting(attempt = attempt, reason = why)

        // 指数退避，封顶 60 秒：1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s, ...
        val delayMs = backoffDelayMs(attempt)
        log("连接断开（$why），${delayMs}ms 后第 $attempt 次重连")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manuallyClosed && prefs.enabled) openSocket(firstAttempt = false)
        }
    }

    /**
     * 心跳超时（半开连接）之后的重新拨号。
     *
     * 和 [scheduleReconnect] 分开：这是"链路已经死了但 socket 没察觉"，
     * 不需要等一整个退避周期，但**要占用一次 attempt 计数** —— 否则
     * 每次心跳超时都从 1 秒重来，反复失败时退避形同虚设。
     *
     * 调用方（心跳看门狗）负责先把共享 [socket] 摘掉再 cancel，这样这条
     * socket 迟到的 onFailure 不会和这次重连打架。
     */
    private fun reconnectNow(reason: String) {
        if (manuallyClosed || !prefs.enabled) return
        reconnectAttempt += 1
        _disconnectReason.value = reason
        _state.value = RemoteConnectionState.Reconnecting(
            attempt = reconnectAttempt,
            reason = reason
        )
        log("检测到掉线（$reason），立刻重连")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1_000L)
            if (!manuallyClosed && prefs.enabled) openSocket(firstAttempt = false)
        }
    }

    private fun backoffDelayMs(attempt: Int): Long =
        (1000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(MAX_BACKOFF_MS)

    /**
     * 心跳 + 掉线看门狗。
     *
     * 循环做两件事：
     *   1. 每 [HEARTBEAT_INTERVAL_MS] 发一条应用层 ping（对端回 pong）；
     *      用户关掉「心跳」开关时就不发（省电），但看门狗仍然跑 ——
     *      只在**见过回包之后**才用静默超时断言掉线，所以不发 ping 不会误判；
     *   2. 检查最后一次回包到现在过了多久，超过 [PONG_TIMEOUT_MS] 就判定
     *      链路已死、主动重连。
     *
     * 第 2 步是"后台掉线察觉不到"的补丁：手机进后台被系统挂起时，socket 可能
     * 既不报错也不关闭（半开连接），只有"发出去没有回音"才看得出来。
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        lastPongAt = System.currentTimeMillis()
        pongSeen = false
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val ws = socket ?: break
                if (!_state.value.isLive) break
                if (prefs.heartbeat) {
                    runCatching {
                        ws.send(
                            JSONObject()
                                .put("t", "ping")
                                .put("ts", System.currentTimeMillis())
                                .toString()
                        )
                    }.onFailure { log("心跳发送失败：" + it.message) }
                }
                // 一条回包都没见过就不断言掉线（老插件可能不认 ping）；
                // 见过之后再"静默超时"才是真的死了。
                if (pongSeen && System.currentTimeMillis() - lastPongAt > PONG_TIMEOUT_MS) {
                    val silent = System.currentTimeMillis() - lastPongAt
                    log("心跳超时：${silent}ms 没收到任何回包，判定链路已断")
                    // 先把共享引用摘掉再 cancel：这条 socket 随即会回调 onFailure，
                    // 那时 isAttached 已经是 false，不会再排一次重连（否则会和
                    // 下面这次立刻重连互相覆盖，退避也会被重置）。
                    if (socket === ws) socket = null
                    stopHeartbeat()
                    reconnectNow("心跳超时（${silent / 1000} 秒没有回应）")
                    runCatching { ws.cancel() }
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun restartHeartbeat() {
        if (_state.value.isLive) startHeartbeat() else stopHeartbeat()
    }

    private fun handleMessage(
        text: String,
        source: WebSocket,
        onAuthed: () -> Unit
    ) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        // 任何一条回包都证明链路是活的 —— 心跳看门狗据此判定"没掉线"。
        lastPongAt = System.currentTimeMillis()
        pongSeen = true
        when (json.optString("t")) {
            "state" -> {
                val next = RemoteTrack(
                    playing = json.optBoolean("playing", false),
                    title = json.optString("title"),
                    artist = json.optString("artist"),
                    album = json.optString("album"),
                    path = json.optString("path"),
                    positionMs = json.optLong("pos", 0L),
                    id = json.optString("id"),
                    lyricLine = json.optString("lyric"),
                    // 插件 1.5+（协议 v2）才有；旧插件没有这个键，取 0 走本机时长。
                    durationMs = json.optLong("duration", 0L)
                )
                val old = _track.value
                // 换曲：立即丢掉上一首的 PC 封面/歌词，避免新歌先闪一下旧封面。
                if (RemoteTrackResolver.keyOf(old) != RemoteTrackResolver.keyOf(next)) {
                    _pcCover.value = null
                    _pcLyrics.value = null
                }
                _track.value = next
                // 迟到的封面/歌词：现在 state 到了，key 对得上就补上。
                pendingExtra?.let { (pendingKey, extra) ->
                    val newKey = RemoteTrackResolver.keyOf(next)
                    if (pendingKey == newKey) {
                        pendingExtra = null
                        applyTrackExtra(extra)
                    } else if (pendingKey != RemoteTrackResolver.keyOf(old)) {
                        // 既不是上一首也不是当前这首：已经过期，丢掉。
                        pendingExtra = null
                    }
                }
            }

            // 换曲时插件单独下发的封面 + 完整歌词（协议 v2）。
            // 只接受与当前曲目 key 匹配的一条：拉取是异步的，换歌后旧任务可能才回来。
            "trackExtra" -> {
                val key = json.optString("key")
                if (key.isNotBlank()) {
                    if (key == RemoteTrackResolver.keyOf(_track.value)) {
                        applyTrackExtra(json)
                    } else {
                        // state 还没到：先存着，等 state 到了再判断是不是当前曲目。
                        pendingExtra = key to json
                    }
                }
            }

            // 插件要求配对：把状态切成 Unauthorized，界面据此弹输入框
            "authRequired" -> {
                if (!_state.value.isLive) {
                    _state.value = RemoteConnectionState.Unauthorized("插件要求配对码")
                }
            }

            "authOk" -> {
                val firstOfThisRound = !hasAuthedOnce
                hasAuthedOnce = true
                reconnectAttempt = 0
                // 新一轮连接：上一条连接遗留的封面/歌词/待定 extra 都不再可信。
                pendingExtra = null
                _state.value = RemoteConnectionState.Connected
                _lastError.value = ""
                _disconnectReason.value = ""
                // 连接代次 +1：界面据此在重连后重新对齐同步播放 / 重新解析本机匹配。
                _connectionEpoch.value = _connectionEpoch.value + 1
                log(
                    if (firstOfThisRound) "配对成功，连接建立"
                    else "重连配对成功，连接已恢复"
                )
                // 配对成功才去问地址列表 —— 这就是"鉴权通过才看得到地址"
                // detached 的试探连接此刻还没接管共享 socket，收编后由
                // runCandidatesConcurrent 再发一次
                if (socket === source) requestAddresses()
                // 通知 Listener：竞速循环靠这个判定"这个候选可用"
                runCatching { onAuthed() }
            }

            // 插件下发地址列表（只在配对成功后才会有）
            "netinfo" -> {
                val port = json.optInt("wsPort", 0).takeIf { it in 1..65535 }
                    ?: prefs.port
                val list = ArrayList<RemoteAddress>()
                json.optJSONArray("lan")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val h = arr.optString(i).trim()
                        if (h.isNotEmpty()) list.add(RemoteAddress(h, "lan", port))
                    }
                }
                json.optJSONArray("ipv6")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val h = arr.optString(i).trim()
                        if (h.isNotEmpty()) list.add(RemoteAddress(h, "ipv6", port))
                    }
                }
                json.optString("public").takeIf { it.isNotBlank() && it != "null" }?.let {
                    list.add(RemoteAddress(it.trim(), "pub", port))
                }
                _addresses.value = list.distinctBy { it.host to it.kind }
                log("插件下发 ${list.size} 个可用地址")

                // 局域网免配对：插件把配对码一并交接过来。
                // 存下来写回设置，用户就不用手打这 12 个字符。
                // 界面上仍然显示它，方便用户告诉第二台设备。
                val handedToken = json.optString("token").takeIf {
                    it.isNotBlank() && it != "null"
                }
                if (handedToken != null && handedToken != prefs.token) {
                    log("已从插件获取配对码，后续连接自动使用")
                    onTokenHandoff?.invoke(handedToken)
                }
            }

            "authFail" -> {
                _state.value = RemoteConnectionState.Unauthorized(
                    json.optString("err").ifBlank { "配对码不对" }
                )
                _lastError.value = "配对码不对，请在插件配置里核对"
                // 配对失败不自动重连：重连还是会失败，要等用户改配对码。
                // detached 的试探连接只关自己，不能动共享 socket
                if (socket === source) {
                    stopHeartbeat()
                    socket = null
                }
                runCatching { source.close(1000, "auth failed") }
            }

            "hello" -> log("插件握手成功，协议 v${json.optInt("protocol")}")

            "ack" -> if (!json.optBoolean("ok", true)) {
                _lastError.value = "指令被拒绝：" + json.optString("err")
            }

            "error" -> _lastError.value = json.optString("err").ifBlank { "插件返回错误" }

            "pong" -> Unit // 收到就说明链路活着（上面已经更新过 lastPongAt）
        }
    }

    /** 应用一条已经确认属于当前曲目的 `trackExtra`。 */
    private fun applyTrackExtra(json: JSONObject) {
        val key = json.optString("key")
        if (key.isBlank()) return

        val coverData = json.optString("cover")
        if (coverData.isNotBlank()) {
            runCatching {
                val bytes = Base64.decode(coverData, Base64.DEFAULT)
                if (bytes.size <= MAX_COVER_BYTES) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.let { _pcCover.value = it }
                }
            }
        }

        json.optJSONArray("lyrics")?.let { array ->
            val lines = ArrayList<RemoteLyricLine>(array.length().coerceAtMost(256))
            for (i in 0 until array.length().coerceAtMost(MAX_LYRIC_LINES)) {
                val line = array.optJSONObject(i) ?: continue
                val main = line.optString("m")
                if (main.isBlank()) continue
                lines += RemoteLyricLine(
                    startMs = line.optLong("s", 0L),
                    endMs = line.optLong("e", 0L),
                    main = main,
                    translation = line.optString("t")
                )
            }
            _pcLyrics.value = RemoteLyrics(key, lines)
        }
    }

    /** 把底层异常翻译成用户能看懂、并且知道该去查什么的一句话。 */
    private fun describeFailure(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("ECONNREFUSED", true) || msg.contains("Connection refused", true) ->
                "连接被拒绝：插件没启用，或端口不对"
            msg.contains("timeout", true) || t is java.net.SocketTimeoutException ->
                "连接超时：检查是否同一网段、防火墙是否拦截"
            msg.contains("NoRouteToHost", true) || msg.contains("ENETUNREACH", true) ->
                "主机不可达：检查网络或 Tailscale 是否已登录"
            msg.contains("Unable to resolve host", true) ->
                "域名解析失败：换个地址或用 IP"
            msg.contains("Network is unreachable", true) -> "网络不可用"
            msg.isBlank() -> t.javaClass.simpleName
            else -> msg
        }
    }

    private inner class Listener(
        private val cfg: RemoteControlPrefs,
        private val onOpened: () -> Unit,
        private val onFailed: () -> Unit,
        private val onAuthed: () -> Unit,
        private val detached: Boolean = false
    ) : WebSocketListener() {

        private val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        private val authed = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 这条 socket 是否代表当前主连接。
         *
         * 常规连接恒为 true；并发竞速的试探连接只有在被收编为共享
         * [socket] 之后才算数 —— 这样输家迟到的 onClosed/onFailure
         * 不会把赢家的状态冲掉，也不会停赢家的心跳。
         */
        private fun isAttached(webSocket: WebSocket): Boolean =
            !detached || socket === webSocket

        private fun settle() {
            if (settled.compareAndSet(false, true)) onFailed()
        }

        /** 配对成功：竞速循环靠这个判定"这个候选可用"。 */
        fun markAuthed() {
            if (authed.compareAndSet(false, true)) {
                settled.set(true) // 成功之后不再触发 onFailed
                onAuthed()
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            settled.set(true)
            log("已连接 ${cfg.endpoint}，发送配对码")
            pongSeen = false
            lastPongAt = System.currentTimeMillis()
            // **先配对再做事**：插件在配对成功前不会给状态，
            // 所以这里不设 Connected，等 authOk 再设。
            _state.value = RemoteConnectionState.Connecting
            _activeEndpoint.value = cfg.endpoint
            _lastError.value = ""
            runCatching {
                webSocket.send(
                    JSONObject()
                        .put("t", "auth")
                        .put("token", cfg.effectiveToken)
                        .toString()
                )
            }
            // 试探连接还没收编，心跳等收编后由编排层启动
            if (isAttached(webSocket)) startHeartbeat()
            onOpened()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleMessage(text, webSocket, ::markAuthed) }
                .onFailure { log("消息解析失败: ${it.message}") }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 并发竞速的输家：只放行 latch，不碰主状态/心跳
            if (!isAttached(webSocket)) {
                settle()
                return
            }
            stopHeartbeat()
            val why = reason.ifBlank { "服务端关闭（code=$code）" }
            /*
             * 这里**不再清 _track / _activeEndpoint**：
             * 清掉会让界面丢掉本机匹配到的封面与时长，并让同步播放停下来。
             * 保留下线前的最后一帧，重连后的 state 推送会刷新它。
             */
            if (manuallyClosed) {
                log("连接已关闭（$why）")
                _state.value = RemoteConnectionState.Idle
            } else {
                log("连接被对端关闭（$why），进入退避重连")
                scheduleReconnect(why)
            }
            settle()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 并发竞速的输家：只放行 latch，不碰主状态/心跳
            if (!isAttached(webSocket)) {
                settle()
                return
            }
            stopHeartbeat()
            val reason = describeFailure(t)
            if (manuallyClosed) {
                log("连接失败（$reason）")
                _state.value = RemoteConnectionState.Idle
            } else {
                // 竞速阶段：不进入重连，交给 runCandidates 试下一个候选
                if (candidates.isNotEmpty()) {
                    _lastError.value = reason
                    log("候选连接失败：$reason")
                } else {
                    /*
                     * 不再区分"从没连通过"就永久 Failed。
                     *
                     * 老行为：App 启动时电脑没开 SPW → 试一次就判"电脑不在线"，
                     * 之后必须有用户手动点刷新才会再试。用户的实际感受是
                     * "后台断一次就再也连不回来"。
                     *
                     * 新行为：一律进入指数退避重连（封顶 60 秒后一直按 60 秒重试），
                     * 到第 RESCAN_AFTER_ATTEMPTS 次时顺带重扫一次局域网找新地址。
                     * 想停只有一条路：用户关掉开关或手动断开。
                     */
                    _lastError.value = reason
                    log("连接失败：$reason")
                    scheduleReconnect(reason)
                }
            }
            settle()
        }
    }

    companion object {
        private const val TAG = "SpwRemote"
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_SEEK = "seek"

        /** 单个候选的连接预算：超了就换下一个，避免一个死地址拖住整轮。 */
        private const val PER_CANDIDATE_TIMEOUT_MS = 4_500L

        /** 插件把封面压到 150KB 以内再 base64；这里再兜一次，防止异常大包进内存。 */
        private const val MAX_COVER_BYTES = 400_000

        /** 歌词行上限：正常一首歌几百行，超大文件不应该把界面拖垮。 */
        private const val MAX_LYRIC_LINES = 2_000

        /**
         * 连续重连失败到几次就触发一次"重新发现设备"。
         *
         * 取 4：退避序列是 1s / 2s / 4s / 8s，累计约 15 秒。
         * 短于这个数会因为一次网络抖动就白扫一遍；长于这个数用户会觉得
         * "断了之后一直不动"。**注意这只是"重扫"的节拍，不是停止重连的门槛。**
         */
        private const val RESCAN_AFTER_ATTEMPTS = 4

        /** 退避封顶（毫秒）。封顶之后一直按这个间隔重试，永不放弃。 */
        private const val MAX_BACKOFF_MS = 60_000L

        /** 心跳发包间隔（毫秒）。 */
        private const val HEARTBEAT_INTERVAL_MS = 25_000L

        /**
         * 多久没收到任何回包就判定链路已死（毫秒）。
         *
         * 取 75 秒 ≈ 3 个心跳周期：留足弱网/后台限流的余量，避免把"手机刚
         * 从 Doze 醒来、pong 还在路上"误判成掉线；真的断了则最迟 75 秒后
         * 开始重连，比"永远察觉不到"强得多。
         */
        private const val PONG_TIMEOUT_MS = 75_000L
    }
}
