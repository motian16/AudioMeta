package com.audiometa.app.ui.screen.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.domain.model.CornerStyle
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.media.audio.SilenceProfileStore
import com.audiometa.app.media.audio.TrackPlayer
import com.audiometa.app.media.image.ArtworkKind
import com.audiometa.app.media.image.CoverColorSampler
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.ui.component.BottomSheetScaffold
import com.audiometa.app.ui.component.ChoiceChips
import com.audiometa.app.ui.component.LocalImage
import com.audiometa.app.ui.component.SectionTitle

import com.audiometa.app.ui.component.TrackCover
import com.audiometa.app.ui.component.copyToClipboard
import com.audiometa.app.ui.component.invalidateCoverCache
import com.audiometa.app.util.ArtworkTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/** 详情页要展示的全部信息，打开时才读文件。 */
data class TrackDetail(
    val meta: TrackMeta,
    val bitRate: Long = 0L,
    val sampleRate: Int = 0,
    /** 位深（bit depth），16/24/32。无损格式才有意义。 */
    val bitDepth: Int = 0,
    val channels: String = "",
    val variableBitRate: Boolean = false,
    val format: String = "",
    val fileSize: Long = 0L,
    val lastModified: Long = 0L,
    /** 封面原始宽高比（宽/高）。展开时按它显示完整图片。 */
    val coverAspect: Float = 1f
)

private suspend fun loadDetail(track: TrackEntity): TrackDetail = withContext(Dispatchers.IO) {
    val file = File(track.path)
    val meta = runCatching { TagReader().read(file) }.getOrDefault(TrackMeta())
    var bitRate = 0L
    var sampleRate = 0
    var bitDepth = 0
    var channels = ""
    var vbr = false
    runCatching {
        val header = AudioFileIO.read(file).audioHeader
        if (header != null) {
            bitRate = header.bitRateAsNumber
            sampleRate = header.sampleRateAsNumber
            bitDepth = runCatching { header.bitsPerSample }.getOrDefault(0)
            channels = header.channels.orEmpty()
            vbr = header.isVariableBitRate
        }
    }
    val aspect = runCatching {
        val source = com.audiometa.app.media.image.ArtworkResolver
            .resolve(file, com.audiometa.app.media.image.ArtworkKind.COVER) ?: return@runCatching 1f
        val bytes = when (source) {
            is com.audiometa.app.media.image.ArtworkResolver.Source.External ->
                File(source.path).readBytes()
            is com.audiometa.app.media.image.ArtworkResolver.Source.Bytes -> source.data
        }
        com.audiometa.app.media.image.ImageSizeProbe.probe(bytes)?.let { (w, h) ->
            if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
        } ?: 1f
    }.getOrDefault(1f)
    TrackDetail(
        meta = meta,
        bitRate = bitRate,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channels = channels,
        variableBitRate = vbr,
        format = file.extension.uppercase(Locale.US),
        fileSize = file.length(),
        lastModified = file.lastModified(),
        coverAspect = if (aspect > 0f) aspect else 1f
    )
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "未知"
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return minutes.toString() + ":" + (if (seconds < 10) "0" + seconds else seconds.toString())
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
    else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 已播放/总时长，进度条旁边的 mm:ss。 */
private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    return (total / 60).toString() + ":" + String.format(Locale.US, "%02d", total % 60)
}

/** 翻译标签的三种状态。 */
private sealed interface TranslationTag {
    /** 中文歌词：本来就不需要翻译，用中性样式表示"无需翻译"。 */
    data object NotNeeded : TranslationTag

    /** 日文/英文等：需要翻译且文件里已经有。 */
    data object Available : TranslationTag

    /** 需要翻译但文件里没有。 */
    data object Absent : TranslationTag
}

