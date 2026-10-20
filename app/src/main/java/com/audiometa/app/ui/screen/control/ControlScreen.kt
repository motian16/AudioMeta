package com.audiometa.app.ui.screen.control

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.remote.RemoteTrackResolver
import com.audiometa.app.ui.screen.settings.SettingsViewModel
import kotlinx.coroutines.delay

/**
 * 进度条"本地走秒"的刷新间隔（毫秒）。
 *
 * 250ms 足够顺滑（这个量级已经看不出跳格），又不会像 16ms 那样每秒重组
 * 几十次 —— 这一页的内容（歌词、封面）本来就不需要那么高的频率。
 */
private const val UI_POSITION_TICK_MS = 250L

/**
 * 「控制」页：遥控电脑上的 Salt Player。
 *
 * ## 布局（按用户要求重排，充分利用页面空间）
 *
 *   左上：同步播放音频开关（紧凑横向）      右上：刷新
 *   ─────────────────────────────────────────────
 *                       歌曲名（居中）
 *                  艺术家 · 专辑（居中一行）
 *                     封面（正方形，占位图兜底）
 *              歌词三行：上一行 / 当前行 / 下一行
 *                        进度条
 *                    上一首 播放 下一首
 *
 * ## 为什么这样写才不会闪
 *
 * 两处根因，都在这一版修掉了：
 *   1. **副作用绑错了 key**：原来 `LaunchedEffect(remote)` 的 key 是整个
 *      `AppSettings`，DataStore 每次发新值都是新对象，任何设置变化都会
 *      重跑 effect 并重新 `connect()`，连接被反复重建。现在连接编排全在
 *      ViewModel 里，UI 只 `LaunchedEffect(Unit)` 叫一次。
 *   2. **`stateIn(WhileSubscribed(5000))`**：切走 5 秒后上游停止，
 *      再切回来先发一次初值（空白设置页），然后才推真实数据 ——
 *      用户看到的"切换 Tab 闪一下"就是这个。现在改成 `Eagerly`。
 *
 * UI 只 collect **窄** StateFlow，不 collect 整个 `AppSettings`。
 */
