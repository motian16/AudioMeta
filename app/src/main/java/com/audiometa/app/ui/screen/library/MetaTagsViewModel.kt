package com.audiometa.app.ui.screen.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.MetaTagEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.tagrules.TagRules
import com.audiometa.app.domain.tagrules.TagSummary
import com.audiometa.app.ui.component.OneShotNotice
import com.audiometa.app.ui.component.fileName
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 元信息页里的一个来源分组。
 *
 * 用户要求"按照平台名进行合并一项"：同一个平台的所有标签合成一张卡，
 * 卡上只写平台名。没命中任何平台/工具的标签归到 [TagSummary.UNKNOWN_GROUP]。
 */
data class TagGroup(
    val name: String,
    /** 组内不同标签键的个数。 */
    val tagCount: Int,
    /** 组内标签在曲库里的命中总次数（同一首歌同一标签算一次）。 */
    val hitCount: Int,
    /** 是否是"不在规则里的标签"那一组。 */
    val isUnknown: Boolean = false,
    /** 是否是"未知图片"那一组（封面和艺术家图之外的图片标签）。 */
    val isImage: Boolean = false
)

/**
 * 元信息页点进某首歌时带过去的上下文。
 *
 * 用户要求：曲目列表右侧只显示别名，**完整值、键名和说明放到点进去的
 * 歌曲详情页**里显示。这个对象就是那份数据。
 */
data class MetaTagFocus(
    /** 来源分组名（平台名 / 工具名 / 未知标签 / 未知图片）。 */
    val group: String,
    val items: List<MetaTagItem>
)

/** 详情页里要展示的一条自定义标签。 */
data class MetaTagItem(
    /** 界面上显示的键名（已去掉容器前缀，例如 major_brand）。 */
    val key: String,
    /** 键名的中文别名（例如「容器主品牌」）。 */
    val keyAlias: String = "",
    /** 原文完整值。详情页要显示"完整值"，不做截断、不换成别名。 */
    val rawValue: String,
    /** 值的中文别名（例如「ISO 基础媒体格式第 5 版」）。 */
    val valueAlias: String = "",
    /** 这个键大概是什么（键名级说明，兜底用）。 */
    val keyType: String = "",
    val keyDescription: String = "",
    /** 这个**值**是什么（值级说明，优先用）。 */
    val valueType: String = "",
    val valueDescription: String = "",
    /** 值是图片时，库里那个 `IMG:` 标记；否则为空。 */
    val imageMarker: String = ""
) {
    val isImage: Boolean get() = imageMarker.isNotEmpty()

    /** 只有键、没有值的标签也要显示出来（用户要求）。 */
    val isEmptyValue: Boolean get() = !isImage && rawValue.isBlank()

    /** 最终显示的说明：值级优先，没有才退回键名级。 */
    val description: String get() = valueDescription.ifBlank { keyDescription }

    /** 最终显示的类型。 */
    val typeLabel: String get() = valueType.ifBlank { keyType }
}

/** 曲目在某一个来源下的一条取值。 */
data class MetaTrackValue(
    /** 界面上显示的键名。 */
    val key: String,
    val keyAlias: String = "",
    /** 已按"中文译名 → 类型别名 → 原文截断"处理过、可以直接显示的文字。 */
    val display: String,
    /** 原始值。复制到剪贴板用的是它（用户要的是能拿去做事的原值）。 */
    val raw: String,
    /** 值是图片时，库里存的那个 `IMG:` 标记；否则为空。 */
    val imageMarker: String = "",
    /** 键名级说明。 */
    val keyType: String = "",
    val keyDescription: String = "",
    /** 值级说明（优先于键名级）。 */
    val valueType: String = "",
    val valueDescription: String = "",
    /** 容器里的原始键名。 */
    val rawKey: String = ""
) {
    val isImage: Boolean get() = imageMarker.isNotEmpty()

    /** 只有键、没有值。 */
    val isEmptyValue: Boolean get() = !isImage && raw.isBlank()

    val description: String get() = valueDescription.ifBlank { keyDescription }
    val typeLabel: String get() = valueType.ifBlank { keyType }
}