@Composable
fun SongDetailScreen(
    track: TrackEntity,
    cornerStyle: CornerStyle,
    onBack: () -> Unit,
    onScrape: (Set<ScrapeCapability>) -> Unit,
    /** 是否跳过开头静音。来自设置，默认开。 */
    skipSilentIntro: Boolean = true,
    /**
     * 写完文件是否保留原来的修改时间。来自设置。
     *
     * 用户要求"所有写入操作都遵守设置里的保持修改时间机制" —— 手动改元信息
     * 以前是写死保留的，即使设置里关掉了也照样保留，现在跟着设置走。
     */
    keepModTime: Boolean = true,
    /**
     * 从「元信息」页点进来时带的自定义标签上下文。
     *
     * 不为空时这一页变成**元信息歌曲详情页**（用户要求）：
     * - 歌手头像底下那五个项（歌词 / 翻译 / 组合刮削 / 艺术家图片 / 元信息）全部移除；
     * - 「元信息」区域上移，内容换成当前自定义标签的键名、完整值和说明；
     * - 「文件」区域照旧保留。
     * 其余逻辑（封面、播放、路径复制…）和普通详情页完全一样。
     */
    metaFocus: MetaTagFocus? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // 路径复制后的短暂反馈。
    var copied by remember { mutableStateOf(false) }

    // 手动刷新用：点一下标签状态就重读文件、清掉缩略图缓存。
    var refreshTicket by remember { mutableStateOf(0) }
    // 封面展开进度：0=常规，1=铺满。用一个可动画的值驱动所有派生量，保证完全同步。
    val expandAnim = remember { Animatable(0f) }
    val expanded = expandAnim.value > 0.5f
    // 点「歌词」标签弹出的底部歌词窗口。
    var showLyrics by remember { mutableStateOf(false) }
    // 点歌手头像弹出的底部窗口。
    var showArtistSheet by remember { mutableStateOf(false) }
    // 封面刷新后的短暂提示（下拉刷新封面时用）。
    var coverNotice by remember { mutableStateOf("") }
    // 元信息编辑：改一项就写一次文件（用户要求"手动改了某一项后直接同步写入"），
    // 这个 map 只是写盘期间的**乐观覆盖层** —— 新值先放进来让界面马上显示，
    // 等写盘成功、文件重读回来之后再把这个键清掉（见下面 MetaEditor 的 onEdit）。
    // 变量名刻意避开 MetaEditor（同名会遮蔽组件）。
    val metaEdits = remember(track.path) { mutableStateMapOf<String, String>() }
    // 单项写入的一句话反馈（成功/失败），几秒后自己消失。
    var metaSaveNotice by remember { mutableStateOf("") }
    // 这条反馈是成功还是失败：失败要用错误色，别让"写入失败"看着像好消息。
    var metaWriteFailed by remember { mutableStateOf(false) }
    // 改成"改一项写一次文件"之后，两次编辑可能挨得很近，而 RawTagWriter 走的是
    // "复制到同名临时文件 → 改 → 移回" 的路子：并发写同一首歌会撞在同一个临时文件上。
    // 用一把锁把"写盘"这一段串起来；等重读、刷新都在锁外，不占着锁等 IO。
    val metaWriteLock = remember(track.path) { Mutex() }

    /**
     * 进页面就 prepare，不等展开动画。
     *
     * 这是"点了半天才响"的解法：setDataSource + prepareAsync 才是慢的那一步
     * （要探测容器、读索引），把它提前到用户还在看信息的时候做完，
     * 真正点击时 start() 几乎是即时的。
     */
    val player = remember(track.path) { TrackPlayer(context.applicationContext, scope) }
    val playerState by player.state.collectAsState()
    // 跳过开头静音受设置控制；不论开关如何，档案都是后台预计算好的，这里零延迟。
    LaunchedEffect(track.id, refreshTicket, skipSilentIntro) {
        val profile = withContext(Dispatchers.IO) { SilenceProfileStore.get(context, track.id) }
        player.prepare(track.path, if (profile.hasSkip && skipSilentIntro) profile.skipMs else 0)
    }
    // 离开详情页：停止播放并释放。
    // 用 stop() 而不是直接 release()：先 pause + 回到开头，再释放。
    // 进度**不落盘**——按用户要求，在详情页停留期间的进度是持久的（暂停不丢），
    // 一旦退出这个页面就清掉，下次进来从头放。
    DisposableEffect(track.path) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    // 展开到一半就开播，收回到三分之一就暂停，避免在临界点抖动。
    //
    // 两个要点：
    // 1. 用 snapshotFlow 同时读两个值。如果只观察 expandAnim.value、然后在 collect 里
    //    读 playerState.playing，读到的会是**进入组合那一刻**的旧值
    //    （LaunchedEffect(Unit) 不跟着重组重启），于是收起时永远判断不出该暂停，音乐不会停。
    // 2. 用一个"这次展开是否已经请求过播放"的标记。展开动画有几十帧，
    //    每帧都调 start() 的话，在 prepare 还没完成时会反复触发失败重试——
    //    只请求一次，剩下的交给 prepared 回调里的 pendingStart 处理。
    var startRequested by remember(track.path) { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { expandAnim.value to playerState.playing }
            .collect { (progress, playing) ->
                if (progress >= 0.5f) {
                    if (!playing && !startRequested) {
                        startRequested = true
                        player.start()
                    }
                } else if (progress <= 0.3f) {
                    // 回到收起区间：允许下一次展开重新请求播放。
                    startRequested = false
                    if (playing) player.pause()
                }
            }
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 状态栏高度：模糊背景要铺到它下面，清晰封面从它下面开始。
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // updatedAt 变化说明刚被刮削写过，重新读一遍文件，标签状态和图片立刻更新。
    val detail by produceState<TrackDetail?>(null, track.path, track.updatedAt, refreshTicket) {
        value = loadDetail(track)
    }

    /*
     * 刮削跑在后台服务里，跑完详情页不会自己知道。
     *
     * 用户报的问题正是这个：选完候选/确认写入之后**停在这一页**，头像和文件信息
     * 还是旧的，得退出详情页再进来；很合理地推断成"文件根本没写进去"。
     * 文件其实写进去了，只是界面没有重读一遍。
     *
     * 判据用 [ScrapeProgress.finishedAt]（完成时刻）而不是 running 的 true→false：
     * 单曲刮削可能只花几百毫秒，StateFlow 只保证最新值，主线程一忙就只看到 false，
     * 于是这次刷新被整个漏掉。时间戳一定会变，漏不掉。
     */
    val scrapeProgress by com.audiometa.app.domain.usecase.ScrapeService.progress.collectAsState()
    var lastScrapeDone by remember { mutableStateOf(0L) }
    LaunchedEffect(scrapeProgress.finishedAt) {
        val doneAt = scrapeProgress.finishedAt
        if (doneAt == 0L || doneAt == lastScrapeDone) return@LaunchedEffect
        lastScrapeDone = doneAt
        // 两类缩略图缓存都要重建：艺术家图的缓存 key 只绑艺术家名，
        // 不重建的话文件写进去了头像也还是旧的。
        invalidateCoverCache(context)
        withContext(Dispatchers.IO) {
            runCatching {
                com.audiometa.app.di.ServiceLocator.indexer.refreshTrack(track.id)
            }
        }
        refreshTicket++
    }
    /*
     * 封面按**屏幕物理像素**解码，而不是写死 1024。
     *
     * 用户要"顶部显示的是封面原图或高质量 JPG"：以前盘里只存 512 边长的 JPEG，
     * 详情页又传 1024，实际拿到的还是 512 的图，1080p 屏上明显发虚。
     * 现在按屏宽实际像素取（上限 1600，兼顾内存），配合磁盘缓存的高清化，
     * 展开看到的就是接近原图质量的封面。
     */
    val coverTargetEdge = with(androidx.compose.ui.platform.LocalDensity.current) {
        screenWidth.toPx().toInt().coerceIn(640, 1600)
    }

    // 进度条配色：跟着封面底部走，取色只在展开后做一次，收起不重算。
    val fallbackPrimary = MaterialTheme.colorScheme.primary
    val fallbackSurface = MaterialTheme.colorScheme.surfaceVariant
    val palette by produceState(
        initialValue = CoverColorSampler.Palette.fallback(fallbackPrimary, fallbackSurface),
        track.path, track.updatedAt, refreshTicket, expanded
    ) {
        value = if (!expanded) {
            CoverColorSampler.Palette.fallback(fallbackPrimary, fallbackSurface)
        } else {
            withContext(Dispatchers.IO) {
                CoverColorSampler.sampleSource(
                    audioPath = track.path,
                    kind = ArtworkKind.COVER,
                    fallbackPrimary = fallbackPrimary,
                    fallbackSurface = fallbackSurface
                )
            }
        }
    }

    /*
     * 写入方式：横向开关 —— 开 = 覆盖，关 = 跳过已有的内容（只补缺）。
     *
     * 用户要求放在刮削入口上方。默认"覆盖"（详情页手动入口的原有行为），
     * 关掉之后走写入策略的 FILL_MISSING：文件里已有艺术家图/歌词/元信息时不再改写。
     */
    var overwrite by remember(track.path) { mutableStateOf(true) }
    // 组合刮削：先只搜不写，弹出对比让用户确认。
    var plan by remember { mutableStateOf<com.audiometa.app.domain.usecase.ScrapePlan?>(null) }
    var planning by remember { mutableStateOf(false) }

    // 单项刮削先进结果选择页，选中了才写文件。
    var picking by remember { mutableStateOf<ScrapeCapability?>(null) }
    val target = picking
    if (target != null) {
        com.audiometa.app.ui.screen.scrape.ScrapePickerScreen(
            trackId = track.id,
            capability = target,
            audioPath = track.path,
            stamp = track.updatedAt,
            onBack = { picking = null },
            onPick = { candidate ->
                com.audiometa.app.domain.usecase.ScrapeService.startWithCandidate(
                    context = context,
                    trackId = track.id,
                    capability = target,
                    pluginId = candidate.sourceId,
                    candidateJson = candidate.raw,
                    forceOverwrite = overwrite
                )
                picking = null
            },
            onPickLyrics = { candidate ->
                // 歌词的正文已经在候选里了，直接带过去写，不用再问一次源。
                com.audiometa.app.domain.usecase.ScrapeService.startWithLyrics(
                    context = context,
                    trackId = track.id,
                    candidateJson = org.json.JSONObject()
                        .put("lyrics", candidate.lyrics)
                        .put("translation", candidate.translation)
                        .put("type", candidate.type)
                        .toString(),
                    forceOverwrite = overwrite
                )
                picking = null
            }
        )
        BackHandler(enabled = true) { picking = null }
        return
    }

    plan?.let { current ->
        com.audiometa.app.ui.screen.scrape.ScrapePlanSheet(
            plan = current,
            loading = planning,
            onDismiss = { plan = null; planning = false },
            onConfirm = {
                plan = null
                planning = false
                com.audiometa.app.domain.usecase.ScrapeService.startWithPlan(
                    context = context,
                    trackId = track.id,
                    capabilities = current.capabilities,
                    plan = current.entries,
                    // 详情页的手动入口：默认覆盖，不受"已有内容就跳过"影响。
                    forceOverwrite = overwrite
                )
            }
        )
    }

    BackHandler(enabled = true) {
        when {
            plan != null -> {
                plan = null
                planning = false
            }
            // 展开状态下返回先收起封面（同时停止播放），不直接退出页面。
            expandAnim.value > 0.02f -> scope.launch {
                collapseCover(expandAnim)
                player.stop()
            }
            else -> onBack()
        }
    }

    // 状态栏透明，让封面的模糊色透上来；图标明暗按封面顶部亮度自动切换。
    val view = LocalView.current
    LaunchedEffect(track.path, track.updatedAt) {
        val tint = withContext(Dispatchers.IO) { sampleTopColor(track.path) }
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        @Suppress("DEPRECATION")
        run { window.statusBarColor = android.graphics.Color.TRANSPARENT }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        val color = tint ?: DEFAULT_STATUS_BAR_COLOR
        val r = android.graphics.Color.red(color) / 255f
        val g = android.graphics.Color.green(color) / 255f
        val b = android.graphics.Color.blue(color) / 255f
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        // 封面偏亮就用深色图标，反之用浅色。
        controller.isAppearanceLightStatusBars = luminance > 0.62f
    }
    DisposableEffect(Unit) {
        onDispose {
            (context as? android.app.Activity)?.window?.let { win ->
                @Suppress("DEPRECATION")
                run { win.statusBarColor = DEFAULT_STATUS_BAR_COLOR }
                androidx.core.view.WindowCompat.getInsetsController(win, view)
                    .isAppearanceLightStatusBars = false
            }
        }
    }

    /**
     * 刷新封面：清缓存 + 重读文件。
     *
     * 读取顺序交给 [com.audiometa.app.media.image.ArtworkResolver]：
     * 先找同名外置图（.cover.jpg / .jpg …），没有才读内嵌图片。
     * 这正是用户要的"有外置封面图就默认优先读取"。
     */
    fun refreshCover() {
        scope.launch {
            // 只失效封面，不动艺术家图：一起失效的话头像会闪一下占位图再回来。
            com.audiometa.app.ui.component.invalidateCoverOnly(context)
            val found = withContext(Dispatchers.IO) {
                val file = File(track.path)
                val external = com.audiometa.app.media.image.ArtworkResolver.externalCoverPath(file)
                val source = com.audiometa.app.media.image.ArtworkResolver.resolve(file, ArtworkKind.COVER)
                external to (source != null)
            }
            coverNotice = when {
                found.first != null -> "已刷新封面：使用外置图片"
                found.second -> "已刷新封面：读取内嵌图片"
                else -> "这首歌没有封面图片"
            }
            refreshTicket++
        }
    }

    // 整页用一个 Box 包住：展开封面之后要盖一层**全屏**手势层，
    // 这样"从下往上滑"在整个屏幕上都能生效，而不是只有封面那一条。
    //
    // 用 BoxWithConstraints 拿**内容区实际高度**：底栏由外层 Scaffold 的
    // padding 掉了，maxHeight 才是这一页真正能画的高度 —— 展开封面时
    // 进度条就在这个高度里贴底，不会被底栏顶出屏幕。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentMaxHeight = maxHeight

        val collapsedCover = screenWidth * 10f / 16f
        // 展开的目标高度不是「屏幕宽」，而是「按封面原始比例铺满屏幕宽」后的高度：
        // 这样展开时看到的是完整封面，而不是把方图裁掉上下两半。
        // 上限按**内容区实际高度**（不含底栏）的 88% 算：特别高的封面也不至于
        // 把下面的内容全顶出屏幕，进度条始终留在可见范围内。
        val coverAspect = detail?.coverAspect?.takeIf { it > 0.05f } ?: 1f
        val expandedCover = minOf(screenWidth / coverAspect, contentMaxHeight * 0.88f)
        val coverHeight = collapsedCover + (expandedCover - collapsedCover) * expandAnim.value
        Column(
            // 顶部不留边距，封面直接贴到屏幕最上沿。
            // 封面展开时关掉整页滚动：这时候整屏都是封面，用户上下滑的意思
            // 是"刷新封面 / 收起"，不是"滚动下面的元信息"。
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = !expanded)
                .padding(bottom = 8.dp)
        ) {
        // 封面从屏幕最顶端开始（顶到状态栏上面），状态栏那一段用虚化盖住。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
        ) {
            // 虚化层：只盖住状态栏那一段。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight)
                    .align(Alignment.TopCenter)
                    .clipToBounds()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.White,
                                1f to Color.Transparent
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                        )
                    }
            ) {
                TrackCover(
                    audioPath = track.path,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    kind = ArtworkKind.COVER,
                    stamp = track.updatedAt,
                    refreshKey = refreshTicket,
                    // 详情页的封面要占满整屏宽，按屏幕实际像素解码（列表里默认只有 256）。
                    targetEdge = coverTargetEdge,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(coverHeight)
                        .blur(10.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                )
            }

            // 清晰封面。点击展开/收起；展开后的滑动交给上面那层全屏手势层。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .pointerInput(track.path) {
                        detectTapGestures {
                            if (expandAnim.value > 0.5f) {
                                scope.launch {
                                    collapseCover(expandAnim)
                                    player.pause()
                                }
                            } else {
                                scope.launch { settleCover(expandAnim, true) }
                            }
                        }
                    }
            ) {
                TrackCover(
                    audioPath = track.path,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    kind = ArtworkKind.COVER,
                    stamp = track.updatedAt,
                    refreshKey = refreshTicket,
                    // 详情页的清晰封面：展开后铺满整屏，按大图解码。
                    targetEdge = coverTargetEdge,
                    // 展开到位才按原始比例完整显示；过程中保持裁切，避免中途跳变。
                    alignment = if (expanded) Alignment.Center else Alignment.TopCenter,
                    animated = expanded,
                    modifier = Modifier.fillMaxWidth().height(coverHeight),
                    contentDescription = track.title
                )
            }

            // 收起时左上角的返回按钮；展开时变成"收起"。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = statusBarHeight + 4.dp,
                        start = 4.dp,
                        end = 4.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (expandAnim.value > 0.02f) {
                        scope.launch {
                            collapseCover(expandAnim)
                            player.stop()
                        }
                    } else {
                        onBack()
                    }
                }) {
                    Icon(
                        if (expandAnim.value > 0.02f) Icons.Outlined.Close else Icons.Outlined.ArrowBack,
                        contentDescription = if (expandAnim.value > 0.02f) "收起封面" else "返回"
                    )
                }
                Spacer(Modifier.weight(1f))
            }

            // 封面下拉刷新后的提示，两秒后自己消失。
            if (coverNotice.isNotBlank()) {
                LaunchedEffect(coverNotice) {
                    kotlinx.coroutines.delay(2000)
                    coverNotice = ""
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusBarHeight + 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(coverNotice, style = MaterialTheme.typography.labelMedium)
                }
            }

            // 封面下沿柔和过渡到页面背景。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = (1f - expandAnim.value * 0.35f).coerceIn(0f, 1f) }
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                            0.78f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
            )

            /*
             * 展开后的播放进度条。
             *
             * **必须是封面 Box 的最后一个子节点**：以前画在渐变过渡层之前会被盖住，
             * 看起来"半隐藏"。
             *
             * 垫底不再用黑色圆角块 —— 那是用户说的"割裂感"的来源：一块突兀的黑
             * 浮在封面上。现在改成一条**从透明到页面背景色**的柔和渐变，
             * 和封面下沿那条 96dp 渐变是同一种语言：封面 → 渐变 → 进度条 → 页面
             * 背景，一路过渡下来，不再有边界。
             */
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                        .padding(top = 26.dp, bottom = 10.dp)
                ) {
                    CoverProgressBar(
                        state = playerState,
                        palette = palette,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            /*
             * 头像 + 右侧几行。
             *
             * 用户逐项指定了这块的排版：
             *   1. **艺术家名字显示在头像底部**（原来在右侧第一行）；
             *   2. 头像右侧从上到下是：**歌曲名 → 专辑 → 封面展开并播放 → 歌词/翻译状态**。
             * 歌词 / 翻译这两个胶囊原来在下面单独占一行，现在按用户要求
             * 挪到"展开封面播放"那一行的正下方（见右侧 Column 末尾）。
             */
            // detail 可能还没读回来（此时 meta 为 null）。提前取一次是因为
            // 下面右侧 Column 里的状态胶囊要用它，而局部变量必须先声明后使用。
            val meta = detail?.meta
            /**
             * 点任意标签都重新读一次音频文件。
             *
             * 不只是清缩略图 —— 用户点这个的意思是"文件我刚改过，重来一遍"，所以：
             * - 清掉这首歌的缩略图（封面 + 艺术家图两种变体）；
             * - 让后台重新读标签、重建歌词搜索缓存、重算开头静音；
             * - refreshTicket +1 触发详情页和大图重新取一次文件。
             */
            val refresh: () -> Unit = {
                invalidateCoverCache(context)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        com.audiometa.app.di.ServiceLocator.indexer.refreshTrack(track.id)
                    }
                }
                refreshTicket++
            }

            Row(
                // 顶部只留 1dp：这一页要塞进一屏，头像那一块不能再有额外留白。
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp)
                ) {
                    TrackCover(
                        audioPath = track.path,
                        shape = CircleShape,
                        kind = ArtworkKind.ARTIST,
                        stamp = track.updatedAt,
                        refreshKey = refreshTicket,
                        modifier = Modifier
                            .size(52.dp)
                            // 点歌手头像：从下方弹出窗口。
                            .clickable { showArtistSheet = true },
                        contentDescription = "查看艺术家图片"
                    )
                    // 艺术家名字在头像底部（用户要求）。
                    Text(
                        (detail?.meta?.artist ?: track.artist).ifBlank { "未知艺术家" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                    Text(
                        detail?.meta?.title?.ifBlank { track.title } ?: track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (detail?.meta?.album ?: track.album).ifBlank { "未知专辑" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 播放状态一行：让"展开即播放"这件事有文字反馈。
                    PlayerStatusLine(playerState)
                    /*
                     * 歌词 / 翻译两个状态胶囊：从下面那一行挪到这里 —— 用户要求
                     * 放在"点击封面并开始播放"（也就是上面这行状态文字）的下方。
                     *
                     * 紧凑排一行：两个胶囊之间 6dp，上下不留多余空白。
                     * 状态规则见 [StatusPill]：有歌词 / 有翻译 = 正常字色 + 可点，
                     * 无歌词 / 无翻译 = 灰色 + 完全不可交互。
                     */
                    if (metaFocus == null) {
                        val lyricsText = meta?.lyrics.orEmpty()
                        val lyricsType = meta?.lyricsType ?: track.lyricsType
                        val hasLyrics = meta?.hasLyrics ?: track.hasLyrics
                        Row(
                            modifier = Modifier.padding(top = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 歌词项：文字按实际读到的歌词类型显示（逐字歌词 / 逐行歌词 / 纯文本歌词）。
                            // 没有歌词时置灰且不可点：没有内容可看，弹窗也没意义。
                            StatusPill(
                                label = lyricsPillLabel(lyricsText, lyricsType, hasLyrics),
                                enabled = hasLyrics,
                                onClick = {
                                    refresh()
                                    showLyrics = true
                                }
                            )
                            when (translationTagOf(lyricsText, meta?.hasTranslation ?: track.hasTranslation)) {
                                // 中文歌词本来就不需要翻译：更小的中性样式，点了没有任何反应。
                                TranslationTag.NotNeeded -> StatusPill(
                                    label = "无需翻译",
                                    enabled = false,
                                    compact = true
                                )
                                // 需要翻译且文件里有：正常显示，点开歌词窗口看双语。
                                TranslationTag.Available -> StatusPill(
                                    label = "翻译",
                                    enabled = true,
                                    onClick = {
                                        refresh()
                                        showLyrics = true
                                    }
                                )
                                // 需要翻译但文件里没有：灰色（没什么可看的），不可点。
                                TranslationTag.Absent -> StatusPill(
                                    label = "无翻译",
                                    enabled = false
                                )
                            }
                            // 写入方式和无歌词/翻译标签同一行，省掉单独一行。
                            Spacer(Modifier.weight(1f))
                            Text(
                                "写入",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            WriteModeChip("覆盖", selected = overwrite) { overwrite = true }
                            Spacer(Modifier.width(2.dp))
                            WriteModeChip("跳过", selected = !overwrite) { overwrite = false }
                        }
                    }
                }
            }

            /*
             * 头像底下那几项（歌词 / 翻译 / 组合刮削 / 艺术家图片 / 元信息刮削）。
             *
             * 用户明确要求：**从元信息页点进来的详情页要把这些项整体移除**，
             * 把上面腾出来的位置让给「元信息」区域。所以整块只在普通详情页里画。
             * 其中「歌词 / 翻译」两个胶囊已经上移到播放状态那一行下面了，
             * 这里只剩下刮削入口和写入方式开关。
             */
            if (metaFocus == null) {
                /*
                 * 刮削区：一排图标按钮。
                 *
                 * 之前是六个小胶囊硬挤一行，字缩到 10sp、还带一个两态块，看着就是一排碎按钮 ——
                 * 用户说"布局太难看"。现在改成常见的"图标 + 小字"操作行：
                 * 五个等宽的点击区，图标在上、文字在下，没有底色，靠间距和排列对齐。
                 */
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    // 一层很淡的底色把五个入口收成一组：不加这层，图标浮在页面上
                    // 看着像零散元素；加了之后它是一条明确的"操作栏"。
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        ScrapeTile(
                            label = "组合",
                            icon = Icons.Outlined.AutoAwesome,
                            busy = planning,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (planning) return@ScrapeTile
                            /*
                             * 组合 = 曲风 + 歌词。艺术家图和专辑图不参与：
                             * 它们各有自己的入口，也不会因为"组合"被顺手改掉。
                             * 三基项匹配、匹配不上不写，全部由 ScrapeService 统一执行。
                             */
                            planning = true
                            com.audiometa.app.domain.usecase.ScrapeService.startWithPlan(
                                context = context,
                                trackId = track.id,
                                capabilities = COMBINED_SCRAPE_CAPABILITIES,
                                plan = emptyList(),
                                forceOverwrite = true
                            )
                            planning = false
                            android.widget.Toast.makeText(
                                context,
                                "已开始组合刮削（曲风 + 歌词）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        // 按钮顺序固定（用户指定）：组合 → 专辑图 → 艺术家图 → 曲风 → 歌词。
                        // 元信息整块刮削已从详情页移除，只保留「曲风」这一项文本字段。
                        DETAIL_SCRAPE_TILES.forEach { capability ->
                            ScrapeTile(
                                label = capability.compactLabel,
                                icon = capability.tileIcon,
                                busy = false,
                                modifier = Modifier.weight(1f)
                            ) { picking = capability }
                        }
                    }
                }
            }

            val loaded = detail
            if (metaFocus != null) {
                // 元信息歌曲详情页：这一区不再是可编辑的标准字段，
                // 而是"当前自定义标签的键名 + 完整值 + 说明"。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DetailSectionTitle("自定义标签 · " + metaFocus.group)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        copyToClipboard(
                            context,
                            "元信息",
                            buildTrackMetaReport(track, metaFocus)
                        )
                        android.widget.Toast
                            .makeText(context, "已复制本曲元信息", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }) { Text("复制") }
                }
                MetaTagPanel(metaFocus)
            } else {
                DetailSectionTitle("元信息")
                if (loaded == null) {
                    Text("正在读取文件…", style = MaterialTheme.typography.bodySmall)
                } else {
                // 可编辑：点任意一项就能改这一项。
                //
                // 用户要求"手动修改了某一项信息后，直接同步写入该文件"：
                // 弹窗点「确定」= 立刻把**这一项**写进文件，底部那一整行
                // 「有 N 项待保存 / 放弃 / 保存」已经删掉，不再让用户确认第二次。
                val metaItems = editableMetaOf(loaded.meta)
                MetaEditor(
                    items = metaItems,
                    onEdit = { key, value ->
                        // 1. 乐观覆盖：弹窗一关这一行就显示新值，不等写盘、也不等重读。
                        metaEdits[key] = value
                        scope.launch {
                            // 记下这次重读之前的 detail，用来判断"文件里的值已经读回来了"。
                            val before = detail
                            // 一次只写一项：tags 里只有这一个键，
                            // 和原来点「保存」走的是同一条写入路径。
                            val result = withContext(Dispatchers.IO) {
                                // 同一首歌的两次写入串行进行，避免撞在同一个临时文件上。
                                metaWriteLock.withLock {
                                    com.audiometa.app.media.tag.RawTagWriter.write(
                                        file = File(track.path),
                                        tags = mapOf(key to value),
                                        keepModTime = keepModTime
                                    )
                                }
                            }
                            val name = metaItems.firstOrNull { it.fieldKey == key }?.label ?: key
                            metaWriteFailed = !result.ok
                            metaSaveNotice = when {
                                !result.ok -> "「" + name + "」写入失败：" + (result.error ?: "未知错误")
                                key in result.skipped -> "「" + name + "」写不进这个格式"
                                else -> "已写入「" + name + "」"
                            }
                            // 三步刷新照旧：清缩略图缓存 → 后台重读标签 →
                            // refreshTicket +1 让详情页和大图重新取一次文件。
                            // 写失败也走一遍：这样界面显示的是文件里真实的值，不会骗人。
                            invalidateCoverCache(context)
                            // 写入之后按用户要求重新读一遍标签 + 刷新缓存数据，
                            // 不只是在界面上重取图片。
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    com.audiometa.app.di.ServiceLocator.indexer
                                        .refreshTrack(track.id)
                                }
                            }
                            refreshTicket++
                            /*
                             * 等文件重读回来，再撤掉这一项的乐观覆盖。
                             *
                             * 撤早了会在"文件已经改了、界面还没重读"的空档里
                             * 闪回旧值 —— 那正是这层覆盖要解决的问题。
                             * 撤掉之后这一行显示的就是文件里真正读到的值
                             * （写失败时也一样，等于把改动撤回）。
                             * 3 秒兜底：万一重读没回来，也不能让覆盖一直留在内存里。
                             */
                            withTimeoutOrNull(3000) {
                                snapshotFlow { detail }.first { it !== before }
                            }
                            metaEdits.remove(key)
                        }
                    },
                    // 乐观覆盖层：显示优先级是"覆盖里的新值 > 文件里读到的值"。
                    edits = metaEdits
                )
                // 写入结果的一句话反馈，2.6 秒后自己消失。
                if (metaSaveNotice.isNotBlank()) {
                    LaunchedEffect(metaSaveNotice) {
                        kotlinx.coroutines.delay(2600)
                        metaSaveNotice = ""
                    }
                    Text(
                        metaSaveNotice,
                        style = MaterialTheme.typography.labelSmall,
                        // 失败用错误色：失败提示和成功提示长得一样会被看漏。
                        color = if (metaWriteFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                }
            }

            DetailSectionTitle("文件")
            if (loaded != null) {
                // 四行两列的固定排版（用户逐项指定）：
                //
                //   时长     格式
                //   比特率   采样率
                //   声道     位深
                //   大小     修改时间
                //
                // 「大小」原来在第三行右边，用户要求把它移到下一行，
                // 第四行按"大小 → 修改时间"的先后排；腾出来的位置显示**音频位深**。
                InfoRows(
                    listOf(
                        listOf(
                            "时长" to formatDuration(loaded.meta.durationMs),
                            "格式" to loaded.format
                        ),
                        listOf(
                            "比特率" to if (loaded.bitRate > 0) {
                                loaded.bitRate.toString() + " kbps" + (if (loaded.variableBitRate) " VBR" else "")
                            } else {
                                ""
                            },
                            "采样率" to if (loaded.sampleRate > 0) loaded.sampleRate.toString() + " Hz" else ""
                        ),
                        listOf(
                            "声道" to loaded.channels,
                            "位深" to if (loaded.bitDepth > 0) loaded.bitDepth.toString() + " bit" else ""
                        ),
                        listOf(
                            "大小" to formatSize(loaded.fileSize),
                            "修改时间" to formatTime(loaded.lastModified)
                        )
                    )
                )
            }
            // 路径放在文件信息最后一行；不额外高亮，也不固定在页面底部。
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    track.path,
                    style = MaterialTheme.typography.labelSmall,
                    // 完整显示，不省略；长路径自然换行。
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(track.path))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "复制路径",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.padding(bottom = 4.dp))
        }

        /**
         * 展开封面时的**全屏**手势层。
         *
         * 为什么要单独盖一层，而不是挂在封面上：
         * 用户的手指不会乖乖落在封面范围内——它可能落在进度条上、落在下面的
         * 元信息上、落在屏幕最底部。挂在封面上时，这些位置的"从下往上滑"
         * 完全没有反应。盖一层全屏的之后，**整个屏幕**都能上滑收起、下滑刷新。
         *
         * 只在展开时存在：收起时这一层不参与组合，列表滚动照旧。
         */
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(track.path, expandedCover) {
                        var axis = 0
                        var totalDx = 0f
                        var totalDy = 0f
                        var startFraction = 0f
                        var lastFraction = 0f
                        detectDragGestures(
                            onDragStart = {
                                axis = 0
                                totalDx = 0f
                                totalDy = 0f
                                startFraction = playerState.progress
                                lastFraction = startFraction
                            },
                            onDragCancel = { axis = 0 },
                            onDragEnd = {
                                when (axis) {
                                    // 上下：往下滑 = 刷新封面（优先外置图），
                                    //       往上滑 = 收起并【停止】播放。
                                    // 不做跟手收缩，避免和"刷新"抢同一个方向。
                                    2 -> {
                                        if (totalDy > 0f) {
                                            refreshCover()
                                        } else if (totalDy < 0f) {
                                            scope.launch {
                                                collapseCover(expandAnim)
                                                player.stop()
                                            }
                                        }
                                    }
                                    // 左右：松手才真正 seek 一次。
                                    1 -> player.commitPosition(
                                        (lastFraction * playerState.durationMs).toLong()
                                    )
                                }
                                axis = 0
                            },
                            onDrag = { change, delta ->
                                totalDx += delta.x
                                totalDy += delta.y
                                if (axis == 0 &&
                                    (kotlin.math.abs(totalDx) > 10f || kotlin.math.abs(totalDy) > 10f)
                                ) {
                                    axis = if (kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)) 1 else 2
                                }
                                if (axis != 1) return@detectDragGestures
                                change.consume()
                                val durationMs: Long = playerState.durationMs
                                val width = size.width.toFloat()
                                if (durationMs <= 0L || width <= 0f) {
                                    return@detectDragGestures
                                }
                                // 拖动映射：一次满宽滑动 = 总时长的一半，而且是**递增**的。
                                //
                                // 之前是线性的"满宽 = 10%"，3 分 50 秒的歌整条划过去只跳 23 秒，
                                // 想跳副歌得划好几遍。现在用 index^1.7：
                                // 手指刚离开起点时变化很细（能精确定位），越往右跨度越大（能快速跳远），
                                // 划到底正好半首歌。
                                val index = (totalDx / width).coerceIn(-1f, 1f)
                                val curved = if (index >= 0f) {
                                    index.toDouble().pow(1.7).toFloat()
                                } else {
                                    -(-index.toDouble()).pow(1.7).toFloat()
                                }
                                val nextFraction = (startFraction + curved * 0.5f).coerceIn(0f, 1f)
                                lastFraction = nextFraction
                                player.previewPosition((nextFraction * durationMs).toLong())
                            }
                        )
                    }
                    // 点一下 = 收起并暂停（和点封面同一个语义）。
                    .pointerInput(track.path) {
                        detectTapGestures {
                            scope.launch {
                                collapseCover(expandAnim)
                                player.pause()
                            }
                        }
                    }
            ) {
                // 手势层里的按钮：只占左上角一小块，其余区域照常传手势。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = statusBarHeight + 4.dp,
                            start = 4.dp,
                            end = 4.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        scope.launch {
                            collapseCover(expandAnim)
                            player.stop()
                        }
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "收起封面")
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    // 点歌手头像：底部弹出窗口，里面是艺术家大图 + 保存/选取两条操作。
    if (showArtistSheet) {
        ArtistImageSheet(
            audioPath = track.path,
            stamp = track.updatedAt,
            title = (detail?.meta?.artist ?: track.artist).ifBlank { "未知艺术家" },
            displayName = (detail?.meta?.artist ?: track.artist).ifBlank { track.title },
            refreshKey = refreshTicket,
            onPickFromAlbum = { uri ->
                scope.launch {
                    ArtworkTools.replaceArtistImage(context, track, uri)
                    invalidateCoverCache(context)
                    // 换图后不仅要重读详情，窗口里的大图也要重新取一次。
                    refreshTicket++
                }
            },
            onDismiss = { showArtistSheet = false }
        )
    }

    // 点「歌词」标签弹出的底部歌词窗口：默认 70%，可在顶部拖动改大小。
    if (showLyrics) {
        LyricsSheet(
            title = (detail?.meta?.title ?: track.title).ifBlank { track.path.substringAfterLast('/') },
            lyrics = detail?.meta?.lyrics.orEmpty(),
            translation = detail?.meta?.translation.orEmpty(),
            onDismiss = { showLyrics = false }
        )
    }
    }
}

