package com.audiometa.app.ui.screen.scrape

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.data.db.ScrapeLogEntity
import com.audiometa.app.data.db.SourceRecordEntity
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.ScrapeProgress
import com.audiometa.app.domain.usecase.ScrapeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 「刮削内容」每一行的三态。取值和 TriStateRow 的 state 参数一一对应。
 *
 * 这是**刮削窗口自己的临时状态**：每次打开窗口都从「关闭」开始，
 * 必须由用户手动把某一项点成「补全」或「覆盖」。
 * 设置里那套持久化的 capabilities / overwriteCapabilities **已经整个删掉了** ——
 * 它们没有任何读取方，留着只会让"设置里能改、窗口里不生效"重新出现。
 */
object ScrapeCapState {
    /** 不处理这一项。 */
    const val OFF = 0

    /** 只补文件里缺失的内容。 */
    const val FILL = 1

    /** 覆盖文件里已有的内容。 */
    const val OVERWRITE = 2
}

data class ScrapeUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val sources: List<SourceRecordEntity> = emptyList(),
    val logs: List<ScrapeLogEntity> = emptyList(),
    val availableCapabilities: Set<ScrapeCapability> = emptySet(),
    /** 窗口里每一项的三态，缺失的键按「关闭」算。只活在内存里，关掉窗口即废。 */
    val capabilityStates: Map<ScrapeCapability, Int> = emptyMap(),
    val progress: ScrapeProgress = ScrapeProgress(),
    val message: String = "",
    val pickedIds: Set<Long> = emptySet()
) {
    /** 标成「补全」或「覆盖」的能力 —— 这一次真正要刮的内容。 */
    val effectiveCapabilities: Set<ScrapeCapability>
        get() = capabilityStates.filterValues { it != ScrapeCapState.OFF }.keys

    /** 上面那些里标成「覆盖」的子集：允许改写文件里已有的内容。 */
    val overwriteCapabilities: Set<ScrapeCapability>
        get() = capabilityStates.filterValues { it == ScrapeCapState.OVERWRITE }.keys

    /**
     * 这一次要处理的曲目。
     *
     * 「范围」区块（缺少项 / 已选 / 全部）已按用户要求整个撤掉，
     * 目标规则收敛成一条：音频库里有勾选（[pickedIds] 非空）就只处理勾选的，
     * 没有勾选就处理曲库里的全部曲目。
     * 标题右边的「N 首待处理」和底部按钮上的数量都走这一个属性。
     */
    val targetIds: List<Long>
        get() = if (pickedIds.isEmpty()) {
            tracks.map { it.id }
        } else {
            tracks.filter { it.id in pickedIds }.map { it.id }
        }

    /** 这次真正要处理的曲目（和 targetIds 一一对应，给界面算"几位歌手"用）。 */
    val targetTracks: List<TrackEntity>
        get() = if (pickedIds.isEmpty()) tracks else tracks.filter { it.id in pickedIds }

    /**
     * 艺术家图是按"位"处理的，不是按"首"：合唱曲只算首位艺术家。
     * 窗口标题右侧/按钮用它，避免用户以为选 30 首歌就要搜 30 次。
     */
    val targetArtistCount: Int
        get() = targetTracks
            .mapNotNull { com.audiometa.app.domain.model.ArtistNames.of(it.artist).firstOrNull() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .size
}

class ScrapeViewModel(app: Application) : AndroidViewModel(app) {
    /** 窗口的三态。刻意不放 DataStore：它只属于"这一次打开的窗口"。 */
    private val capabilityStates = MutableStateFlow<Map<ScrapeCapability, Int>>(emptyMap())
    private val logs = MutableStateFlow<List<ScrapeLogEntity>>(emptyList())
    private val message = MutableStateFlow("")

    val state = combine(
        ServiceLocator.tracks.observeAll(),
        ServiceLocator.pluginRepo.observeSources(),
        capabilityStates,
        logs,
        message,
        ScrapeService.progress,
        ServiceLocator.selection.ids
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tracks = values[0] as List<TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val sources = values[1] as List<SourceRecordEntity>
        @Suppress("UNCHECKED_CAST")
        val caps = values[2] as Map<ScrapeCapability, Int>
        @Suppress("UNCHECKED_CAST")
        val recentLogs = values[3] as List<ScrapeLogEntity>
        val note = values[4] as String
        val progress = values[5] as ScrapeProgress
        @Suppress("UNCHECKED_CAST")
        val picked = values[6] as Set<Long>
        val available = sources.filter { it.enabled }.flatMap { it.capabilitySet() }.toSet()
        ScrapeUiState(
            tracks = tracks,
            sources = sources,
            logs = recentLogs,
            availableCapabilities = available,
            capabilityStates = caps,
            progress = progress,
            message = note,
            pickedIds = picked.intersect(tracks.map { it.id }.toSet())
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScrapeUiState())

    init {
        refreshLogs()
    }

    /**
     * 把三态恢复成「全部关闭」。
     *
     * ScrapeSheet 默认用的 `viewModel()` 挂在 Activity 上，会跨窗口复用，
     * 所以窗口每次进入组合时都要调一次，否则上一次的点选会留到下一次打开的窗口里。
     */
    fun resetCapabilities() {
        capabilityStates.value = emptyMap()
    }

    /** 设置某一项的三态：[ScrapeCapState.OFF] / [ScrapeCapState.FILL] / [ScrapeCapState.OVERWRITE]。 */
    fun setCapabilityState(capability: ScrapeCapability, value: Int) {
        capabilityStates.value = capabilityStates.value.toMutableMap().apply {
            // 艺术家图和逐首项互斥：开一边就关另一边，界面上其余项同时灰掉。
            if (value > ScrapeCapState.OFF) {
                if (capability == ScrapeCapability.ARTIST_IMAGE) {
                    clear()
                } else {
                    remove(ScrapeCapability.ARTIST_IMAGE)
                }
                put(capability, value)
            } else {
                remove(capability)
            }
        }
    }

    fun start(ids: List<Long>) {
        val current = state.value
        // 能力来自窗口里的临时三态，不再从 DataStore 的 settings 推导：
        // 标成「补全」或「覆盖」的都会刮，其中标成「覆盖」的允许改写已有内容。
        val caps = current.effectiveCapabilities
        val overwrite = current.overwriteCapabilities
        if (ids.isEmpty()) {
            message.value = "没有可刮削的曲目"
            return
        }
        if (caps.isEmpty()) {
            message.value = "还没有选择刮削内容。先在「刮削内容」里选一项。"
            return
        }
        val overwriteNote = if (overwrite.isEmpty()) "" else "，其中 " + overwrite.size + " 项覆盖"
        message.value = "已提交 " + ids.size + " 首，能力 " + caps.size + " 项" + overwriteNote
        ScrapeService.start(getApplication(), ids, caps, overwrite = overwrite)
    }

    fun cancel() {
        ScrapeService.cancel(getApplication())
        message.value = "已请求取消"
    }

    fun refreshLogs() {
        viewModelScope.launch {
            logs.value = ServiceLocator.logs.recent(80)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            ServiceLocator.logs.clear()
            logs.value = emptyList()
        }
    }
}

private fun SourceRecordEntity.capabilitySet(): Set<ScrapeCapability> = runCatching {
    val json = JSONObject(capabilitiesJson.ifBlank { "{}" })
    val declared = json.keys().asSequence()
        .filter { json.optBoolean(it, false) }
        .mapNotNull { ScrapeCapability.fromKey(it) }
        .toMutableSet()
    // 曲风走「按歌搜」，声明了元信息的源同样能提供曲风（和 plugin manifest 同一规则）。
    if (ScrapeCapability.TRACK_META in declared) declared += ScrapeCapability.GENRE
    declared
}.getOrDefault(emptySet())
