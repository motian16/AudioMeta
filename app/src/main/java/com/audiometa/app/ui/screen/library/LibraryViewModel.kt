package com.audiometa.app.ui.screen.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音频库的三个页签。
 *
 * 顺序就是左右滑动的顺序：歌曲 → 艺术家 → 曲风。
 * 曲风和艺术家看起来像，但**一首歌可以有多个曲风**，
 * 所以聚合方式不同：艺术家按唯一值分组，曲风要把每首歌的曲风列表拆开再分别归组。
 */
enum class LibraryMode(val label: String) {
    SONGS("歌曲"),
    ARTIST("艺术家"),
    GENRE("流派"),
    /**
     * 元信息：按"未知/自定义标签"浏览曲库。
     *
     * 识别规则见 [com.audiometa.app.domain.tagrules.TagRules]——
     * 平台私有标记（酷狗 kghash、QQ QMQuality、网易云 NCM 残留…）
     * 和转码工具签名都会在这里聚合出来。
     */
    META("元信息")
}

/**
 * 艺术家页的列表模式（用户在右上角「布局」窗口里切换）。
 *
 * - [PRIMARY]：**默认**。一首歌只出现在它的**首位艺术家**名下
 *   （`A/B`、`A & B`、`A，B` 这类合作曲只算 A）。
 * - [MULTI]：反过来，只列出**有多位艺术家**的歌曲，按完整的艺术家串分组。
 */
enum class ArtistListMode(val label: String) {
    PRIMARY("首位艺术家"),
    MULTI("多位艺术家歌曲")
}

enum class ArtistFilter(val label: String) {
    ALL("全部"),
    WITH_IMAGE("有图"),
    WITHOUT_IMAGE("无图")
}

/**
 * 每行个数。
 *
 * 用户明确要求**只保留标准和宽松**（原来的"紧凑"6 列已删除），
 * 而且这是**全局**设置：歌曲 / 艺术家 / 曲风三页共用同一个列数，
 * 不再每页各存一份。列数本身不是"页签的布局偏好"，
 * 而是"屏幕上一行放几个"的观感选择。
 */
enum class GridPreset(val label: String, val columns: Int) {
    STANDARD("标准", 4),
    COMFORTABLE("宽松", 3);