/** 展开/收起：拖动到一半就按方向吸过去，带一点弹性，松手不会顿一下。 */
private suspend fun settleCover(anim: Animatable<Float, *>, open: Boolean) {
    anim.animateTo(
        targetValue = if (open) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )
}

private suspend fun collapseCover(anim: Animatable<Float, *>) = settleCover(anim, false)

/**
 * 封面底部的播放进度条。
 *
 * 配色来自封面底部取色：已播放部分用主色，未播放部分是它的暗化版本，
 * 所以进度条看起来像封面自己延伸出来的一条，而不是贴上去的外来色块。
 */
@Composable
private fun CoverProgressBar(
    state: com.audiometa.app.media.audio.PlayerState,
    palette: CoverColorSampler.Palette,
    modifier: Modifier = Modifier
) {
    val duration = state.durationMs
    val shown = if (duration > 0L) state.positionMs else 0L
    val trackFraction = if (duration > 0L) {
        (shown.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatClock(shown),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0L) formatClock(duration) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(palette.restColor.copy(alpha = 0.85f), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(trackFraction)
                    .height(6.dp)
                    .background(palette.trackColor, CircleShape)
            )
        }
    }
}

/** 播放器状态一行字。状态栏高度已经让开了，不会挡到内容。 */
@Composable
private fun PlayerStatusLine(state: com.audiometa.app.media.audio.PlayerState) {
    val text = when {
        state.message.isNotBlank() -> state.message
        state.playing -> "正在播放 · 上滑封面可停止"
        state.prepareState == com.audiometa.app.media.audio.PrepareState.PREPARING -> "正在准备音频…"
        state.prepareState == com.audiometa.app.media.audio.PrepareState.FAILED -> "音频无法播放"
        else -> "展开封面播放"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 顶部状态胶囊（歌词 / 翻译）。
 *
 * 规则（用户逐项指定）：
 * - **不带任何 ✓ / ✗ 前缀**："有没有"由颜色和能不能点表达，
 *   不再往文案里塞符号（原来 `(if (ok) "✓ " else "✗ ") + label` 正是用户要去掉的）；
 * - [enabled] = true（有歌词 / 有翻译）：正常字色、正常内边距、可点；
 * - [enabled] = false（无歌词 / 无翻译）：灰色，而且**点击没有任何反应** ——
 *   不弹歌词窗、不刷新文件，连水波纹都没有；
 * - [compact] = true：「无需翻译」这一档用更小的字号和内边距，
 *   和"真的缺东西"（无翻译）区分开，也因为中文歌词本来就不需要翻译。
 *
 * 实现上不可交互时**干脆不挂 clickable**，而不是 `Card(onClick = {})` 那种空 lambda：
 * 空 lambda 仍然带点击语义和水波纹，看上去是个能点的按钮、点了却什么都不发生，
 * 那正是用户说的"看着能点又没反应"。
 */
@Composable
private fun StatusPill(
    label: String,
    enabled: Boolean,
    compact: Boolean = false,
    // 不可交互时这个 lambda 根本不会被调用（见上面的说明）。
    onClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(50)
    Surface(
        // 只有可交互的胶囊才进交互树：不可交互时连 clickable 都不加。
        modifier = if (enabled) {
            Modifier.clip(shape).clickable(onClick = onClick)
        } else {
            Modifier.clip(shape)
        },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        // 字色交给 Surface 的 contentColor 传给里面的 Text：
        // 可点 = 正常字色（onSurface），不可点 = 灰色（onSurfaceVariant）。
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            label,
            /*
             * 用户要求「头像右侧的歌词和翻译标签更小一些」：
             * 从 labelSmall（12sp）压到 9sp，内边距也跟着收，
             * 两个胶囊加起来只占一行的一小截，不再把右侧那几行撑高。
             */
            style = if (compact) {
                // 「无需翻译」：再小一号，弱化但不像是"缺东西"。
                MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp)
            } else {
                MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = if (compact) 5.dp else 7.dp,
                vertical = if (compact) 1.dp else 2.dp
            )
        )
    }
}