/** 元信息页里的一行曲目：曲目本身 + 它在这一组里带的标签取值。 */
data class MetaTrackRow(
    val track: com.audiometa.app.data.db.TrackEntity,
    /** 这条曲目在这一组里出现的取值，已去重。 */
    val values: List<MetaTrackValue>,
    /** 这一行属于哪个来源分组 —— 决定右侧固定显示哪个键的值（见 [rightValue]）。 */
    val group: String = ""
) {
    /** 第一张图片值，列表行里当缩略图用。 */
    val firstImage: MetaTrackValue? get() = values.firstOrNull { it.isImage }

    /**
     * 规则给这一组指定的"右侧固定键"对应的那条取值。
     *
     * 用户要求：文件里同一组往往有好几个键名（酷狗有 kghash/extend/QUALITY…），
     * 右侧要**稳定**显示最关键的那个 —— 规则里每组写 `rightKey` 决定是谁。
     * 这条键不在文件里（或者没有值）时返回 null，界面退回"按别名去重"的老行为。
     */
    val rightValue: MetaTrackValue?
        get() {
            val wanted = com.audiometa.app.domain.tagrules.TagRules.rightKey(group) ?: return null
            return values.firstOrNull { !it.isImage && it.key.lowercase() == wanted && !it.isEmptyValue }
        }

    /**
     * 右列显示的别名串。
     *
     * 有 `rightKey` 时只显示那一条的别名；没有就退回"按别名去重、最多两项"——
     * mora 一首歌有五个 UUID 标签，全列出来那一列根本显示不全。
     */
    val valueText: String
        get() {
            rightValue?.let { value ->
                return value.display.ifBlank { value.raw }
            }
            val aliases = values.filter { !it.isImage && !it.isEmptyValue }
                .map { it.display }
                .filter { it.isNotBlank() }
                .distinct()
            if (aliases.isEmpty()) {
                return if (values.any { !it.isImage && it.isEmptyValue }) "（无值）" else ""
            }
            val head = aliases.take(MAX_LIST_ALIASES).joinToString(" / ")
            return if (aliases.size > MAX_LIST_ALIASES) head + " …" else head
        }

    /**
     * 点右列复制出去的内容。
     *
     * 复制的是**原始值**（不是界面上那个别名）—— 用户复制它多半是要拿去搜、
     * 拿去比对或者贴进报告里。有 [rightValue] 时只复制那一条（右侧显示的就是它），
     * 否则一条曲目在这个来源下的取值全部带上，一行一个。
     */
    val copyText: String
        get() = rightValue?.raw
            ?: values.filter { !it.isImage }.joinToString("\n") { it.raw }

    /**
     * 点进歌曲详情页时带过去的标签上下文。
     *
     * 图片值也一并带上：详情页要显示"完整值"，图片值就画成图。
     *
     * **顺序**（用户要求）：`rightKey` 那一条排第一（详情页第一屏就看得到），
     * 其余**按键名**排（同名键自然挨在一起），**没有值的键沉到最后**。
     */
    fun focus(groupName: String): MetaTagFocus {
        val wanted = com.audiometa.app.domain.tagrules.TagRules.rightKey(groupName)
        val items = values.map {
            MetaTagItem(
                key = it.key,
                keyAlias = it.keyAlias,
                rawValue = it.raw,
                valueAlias = it.display.takeIf { d -> d != it.raw && d != "图片" }.orEmpty(),
                keyType = it.keyType,
                keyDescription = it.keyDescription,
                valueType = it.valueType,
                valueDescription = it.valueDescription,
                imageMarker = it.imageMarker
            )
        }.sortedWith(
            compareBy(
                { if (wanted != null && it.key.lowercase() == wanted) 0 else 1 },
                { if (it.isEmptyValue) 1 else 0 },
                { it.key.lowercase() }
            )
        )
        return MetaTagFocus(group = groupName, items = items)
    }
}

/** 列表右侧最多显示几个别名，多的收进省略号。 */
private const val MAX_LIST_ALIASES = 2

/**
 * 同一种标签（同一个键 + 同一个值）命中多少首曲目时，合并成一级列表里的一项。
 *
 * 用户要求："如果列表有超过 10 首及 10 首以上的音频有同一种标签的话，合并为一个，
 * 点击进入二级列表"。所以阈值就是 10。
 */
const val BUCKET_MIN_TRACKS = 10

/**
 * 一级列表里的一项。
 *
 * 用户要的结构是两级：
 * - 分组（平台）→ **一级列表**：[Song] 和 [Bucket] 混排；
 * - [Bucket] 点进去 → **二级列表**（同一个键同一个值的那批曲目）。
 *
 * 一首歌"内含多个标签"时可以同时出现在二级列表和一级列表里 ——
 * 也就是它既在被合并的那一项里，又因为还有别的没被合并的取值而单独占一行。
 */
sealed interface MetaListEntry {
    /** 单独列出来的曲目：它在本组里还有没被合并的取值。 */
    data class Song(val row: MetaTrackRow) : MetaListEntry

    /** 合并项：同一个键同一个值命中 ≥ [BUCKET_MIN_TRACKS] 首。 */
    data class Bucket(val item: MetaBucket) : MetaListEntry
}

