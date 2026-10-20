package com.audiometa.app.ui.screen.scrape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.usecase.CandidateSearch
import com.audiometa.app.domain.usecase.ScrapeCandidate
import com.audiometa.app.domain.usecase.pinPickedImageUrl
import com.audiometa.app.ui.component.EmptyHint
import com.audiometa.app.ui.component.TrackCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单首刮削的结果选择页。
 *
 * 不再点一下就直接刮：先把各源的搜索结果列出来，用户选中哪一条就用哪一条。
 * 批量刮削才走自动取最高分。
 *
 * 封面和艺术家图是「选图」，用一行三个的网格，角标显示平台与分辨率；
 * 歌词和元信息是「选文本」，仍然用列表。
 */
@Composable
fun ScrapePickerScreen(
    trackId: Long,
    capability: ScrapeCapability,
    audioPath: String? = null,
    stamp: Long = 0L,
    onBack: () -> Unit,
    onPick: (ScrapeCandidate) -> Unit,
    /** 歌词是另一条数据通路（候选本身带正文），单独一个回调。 */
    onPickLyrics: (com.audiometa.app.domain.usecase.LyricsCandidate) -> Unit = {}
) {
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val candidates by produceState<List<ScrapeCandidate>?>(initialValue = null, trackId, capability, reload) {
        loading = true
        value = withContext(Dispatchers.IO) {
            runCatching { CandidateSearch().search(trackId, capability) }.getOrDefault(emptyList())
        }
        loading = false
    }
    val lyrics by produceState<com.audiometa.app.domain.usecase.LyricsSearchResult>(
        initialValue = com.audiometa.app.domain.usecase.LyricsSearchResult(),
        trackId,
        capability,
        reload
    ) {
        if (capability != ScrapeCapability.LYRICS) return@produceState
        // 边搜边报：谁先回来谁先上屏，不必等最慢的那个源（Apple 首次要抓 token）。
        runCatching {
            com.audiometa.app.domain.usecase.LyricsSearch().searchStream(trackId).collect { value = it }
        }.onFailure {
            value = com.audiometa.app.domain.usecase.LyricsSearchResult(finished = true)
        }
    }

    // 「选图」：专辑图和艺术家图；元信息和歌词是「选文本」。
    val isArtwork = capability == ScrapeCapability.ARTIST_IMAGE || capability == ScrapeCapability.COVER
    val isLyrics = capability == ScrapeCapability.LYRICS

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("选择搜索结果", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        isArtwork -> capability.label + " · 按匹配度排序"
                        isLyrics -> capability.label + " · 逐字+翻译优先"
                        else -> capability.label + " · 按匹配度排序"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { reload++ }) { Text("重搜") }
        }

        if (isLyrics) {
            val result = lyrics
            when {
                // 已经有结果就先上屏（后台还有源没回来时，列表底部给一行"还在搜索"）。
                result.candidates.isNotEmpty() -> LyricsResultList(
                    items = result.candidates,
                    audioPath = audioPath,
                    stamp = stamp,
                    searching = !result.finished,
                    onPick = onPickLyrics
                )
                // 还在搜：显示进度而不是空白转圈。
                !result.finished -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text(
                        if (result.sources.isEmpty()) {
                            "正在准备歌词源…"
                        } else {
                            "正在搜索 " + result.sources.size + " 个歌词源：\n" +
                                result.sources.joinToString("、")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                // 说清到底是哪一种"没有"：没源 / 源报错 / 源问了但没这首。
                result.sources.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                    EmptyHint(
                        "没有可用的歌词源。\n" +
                            "已装的插件里没有一个声明了 getLyrics 能力 —— " +
                            "到「设置 - 插件」订阅一次（元信息源自带歌词能力），" +
                            "或在「设置 - 插件 - 元信息源」里把带歌词能力的源打开。"
                    )
                }
                result.failures.isNotEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                    EmptyHint(
                        result.sources.size.toString() + " 个歌词源都没能返回结果：\n" +
                            result.failures.joinToString("\n") { (name, reason) ->
                                "· " + name + "：" + reason
                            }
                    )
                }
                else -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                    EmptyHint(
                        "问过 " + result.sources.size + " 个歌词源（" +
                            result.sources.joinToString("、") + "），这首歌都还没有歌词。"
                    )
                }
            }
            return@Column
        }

        val list = candidates
        // 用户要求：结果一律**按匹配度从高到低**排。服务端已经排过一次，
        // 这里再排一次是防御性的 —— 流式/合并的结果到界面前必须保证顺序一致。
        val sorted = list?.sortedByDescending { it.score }
        val icons = rememberSourceIcons(sorted?.map { it.sourceId }.orEmpty())
        when {
            loading && list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sorted.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                EmptyHint("没有搜到结果。可能是这个能力没有可用插件源，或关键词不匹配。")
            }
            // 图片是"选图"：点哪张，就把**网格里显示的那张**的地址钉进候选（见 [pinPickedImageUrl]），
            // 再交给上层。服务端会优先用这个地址 —— 否则它会拿候选 JSON 自己再解析一遍地址，
            // 解析结果和网格里显示的这张不一致时，用户挑的这张就被悄悄换成了别的（自动搜索排名第一那张）。
            isArtwork -> ArtworkResultGrid(
                sorted,
                icons = icons,
                onPick = { candidate -> onPick(candidate.pinPickedImageUrl()) },
                modifier = Modifier.weight(1f)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(sorted, key = { i, c -> c.sourceId + "#" + i + "#" + c.title }) { _, candidate ->
                    CandidateRow(candidate, icons[candidate.sourceId], audioPath, stamp) { onPick(candidate) }
                }
            }
        }
    }
}