/** 详情页的元信息区是可编辑的，这里直接用 FieldKey 的名字当键。 */
private typealias FieldKey = org.jaudiotagger.tag.FieldKey

/**
 * 导出这一首歌的自定义标签。
 *
 * 格式按用户给的样例（`正确.txt`）钉死：
 * ```
 * 歌曲元信息导出
 * 标题：… / 艺术家：… / 专辑：… / 来源：… / 文件：…
 *
 * voice-memo-uuid
 * 类型：苹果语音备忘录内部 UUID
 * 说明：…
 * 值：DC40A8AD-…
 * 别名：UUID
 * ```
 * **不写"原始键名"**：容器前缀（`----:com.apple.iTunes:`）对用户没有意义，
 * 详情卡片里能看到就够了，导出的文本不带它。
 */
private fun buildTrackMetaReport(track: TrackEntity, focus: MetaTagFocus): String = buildString {
    appendLine("歌曲元信息导出")
    appendLine("标题：" + track.title.ifBlank { track.path.substringAfterLast('/') })
    appendLine("艺术家：" + track.artist.ifBlank { "（无）" })
    appendLine("专辑：" + track.album.ifBlank { "（无）" })
    appendLine("来源：" + focus.group)
    appendLine("文件：" + track.path)
    appendLine()
    focus.items.forEach { item ->
        appendLine(item.key)
        item.typeLabel.takeIf { it.isNotBlank() }?.let { appendLine("类型：" + it) }
        appendLine("说明：" + item.description.ifBlank { "规则里还没有这个标签的说明" })
        when {
            item.isImage -> appendLine("值：（图片）")
            item.isEmptyValue -> appendLine("值：（无值，文件里只有键）")
            else -> appendLine("值：" + item.rawValue)
        }
        if (!item.isImage && item.valueAlias.isNotBlank() && item.valueAlias != item.rawValue) {
            appendLine("别名：" + item.valueAlias)
        }
        appendLine()
    }
}

