package com.audiometa.app.ui.screen.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.domain.usecase.ScrapeService
import com.audiometa.app.ui.component.BottomSheetScaffold
import com.audiometa.app.ui.component.CheckRow
import com.audiometa.app.ui.component.EmptyHint
import com.audiometa.app.ui.component.LocalImage
import com.audiometa.app.ui.component.TrackCover
import com.audiometa.app.ui.component.listSubtitle
import com.audiometa.app.ui.component.listTitle
import com.audiometa.app.ui.theme.cornerShape
/**
 * 音频库。
 *
 * 三个页签（歌曲 / 艺术家 / 曲风）用左右滑切换。
 *
 * 顶栏按用户要求重新设计，尽量紧凑：
 * - 只剩三个页签名 + 两个图标按钮（布局 / 搜索）；
 * - 右上角的「0 / 0 首」、多选按钮、网格开关**都移除了**：
 *   网格和列数改到「布局」弹窗里，艺术家页那两行筛选芯片也一并撤掉了；
 * - 多选改成"长按某一项才开启"，这是全局行为（歌曲列表、艺术家列表、曲风列表都一样）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    /**
     * 通知外层"这一页要全屏"。
     * 搜索页打开时底部标签栏让位，收起时恢复——不然搜索列表只占半屏，
     * 而且搜索页的"左滑返回"手势会被标签栏截断。
     */
    onFullScreenChanged: (Boolean) -> Unit = {},
    /** 外层的返回信号：每 +1 表示按了一次返回。 */
    backSignal: Int = 0,
    /** 告诉外层"这次返回我已经处理掉了"。 */
    onBackHandled: () -> Unit = {},
    /** 告诉外层"我这里没有上一层级了"。 */
    onNeedParent: () -> Boolean = { false },
    /** 刮削窗口是否开着。开着时返回要先关它。 */
    scrapeOpen: Boolean = false,
    onOpenScrape: () -> Unit = {},
    onCloseScrape: () -> Unit = {},
    /**
     * 切到「设置」页。
     *
     * 元信息页在"本地还没有规则"时要给一条能直接去订阅的出路
     * （提示里写"到「设置 - 插件」更新订阅"，用户点了就该到设置页）。
     */
    onOpenSettings: () -> Unit = {},
    vm: LibraryViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    // 曲库是否正在扫：歌曲列表下拉刷新的转圈看它（也在扫描结束后自动收起）。
    val scanRunning by vm.scanRunning.collectAsState()
    var multiSelect by rememberSaveable { mutableStateOf(false) }
    var detailPath by rememberSaveable { mutableStateOf<String?>(null) }
    /** 多选栏「修改标签」打开的多曲标签编辑页。 */
    var editTagsIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    /**
     * 从「元信息」页点进歌曲时带过去的自定义标签上下文。
     *
     * 刻意不放进 rememberSaveable：它是一份展示用的上下文，
     * 进程被杀重建后详情页照常能打开（detailPath 是保存的），
     * 只是退回成普通详情页 —— 比为了它去写 Parcelable 划算。
     */
    var detailFocus by remember { mutableStateOf<MetaTagFocus?>(null) }
    /** 打开详情页。**每次都要显式给 focus**，否则会串上一次的上下文。 */
    fun openDetail(path: String, focus: MetaTagFocus? = null) {
        detailFocus = focus
        detailPath = path
    }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    /** 布局弹窗：从下方弹出，调当前页的网格/列表与列数。 */
    var layoutSheetOpen by rememberSaveable { mutableStateOf(false) }
    /**
     * 现在停在艺术家网格上（还在选人，没点进某位艺术家）。
     *
     * 这一层的多选是**按艺术家**算的：底部数字、全选都是"几位艺术家"。
     * 曲目 id 仍然照旧全部进 selection（刮削逐首写标签要用），
     * 只是不拿它当计数——一位艺术家名下几十首，按曲目报数会让人以为选了几十项。
     */
    val artistGridSelect = state.mode == LibraryMode.ARTIST && state.selectedArtist == null
    val selectedCount = if (artistGridSelect) state.selectedArtists.size else state.selectedIds.size
    val selectTotal = if (artistGridSelect) state.artists.size else state.visibleTracks.size

    // 元信息页的 ViewModel 提升到这一层：顶栏的「扫描」「复制」键要调它，
    // 而顶栏在 pager 外面。放在 pager 里面拿不到。
    // 位置必须在返回路由之前 —— 那段要读 metaState.selected 判断该不该退层。
    val metaVm: MetaTagsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val metaState by metaVm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 只有搜索页要求隐藏底部标签栏（搜索列表被标签栏截断会很难看）。
    // 详情页（播放页）**保留底栏**：封面照样能顶到状态栏下面（那只跟顶部有关），
    // 底栏还在，用户不用先退出详情页再切 Tab。
    LaunchedEffect(searchOpen) {
        onFullScreenChanged(searchOpen)
    }

    /**
     * 返回路由。层级：详情页 → 搜索页 → 布局弹窗 → 多选 → 进了某个艺术家/曲风 → 外层。
     *
     * 只有真的退了一层才回调 onBackHandled；退不动就交给外层
     * （外层会回上一次看的 Tab，最后才是退出应用）。
     */
    var handledSignal by remember { mutableStateOf(backSignal) }
    LaunchedEffect(backSignal) {
        if (backSignal == handledSignal) return@LaunchedEffect
        handledSignal = backSignal
        when {
            // 刮削窗口开着：先关它。它在 MainActivity 那一层，这里只递个信号。
            scrapeOpen -> onCloseScrape()
            detailPath != null -> detailPath = null
            searchOpen -> searchOpen = false
            layoutSheetOpen -> layoutSheetOpen = false
            multiSelect -> {
                multiSelect = false
                vm.clearSelection()
            }
            // 元信息页点进了某个标签的曲目列表：先回标签列表。
            // 这是"返回永远是返回上一层级"的一部分。
            metaState.selected != null -> metaVm.select(null)
            state.insideCollection -> vm.goUp()
            else -> onNeedParent()
        }
        onBackHandled()
    }

    val tabs = LibraryMode.entries
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(state.mode).coerceAtLeast(0)) {
        tabs.size
    }
    // 页签和页码双向同步：点标题栏切页要带动 pager，滑 pager 也要更新状态。
    LaunchedEffect(pagerState.currentPage) {
        val target = tabs[pagerState.currentPage]
        /*
         * 滑动过程中**只切内存里的页签，先不写设置**。
         *
         * 以前这里每次都调 setMode()，它会顺手写一次 DataStore；DataStore 一写就推一次
         * settings 流，接着整份 LibraryUiState 重算、两个页签一起重组 —— 全落在
         * 滑动动画中间。来回滑几个页签时这就是最明显的一处卡顿。
         * 落点由下面的 settledPage 负责记（手停下来之后才写一次）。
         */
        if (target != state.mode) vm.setMode(target, persist = false)
    }
    // 手指停下来、动画收敛之后再记一次页签，重启软件仍然停在这一页。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let { vm.persistMode(it) }
        }
    }
    LaunchedEffect(state.mode) {
        val index = tabs.indexOf(state.mode)
        // 用 scrollToPage 而不是 animateScrollToPage。
        //
        // 原因：目标页离得远时（歌曲 → 元信息要跨 3 页），animateScrollToPage 会把
        // 中间每一页都滑过一遍，也就都要组合一次——其中元信息页一组合就去读 40 个
        // 音频文件的标签，正好卡在切页动画中间，表现就是"切页掉帧"。
        // 点标题栏本来就是"跳过去"，瞬时切换反而更符合预期；手势滑动仍然有原生动画。
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }

    // 详情页：点歌曲进入，显示封面与完整元信息。
    val detailTrack = detailPath?.let { path -> state.tracks.firstOrNull { it.path == path } }
    if (detailTrack != null) {
        SongDetailScreen(
            track = detailTrack,
            cornerStyle = state.settings.cornerStyle,
            skipSilentIntro = state.settings.skipSilentIntro,
            // 所有写入都遵守设置里的「保持修改时间」：手动改元信息也一样。
            keepModTime = state.settings.keepModTime,
            onBack = {
                detailPath = null
                detailFocus = null
            },
            metaFocus = detailFocus,
            onScrape = { caps ->
                ScrapeService.start(
                    com.audiometa.app.di.ServiceLocator.appContext,
                    listOf(detailTrack.id),
                    caps
                )
            }
        )
        return
    }

    // 修改标签页：和多选栏同层，打开时盖住曲库（不进入播放详情）。
    val editTagsTracks = editTagsIds.mapNotNull { id -> state.tracks.firstOrNull { it.id == id } }
    if (editTagsTracks.isNotEmpty()) {
        EditTagsScreen(
            tracks = editTagsTracks,
            keepModTime = state.settings.keepModTime,
            onBack = { editTagsIds = emptyList() }
        )
        return
    }

    // 搜索页：从右侧滑入的整页覆盖。
    AnimatedVisibility(
        visible = searchOpen,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        SearchScreen(
            onOpenTrack = { path ->
                searchOpen = false
                openDetail(path)
            },
            onClose = { searchOpen = false }
        )
    }
    if (searchOpen) return

    // 返回是「上一层级」，逐层退出：
    // 详情页 → 搜索页 → 多选 → 进了某个艺术家/曲风 → 回上一层。
    androidx.activity.compose.BackHandler(
        enabled = multiSelect || state.insideCollection
    ) {
        when {
            multiSelect -> {
                multiSelect = false
                vm.clearSelection()
            }
            else -> vm.goUp()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        LibraryTopBar(
            mode = state.mode,
            onSelectTab = { target ->
                if (target != state.mode) vm.setMode(target)
            },
            onLayout = { layoutSheetOpen = true },
            onScrape = onOpenScrape,
            onSearch = { searchOpen = true },
            // 元信息页不显示刮削和布局切换：它是标签聚合视图，
            // 既没有可刮削的选中项，也没有列表/网格可切。
            showActions = state.mode != LibraryMode.META,
            // 流派页只留「布局」，右上角不再出现刮削入口（用户要求）。
            // 理由：流派是聚合出来的标签，页面上没有"要刮这一项"的语义，
            // 刮削入口在那里只会占位置、还容易被误点。
            showScrape = state.mode != LibraryMode.META && state.mode != LibraryMode.GENRE,
            // 元信息页在搜索键左边多两个键：扫描（带转圈）和复制（仅调试模式）。
            metaScanning = metaState.loading,
            metaCanCopy = metaState.tags.isNotEmpty(),
            debugMode = state.settings.debugMode,
            onRescanMeta = { metaVm.rescan() },
            onSendMeta = {
                // 用户要求：开启「发送报告」后，这里（复制键左边）多一个发送键，
                // 点它调系统邮件客户端给关于页那个邮箱**只发未知标签** ——
                // 别的分组都是规则已经认识的，没必要跟着发出去。
                scope.launch {
                    val report = com.audiometa.app.domain.report.MetaReport.markdown(unknownOnly = true)
                    if (report.isBlank()) {
                        metaVm.noticeShow("未知标签里没有内容，不用发")
                    } else {
                        val ok = com.audiometa.app.util.AboutIntents.sendEmail(
                            context,
                            subject = "AudioMeta 未知标签",
                            body = report
                        )
                        metaVm.noticeShow(
                            if (ok) "已打开邮件应用，收件人 " + com.audiometa.app.util.AboutIntents.EMAIL
                            else "没有可用的邮件应用，请复制后手动发送"
                        )
                    }
                }
            },
            onCopyMeta = {
                // 报告统一成 Markdown + 按来源分组（和「设置 - 调试 - 发送报告」同一份生成器）：
                // 以前这里是另一套纯文本格式，两处复制出来的东西长得不一样。
                scope.launch {
                    val report = com.audiometa.app.domain.report.MetaReport.markdown(unknownOnly = false)
                    if (report.isBlank()) {
                        metaVm.noticeShow("还没有可复制的标签")
                    } else {
                        com.audiometa.app.ui.component.copyToClipboard(context, "元信息报告", report)
                        metaVm.noticeShow("已复制报告（" + report.lineSequence().count() + " 行）")
                    }
                }
            }
        )

        if (!state.atRoot) {
            BreadcrumbRow(state.breadcrumbs.map { it.second }) { index ->
                val target = state.breadcrumbs.getOrNull(index)?.first
                when {
                    state.selectedGenre != null -> vm.openGenre(null)
                    state.selectedArtist != null -> vm.openArtist(null)
                    else -> vm.openFolder(target)
                }
            }
        }

        // 内容从顶栏下方就开始排，不居中。
        // 之前歌曲少的时候会"浮"在屏幕中间，就是因为没有显式指定顶部对齐，
        // 列表在 pager 给的约束里按居中摆放了。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopStart
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 页签内容自己管滚动，pager 不再抢手势。
                userScrollEnabled = true,
                verticalAlignment = Alignment.Top
            ) { page ->
                when (tabs[page]) {
                    LibraryMode.SONGS -> SongsPane(
                        tracks = state.visibleTracks,
                        loading = state.loading,
                        selectedIds = state.selectedIds,
                        multiSelect = multiSelect,
                        asGrid = state.tabLayout.asGrid,
                        // 每行个数是全局设置，三页共用。
                        refreshing = scanRunning,
                        onRescan = { vm.rescan() },
                        gridColumns = state.gridColumns,
                        cornerStyle = state.settings.cornerStyle,
                        onToggle = vm::toggle,
                        onLongPress = { id ->
                            multiSelect = true
                            vm.selectOnly(id)
                        },
                        onOpen = { openDetail(it) }
                    )
                    LibraryMode.ARTIST -> ArtistsPane(
                        state = state,
                        multiSelect = multiSelect,
                        onOpen = { vm.openArtist(it) },
                        onToggle = vm::toggle,
                        onLongPress = { id ->
                            multiSelect = true
                            vm.selectOnly(id)
                        },
                        // 长按艺术家卡片 = 把这位艺术家的全部曲目选进多选。
                        onLongPressArtist = { name ->
                            vm.selectArtist(name)
                            multiSelect = true
                        },
                        // 多选中点卡片不再跳进他的曲目列表，而是切换这位艺术家的选中态。
                        onToggleArtist = vm::toggleArtist,
                        onOpenTrack = { openDetail(it) }
                    )
                    LibraryMode.GENRE -> GenresPane(
                        state = state,
                        multiSelect = multiSelect,
                        onOpen = { vm.openGenre(it) },
                        onToggle = vm::toggle,
                        onLongPress = { id ->
                            multiSelect = true
                            vm.selectOnly(id)
                        },
                        onOpenTrack = { openDetail(it) }
                    )
                    // 元信息页签自带 ViewModel：读标签是文件 IO，节奏和曲库索引不一样。
                    // 点进去带的是"这首歌在这个来源下的自定义标签"，详情页会把
                    // 元信息区域换成键名 + 完整值 + 说明。
                    LibraryMode.META -> MetaTagsPane(
                        debugMode = state.settings.debugMode,
                        onOpenTrack = { path, focus -> openDetail(path, focus) },
                        cornerStyle = state.settings.cornerStyle,
                        onOpenSettings = onOpenSettings,
                        vm = metaVm
                    )
                }
            }
        }

        // 多选操作条只在真的选中了东西时出现——没有右上角按钮，它不会平白占一行。
        AnimatedVisibility(
            visible = multiSelect && selectedCount > 0,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            SelectActionBar(
                selectedCount = selectedCount,
                total = selectTotal,
                // 艺术家网格上的全选/已选都按艺术家算，别的层级照旧按曲目算。
                allSelected = if (artistGridSelect) {
                    state.artists.isNotEmpty() && state.artists.all { it.name in state.selectedArtists }
                } else {
                    state.visibleTracks.isNotEmpty() &&
                        state.visibleTracks.all { it.id in state.selectedIds }
                },
                onSelectAll = {
                    if (artistGridSelect) vm.selectAllArtists() else vm.selectVisible(true)
                },
                onExit = {
                    multiSelect = false
                    vm.clearSelection()
                },
                onEditTags = {
                    editTagsIds = state.selectedIds.toList()
                },
                editTagsEnabled = !artistGridSelect && state.selectedIds.isNotEmpty(),
                onScrape = {
                    onOpenScrape()
                }
            )
        }
    }

    // 布局弹窗：每页独立配置 + 排序。每行个数是全局的，独立传进来。
    if (layoutSheetOpen) {
        // 列数反查回预设：弹窗要按列数高亮当前项。旧版本存过 6 列（已删除的"紧凑"）
        // 时反查不到，退回标准，和网格实际用的列数保持一致。
        val preset = GridPreset.entries.firstOrNull { it.columns == state.gridColumns }
            ?: GridPreset.STANDARD
        LayoutSheet(
            mode = state.mode,
            layout = state.tabLayout,
            preset = preset,
            artistFilter = state.artistFilter,
            onLayoutChange = { vm.updateTabLayout(it) },
            onPresetChange = { vm.setGridPreset(it) },
            onArtistFilter = { vm.setArtistFilter(it) },
            artistListMode = state.artistListMode,
            onArtistListMode = { vm.setArtistListMode(it) },
            onDismiss = { layoutSheetOpen = false }
        )
    }
}

