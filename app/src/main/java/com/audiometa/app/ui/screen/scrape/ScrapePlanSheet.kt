package com.audiometa.app.ui.screen.scrape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiometa.app.domain.usecase.ScrapePlan

/**
 * 组合刮削的确认弹窗。
 *
 * 用户逐项指定的排版：
 *
 * ```
 * 确认写入
 * ┌ 基础标签（只读，不会改）─────────┐
 * │ 标题     学府大道 7              │
 * │ 专辑     rap16                   │
 * │ 艺术家   王员外                   │
 * └─────────────────────────────────┘
 * ─────────────── 分隔 ───────────────
 * 艺术家图片   张韶涵（网易云音乐）
 * 流派         民谣
 * 音轨         3            相同
 * 碟号         没有搜到
 * 作曲         王员外
 * 注释         …
 * ```
 *
 * 几个要点：
 * - **出现就完整显示**：`skipPartiallyExpanded = true`，不再先占半屏、
 *   要用户手指往上滑才看得全（用户明确报过这个问题）；
 * - 上半是只读的基础标签，只有一条分隔线把它和下半分开；
 * - 下半按固定顺序排，和结果一样的显示「相同」；
 * - 不再有「参与源」那一行（用户要求移除）；
 * - 搜到的内容对不上基础标签时，整块显示「无可用结果」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrapePlanSheet(
    plan: ScrapePlan,
    loading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // skipPartiallyExpanded：打开就是完整高度，不需要往上滑一下。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("确认写入", style = MaterialTheme.typography.titleMedium)

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(
                        "正在搜索各源…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                }
                return@Column
            }

            // ---- 上半：只读的基础标签 ----
            if (plan.basic.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "基础标签（只读，不会修改）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                plan.basic.forEach { tag ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            tag.label,
                            Modifier.weight(0.24f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            tag.value.ifBlank { "（空）" },
                            Modifier.weight(0.76f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            // ---- 下半：要写入的内容 ----
            if (plan.noResult.isNotBlank()) {
                Text(
                    plan.noResult,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                // 标题行：左列"当前值"、右列"新值"（用户要求双列对比）。
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                    Spacer(Modifier.weight(0.24f))
                    Text(
                        "当前值",
                        Modifier.weight(0.38f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "新值",
                        Modifier.weight(0.38f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                plan.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(
                            row.label,
                            Modifier.weight(0.24f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            row.current,
                            Modifier.weight(0.38f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            row.display,
                            Modifier.weight(0.38f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.same) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontWeight = if (row.same) FontWeight.Normal else FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = !plan.isEmpty
                ) { Text("确认写入") }
            }
        }
    }
}