/**
 * 元信息歌曲详情页里的「自定义标签」区。
 *
 * 用户要求：
 * - **值和介绍要区分开**：值在上（正常字号、正文色、可点复制），
 *   介绍在下（小字、次要色），两者不再是同一种文本格式；
 * - **键名和值都有中文别名**：键名旁边贴键名别名，值下面贴值别名；
 * - 说明优先用**值级**的（同一类值共用一条，不为每个键各写一份）。
 *
 * 布局：
 * ```
 * ⧉ major_brand   容器主品牌          ← 键名 + 键名中文别名（点一下复制键名）
 *   值  iso5                         ← 值在上（点一下复制完整值）
 *   别名  ISO 基础媒体格式第 5 版       ← 值的中文别名
 *   ─────────────────────────
 *   说明  ftyp box 的主品牌…           ← 介绍在下，小字次要色
 * ```
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetaTagPanel(focus: MetaTagFocus) {
    val context = LocalContext.current
    var zoom by remember { mutableStateOf<String?>(null) }

    if (focus.items.isEmpty()) {
        Text("这个文件上没有识别到自定义标签。", style = MaterialTheme.typography.bodySmall)
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        focus.items.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    // ---- 键名 + 键名中文别名：点一下只复制键名 ----
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable {
                                copyToClipboard(context, "键名", item.key)
                                android.widget.Toast
                                    .makeText(context, "已复制键名", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.key,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.keyAlias.isNotBlank()) {
                            Text(
                                item.keyAlias,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp).padding(start = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // ---- 值在上 ----
                    when {
                        item.isImage -> LocalImage(
                            path = com.audiometa.app.media.tag.MetaImageStore
                                .fileOf(context, item.imageMarker)?.absolutePath,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { zoom = item.imageMarker },
                            contentDescription = "标签图片"
                        )

                        item.isEmptyValue -> Text(
                            "（无值，文件里只有这个键）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .clickable {
                                    copyToClipboard(context, "值", item.rawValue)
                                    android.widget.Toast
                                        .makeText(context, "已复制值", android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                item.rawValue,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            // 值的中文别名，贴在值的右边。
                            if (item.valueAlias.isNotBlank() && item.valueAlias != item.rawValue) {
                                Text(
                                    item.valueAlias,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }

                    // ---- 介绍在下：小字 + 次要色 + 分隔线，和值明显分开 ----
                    val description = item.description
                    if (description.isNotBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 7.dp, bottom = 5.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            (item.typeLabel.takeIf { it.isNotBlank() }?.let { it + "：" } ?: "") + description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 点图片放大看。
    zoom?.let { marker ->
        val path = com.audiometa.app.media.tag.MetaImageStore.fileOf(context, marker)?.absolutePath
        androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { zoom = null }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                LocalImage(path = path, modifier = Modifier.fillMaxWidth(), contentDescription = "标签图片")
            }
        }
    }
}

/**
 * 详情页要展示的字段顺序与可编辑键名。
 *
 * 顺序和"单列/双列"由用户逐项指定，不要随意调整：
 *
 * ```
 * 标题                       单列
 * 专辑                       单列
 * 艺术家                     单列
 * 专辑艺术家                 单列
 * 流派                       单列
 * 音轨号   碟号              双列
 * 作词     作曲              双列
 * 日期     语言              双列
 * 版权     注释              双列
 * BPM      ISRC              双列
 * ```
 *
 * 歌词类型不在这里：它是由文件里实际读到的歌词**自动判断**的，
 * 属于"状态"而不是"可编辑的元信息"，放在顶部那一行显示。
 */