/**
 * 顶栏。
 *
 * 左侧是四个页签名（当前那个高亮，点了可以跳过去），右侧按页签给不同的按钮：
 *
 * | 页签 | 右侧按钮 |
 * |---|---|
 * | 歌曲 / 艺术家 / 曲风 | 刮削、布局、搜索 |
 * | 元信息 | 扫描、搜索（调试模式下多一个复制） |
 *
 * 「布局」按钮的三页各有独立配置，见 [LayoutSheet]。
 */
@Composable
private fun LibraryTopBar(
    mode: LibraryMode,
    onSelectTab: (LibraryMode) -> Unit,
    onLayout: () -> Unit,
    onScrape: () -> Unit,
    onSearch: () -> Unit,
    /** 是否显示刮削与布局按钮。元信息页不显示。 */
    showActions: Boolean = true,
    /**
     * 是否显示右上角的刮削按钮。
     *
     * 和 [showActions] 分开是因为流派页要"留布局、去掉刮削" —— 一个布尔量
     * 表达不了"两个按钮各自开关"。
     */
    showScrape: Boolean = true,
    /** 元信息页：是否正在扫描。 */
    metaScanning: Boolean = false,
    metaCanCopy: Boolean = false,
    debugMode: Boolean = false,
    onRescanMeta: () -> Unit = {},
    /** 开启「发送报告」后的发送键：只把未知标签发到邮箱。 */
    onSendMeta: () -> Unit = {},
    onCopyMeta: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryMode.entries.forEach { entry ->
            val active = entry == mode
            Text(
                entry.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectTab(entry) }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (showActions) {
            // 「刮削」按钮：点开从底部上拉出刮削窗口。流派页不画它（见 showScrape）。
            if (showScrape) {
                IconButton(onClick = onScrape, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.TravelExplore,
                        contentDescription = "刮削",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 「布局」按钮：点开从下方弹出窗口，调当前页的形态、列数和排序。
            IconButton(onClick = onLayout, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Outlined.DashboardCustomize,
                    contentDescription = "调整布局",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (mode == LibraryMode.META) {
            // 元信息页：扫描键放在搜索键左边（用户要求）。
            // 开启「发送报告」后，左边再多两个键：**发送**（只发未知标签到邮箱）和复制。
            if (debugMode) {
                IconButton(
                    onClick = onSendMeta,
                    enabled = metaCanCopy,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.MailOutline,
                        contentDescription = "发送未知标签到邮箱",
                        modifier = Modifier.size(19.dp),
                        tint = if (metaCanCopy) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
                IconButton(
                    onClick = onCopyMeta,
                    enabled = metaCanCopy,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制报告",
                        modifier = Modifier.size(19.dp),
                        tint = if (metaCanCopy) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
            }
            IconButton(
                onClick = onRescanMeta,
                enabled = !metaScanning,
                modifier = Modifier.size(34.dp)
            ) {
                if (metaScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "重新扫描",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(onClick = onSearch, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "搜索",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 布局弹窗。
 *
 * 调的是**当前页签**的布局，每页各存一份、各自持久化（重启保持上次状态）。
 *
 * 三页的内容（用户逐条指定）：
 *
 * ```
 * 歌曲    显示方式（列表 / 网格）→ 每行个数 → 排序（六项）→ 顺序
 * 艺术家  每行个数 → 排序（数量 / 艺术家名）→ 顺序
 * 曲风    每行个数 → 排序（数量 / 名字）→ 顺序
 * ```
 *
 * 几个刻意的决定：
 *
 * 1. **「每行个数」永远在，只是列表形态下变灰**。以前它是"切到网格才多出来"的，
 *    一多出来底下所有选项就整体下移，点一下布局整块都在跳 —— 用户明确报过这个
 *    观感问题。选项位置固定，切换只改变选中态，不再重排。
 * 2. **每行个数只有标准和宽松**（用户要求删掉了 6 列的"紧凑"），
 *    而且**全局生效**：三页共用一份，改一页就是三页都改。
 * 3. **艺术家页不再有「显示方式」和「头像形状」**：默认就是网格、方形。
 * 4. **排序按页给不同的集合**：艺术家页只剩艺术家名和数量，
 *    曲风页只剩名字和数量（见 [sortOptionsOf]）。
 * 5. **去掉了「单页 / 应用到全局」两个按钮**。默认就是每页独立，不需要问。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutSheet(
    mode: LibraryMode,
    layout: TabLayout,
    preset: GridPreset,
    artistFilter: ArtistFilter,
    /** 艺术家页的列表模式（首位艺术家 / 多位艺术家歌曲）。 */
    artistListMode: ArtistListMode,
    onLayoutChange: (TabLayout) -> Unit,
    onPresetChange: (GridPreset) -> Unit,
    onArtistFilter: (ArtistFilter) -> Unit,
    onArtistListMode: (ArtistListMode) -> Unit,
    onDismiss: () -> Unit
) {
    BottomSheetScaffold(onDismiss = onDismiss, initialFraction = 0.5f) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text("布局 · " + mode.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // ---- 显示方式（只有歌曲页有） ----
            // 艺术家页按用户要求去掉了这一项：默认就是网格。
            // 曲风页一直没有：曲风是一堆短标签，列表形态没意义。
            if (mode == LibraryMode.SONGS) {
                LayoutGroup("显示方式") {
                    LayoutOption("列表", selected = !layout.asGrid) {
                        onLayoutChange(layout.copy(asGrid = false))
                    }
                    LayoutOption("网格", selected = layout.asGrid) {
                        onLayoutChange(layout.copy(asGrid = true))
                    }
                }
            }

            // ---- 艺术家显示范围（只有艺术家页有） ----
            // 用户要求把「有图 / 无图」放进布局窗口：
            // 切到「无图」时只列出还没有艺术家图片的艺术家，
            // 一眼就能看出"还有哪些人的头像没刮到"。
            if (mode == LibraryMode.ARTIST) {
                LayoutGroup("显示") {
                    ArtistFilter.entries.forEach { option ->
                        LayoutOption(option.label, selected = option == artistFilter) {
                            onArtistFilter(option)
                        }
                    }
                }
            }

            // 艺术家列表模式（用户要求放在右上角布局窗口里）：
            // 默认「首位艺术家」—— 一首歌只出现在第一位艺术家名下；
            // 切到「多位艺术家歌曲」只看合作曲。
            if (mode == LibraryMode.ARTIST) {
                LayoutGroup("艺术家列表") {
                    ArtistListMode.entries.forEach { option ->
                        LayoutOption(option.label, selected = option == artistListMode) {
                            onArtistListMode(option)
                        }
                    }
                }
            }

            // ---- 每行个数（全局，位置固定） ----
            // 列表形态下这一行**照旧存在**，只把选项变灰。
            // 这样切列表/网格时它不会凭空出现或消失，下面的「排序」「顺序」不动。
            val columnsEnabled = mode != LibraryMode.SONGS || layout.asGrid
            LayoutGroup("每行个数") {
                GridPreset.entries.forEach { option ->
                    LayoutOption(
                        option.label + "（" + option.columns + "）",
                        selected = option == preset,
                        enabled = columnsEnabled
                    ) { onPresetChange(option) }
                }
            }

            // ---- 排序（每页一套字段） ----
            val currentSort = effectiveSortKey(mode, layout)
            LayoutGroup("排序") {
                sortOptionsOf(mode).forEach { (key, label) ->
                    LayoutOption(label, selected = currentSort == key) {
                        onLayoutChange(layout.copy(sortKey = key))
                    }
                }
            }

            // ---- 顺序 ----
            LayoutGroup("顺序") {
                LayoutOption("正序", selected = !layout.sortDescending) {
                    onLayoutChange(layout.copy(sortDescending = false))
                }
                LayoutOption("倒序", selected = layout.sortDescending) {
                    onLayoutChange(layout.copy(sortDescending = true))
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 布局弹窗里的一个小节：一行标签 + 一行选项。
 *
 * 选项**固定顺序、自动换行**。用 FlowRow 而不是 Row：
 * 歌曲页的排序有六个字段，窄屏上一行放不下会被裁掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutGroup(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(9.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}

/** 布局弹窗里的一个选项按钮。 */
@Composable
private fun LayoutOption(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1
        )
    }
}

// ---------------- 歌曲 ----------------

/**
 * 曲库数据首次加载中的占位。
 *
 * 刻意不用空态文案：Room Flow 第一次发射前的空列表是「还没加载」，
 * 不是「曲库为空」，两者必须区分，否则启动时会闪一下「请扫描」。
 */
@Composable
private fun LibraryLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SongsPane(
    tracks: List<TrackEntity>,
    loading: Boolean,
    selectedIds: Set<Long>,
    multiSelect: Boolean,
    asGrid: Boolean,
    gridColumns: Int,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    /** 曲库扫描是否在跑：下拉刷新后顶部那个刷新图标要转到扫完为止。 */
    refreshing: Boolean = false,
    /** 下拉到底松手：重新扫描整个曲库（用户要求"手指从上往下滑动就是重新扫描"）。 */
    onRescan: () -> Unit = {},
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onOpen: (String) -> Unit
) {
    if (loading) {
        LibraryLoading()
        return
    }
    /*
     * 下拉刷新包住整页（包括"曲库还是空的"那一屏）：
     * 用户第一次进来曲库是空的时候，也需要能直接下拉扫一遍，
     * 而不是必须先跑一趟设置页。
     */
    com.audiometa.app.ui.component.PullToRefreshBox(
        refreshing = refreshing,
        onRefresh = onRescan,
        modifier = Modifier.fillMaxSize()
    ) {
        if (tracks.isEmpty()) {
            EmptyHint("曲库还是空的。往下拉可以重新扫描，也可以到「设置 - 音乐库」里添加目录。")
            return@PullToRefreshBox
        }
        if (!asGrid) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    TrackRow(
                        track = track,
                        checked = track.id in selectedIds,
                        showCheckbox = multiSelect,
                        cornerStyle = cornerStyle,
                        onToggle = { onToggle(track.id) },
                        onLongPress = { onLongPress(track.id) },
                        onOpen = { onOpen(track.path) }
                    )
                }
            }
            return@PullToRefreshBox
        }
        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Fixed(gridColumns),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                TrackCell(
                    track = track,
                    checked = track.id in selectedIds,
                    showCheckbox = multiSelect,
                    cornerStyle = cornerStyle,
                    onToggle = { onToggle(track.id) },
                    onLongPress = { onLongPress(track.id) },
                    onOpen = { onOpen(track.path) }
                )
            }
        }
    }
}

// ---------------- 艺术家 ----------------

@Composable
private fun ArtistsPane(
    state: LibraryUiState,
    multiSelect: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onLongPressArtist: (String) -> Unit,
    /** 多选中点艺术家卡片：切换选中态，不进他的曲目列表。 */
    onToggleArtist: (String) -> Unit,
    onOpenTrack: (String) -> Unit
) {
    val artist = state.selectedArtist
    if (state.loading) {
        LibraryLoading()
        return
    }
    if (artist == null) {
        // 「显示 全部/有图/无图」那一行按用户要求撤掉了。
        // 艺术家页固定是**网格 + 方形头像**（用户要求：布局弹窗里不再给显示方式和形状），
        // 每行个数用全局设置。
        ArtistGrid(
            artists = state.artists,
            selectedArtists = state.selectedArtists,
            multiSelect = multiSelect,
            columns = state.gridColumns,
            circle = false,
            cornerStyle = state.settings.cornerStyle,
            onOpen = onOpen,
            onLongPress = onLongPressArtist,
            onToggleSelect = onToggleArtist
        )
        return
    }
    // 进到某位艺术家：列出他的曲目，左侧显示艺术家图（与歌曲列表的封面区分开）。
    TrackList(
        tracks = state.visibleTracks,
        selectedIds = state.selectedIds,
        multiSelect = multiSelect,
        cornerStyle = state.settings.cornerStyle,
        imageKind = com.audiometa.app.media.image.ArtworkKind.ARTIST,
        emptyHint = "这位艺术家名下没有曲目。",
        onToggle = onToggle,
        onLongPress = onLongPress,
        onOpen = onOpenTrack
    )
}

// ---------------- 曲风 ----------------

@Composable
private fun GenresPane(
    state: LibraryUiState,
    multiSelect: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onOpenTrack: (String) -> Unit
) {
    val genre = state.selectedGenre
    if (state.loading) {
        LibraryLoading()
        return
    }
    if (genre == null) {
        // 曲风页固定网格。每行个数用**全局**设置 ——
        // 以前这里读的是遗留的 gridPreset 单项，和布局弹窗里改的
        // tabLayout.columns 不是同一个值，所以"切了没反应"（用户报的 bug）。
        // 这一层**不做多选**（用户要求）：曲风是一堆短标签，
        // 长按就进多选会跟"点一下看这个流派的歌"抢同一个手势。
        GenreGrid(
            genres = state.genres,
            columns = state.gridColumns,
            cornerStyle = state.settings.cornerStyle,
            onOpen = onOpen
        )
        return
    }
    TrackList(
        tracks = state.visibleTracks,
        selectedIds = state.selectedIds,
        multiSelect = multiSelect,
        cornerStyle = state.settings.cornerStyle,
        emptyHint = "这个流派下没有曲目。",
        onToggle = onToggle,
        onLongPress = onLongPress,
        onOpen = onOpenTrack
    )
}

/**
 * 曲风格子。
 *
 * 用代表曲目的封面当配图（没有封面才显示占位图）——
 * 曲风没有自己的图片资源，用封面比清一色的音符图标有辨识度。
 * 右上角标出歌曲数：**一首歌有多个曲风时会被计进多格**，
 * 所以这里的数字是"带这个曲风的歌曲数"，不是曲库总数的一部分。
 *
 * 曲风页固定网格形态：曲风是一堆短标签，列表形态没意义，
 * 布局弹窗里也不再给「显示方式」这一项。
 *
 * 这一层**没有长按多选**（用户要求）：曲风格子只负责"点进去看这个流派的歌"。
 */
@Composable
private fun GenreGrid(
    genres: List<GenreEntry>,
    columns: Int,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onOpen: (String) -> Unit
) {
    if (genres.isEmpty()) {
        EmptyHint("还没有流派标签。扫一遍曲库，或刮削时补上流派再回来看看。")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(genres, key = { it.name }, contentType = { "genre" }) { entry ->
            Column(
                modifier = Modifier.clickable { onOpen(entry.name) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (entry.sampleTrackPath.isNullOrBlank()) {
                    TrackCover(
                        audioPath = null,
                        shape = cornerShape(cornerStyle, 12.dp),
                        kind = com.audiometa.app.media.image.ArtworkKind.COVER,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentDescription = entry.name
                    )
                } else {
                    TrackCover(
                        audioPath = entry.sampleTrackPath,
                        shape = cornerShape(cornerStyle, 12.dp),
                        kind = com.audiometa.app.media.image.ArtworkKind.COVER,
                        stamp = entry.sampleStamp,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        warmDiskCache = false,
                        contentDescription = entry.name
                    )
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    entry.trackCount.toString() + " 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistGrid(
    artists: List<ArtistEntry>,
    selectedArtists: Set<String>,
    multiSelect: Boolean,
    columns: Int,
    circle: Boolean,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleSelect: (String) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyHint("还没有艺术家信息。扫一遍曲库，或检查文件的艺术家标签。")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(artists, key = { it.name }, contentType = { "artist" }) { artist ->
            val selected = artist.name in selectedArtists
            Column(
                // 点开这位艺术家；长按把这位艺术家的全部曲目选进多选（全局一致的长按行为）。
                //
                // 已经在多选里时，点击改成**切换这位艺术家的选中态**：
                // 这时用户要的是"挑几位一起去刮削"，点一下就跳进曲目列表会把
                // 刚选好的一次性全丢掉（而且跳过去之后底下那条计数条也没意义了）。
                modifier = Modifier.combinedClickable(
                    onClick = { if (multiSelect) onToggleSelect(artist.name) else onOpen(artist.name) },
                    onLongClick = { onLongPress(artist.name) }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    // 边角样式跟随设置（圆形/圆角/方形）。
                    // 艺术家图优先读外置文件，其次读「真的有内嵌艺术家图的那首」，
                    // 都不行才是占位图：绝不拿歌曲封面顶替艺术家图。
                    if (!artist.imagePath.isNullOrBlank()) {
                        LocalImage(
                            path = artist.imagePath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(if (circle) CircleShape else cornerShape(cornerStyle, 12.dp)),
                            contentDescription = artist.name
                        )
                    } else {
                        val hasEmbedded = !artist.embeddedTrackPath.isNullOrBlank()
                        TrackCover(
                            // 有内嵌图就用带图的那首；没有才退回代表曲目（必然显示占位图）。
                            audioPath = if (hasEmbedded) artist.embeddedTrackPath else artist.sampleTrackPath,
                            shape = if (circle) CircleShape else cornerShape(cornerStyle, 12.dp),
                            kind = com.audiometa.app.media.image.ArtworkKind.ARTIST,
                            stamp = if (hasEmbedded) artist.embeddedStamp else artist.sampleStamp,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            warmDiskCache = false,
                            contentDescription = artist.name
                        )
                    }
                    // 底部只报"选了几位"，具体是哪几位得能在这张网格上看出来。
                    if (multiSelect) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleSelect(artist.name) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
                Text(
                    artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    artist.trackCount.toString() + " 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------- 公共 ----------------

/**
 * 底部多选操作条。
 *
 * 多了个「退出多选」：因为入口（右上角按钮）按用户要求撤掉了，
 * 没有这个按钮的话只能靠系统返回键退出，不够直观。
 */
@Composable
private fun SelectActionBar(
    selectedCount: Int,
    total: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    onEditTags: () -> Unit,
    editTagsEnabled: Boolean,
    onScrape: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选 " + selectedCount + "/" + total,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            // 单行紧凑操作：不再拆成两行，底栏占的高度和普通列表项接近。
            TextButton(
                onClick = onSelectAll,
                enabled = !allSelected && total > 0,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) { Text("全选", maxLines = 1) }
            TextButton(
                onClick = onEditTags,
                enabled = editTagsEnabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) { Text("修改标签", maxLines = 1) }
            TextButton(
                onClick = onScrape,
                enabled = selectedCount > 0,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) { Text("刮削", maxLines = 1) }
            TextButton(
                onClick = onExit,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) { Text("退出", maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(
    tracks: List<TrackEntity>,
    selectedIds: Set<Long>,
    multiSelect: Boolean,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    imageKind: com.audiometa.app.media.image.ArtworkKind =
        com.audiometa.app.media.image.ArtworkKind.COVER,
    emptyHint: String,
    onToggle: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onOpen: (String) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyHint(emptyHint)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tracks, key = { it.id }, contentType = { "track" }) { track ->
            TrackRow(
                track = track,
                checked = track.id in selectedIds,
                showCheckbox = multiSelect,
                cornerStyle = cornerStyle,
                imageKind = imageKind,
                onToggle = { onToggle(track.id) },
                onLongPress = { onLongPress(track.id) },
                onOpen = { onOpen(track.path) }
            )
        }
    }
}

@Composable
private fun BreadcrumbRow(labels: List<String>, onJump: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) {
                Text(
                    "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val last = index == labels.lastIndex
            androidx.compose.material3.TextButton(
                onClick = { if (!last) onJump(index) },
                enabled = !last,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 列表里的一行曲目。
 *
 * 点击 = 进详情（或多选中切换勾选）；**长按 = 进入多选并选中这一项**。
 * 勾选框只在进入多选后才出现，平时列表保持干净。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackEntity,
    checked: Boolean,
    showCheckbox: Boolean,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    imageKind: com.audiometa.app.media.image.ArtworkKind =
        com.audiometa.app.media.image.ArtworkKind.COVER,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit
) {
    val cardColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "trackSelectColor"
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(
                onClick = { if (showCheckbox) onToggle() else onOpen() },
                onLongClick = { if (showCheckbox) onToggle() else onLongPress() }
            )
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = showCheckbox) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
            }
            TrackCover(
                audioPath = track.path,
                shape = cornerShape(cornerStyle, 8.dp),
                kind = imageKind,
                stamp = track.updatedAt,
                modifier = Modifier.size(46.dp),
                warmDiskCache = false,
                contentDescription = track.title
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    // 文件里没有内嵌标题标签就显示完整文件名（用户要求，全列表统一）。
                    track.listTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal
                )
                // 副标题：艺术家 · 专辑。两者都没有（无内嵌标签）时整行不画，
                // 不再显示"未知艺术家 · 未知专辑"这种占位字，避免和文件名挤在一起。
                Text(
                    listOfNotNull(
                        track.listSubtitle.takeIf { it.isNotBlank() },
                        track.primaryGenre.takeIf { it.isNotBlank() },
                        track.format.uppercase()
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 网格里的一格曲目。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCell(
    track: TrackEntity,
    checked: Boolean,
    showCheckbox: Boolean,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { if (showCheckbox) onToggle() else onOpen() },
            onLongClick = { if (showCheckbox) onToggle() else onLongPress() }
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            TrackCover(
                audioPath = track.path,
                shape = cornerShape(cornerStyle, 10.dp),
                kind = com.audiometa.app.media.image.ArtworkKind.COVER,
                stamp = track.updatedAt,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                warmDiskCache = false,
                contentDescription = track.title
            )
            if (showCheckbox) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        Text(
            // 网格里只有标题一行，同样走"无内嵌标签就显示文件名"。
            track.listTitle,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}