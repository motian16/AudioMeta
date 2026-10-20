package com.audiometa.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        // 行高收紧：原来上下 24dp 的留白把设置页撑得很长。10/2 是压缩和可读性的平衡点。
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
fun StatusLine(label: String, granted: Boolean) {
    Text(
        text = "$label：${if (granted) "已授予" else "未授予"}",
        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 进度条 + 说明文字。
 *
 * 刻意不用卡片：它是页面里的一段普通内容，不是浮在界面上的提示横幅。
 * 间距压紧到 3dp —— 刮削窗口里它是常驻内容，多出来的留白会挤掉下面的设置项。
 */
@Composable
fun ProgressCard(title: String, detail: String, progress: Float?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 一行三态选择：关闭 / 补全 / 覆盖。
 *
 * 用自己画的芯片而不是 Material 的 FilterChip：FilterChip 自带 32dp 高度和较大的
 * 内边距，三个排一行会把行高顶到 48dp 以上。
 *
 * 但也不要压得太狠——上一版压到 26dp 之后按钮又小又挤。
 * 现在取中间值：垂直内边距 6dp + labelMedium，约 34dp，
 * 有足够点击面积（人手指约 40dp 舒适区，配合横向 padding 实际可点区域更大）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TriStateRow(
    label: String,
    /** 0=关闭，1=补全，2=覆盖。 */
    state: Int,
    available: Boolean,
    /** 额外禁用（例如艺术家图开启时，逐首项全部灰掉）。 */
    enabled: Boolean = true,
    onState: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (available && enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        listOf(0 to "关闭", 1 to "补全", 2 to "覆盖").forEach { (value, text) ->
            CompactChip(
                label = text,
                selected = state == value,
                enabled = available && enabled,
                onClick = { onState(value) }
            )
        }
    }
}

/**
 * 紧凑芯片。
 *
 * 高度靠 6dp 垂直内边距 + labelMedium 撑出来（约 34dp）：
 * 比 FilterChip 矮一点、够紧凑，又不至于小到难点。
 */
@Composable
fun CompactChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        selected -> accent.copy(alpha = 0.20f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> accent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val pressed by interaction.collectIsPressedAsState()
    // 按下时轻微缩小：比只靠水波纹更像"真的按到了"，又不会晃动布局。
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.tween(90),
        label = "chipPress"
    )
    val background by androidx.compose.animation.animateColorAsState(
        targetValue = container,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "chipColor"
    )
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * 多选芯片。比一行一个开关紧凑得多，适合「四项能力 × 开/覆盖」这种组合。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ToggleChips(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    enabled: (String) -> Boolean = { true },
    onToggle: (String, Boolean) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (key, label) ->
            val on = key in selected
            val usable = enabled(key)
            androidx.compose.material3.FilterChip(
                selected = on,
                onClick = { if (usable) onToggle(key, !on) },
                enabled = usable,
                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, struck: Boolean = false, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(checked) { onChecked(it) }
            .padding(vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        if (label.isNotBlank()) {
            Text(
                text = label,
                modifier = Modifier.padding(start = 8.dp),
                textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
                // 允许两行，长句不再被截断成看不懂的半句。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 选项芯片。用 FlowRow 自动换行，避免窄屏上横向溢出或被裁掉。
 *
 * 同样换成自绘的紧凑芯片：ChoiceChips 在刮削窗口里要出现三次，
 * 每次都按 FilterChip 的高度算，光这三行就是 150dp 以上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (key, label) ->
            Box(Modifier.padding(end = 0.dp)) {
                CompactChip(label = label, selected = selected == key) { onSelect(key) }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(text, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 量出一组标签里最宽的那个需要多少宽度。
 *
 * 详情页的标签列以前是写死的 44dp / 56dp，于是"修改时间"（4 字）被裁掉最后一个"间"、
 * "专辑艺术家"（5 字）被裁掉最后一个"家" —— 用户两次报过同一类问题。
 * 写死宽度在系统字体放大时还会再犯，所以直接按当前字体量一遍：
 * 量出来的宽度既够放下全部标签，又不会白白留一大截空隙。
 *
 * [keySource] 用来做 remember 的键：调用方每次重组都会新建 List，
 * 拿 List 本身当键会导致每次都重新测量。
 */
@Composable
fun rememberLabelWidth(
    labels: List<String>,
    style: androidx.compose.ui.text.TextStyle
): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val key = labels.joinToString("\u0001")
    return androidx.compose.runtime.remember(key, style, density) {
        val widest = labels.maxOfOrNull { label ->
            measurer.measure(text = label, style = style).size.width
        } ?: 0
        with(density) { (widest + 2).toDp() }
    }
}