private fun editableMetaOf(meta: TrackMeta, compact: Boolean = false): List<EditableMeta> {
    val all = listOf(
        EditableMeta("标题", FieldKey.TITLE.name, meta.title, singleColumn = true),
        EditableMeta("专辑", FieldKey.ALBUM.name, meta.album, singleColumn = true),
        EditableMeta("艺术家", FieldKey.ARTIST.name, meta.artist, singleColumn = true),
        EditableMeta("专辑艺术家", FieldKey.ALBUM_ARTIST.name, meta.albumArtist, singleColumn = true),
        EditableMeta("流派", FieldKey.GENRE.name, meta.genre, singleColumn = true),
        EditableMeta("音轨号", FieldKey.TRACK.name, meta.trackNo.takeIf { it > 0 }?.toString().orEmpty()),
        EditableMeta("碟号", FieldKey.DISC_NO.name, meta.discNo.takeIf { it > 0 }?.toString().orEmpty()),
        EditableMeta("作词", FieldKey.LYRICIST.name, meta.lyricist),
        EditableMeta("作曲", FieldKey.COMPOSER.name, meta.composer),
        EditableMeta(
            "日期",
            FieldKey.YEAR.name,
            meta.date.ifBlank { meta.year.takeIf { it > 0 }?.toString().orEmpty() }
        ),
        EditableMeta("语言", FieldKey.LANGUAGE.name, meta.language),
        EditableMeta("版权", FieldKey.COPYRIGHT.name, meta.copyright),
        EditableMeta("注释", FieldKey.COMMENT.name, meta.comment),
        EditableMeta("BPM", FieldKey.BPM.name, meta.bpm),
        EditableMeta("ISRC", FieldKey.ISRC.name, meta.isrc)
    )
    if (!compact) return all
    // 修改标签页：去掉这五项，剩下的全部单列，行距也放宽。
    val hidden = setOf(
        FieldKey.TRACK.name,
        FieldKey.DISC_NO.name,
        FieldKey.COPYRIGHT.name,
        FieldKey.BPM.name,
        FieldKey.ISRC.name
    )
    return all.filterNot { it.fieldKey in hidden }.map { it.copy(singleColumn = true) }
}

