package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置页的公共外观。
 *
 * 之前每个设置页都是各写各的：有的用 Card、有的直接堆 Text，行高、圆角、
 * 说明文字的位置全不一样，看起来像"毛坯房"。这里把外观收敛成一套零件，
 * 所有设置页只用这几个积木搭，样式自然就统一了。
 *
 * 视觉规则（跟着应用整体的 [com.audiometa.app.domain.model.CornerStyle] 走）：
 * - **分组**：一个 [SettingsGroup] 是一张卡片，标题在卡片上方而不是卡片里 ——
 *   这样卡片里的行可以左右顶满，看着更整齐；
 * - **行**：统一 [SettingsRow]，左侧标题 + 可选说明，右侧是控件或数值；
 * - **说明文字**：只在需要解释"为什么"的地方出现，放在行下面 labelSmall，
 *   不做成第二行标题。
 */

/**
 * 分组标题 + 一张卡片。所有设置内容都应该套在它里面。
 *
 * [trailing] 放在**标题行右侧**（例如「网络与镜像」那个「测试」按钮）：
 * 它是这一组的动作，不属于卡片里的任何一行，所以不占卡片高度。
 *
 * [title] 留空表示**不要标题行**，只剩卡片 —— 给"一页里要排好几张卡、
 * 每张都加小标题反而啰嗦"的页面用（网络遥控页就是这种）。
 */
@Composable
fun SettingsGroup(
    title: String = "",
    summary: String = "",
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = if (title.isBlank() && summary.isBlank()) 8.dp else 14.dp)) {
        if (title.isNotBlank() || summary.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                    }
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                    }
                }
                trailing?.invoke()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            content()
        }
    }
}

/**
 * 一行设置。右侧放什么由调用方决定。
 *
 * [compact] 给"一页里要排很多行"的清单用（插件列表）：上下留白收窄到 8dp、
 * 文字只占一行，整行大约 56dp；[leading] 是自定义左侧图标（比如插件自带的那张图），
 * 给了它就不再画 [icon]。
 */
@Composable
fun SettingsRow(
    title: String,
    summary: String = "",
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

/** 开关行。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    summary: String = "",
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    SettingsRow(title = title, summary = summary, enabled = enabled) {
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** 行内的分隔线（不占满整行，右边留出内边距，视觉上更轻）。 */
@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
    )
}

/**
 * 看得清的分隔线：**专给插件列表用**。
 *
 * [SettingsDivider] 那条是 0.5dp / 15% 透明度，几行插件挨在一起时分不出边界；
 * 这里用 1dp、透明度也提上去，相邻两个插件一眼就是两行。
 */
@Composable
fun SettingsStrongDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
    )
}

/**
 * 分段选择：一排等宽按钮，选中那个填充主题色。
 *
 * 取代原来那种松散的 Chip：[ChoiceChips]。设置页里的"几选一"全部用它，
 * 一眼能看出这是一组互斥选项，而不是几个可点的小标签。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
        }
    }
}

/** 数值滑杆行：标题 + 当前值 + 滑杆。 */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Int,
    valueRange: IntRange,
    valueText: String = value.toString(),
    onValueChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
        )
    }
}

/** 文本输入行。 */
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    supporting: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        singleLine = singleLine,
        label = { Text(label) },
        supportingText = if (supporting.isBlank()) null else {
            {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

/**
 * 一行小字说明，放在分组外面。
 *
 * 文案本身要控制在一行以内；这里只压平换行，不再截断加省略号。
 */
@Composable
fun SettingsNote(text: String) {
    Text(
        noteText(text),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = NOTE_LINES,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

/** 提示文字的目标字数：内容应该控制在这个长度以内。 */
private const val NOTE_CHARS_PER_LINE = 28

/** 最多显示几行：说明一律压到一行。 */
private const val NOTE_LINES = 1

/**
 * 把提示压成一行（去换行/连续空格）。
 *
 * 不再按字数截断加省略号：文案本身要短。28 字是写作目标，不是运行时截断阈值。
 */
internal fun noteText(raw: String): String =
    raw.trim().replace(Regex("\\s+"), " ")

/** 小徽标：版本号、能力这类短信息。 */
@Composable
fun SettingsBadge(text: String, highlight: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
