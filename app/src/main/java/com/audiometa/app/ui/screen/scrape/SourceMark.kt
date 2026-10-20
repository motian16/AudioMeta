package com.audiometa.app.ui.screen.scrape

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audiometa.app.ui.component.LocalImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索结果里的「源标识」。
 *
 * 用户要求：结果列表**不再写「网易云音乐 xx」这种文字**，而是用每个源自己的
 * 图标来标识。图标就是插件包里那个 `icon.png`（`InstalledPlugin.iconPath`），
 * 也就是插件页里显示的那张 —— 同一个人在两处看到的是同一个图标。
 */

/** 插件 id → 图标文件路径。一个进程只查一次，查不到的记成空串免得反复读盘。 */
private val iconPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

suspend fun pluginIconPath(pluginId: String): String? {
    if (pluginId.isBlank()) return null
    iconPathCache[pluginId]?.let { return it.ifBlank { null } }
    val path = withContext(Dispatchers.IO) {
        runCatching { com.audiometa.app.di.ServiceLocator.plugins.load(pluginId)?.iconPath }.getOrNull()
    }.orEmpty()
    iconPathCache[pluginId] = path
    return path.ifBlank { null }
}

/** 一批源 id 的图标路径。界面拿到之后直接给 [SourceMark]。 */
@Composable
fun rememberSourceIcons(sourceIds: List<String>): Map<String, String> {
    val wanted = remember(sourceIds) { sourceIds.distinct().filter { it.isNotBlank() } }
    val icons by produceState(initialValue = emptyMap<String, String>(), wanted) {
        value = withContext(Dispatchers.IO) {
            wanted.mapNotNull { id -> pluginIconPath(id)?.let { id to it } }.toMap()
        }
    }
    return icons
}

/**
 * 一个源的图标。
 *
 * 拿不到图标（插件被删了、包里没带图）时退回一个通用图标 ——
 * 界面上留一块空白比"什么都不显示"更让人以为是加载失败。
 */
@Composable
fun SourceMark(
    iconPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    if (iconPath.isNullOrBlank()) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size)
        )
        return
    }
    LocalImage(
        path = iconPath,
        modifier = modifier.size(size).clip(CircleShape),
        contentDescription = null,
        placeholderIcon = false
    )
}