/**
 * 属性行。
 *
 * 标签宽度不再写死 44dp —— 那正好卡在"修改时间"（4 个字）的边界上，
 * 结果最后一个"间"被裁掉（用户报的）。现在由 [InfoRows] 按当前字体量出来传进来。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    labelWidth: androidx.compose.ui.unit.Dp,
    labelStyle: androidx.compose.ui.text.TextStyle
) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(labelWidth)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 固定分行的字段表。
 *
 * **每一行单独给出**，不再"按顺序两两配对"。理由：空值项会被过滤掉，
 * 配对式排版一旦中间有一项为空，后面的项就整体前移，行与行的对应关系全乱 ——
 * 用户要求的"第四行 = 大小 + 修改时间"就是这么被破坏的。
 *
 * 行内某一项为空时只影响它自己：这一行剩下的项仍然留在原来的位置上。
 */
@Composable
private fun InfoRows(rows: List<List<Pair<String, String>>>) {
    val visible = rows
        .map { row -> row.filter { it.second.isNotBlank() } }
        .filter { it.isNotEmpty() }
    if (visible.isEmpty()) return

    val labelStyle = MaterialTheme.typography.labelSmall
    val labelWidth = com.audiometa.app.ui.component.rememberLabelWidth(
        visible.flatten().map { it.first }, labelStyle
    )

    Column(Modifier.fillMaxWidth()) {
        visible.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, pair ->
                    if (index > 0) Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        InfoRow(pair.first, pair.second, labelWidth, labelStyle)
                    }
                }
            }
        }
    }
}

/**
 * 歌词那一项显示什么文字。
 *
 * 文字是**跟着文件里实际的歌词类型变的**（用户明确要求）：
 * 逐字歌词 / 逐行歌词 / 纯文本歌词；没有歌词时显示"无歌词"。
 * 以前这里固定写"歌词"，看不出来到底读到了什么。
 */
private fun lyricsPillLabel(lyrics: String, lyricsType: String, hasLyrics: Boolean): String {
    if (!hasLyrics && lyrics.isBlank()) return "无歌词"
    return when (lyricsType) {
        "word" -> "逐字歌词"
        "line" -> "逐行歌词"
        "plain" -> "纯文本歌词"
        else -> if (lyrics.isNotBlank()) "有歌词" else "无歌词"
    }
}

/**
 * 详情页的小节标题。
 *
 * 和设置页的 SectionTitle 同一套字，但上下留白更紧（7dp / 1dp）：
 * 用户要求"这一页的信息尽量在一页内显示完全"，两个小节标题各让出一点就够一行了。
 */
@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 7.dp, bottom = 1.dp)
    )
}

/**
 * 一个刮削入口：图标在上、短标签在下。
 *
 * 五个入口等宽排一行，没有底色、没有边框 —— 详情页里这一片是"操作行"，
 * 不是一排待选的标签，给每个都套个胶囊反而显得挤。
 */
@Composable
private fun ScrapeTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * 写入方式：两个短词，点哪个就是哪个（选中那个用主色）。
 *
 * 放在「刮削」小标题右边，和下面那排图标按钮分开 —— 它是"这次怎么写"，
 * 不是又一个刮削入口，混在一排里会分不清谁是动作、谁是设置。
 */
@Composable
private fun WriteModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 详情页刮削入口的短标签（完整名字在 [ScrapeCapability.label]）。 */
private val ScrapeCapability.compactLabel: String
    get() = when (this) {
        ScrapeCapability.COVER -> "专辑图"
        ScrapeCapability.ARTIST_IMAGE -> "艺术家图"
        ScrapeCapability.TRACK_META -> "元信息"
        ScrapeCapability.GENRE -> "曲风"
        ScrapeCapability.LYRICS -> "歌词"
    }

/** 每个刮削入口的图标。 */
private val ScrapeCapability.tileIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        ScrapeCapability.COVER -> Icons.Outlined.Wallpaper
        ScrapeCapability.ARTIST_IMAGE -> Icons.Outlined.Person
        ScrapeCapability.TRACK_META -> Icons.Outlined.Notes
        ScrapeCapability.GENRE -> Icons.Outlined.MusicNote
        ScrapeCapability.LYRICS -> Icons.Outlined.Subject
    }

/** 详情页五个按钮的顺序（组合之后从左到右）。 */
private val DETAIL_SCRAPE_TILES: List<ScrapeCapability> = listOf(
    ScrapeCapability.COVER,
    ScrapeCapability.ARTIST_IMAGE,
    ScrapeCapability.GENRE,
    ScrapeCapability.LYRICS
)

/**
 * 多选栏「修改标签」页。
 *
 * 支持一次选中多首：值相同的字段直接显示该值；多首值不同的字段显示「未修改」，
 * 手动编辑后以编辑值为准。所有改动先攒着，点底部「确认」才一次写入选中的全部文件；
 * 有改动时返回会先问是否保存。
 */
