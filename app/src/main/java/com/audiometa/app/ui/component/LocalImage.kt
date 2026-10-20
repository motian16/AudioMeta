package com.audiometa.app.ui.component

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地图片。为了不引入图片加载库，直接解码文件并按需降采样，
 * 用 LruCache 缓存已解码的位图。文件不存在时显示占位图。
 */
private val imageCache = object : LruCache<String, ImageBitmap>(96) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

@Composable
fun LocalImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    placeholderIcon: Boolean = true
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { loadBitmap(path) }
        }
    }
    val current = bitmap
    if (current == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (placeholderIcon) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

private const val TARGET_EDGE = 320

private fun loadBitmap(path: String): ImageBitmap? {
    imageCache.get(path)?.let { return it }
    val file = File(path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > TARGET_EDGE * 2 || bounds.outHeight / sample > TARGET_EDGE * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
    val image = bitmap.asImageBitmap()
    imageCache.put(path, image)
    return image
}