@Composable
fun ControlScreen(
    onOpenRemoteSettings: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val connection by ServiceLocator.remoteControl.state.collectAsState()
    val track by ServiceLocator.remoteControl.track.collectAsState()
    val lastError by ServiceLocator.remoteControl.lastError.collectAsState()
    val scanning by vm.remoteScanning.collectAsState()
    val remote by vm.remotePrefs.collectAsState()
    val nowPlaying by vm.nowPlaying.collectAsState()
    val notice by vm.connectionNotice.collectAsState()
    // 本机曲库拿不到封面/歌词时，用 PC 插件随 trackExtra 下发的那一份。
    val pcCover by ServiceLocator.remoteControl.pcCover.collectAsState()
    val pcLyrics by ServiceLocator.remoteControl.pcLyrics.collectAsState()

    var tokenInput by remember { mutableStateOf("") }
    // 配对码提示做成“粘性”的：连接在 Unauthorized / Connecting / Reconnecting
    // 之间来回切时不要跟着闪，直到真正连上或用户手动关掉。
    var pairingRequired by remember { mutableStateOf(false) }

    /** 当前这首歌的稳定键，用来判断界面上的匹配结果是不是这首歌的。 */
    val currentTrackKey = RemoteTrackResolver.keyOf(track)

    // 只跑一次。key 用 Unit —— 绝不跟设置走，这是上一版闪烁的根因之一。
    LaunchedEffect(Unit) { vm.refreshRemote() }

    // 曲目变了才去本机曲库匹配封面/歌词；位置每秒变化不会触发重解析。
    LaunchedEffect(track.id, track.path, track.title, track.artist) {
        vm.resolveLocalArtifacts(track)
    }

    /*
     * 进度条的"本地走秒"。
     *
     * 宿主每秒才推一次 pos，直接用 `track.positionMs` 画进度条是"一秒跳一格"，
     * 而且右侧总时长一旦没了（本机没匹配到）整条进度就只剩占位文字。
     * 这里起一个 250ms 的小定时器，反复问 ViewModel 要**插值后**的位置
     * （[SettingsViewModel.positionForUi]，和歌词用的是同一条插值链路），
     * 于是进度条连续前进；没有连接/没有曲目时它不跑，不白费电。
     */
    var uiPositionMs by remember { mutableLongStateOf(track.positionMs) }
    /*
     * 界面在前台吗。
     *
     * 连接是进程级常驻的，但**这个 250ms 的刷新循环没必要在后台空转** ——
     * 那会让进程每 250ms 醒一次做一次无意义的组合。进后台就停，
     * 回前台自动重来（位置会从插值基线接着算，不需要额外补偿）。
     *
     * 用显式的 LifecycleEventObserver 而不是 `lifecycle.currentStateFlow`：
     * 后者在 lifecycle 2.8 里是 experimental，这里走稳定 API。
     */
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        foreground = lifecycleOwner.lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 曲目换了 / 连接重建：先把显示对齐到这次的原始位置，免得残留上一首的进度
    LaunchedEffect(track.id, track.path, connection.isLive) {
        vm.resetPositionInterpolation()
        uiPositionMs = track.positionMs
    }
    LaunchedEffect(connection.isLive, track.hasTrack, track.playing, foreground) {
        if (!connection.isLive || !track.hasTrack || !foreground) return@LaunchedEffect
        while (true) {
            uiPositionMs = vm.positionForUi(track.positionMs, track.playing)
            delay(UI_POSITION_TICK_MS)
        }
    }

    // 播放/暂停变化时对齐本地音频（开关打开时才有动作）。
    // 位置用"每 5 秒一档"当 key：既能纠偏，又不会每秒都触发。
    LaunchedEffect(track.playing, track.positionMs / 5000L) {
        vm.applyAudioSync()
    }

    val showTokenField = pairingRequired && !connection.isLive
    LaunchedEffect(connection, lastError, remote.enabled) {
        when {
            !remote.enabled || connection.isLive -> pairingRequired = false
            connection.needsToken || lastError.contains("配对", ignoreCase = true) ->
                pairingRequired = true
        }
    }

    /*
     * 电脑不在线：真正的 `Failed`（地址无效一类不会再自动恢复的硬失败）才显示，
     * 给一条明确提示 + 重试。普通的掉线现在由进程级客户端自己退避重连，
     * 状态是 `Reconnecting`，由下面的 ConnectionNoticeBar 显示"重连中：原因"。
     * Idle（启动瞬间、尚未发起连接）不显示，避免打开页面闪一下"不在线"。
     */
    val offlineReason = (connection as? com.audiometa.app.data.prefs.RemoteConnectionState.Failed)
        ?.reason?.takeIf { it.isNotBlank() }
    val showOffline = offlineReason != null && !showTokenField

    if (!remote.enabled) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))
            Text(
                "远程控制未启用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onOpenRemoteSettings) { Text("打开网络遥控设置") }
        }
        return
    }

    /*
     * 整页**不滚动**（用户明确要求），所以用 weight 把空间分给封面区，
     * 而不是让内容溢出后被裁掉。
     *
     *   固定顶栏（开关 + 刷新）
     *   ─────────────
     *   曲名 / 艺术家 · 专辑      ← 固定
     *   封面（吃掉剩余空间）        ← weight(1f)
     *   歌词三行                  ← 固定
     *   进度                     ← 固定
     *   播控三键                  ← 固定
     */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp)
    ) {
        TopBar(
            syncAudio = remote.syncLocalAudio,
            syncEnabled = connection.isLive && nowPlaying.matched,
            onSyncChange = { vm.setSyncLocalAudio(it) },
            onRefresh = {
                if (connection.isLive) vm.remoteReloadNowPlaying() else vm.refreshRemote()
            },
            refreshing = scanning
        )

        Spacer(Modifier.height(6.dp))

        TitleBlock(
            hasTrack = track.hasTrack,
            title = track.title,
            artist = track.artist,
            album = track.album,
            connected = connection.isLive,
            scanning = scanning
        )

        // 封面吃掉剩余空间 —— 这样不同屏幕高度下都不会把下面的控件挤出去
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // 优先级：本机曲库封面 → PC 下发的压缩封面。后者只在换曲时来一次，
            // 不会跟着每秒的位置广播走。
            val cover = nowPlaying.cover ?: pcCover
            CoverBlock(
                bitmap = cover,
                // 「本机曲库里没有这首歌」是一句**断言**，只有在"这份解析结果就是
                // 当前这首歌"时才敢说 —— 刚换歌还没解析完、或者断线收到空快照时，
                // _nowPlaying 里可能还是上一首的结果，那只是"还没匹配"，不是"没有"。
                // 已经有 PC 封面时也不必再补一句"本机没有"。
                showMissingNote = track.hasTrack &&
                    nowPlaying.trackKey.isNotBlank() &&
                    nowPlaying.trackKey == currentTrackKey &&
                    !nowPlaying.matched &&
                    cover == null,
                hasTrack = track.hasTrack
            )
        }

        LyricBlock(
            vm = vm,
            positionMs = uiPositionMs,
            matched = nowPlaying.matched,
            lyricSource = nowPlaying.lyricSource,
            hasTrack = track.hasTrack,
            pluginFallback = track.lyricLine,
            pcLyrics = pcLyrics
        )

        Spacer(Modifier.height(10.dp))

        SeekBar(
            enabled = connection.isLive && track.hasTrack,
            positionMs = uiPositionMs,
            // 本机匹配到的时长最准；没有就用 PC 提供的，再没有就退回占位刻度。
            durationMs = nowPlaying.durationMs.takeIf { it > 0 } ?: track.durationMs,
            onSeek = { vm.remoteSeekTo(it) }
        )

        // 没连上时给一句"为什么"（断线原因由进程级的客户端记着）。
        // 高度固定，出现/消失不会顶动下面的播控键。
        // 重连时不要显示"第 N 次"和每次变化的原因：N 每退避一次就变，
        // 文字会一直跳。连接真正失败（Failed）时才把具体原因放出来。
        val reconnecting = connection is RemoteConnectionState.Reconnecting
        val connectionText = if (reconnecting) "正在重连…" else connection.label
        ConnectionNoticeBar(
            // 已经显示「电脑不在线」那条时不再重复同一句话（两行红字会互相抢注意力）。
            text = if (connection.isLive || !track.hasTrack || showOffline) {
                ""
            } else if (reconnecting) {
                connectionText
            } else if (notice.isBlank()) {
                connectionText
            } else {
                connectionText + "：" + notice
            }
        )

        Spacer(Modifier.height(14.dp))

        TransportRow(
            playing = track.playing,
            enabled = connection.isLive,
            onPrevious = { vm.remotePrevious() },
            onPlayPause = { if (track.playing) vm.remotePause() else vm.remotePlay() },
            onNext = { vm.remoteNext() }
        )

        if (showOffline) {
            Spacer(Modifier.height(10.dp))
            OfflineBar(
                reason = offlineReason!!,
                onRetry = { vm.refreshRemote() }
            )
        }
    }

    if (showTokenField) {
        TokenPrompt(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            error = lastError.takeIf { !connection.isLive && it.isNotBlank() }.orEmpty(),
            onConfirm = {
                vm.setRemoteToken(tokenInput)
                vm.refreshRemote()
                // 先收起；如果配对码不对，下一次 Unauthorized/错误会把它重新打开。
                pairingRequired = false
            },
            onOpenSettings = {
                pairingRequired = false
                onOpenRemoteSettings()
            },
            onDismiss = { pairingRequired = false }
        )
    }
}

