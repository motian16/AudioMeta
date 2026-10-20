package com.audiometa.app.ui.screen.scrape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.ui.component.ProgressCard
import com.audiometa.app.ui.component.SectionTitle
import com.audiometa.app.ui.component.TriStateRow

/**
 * 刮削窗口：从屏幕底部上拉出现的一个面板。
 *
 * 以前它是一个独立的底部 Tab 整页，内容稀稀拉拉铺满一屏，留白很多。
 * 现在收成一个底部窗口：
 * - 默认占屏幕约一半，够放下全部设置；
 * - 拖动顶部可以拉高到全屏（进度和日志多的时候有用）；
 * - 「开始刮削」固定在窗口底部，不用下滑去找；
 * - 关闭方式：点窗口外、往下拖过一半、按系统返回。
 *
 * 按用户要求，「范围」和「刮削方式」两块已经撤掉：
 * - 「刮削内容」的三态是**这个窗口自己的临时状态**，每次打开都是「全部关闭」，
 *   要补哪一项得自己点成「补全」或「覆盖」（不写 DataStore）；
 * - 目标曲目只有一条规则 —— 音频库里有勾选就只处理勾选的，没勾选就处理整个曲库。
 */
@Composable
fun ScrapeSheet(
    onDismiss: () -> Unit,
    vm: ScrapeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.progress.finished, state.progress.done) {
        if (state.progress.finished || state.progress.done > 0) vm.refreshLogs()
    }
    // 窗口每次出现都从「全部关闭」开始。
    // viewModel() 取到的是 Activity 上的同一个实例（跨窗口复用），
    // 不重置的话上一次的点选会留到下一次打开的窗口里。
    LaunchedEffect(Unit) { vm.resetCapabilities() }

    com.audiometa.app.ui.component.BottomSheetScaffold(
        onDismiss = onDismiss,
        /*
         * 用户报"点击后弹出的窗口留白太多"。
         *
         * 这个窗口的内容就是标题 + 三项「刮削内容」+ 一句说明 + 底部按钮，
         * 大约 0.36 屏高。以前固定开 0.62，下面空出近四分之一屏。
         * 现在按内容给到 0.42，并且把"往下拖多少关闭"单独收到 0.26 ——
         * 0.42 刚好压在原来那个公共下沿上，不单独改的话窗口一拖就关。
         */
        initialFraction = 0.36f,
        hintFraction = 0.6f,
        dismissFraction = 0.22f
    ) { dragHandle ->
        Column(Modifier.fillMaxSize()) {
            // 标题栏挂拖动：抓标题往上提也能拉高窗口。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dragHandle.run { Modifier.draggable() })
                    .padding(horizontal = 16.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "刮削",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                val artistOnly = state.effectiveCapabilities ==
                    setOf(ScrapeCapability.ARTIST_IMAGE)
                Text(
                    if (artistOnly) {
                        state.targetArtistCount.toString() + " 位歌手"
                    } else {
                        state.targetIds.size.toString() + " 首待处理"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 内容区：自己滚动，不留无意义的空白。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                ScrapeSettings(state, vm)

                if (state.progress.running || state.progress.finished) {
                    val ratio = if (state.progress.total == 0) null
                    else state.progress.done.toFloat() / state.progress.total
                    ProgressCard(
                        title = when {
                            state.progress.running -> "正在刮削"
                            state.progress.cancelled -> "已取消"
                            else -> "刮削结束"
                        },
                        // 只留一行统计：原来把 message 和当前曲名也拼进来，会撑成三行。
                        detail = "成功 " + state.progress.success +
                            " · 跳过 " + state.progress.skipped +
                            " · 失败 " + state.progress.failed,
                        progress = if (state.progress.running) ratio else 1f
                    )
                }
                if (state.message.isNotBlank()) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 开始按钮固定在窗口底部。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { vm.start(state.targetIds) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.progress.running &&
                        state.targetIds.isNotEmpty() &&
                        state.effectiveCapabilities.isNotEmpty()
                ) {
                    val artistOnly = state.effectiveCapabilities ==
                        setOf(ScrapeCapability.ARTIST_IMAGE)
                    Text(
                        if (artistOnly) {
                            "开始刮削（" + state.targetArtistCount + " 位歌手）"
                        } else {
                            "开始刮削（" + state.targetIds.size + " 首）"
                        }
                    )
                }
                OutlinedButton(onClick = vm::cancel, enabled = state.progress.running) { Text("取消") }
            }
        }
    }
}

/**
 * 「刮削内容」三态行。
 *
 * 没有「范围」和「刮削方式」了：每一项各自决定处理不处理、补全还是覆盖。
 * 没有启用源支持的能力显示「（无源）」并且三个芯片都点不动。
 *
 * **「元信息」已按用户要求整块移除**：批量刮削只保留 专辑图 / 艺术家图 / 曲风 / 歌词
 * 四项。整块元信息（日期、语言、版权、注释…）不再由 App 批量抓取；
 * 需要补某一个字段就在歌曲详情页里手动编辑（那是唯一能改标题/艺术家/专辑的地方）。
 */
@Composable
private fun ScrapeSettings(state: ScrapeUiState, vm: ScrapeViewModel) {
    val artistOn = (state.capabilityStates[ScrapeCapability.ARTIST_IMAGE] ?: ScrapeCapState.OFF) !=
        ScrapeCapState.OFF
    val otherOn = PER_TRACK_CAPABILITIES.any {
        (state.capabilityStates[it] ?: ScrapeCapState.OFF) != ScrapeCapState.OFF
    }
    // 标题已经是「刮削」，这里不再重复写「刮削内容」。
    PER_TRACK_CAPABILITIES.forEach { capability ->
        ScrapeCapabilityRow(state, vm, capability, enabled = !artistOn)
    }
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
    // 艺术家图按"歌手"处理，单独一行；它和其他项二选一。
    ScrapeCapabilityRow(
        state = state,
        vm = vm,
        capability = ScrapeCapability.ARTIST_IMAGE,
        label = "艺术家图（按歌手）",
        enabled = !otherOn
    )
    Text(
        "补全只补缺，覆盖会改写；有勾选时只处理勾选曲目。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun ScrapeCapabilityRow(
    state: ScrapeUiState,
    vm: ScrapeViewModel,
    capability: ScrapeCapability,
    label: String = capability.label,
    enabled: Boolean = true
) {
    val offered = capability in state.availableCapabilities
    TriStateRow(
        label = if (offered) label else label + "（无源）",
        state = state.capabilityStates[capability] ?: ScrapeCapState.OFF,
        available = offered,
        enabled = enabled,
        onState = { value -> vm.setCapabilityState(capability, value) }
    )
}

/**
 * 批量刮削面板里出现的四项（顺序固定）。
 *
 * 艺术家图单列在最后：它按歌手处理、会写入该歌手全部歌曲，和逐首的封面/曲风/歌词
 * 不是同一种范围。元信息整块仍不在这里（详情页手动编辑）。
 */
private val PER_TRACK_CAPABILITIES: List<ScrapeCapability> = listOf(
    ScrapeCapability.COVER,
    ScrapeCapability.GENRE,
    ScrapeCapability.LYRICS
)