/**
 * 歌词结果列表。
 *
 * 用户指定的排版：**左侧歌曲封面，然后是标题、艺术家、专辑，最后是歌词类型标签**
 * （「逐字 / 翻译」「逐行 / 翻译」）。一条候选 = 一个源的一种歌词类型，
 * 所以同一个源可能同时出现"逐字"和"逐行"两行。
 */
@Composable
private fun LyricsResultList(
    items: List<com.audiometa.app.domain.usecase.LyricsCandidate>,
    audioPath: String?,
    stamp: Long,
    onPick: (com.audiometa.app.domain.usecase.LyricsCandidate) -> Unit,
    /** 后台还有源没回来：列表底部给一行小字（不是转圈，不挡已到的结果）。 */
    searching: Boolean = false
) {
    // 歌词是按源"边搜边到"的，到达顺序和好坏无关：这里统一按匹配度重排。
    val sorted = remember(items) { items.sortedByDescending { it.score } }
    val icons = rememberSourceIcons(sorted.map { it.sourceId })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(
            sorted,
            key = { i, c -> c.sourceId + "#" + c.type + "#" + i }
        ) { _, candidate ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPick(candidate) }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackCover(
                        audioPath = audioPath,
                        shape = RoundedCornerShape(8.dp),
                        stamp = stamp,
                        modifier = Modifier.size(56.dp),
                        contentDescription = candidate.title
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            candidate.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val line = listOf(candidate.artist, candidate.album)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (line.isNotBlank()) {
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 源用图标标识（用户要求），匹配度写在图标右边。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceMark(icons[candidate.sourceId], size = 13.dp)
                            Text(
                                (candidate.score * 100).toInt().toString() + "%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 5.dp)
                            )
                        }
                    }
                    // 歌词类型标签：逐字 / 翻译、逐行 / 翻译。
                    Text(
                        candidate.typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        if (searching) {
            item(key = "lyrics-searching") {
                Text(
                    "还在搜索其它源…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ScrapeCandidate,
    iconPath: String?,
    audioPath: String?,
    stamp: Long,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackCover(
                audioPath = audioPath,
                shape = RoundedCornerShape(8.dp),
                stamp = stamp,
                modifier = Modifier.size(56.dp),
                contentDescription = candidate.title
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    candidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val line = listOf(candidate.artist, candidate.album)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 源标识用**图标**，不再写「网易云音乐」这串文字（用户要求）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceMark(iconPath, size = 13.dp)
                    if (candidate.subtitle.isNotBlank()) {
                        Text(
                            candidate.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
            // 匹配分数：一眼看出哪条最像。
            Text(
                (candidate.score * 100).toInt().toString() + "%",
                style = MaterialTheme.typography.labelLarge,
                color = if (candidate.score >= 0.7) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
