package com.audiometa.app.ui.screen.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.media.image.ArtworkKind
import com.audiometa.app.ui.component.EmptyHint
import com.audiometa.app.ui.component.TrackCover
import com.audiometa.app.ui.component.listSubtitle
import com.audiometa.app.ui.component.listTitle
import com.audiometa.app.ui.theme.cornerShape

/**
 * 搜索页。
 *
 * 按用户要求的形态：
 * - 从右侧滑入的整页，不是弹在列表上方的一个输入框（那个"很丑"）；
 * - 搜索框紧贴状态栏下方，**左上角没有返回键**，默认左滑返回；
 * - 顶部提示"搜索 标题 / 艺术家 / 专辑 / 曲风 / 歌词"；
 * - 底下是筛选按钮，顺序与提示一致，默认选中第一个「标题」；
 * - 搜索时**只检索选中的那一项**。
 */
@Composable
fun SearchScreen(
    onOpenTrack: (String) -> Unit,
    onClose: () -> Unit,
    vm: SearchViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var dragX by remember { mutableStateOf(0f) }

    // 打开就聚焦并弹键盘：省掉"还要再点一下输入框"。
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    // 进搜索页就顺手推进后台缓存，用户切到「歌词」时多半已经有数据了。
    LaunchedEffect(Unit) { vm.ensureLyricsIndex() }

    // 默认左滑返回。往回拖的时候整页跟着手走，给一点"可以滑掉"的暗示。
    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // 往右拖是"返回上一页"，左滑不做事（防止误触退出）。
                        if (dragX > 120f) onClose() else dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragX = (dragX + delta).coerceIn(0f, size.width.toFloat())
                    }
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // 搜索框就在状态栏底下；没有返回键，靠左滑退出。
            SearchField(
                value = state.query,
                hint = state.filter.hint,
                onValueChange = vm::setQuery,
                onClear = { vm.setQuery("") },
                focusRequester = focusRequester,
                onSearch = { keyboard?.hide() }
            )

            Spacer(Modifier.height(6.dp))
            SearchFilterBar(selected = state.filter) { vm.setFilter(it) }

            if (state.filter == SearchFilter.LYRICS && state.lyricsIndexing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "歌词缓存正在后台建立，结果会陆续补全" +
                        if (state.lyricsIndexMessage.isNotBlank()) "（${state.lyricsIndexMessage}）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                when {
                    // 没输入时不摆任何提示框：搜索框自己已经把话说完了，
                    // 中间再放一个"输入关键词开始搜索"的方块只是噪音。
                    !state.hasQuery -> Unit
                    state.hits.isEmpty() && state.searched -> EmptyHint(
                        when (state.filter) {
                            SearchFilter.LYRICS ->
                                "没有歌词命中「" + state.query + "」。缓存可能还没建完，稍后再试。"
                            else -> "没有匹配的" + state.filter.label + "。"
                        }
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.hits, key = { it.track.id }, contentType = { "search-hit" }) { hit ->
                            SearchResultRow(
                                hit = hit,
                                cornerStyle = state.settings.cornerStyle,
                                onClick = { onOpenTrack(hit.track.path) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** 顶部的搜索输入框。自己画而不是用 OutlinedTextField：后者自带一圈边框和标签，太占地方。 */
@Composable
private fun SearchField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "清空",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 筛选按钮：标题 / 艺术家 / 专辑 / 曲风 / 歌词。
 *
 * 用等宽的一行，不换行：五个中文短语刚好放得下，
 * 换行会让"默认选中第一个"这件事看起来像是另一种控件。
 */
@Composable
private fun SearchFilterBar(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchFilter.entries.forEach { entry ->
            val active = entry == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    cornerStyle: com.audiometa.app.domain.model.CornerStyle,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackCover(
                audioPath = hit.track.path,
                shape = cornerShape(cornerStyle, 8.dp),
                kind = ArtworkKind.COVER,
                stamp = hit.track.updatedAt,
                modifier = Modifier.size(44.dp),
                contentDescription = hit.track.title
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    // 无内嵌标签就显示文件名（全局统一的列表规则）。
                    hit.track.listTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = hit.track.listSubtitle
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 歌词命中：把那一小段原文显示出来，用户才知道为什么这条会中。
                if (hit.excerpt.isNotBlank()) {
                    Text(
                        hit.excerpt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
