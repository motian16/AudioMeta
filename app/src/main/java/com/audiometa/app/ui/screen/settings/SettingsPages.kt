package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.data.prefs.SettingsStore
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.domain.model.lyricsTypeLabel
import com.audiometa.app.ui.component.EmptyHint
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 「标签规则」页：把规则文件翻成人看得懂的样子。
 *
 * 用户要求：解析出来的规则不再只是一串 JSON —— 这一页按**分组**列出来，
 * 每组写清楚"认领键、固定键与值译名"，
 * 每个键带上别名、类型和说明。规则本身只读：它跟着「更新订阅」一起更新。
 */
@Composable
fun TagRulePage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val info = com.audiometa.app.domain.tagrules.TagRules.ruleInfo
    val imported = com.audiometa.app.domain.tagrules.TagRules.isImported
    val book = com.audiometa.app.domain.tagrules.TagRules.ruleBook

    SettingsGroup("当前规则") {
        SettingsRow(
            title = if (imported) "已装规则" else "还没有规则",
            summary = if (imported) {
                "版本 " + info.version + " · " + info.updatedAt + " · 来自" + info.source
            } else {
                "到「插件仓库」更新订阅，规则会一起装"
            }
        )
        if (imported) {
            SettingsDivider()
            SettingsRow(
                title = "识别范围",
                summary = "平台 " + info.platformCount + " 个 · 工具 " + info.toolCount +
                    " 个 · 规则内标准标签 " + info.standardKeyCount + " 个 · 键名说明 " +
                    book.keyInfoCount + " 条"
            )
            SettingsDivider()
            SettingsRow(
                title = "规则文件",
                summary = com.audiometa.app.domain.tagrules.TagRules.externalFile(context).absolutePath
            )
        }
    }

    if (!imported) {
        SettingsNote("没有规则也能识别，只是没有别名与平台分组。")
        return
    }

    SettingsNote("每个分组认领的键、固定显示键和值译名。")

    book.groups.forEach { group ->
        RuleGroupBlock(group)
    }

    SettingsGroup("全局规则", "按值类型生成别名与说明") {
        var first = true
        /** 一节：一句说明 + 每条一行。 */
        @Composable
        fun section(title: String, lines: List<Pair<String, String>>) {
            if (lines.isEmpty()) return
            if (!first) SettingsDivider()
            first = false
            SettingsRow(title, lines.size.toString() + " 条")
            lines.forEach { (head, detail) ->
                SettingsDivider()
                SettingsRow(title = head, summary = detail)
            }
        }
        section(
            "值别名",
            book.valueAliases.map { (it.alias.ifBlank { it.type }).ifBlank { "别名" } to it.key }
        )
        section(
            "值说明",
            book.valueInfos.map { it.alias.ifBlank { it.type }.ifBlank { "说明" } to it.key }
        )
        section(
            "值译名",
            book.valueLabels.map { (value, label) -> label to value }
        )
        section(
            "启发提示",
            book.hints.map { it.alias.ifBlank { "提示" } to it.key }
        )
        // 标准值：编码函数库这类"连值一起判"的键 —— 值不合标准的会被列出来。
        if (book.standardValues.isNotEmpty()) {
            if (!first) SettingsDivider()
            first = false
            SettingsRow(
                "标准值",
                "这些键只有值也对得上才算标准：" +
                    book.standardValues.map { it.first }.distinct().joinToString("、")
            )
            book.standardValues.distinctBy { it.second }.forEach { (_, pattern) ->
                SettingsDivider()
                SettingsRow(
                    title = pattern,
                    summary = "认可 " + book.standardValues
                        .filter { it.second == pattern }
                        .map { it.first }
                        .distinct()
                        .joinToString("、")
                )
            }
        }
    }
}

/** 一个分组：名字 + 类型 + 右侧固定键，然后是它认领的所有键名。 */
@Composable
private fun RuleGroupBlock(group: com.audiometa.app.domain.tagrules.RuleGroupInfo) {
    val kindLabel = when (group.kind) {
        "tool" -> "转码工具"
        "image" -> "图片"
        else -> "平台"
    }
    val summary = buildString {
        append(kindLabel)
        if (group.rightKey.isNotBlank()) append(" · 列表右侧固定显示：").append(group.rightKey)
        if (group.keyPatternCount > 0) append(" · 键名正则 ").append(group.keyPatternCount).append(" 条")
        if (group.valuePatternCount > 0) append(" · 值特征 ").append(group.valuePatternCount).append(" 条")
    }

    SettingsGroup(group.name, summary) {
        if (group.keys.isEmpty()) {
            SettingsRow("没有按键名认领的标签", "这一组只按值的特征匹配")
        }
        group.keys.forEachIndexed { index, key ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                title = if (key.pattern) "/" + key.key + "/" else key.key,
                summary = listOfNotNull(
                    key.alias.takeIf { it.isNotBlank() },
                    key.type.takeIf { it.isNotBlank() },
                    key.description.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
            )
        }
        if (group.valueLabels.isNotEmpty()) {
            SettingsDivider()
            SettingsRow("值译名", group.valueLabels.size.toString() + " 条")
            group.valueLabels.forEach { (value, label) ->
                SettingsDivider()
                SettingsRow(title = label, summary = value)
            }
        }
    }
}

