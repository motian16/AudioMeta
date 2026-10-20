package com.audiometa.app.ui.screen.plugin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.db.SourceRecordEntity
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.plugin.model.ConfigField
import com.audiometa.app.plugin.model.ConfigFieldType
import com.audiometa.app.ui.component.CheckRow
import com.audiometa.app.ui.component.ChoiceChips
import com.audiometa.app.ui.component.EmptyHint
import com.audiometa.app.ui.component.SectionTitle

@Composable
fun PluginScreen(
    /** 外层的返回信号：每 +1 表示按了一次返回。 */
    backSignal: Int = 0,
    /** 告诉外层"这次返回我已经处理掉了"。 */
    onBackHandled: () -> Unit = {},
    /** 告诉外层"我这里没有上一层级了"。 */
    onNeedParent: () -> Boolean = { false },
    /** 只显示声明了该能力的插件；null=全部（元信息源）。 */
    capabilityFilter: ScrapeCapability? = ScrapeCapability.TRACK_META,
    /** 页面标题（设置里的二级页用）。 */
    title: String = "插件",
    vm: PluginViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<PluginRecordEntity?>(null) }
    // 每个插件自己支持的设置项标题（不显示具体值），紧凑列在卡片第三行。
    val configTitles by androidx.compose.runtime.produceState<Map<String, List<String>>>(
        emptyMap(), state.plugins
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            state.plugins.associate { plugin ->
                val titles = runCatching {
                    com.audiometa.app.di.ServiceLocator.plugins.load(plugin.pluginId)
                        ?.manifest
                        ?.localized(java.util.Locale.getDefault().toLanguageTag().ifBlank { "en" })
                        ?.configFields
                        ?.filter { !it.isDisplayOnly && it.title.isNotBlank() }
                        ?.map { it.title }
                        .orEmpty()
                }.getOrDefault(emptyList())
                plugin.pluginId to titles
            }
        }
    }

    // 插件页是单层列表，没有内部层级：返回直接交给外层（回上一次看的 Tab）。
    var handledSignal by remember { mutableStateOf(backSignal) }
    androidx.compose.runtime.LaunchedEffect(backSignal) {
        if (backSignal == handledSignal) return@LaunchedEffect
        handledSignal = backSignal
        onNeedParent()
        onBackHandled()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri)
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val pluginId = pendingExport
        pendingExport = null
        if (uri != null && pluginId != null) vm.export(pluginId, uri)
    }

    // 列表占满剩余空间，导入按钮固定在底部：它是这页唯一的入口，
    // 不该因为列表滚动或提示文案出现/消失而上下跳。
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ReorderablePluginList(
                plugins = state.orderedPluginsFor(capabilityFilter),
                sources = state.sources,
                busy = state.busy,
                header = title,
                configTitles = configTitles,
                onOrderChange = { ids ->
                    // 只重排当前页可见的插件；其余插件保持原来的相对顺序。
                    val all = state.plugins.map { it.pluginId }
                    vm.setPluginOrder(ids + all.filter { it !in ids })
                },
                onEnabled = { id, enabled -> vm.setPluginEnabled(id, enabled) },
                onOptions = { id -> vm.openOptions(id) },
                onExport = { plugin ->
                    pendingExport = plugin.pluginId
                    exporter.launch(plugin.pluginId + "-" + plugin.pluginVersion + ".zip")
                },
                onDelete = { plugin -> pendingDelete = plugin }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text(if (state.busy) "正在安装…" else "导入插件 zip") }
        }
    }

    // 顶部不再常驻提示小字：安装/卸载/导出的结论只在这里出现一次，关掉就没了。
    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            title = { Text(notice.title) },
            text = { Text(notice.detail) },
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("知道了") } }
        )
    }

    state.editing?.let { editor ->
        ConfigDialog(
            editor = editor,
            onChange = vm::updateOption,
            onDismiss = vm::closeOptions,
            onSave = vm::saveOptions
        )
    }
    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("卸载 " + plugin.pluginName) },
            text = { Text("会删除安装目录和插件记录。已经写入音频文件的标签不会回滚。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.uninstall(plugin.pluginId)
                    pendingDelete = null
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReorderablePluginList(
    plugins: List<PluginRecordEntity>,
    sources: List<SourceRecordEntity>,
    busy: Boolean,
    header: String = "",
    configTitles: Map<String, List<String>> = emptyMap(),
    onOrderChange: (List<String>) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onOptions: (String) -> Unit,
    onExport: (PluginRecordEntity) -> Unit,
    onDelete: (PluginRecordEntity) -> Unit
) {
    // 和 Lyrico 示例客户端同一套写法：LazyColumn + rememberReorderableLazyListState
    // + ReorderableItem + longPressDraggableHandle，长按卡片任意位置即可拖动。
    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var localOrder by remember(plugins) { mutableStateOf(plugins) }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 拖动过程中列表可能被重组，索引越界会直接崩，这里全部夹紧一次。
        val current = localOrder
        val fromIndex = from.index
        if (fromIndex in current.indices) {
            val toIndex = to.index.coerceIn(0, current.lastIndex)
            if (toIndex != fromIndex) {
                localOrder = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 这里刻意不放「处理中…」「已安装 x 个插件…」这类提示行：
        // 它们会把卡片往下推、还会随提示出现/消失让整个列表跳动。
        if (header.isNotBlank()) {
            item(key = "header") {
                Text(
                    header,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 2.dp)
                )
            }
        }
        if (plugins.isEmpty()) {
            item(key = "empty") {
                EmptyHint("还没有插件。点下面的按钮导入 zip，或先更新订阅。")
            }
        }
        itemsIndexed(localOrder, key = { _, plugin -> plugin.pluginId }) { _, plugin ->
            ReorderableItem(state = reorderState, key = plugin.pluginId) { isDragging ->
                // 拖起时抬升并加阴影，同时让其它项平滑让位。
                // 少了 animateItemPlacement 就会「脱手」——手指还在拖，项却瞬间跳位。
                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    label = "pluginCardElevation"
                )
                val interactionSource = remember { MutableInteractionSource() }
                PluginCard(
                    plugin = plugin,
                    source = sources.firstOrNull { it.pluginId == plugin.pluginId },
                    busy = busy,
                    elevation = elevation,
                    configTitles = configTitles[plugin.pluginId].orEmpty(),
                    onEnabled = { onEnabled(plugin.pluginId, it) },
                    onOptions = { onOptions(plugin.pluginId) },
                    onExport = { onExport(plugin) },
                    onDelete = { onDelete(plugin) },
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = { onOrderChange(localOrder.map { it.pluginId }) },
                        interactionSource = interactionSource
                    ),
                    modifier = Modifier
                        .animateItemPlacement()
                        .zIndex(if (isDragging) 1f else 0f)
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginRecordEntity,
    source: SourceRecordEntity?,
    busy: Boolean,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    configTitles: List<String> = emptyList(),
    onEnabled: (Boolean) -> Unit,
    onOptions: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    // 紧凑两行卡片：一屏至少能放下 8 个。
    // 第一行：名称 · 版本 + 开关；第二行：能力摘要 + 操作图标。
    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = elevation)) {
        Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.pluginName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    " · " + plugin.pluginVersion,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source != null && source.failStreak > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = plugin.enabled, onCheckedChange = onEnabled, enabled = !busy)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val caps = source?.declaredCapabilities().orEmpty()
                Text(
                    if (caps.isEmpty()) "未声明能力" else caps.joinToString("、") { it.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOptions, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Settings, contentDescription = "配置", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onExport, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "导出", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "卸载", modifier = Modifier.size(18.dp))
                }
                Box(
                    modifier = dragHandleModifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = "拖动排序",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 插件自己支持的设置项：只列名称，具体值点上面的齿轮改。
            val shownConfig = configTitles.ifEmpty {
                runCatching {
                    org.json.JSONObject(source?.configJson.orEmpty().ifBlank { "{}" })
                        .keys().asSequence().toList()
                }.getOrDefault(emptyList())
            }
            if (shownConfig.isNotEmpty()) {
                Text(
                    "设置：" + shownConfig.take(6).joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfigDialog(
    editor: PluginConfigEditor,
    onChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val visible = editor.fields.filter { isVisible(it, editor.values) }
    val groups = visible.groupBy { it.group }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "配置只保存在本机，不会写回导出的插件包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (editor.error.isNotBlank()) {
                    Text(
                        editor.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (visible.isEmpty()) {
                    Text("这个插件没有可配置项。")
                }
                groups.forEach { (group, fields) ->
                    if (group.isNotBlank()) SectionTitle(group)
                    fields.forEach { field ->
                        ConfigFieldView(field, editor.values[field.key].orEmpty(), onChange)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfigFieldView(
    field: ConfigField,
    value: String,
    onChange: (String, String) -> Unit
) {
    when (field.type) {
        ConfigFieldType.MARKDOWN -> {
            Text(
                plainTextFromMarkdown(field.summary.ifBlank { field.defaultValue }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ConfigFieldType.SWITCH -> {
            CheckRow(
                label = field.title,
                checked = value.equals("true", true) || value == "1",
                onChecked = { onChange(field.key, it.toString()) }
            )
            if (field.summary.isNotBlank()) FieldSummary(field.summary)
        }
        ConfigFieldType.DROPDOWN -> {
            Text(field.title, fontWeight = FontWeight.Medium)
            if (field.summary.isNotBlank()) FieldSummary(field.summary)
            ChoiceChips(
                options = field.options.map { it.value to it.label.ifBlank { it.value } },
                selected = value,
                onSelect = { onChange(field.key, it) }
            )
        }
        ConfigFieldType.NUMBER, ConfigFieldType.TEXT, ConfigFieldType.PASSWORD -> {
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(field.key, it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (field.required) field.title + " *" else field.title) },
                supportingText = if (field.summary.isBlank()) null else {
                    { Text(field.summary) }
                },
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
            )
        }
    }
}

@Composable
private fun FieldSummary(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 插件里的说明文字常带 Markdown（标题、粗体、链接、列表），
 * 原样显示会是「### 标题 **加粗** [文字](链接)」这种，很难读。这里转成直观纯文本。
 */
private fun plainTextFromMarkdown(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    val linkPattern = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
    val out = StringBuilder()
    text.split('\n').forEach { line ->
        var value = line.trim().trimStart('#').trim()
        if (value.startsWith("- ") || value.startsWith("* ") || value.startsWith("+ ")) {
            value = "• " + value.substring(2).trim()
        }
        value = linkPattern.replace(value) { match ->
            val label = match.groupValues[1]
            val url = match.groupValues[2]
            if (label == url) url else label + "（" + url + "）"
        }
        value = value.replace("**", "").replace("__", "").replace("`", "")
        if (value.isNotBlank()) out.append(value).append('\n')
    }
    return out.toString().trim()
}
