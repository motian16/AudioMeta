package com.audiometa.app.ui.screen.scrape

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiometa.app.domain.usecase.ScrapeCandidate
import com.audiometa.app.media.image.ImageSizeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val imageCache = object : LruCache<String, ImageBitmap>(96) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

/** 图片尺寸缓存：同一个地址只探一次。 */
private val sizeCache = object : LruCache<String, IntArray>(256) {
    override fun sizeOf(key: String, value: IntArray): Int = 1
}

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * 封面 / 艺术家图的搜索结果网格。
 *
 * 一行三个（用户要求），每格：
 * - 左上角：平台名（半透明黑底）
 * - 右下角：分辨率，例如 300×300（半透明黑底）
 *
 * 之前是竖向列表，图片小、看不出分辨率，也不好比较。
 *
 * ⚠ 契约：每一格显示的就是 [ScrapeCandidate.previewUrl]，**选中时钉进候选的也是它**
 * （见 `ScrapePickerScreen` 里的 `pinPickedImageUrl()`）。所以这里必须始终只从 previewUrl
 * 取图、探尺寸，不要再引入"另一路解析出来的地址"—— 一旦显示的地址和钉进去/写文件的地址
 * 不是同一个，用户挑的那张就会被换掉，而且界面上完全看不出来。
 */
@Composable
fun ArtworkResultGrid(
    candidates: List<ScrapeCandidate>,
    onPick: (ScrapeCandidate) -> Unit,
    modifier: Modifier = Modifier,
    /** 源 id → 图标路径：左上角用源自己的图标标识，不写平台名（用户要求）。 */
    icons: Map<String, String> = emptyMap()
) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            candidates,
            key = { index, candidate -> candidate.sourceId + "#" + index }
        ) { _, candidate ->
            ArtworkCell(candidate, icons[candidate.sourceId]) { onPick(candidate) }
        }
    }
}

@Composable
private fun ArtworkCell(candidate: ScrapeCandidate, iconPath: String?, onClick: () -> Unit) {
    // 这一格显示的地址，就是选中时会被钉进候选、最终写进文件的那个地址。
    // 加载和探尺寸都只认它，避免"看到的"和"写进去的"各走一路解析。
    val shownUrl = candidate.previewUrl
    val bitmap by produceState<ImageBitmap?>(null, shownUrl) {
        value = if (shownUrl.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { loadImage(shownUrl) }
        }
    }
    // 分辨率优先用插件给的；没有再去探图片真实尺寸。
    val measured by produceState(IntArray(0), shownUrl, candidate.imageWidth) {
        value = when {
            candidate.imageWidth > 0 -> intArrayOf(candidate.imageWidth, candidate.imageHeight)
            shownUrl.isBlank() -> IntArray(0)
            else -> withContext(Dispatchers.IO) { probeSize(shownUrl) }
        }
    }
    val resolution = if (measured.size == 2 && measured[0] > 0) {
        measured[0].toString() + "×" + measured[1]
    } else {
        candidate.resolutionLabel
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            ) {
                val current = bitmap
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Image(
                        bitmap = current,
                        contentDescription = candidate.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // 左上角：源图标（用户要求：不再写平台名，用每个源自己的 icon）
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 3.dp, vertical = 3.dp)
                ) {
                    SourceMark(iconPath, size = 14.dp)
                }
                // 右下角：分辨率
                if (resolution.isNotBlank()) {
                    Badge(
                        text = resolution,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(
                    candidate.title.ifBlank { "(无标题)" },
                    style = MaterialTheme.typography.labelMedium,
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
                Text(
                    (candidate.score * 100).toInt().toString() + "%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (candidate.score >= 0.7) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** 半透明黑底的小角标。 */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun loadImage(url: String): ImageBitmap? {
    imageCache.get(url)?.let { return it }
    val bytes = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    val image = bitmap.asImageBitmap()
    imageCache.put(url, image)
    return image
}

/**
 * 只为了拿分辨率。
 *
 * 用流式读取，解析完文件头就停：图片的宽高都在头里，
 * 不需要把整张图拉下来，网格里一屏十几张图时才不会把网络打满。
 */
private fun probeSize(url: String): IntArray {
    sizeCache.get(url)?.let { return it }
    val size = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            body.byteStream().use { stream ->
                ImageSizeProbe.read(stream, PROBE_BYTES)
            }
        }
    }.getOrNull() ?: return IntArray(0)
    val out = intArrayOf(size.first, size.second)
    sizeCache.put(url, out)
    return out
}

private const val PROBE_BYTES = 256 * 1024
