package com.audiometa.app.media.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 详情页的试听播放器。
 *
 * 关键点是**进页面就 prepare，而不是等展开动画跑完**。
 * 之前是「展开进度过 0.6 才 new MediaPlayer + prepareAsync」，
 * 等于把 setDataSource（要探测容器、读索引，FLAC 上尤其慢）整个压在
 * 用户已经看到封面、正在等声音的那一刻，所以听起来"按下去半天才响"。
 *
 * 现在：进入详情页立刻 prepareAsync（此时用户还在看页面），
 * 点击展开时通常已经 prepared，start() 是即时的。
 *
 * 静音跳过时间来自预计算档案，播放开始时 seek 一次，不额外解码。
 */
class TrackPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * 低优先级模式（遥控同步播放用）。
     *
     * 打开后有两个行为变化：
     *   1. **完全不申请音频焦点** —— 同步播放是"跟着电脑一起放"的从属行为，
     *      抢焦点会把手机上其他正在放音乐的应用压低（GAIN 让对方 duck），
     *      而且我们退出后对方不一定能立刻恢复，这是被用户投诉过的真实体验；
     *   2. 改用 [AudioManager.AudioPlaybackCallback] **被动监听**：别的应用
     *      开始出声时通过 [onExternalConflict] 通知调用方，由调用方决定让位方式
     *      （当前是关闭同步开关），而不是我们抢在前面把别人打断。
     */
    private val lowPriority: Boolean = false
) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var prepared = false
    private var pendingStart = false
    private var currentPath: String = ""

    /**
     * 起播前要跳到的位置。
     *
     * 单独存一个字段而不是调 `seekTo()`：prepare 还没完成时 seek 会被丢掉，
     * 结果是"先从头出声再跳"。见 [playAt]。
     */
    private var pendingSeekMs: Long? = null

    /**
     * 最近一次"外部主动定位"的时间（用户在控制页拖进度条）。
     *
     * 见 [seekIfCurrent]：拖条松手瞬间本地已就地位，随后电脑回推的位置
     * 在短时间内一律不再纠偏，避免"本地 seek 一次 + 回推到达又 seek 一次"
     * 的连续 decoder flush —— 那正是听感上"卡顿好几下才开始放"的来源。
     */
    private var lastExternalSeekAt: Long = 0L

    /**
     * 同一首歌内允许的位置偏差（毫秒），超过才 seek。
     *
     * 遥控每秒推一次位置，容差太小会变成每秒 seek 一次（听感上是结巴）；
     * 太大则声画不同步。1500ms 是这两者之间的折中。
     */
    private val IN_TRACK_TOLERANCE_MS = 1_500L

    /** 音频焦点。null 表示拿不到 AudioManager（极少数情况），此时不做焦点管理。 */
    private val audioManager: android.media.AudioManager? = runCatching {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }.getOrNull()

    /**
     * 焦点变化回调：别人拿走就暂停，别人松手就恢复。
     *
     * 恢复只在"是我们自己因为丢焦点而暂停的"时候做 —— 用户手动暂停的
     * 不该被别人的一次播报给弄回播放状态。
     */
    private var pausedByFocusLoss = false

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = true
                pause()
            }

            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByFocusLoss = true
                pause()
            }

            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 压低音量即可：闪避是系统行为，这里不额外处理
            }

            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    start()
                }
            }
        }
    }

    private val focusRequest: android.media.AudioFocusRequest? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }

    /**
     * 低优先级模式的冲突回调：本机正在同步播放时，检测到**其他应用**开始出声。
     * 只在 [lowPriority] 时才会触发。由调用方（SettingsViewModel）关闭同步开关让位。
     */
    var onExternalConflict: (() -> Unit)? = null

    /** 最近一次本机起播的时刻，供 [externalPlaybackWatcher] 排除自己的配置注册。 */
    private var startedPlayingAt = 0L

    private val _state = MutableStateFlow(PlayerState())

    /** 供界面订阅：播放状态、进度、时长。 */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * 外部播放监听器：系统每次播放配置变化都会回调这里。
     *
     * 只关心一件事 —— 出现了**新的**活跃 USAGE_MEDIA 播放。
     *
     * 公开 SDK 里配置对象只暴露 `getAudioAttributes()`，`isActive()`/`getId()`
     * 都是隐藏方法，所以反射读取；系统拦截反射时（部分版本会）拿不到结果，
     * **自动放弃判定** —— 优雅降级为"不做自动让位"，绝不会因此崩溃。
     * 拿得到时用**差集**判断新增：记录当前活跃媒体配置的 Id 集合，这次回调里
     * 新出现的 Id 就是刚开始的播放。本机自己的配置在起播后的极短时间内注册
     * （记下起播时刻，窗口期内的新增忽略不计），窗口之后新增的必然是别的应用
     * —— 这时如果本机正在同步播放，就是冲突，触发 [onExternalConflict]
     * 让调用方让位。
     */
    private val externalPlaybackWatcher = object : android.media.AudioManager.AudioPlaybackCallback() {
        private val isActiveMethod = runCatching {
            android.media.AudioPlaybackConfiguration::class.java.getMethod("isActive")
        }.getOrNull()
        private val idMethod = runCatching {
            android.media.AudioPlaybackConfiguration::class.java.getMethod("getId")
        }.getOrNull()

        /** 当前活跃的媒体播放配置 Id。 */
        private var activeMediaIds: Set<Int> = emptySet()

        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
            // 反射不可用：放弃自动让位（"不抢焦点"的主行为不受影响）。
            val activeCheck = isActiveMethod ?: return
            val idOf = idMethod ?: return
            val current = configs.asSequence().mapNotNull { cfg ->
                val active = runCatching { activeCheck.invoke(cfg) as Boolean }.getOrDefault(false)
                val id = runCatching { idOf.invoke(cfg) as Int }.getOrDefault(-1)
                if (active && id >= 0 &&
                    cfg.audioAttributes?.usage == android.media.AudioAttributes.USAGE_MEDIA
                ) id else null
            }.toSet()
            val added = current - activeMediaIds
            activeMediaIds = current

            if (!_state.value.playing) return
            if (added.isEmpty()) return
            // 本机自己的配置在起播后马上注册：起播窗口期内的新增不是别人。
            if (System.currentTimeMillis() - startedPlayingAt < OWN_CONFIG_REGISTER_MS) return
            AppLog.i(TAG, "检测到其他应用开始播放媒体，同步播放让位（低优先级）")
            onExternalConflict?.invoke()
        }
    }

    init {
        if (lowPriority) {
            audioManager?.registerAudioPlaybackCallback(externalPlaybackWatcher, null)
        }
    }

    /** 换一首歌。会复用同一个 MediaPlayer 实例（只换数据源），省掉一次对象创建和音频焦点申请。 */
    fun prepare(audioPath: String, skipMs: Int) {
        if (audioPath == currentPath && player != null) {
            _state.value = _state.value.copy(skipMs = skipMs)
            return
        }
        release()
        currentPath = audioPath
        prepared = false
        pendingStart = false
        // 换歌时丢掉上一首留下的起播位置，否则新歌会从旧歌的位置开始
        pendingSeekMs = null
        _state.value = PlayerState(
            audioPath = audioPath,
            skipMs = skipMs,
            prepareState = PrepareState.PREPARING
        )
        val file = File(audioPath)
        if (!file.isFile) {
            _state.value = _state.value.copy(
                prepareState = PrepareState.FAILED,
                message = "文件不存在"
            )
            return
        }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(audioPath)
            /*
             * 音频焦点：用安卓自带的"冲突就暂停"机制。
             *
             * 别的 App 要出声（来电话、导航播报、另一个播放器）时系统会收回焦点，
             * 我们照做暂停自己 —— 用户不需要手动去关。
             *
             * **只在真正开始播放时才申请**，不在 prepare 时申请：
             * 否则光是把歌准备好就会把别人正在放的音频掐掉。
             */
            mp.setOnPreparedListener { preparedPlayer ->
                prepared = true
                _state.value = _state.value.copy(
                    prepareState = PrepareState.READY,
                    durationMs = runCatching { preparedPlayer.duration.toLong() }.getOrDefault(0L)
                )
                if (pendingStart) {
                    pendingStart = false
                    if (requestAudioFocus()) startInternal()
                }
            }
            mp.setOnCompletionListener {
                stopTicker()
                _state.value = _state.value.copy(playing = false, positionMs = 0L)
            }
            mp.setOnErrorListener { _, what, extra ->
                AppLog.w(TAG, "播放出错 what=$what extra=$extra")
                stopTicker()
                _state.value = _state.value.copy(
                    playing = false,
                    prepareState = PrepareState.FAILED,
                    message = "播放失败（$what/$extra）"
                )
                true
            }
            // prepareAsync 立刻返回，真正的解码准备在后台线程完成。
            mp.prepareAsync()
        }.onFailure {
            AppLog.w(TAG, "准备播放失败：" + it.message)
            _state.value = _state.value.copy(
                prepareState = PrepareState.FAILED,
                message = it.message.orEmpty().ifBlank { "无法准备音频" }
            )
        }
    }

    /**
     * 开始播放。
     *
     * 如果 prepare 还没完成就先记下来，prepared 回调里自动开播——
     * 这样"点了就播"，而不是"点了没反应，要再点一次"。
     */
    fun start() {
        val mp = player ?: return
        if (!prepared) {
            pendingStart = true
            return
        }
        if (requestAudioFocus()) startInternal()
    }

    /**
     * 申请音频焦点。
     *
     * 用**瞬态+允许闪避**（`TRANSIENT` + `MAY_DUCK`）：导航播报、通知音这类
     * 短促声音只会让我们压低音量，播完自动回来；来电、另一个播放器
     * 这类会拿走焦点，我们就暂停。
     *
     * 拿不到焦点时**不播放** —— 这比"硬放出来和别人打架"体验好。
     */
    private fun requestAudioFocus(): Boolean {
        // 低优先级模式：完全绕开焦点机制，不打扰手机上正在出声的其他应用；
        // 冲突由 externalPlaybackWatcher 被动发现，交给 onExternalConflict 处理。
        if (lowPriority) return true
        val am = audioManager ?: return true
        return runCatching {
            val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = focusRequest
                    ?: return@runCatching true
                am.requestAudioFocus(req) ==
                    android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN
                ) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            result
        }.getOrDefault(true) // 系统不给答复时按"允许"处理，别把播放卡死
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        }
    }

    /**
     * 从指定位置开始播放**指定文件**（换歌 + 定位 + 起播，一步到位）。
     *
     * 这是遥控同步唯一该用的入口。三个坑都在这里堵住了：
     *
     *   1. **必须先 prepare**：不 prepare 的话 `player` 是 null，
     *      `startInternal()` 会 `?: return` 静默什么都不做 ——
     *      表现就是"开关打开了，手机一点声音都没有"。
     *   2. **不能在 prepare 之前 seek**：`seekTo()` 在还没 prepare 完时会被丢掉，
     *      于是先从头出声、再跳到目标位置 —— 表现就是"先播开头然后突然跳"。
     *      所以位置先记在 [pendingSeekMs]，由 onPrepared 在 start 之前一次性落位。
     *   3. **同一首歌重复调用不该重启**：遥控每秒都可能推状态过来，
     *      每次都重建播放器会变成结巴。
     *
     * @param path 要播的文件绝对路径
     * @param positionMs 起播位置；0 表示从头
     */
    fun playAt(path: String, positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val samePath = path == currentPath && player != null

        if (!samePath) {
            // 换歌：先记下目标位置，再 prepare —— 顺序不能反
            pendingSeekMs = target
            prepare(path, skipMs = 0)
            if (!prepared) {
                // prepare 是异步的，onPrepared 里会带着 pendingSeekMs 起播
                pendingStart = true
                return
            }
        } else if (_state.value.playing) {
            // 同一首、正在放：只在明显偏了的时候纠一次，避免每秒 seek。
            // 但用户刚在控制页拖过进度条时除外：本地已经 seek 到目标位置，
            // 电脑回推的位置正在追上来，这几秒内再 seek 纯属二次卡顿。
            val recentlyUserSeek = System.currentTimeMillis() - lastExternalSeekAt < USER_SEEK_GRACE_MS
            if (!recentlyUserSeek &&
                kotlin.math.abs(_state.value.positionMs - target) > IN_TRACK_TOLERANCE_MS
            ) {
                seekTo(target.toInt())
            }
            return
        } else {
            pendingSeekMs = target
        }

        if (requestAudioFocus()) startInternal()
    }

    private fun startInternal() {
        val mp = player ?: return
        if (_state.value.playing) return
        // 给外部播放监听器用：区分"自己的配置刚注册"和"别的应用开始出声"。
        startedPlayingAt = System.currentTimeMillis()
        runCatching {
            /*
             * 起播位置，优先级从高到低：
             *   1. pendingSeekMs —— 调用方明确要求的位置（遥控同步）；
             *   2. 预计算的静音跳过 —— 只在第一次开始时生效；
             *   3. 不动，从头播。
             */
            val pending = pendingSeekMs
            pendingSeekMs = null
            when {
                pending != null -> {
                    mp.seekTo(pending.toInt())
                    _state.value = _state.value.copy(positionMs = pending)
                }
                _state.value.skipMs > 0 && _state.value.positionMs <= 0L ->
                    mp.seekTo(_state.value.skipMs)
            }
            mp.start()
            _state.value = _state.value.copy(playing = true, prepareState = PrepareState.READY)
            startTicker()
        }.onFailure { AppLog.w(TAG, "开始播放失败：" + it.message) }
    }

    /**
     * 用户在控制页拖完进度条时调用：**只在本地正在放的就是 [path] 这首时**，
     * 立刻就地跳转，不等电脑的状态回推。
     *
     * 为什么必须本地立刻跳：旧链路是"手机发 seek → 电脑跳转 → 回推新位置
     * → 手机按 5 秒分桶等 applyAudioSync"，本地音频会先在旧位置继续响几秒，
     * 再猛地跳一次，用户听到的就是"卡顿一下才从新位置放"。
     *
     * 返回 false 表示当前没在放这首（没准备好/换了歌），调用方不用做别的，
     * 后续状态回推会走正常的 [playAt] 起播流程。
     */
    fun seekIfCurrent(path: String, positionMs: Long): Boolean {
        if (path != currentPath || !prepared) return false
        lastExternalSeekAt = System.currentTimeMillis()
        seekTo(positionMs.toInt())
        return true
    }

    fun pause() {
        val mp = player ?: return
        pendingStart = false
        runCatching {
            if (mp.isPlaying) mp.pause()
        }
        stopTicker()
        _state.value = _state.value.copy(playing = false)
    }

    fun seekTo(positionMs: Int) {
        val mp = player ?: return
        val duration = _state.value.durationMs
        val target = positionMs.toLong().coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        runCatching { mp.seekTo(target.toInt()) }
        _state.value = _state.value.copy(positionMs = target)
    }

    /** 拖动进度时先只更新显示，松手才真的 seek，避免每一帧都跳一次。 */
    fun previewPosition(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs, previewing = true)
    }

    fun commitPosition(positionMs: Long) {
        seekTo(positionMs.toInt())
        _state.value = _state.value.copy(previewing = false)
    }

    /**
     * 停止并回到开头。
     *
     * "暂停"和"停止"在界面上是两件事：手指上滑是**停止**（回开头，下次从静音之后放），
     * 离开详情页也是停止，但进度的持久保存只在详情页停留期间有效，
     * 退出页面就丢掉，所以这里不需要把位置写进任何存储。
     */
    fun stop() {
        val mp = player ?: return
        pendingStart = false
        runCatching {
            if (mp.isPlaying) mp.pause()
            mp.seekTo(0)
        }
        stopTicker()
        _state.value = _state.value.copy(playing = false, positionMs = 0L, previewing = false)
    }

    fun release() {
        stopTicker()
        abandonAudioFocus()
        pausedByFocusLoss = false
        pendingSeekMs = null
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        prepared = false
        pendingStart = false
        currentPath = ""
        _state.value = PlayerState()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val mp = player
                if (mp != null && _state.value.playing && !_state.value.previewing) {
                    val position = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                    _state.value = _state.value.copy(positionMs = position)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TICK_MS = 200L
        const val TAG = "TrackPlayer"

        /** 用户主动 seek 后，这么久内忽略位置纠偏（毫秒）。略大于两次状态回推间隔。 */
        const val USER_SEEK_GRACE_MS = 2_500L

        /**
         * 本机起播后，自己那条播放配置的注册窗口（毫秒）。
         * 窗口期内 watcher 看到的新增媒体配置按"是自己"处理，不触发冲突。
         */
        const val OWN_CONFIG_REGISTER_MS = 1_500L
    }
}

enum class PrepareState { IDLE, PREPARING, READY, FAILED }

data class PlayerState(
    val audioPath: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val prepareState: PrepareState = PrepareState.IDLE,
    val skipMs: Int = 0,
    val message: String = "",
    /** 手指正在拖进度条：此时不要用播放器的真实位置覆盖显示。 */
    val previewing: Boolean = false
) {
    /** 0..1，时长未知时返回 0。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f
        else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