/**
 * 优先级页：一张长卡片，每行一个源，右边是拖动把手。
 *
 * 用户要求**紧凑显示**：以前行高是 78dp（约普通行的 2.5 倍），一页四个源
 * 就要滚屏，看着又空又散。现在收到 46dp —— 仍是舒服的行高
 * （Material 的最小触摸目标是 48dp，这里靠内侧的拖动把手把可点区域撑到 48dp），
 * 序号、名字、把手也都收小一号，一屏能看全。
 */
@Composable
fun SourcePriorityPage(
    settings: AppSettings,
    plugins: List<PluginRecordEntity>,
    vm: SettingsViewModel,
    key: String,
    title: String,
    hint: String
) {
    val ids = settings.sourcePriority[key].orEmpty()
    val ordered = remember(ids, plugins) {
        val byId = plugins.associateBy { it.pluginId }
        val ranked = ids.mapNotNull { byId[it] }
        ranked + plugins.filter { p -> ranked.none { it.pluginId == p.pluginId } }
    }

    SettingsGroup(title, hint) {
        if (ordered.isEmpty()) {
            SettingsRow("没有可排序的源", "先到「插件」页装一个插件")
        } else {
            OrderableList(
                items = ordered,
                labelOf = { it.pluginName.ifBlank { it.pluginId } },
                keyOf = { it.pluginId },
                onMove = { from, to -> vm.moveSourcePriorityTo(key, from, to) }
            )
        }
    }
    SettingsNote("越靠上越先被问，未列的源排最后")
}

/** 歌词优先级页：源顺序 + 逐字/逐行顺序 + 翻译开关。 */
@Composable
fun LyricsPriorityPage(
    settings: AppSettings,
    plugins: List<PluginRecordEntity>,
    vm: SettingsViewModel
) {
    val ids = settings.sourcePriority[PriorityKey.LYRICS].orEmpty()
    val ordered = remember(ids, plugins) {
        val byId = plugins.associateBy { it.pluginId }
        val ranked = ids.mapNotNull { byId[it] }
        ranked + plugins.filter { p -> ranked.none { it.pluginId == p.pluginId } }
    }

    SettingsGroup("源顺序", "越靠上越先被问；匹配度更高的结果仍然会排到前面") {
        if (ordered.isEmpty()) {
            SettingsRow("没有可排序的源", "先到「插件」页装一个带歌词能力的插件")
        } else {
            OrderableList(
                items = ordered,
                labelOf = { it.pluginName.ifBlank { it.pluginId } },
                keyOf = { it.pluginId },
                onMove = { from, to -> vm.moveSourcePriorityTo(PriorityKey.LYRICS, from, to) }
            )
        }
    }

    SettingsGroup("歌词类型顺序", "同一首歌同时有逐字和逐行时，先给哪一个") {
        OrderableList(
            items = settings.lyricsPriority,
            labelOf = { lyricsTypeLabel(it) },
            keyOf = { it },
            onMove = { from, to -> vm.moveLyricsType(from, to) }
        )
    }

    SettingsGroup("翻译") {
        SettingsSwitchRow(
            title = "下载歌词时一并取翻译",
            checked = settings.downloadTranslation,
            onChecked = vm::setTranslation
        )
    }
}