/**
 * 电脑不在线提示条。
 *
 * 只有客户端停在 `Failed`（地址无效一类**不会再自动恢复**的硬失败）时才出现
 * —— 普通的掉线现在由进程级客户端自己按指数退避重连（见 RemoteControlClient），
 * 状态是 `Reconnecting`，页面显示的是下面那行"重连中：原因"，
 * 不需要用户手动点重试。
 */
@Composable
private fun OfflineBar(reason: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "电脑不在线",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium
            )
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/**
 * 固定顶栏：左边同步播放音频开关，右边刷新。
 *
 * 用户要求：
 *   - **不要文字提示**，只留横向开关；
 *   - 开关里显示**喇叭图标**（而不是默认那个圆点），一眼看出这是音频相关；
 *   - 固定在顶部，不跟随内容滚动。
 *
 * 没匹配到本机曲目或没连上时开关置灰 —— 免得用户打开一个什么都不做的开关。
 */
@Composable
private fun TopBar(
    syncAudio: Boolean,
    syncEnabled: Boolean,
    onSyncChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 自绘开关：Material3 的 Switch 不能换 thumb 内容，
        // 而用户要求"开关中的圆形显示喇叭图标"，所以这个用 Surface + 动画自己拼。
        SpeakerSwitch(
            checked = syncAudio,
            enabled = syncEnabled || syncAudio,
            onCheckedChange = onSyncChange
        )

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "重新获取播放信息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 带喇叭图标的开关。
 *
 * 用 [animateFloatAsState] 让滑块位置动起来，而不是直接跳 ——
 * 这一页用户对"闪动"很敏感，任何生硬的状态切换都要给一点动画。
 */
@Composable
private fun SpeakerSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackWidth = 48.dp
    val thumb = 26.dp
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(160),
        label = "speaker-switch"
    )

    val trackColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val thumbColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        checked -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = (trackWidth - thumb - 4.dp) * offset)
                .size(thumb)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (checked) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                contentDescription = "同步播放音频",
                modifier = Modifier.size(15.dp),
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        }
    }
}