@Composable
fun EditTagsScreen(
    tracks: List<TrackEntity>,
    keepModTime: Boolean,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reloadTicket by remember { mutableStateOf(0) }
    val details by produceState<List<TrackDetail?>?>(null, tracks.map { it.path }, reloadTicket) {
        value = withContext(Dispatchers.IO) {
            tracks.map { track -> runCatching { loadDetail(track) }.getOrNull() }
        }
    }
    var metaEdits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var askSave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    val loaded = details
    val metas = loaded?.mapNotNull { it?.meta }.orEmpty()
    val complete = loaded != null && metas.size == tracks.size && tracks.isNotEmpty()

    /** 所有选中文件该字段是否同值；不同则返回空串，由占位符显示「未修改」。 */
    fun commonValue(fieldKey: String): Pair<String, Boolean> {
        if (!complete) return "" to false
        val values = loaded!!.map { detail ->
            detail?.let { d ->
                editableMetaOf(d.meta, compact = true)
                    .firstOrNull { it.fieldKey == fieldKey }?.value.orEmpty()
            }.orEmpty()
        }.distinct()
        return if (values.size == 1) values.first() to true else "" to false
    }

    val items = if (complete) {
        editableMetaOf(loaded!!.first()!!.meta, compact = true).map { item ->
            val (value, same) = commonValue(item.fieldKey)
            item.copy(
                value = if (same) value else "",
                placeholder = if (same) "—" else "未修改"
            )
        }
    } else {
        emptyList()
    }

    val artistValue = if (!complete) {
        "正在读取…"
    } else {
        metaEdits[FieldKey.ARTIST.name] ?: run {
            val artists = metas.map { it.artist.trim() }.distinct()
            if (artists.size == 1) artists.first().ifBlank { "未知艺术家" } else "未修改"
        }
    }
    val albumValue = if (!complete) {
        ""
    } else {
        val albums = metas.map { it.album.trim() }.distinct()
        if (albums.size == 1) albums.first().ifBlank { "未知专辑" } else "未修改"
    }

    fun requestClose() {
        if (metaEdits.isNotEmpty()) askSave = true else onBack()
    }

    fun saveAll() {
        if (saving) return
        if (metaEdits.isEmpty()) {
            onBack()
            return
        }
        saving = true
        notice = ""
        scope.launch {
            val payload = metaEdits
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                tracks.forEach { track ->
                    val file = File(track.path)
                    if (!file.isFile) {
                        failed++
                        return@forEach
                    }
                    val result = runCatching {
                        com.audiometa.app.media.tag.RawTagWriter.write(
                            file = file,
                            tags = payload,
                            keepModTime = keepModTime
                        )
                    }.getOrNull()
                    if (result != null && result.ok && result.skipped.isEmpty()) {
                        ok++
                    } else {
                        failed++
                    }
                    runCatching {
                        com.audiometa.app.di.ServiceLocator.indexer.refreshTrack(track.id)
                    }
                }
            }
            invalidateCoverCache(context)
            saving = false
            askSave = false
            if (failed == 0) {
                onBack()
            } else {
                notice = "已写入 " + ok + " 首，失败 " + failed + " 首"
                reloadTicket++
            }
        }
    }

    androidx.activity.compose.BackHandler { requestClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(Modifier.fillMaxSize().padding(bottom = 80.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "修改标签",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                if (tracks.size > 1) {
                    Text(
                        tracks.size.toString() + " 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackCover(
                    audioPath = tracks.firstOrNull()?.path.orEmpty(),
                    shape = CircleShape,
                    kind = ArtworkKind.ARTIST,
                    stamp = tracks.firstOrNull()?.updatedAt ?: 0L,
                    refreshKey = reloadTicket,
                    modifier = Modifier.size(56.dp),
                    contentDescription = "艺术家图片"
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        if (tracks.size > 1) "多首歌曲" else {
                            metas.firstOrNull()?.title?.ifBlank { tracks.firstOrNull()?.title.orEmpty() }
                                .orEmpty().ifBlank { "未知标题" }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                    Text(
                        albumValue.ifBlank { "正在读取…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        artistValue,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            HorizontalDivider()

            if (!complete) {
                Text(
                    "正在读取文件…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                ) {
                    DetailSectionTitle("元信息")
                    MetaEditor(
                        items = items,
                        spacious = true,
                        edits = metaEdits,
                        onEdit = { key, value -> metaEdits = metaEdits + (key to value) }
                    )
                    if (notice.isNotBlank()) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { requestClose() },
                    enabled = !saving,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { saveAll() },
                    enabled = !saving && complete,
                    modifier = Modifier.weight(1f)
                ) { Text(if (saving) "保存中…" else "确认") }
            }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text("保存修改？") },
            text = {
                Text("已修改 " + metaEdits.size + " 项，是否保存到选中的 " + tracks.size + " 个文件？")
            },
            confirmButton = {
                TextButton(onClick = { saveAll() }) { Text("保存并返回") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askSave = false
                    onBack()
                }) { Text("不保存") }
            }
        )
    }
}

/** 「组合」一次要写的三类内容：专辑图、艺术家图、曲风。 */
private val COMBINED_SCRAPE_CAPABILITIES: Set<ScrapeCapability> = setOf(
    ScrapeCapability.GENRE,
    ScrapeCapability.LYRICS
)
/**
 * 艺术家图：从下方弹出的窗口。
 *
 * 以前是整页 Dialog，状态栏区域也会跟着变黑；改成底部窗口后，
 * 上方露出一点原来的封面，层级关系更清楚，关闭也更自然。
 */
@Composable
private fun ArtistImageSheet(
    audioPath: String,
    stamp: Long,
    title: String,
    displayName: String,
    refreshKey: Int,
    onPickFromAlbum: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickFromAlbum(uri) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val bytes by produceState<ByteArray?>(null, audioPath, stamp, refreshKey) {
        value = ArtworkTools.loadBytes(audioPath, ArtworkKind.ARTIST)
    }
    // 大图解码放到 IO 线程：直接 remember 里解码会卡住这一帧。
    val bitmap by produceState<android.graphics.Bitmap?>(null, bytes) {
        val data = bytes
        value = if (data == null) null else withContext(Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
        }
    }
    val screenHeightPx = LocalConfiguration.current.screenHeightDp

    BottomSheetScaffold(onDismiss = onDismiss, initialFraction = 0.72f) {
        Column(            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "艺术家图",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            val current = bitmap
            if (current == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (bytes == null) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("这首歌还没有艺术家图")
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "可以先刮削，或直接从相册选一张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            } else {
                // 图片按原图比例塞进剩余空间：不裁切、不留黑边，也不会把下面的按钮顶出窗口。
                // 上一版是 fillMaxWidth + 按屏幕宽算固定高度，长图会直接撑破这个窗口。
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (screenHeightPx * 0.5f).dp)
                            .clickable { }
                    )
                }
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        if (working) return@FilledTonalButton
                        working = true
                        scope.launch {
                            val result = ArtworkTools.saveToAlbum(context, audioPath, ArtworkKind.ARTIST, displayName)
                            status = result.message
                            working = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !working && bytes != null,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存到相册", maxLines = 1)
                }
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        if (working) return@FilledTonalButton
                        status = ""
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !working,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从相册选取", maxLines = 1)
                }
            }
            if (working) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * 歌词弹窗：底部弹出，默认占屏幕 70%。
 *
 * 手势按用户要求重做：
 * - 打开时默认 70%；
 * - **滑动歌词区域不改窗口大小**——歌词列表该滚就滚，窗口纹丝不动；
 * - 滚到歌词顶部后继续往上滑 → 关闭窗口；
 * - 拖顶部拖拽条或标题栏才改窗口大小：上提过 70% 震一下、过 86% 吸附全屏，
 *   松手在中间回 70%，往下拖过 42% 关闭；
 * - 窗口以外的整个屏幕（含状态栏）被暗色遮罩盖住，不再露出封面；
 * - 全屏时顶部自动让开状态栏高度，标题不会被时间挡住。
 *
 * 按歌词类型换展示方式：
 * - 逐字歌词：每个字单独一格，词与词之间留一点间距，能看出断句；
 * - 带翻译的逐字：原文一行 + 译文一行，译文用次要色、字号小一号；
 * - 逐行：一行一句，长句自动换行。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun LyricsSheet(
    title: String,
    lyrics: String,
    translation: String,
    onDismiss: () -> Unit
) {
    val parsed = remember(lyrics, translation) {
        val base = com.audiometa.app.media.lyrics.LyricsParser.parse(lyrics)
        if (base.hasTranslation || translation.isBlank()) {
            base
        } else {
            // 文件里的翻译可能是独立字段，也可能写在 LRC 的 [translation] 段之后。
            base.copy(translation = com.audiometa.app.media.lyrics.LyricsParser.parse(translation).original)
        }
    }
    // 全屏时窗口会顶到屏幕最上沿，这里把状态栏那一段让出来，
    // 保证标题不会被时间和信号图标压住。
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    com.audiometa.app.ui.component.BottomSheetScaffold(
        onDismiss = onDismiss,
        initialFraction = 0.7f,
        hintFraction = 0.7f
    ) { dragHandle ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = statusBarHeight + 4.dp)
                .padding(horizontal = 16.dp)
        ) {
            // 标题栏挂拖动：用户很自然会抓标题往上提，而不是找那根小横杠。
            Column(Modifier.fillMaxWidth().then(dragHandle.run { Modifier.draggable() })) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "歌词",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    val kindLabel = when {
                        parsed.isWordLevel && parsed.hasTranslation -> "逐字 · 含翻译"
                        parsed.isWordLevel -> "逐字"
                        parsed.hasTranslation -> "逐行 · 含翻译"
                        else -> "逐行"
                    }
                    Text(
                        kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            if (!parsed.hasOriginal) {
                Text(
                    "这个文件里没有歌词。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 歌词区域内从下往上滑：列表先自己滚；已经滚到顶了还往上滑，
                    // 就当成"关掉这个窗口"。窗口大小全程不变——滑动歌词不该改窗口，
                    // 这是上一版手感差的主要原因。
                    .pointerInput(parsed.original.size) {
                        var accumulated = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onVerticalDrag = { change, delta ->
                                accumulated += delta
                                val atTop = !listState.canScrollBackward
                                // 已经在顶部还继续往上滑，且累计够一个手势距离 → 关闭。
                                if (atTop && accumulated < -90f) {
                                    change.consume()
                                    onDismiss()
                                }
                            },
                            onDragEnd = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f }
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(if (parsed.isWordLevel) 10.dp else 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(parsed.original) { index, line ->
                    Column(Modifier.fillMaxWidth()) {
                        if (line.wordLevel) {
                            // 逐字：用 FlowRow 让每个字独立排版，字间距略大一点，读起来能断句。
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                line.words.forEach { word ->
                                    Text(
                                        word.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 24.sp
                            )
                        }
                        val translated = parsed.translation.getOrNull(index)?.text
                        if (!translated.isNullOrBlank()) {
                            Text(
                                translated,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_STATUS_BAR_COLOR = 0xFF12141A.toInt()

/** 取封面顶部若干行的平均色，作为状态栏色调。 */
private fun sampleTopColor(audioPath: String): Int? = runCatching {
    val audio = File(audioPath)
    if (!audio.isFile) return@runCatching null
    val source = com.audiometa.app.media.image.ArtworkResolver
        .resolve(audio, ArtworkKind.COVER) ?: return@runCatching null
    val bytes = when (source) {
        is com.audiometa.app.media.image.ArtworkResolver.Source.External ->
            File(source.path).readBytes()
        is com.audiometa.app.media.image.ArtworkResolver.Source.Bytes -> source.data
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        ?: return@runCatching null
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return@runCatching null
    val rows = (height / 12).coerceAtLeast(1)
    val stepX = (width / 16).coerceAtLeast(1)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    var y = 0
    while (y < rows) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
            x += stepX
        }
        y += 2
    }
    bitmap.recycle()
    if (count == 0) null else android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}.getOrNull()

/**
 * 翻译标签的状态。
 *
 * 全中文歌词无需翻译，用中性样式；日文/英文才按"有/无翻译"显示。
 */
private fun translationTagOf(lyrics: String, hasTranslation: Boolean): TranslationTag = when {
    hasTranslation -> TranslationTag.Available
    lyrics.isNotBlank() && com.audiometa.app.media.lyrics.LyricsLanguage.needsTranslation(lyrics).not() ->
        TranslationTag.NotNeeded
    else -> TranslationTag.Absent
}