/**
 * 可拖动排序的紧凑列表。
 *
 * 用和插件排序同一个库（`sh.calvin.reorderable`），
 * 写法照搬 PluginScreen：LazyColumn + rememberReorderableLazyListState
 * + ReorderableItem + longPressDraggableHandle。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun <T> OrderableList(
    items: List<T>,
    labelOf: (T) -> String,
    keyOf: (T) -> String,
    onMove: (from: Int, to: Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // 拖动过程中以本地列表为准，松手后上游数据会跟着更新并重置这一份。
    var local by remember(items) { mutableStateOf(items) }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val current = local
        val fromIndex = from.index
        if (fromIndex in current.indices) {
            val toIndex = to.index.coerceIn(0, current.lastIndex)
            if (toIndex != fromIndex) {
                local = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                onMove(fromIndex, toIndex)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        // 高度上限也一起收：四五个源不用再滚，行数多的时候滚动区域也不占满整屏。
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        itemsIndexed(local, key = { _, item -> keyOf(item) }) { index, item ->
            ReorderableItem(state = reorderState, key = keyOf(item)) { isDragging ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        labelOf(item),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            // 48dp 的可点区域（把手的图标本身只有 20dp）。
                            .size(48.dp)
                            .padding(14.dp)
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * 某个目录的**子文件夹排除页**（独立一页）。
 *
 * 用户逐条指定的行为：
 * - 点「音乐库」里的目录进新页面，顶部不再出现「音频目录」那一块；
 * - 系统返回键回**上一级**（音乐库），不是直接回设置主页；
 * - 紧凑一行：勾选框 + 目录名 + 里面第一个音频文件名；
 * - 小字不写"无子目录"，改显示该目录里排序第一位的音频文件名；
 * - 按"含所有下级的音频文件数"从多到少排序；
 * - 扫不到音频的目录默认就是排除的；
 * - **点名字只切勾选，点右侧箭头才进下一级**；
 * - 内置默认排除的目录仍然列出来、默认勾上，直接选中它就取消排除且以后不再自动勾。
 */
@Composable
fun SubfoldersPage(
    settings: AppSettings,
    folders: List<FolderEntity>,
    vm: SettingsViewModel
) {
    val root = vm.subfolderRoot
    if (root.isNullOrBlank()) {
        EmptyHint("没有选中目录。")
        return
    }
    val stats = vm.folderStats
    val byParent = remember(folders) { folders.groupBy { it.parentPath } }
    var current by rememberSaveable(root) { mutableStateOf<String?>(null) }
    val level = current ?: root

    androidx.activity.compose.BackHandler(enabled = true) {
        if (current != null) {
            val parent = folders.firstOrNull { it.path == current }?.parentPath.orEmpty()
            current = if (parent.isBlank() || parent == root) null else parent
        } else {
            vm.backFromSubfolders()
        }
    }

    fun audioCountOf(path: String): Int = stats[path]?.audioCount ?: 0

    val rows = remember(level, folders, stats, settings.excluded) {
        byParent[level].orEmpty().sortedWith(
            compareByDescending<FolderEntity> { stats[it.path]?.audioCount ?: 0 }
                .thenBy { it.name.lowercase() }
        )
    }

    SettingsGroup(
        "内置默认排除",
        SettingsStore.BUILT_IN_EXCLUDE_NAMES
            .filter { it == it.trim() && it.first().isUpperCase() }
            .joinToString("、") + " —— 首次扫描会自动勾上，取消后不再自动排除"
    ) {
        val trail = remember(level, folders) {
            val chain = mutableListOf<String>()
            var cursor = level
            while (cursor.isNotBlank() && cursor != root) {
                chain += cursor
                cursor = folders.firstOrNull { it.path == cursor }?.parentPath.orEmpty()
            }
            chain.asReversed()
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { current = null }, enabled = current != null) {
                Text(root.substringAfterLast('/').ifBlank { root }, style = MaterialTheme.typography.labelSmall)
            }
            trail.forEachIndexed { index, path ->
                Text("/", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    onClick = { current = path },
                    enabled = index != trail.lastIndex
                ) {
                    Text(
                        path.substringAfterLast('/').ifBlank { path },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (rows.isEmpty()) {
        EmptyHint("没有子文件夹")
        return
    }

    SettingsGroup("子文件夹", level) {
        rows.forEachIndexed { index, folder ->
            if (index > 0) SettingsDivider()
            val count = audioCountOf(folder.path)
            val childCount = byParent[folder.path].orEmpty().size
            val excluded = folder.path in settings.excluded || count == 0
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = excluded,
                    onCheckedChange = { checked ->
                        vm.toggleFolderExcluded(folder.path, checked)
                        if (!checked && folder.name in SettingsStore.BUILT_IN_EXCLUDE_NAMES) {
                            vm.keepAutoExclude(folder.path)
                        }
                    }
                )
                // 名字区：只切勾选，不跳层。
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            vm.toggleFolderExcluded(folder.path, !excluded)
                            if (excluded && folder.name in SettingsStore.BUILT_IN_EXCLUDE_NAMES) {
                                vm.keepAutoExclude(folder.path)
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        folder.name.ifBlank { folder.path },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None
                    )
                    val sample = stats[folder.path]?.sampleFileName.orEmpty()
                    if (sample.isNotBlank()) {
                        Text(
                            sample,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (childCount > 0) {
                    // 右侧进入区：只有这一块跳下一级。
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { current = folder.path },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowRight,
                            contentDescription = "进入子文件夹",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(44.dp))
                }
            }
        }
    }
}