/** 一个被合并的标签项：这份数据就是它下一级的列表。 */
data class MetaBucket(
    /** 合并的是"变量"（键）还是"取值"（键 + 值）。 */
    val kind: MetaBucketing.Kind,
    /** 界面显示的键名（已去容器前缀）。 */
    val key: String,
    val keyAlias: String = "",
    /** 原始键名（复制和下级筛选用）。 */
    val rawKey: String,
    /** 原始值；变量项为空串。 */
    val rawValue: String,
    /** 界面显示的值（中文别名优先）；变量项为空串。 */
    val display: String,
    /** 额外的副标题前缀（按专辑/艺术家拆列表时写"3 张专辑"）。 */
    val subtitleExtra: String = "",
    /** 命中这一项的全部曲目（下一级列表）。 */
    val rows: List<MetaTrackRow> = emptyList()
) {
    val count: Int get() = rows.size

    /** 变量项：点进去还有一层（取值）；其余（取值/家族/专辑）点进去就是曲目。 */
    val isVariable: Boolean get() = kind == MetaBucketing.Kind.VARIABLE

    /** 一级列表里那一行的文字：变量项显示键别名，取值项显示值，专辑项显示专辑/艺术家名。 */
    val title: String
        get() = if (isVariable) {
            keyAlias.ifBlank { key }
        } else {
            display.ifBlank { rawValue }.ifBlank { "（无值）" }
        }

    /** 副标题：键名 + 数量；专辑列表额外带上"几张专辑"。 */
    val subtitle: String
        get() = listOf(
            subtitleExtra,
            if (isVariable || kind == MetaBucketing.Kind.ALBUM) "" else keyAlias.ifBlank { key },
            count.toString() + " 首"
        ).filter { it.isNotBlank() }.joinToString(" · ")

    /** 这一项匹配不匹配某首歌的某个取值（下级筛选用）。 */
    fun matches(value: MetaTrackValue): Boolean {
        // 比较**归一化后的键名**（和分组用的是同一套），否则同一逻辑键的另一种写法匹配不上。
        val valueKey = value.key.lowercase()
        if (valueKey != rawKey) return false
        return isVariable || value.raw == rawValue
    }
}

/** 「元信息」页签的界面状态。 */
data class MetaTagsUiState(
    val loading: Boolean = false,
    /** 曲库里的未知/自定义标签，按平台名合并后的分组。来自数据库，不是内存。 */
    val groups: List<TagGroup> = emptyList(),
    /** 全部标签（扁平），复制报告用。 */
    val tags: List<TagSummary> = emptyList(),
    /** 手动重扫的进度：只能靠扫描过程自己报，数据库里读不出"扫到第几首"。 */
    val scannedTracks: Int = 0,
    val totalTracks: Int = 0,
    /** 选中的平台分组 → 这个平台下的曲目。 */
    val selected: TagGroup? = null,
    /** 选中分组下、带这个来源标签的全部曲目（[entries] 就是从它算出来的）。 */
    val selectedTracks: List<MetaTrackRow> = emptyList(),
    /** 一级列表（曲目 + 被合并的标签项）。 */
    val entries: List<MetaListEntry> = emptyList(),
    /** 组内浏览路径：一层层点进来的合并项。空 = 组页面（一级列表）。 */
    val path: List<MetaBucket> = emptyList(),
    val loadingTracks: Boolean = false
) {
    /** 已经有扫描结果（数据库里有记录）。 */
    val hasResult: Boolean get() = tags.isNotEmpty()

    /** 选中分组里的标签键。详情页头部要显示键名和它的说明。 */
    val selectedKeys: List<TagSummary>
        get() = selected?.let { group -> tags.filter { it.group == group.name } }.orEmpty()
}

/**
 * 「元信息」页签。
 *
 * 三个关键设计（都是修 bug 修出来的）：
 *
 * 1. **结果落库**。之前只存内存，关闭软件就丢，下次启动重新扫第一批 ——
 *    用户看到的是"全部扫描后重启只显示 40 项"。现在打开页面直接从数据库读，
 *    不需要任何预扫描。
 *
 * 2. **跟着音乐库扫描一起做**。标签识别已经并进 [com.audiometa.app.media.scan.LibraryIndexer]，
 *    曲库扫描（或后台补全）跑完，这里的记录就有了 —— 不再需要用户先手动点一次「重新扫描」。
 *    手动入口保留，用于"我刚改过文件标签"这种要强制重读的场景。
 *
 * 3. **数据库一有变化就刷新**。之前只在进页面时读一次，
 *    扫完曲库回到这一页看到的还是旧结果。
 */
class MetaTagsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MetaTagsUiState())
    val state = _state.asStateFlow()

    /** 一次性提示走系统横幅，不写进界面文字。 */
    val notice = OneShotNotice()

    private var running = false

    init {
        // 数据库一有变化就重算分组。曲库扫描、后台补全、手动重扫都会走到这里，
        // 界面不需要自己去问 —— 之前只在进页面时读一次，扫完曲库回来看到的还是旧结果。
        //
        // 只订阅 meta_tags：它是唯一的数据源。曲目总数不订阅，
        // 那是一个全表 Flow，多一个订阅者就多一份完整曲目列表的内存和查询开销，
        // 而这里只有"扫描进度 x/y"用得到它。
        //
        // conflate 是必须的：后台补全是逐首写库的，一次全量补全会发出上千次
        // 表变更通知。不合并的话每次都要把整张表读出来重新分组，
        // 扫描期间界面会被这些中间态拖着走。合并之后只保证"最终一定会刷到最新"。
        viewModelScope.launch {
            ServiceLocator.metaTags.observeAll()
                .conflate()
                .collect { rows ->
                    /*
                     * 分组是纯计算，但输入是**文件里的真实标签**（键名、值都可能很怪）。
                     *
                     * 扫描一结束这张表就会有数据，于是主界面一组合就会走这里；
                     * 一旦某个键触发了未预料的异常，异常会顺着 Flow 冒到界面 ——
                     * 用户看到的就是"扫描完再打开就闪退"。这里兜住：这次分组失败
                     * 就保留上一次的结果，并记一条日志，绝不让整页崩掉。
                     */
                    val grouped = runCatching {
                        withContext(Dispatchers.Default) { buildGroups(rows) }
                    }.onFailure {
                        AppLog.w(TAG, "元信息分组失败（本次保留旧结果）：" + it.message)
                    }.getOrNull() ?: return@collect
                    val (tags, groups) = grouped
                    // 选中的分组如果还在，保持选中（用户点进去之后后台又扫了一遍，
                    // 不该把他弹回列表页）。
                    val selected = _state.value.selected?.let { current ->
                        groups.firstOrNull { it.name == current.name }
                    }
                    _state.value = _state.value.copy(tags = tags, groups = groups, selected = selected)
                }
        }
    }

    /** 把数据库里的行汇总成"按平台合并"的分组和扁平的标签表。不读任何文件。 */
    private fun buildGroups(rows: List<MetaTagEntity>): Pair<List<TagSummary>, List<TagGroup>> {
        /*
         * **不按"有没有规则"过滤。**
         *
         * 这里以前是 `if (!TagRules.isImported) return empty…` —— 没有规则时
         * 一条都不显示，页面只剩一句"本地无规则，请添加规则源"。
         * 但规则的作用是给标签**加别名/说明/平台分组**，不是决定标签存不存在：
         * 用户文件里真实存在的自定义标签，就算规则表没提到，也该出现在元信息页
         * （归到「未知标签」那一组）。所以这里照常汇总，页面上的"去订阅一次"
         * 提示改成**只在这份结果为空时**才出现（见 MetaTagsPane）。
         */
        val tags = TagRules.summarize(rows.map { it.toEntry() })
        val groups = tags
            .groupBy { it.group }
            .map { (name, items) ->
                // 一份规则都没有时，分组名就是键名 —— 这时的卡片和「未知标签」
                // 是一类东西（都没有来源），图标与排序按未知处理。
                val byKey = !TagRules.isImported &&
                    items.all { it.platform == null && it.tool == null && !it.isImage }
                TagGroup(
                    name = name,
                    tagCount = items.size,
                    hitCount = items.sumOf { it.count },
                    isUnknown = name == TagSummary.UNKNOWN_GROUP || byKey,
                    isImage = name == TagRules.imageGroup()
                )
            }
            // 有来源的排前面，"未知图片"和"未知标签"垫底。
            .sortedWith(
                compareBy<TagGroup> { it.isUnknown || it.isImage }
                    .thenByDescending { it.tagCount }
                    .thenBy { it.name.lowercase() }
            )
        return tags to groups
    }

    /**
     * 重新扫描：把曲库里所有音频文件的标签重读一遍。
     *
     * 一次扫完，不分批 —— 分批会让界面长时间处于"扫了一半"的状态，
     * 那正是之前"只显示 40 项"的来源。扫描在 IO 线程逐首进行，
     * 每首写完就落库，中途退出也不会丢已扫的部分。
     */
    fun rescan() {
        if (running) return
        /*
         * **没有规则也照扫。**
         *
         * 这里以前是 `if (!TagRules.isImported) { notice.show("本地无规则，请先添加规则源"); return }`
         * —— 没有规则就什么都不做。这个早退是那次"元信息页刷屏"回归的一部分：
         * 用户点「重新扫描」的诉求是"文件里的自定义标签读出来给我看"，
         * 而这件事不需要规则也能做（规则只负责别名/说明/分组）。
         *
         * 扫描照跑，只在**结果**那一句里把"现在还没有规则"说清楚，
         * 免得用户以为"识别完了，就这么多"（那句话在 [rescanAll] 结尾现读状态，
         * 因为扫描可能跑很久，期间用户可能已经去把规则导入了）。
         */
        running = true
        viewModelScope.launch {
            try {
                rescanAll()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // 这个入口是逐首读文件的，任何一首出意外都不该把整个应用带走。
                notice.show("重扫标签失败：" + (error.message ?: error.javaClass.simpleName))
            } finally {
                // running 必须复位：卡在 true 上这个按钮就永远点不动了。
                running = false
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /** [rescan] 的实际流程；异常由调用方兜住。 */
    private suspend fun rescanAll() {
        val tracks = withContext(Dispatchers.IO) { ServiceLocator.tracks.allOnce() }
        _state.value = _state.value.copy(loading = true, totalTracks = tracks.size)

        withContext(Dispatchers.IO) {
            val reader = ServiceLocator.tagReader
            val sink: (ByteArray, String) -> String? = { bytes, mime ->
                com.audiometa.app.media.tag.MetaImageStore
                    .store(ServiceLocator.appContext, bytes, mime)
            }
            tracks.forEachIndexed { index, track ->
                val file = File(track.path)
                val entries = if (file.isFile) {
                    runCatching { reader.readAllTags(file, sink) }.getOrDefault(emptyMap())
                        .let { raw ->
                            if (raw.isEmpty()) emptyList()
                            else TagRules.classify(raw, includeStandard = false, keepBlankValues = true)
                        }
                } else {
                    emptyList()
                }
                ServiceLocator.metaTags.replaceForTrack(
                    trackId = track.id,
                    rows = entries.map { it.toEntity(track.id) }
                )
                // 每 8 首更新一次进度：太频繁会让界面抖动。
                if (index % 8 == 0 || index == tracks.lastIndex) {
                    _state.value = _state.value.copy(scannedTracks = index + 1)
                }
            }
            // 清掉已经不在曲库里的记录，以及没人引用的图片。
            runCatching { ServiceLocator.metaTags.pruneTo(tracks.map { it.id }) }
                .onFailure { com.audiometa.app.util.AppLog.w(TAG, "清理失效标签记录失败：" + it.message) }
            runCatching {
                val keep = ServiceLocator.metaTags.all()
                    .map { it.value }
                    .filter { com.audiometa.app.media.tag.MetaImageStore.isImageValue(it) }
                    .map { com.audiometa.app.media.tag.MetaImageStore.fileNameOf(it) }
                    .toSet()
                com.audiometa.app.media.tag.MetaImageStore.prune(ServiceLocator.appContext, keep)
            }
        }

        _state.value = _state.value.copy(loading = false, scannedTracks = tracks.size)
        // 组数直接从库里数一遍：界面状态是订阅数据库刷出来的，
        // 刚写完的那一批还没走到的时候读数会偏小。
        val count = withContext(Dispatchers.IO) {
            runCatching {
                TagRules.summarize(ServiceLocator.metaTags.all().map { it.toEntry() })
                    .map { it.group }
                    .distinct()
                    .size
            }.getOrDefault(0)
        }
        notice.show(
            buildString {
                if (count == 0) {
                    append("扫描完成，没有发现自定义标签")
                } else {
                    append("扫描完成，发现 ").append(count).append(" 组标签来源")
                }
                // 没有规则时所有自定义标签都会落在「未知标签」一组里：
                // 这不是扫描失败，但用户该知道"想看到平台名/说明还得导入规则"。
                // 判据在这里现读（不是进函数时读一次）：扫描可能跑很久，
                // 期间用户完全可能去设置页把规则导入了。
                if (!TagRules.isImported) append("（本地无规则，到「设置 - 插件」更新订阅后即可识别平台来源）")
            }
        )
    }

    /**
     * 把识别结果整理成可直接粘贴的文本报告。
     *
     * 用户要求：
     * - **完全**导出软件识别到的东西 —— 每个键的**全部**取值（不是一个样本）；
     * - **附带歌曲内嵌的标题、艺术家、专辑**，这样报告拿出去能对上具体是哪首歌；
     * - 带上原始键名和说明，别人（或以后的我）不用再猜这个标签是什么。
     */
    suspend fun buildReportText(): String {
        val tags = _state.value.tags
        if (tags.isEmpty()) return ""
        // 键 → 出现的曲目。逐个键查一次太慢，一次性把行读出来自己分组。
        val rows = withContext(Dispatchers.IO) { ServiceLocator.metaTags.all() }
        val trackById = withContext(Dispatchers.IO) {
            ServiceLocator.tracks.allOnce().associateBy { it.id }
        }
        val byKey = rows.groupBy { TagRules.displayKey(it.key).lowercase() }
        return buildString {
            appendLine("音乐标签扫描报告")
            appendLine("共 " + tags.size + " 种自定义标签，来自 " + trackById.size + " 首曲目")
            appendLine()
            tags.forEachIndexed { index, tag ->
                appendLine("【" + (index + 1) + "】" + tag.key)
                tag.rawKeys.firstOrNull { it != tag.key }
                    ?.let { appendLine("原始键名：" + it) }
                tag.keyInfo?.type?.takeIf { it.isNotBlank() }?.let { appendLine("类型：" + it) }
                tag.keyInfo?.description?.takeIf { it.isNotBlank() }?.let { appendLine("说明：" + it) }
                if (tag.keyInfo == null) appendLine("说明：规则里还没有这个标签的说明")
                tag.hints.takeIf { it.isNotEmpty() }?.let { appendLine("提示：" + it.joinToString("、")) }

                // 全部取值（不是一个样本）。
                val values = byKey[tag.key.lowercase()]
                    .orEmpty()
                    .map { it.value }
                    .distinct()
                appendLine("取值（" + values.size + " 种）：")
                if (values.isEmpty()) {
                    appendLine("  （无值）")
                } else {
                    values.forEach { raw ->
                        appendLine("  " + describeRawValue(tag, raw))
                    }
                }

                // 出现的曲目：标题 / 艺术家 / 专辑 —— 用户要求附带上。
                val tracks = byKey[tag.key.lowercase()]
                    .orEmpty()
                    .mapNotNull { trackById[it.trackId] }
                    .distinctBy { it.id }
                appendLine("出现 " + tracks.size + " 首：")
                tracks.take(REPORT_TRACK_LIMIT).forEach { track ->
                    appendLine(
                        "  " + track.title.ifBlank { track.fileName } +
                            " — " + track.artist.ifBlank { "未知艺术家" } +
                            " · " + track.album.ifBlank { "未知专辑" } +
                            "   [" + track.path.substringAfterLast('/') + "]"
                    )
                }
                if (tracks.size > REPORT_TRACK_LIMIT) {
                    appendLine("  …另有 " + (tracks.size - REPORT_TRACK_LIMIT) + " 首")
                }
                appendLine()
            }
        }
    }

    /** 报告里一行取值的写法：图片、空值、别名和原始值分清楚。 */
    private fun describeRawValue(tag: TagSummary, raw: String): String = when {
        com.audiometa.app.media.tag.MetaImageStore.isImageValue(raw) -> "（图片：" +
            com.audiometa.app.media.tag.MetaImageStore.fileNameOf(raw) + "）"
        raw.isBlank() -> "（无值）"
        else -> {
            val alias = TagRules.displayValue(tag.key, raw)
            if (alias == raw) raw else alias + "   ← " + raw
        }
    }

    /**
     * 选中某个平台分组 → 列出带这个来源标签的曲目。
     *
     * 用户要求："**点进去的是进入歌曲列表**，每一行显示音频标题、艺术家、专辑，
     * 不显示格式，在右侧显示值的中文别名"。所以这里不再列"标签键 → 取值"，
     * 而是列曲目；取值作为右列挂在每一行上。
     */
    fun select(group: TagGroup?) {
        _state.value = _state.value.copy(
            selected = group,
            selectedTracks = emptyList(),
            entries = emptyList(),
            path = emptyList(),
            loadingTracks = group != null
        )
        if (group == null) return
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { loadTracks(group) }
            // 期间用户可能又点了别的分组/返回了，结果对不上就丢掉。
            if (_state.value.selected?.name != group.name) return@launch
            _state.value = _state.value.copy(
                selectedTracks = rows,
                entries = withContext(Dispatchers.Default) { rebuildEntries(rows, emptyList()) },
                path = emptyList(),
                loadingTracks = false
            )
        }
    }

    /** 点开一个合并项 → 下一级列表；传 null 回到上一级（到底就回组列表）。 */
    fun openBucket(bucket: MetaBucket?) {
        val current = _state.value
        val path = if (bucket == null) current.path.dropLast(1) else current.path + bucket
        _state.value = current.copy(
            path = path,
            entries = rebuildEntries(current.selectedTracks, path),
            loadingTracks = false
        )
    }

    /** 回到一级列表（组页面）。 */
    fun closeBuckets() {
        val current = _state.value
        _state.value = current.copy(
            path = emptyList(),
            entries = rebuildEntries(current.selectedTracks, emptyList())
        )
    }

    /**
     * 某一层的列表怎么拼。
     *
     * 用户规则：**同一个变量（键）命中 ≥ [BUCKET_MIN_TRACKS] 首**就合并成一项
     * （回放增益那三个键无条件拆），否则**同一个键 + 同一个值命中 ≥ 10 首**合并成一项；
     * 一首歌带多个标签时，可以既出现在下级列表里、又在本级单独占一行。
     *
     * 判断逻辑在 [MetaBucketing] 里（纯函数，有单测），这里只负责把下标换回行。
     */
    private fun rebuildEntries(rows: List<MetaTrackRow>, path: List<MetaBucket>): List<MetaListEntry> {
        // 最深到四级列表：组（一级）→ 键/取值项（二级）→ 取值项（三级）→ 曲目（四级）。
        // 再深就没有意义了（用户明确说规则最深只用到四级），这里直接兜住，
        // 免得哪天又出现"点进去还是同一项"的套娃。
        if (path.size >= 2) return emptyList()
        val groupName = _state.value.selected?.name.orEmpty()
        // 规则驱动的"按专辑拆列表"（专辑曲目那一组）：一级列表就是专辑/艺术家列表。
        if (path.isEmpty() && TagRules.listBy(groupName) == "album") {
            return albumEntries(rows, groupName)
        }
        // 当前层要看哪些曲目：路径上每一层都要匹配得上。
        val scoped = rows.filter { row -> path.all { step -> row.values.any { step.matches(it) } } }
        // 变量项点进去之后，只按那一个键的取值往下分，并且**不再生成变量项**。
        val onlyKey = path.lastOrNull { it.isVariable }?.rawKey
        val perTrack = scoped.map { row ->
            row.values
                .filter { !it.isImage }
                .filter { onlyKey == null || it.key.lowercase() == onlyKey }
                .map { value ->
                    // 用**归一化后的键名**分组：同一个逻辑键在文件里可能有多种写法
                    // （`REPLAYGAIN_TRACK_GAIN` / `TXXX:REPLAYGAIN_TRACK_GAIN`），
                    // 按原始键名分会冒出几个一模一样的项（用户看到的"6 个里 3 个重复"）。
                    MetaBucketing.Key(value.key.lowercase(), value.raw)
                }
        }
        // 「按键盘出列表」只在组这一层生效（回放增益 → 三个下级列表）。
        val keyLists = if (path.isEmpty()) {
            val names = scoped.flatMap { row -> row.values.map { it.key.lowercase() } }
                .toSet()
            if (TagRules.wantsKeyLists(groupName)) names else emptySet()
        } else {
            emptySet()
        }
        val plan = MetaBucketing.plan(
            valuesPerTrack = perTrack,
            keyLists = keyLists,
            insideKey = onlyKey != null,
            // 家族合并（Lavf57… / reference libFLAC…）也只在组这一层生效。
            families = if (path.isEmpty()) TagRules.familyPatterns(groupName) else emptyList(),
            min = if (path.isEmpty()) TagRules.familyMin(groupName) else BUCKET_MIN_TRACKS
        )

        val buckets = plan.entries.map { node ->
            val indexes = node.tracks
            if (node.kind == MetaBucketing.Kind.FAMILY) {
                // 家族项没有"某一个键"：标题直接用家族名（Lavf / reference libFLAC）。
                MetaBucket(
                    kind = node.kind,
                    key = node.rawValue,
                    rawKey = "",
                    rawValue = node.rawValue,
                    display = node.rawValue,
                    rows = indexes.map { scoped[it] }.distinctBy { it.track.id }
                        .sortedBy { it.track.title.lowercase() }
                )
            } else {
                val first = scoped[indexes.first()].values.first { value ->
                    val valueKey = value.key.lowercase()
                    valueKey == node.rawKey &&
                        (node.kind == MetaBucketing.Kind.VARIABLE || value.raw == node.rawValue)
                }
                MetaBucket(
                    kind = node.kind,
                    key = first.key,
                    keyAlias = first.keyAlias,
                    rawKey = node.rawKey,
                    rawValue = node.rawValue,
                    display = if (node.kind == MetaBucketing.Kind.VARIABLE) "" else first.display,
                    rows = indexes.map { scoped[it] }.distinctBy { it.track.id }
                        .sortedBy { it.track.title.lowercase() }
                )
            }
        }.sortedWith(
            // 家族/变量项在前（它们是"一批歌"的入口），同类型里命中多的在前。
            compareByDescending<MetaBucket> { it.kind != MetaBucketing.Kind.VALUE }
                .thenByDescending { it.count }
                .thenBy { it.title.lowercase() }
        )

        return buckets.map { MetaListEntry.Bucket(it) } +
            plan.standalone.map { MetaListEntry.Song(scoped[it]) }
    }

    /**
     * 规则说这一组要"按专辑拆列表"（[TagRules.listBy] = album）时的一级列表。
     *
     * 用户要求：专辑曲目这一组按专辑分成一个个列表；**同一位艺术家有多张专辑时合并成一个列表**
     * （[TagRules.mergeListBy] = artist）。合并与否完全由规则文件决定，宿主不写死组名。
     */
    private fun albumEntries(rows: List<MetaTrackRow>, groupName: String): List<MetaListEntry> {
        val mergeByArtist = TagRules.mergeListBy(groupName) == "artist"
        if (!mergeByArtist) {
            return rows.groupBy { it.track.album.ifBlank { "未知专辑" } }
                .entries
                .sortedBy { it.key.lowercase() }
                .map { (album, list) -> MetaListEntry.Bucket(albumBucket(album, "", list)) }
        }
        return rows.groupBy { it.track.artist.ifBlank { "未知艺术家" } }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { (artist, list) ->
                val albums = list.map { it.track.album.ifBlank { "未知专辑" } }.distinct()
                if (albums.size > 1) {
                    // 多张专辑同一位艺术家 → 合并列表，标题用艺术家名。
                    MetaListEntry.Bucket(
                        albumBucket(
                            title = artist,
                            subtitle = albums.size.toString() + " 张专辑",
                            list = list
                        )
                    )
                } else {
                    MetaListEntry.Bucket(albumBucket(albums.first(), artist, list))
                }
            }
    }

    private fun albumBucket(title: String, subtitle: String, list: List<MetaTrackRow>): MetaBucket =
        MetaBucket(
            kind = MetaBucketing.Kind.ALBUM,
            key = title,
            keyAlias = title,
            rawKey = title,
            rawValue = title,
            display = title,
            subtitleExtra = subtitle,
            rows = list.sortedBy { it.track.title.lowercase() }
        )

    private suspend fun loadTracks(group: TagGroup): List<MetaTrackRow> {
        val all = ServiceLocator.metaTags.all()
        /*
         * **允许一首歌同时出现在多个组里**（用户明确要求）。
         *
         * 一首歌常常同时带 QQ 和酷狗的标签，两边都该列出它 —— 组与组之间不是划分，
         * 组内才是"合并项 + 单独曲目"的分层。
         */
        val mine = all.filter { it.groupName() == group.name }
        if (mine.isEmpty()) return emptyList()
        val byTrackId = mine.groupBy { it.trackId }
        val tracks = ServiceLocator.tracks.getByIds(byTrackId.keys.toList())
        return tracks
            .sortedBy { it.title.lowercase() }
            .map { track ->
                MetaTrackRow(
                    track = track,
                    group = group.name,
                    values = byTrackId[track.id].orEmpty()
                        .distinctBy { TagRules.displayKey(it.key).lowercase() + "\u0001" + it.value }
                        .map { row ->
                            val image = com.audiometa.app.media.tag.MetaImageStore.isImageValue(row.value)
                            val keyInfo = TagRules.describe(row.key)
                            val valueInfo = if (image) null else TagRules.valueInfo(row.key, row.value)
                            MetaTrackValue(
                                key = TagRules.displayKey(row.key),
                                keyAlias = keyInfo?.alias.orEmpty(),
                                rawKey = row.key,
                                // 图片值在界面上画缩略图，文字部分给个占位说明。
                                display = when {
                                    image -> "图片"
                                    row.value.isBlank() -> "（无值）"
                                    else -> valueInfo?.alias?.takeIf { it.isNotBlank() }
                                        ?: TagRules.displayValue(row.key, row.value)
                                },
                                raw = row.value,
                                imageMarker = row.value.takeIf { image }.orEmpty(),
                                keyType = keyInfo?.type.orEmpty(),
                                keyDescription = keyInfo?.description.orEmpty(),
                                valueType = valueInfo?.type.orEmpty(),
                                valueDescription = valueInfo?.description.orEmpty()
                            )
                        }
                )
            }
    }

    fun noticeShow(message: String) = notice.show(message)
    fun noticeConsume() = notice.consume()
}

/** 报告里一个标签最多列多少首曲目，避免整段文本过长。 */
private const val REPORT_TRACK_LIMIT = 30

/** 日志标签：清理失效记录这类"不影响结果但值得知道"的失败记在它下面。 */
private const val TAG = "MetaTagsViewModel"

// ---- 实体 ↔ 领域模型 ----

/** 这条标签属于哪个来源分组：平台优先，其次转码工具，都没有才算"未知标签"。 */
internal fun MetaTagEntity.groupName(): String =
    platform.ifBlank { tool }.ifBlank { TagSummary.UNKNOWN_GROUP }

internal fun MetaTagEntity.toEntry() = com.audiometa.app.domain.tagrules.TagEntry(
    key = key,
    value = value,
    platform = platform.takeIf { it.isNotBlank() },
    tool = tool.takeIf { it.isNotBlank() },
    hints = hints.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
    isStandard = false
)

private fun com.audiometa.app.domain.tagrules.TagEntry.toEntity(trackId: Long) = MetaTagEntity(
    trackId = trackId,
    key = key,
    value = value.take(2000),
    platform = platform.orEmpty(),
    tool = tool.orEmpty(),
    hints = hints.joinToString("\n")
)