/** 歌曲名（居中）+ 艺术家 · 专辑（居中一行）。 */
@Composable
private fun TitleBlock(
    hasTrack: Boolean,
    title: String,
    artist: String,
    album: String,
    connected: Boolean,
    scanning: Boolean
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!hasTrack) {
            Text(
                when {
                    scanning -> "正在查找电脑…"
                    connected -> "电脑当前没有播放"
                    else -> "未连接"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            return
        }

        Text(
            title.ifBlank { "（未知曲目）" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // 艺术家和专辑合并成一行，符合"歌曲名下方显示艺术家和专辑"
        val subtitle = listOf(artist, album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 封面。
 *
 * 占满可用宽度（留一点边距），正方形。没有封面时用音符占位图，
 * 并且**只在确实解析过这首歌、结论是"没有"时**才标一句"本机曲库里没有这首歌"。
 *
 * [resolvedForCurrent] 就是这个"结论是不是这首歌的"：匹配成功但封面读不出来
 * （文件损坏、没有内嵌图、外挂图缺失）时它是 true、`matched` 也是 true ——
 * 那时只画音符占位图，不会说"本机没有这首歌"。刚换歌还没解析完/断线快照
 * 那样"还没结论"的情况它是 false，同样不会乱下断言。
 */
@Composable
private fun CoverBlock(
    bitmap: android.graphics.Bitmap?,
    showMissingNote: Boolean,
    hasTrack: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
        if (hasTrack && showMissingNote) {
            Spacer(Modifier.height(6.dp))
            Text(
                "本机曲库里没有这首歌",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 未连接时的一行说明：连接状态 + 客户端记下的**掉线原因**。
 *
 * 用户要求"能从日志看出为什么断了" —— 这一行就是那句话的界面版：
 * 心跳超时 / 服务端关闭 / 连接被拒绝 / 网络不可用，一眼就知道该去查什么。
 * 高度固定，出现与消失都不会顶动下面的播控键（[text] 为空时只占位不显示）。
 */
@Composable
private fun ConnectionNoticeBar(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (text.isBlank()) return@Box
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 歌词三行：上一行 / 当前行 / 下一行，**切行时从下往上滑**。
 *
 * 用户要求"是从下往上流畅的滑动，而不是直接变换"，所以用 [AnimatedContent]
 * 配 `slideInVertically`：新的一行从下方滑入、旧的一行向上滑出。
 *
 * 高度固定为三行，所以在"有歌词/没歌词"之间切换时不会顶动整页 ——
 * 这也是之前"布局在抖"的一个来源。
 *
 * 动画的 key 用**当前行的索引**：内容相同的重复行不会触发动画，
 * 换行才动，正是想要的效果。
 */
@Composable
private fun LyricBlock(
    vm: SettingsViewModel,
    positionMs: Long,
    matched: Boolean,
    lyricSource: RemoteTrackResolver.LyricSource,
    hasTrack: Boolean,
    pluginFallback: String,
    pcLyrics: com.audiometa.app.remote.RemoteLyrics?
) {
    // 位置/来源/PC 歌词任意一个变了都要重查：PC 歌词是异步到达的，
    // 只把 positionMs 当 key 的话它会一直停在"暂无歌词"。
    val trio = remember(positionMs, lyricSource, pcLyrics) { vm.lyricsTrio(positionMs) }
    val current = trio.current.ifBlank { pluginFallback }

    val hasAny = current.isNotBlank() || trio.previous.isNotBlank() || trio.next.isNotBlank()

    Box(
        modifier = Modifier.fillMaxWidth().height(104.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!hasAny) {
            Text(
                when {
                    !hasTrack -> "电脑开始播放后这里显示歌词"
                    // 本机匹配到了这首歌、文件里确实没有歌词，PC 也没给
                    matched && lyricSource == RemoteTrackResolver.LyricSource.NONE &&
                        (pcLyrics == null || pcLyrics.isEmpty) -> "本歌曲无歌词"
                    else -> "暂无歌词"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            return@Box
        }

        /*
         * 用三元组整体内容当动画 key。
         *
         * 不只用当前行：上一行/下一行也变了说明确实换行了。
         * 内容完全相同（同一行重复推送）时 key 不变，不会白播一次动画。
         */
        val lineKey = trio.previous + "\u0000" + trio.current + "\u0000" + trio.next

        AnimatedContent(
            targetState = lineKey,
            transitionSpec = {
                // 从下往上：新内容自下方进入，旧内容向上退出
                (slideInVertically(
                    animationSpec = tween(LYRIC_ANIM_MS, easing = FastOutSlowInEasing)
                ) { full -> full / 3 } + fadeIn(tween(LYRIC_ANIM_MS / 2)))
                    .togetherWith(
                        slideOutVertically(
                            animationSpec = tween(LYRIC_ANIM_MS, easing = FastOutSlowInEasing)
                        ) { full -> -full / 3 } + fadeOut(tween(LYRIC_ANIM_MS / 2))
                    )
            },
            label = "lyric-line",
            modifier = Modifier.fillMaxWidth()
        ) { key ->
            // key 解回三行；动画期间内容用的是"目标那一帧"的值
            val parts = key.split('\u0000')
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LyricLineText(parts.getOrNull(0).orEmpty(), active = false)
                LyricLineText(parts.getOrNull(1).orEmpty(), active = true)
                // 翻译只跟当前行走，不参与滑动（否则会多出一条多余的动画）
                if (trio.translation.isNotBlank()) {
                    Text(
                        trio.translation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                LyricLineText(parts.getOrNull(2).orEmpty(), active = false)
            }
        }
    }
}

/** 歌词滑动动画时长。380ms 是"看得出在动"和"不拖沓"之间的折中。 */
private const val LYRIC_ANIM_MS = 380

/** 歌词行。当前行用主题色 + 中粗，前后行淡灰。 */
@Composable
private fun LyricLineText(text: String, active: Boolean) {
    if (text.isBlank()) {
        // 空行也要占位，否则三行的高度会随歌词变化而抖
        Spacer(Modifier.height(if (active) 24.dp else 20.dp))
        return
    }
    Text(
        text,
        style = if (active) {
            MaterialTheme.typography.bodyLarge
        } else {
            MaterialTheme.typography.bodySmall
        },
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/**
 * 自绘进度条。
 *
 * 不用 Material3 的 `Slider`：它的值是受控参数，每秒一条 state 推送都会
 * 走一遍内部状态机，拖动和外部更新同时发生时容易互相打断。
 * 这里拖动状态完全关在组件内部，松手才发 seek。
 *
 * [durationMs] > 0 时画**真实百分比**；否则退回"按 10 分钟折算"——
 * 因为宿主 dev20 不提供曲目总时长。
 */
@Composable
private fun SeekBar(
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    var widthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val spanMs = if (durationMs > 0) durationMs.toFloat() else 10 * 60 * 1000f
    val shownFraction = if (dragging) {
        dragFraction
    } else {
        (positionMs.coerceIn(0L, spanMs.toLong()).toFloat() / spanMs)
    }.coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(enabled, spanMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        if (widthPx > 0) {
                            onSeek(((offset.x / widthPx).coerceIn(0f, 1f) * spanMs).toLong())
                        }
                    }
                }
                .pointerInput(enabled, spanMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            if (widthPx > 0) {
                                dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            onSeek((dragFraction * spanMs).toLong())
                        },
                        onDragCancel = { dragging = false }
                    ) { change, _ ->
                        if (widthPx > 0) {
                            dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f))
            )
            Box(
                Modifier.fillMaxWidth(shownFraction).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
            )
            Box(Modifier.fillMaxWidth(shownFraction).height(14.dp)) {
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .size(if (dragging) 20.dp else 14.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatPosition(if (dragging) (dragFraction * spanMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    !enabled -> "未连接"
                    durationMs > 0 -> formatPosition(durationMs)
                    else -> "拖动跳转"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 播控三键。
 *
 * 比例按用户反馈调过：中间播放键 76dp，两侧 44dp 图标，
 * 间距拉开到 36dp，整体在页面里更稳、更好点。
 */
@Composable
private fun TransportRow(
    playing: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious, enabled = enabled) {
            Icon(
                Icons.Outlined.SkipPrevious,
                contentDescription = "上一首",
                modifier = Modifier.size(44.dp),
                tint = tintFor(enabled)
            )
        }
        Spacer(Modifier.width(36.dp))
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onPlayPause, enabled = enabled) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    modifier = Modifier.size(40.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(36.dp))
        IconButton(onClick = onNext, enabled = enabled) {
            Icon(
                Icons.Outlined.SkipNext,
                contentDescription = "下一首",
                modifier = Modifier.size(44.dp),
                tint = tintFor(enabled)
            )
        }
    }
}

@Composable
private fun tintFor(enabled: Boolean) = if (enabled) {
    MaterialTheme.colorScheme.onSurface
} else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
}

/**
 * 配对码输入。
 *
 * 用 Dialog 而不是页面里的条件块：条件块出现/消失会把整页顶动，
 * 连接在 Unauthorized / Connecting / Reconnecting 之间切换时就表现为“闪”。
 */
@Composable
private fun TokenPrompt(
    value: String,
    onValueChange: (String) -> Unit,
    error: String,
    onConfirm: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "需要配对码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "配对码在电脑上：Salt Player → 插件配置 → 远程遥控 → 配对码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("稍后") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                    TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text("连接") }
                }
            }
        }
    }
}

/** 出问题时才显示的诊断块。设备列表在设置页，这里不放。 */
@Composable
private fun DiagnosticNote(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRetry) { Text("重试") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("打开网络遥控设置") }
        }
    }
}

/** 毫秒转 mm:ss（超过一小时才显示小时）。 */
private fun formatPosition(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