    companion object {
        fun fromName(name: String?): GridPreset =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/**
 * 歌曲页的排序字段。
 *
 * 用户指定的六种：**修改时间**、文件名、艺术家名、年份、音频长短、大小。
 *
 * 「创建时间」这一项按用户要求**整体移除**了 —— 安卓上根本拿不到可靠的
 * 文件创建时间（很多文件系统不记录，复制一次就没了），软件以前拿"入库时间"
 * 代替，但那个值对用户没有意义：用户要找的是"我最近改过的那首歌"。
 * 所以现在按**文件修改时间**（`lastModified`）排，和文件管理器里看到的一致。
 */
enum class SortField(val label: String) {
    MODIFIED("修改时间"),
    FILE_NAME("文件名"),
    ARTIST("艺术家名"),
    YEAR("年份"),
    DURATION("音频长短"),
    SIZE("大小");

    companion object {
        fun fromName(name: String?): SortField =
            entries.firstOrNull { it.name == name } ?: MODIFIED
    }
}

/**
 * 艺术家页的排序字段。
 *
 * 用户要求**移除除艺术家名以外的所有排序规则**，并加入「数量」：
 * 按每位艺术家名下的歌曲数排列。默认就是数量。
 */
enum class ArtistSort(val label: String) {
    COUNT("数量"),
    NAME("艺术家名");

    companion object {
        fun fromName(name: String?): ArtistSort =
            entries.firstOrNull { it.name == name } ?: COUNT
    }
}

/** 曲风页的排序字段。规则与艺术家页一致：数量 / 名字，默认数量。 */
enum class GenreSort(val label: String) {
    COUNT("数量"),
    NAME("名字");

    companion object {
        fun fromName(name: String?): GenreSort =
            entries.firstOrNull { it.name == name } ?: COUNT
    }
}

/**
 * 一个页签的布局配置。
 *
 * 每页各存一份、各自持久化（用户明确要求"每页状态独立且持久化存储，
 * 重启软件保持上次设置的状态"）。
 *
 * 注意这里**没有列数**：每行个数是全局的（见 [GridPreset]）。
 * 排序字段存枚举名，三种页签各有自己的枚举，所以统一按字符串存，
 * 读的时候各页用各自的 [fromName] 解析 —— 旧版本存的
 * "ADDED"/"ARTIST" 这类歌曲页字段在艺术家/曲风页解析不到，
 * 会自动退回该页的默认值（数量），不会串味。
 */
data class TabLayout(
    /**
     * 歌曲页：列表 or 网格。艺术家页和曲风页恒为网格，
     * 这个字段对它们没有意义（界面也不再给切换入口）。
     */
    val asGrid: Boolean = true,
    /** 排序字段的枚举名；空字符串表示用该页的默认排序。 */
    val sortKey: String = "",
    val sortDescending: Boolean = true
) {
    fun songSort(): SortField = SortField.fromName(sortKey)
        fun artistSort(): ArtistSort = ArtistSort.fromName(sortKey)
    fun genreSort(): GenreSort = GenreSort.fromName(sortKey)
}

/**
 * 某个页签可选的排序项：枚举名 → 界面文字。
 *
 * 三页各有自己的排序枚举（用户要求：艺术家只留艺术家名和数量，
 * 曲风只留名字和数量，歌曲仍是原来的六项）。
 */
fun sortOptionsOf(mode: LibraryMode): List<Pair<String, String>> = when (mode) {
    LibraryMode.SONGS -> SortField.entries.map { it.name to it.label }
    LibraryMode.ARTIST -> ArtistSort.entries.map { it.name to it.label }
    LibraryMode.GENRE -> GenreSort.entries.map { it.name to it.label }
    LibraryMode.META -> emptyList()
}

/**
 * 当前真正生效的排序项枚举名。
 *
 * [TabLayout.sortKey] 为空（从没设置过）时取该页的默认值 ——
 * 布局弹窗要高亮当前项，不能因为"没存过"就一项都不亮。
 */
fun effectiveSortKey(mode: LibraryMode, layout: TabLayout): String = when (mode) {
    LibraryMode.SONGS -> layout.songSort().name
    LibraryMode.ARTIST -> layout.artistSort().name
    LibraryMode.GENRE -> layout.genreSort().name
    LibraryMode.META -> ""
}

data class ArtistEntry(
    val name: String,
    val trackCount: Int,
    val imagePath: String?,
    /**
     * 代表曲目：按文件路径排序后的第一首。
     * 艺术家图优先从它的内嵌图片里读，没有才用占位图——绝不拿歌曲封面顶替。
     */
    val sampleTrackPath: String? = null,
    val sampleStamp: Long = 0L,
    /** 代表曲目里是否真的内嵌了艺术家图（来自数据库，不用读文件）。 */
    val sampleHasArtistImage: Boolean = false,
    /**
     * 这位艺术家里真的带内嵌艺术家图的那首曲目。
     *
     * 刮削按艺术家进行、但要逐首写入，所以「哪一首上有图」是不确定的；
     * 用这一首取图，能保证只要这位艺术家刮到过图，网格里就显示得出来。
     */
    val embeddedTrackPath: String? = null,
    val embeddedStamp: Long = 0L
) {
    /**
     * 是否有艺术家图。
     * 之前写成「有代表曲目就算有图」，而每个艺术家都有代表曲目，
     * 于是「无图」筛选永远筛不出东西。现在只看真的有没有图。
     */
    val hasImage: Boolean get() = !imagePath.isNullOrBlank() || sampleHasArtistImage ||
        !embeddedTrackPath.isNullOrBlank()
}

/**
 * 曲风网格的一格。
 *
 * 刻意做成分层命名：`name` 是这一格的标签（单个曲风），
 * `trackCount` 是**带这个曲风的歌曲数**。一首歌有多个曲风时会被计入多格，
 * 所以所有格子的 trackCount 加起来会大于曲目总数——这是对的，不是 bug。
 */
data class GenreEntry(
    val name: String,
    val trackCount: Int,
    /** 代表曲目：用来取一张封面当格子配图。 */
    val sampleTrackPath: String? = null,
    val sampleStamp: Long = 0L,
    /** 曲风下没有封面时退回艺术家图。 */
    val sampleHasCover: Boolean = false
)

data class LibraryUiState(
    /**
     * 数据库第一次发射之前都算加载中。
     *
     * Room 的 Flow 是异步的：stateIn 的初始值（全空列表）会先被渲染成
     * 「曲库还是空的，请扫描」，等真实数据到了又立刻变成列表 ——
     * 用户看到的就是启动闪一下空态。有了这个标志，加载中只显示中性
     * 加载指示，绝不显示空态文案（真空与未加载是两回事）。
     */
    val loading: Boolean = true,
    /** 当前页签的布局配置（每页独立，来自持久化存储）。 */
    val tabLayout: TabLayout = TabLayout(),
    val mode: LibraryMode = LibraryMode.SONGS,
    val query: String = "",
    val selectedFolder: String? = null,
    val selectedArtist: String? = null,
    val selectedGenre: String? = null,
    val selectedIds: Set<Long> = emptySet(),
    /**
     * 艺术家页被整体选中的艺术家名。
     *
     * 和 [selectedIds] 分开存，是因为两者回答的不是同一个问题：
     * selectedIds 是"刮削要逐首处理哪些曲目"，这里只是"选了几位艺术家"。
     * 艺术家页底部要按**艺术家**计数——一位艺术家名下可能有几十首曲目，
     * 拿曲目数当"已选 n"会让用户以为自己选中了几十项。
     */
    val selectedArtists: Set<String> = emptySet(),
    /** 当前层级要显示的文件夹；搜索或艺术家模式下为空。 */
    val folders: List<FolderEntity> = emptyList(),
    val tracks: List<TrackEntity> = emptyList(),
    val visibleTracks: List<TrackEntity> = emptyList(),
    val artists: List<ArtistEntry> = emptyList(),
    val genres: List<GenreEntry> = emptyList(),
    val gridColumns: Int = GridPreset.STANDARD.columns,
    val artistFilter: ArtistFilter = ArtistFilter.ALL,
    /** 艺术家页的列表模式（布局弹窗里切换，见 [ArtistListMode]）。 */
    val artistListMode: ArtistListMode = ArtistListMode.PRIMARY,
    val settings: AppSettings = AppSettings()
) {
    val searching: Boolean get() = query.isNotBlank()

    /** 在最外层（没进某个艺术家/曲风、没搜索）时才算"根"。 */
    val atRoot: Boolean get() = selectedFolder == null && selectedArtist == null &&
        selectedGenre == null && !searching

    /** 当前是否停在某个集合的曲目列表里。 */
    val insideCollection: Boolean get() = selectedArtist != null || selectedGenre != null

    /** 面包屑，第一项是"全部"。 */
    val breadcrumbs: List<Pair<String?, String>> = buildList {
        add(null to "全部")
        selectedArtist?.let { add(null to it) }
        selectedGenre?.let { add(null to it) }
        selectedFolder?.let { path ->
            val chain = mutableListOf<String>()
            var cur: String? = path
            while (!cur.isNullOrBlank()) {
                chain += cur
                if (cur in settings.roots) break
                cur = cur.substringBeforeLast('/', "").ifBlank { null }
            }
            chain.asReversed().forEach { add(it to (it.substringAfterLast('/').ifBlank { it })) }
        }
    }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val mode = MutableStateFlow(LibraryMode.SONGS)

    init {
        // 恢复上次的界面状态：浏览模式（页签）/ 网格密度 / 艺术家筛选。
        viewModelScope.launch {
            val saved = ServiceLocator.settings.settings.first()
            LibraryMode.entries.firstOrNull { it.name == saved.libraryTab }?.let { mode.value = it }
            GridPreset.entries.firstOrNull { it.name == saved.gridPreset }?.let { gridPreset.value = it }
            ArtistFilter.entries.firstOrNull { it.name == saved.artistFilter }?.let { artistFilter.value = it }
        }
    }

    /**
     * 曲库扫描是否在跑。
     *
     * 歌曲列表下拉刷新之后，顶部那个刷新图标就靠它转：扫描一开始是 true，
     * 扫完整轮（含后台补全）才变 false，指示器自己收起来。
     */
    val scanRunning = ServiceLocator.scanner.progress
        .map { it.running }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ServiceLocator.scanner.progress.value.running
        )

    /**
     * 重新扫描整个曲库。
     *
     * 用户要求"音频库歌曲列表手指从上往下滑动就是重新扫描"，入口就在这里。
     * 走的是和「设置 - 音乐库 - 重新扫描」同一条路（[ScanLauncher]）：
     * 前台服务重读所有文件，跑完接着刷新歌词、元信息标签和开头静音档案，
     * 所以**不需要**在这里再单独扫一遍标签。
     */
    fun rescan() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val saved = ServiceLocator.settings.settings.first()
            runCatching {
                com.audiometa.app.media.scan.ScanLauncher.start(
                    context = context,
                    roots = saved.roots,
                    formats = saved.formats,
                    excluded = saved.excluded,
                    minDuration = saved.minDurationMs(),
                    excludeSubdirs = saved.excludeSubdirs
                )
            }
        }
    }

    private val query = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<String?>(null)
    private val selectedArtist = MutableStateFlow<String?>(null)
    private val selectedGenre = MutableStateFlow<String?>(null)
    private val artistFilter = MutableStateFlow(ArtistFilter.ALL)
    private val gridPreset = MutableStateFlow(GridPreset.STANDARD)

    /**
     * 艺术家页多选时被选中的艺术家名（见 [LibraryUiState.selectedArtists]）。
     *
     * 是状态流而不是 Compose 本地状态：底部操作条的数量、全选按钮的可用性
     * 都要跟着它走，而操作条画在 pager 外面。
     */
    private val selectedArtists = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 艺术家页的列表模式。
     *
     * 放进 combine 的输入里（而不是普通 Compose 状态）：分组结果是在 combine 里算的，
     * 只改一个状态不会让列表重算 —— 用户会看到"切了但列表没变"。
     */
    private val artistListMode = MutableStateFlow(ArtistListMode.PRIMARY)

    fun setArtistListMode(mode: ArtistListMode) {
        if (mode == artistListMode.value) return
        artistListMode.value = mode
        // 派生缓存按曲库实例缓存，模式变了必须重算。
        derivedCache = null
    }


    val state = combine(
        mode,
        query,
        selectedFolder,
        selectedArtist,
        ServiceLocator.selection.ids,
        ServiceLocator.folders.observeAll(),
        ServiceLocator.tracks.observeAll(),
        ServiceLocator.settings.settings,
        ServiceLocator.artists.observeAll(),
        artistFilter,
        gridPreset,
        selectedGenre,
        artistListMode,
        selectedArtists
    ) { values ->
        val currentMode = values[0] as LibraryMode
        val currentQuery = values[1] as String
        val folder = values[2] as String?
        val artist = values[3] as String?
        @Suppress("UNCHECKED_CAST")
        val ids = values[4] as Set<Long>
        @Suppress("UNCHECKED_CAST")
        val allFolders = values[5] as List<FolderEntity>
        @Suppress("UNCHECKED_CAST")
        val tracks = values[6] as List<TrackEntity>
        val currentArtistListMode = values[12] as ArtistListMode
        val settings = values[7] as AppSettings
        @Suppress("UNCHECKED_CAST")
        val artistEntities = values[8] as List<com.audiometa.app.data.db.ArtistEntity>
        val filter = values[9] as ArtistFilter
        val preset = values[10] as GridPreset
        val genre = values[11] as String?
        @Suppress("UNCHECKED_CAST")
        val selectedArtistNames = values[13] as Set<String>

        /*
         * 整段都在 Dispatchers.Default 上跑。
         *
         * 这里是"把整个曲库重算一遍"：过滤、按艺术家/曲风分组、排序。
         * 以前它跑在 viewModelScope 的 Main 上 —— 切页签（mode 变了）、
         * 改设置、勾选一首歌都会触发一次；曲库一大就是几十上百毫秒的主线程停顿，
         * 表现正是"几个子页来回滑动卡顿"。
         *
         * 另外 [derivedOf] 会把"按曲库分组"的结果按 tracks 的**同一份实例**缓存下来：
         * 切页签时曲库没变，就不必再拆一遍艺术家名、再分一遍组。
         */
        withContext(Dispatchers.Default) {
            val needle = currentQuery.trim()
            val searching = needle.isNotBlank()
            val derived = derivedOf(tracks)
            val artistNames = derived.artistNames

            val visible = tracks.filter { track ->
                val inFolder = folder == null || track.folderPath == folder ||
                    track.folderPath.startsWith(folder + "/")
                // 保持原来的语义：这里看的是整串 artist 字段（合作曲的展开只在下面
                // 艺术家页签那一支里做）。别顺手改，它决定多选"全选"选到哪些歌。
                val inArtist = artist == null || track.artist == artist
                // 曲风是"命中其中之一"：一首歌有多个曲风，任一命中就算在列表里。
                val inGenre = genre == null || track.genreList.any { it.equals(genre, true) }
                val matches = !searching ||
                    track.title.contains(needle, true) ||
                    track.artist.contains(needle, true) ||
                    track.album.contains(needle, true) ||
                    track.path.contains(needle, true)
                inFolder && inArtist && inGenre && matches
            }

            // 搜索时平铺结果；歌曲页签就是完整列表；
            // 艺术家/曲风页签只在点进具体某一项之后才列曲目。
            val shownTracks = when {
                searching -> visible
                currentMode == LibraryMode.SONGS -> tracks
                // 合作曲也要算进来：点进 A 应该能看到 "A/B" 这首。
                artist != null -> tracks.filter { artist in artistNames[it.id].orEmpty() }
                genre != null -> tracks.filter { t -> t.genreList.any { it.equals(genre, true) } }
                else -> emptyList()
            }.let { list ->
                // 排序按当前页签自己的配置（每页独立持久化）。
                sortedTracks(list, layoutOf(settings, currentMode))
            }

            // 音频库不再按目录浏览，目录管理都在设置里。
            val shownFolders = emptyList<FolderEntity>()

            val imageByName = imageByNameOf(artistEntities)
            // 艺术家图既可能外置成文件，也可能内嵌在标签里。
            // 之前只看 ArtistEntity.imageLocalPath，内嵌的情况下这里是空的，
            // 网格就退回读「代表曲目」——而刮削是按艺术家做的，
            // 代表曲目不一定就是被写过图的那首，于是有图也显示成占位图。
            // 现在把这位艺术家所有曲目里第一个真的带艺术家图的位置也记下来。
            val embeddedByName = derived.embeddedByName
            // 艺术家按名字聚合。
            //
            // 关键点：一首歌的 artist 字段经常是"A/B"、"A feat. B"、"A、B"这种合作写法，
            // 直接把整串当艺术家名的话，网格里会冒出一堆"A/B"这种没人认识的"艺术家"。
            // 所以先把每首歌拆成单个艺术家名，再按名字归组：同一首歌会出现在 A 和 B 两格里，
            // 点进任意一位都能看到这首合作曲。
            val artists = derived.artistGroups
                .map { (name, items) ->
                    val image = imageByName[name]?.takeIf { it.isNotBlank() }
                    val embedded = embeddedByName[name]
                    val sample = items.minByOrNull { it.path.lowercase() }
                    ArtistEntry(
                        name = name,
                        trackCount = items.size,
                        imagePath = image,
                        // 代表曲目：按文件路径排序后的第一首，艺术家图从它里面读。
                        sampleTrackPath = sample?.path,
                        sampleStamp = sample?.updatedAt ?: 0L,
                        sampleHasArtistImage = sample?.hasArtistImage == true,
                        // 内嵌图不在代表曲目里时，用真正带图的那首来显示。
                        embeddedTrackPath = embedded?.first,
                        embeddedStamp = embedded?.second ?: 0L
                    )
                }
                .filter {
                    when (filter) {
                        ArtistFilter.ALL -> true
                        ArtistFilter.WITH_IMAGE -> it.hasImage
                        ArtistFilter.WITHOUT_IMAGE -> !it.hasImage
                    }
                }
                .let { sortArtists(it, layoutOf(settings, LibraryMode.ARTIST)) }

            // 曲风：先把每首歌的曲风列表摊平，再按曲风名归组。
            // 关键差别就在这一步——一首歌出现在几个曲风下，就贡献几次计数。
            val genres = derived.genreGroups
                .map { (name, items) ->
                    val sample = items.minByOrNull { it.path.lowercase() }
                    GenreEntry(
                        name = name,
                        trackCount = items.size,
                        sampleTrackPath = sample?.path,
                        sampleStamp = sample?.updatedAt ?: 0L,
                        sampleHasCover = sample?.hasCover == true
                    )
                }
                .let { sortGenres(it, layoutOf(settings, LibraryMode.GENRE)) }

            // 勾选状态只保留"当前可见的"：没勾任何东西时连集合都不必建。
            val selectedIds = if (ids.isEmpty()) {
                emptySet()
            } else {
                ids.intersect(visible.mapTo(HashSet(visible.size)) { it.id })
            }

            LibraryUiState(
                loading = false,
                mode = currentMode,
                query = currentQuery,
                selectedFolder = folder,
                selectedArtist = artist,
                selectedGenre = genre,
                selectedIds = selectedIds,
                selectedArtists = selectedArtistNames,
                artistListMode = currentArtistListMode,
                folders = shownFolders,
                tracks = tracks,
                visibleTracks = shownTracks,
                artists = artists,
                genres = genres,
                gridColumns = preset.columns,
                artistFilter = filter,
                settings = settings,
                tabLayout = layoutOf(settings, currentMode)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /**
     * 每页布局配置的解析结果。
     *
     * `tabLayouts` 是一段 JSON，一次状态更新要按页签读 3~4 回；字符串没变就不必重解析。
     */
    private var layoutsSource: String? = null
    private var layoutsParsed: Map<LibraryMode, TabLayout> = emptyMap()

    private fun layoutOf(settings: AppSettings, mode: LibraryMode): TabLayout {
        val raw = settings.tabLayouts
        if (layoutsSource != raw) {
            layoutsSource = raw
            layoutsParsed = LibraryMode.entries.associateWith { entry ->
                com.audiometa.app.data.prefs.TabLayouts.read(raw, entry.name)
            }
        }
        return layoutsParsed[mode] ?: TabLayout()
    }

    /**
     * 排好序的曲目列表。
     *
     * 输入列表和排序方式都没变就直接用上次那份：
     * 一来省掉一次 O(n log n)，二来**列表实例保持不变**，
     * LazyColumn 不必为一次无关的状态更新（比如勾选一首歌）重排整页。
     *
     * 真正的排序规则在 [LibrarySorting]（纯函数，有单测钉着）。
     */
    private var sortedInput: List<TrackEntity>? = null
    private var sortedBy: String = ""
    private var sortedValue: List<TrackEntity> = emptyList()

    private fun sortedTracks(list: List<TrackEntity>, layout: TabLayout): List<TrackEntity> {
        val by = layout.songSort().name + if (layout.sortDescending) "-" else "+"
        if (sortedInput === list && sortedBy == by) return sortedValue
        val sorted = LibrarySorting.tracks(list, layout)
        sortedInput = list
        sortedBy = by
        sortedValue = sorted
        return sorted
    }

    private fun sortArtists(list: List<ArtistEntry>, layout: TabLayout): List<ArtistEntry> =
        LibrarySorting.artists(list, layout)

    private fun sortGenres(list: List<GenreEntry>, layout: TabLayout): List<GenreEntry> =
        LibrarySorting.genres(list, layout)

    /**
     * 按曲库分组出来的中间结果。
     *
     * 这些量只和"曲库列表"有关，和页签、搜索、勾选都无关；以前每次重算都重做一遍，
     * 而它正是最贵的一段（拆艺术家名、按艺术家/曲风归组）。
     */
    private class Derived(
        val tracks: List<TrackEntity>,
        /** 每首歌拆出来的艺术家名（合作写法拆开）。 */
        val artistNames: Map<Long, List<String>>,
        /** 艺术家名 → 名下的曲目（合作曲会出现在多位艺术家名下）。 */
        val artistGroups: Map<String, List<TrackEntity>>,
        /** 艺术家名 → 真正带内嵌艺术家图的那首（路径, updatedAt）。 */
        val embeddedByName: Map<String, Pair<String, Long>>,
        /** 曲风名 → 带这个曲风的曲目。 */
        val genreGroups: Map<String, List<TrackEntity>>
    )

    private var derivedCache: Derived? = null

    /** 取这份曲库的派生结果；曲库没换（同一个列表实例）就直接用上次的。 */
    private fun derivedOf(tracks: List<TrackEntity>): Derived {
        derivedCache?.let { if (it.tracks === tracks) return it }

        val names = HashMap<Long, List<String>>(tracks.size * 2)
        val artistGroups = HashMap<String, MutableList<TrackEntity>>()
        val genreGroups = HashMap<String, MutableList<TrackEntity>>()
        for (track in tracks) {
            val artistList = artistNamesOf(track)
            names[track.id] = artistList
            /*
             * 艺术家分组按"列表模式"来（用户要求，可在布局窗口里切换）：
             * - 默认（首位艺术家）：一首歌**只**归它的第一位艺术家，合作曲不会在
             *   每位合作者名下各出现一次 —— 艺术家列表里一首音频只出现一次；
             * - 多位艺术家歌曲：只收合作曲，按完整艺术家串分组。
             */
            when (artistListMode.value) {
                ArtistListMode.PRIMARY ->
                    artistList.firstOrNull()?.let { name ->
                        artistGroups.getOrPut(name) { mutableListOf() } += track
                    }
                ArtistListMode.MULTI ->
                    if (artistList.size > 1) {
                        val key = track.artist.trim().ifBlank { artistList.joinToString("/") }
                        artistGroups.getOrPut(key) { mutableListOf() } += track
                    }
            }
            track.genreList.ifEmpty { listOf(UNKNOWN_GENRE) }
                .forEach { name -> genreGroups.getOrPut(name) { mutableListOf() } += track }
        }

        val embedded = HashMap<String, Pair<String, Long>>()
        // 比较用的是小写路径，但**存下去的必须是原路径**（界面要拿它去读图）。
        val embeddedLower = HashMap<String, String>()
        for (track in tracks) {
            if (!track.hasArtistImage) continue
            val lower = track.path.lowercase()
            for (name in names[track.id].orEmpty()) {
                val current = embeddedLower[name]
                // 取路径最小的那首（和以前 minByOrNull { path.lowercase() } 一致）。
                if (current == null || lower < current) {
                    embeddedLower[name] = lower
                    embedded[name] = track.path to track.updatedAt
                }
            }
        }

        return Derived(
            tracks = tracks,
            artistNames = names,
            artistGroups = artistGroups,
            embeddedByName = embedded,
            genreGroups = genreGroups
        ).also { derivedCache = it }
    }

    /** 艺术家名 → 外置艺术家图路径（可能是 null）；按 artistEntities 的实例缓存。 */
    private var imageCache:
        Pair<List<com.audiometa.app.data.db.ArtistEntity>, Map<String, String?>>? = null

    private fun imageByNameOf(
        artistEntities: List<com.audiometa.app.data.db.ArtistEntity>
    ): Map<String, String?> {
        imageCache?.let { if (it.first === artistEntities) return it.second }
        val map = HashMap<String, String?>(artistEntities.size * 2)
        artistEntities.forEach { entity -> map[entity.name] = entity.imageLocalPath }
        imageCache = artistEntities to map
        return map
    }

    /**
     * 切换页签。
     *
     * @param persist 要不要把页签写进设置。滑动过程中由界面传 `false`：
     *   DataStore 每写一次都会推一次 settings 流，接着整份状态重算 + 重组，
     *   正好压在滑动动画上（卡顿的一大来源）。停下来之后界面会调 [persistMode] 补一次。
     */
    fun setMode(value: LibraryMode, persist: Boolean = true) {
        mode.value = value
        selectedFolder.value = null
        selectedArtist.value = null
        selectedGenre.value = null
        if (persist) persistMode(value)
    }

    /** 把当前页签记进设置（重启软件停在同一页）。 */
    fun persistMode(value: LibraryMode) {
        viewModelScope.launch { ServiceLocator.settings.setLibraryTab(value.name) }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setArtistFilter(value: ArtistFilter) {
        artistFilter.value = value
        viewModelScope.launch { ServiceLocator.settings.setArtistFilter(value.name) }
    }

    fun setArtistShape(value: String) {
        viewModelScope.launch { ServiceLocator.settings.setArtistShape(value) }
    }

    /** 兼容旧调用：改成写当前页的配置。 */
    fun setGridPreset(value: GridPreset) {
        gridPreset.value = value
        viewModelScope.launch { ServiceLocator.settings.setGridPreset(value.name) }
    }

    fun setSongsAsGrid(value: Boolean) {
        viewModelScope.launch { ServiceLocator.settings.setSongsAsGrid(value) }
    }

    fun setArtistsAsGrid(value: Boolean) {
        viewModelScope.launch { ServiceLocator.settings.setArtistsAsGrid(value) }
    }

    fun setGenresAsGrid(value: Boolean) {
        viewModelScope.launch { ServiceLocator.settings.setGenresAsGrid(value) }
    }

    /**
     * 更新当前页签的布局配置，并立即持久化。
     *
     * 每页各存一份（JSON，键是页签名）。用户要求"每页状态独立且持久化存储，
     * 重启软件保持上次设置的状态"，所以这里不做任何跨页同步。
     */
    fun updateTabLayout(layout: TabLayout) {
        viewModelScope.launch {
            val store = ServiceLocator.settings
            val current = store.settings.first().tabLayouts
            store.setTabLayouts(
                com.audiometa.app.data.prefs.TabLayouts.write(current, mode.value.name, layout)
            )
        }
    }

    fun openFolder(path: String?) {
        selectedFolder.value = path
    }

    fun openArtist(name: String?) {
        selectedArtist.value = name
    }

    fun openGenre(name: String?) {
        selectedGenre.value = name
    }

    /** 返回上一层：曲风 → 艺术家 → 目录 → 根。用一层就够，不需要递归。 */
    fun goUp() {
        when {
            selectedGenre.value != null -> selectedGenre.value = null
            selectedArtist.value != null -> selectedArtist.value = null
            selectedFolder.value != null -> {
                val folder = selectedFolder.value
                val parent = folder?.substringBeforeLast('/', "") ?: ""
                selectedFolder.value = if (parent.isBlank() ||
                    parent in state.value.settings.roots
                ) {
                    null
                } else {
                    parent
                }
            }
        }
    }

    fun toggle(id: Long) = ServiceLocator.selection.toggle(id)

    /** 进入多选：只由长按触发，界面上的按钮已经按用户要求移除了。 */
    fun selectOnly(id: Long) = ServiceLocator.selection.set(setOf(id))

    /**
     * 一位艺术家名下的全部曲目 id。
     *
     * 刻意复用 [derivedOf] 的艺术家分组，而不是自己再用 [artistNamesOf] 匹配一遍：
     * 网格里那一格装的是哪些曲目，这里取到的就是哪些。
     * 「多位艺术家歌曲」模式下格子名是完整的艺术家串（`A/B`），
     * 自己按单个名字去匹配会一首也匹配不上，于是"长按了却什么都没选上"。
     */
    private fun artistTrackIds(name: String): Set<Long> =
        derivedOf(state.value.tracks).artistGroups[name].orEmpty()
            .mapTo(HashSet<Long>()) { it.id }

    /**
     * 长按艺术家：进入多选并选中这位艺术家。
     *
     * 曲目 id 照旧全部并入 selection —— 刮削是逐首写标签的，
     * 少了这一步"选中某位艺术家再去刮削"就不成立（合作曲也一并算他的）。
     * 艺术家名另外记一份，只服务于底部那句"已选 n/总数"。
     */
    fun selectArtist(name: String) {
        val ids = artistTrackIds(name)
        selectedArtists.value = selectedArtists.value + name
        if (ids.isNotEmpty()) ServiceLocator.selection.addAll(ids)
    }

    /**
     * 多选状态下点艺术家卡片：切换这位艺术家的选中态。
     *
     * 取消选中时只摘掉"**只**属于这位艺术家"的曲目：合作曲同时挂在另一位
     * 仍被选中的艺术家名下，连它一起删会让那位的刮削范围凭空少一首。
     */
    fun toggleArtist(name: String) {
        val selected = selectedArtists.value
        if (name !in selected) {
            selectArtist(name)
            return
        }
        val others = selected - name
        val shared = others.flatMapTo(HashSet<Long>()) { artistTrackIds(it) }
        val exclusive = artistTrackIds(name) - shared
        selectedArtists.value = others
        if (exclusive.isNotEmpty()) {
            ServiceLocator.selection.set(ServiceLocator.selection.ids.value - exclusive)
        }
    }

    /**
     * 艺术家维度的「全选」：把网格里的艺术家全部选上，
     * 并把他们名下的曲目 id 都并进 selection（刮削最终认的仍然是这些 id）。
     */
    fun selectAllArtists() {
        val names = state.value.artists.map { it.name }
        selectedArtists.value = names.toSet()
        ServiceLocator.selection.addAll(names.flatMap { artistTrackIds(it) })
    }

    /**
     * 清空多选。
     *
     * 艺术家选中集合和曲目 selection 是一件事的两面（一个给计数看、一个给刮削用），
     * 只清一边就会留下"一位艺术家都没选、却还能去刮削"的错位。
     */
    fun clearSelection() {
        selectedArtists.value = emptySet()
        ServiceLocator.selection.set(emptySet())
    }

    /** 长按曲风：把带这个曲风的全部曲目一次选上（多曲风的歌只算一次）。 */
    fun selectGenre(name: String) {
        val ids = state.value.tracks
            .filter { track -> track.genreList.any { it.equals(name, true) } }
            .map { it.id }
            .toSet()
        if (ids.isNotEmpty()) ServiceLocator.selection.set(ids)
    }

    /**
     * 曲目维度的全选 / 清空（歌曲页、点进某位艺术家之后的曲目列表用）。
     *
     * 清空交给 [clearSelection]：艺术家选中集合必须跟着一起清，
     * 否则切回艺术家页会看到一份早就作废的计数。
     */
    fun selectVisible(select: Boolean) {
        if (!select) {
            clearSelection()
            return
        }
        val visible = state.value.visibleTracks.map { it.id }.toSet()
        ServiceLocator.selection.set(visible)
    }

    companion object {
        /** 没有流派标签的歌归到这一档，免得整首歌在流派页签里凭空消失。 */
        const val UNKNOWN_GENRE = "未知流派"

        const val UNKNOWN_ARTIST = "未知艺术家"

        /**
         * 把一首歌的 artist 字段拆成单个艺术家名。
         *
         * 一首歌可能是 A/B 或 "A feat. B"，直接当整串用的话艺术家页会出现
         * 一堆"A/B"这种没意义的条目。拆开之后，合作曲在 A 和 B 下面都看得到。
         *
         * 分隔符和拆法在 [com.audiometa.app.domain.model.ArtistNames] ——
         * 刮削判断"这首歌属不属于某位艺术家"用的是同一份规则。
         *
         * 拆不动（只有一个名字）就原样返回；字段为空归到「未知艺术家」。
         */
        fun artistNamesOf(track: TrackEntity): List<String> {
            val raw = track.artist.trim()
            if (raw.isBlank()) return listOf(UNKNOWN_ARTIST)
            return com.audiometa.app.domain.model.ArtistNames.of(raw)
        }
    }
}
