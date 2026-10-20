package com.audiometa.app.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.data.prefs.RemoteControlPrefs
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.remote.LanDiscovery
import com.audiometa.app.remote.RemoteAddressPolicy
import com.audiometa.app.remote.RemoteControlClient
import com.audiometa.app.remote.RemoteTrackResolver
import com.audiometa.app.plugin.model.PluginCapability
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    val query = MutableStateFlow("")
    internal val section = MutableStateFlow<SettingsSection?>(null)

    /** 缓存操作后的反馈文字，界面上显示一条横幅。 */
    val cacheMessage = MutableStateFlow("")

    /** 「关于 - 缓存」进去的那一页；null 表示没进。 */
    var cachePage by mutableStateOf<CachePage?>(null)
        internal set

    /** 各缓存的统计。 */
    var cacheStats by mutableStateOf<CacheStats?>(null)
        internal set

    /** 统计正在重算。 */
    var cacheStatsLoading by mutableStateOf(false)
        internal set

    // ---------------- 本地补全：封面 / 歌词 / 时长 ----------------

    /*
     * 这几个字段必须声明在 [init] **之前**。
     *
     * `init` 里订阅的 `connectionEpoch` 是 StateFlow：`launchIn(Main.immediate)`
     * 会在构造期间就同步收到当前值。如果那时已经连过电脑（epoch > 0），
     * 回调会直接进 resolveLocalArtifacts，而 Kotlin 按声明顺序初始化属性 ——
     * 声明在后面的 `_nowPlaying` 此刻还是 JVM 默认的 null，读 `.value`
     * 就是线上那条 "Attempt to invoke ... on a null object reference" 闪退。
     */

    private val _nowPlaying = MutableStateFlow(RemoteTrackResolver.NowPlaying())

    /** PC 正在播的那首歌在本机曲库里对应的封面与当前歌词行。 */
    val nowPlaying: StateFlow<RemoteTrackResolver.NowPlaying> = _nowPlaying.asStateFlow()

    /**
     * 曲目一变就重新解析本机资源。
     *
     * 只在**曲目标识变化**时重解析：位置每秒都在变，但封面和歌词不需要重算 ——
     * 歌词行走的是 [currentLyricLine]，由界面按时序自己取，见 [lyricAt]。
     */
    private var resolveJob: Job? = null

    internal var lastResolveKey: String = ""

    val state = combine(
        ServiceLocator.settings.settings,
        query,
        section,
        ServiceLocator.pluginRepo.observePlugins(),
        ServiceLocator.folders.observeAll()
    ) { values ->
        val s = values[0] as AppSettings
        val q = values[1] as String
        val sec = values[2] as SettingsSection?
        @Suppress("UNCHECKED_CAST")
        val plugins = values[3] as List<PluginRecordEntity>
        @Suppress("UNCHECKED_CAST")
        val folders = values[4] as List<FolderEntity>
        SettingsUiState(
            settings = s,
            query = q,
            section = sec,
            results = searchSettings(q),
            plugins = plugins.sortedBy { it.pluginName.lowercase() },
            folders = folders.sortedWith(compareBy({ it.depth }, { it.path }))
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        /*
         * 同步播放是低优先级：手机上别的应用开始出声（放音乐、播视频）时
         * 不去抢、也不去停别人 —— 让自己退场。这里把播放器的冲突回调接成
         * "关闭同步开关"：持久化写入 DataStore + 停掉本机播放，
         * SPW 那边不受影响继续放。
         */
        ServiceLocator.localPlayer.onExternalConflict = {
            if (remotePrefsValue.syncLocalAudio) setSyncLocalAudio(false)
        }

        /*
         * DataStore 回流：内存快照必须从持久化值恢复。
         *
         * 这是"重启后网络遥控开关/配对码/指定地址全部变回默认值"的根因 —
         * 之前 _remotePrefsFlow 初值是默认 RemoteControlPrefs()，而回流
         * 同步函数定义了却从没被调用。去重后写回，避免和本地 mutate 打架。
         */
        var startupPrefsLoaded = false
        ServiceLocator.settings.settings
            .map { it.remote }
            .distinctUntilChanged()
            .onEach { prefs ->
                if (prefs != remotePrefsValue) {
                    remotePrefsValue = prefs
                    _remotePrefsFlow.value = prefs
                }
                /*
                 * 进程启动后第一次拿到持久化配置：开关开着就**立刻**按策略连
                 * （固定内网 IP 直连 / 记住的地址 / 扫局域网），
                 * 不用等用户打开「控制」页。
                 */
                if (!startupPrefsLoaded) {
                    startupPrefsLoaded = true
                    // 启动时走"3 秒内判定离线"的快速编排（见 startupRemoteScan）。
                    if (prefs.enabled) startupRemoteScan()
                }
            }
            .launchIn(viewModelScope)

        /*
         * 通知栏跟随电脑的曲目：**进程级**订阅，不依赖控制页是否在前台。
         *
         * 注意：这条订阅**不能**放在类最前面的 init 块里 —— 见下方
         * [remotePrefsValue] 的说明，也见类末尾那个 init 块。
         */

        /*
         * 鉴权成功后插件下发的完整地址（v6/公网只在配对后才有）落 Room，
         * 下次启动才轮得到它们。广播只能拿到来源 IP，见 persistDevices。
         */
        ServiceLocator.remoteControl.addresses
            // 这个流本身是 StateFlow：内容相同的地址列表不会重复发射，
            // 所以"每次重连都重写一遍 Room"的情况已经由它挡掉了。
            .onEach { if (it.isNotEmpty()) persistNetinfoAddresses(it) }
            .launchIn(viewModelScope)

        /*
         * 重连成功后重新对齐。
         *
         * 断线期间宿主的曲目可能已经换了，而"曲目字段"这一路（控制页的
         * LaunchedEffect）只在字段变化时才跑 —— 同一首歌重连后字段没变，
         * 就永远不会再解析/对齐一次。这里盯**连接代次**（每次配对成功 +1），
         * 重连成功就强制重新解析一次本机匹配并重新对齐本地播放。
         */
        ServiceLocator.remoteControl.connectionEpoch
            .onEach { epoch ->
                if (epoch <= 0) return@onEach
                AppLog.i(TAG_REMOTE, "连接代次 $epoch：重新解析本机匹配并对齐同步播放")
                // 新连接的位置是全新的，不能接着上一次连接的基线继续插值
                resetPositionInterpolation()
                resolveLocalArtifacts(ServiceLocator.remoteControl.track.value, force = true)
            }
            .launchIn(viewModelScope)
    }

    /**
     * 当前正在看的子文件夹页属于哪个根目录。
     *
     * 单独存一个字段（而不是塞进 section 参数）：子文件夹页是"某个目录的详情"，
     * 同一个 section 会对应不同的根目录，光靠 [SettingsSection.SUBFOLDERS] 说不清。
     */
    var subfolderRoot by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set

    /**
     * 每个目录的音频统计：**含所有下级**的音频文件数 + 其中第一个文件名。
     *
     * 用户要求：子目录列表按"里面音频文件多到少"排序（要算上所有层级），
     * 行下面的小字显示其中一个音频文件的名字（取排序后的第一位）。
     * 曲目表里本来就有 folderPath，一次遍历往上累加即可，不用读文件。
     */
    var folderStats by androidx.compose.runtime.mutableStateOf<Map<String, FolderStat>>(emptyMap())
        internal set

    /**
     * 加目录失败时给用户看的一句话。
     *
     * 曲库页直接显示它 —— 失败必须说出来，静默是上一次踩坑的根源。
     */
    var libraryNotice by androidx.compose.runtime.mutableStateOf("")
        internal set

    internal fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /**
     * 离开设置页：**把层级整体复位**。
     *
     * 由 `MainActivity.switchTab` 在"从设置切到别的 Tab"时同步调用。
     * 必须同步（而不是靠页面 onDispose 或 effect）：切回来时本页会重新组合，
     * 那一帧读到的是复位前的值 —— 先渲染旧页再跳回主页，就是用户看到的闪烁。
     *
     * 用户要求：设置页的页面状态**不暂存**，从任何一层切走再回来都落主页。
     */
    fun exitSettings() {
        section.value = null
        query.value = ""
        pendingSection = null
        cachePage = null
        subfolderRoot = null
    }

    // ---------------- 远程控制 ----------------

    /**
     * 当前生效的远程控制配置。
     *
     * 单独留一份内存快照（而不是每处都 collect state）：连接客户端由配置驱动，
     * 改配置时要立刻拿它去重连，不必等 DataStore 回流。
     *
     * ## 为什么用「可空后备字段 + 访问器」而不是普通 `var = RemoteControlPrefs()`
     *
     * Kotlin **按声明顺序**初始化属性，`init` 块也参与这个顺序。这个类最前面的
     * init 块里注册了几个 `StateFlow` 订阅，而 `StateFlow` 会**同步**发出当前值：
     * 一旦某个回调在构造期间读到本属性，普通 `var` 此刻还是 JVM 默认的 null，
     * 于是 `RemoteControlPrefs.syncLocalAudio` 直接 NPE —— 用户看到的就是
     * "打开应用就闪退"（1.2(8) 的真实崩溃日志）。
     *
     * 换成访问器之后：构造期间（还没从 DataStore 读回来）读到的就是**默认配置**，
     * 和"设置尚未加载"的语义一致，任何初始化顺序都不会再 NPE。
     */
    private var _remotePrefsValue: RemoteControlPrefs? = null

    internal var remotePrefsValue: RemoteControlPrefs
        get() = _remotePrefsValue ?: RemoteControlPrefs()
        set(value) {
            _remotePrefsValue = value
        }

    internal val _remotePrefsFlow = MutableStateFlow(RemoteControlPrefs())

    /** 界面读这个（窄流：只有遥控配置变化才会触发重组）。 */
    val remotePrefs: StateFlow<RemoteControlPrefs> = _remotePrefsFlow.asStateFlow()

    internal val _remoteScanning = MutableStateFlow(false)

    /** 正在扫描/连接。 */
    val remoteScanning: StateFlow<Boolean> = _remoteScanning.asStateFlow()

    internal val _remoteDevices = MutableStateFlow<List<LanDiscovery.DiscoveredDevice>>(emptyList())

    /** 局域网里发现的插件。 */
    val remoteDevices: StateFlow<List<LanDiscovery.DiscoveredDevice>> =
        _remoteDevices.asStateFlow()

    internal val _remoteScanMessage = MutableStateFlow("")

    /** 扫描结果的一句话说明（没找到时给原因）。 */
    val remoteScanMessage: StateFlow<String> = _remoteScanMessage.asStateFlow()

    internal val _remoteCandidate = MutableStateFlow("")

    /** 本轮实际尝试连接的地址，界面上显示出来，方便用户知道连的是哪个。 */
    val remoteCandidate: StateFlow<String> = _remoteCandidate.asStateFlow()

    internal var remoteJob: Job? = null

    /**
     * 自己的连接编排是否在跑。
     *
     * 用来区分"忙"是谁造成的：App 启动时 [com.audiometa.app.App] 会按手动地址
     * 直连一次（外部直连，失败后要由我们兜底到记住地址/扫描）；
     * 自己的候选竞速在跑时则不能被重复触发的 refreshRemote 打断。
     */
    private var orchestrating = false

    /** 启动时那次"3 秒确认离线"的扫描是否已经发起过（进程内只跑一次）。 */
    private var startupScanDone = false

    /** 启动扫描正在进行。refreshRemote 看到它在跑就不打断。 */
    private var startupScanning = false

    /**
     * 设置配对码。
     *
     * 配对码**不影响已建立的连接**（插件只在握手时校验一次），
     * 所以这里传 pushToClient=false：改完由调用方显式重连，
     * 避免用户一边打字一边把连接打断又重建。
     */
    fun setRemoteToken(value: String) {
        mutateRemotePrefs(pushToClient = false) { it.copy(token = value.trim()) }
        edit { ServiceLocator.settings.setRemoteToken(value) }
    }

    /**
     * 「控制」页的「打开网络遥控设置」用这个。
     *
     * 只负责"记住要去哪一页"（[consumePendingSection]），
     * 真正的 section 切换由设置页在**首次组合期间**用 initialSection 完成 ——
     * 这样不会出现"先渲染旧页再切过去"的闪烁。
     */
    fun openRemoteSettings() {
        pendingSection = SettingsSection.REMOTE
    }

    /** 设置页取走"要打开哪一页"，取一次就清空。 */
    fun consumePendingSection(): SettingsSection? {
        val v = pendingSection
        pendingSection = null
        return v
    }

    /**
     * 更新内存快照。**所有 setter 都走这里**，保证界面状态和内部状态不会分叉。
     *
     * @param pushToClient 是否立刻把连接参数推给客户端（改地址/端口/TLS/开关时 true，
     *        改自动连接、心跳这类"不影响当前连接"的项时传 false）。
     */
    internal fun mutateRemotePrefs(
        pushToClient: Boolean,
        transform: (RemoteControlPrefs) -> RemoteControlPrefs
    ) {
        remotePrefsValue = transform(remotePrefsValue)
        _remotePrefsFlow.value = remotePrefsValue
        if (pushToClient) pushRemotePrefsToClient()
    }

    /**
     * 把内存快照推给连接客户端。
     *
     * **只在地址、端口、TLS、启用状态这些"连接参数"真的变了才动连接** ——
     * 上一版之所以闪烁，就是因为配置对象每次都是新的、`apply()` 无脑重连。
     */
    private fun pushRemotePrefsToClient() {
        val next = remotePrefsValue
        val applied = ServiceLocator.remoteControl.appliedPrefs()
        val connectionArgsChanged = applied == null ||
            applied.host != next.host ||
            applied.port != next.port ||
            applied.useTls != next.useTls ||
            applied.enabled != next.enabled

        if (connectionArgsChanged) {
            ServiceLocator.remoteControl.apply(next)
        } else if (applied != null && applied.heartbeat != next.heartbeat) {
            ServiceLocator.remoteControl.apply(next)
        }
    }

    /**
     * 启动时的快速在线确认（用户要求：**3 秒内**确认 PC 播放器是否离线）。
     *
     * 和 [refreshRemote] 的慢编排的区别只在**时间预算和并发方式**，
     * 规则本身仍是"已有规则"：记住的地址（按当前网络排序）+ 局域网发现。
     *
     * ```
     * 0s ── 记住的地址并发竞速 ─┐
     *      └ 局域网探测（1.5s）  ├─ 任一成功 → 连上，结束
     * 3s ── 都没结果 → 明确判定离线（markOffline），控制页立刻显示"电脑不在线"
     * ```
     *
     * 以前启动走的是慢编排：直连等 8 秒 → 记住的地址每个 5 秒 → 才扫局域网，
     * 用户要等十几秒才知道电脑没开。这里把三条路并发、预算压到 3 秒。
     */
    fun startupRemoteScan() {
        if (startupScanDone) return
        startupScanDone = true
        val cfg = remotePrefsValue
        if (!cfg.enabled) return
        if (ServiceLocator.remoteControl.state.value.isLive) return
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch { runStartupScan(cfg) }
    }

    private suspend fun runStartupScan(cfg: RemoteControlPrefs) {
        startupScanning = true
        _remoteScanning.value = true
        _remoteMessageStale.value = false
        _remoteScanMessage.value = "正在确认电脑是否在线…"
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + STARTUP_OFFLINE_BUDGET_MS
        try {
            // 局域网探测和"记住的地址"同时开始，不排队。
            val discovery = viewModelScope.async(Dispatchers.IO) {
                LanDiscovery.scan(
                    getApplication(),
                    LanDiscovery.UDP_PORT,
                    STARTUP_DISCOVERY_MS
                )
            }

            /*
             * 进程启动时 [com.audiometa.app.App] 可能已经按手动/上次地址直连了一次。
             * 先给它一点时间落地，别在**同一秒里对同一台电脑开两条 socket** ——
             * 那既费电，也会让插件的连接配额瞬间被自己占掉。
             */
            if (ServiceLocator.remoteControl.state.value.isBusy) {
                val wait = minOf(STARTUP_DIRECT_WAIT_MS, STARTUP_OFFLINE_BUDGET_MS / 3)
                when (awaitConnectionSettled(wait)) {
                    true -> {
                        rememberConnectedEndpoint()
                        _remoteScanMessage.value = ""
                        return
                    }
                    // 要配对码：停下来等用户输入，不是"离线"
                    null -> return
                    false -> Unit
                }
            }

            val remembered = loadRememberedCandidates()
            if (remembered.isNotEmpty()) {
                ServiceLocator.remoteControl.connectCandidates(remembered, concurrent = true)
            } else if (cfg.hasTarget) {
                ServiceLocator.remoteControl.apply(cfg)
                val st = ServiceLocator.remoteControl.state.value
                if (!st.isLive && !st.isBusy) ServiceLocator.remoteControl.connect()
            }

            var discoveryHandled = false
            while (System.currentTimeMillis() < deadline) {
                when (ServiceLocator.remoteControl.state.value) {
                    is RemoteConnectionState.Connected -> {
                        rememberConnectedEndpoint()
                        _remoteScanMessage.value = ""
                        return
                    }
                    // 要配对码：不是"离线"，停下来等用户输入。
                    is RemoteConnectionState.Unauthorized -> return
                    else -> Unit
                }
                // 局域网先回来：落库 + 只有一台且允许自动连接就直接连它。
                if (!discoveryHandled && discovery.isCompleted) {
                    discoveryHandled = true
                    val result = runCatching { discovery.await() }.getOrNull()
                    if (result != null && !result.isEmpty) {
                        _remoteDevices.value = result.devices
                        rememberDeviceNames(result.devices)
                        persistDevices(result.devices)
                        val only = result.devices.singleOrNull()
                        if (only != null && cfg.autoConnect) connectToDevice(only)
                    }
                }
                delay(80)
            }

            if (!ServiceLocator.remoteControl.state.value.isLive) {
                ServiceLocator.remoteControl.markOffline(
                    "电脑不在线：3 秒内没有发现播放器（局域网与记住的地址都试过）"
                )
                _remoteScanMessage.value = "电脑不在线（3 秒内未发现播放器）"
            }
        } finally {
            startupScanning = false
            _remoteScanning.value = false
            _remoteMessageStale.value = true
        }
    }

    /**
     * 查找 / 自动连接入口（「查找设备」按钮、控制页进入时、启动自动连接都走这里）。
     *
     * 已连接或正在连接时直接返回 —— 不能把一条好好的连接打断重扫。
     *
     * 其余情况进入 [runAutoConnect] 编排，用户要求的顺序是：
     *   1. 手动指定 / 上次实际连通的地址 —— 最高优先，直接连；
     *   2. 设备库里记住的地址（局域网 / IPv6 / 公网），按**当前网络**
     *      （Wi-Fi 还是移动网络）排序后竞速尝试；
     *   3. 上面都连不到 —— 扫一轮局域网，只发现一台设备时自动连上。
     *
     * 扫描是一次性的（[LanDiscovery.scan] 约 2 秒），不会无限转圈。
     */
    fun refreshRemote() {
        val cfg = remotePrefsValue
        // 启动扫描还在 3 秒预算里：**不要打断它**，否则一进控制页就会退回慢编排，
        // "3 秒内确认离线"就永远不可能达成。
        if (startupScanning) return
        if (!cfg.enabled) {
            ServiceLocator.remoteControl.disconnect()
            clearScanMessage()
            return
        }

        val conn = ServiceLocator.remoteControl.state.value
        if (conn.isLive) {
            _remoteScanMessage.value = ""
            _remoteMessageStale.value = true
            return
        }
        // 自己的编排（候选竞速/扫描）还在跑：不打断
        if (conn.isBusy && orchestrating) return

        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            var skipDirect = false
            /*
             * 外部直连（App 启动 apply、手动开关）正在进行：
             * 等它落地 —— 连上/要配对码就收工；失败了再走兜底编排，
             * 且跳过"再直连同一个手动地址一次"。
             */
            if (conn.isBusy) {
                _remoteScanning.value = true
                _remoteMessageStale.value = false
                if (awaitConnectionSettled(8_000L) != false) {
                    _remoteScanning.value = false
                    _remoteMessageStale.value = true
                    return@launch
                }
                skipDirect = true
            }
            runAutoConnect(cfg, skipDirect = skipDirect)
        }
    }

    /** 自动连接编排，见 [refreshRemote]。只在未连接时由它调用。 */
    private suspend fun runAutoConnect(
        cfg: RemoteControlPrefs,
        skipDirect: Boolean = false
    ) {
        _remoteScanning.value = true
        _remoteMessageStale.value = false
        orchestrating = true
        try {
            if (cfg.fixedLan) {
                runFixedLanConnect(cfg)
            } else {
                runRegularConnect(cfg, skipDirect)
            }
        } finally {
            orchestrating = false
            _remoteScanning.value = false
            _remoteMessageStale.value = true
        }
    }

    /**
     * 普通模式（没标「固定内网 IP」）：
     *   1. 手动指定 / 上次实际连通的地址 —— 最高优先，直接连；
     *   2. 设备库里记住的地址（局域网 / IPv6 / 公网），按**当前网络**
     *      （Wi-Fi 还是移动网络）排序后竞速尝试；
     *   3. 上面都连不到 —— 扫一轮局域网，只发现一台设备时自动连上。
     */
    private suspend fun runRegularConnect(
        cfg: RemoteControlPrefs,
        skipDirect: Boolean = false
    ) {
        // 1) 指定地址：用户手填的域名/IP，或上次连通后写回的那个地址
        //    （App 启动时已经直连过且失败时 skipDirect=true，别再拨同一个）
        if (!skipDirect && cfg.hasTarget) {
            _remoteScanMessage.value = "正在连接 ${cfg.hostLabel}…"
            ServiceLocator.remoteControl.apply(cfg)
            val st = ServiceLocator.remoteControl.state.value
            // apply() 在"开关没变"时不会主动发起连接，这里补一次
            if (!st.isLive && !st.isBusy) ServiceLocator.remoteControl.connect()
            // 直连只给 8 秒：连不到就换策略，别死等
            if (awaitConnectionSettled(8_000L) != false) return
        }

        // 2) 设备库里记住的地址：Wi-Fi 下局域网优先，移动网络公网/IPv6 优先
        val remembered = loadRememberedCandidates()
        if (remembered.isNotEmpty()) {
            _remoteScanMessage.value = "正在连接上次的电脑…"
            ServiceLocator.remoteControl.connectCandidates(remembered)
            val deadline = remembered.size * 5_000L + 3_000L
            if (awaitConnectionSettled(deadline) != false) return
        }

        // 3) 都不通：扫局域网
        scanLanForDevices(cfg)
    }

    /**
     * 固定内网 IP 模式（用户在地址列表顶部确认过"这台电脑的局域网 IP 不变"）。
     *
     * 三段，任一段连上（或要求配对码）即返回；**三段全失败判定电脑离线**，
     * 留在 Failed 等用户手动重试，**绝不回退扫局域网**：
     *   1. 固定局域网 IP；若还填了指定域名，两者**并发竞速**（局域网优先）；
     *   2. 记住的 IPv6 地址；
     *   3. 记住的公网 IPv4 地址。
     */
    private suspend fun runFixedLanConnect(cfg: RemoteControlPrefs) {
        // 第一段：固定局域网 IP（+ 指定域名并发）
        val firstWave = ArrayList<Pair<RemoteControlPrefs, String>>()
        val lanHost = cfg.fixedLanHost.ifBlank {
            loadRememberedByKind("lan").firstOrNull()?.first?.host.orEmpty()
        }
        if (lanHost.isNotBlank()) {
            firstWave += cfg.copy(host = lanHost) to "固定局域网"
        }
        // 只有"看起来是域名"的指定地址才并发；写回的 IP 不重复拨
        val domain = cfg.host.takeIf {
            it.isNotBlank() && isDomainName(it) && !it.equals(lanHost, ignoreCase = true)
        }
        if (domain != null) {
            firstWave += cfg to "指定域名"
        }
        if (firstWave.isNotEmpty()) {
            _remoteScanMessage.value = "正在连接固定内网地址…"
            ServiceLocator.remoteControl.connectCandidates(firstWave, concurrent = true)
            if (awaitConnectionSettled(firstWave.size * 5_000L + 3_000L) != false) return
        }

        // 第二段：IPv6（失败一次后才轮到）
        val v6 = loadRememberedByKind("ipv6")
        if (v6.isNotEmpty()) {
            _remoteScanMessage.value = "局域网不通，正在尝试 IPv6…"
            ServiceLocator.remoteControl.connectCandidates(v6)
            if (awaitConnectionSettled(v6.size * 5_000L + 3_000L) != false) return
        }

        // 第三段：公网 IPv4
        val v4 = loadRememberedByKind("pub")
        if (v4.isNotEmpty()) {
            _remoteScanMessage.value = "正在尝试公网 IPv4 地址…"
            ServiceLocator.remoteControl.connectCandidates(v4)
            if (awaitConnectionSettled(v4.size * 5_000L + 3_000L) != false) return
        }

        // 三段全失败：SPW 离线。客户端状态已是 Failed，这里只补一句人话
        _remoteScanMessage.value = "电脑不在线：固定内网 / IPv6 / 公网都连不上"
    }

    /** 简单判断 host 是不是域名（排除 IPv4 字面量和 IPv6）。 */
    private fun isDomainName(host: String): Boolean {
        if (host.contains(':')) return false // IPv6
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        // IPv4：四段、全是 0..255 的数字
        if (parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 }) return false
        // 顶级段至少得有字母（example.local / music.example.com）
        return parts.last().any { it.isLetter() }
    }

    /**
     * 从库里取指定类型的记住地址（固定 IP 模式分段用）。
     *
     * 不经过 [RemoteAddressPolicy]：那是"按当前网络重排"的普通模式逻辑；
     * 固定模式的顺序由用户的三段策略决定，不能被 Wi-Fi/蜂窝打乱。
     */
    private suspend fun loadRememberedByKind(
        vararg kinds: String
    ): List<Pair<RemoteControlPrefs, String>> {
        val want = kinds.toSet()
        val labels = mapOf("lan" to "局域网", "ipv6" to "IPv6", "pub" to "公网 IPv4")
        return runCatching {
            val out = ArrayList<Pair<RemoteControlPrefs, String>>()
            for (device in ServiceLocator.remoteDevices.devices()) {
                for (a in ServiceLocator.remoteDevices.addressesOf(device.id)) {
                    if (a.kind !in want) continue
                    out.add(
                        remotePrefsValue.copy(host = a.host, port = a.port) to
                            (labels[a.kind] ?: a.kind)
                    )
                }
            }
            out.distinctBy { it.first.host.lowercase() to it.first.port }
        }.onFailure {
            com.audiometa.app.util.AppLog.w("RemoteControl", "读记住的地址失败：" + it.message)
        }.getOrDefault(emptyList())
    }

    /**
     * 等当前这轮连接出结果。
     *
     * @return true=已连接；false=失败/超时（调用方继续下一策略）；
     *         null=需要配对码（停下等用户输入，别再扫了）
     */
    private suspend fun awaitConnectionSettled(timeoutMs: Long): Boolean? {
        val client = ServiceLocator.remoteControl
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            when (client.state.value) {
                is RemoteConnectionState.Connected -> {
                    // 连通的地址写回设置：下次启动第一步就能直接连它
                    rememberConnectedEndpoint()
                    return true
                }
                is RemoteConnectionState.Unauthorized -> return null
                is RemoteConnectionState.Failed -> {
                    /*
                     * 固定 IP 三段衔接处，新一批 socket 还没来得及改状态，
                     * 这里可能读到上一段残留的 Failed —— 开头 700ms 内忽略它。
                     */
                    if (System.currentTimeMillis() - start > 700L) return false
                }
                else -> Unit
            }
        }
        return false
    }

    /** 把本次实际连通的地址（可能是候选竞速选出来的）持久化到设置。 */
    private fun rememberConnectedEndpoint() {
        val ep = ServiceLocator.remoteControl.activeEndpoint.value
        if (ep.isBlank()) return
        val host = RemoteControlPrefs.sanitizeHost(ep)
        val port = RemoteControlPrefs.extractPort(ep) ?: return
        if (host.isBlank()) return
        val cur = remotePrefsValue
        /*
         * 固定内网 IP 模式：用户手填的域名不能被实际连通地址覆盖 ——
         * 赢的可能是 v6/公网（第一段没通），域名下次启动还要并发用。
         */
        if (cur.fixedLan && isDomainName(cur.host)) return
        if (host == cur.host && port == cur.port) return
        mutateRemotePrefs(pushToClient = false) { it.copy(host = host, port = port) }
        edit {
            ServiceLocator.settings.setRemoteHost(host)
            ServiceLocator.settings.setRemotePort(port)
        }
    }

    /** 最后一步：扫一轮局域网，单设备且允许自动连接就直接连。 */
    private suspend fun scanLanForDevices(cfg: RemoteControlPrefs) {
        _remoteDevices.value = emptyList()
        _remoteScanMessage.value = "正在扫描局域网…"

        val result = LanDiscovery.scan(getApplication(), LanDiscovery.UDP_PORT)
        _remoteDevices.value = result.devices

        if (!result.isEmpty) {
            rememberDeviceNames(result.devices)
            // 落库：设备名 + 广播里的地址，供下次启动直接用
            persistDevices(result.devices)
        }

        when {
            result.isEmpty ->
                _remoteScanMessage.value = result.error ?: "没有找到电脑"
            cfg.autoConnect && result.devices.size == 1 ->
                connectToDevice(result.devices.first())
            else ->
                _remoteScanMessage.value =
                    "找到 ${result.devices.size} 台设备，请选择"
        }
    }

    /**
     * 把一轮扫描的结果落库。
     *
     * 键用「插件 ID + 设备名」：同一台电脑换 IP 不会新增一行，
     * 地址则整体替换 —— 这样不会留下过期的孤立地址。
     */
    private suspend fun persistDevices(devices: List<LanDiscovery.DiscoveredDevice>) {
        runCatching {
            for (d in devices) {
                val key = d.pluginId + "|" + d.name
                val now = System.currentTimeMillis()
                ServiceLocator.remoteDevices.save(
                    device = com.audiometa.app.data.db.RemoteDeviceEntity(
                        deviceKey = key,
                        name = d.name,
                        pluginId = d.pluginId,
                        pluginVersion = d.version,
                        wsPort = d.wsPort,
                        lastSeenAt = now
                    ),
                    addresses = d.endpoints.map { ep ->
                        com.audiometa.app.data.db.RemoteAddressEntity(
                            deviceId = 0,
                            kind = ep.kind,
                            host = ep.host,
                            port = d.wsPort,
                            updatedAt = now
                        )
                    }
                )
            }
            // 顺手清掉很久没出现的设备（连同地址）
            ServiceLocator.remoteDevices.pruneStale()
        }.onFailure {
            com.audiometa.app.util.AppLog.w("RemoteControl", "设备地址落库失败：" + it.message)
        }
    }

    /**
     * 鉴权后插件自报的完整地址落库。
     *
     * UDP 广播故意不带地址（[persistDevices] 只能记下广播来源的那个局域网 IP），
     * IPv6 / 公网地址是配对后 `netinfo` 才下发的 —— 不在这里补落库，
     * 固定内网 IP 模式第二、三段（v6 / 公网）下次启动就无地址可试。
     *
     * 设备归属：和库里已有地址**按 host 相交**匹配（同一台插件的地址集合必然相交）。
     * 匹配不到（用户手输地址直连、从没扫过局域网）就建一行「我的电脑」占位，
     * 设备键用首个局域网地址，下次换 IP 扫描时 [persistDevices] 会按真实设备另建 —
     * 占位行到期会被 pruneStale 清掉，不产生永久垃圾。
     */
    private suspend fun persistNetinfoAddresses(list: List<RemoteControlClient.RemoteAddress>) {
        runCatching {
            val now = System.currentTimeMillis()
            val repo = ServiceLocator.remoteDevices
            val hosts = list.map { it.host }.toSet()
            val existing = repo.devices().firstOrNull { d ->
                repo.addressesOf(d.id).any { it.host in hosts }
            }
            val port = list.firstOrNull()?.port
                ?: existing?.wsPort
                ?: RemoteControlPrefs.DEFAULT_PORT
            val row = existing?.copy(wsPort = port, lastSeenAt = now)
                ?: com.audiometa.app.data.db.RemoteDeviceEntity(
                    deviceKey = "manual|" +
                        (list.firstOrNull { it.kind == "lan" }?.host ?: list.first().host),
                    name = "我的电脑",
                    pluginId = "",
                    wsPort = port,
                    lastSeenAt = now
                )
            repo.save(
                device = row,
                addresses = list.map {
                    com.audiometa.app.data.db.RemoteAddressEntity(
                        deviceId = 0,
                        kind = it.kind,
                        host = it.host,
                        port = it.port,
                        updatedAt = now
                    )
                }
            )
            com.audiometa.app.util.AppLog.i(
                "RemoteControl",
                "插件地址已落库：${list.size} 个（${row.name}）"
            )
        }.onFailure {
            com.audiometa.app.util.AppLog.w("RemoteControl", "netinfo 地址落库失败：" + it.message)
        }
    }

    /**
     * 从库里取回记住的地址，按当前网络类型排好序。
     *
     * 用 Wi-Fi → 局域网优先；用移动网络 → 公网/IPv6 优先。
     * 指定了域名则只返回域名（由 [RemoteAddressPolicy] 处理）。
     */
    private suspend fun loadRememberedCandidates(): List<Pair<RemoteControlPrefs, String>> {
        return runCatching {
            val ctx = getApplication<Application>()
            val out = ArrayList<Pair<RemoteControlPrefs, String>>()
            for (device in ServiceLocator.remoteDevices.devices()) {
                val addrs = ServiceLocator.remoteDevices.addressesOf(device.id)
                if (addrs.isEmpty()) continue
                val ordered = RemoteAddressPolicy.order(ctx, addrs, remotePrefsValue.takeIf { it.hasTarget })
                for (c in ordered) {
                    out.add(c.copy(token = remotePrefsValue.effectiveToken) to device.name)
                    _activeDeviceName.value = device.name
                }
            }
            out.distinctBy { it.first.host to it.first.port }
        }.onFailure {
            com.audiometa.app.util.AppLog.w("RemoteControl", "读记住的地址失败：" + it.message)
        }.getOrDefault(emptyList())
    }

    /** 等一轮连接结果，结束后把提示标记为过期（小字不该常驻）。 */
    internal fun watchConnectionResult() {
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            _remoteScanning.value = true
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                val st = ServiceLocator.remoteControl.state.value
                if (st.isLive) break
                if (st is com.audiometa.app.data.prefs.RemoteConnectionState.Failed) break
            }
            _remoteScanning.value = false
            _remoteMessageStale.value = true
        }
    }

    /**
     * 记住设备名，供连接状态区显示"当前连接的设备名"。
     *
     * 单独存一份：连接建立后扫描结果可能已经被清掉，
     * 但状态区还是要知道自己连的是哪台机器。
     */
    internal fun rememberDeviceNames(devices: List<LanDiscovery.DiscoveredDevice>) {
        for (d in devices) {
            for (ep in d.orderedEndpoints) {
                deviceNameByHost["${ep.host}:${d.wsPort}"] = d.name
                deviceNameByHost[ep.host] = d.name
            }
        }
    }

    internal fun clearScanMessage() {
        _remoteScanMessage.value = ""
        _remoteMessageStale.value = true
    }

    fun remotePlay() = ServiceLocator.remoteControl.play()

    fun remotePause() = ServiceLocator.remoteControl.pause()

    fun remoteNext() = ServiceLocator.remoteControl.next()

    fun remotePrevious() = ServiceLocator.remoteControl.previous()

    /**
     * 控制页拖进度条松手 / 点进度条。
     *
     * 两条线**同时**走：
     *   1. 发 seek 给电脑；
     *   2. 如果开着「同步播放音频」且本机正在放同一首，本地播放器**立刻** seek 过去。
     *
     * 第 2 步不能等电脑回推：回推要走一个网络往返，而且 UI 那边按 5 秒分桶
     * 触发纠偏，本地声音会先在旧位置继续响、再猛地跳一次 —— 用户听到的就是
     * "卡顿一下，才从跳转后的时间开始播"。松手即跳，那一下 flush 与用户手势
     * 同时发生，属于正常反馈；TrackPlayer 内部还有 2.5 秒抑制窗，回推到达时
     * 不会再 seek 第二次。
     */
    fun remoteSeekTo(positionMs: Long) {
        ServiceLocator.remoteControl.seekTo(positionMs)
        if (remotePrefsValue.syncLocalAudio) {
            val np = _nowPlaying.value
            if (np.matched && np.localPath.isNotBlank() &&
                ServiceLocator.remoteControl.track.value.playing
            ) {
                ServiceLocator.localPlayer.seekIfCurrent(np.localPath, positionMs)
            }
        }
    }

    /**
     * 「控制」页右上角刷新：重新获取当前播放信息与进度。
     *
     * 两件事一起做：
     *   1. 让插件重读宿主当前曲目并回推 state（换歌漏推、刚开始播时用得上）；
     *   2. 顺手要一次地址列表（可能网络环境变了）。
     */
    fun remoteReloadNowPlaying() {
        ServiceLocator.remoteControl.requestState()
        ServiceLocator.remoteControl.requestAddresses()
        // 强制重新解析本机封面 / 歌词 / 时长：用户点刷新就是想让它重新找一遍
        resolveLocalArtifacts(ServiceLocator.remoteControl.track.value, force = true)
    }

    @Volatile
    internal var pendingSection: SettingsSection? = null

    internal val deviceNameByHost = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal val _activeDeviceName = MutableStateFlow("")

    /** 当前连接的设备名。 */
    val activeDeviceName: StateFlow<String> = _activeDeviceName.asStateFlow()

    /** 提示已经过期（连上/连失败之后），页面上不该再显示。 */
    private val _remoteMessageStale = MutableStateFlow(false)
    val remoteMessageStale: StateFlow<Boolean> = _remoteMessageStale.asStateFlow()

    /**
     * 给界面看的一句"为什么现在没连上"。
     *
     * 连接是进程级在后台推进的，用户回到控制页时可能只看到一个"重连中"的
     * 状态字 —— 这里把客户端记下的**具体原因**（心跳超时 / 服务端关闭 /
     * 连接被拒绝…）暴露出来，用户不必去翻日志就知道为什么断了。
     */
    val connectionNotice: StateFlow<String> = ServiceLocator.remoteControl.lastDisconnectReason

    /**
     * 有曲目变化时，去本机曲库找同一首歌并取封面 / 歌词来源 / 时长。
     *
     * 由「控制」页在 track 变化时调用，也由重连成功（连接代次变化）时调用。
     * 放在 ViewModel 里是为了让解析协程跟着 ViewModel 生命周期走，页面切走也不会泄漏。
     *
     * ## 什么时候**不**动界面上的封面/时长（用户报的"封面消失"就是这里）
     *
     * 老逻辑：`if (!track.hasTrack) { _nowPlaying = NowPlaying() }`。
     * `RemoteControlClient` 一掉线就把 `track` 清成 EMPTY，于是**一次瞬时断线**
     * 就把已经匹配好的封面/时长/歌词全擦掉，界面显示成"本机曲库里没有这首歌"
     * ——而本机其实有。现在：
     *   - 空快照：直接返回，什么都不改（断线、重连、宿主还没推 state 都算）；
     *   - 同一首歌（key 没变）且已经有匹配结果：也不再重复解析；
     *   - key 没变但结果**是空的**（上次解析失败/上一轮被清过）：重新解析一次，
     *     这就是"回到控制页之后封面能自己回来"；
     *   - 内存里已有这首歌的匹配结果（[RemoteTrackResolver.lastMatched]）时先拿它
     *     顶上，界面立刻有封面/时长，再去后台刷新一遍。
     *
     * @param force true=忽略 key 去重，强制重新解析（用户点刷新、重连成功时用）
     */
    fun resolveLocalArtifacts(
        track: com.audiometa.app.data.prefs.RemoteTrack,
        force: Boolean = false
    ) {
        // 空快照：保留上一次的匹配结果，绝不擦掉。断线不该让封面/时长消失，
        // 更不该让同步播放停下来（见 applyAudioSync）。
        if (!track.hasTrack) return

        val key = RemoteTrackResolver.keyOf(track)

        /*
         * 内存里还有这首歌的匹配结果就先顶上。
         *
         * 覆盖两种"界面空白"的场景：
         *   1. ViewModel 被重建（进程还在、Activity 重建）后 `_nowPlaying` 是空的，
         *      但解析器里还留着上一次的结果 —— 直接恢复，不用重新解码封面；
         *   2. 重新解析是异步的，先给一帧旧结果比先给一帧占位图好。
         */
        val cached = RemoteTrackResolver.lastMatched()
        if (cached != null && cached.trackKey == key &&
            (force || _nowPlaying.value.trackKey != key)
        ) {
            _nowPlaying.value = cached
        }

        if (!force) {
            if (key == lastResolveKey) {
                // 同一首歌、结果也还在：不用再解析一遍
                if (_nowPlaying.value.trackKey == key) return
                // 结果被清掉过（比如上一轮断线时）→ 落到下面重新解析补回来
            }
        }
        lastResolveKey = key

        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            // 解析要读数据库、读标签、**解一张封面图** —— 明确放到 IO 上，
            // 别让它占着主线程（控制页每秒都在重组，主线程不该被磁盘 IO 卡住）。
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RemoteTrackResolver.resolve(getApplication(), track)
                }
            }.getOrNull()
            if (result == null) {
                // 解析真的失败了（IO/数据库异常）：不锁定 key，下次还能再试，
                // 免得界面永远停在占位图上。
                lastResolveKey = ""
                AppLog.w(TAG_REMOTE, "本机匹配解析失败，下次会重试：" + track.title)
                return@launch
            }
            _nowPlaying.value = result
            // 用 i 而不是 d：默认日志门槛是 INFO，d 级的匹配结果用户在
            // "调试"页里根本看不到 —— 而"本机到底有没有这首歌、时长多少、
            // 封面有没有"正是排查这三个问题时最需要的一行。
            AppLog.i(
                TAG_REMOTE,
                "本机匹配：" + (if (result.matched) "命中" else "未命中") +
                    "（时长 ${result.durationMs}ms，封面 " +
                    (if (result.cover != null) "有" else "无") + "）：" + track.title
            )
            // 曲目解析完就顺手对齐本地音频（开关打开时才有动作）
            applyAudioSync()
        }
    }

    /**
     * 取某个播放位置对应的歌词三行（上一行 / 当前行 / 下一行）+ 当前翻译。
     *
     * 三行是用户要求的：只看当前行看不出上下文。
     * 纯内存查表，不读文件（[RemoteTrackResolver] 内部按文件路径缓存了解析结果）。
     *
     * **传入的 positionMs 会先被本地插值修正**，见 [interpolatedPosition]。
     */
    fun lyricsTrio(positionMs: Long): RemoteTrackResolver.LyricTrio {
        val pos = interpolatedPosition(positionMs)
        // 本机文件里有歌词就用本机的；本机没有（或没匹配到）才用 PC 插件下发的完整时间轴。
        if (RemoteTrackResolver.hasLyrics()) return RemoteTrackResolver.trioAt(pos)
        return ServiceLocator.remoteControl.pcLyrics.value?.trioAt(pos)
            ?: RemoteTrackResolver.trioAt(pos)
    }

    /**
     * 位置插值：让歌词"跟得上"声音。
     *
     * ## 为什么需要
     *
     * 宿主的 `onPositionUpdated` **每秒才推一次**，加上网络往返与广播节流，
     * 手机拿到位置时它已经是 0.3~1.3 秒之前的值了。直接拿它查歌词行，
     * 表现就是**歌词总比声音慢半拍**。
     *
     * ## 做法
     *
     * 记住"上次收到位置的时间和值"，之后每次查询按经过的墙钟时间往前推。
     * 两次推送之间位置连续增长，歌词换行看起来就是准点到的。
     *
     * 上限 [INTERPOLATION_CAP_MS]：只在正常播放时往前推。暂停、断线由调用方
     * 停止推进；这里留一个足够长的上限，避免异常情况下无限跑。
     */
    private fun interpolatedPosition(rawPositionMs: Long): Long {
        // 位置值变了 → 这是一次新推送，重新打时间戳
        if (rawPositionMs != lastRawPositionMs) {
            lastRawPositionMs = rawPositionMs
            lastRawPositionAt = System.currentTimeMillis()
            return rawPositionMs
        }
        val elapsed = System.currentTimeMillis() - lastRawPositionAt
        if (elapsed <= 0) return rawPositionMs
        return rawPositionMs + elapsed.coerceAtMost(INTERPOLATION_CAP_MS)
    }

    /**
     * 给进度条用的插值位置（毫秒）。
     *
     * 和歌词同一套插值 —— 宿主每秒才推一次 `pos`，直接拿它画进度条就是
     * "一秒跳一格"。界面用一个小小的定时器（250ms）反复问这个函数，
     * 进度条就会连续前进。
     *
     * 两条自我保护：
     *   - **暂停时不插值**：[playing] 为 false 就把 `pos` 当成冻结的真值
     *     （宿主暂停后仍会推同样的 pos，插值会表现成"暂停了进度条还在爬"）；
     *   - **单调不回退**：两次推送之间的小抖动不会让进度条往回跳；真的回退
     *     （> [POSITION_JUMP_BACK_MS]，也就是跳转/换歌/重连新基线）时才松开下限。
     */
    fun positionForUi(rawPositionMs: Long, playing: Boolean): Long {
        // 暂停→恢复：不能把暂停期间的时间算进播放进度。
        if (playing != lastPlayingForUi) {
            lastPlayingForUi = playing
            lastRawPositionMs = rawPositionMs
            lastRawPositionAt = System.currentTimeMillis()
            lastInterpolatedMs = rawPositionMs
        }
        /*
         * 倒退（回退超过 2 秒）一定是**真的跳了**：新连接的第一次推送、
         * 用户拖了进度条、换了一首歌。这时候单调下限必须松开，
         * 否则进度条会卡在上一次的大值上不动。
         */
        if (lastRawForUi >= 0 && rawPositionMs < lastRawForUi - POSITION_JUMP_BACK_MS) {
            lastInterpolatedMs = -1L
        }
        lastRawForUi = rawPositionMs
        // 暂停：位置就是真值，重新打时间戳，恢复播放时从当前位置继续推
        if (!playing) {
            lastRawPositionMs = rawPositionMs
            lastRawPositionAt = System.currentTimeMillis()
            lastInterpolatedMs = rawPositionMs
            return rawPositionMs
        }

        val value = interpolatedPosition(rawPositionMs)
        if (value > lastInterpolatedMs) lastInterpolatedMs = value
        return lastInterpolatedMs
    }

    /** 重连成功后清掉位置插值的基线（新连接的位置是全新的，不能接着旧的推）。 */
    fun resetPositionInterpolation() {
        lastRawPositionMs = -1L
        lastRawPositionAt = 0L
        lastInterpolatedMs = -1L
        lastRawForUi = -1L
        lastPlayingForUi = false
    }

    // ---------------- 同步播放本地音频 ----------------

    /**
     * 同步播放本地音频的开关。默认关。
     *
     * 打开后：电脑在放哪首，手机就用**本机曲库**里匹配到的那一首一起放。
     * 音频来自本机曲库（内嵌标签里读到的同一首歌），位置对齐一次
     * —— 用 [TrackPlayer.playAt]，在起播前就 seek 到位，
     * 不会出现"先播开头再突然跳过去"。
     */
    val syncLocalAudio: Boolean get() = remotePrefsValue.syncLocalAudio

    private var lastRawPositionMs: Long = -1
    private var lastRawPositionAt: Long = 0

    /** 已经给过界面的最大插值位置：单调不回退，进度条才不会来回抖。 */
    private var lastInterpolatedMs: Long = -1

    /** [positionForUi] 上一次拿到的原始位置，用来识别"真的往回跳了"。 */
    private var lastRawForUi: Long = -1
    /** [positionForUi] 上一次的播放状态：暂停→恢复时重置插值基线。 */
    private var lastPlayingForUi: Boolean = false

    private companion object {
        /** 远程控制相关日志的 tag，和 ServiceLocator 那一侧保持一致。 */
        const val TAG_REMOTE = "RemoteControl"

        /** 位置偏差超过这个值才纠一次（毫秒）。太小会一直 seek，太大会明显不同步。 */
        const val SYNC_TOLERANCE_MS = 1_500L

        /** 启动时判定"电脑离线"的总预算（用户要求 3 秒或更短）。 */
        const val STARTUP_OFFLINE_BUDGET_MS = 3_000L

        /**
         * 启动扫描里局域网探测的收集窗口。
         *
         * probe 的回应通常在 100ms 内到，1.5 秒足够覆盖插件只做周期广播的情况，
         * 又给"记住的地址"留出足够时间在同一预算内出结果。
         */
        const val STARTUP_DISCOVERY_MS = 1_500L

        /**
         * 启动扫描里"等外部直连落地"的上限。
         *
         * 进程启动时 App 已经按手动地址连了一次；这里只给它一小段时间，
         * 免得在 3 秒预算里既等连接又开新连接，两条 socket 抢同一台电脑。
         */
        const val STARTUP_DIRECT_WAIT_MS = 1_200L

        /**
         * 歌词位置插值上限（毫秒）。
         *
         * 1200ms 略大于宿主的推送间隔（1 秒）：正常推送时插值始终落在这个范围内；
         * 推送一停（暂停/掉线）最多再往前推 1.2 秒就定住，不会一路跑到歌尾。
         */
        // 插件不再每秒确认进度，正常播放靠本地时钟推进；6 小时足够覆盖单曲，
        // 又不至于在异常情况下让进度跑到无穷大。
        const val INTERPOLATION_CAP_MS = 6 * 60 * 60 * 1000L

        /**
         * 原始位置一次往回掉这么多毫秒，就当成"真的跳了"（换歌/跳转/重连后的新基线），
         * 松开进度条的单调下限。取 2 秒：远大于宿主推送间隔的抖动，又小于任何
         * 有意义的回退。
         */
        const val POSITION_JUMP_BACK_MS = 2_000L
    }

    fun setSyncLocalAudio(value: Boolean) {
        mutateRemotePrefs(pushToClient = false) { it.copy(syncLocalAudio = value) }
        edit { ServiceLocator.settings.setRemoteSyncLocalAudio(value) }
        if (!value) {
            // 关掉就立刻停，不要等下一次 state 推送
            runCatching {
                ServiceLocator.localPlayer.pause()
                ServiceLocator.localPlayer.release()
            }
        } else {
            applyAudioSync()
        }
    }

    /**
     * 按当前状态对齐本地播放。幂等：可以在任意时机重复调用。
     *
     * ## 断线/重连期间**什么都不做**（用户报的"断连会中断同步音频播放"）
     *
     * 老逻辑在任何一处不满足时都会 `stopLocalAudio()` —— 断线时 `track` 被清成
     * EMPTY、`nowPlaying` 被清空，于是"控制功能一断连，手机这边的歌就停了"。
     * 现在只有两种情况允许停：
     *   - 用户自己关掉了同步开关（见 [setSyncLocalAudio]）；
     *   - 宿主**明确**处于暂停状态（`track.hasTrack && !track.playing`）。
     *
     * 其余情况（空快照 = 断线/刚重连/宿主还没推、本机还没匹配上、当前匹配
     * 结果还是别的歌）一律**保持现状继续播**：没匹配到就不去动播放器，
     * 匹配到同一首就只在明显跑偏时纠一次位置，换了另一首才会换歌。
     */
    fun applyAudioSync() {
        if (!remotePrefsValue.syncLocalAudio) {
            stopLocalAudio()
            return
        }
        val np = _nowPlaying.value
        val path = np.localPath
        val track = ServiceLocator.remoteControl.track.value

        // 断线/重连的空快照：保持原样，继续播（这就是"断连不打断同步播放"）
        if (!track.hasTrack) return

        // 本机没匹配到 / 还没解析完 / 结果还是上一首的：也不动播放器。
        // 旧代码在这里 stopLocalAudio()，"断线之后本机匹配被清空"就会顺带停播。
        if (!np.matched || path.isBlank()) return
        if (np.trackKey.isNotBlank() && np.trackKey != RemoteTrackResolver.keyOf(track)) return

        val player = ServiceLocator.localPlayer

        if (!track.playing) {
            // 明确的暂停状态：跟着暂停（不断线、不换歌时才走到这里）
            player.pause()
            return
        }

        // playAt 内部会把"换歌 / 定位 / 起播"一次做完，
        // 并且对"同一首正在放"是幂等的（只在明显偏了才 seek）。
        player.playAt(path, track.positionMs)
    }

    private fun stopLocalAudio() {
        runCatching { ServiceLocator.localPlayer.pause() }
    }

    // ------------------------------------------------------------------
    // 插件仓库订阅
    // ------------------------------------------------------------------

    /**
     * 订阅的插件仓库地址（指向 index.json）。
     *
     * **以设置里的值为准**，不再单独存一份：以前它是只在"保存"时赋值的字段，
     * 于是重启之后这一页又显示成"还没有订阅仓库"（刷新按钮也点不动），
     * 明明设置里存着地址。
     */
    val repoUrl: String get() = state.value.settings.pluginRepoUrl.trim()

    /** 最近一次检查/更新的结果，显示在卡片上。 */
    var repoStatus by androidx.compose.runtime.mutableStateOf("")
        internal set

    var repoBusy by androidx.compose.runtime.mutableStateOf(false)
        internal set

    /** 是否弹出"编辑订阅"对话框。 */
    var editRepoDialog by androidx.compose.runtime.mutableStateOf(false)

    /**
     * 插件清单每一行要用到的、从插件包 manifest 里读出来的那几项。
     *
     * @param order 展示顺序，数字小的靠前（[com.audiometa.app.plugin.model.PluginManifest.DEFAULT_ORDER] = 没声明）
     * @param capabilities 声明了哪些能力 —— 决定它进「元信息源」还是「艺术家图源」
     * @param icon manifest 里写的图标文件名（默认 `icon.png`），列表左边那张图就是它
     */
    data class PluginListInfo(
        val order: Int = com.audiometa.app.plugin.model.PluginManifest.DEFAULT_ORDER,
        val capabilities: Set<PluginCapability> = emptySet(),
        val icon: String = "icon.png"
    )

    /**
     * 插件列表的展示顺序、能力与图标。
     *
     * 顺序取自插件包 manifest 里的 `order`（数字小的靠前），读不到的垫到最后；
     * 能力用来把插件页拆成「元信息源」（searchSongs）和「艺术家图源」
     * （searchArtistImages）两份清单。这些都要读安装目录里的 manifest.json，
     * 所以放在 IO 线程，并按 pluginId@版本 缓存，切进切出不再重复读盘。
     *
     * 只影响这一页怎么排、怎么分组，不改数据库：`pluginOrder`（刮削优先级）是另一件事，
     * 由用户在优先级页里手动拖。
     */
    var pluginInfo by androidx.compose.runtime.mutableStateOf<Map<String, PluginListInfo>>(emptyMap())
        internal set

    /** pluginId@版本 → 上面那份信息。切进切出插件页不该每次都去读一遍 manifest.json。 */
    internal val pluginInfoCache = mutableMapOf<String, PluginListInfo>()

    /** 待删除的源：非空时弹确认框（删除不可撤销，必须问一次）。 */
    var pendingUninstall by androidx.compose.runtime.mutableStateOf<PluginRecordEntity?>(null)

    /**
     * 每个镜像这一轮的状态：正在测 / 耗时多少 / 超时。
     *
     * 用 StateFlow 而不是普通 Compose 状态：结果是在 IO 线程上**逐个**填的，
     * StateFlow 的写入是线程安全的。没有条目 = 这一轮还没轮到它。
     */
    internal val _mirrorTestResults = MutableStateFlow<Map<String, MirrorTestResult>>(emptyMap())
    val mirrorTestResults: StateFlow<Map<String, MirrorTestResult>> = _mirrorTestResults.asStateFlow()

    /** 「测试」正在跑（跑着的时候按钮显示「测试中」并置灰）。 */
    internal val _mirrorTestRunning = MutableStateFlow(false)
    val mirrorTestRunning: StateFlow<Boolean> = _mirrorTestRunning.asStateFlow()

    // ------------------------------------------------------------------
    // 标签识别规则
    //
    // 这里**没有**导入入口：规则跟着订阅走 —— 点「更新订阅」时
    // [com.audiometa.app.plugin.install.PluginRepoUpdater] 会把仓库 index.json 里
    // `rules` 指向的那一份下载下来、按 sha256 校验后装进去（见 installRules）。
    // 手填地址和从本地文件导入这两条路都已经去掉了。
    // ------------------------------------------------------------------

}
