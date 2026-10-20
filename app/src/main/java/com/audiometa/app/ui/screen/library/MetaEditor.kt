package com.audiometa.app.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jaudiotagger.tag.FieldKey

/**
 * 可编辑的一条元信息。
 *
 * [fieldKey] 是写入时用的键名（[FieldKey] 的枚举名），[value] 是当前值。
 * 曲风这类多值字段的键名同样是 `GENRE`，分隔符由界面上的"/"按钮输入。
 */
data class EditableMeta(
    val label: String,
    val fieldKey: String,
    val value: String,
    /** 单列显示：值通常较长，并排会挤。 */
    val singleColumn: Boolean = false,
    /** 值为空时显示的占位（多选不同值用「未修改」）。 */
    val placeholder: String = "—"
)

/**
 * 元信息编辑器。
 *
 * 布局规则（按用户逐项指定）：
 * - **标题、专辑、艺术家、专辑艺术家、流派**单列；
 * - 其余短字段两两成对：音轨号/碟号、作词/作曲、日期/语言、版权/注释、BPM/ISRC；
 * - 行距比其他区域大一些，方便点选；
 * - 点整行就打开编辑弹窗，**行内不再放编辑图标**——
 *   每行都挂一个铅笔既吵又挤，点整行本来就是更自然的操作；
 * - 曲风那一行的弹窗里多一组分隔符按钮。
 *
 * 改完**立即落盘**：某一项在弹窗里点「确定」后，由调用方马上把这一个字段写进文件
 * （用户要求"手动修改了某一项信息后，直接同步写入该文件"，取消/确认那一对按钮已移除）；
 * 这里不再攒改动等一次保存。
 *
 * [edits] 是"写盘期间的乐观覆盖层"（键 = [EditableMeta.fieldKey]）。列表里显示的值
 * 取覆盖层优先：用户改完某一项、弹窗一关就能在这一行看到新值，
 * 不必等 IO 写完、文件重读回来才显示（写盘是 IO，重读回来之前行里还显示旧值会闪一下）。
 * 这一层**只影响显示、不参与落盘决策**：调用方在写盘成功、文件重读回来之后
 * 把对应的键清掉，之后显示的就是文件里真正读到的值。
 */
@Composable
fun MetaEditor(
    items: List<EditableMeta>,
    /** 弹窗点「确定」时回调，调用方在这里立即把这一项写进文件。 */
    onEdit: (fieldKey: String, newValue: String) -> Unit,
    modifier: Modifier = Modifier,
    /** 宽松排版：单列、更大行距（修改标签页用）。 */
    spacious: Boolean = false,
    /** 乐观覆盖层：fieldKey → 刚写盘的新值。写盘成功后由调用方清掉，见上面的说明。 */
    edits: Map<String, String> = emptyMap()
) {
    var editing by remember { mutableStateOf<EditableMeta?>(null) }
    // 显示值 = 乐观覆盖层优先（刚写盘、还没重读回来的那一项），其次才是文件里读到的值。
    val rows = items
        .map { item -> edits[item.fieldKey]?.let { item.copy(value = it) } ?: item }
        .filter { it.value.isNotBlank() || it.fieldKey.isNotBlank() }

    // 标签列宽按当前字号量出来，不再写死 56dp。
    // 写死的时候"专辑艺术家"（5 个字）的最后一个"家"会被裁掉 —— 用户报过这个。
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelWidth = com.audiometa.app.ui.component.rememberLabelWidth(rows.map { it.label }, labelStyle)

    Column(modifier.fillMaxWidth()) {
        var index = 0
        while (index < rows.size) {
            val current = rows[index]
            val next: EditableMeta? = if (index + 1 < rows.size) rows[index + 1] else null
            // 是否并排完全由 singleColumn 决定 —— 不再按值的长度自动判断。
            // 用户明确指定了哪几项单列、哪几项成对，那种"长度超过 20 就换行"
            // 的自动规则会让同一份数据在不同歌曲上排版不一致。
            val canPair = next != null && !current.singleColumn && !next.singleColumn
            if (canPair && next != null) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        MetaEditRow(current, labelWidth, labelStyle, spacious) { editing = current }
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.weight(1f)) {
                        MetaEditRow(next, labelWidth, labelStyle, spacious) { editing = next }
                    }
                }
                index += 2
            } else {
                MetaEditRow(current, labelWidth, labelStyle, spacious) { editing = current }
                index += 1
            }
        }
    }

    editing?.let { target ->
        MetaEditDialog(
            item = target,
            onDismiss = { editing = null },
            onConfirm = { newValue ->
                onEdit(target.fieldKey, newValue)
                editing = null
            }
        )
    }
}

/** 元信息长度超过这个值就整行显示，不并排。 */
@Composable
private fun MetaEditRow(
    item: EditableMeta,
    labelWidth: androidx.compose.ui.unit.Dp,
    labelStyle: androidx.compose.ui.text.TextStyle,
    spacious: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            // 宽松模式给修改标签页用；普通详情页维持紧凑行距。
            .padding(vertical = if (spacious) 9.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.label,
            // 字号与底部「文件」信息区一致（labelSmall / bodySmall）。
            // 用户指出元信息区整体偏大，而且要**和底部那块一样**才协调。
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(labelWidth)
        )
        // 标签和值之间留出 8dp：以前按标签最大宽度贴边，「专辑艺术家」和值挤在一起。
        Spacer(Modifier.width(8.dp))
        Text(
            item.value.ifBlank { item.placeholder },
            // 值和标签都用小号字：用户明确要求"作曲这一项的字号和它填写的内容一样"。
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (item.value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 单项编辑弹窗。
 *
 * [FieldKey.GENRE] 这种多值字段会多一行分隔符按钮：点一下把"/"插到光标后面。
 * 用户明确要求过这个按钮——手输斜杠在手机上很容易打成别的符号。
 */
@Composable
private fun MetaEditDialog(
    item: EditableMeta,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(item) { mutableStateOf(item.value) }
    val multiValue = item.fieldKey.equals(FieldKey.GENRE.name, true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑" + item.label) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(item.label) },
                    singleLine = !multiValue,
                    minLines = if (multiValue) 2 else 1,
                    supportingText = {
                        if (multiValue) {
                            Text("多个值用 / 分隔，例如：流行/摇滚")
                        }
                    }
                )
                if (multiValue) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "分隔符",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SeparatorButton("/") { text += "/" }
                        SeparatorButton("、") { text += "、" }
                        SeparatorButton("; ") { text += "; " }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "留空表示删除这一项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 分隔符按钮：一个字符的小方块。 */
@Composable
private fun SeparatorButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
