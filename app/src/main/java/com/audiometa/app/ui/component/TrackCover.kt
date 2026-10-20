package com.audiometa.app.ui.component

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.audiometa.app.media.image.ArtworkKind
import com.audiometa.app.media.image.ArtworkResolver
import com.audiometa.app.media.image.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已解码封面的内存缓存。
 *
 * 以前是"最多 64 个条目"：一屏网格就 40 个，来回滑两个页签（加上滑动过程中被
 * 组合出来的那几行）就把缓存冲干净了 —— 每次滑回上一页都要重新解码一整屏
 * JPEG，卡顿就出在这儿。现在按**字节**算：
 *
 * - 上限取"可用堆的 1/6、最多 48MB"；
 * - 单个条目按 `宽 × 高 × 4` 计费；
 * - 显示用的位图还按控件实际大小做了降采样（见 [TrackCover] 的 `targetEdge`），
 *   一张列表封面只有一两百 KB，于是几个页签来回滑都能留在缓存里。
 *
 * 这一层现在也放**艺术家图**：那一类不再落盘（见 [loadCover] 的说明），
 * 磁盘上没有可命中的东西，内存这份就是它唯一的缓存。键里带着路径 / mtime /
 * 大小 / `updatedAt` / `refreshKey`，所以"文件里换了图"照样能失效重读。
 */
private val coverCache = object : LruCache<String, ImageBitmap>(
    // 高清封面单张可达 1280 边长（≈6.5MB），上限提到 64MB，
    // 否则详情页和列表来回切几次就把大图全淘汰了。
    ((Runtime.getRuntime().maxMemory() / 6).coerceAtMost(64L * 1024L * 1024L)).toInt()
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width.coerceAtLeast(1) * value.height.coerceAtLeast(1) * 4
}

/**
 * 只清内存里的解码结果。
 *
 * 设置里"刷新封面缓存"时用：磁盘重建了，内存这份也得扔，
 * 否则当前界面还显示着旧的解码结果。
 *
 * 对艺术家图来说这一步更重要：那一类没有磁盘缓存（见 [loadCover]），
 * 清掉内存 LRU 就是它唯一的失效手段，清完下次组合立刻重读音频。
 */
fun evictCoverMemoryCache() {
    coverCache.evictAll()
}

/**
 * 清掉整类图片缓存（封面 + 艺术家图）。
 *
 * 缓存按 key 存文件（专辑封面绑 fileId+修改时间），没法按路径反查；
 * 而这一类的"刷新"语义就是"按当前文件重新生成一遍"，
 * 所以直接重建封面目录：清空 + 清 temp + 淘汰。
 *
 * **不再重建艺术家图目录**：艺术家图现在没有磁盘缓存（见 [loadCover]），
 * 没有目录可重建；它那一层只有内存 LRU，[invalidateCoverOnly] 里的清空动作
 * 已经把它清干净，下一次组合就会实时的、重新从音频里解出来。
 *
 * 外置图换了但音频文件没动的情况下，"清内存 + 重建封面目录"就是让界面
 * 读到新图的全部动作。
 */
fun invalidateCoverCache(context: android.content.Context) {
    invalidateCoverOnly(context)
}

/**
 * 只失效封面。
 *
 * 详情页下拉刷新封面时用。以前这里刻意"不动艺术家图"，是为了避免头像
 * 闪一下占位图再回来；艺术家图改成实时读取之后这点自动成立 ——
 * 内存里那份解码结果清掉之后，下一次组合会立刻从音频文件重新解出来，
 * 不存在"要等磁盘重建"的中间态。
 *
 * 之所以两个入口都留着：桌面小组件和设置页分别调的是不同名字，
 * 合并成一个会连带改到别的文件。
 */
fun invalidateCoverOnly(context: android.content.Context) {
    coverCache.evictAll()
    ThumbnailCache.rebuild(context)
}

private fun cacheKey(
    audioPath: String,
    kind: ArtworkKind,
    stamp: Long,
    refreshKey: Int = 0,
    targetEdge: Int = DISPLAY_EDGE
): String {
    val file = File(audioPath)
    // stamp 是外部传进来的版本号（歌曲记录的 updatedAt）。
    // 必须带上它：开了「保留修改时间」后，刮削写完文件时间戳不变，只靠 mtime 缓存不会失效。
    // refreshKey 是详情页手动刷新的计数，同样要进键，否则刷新会命中旧的内存缓存。
    // targetEdge 也要进键：同一个封面在列表里是 256、在详情页是 1024，尺寸不同不能互相顶替。
    return kind.name + "|" + audioPath + "|" + file.lastModified() + "|" + file.length() + "|" +
        stamp + "|" + refreshKey + "|" + targetEdge
}

/** 列表/网格里的封面按这个边长解码；详情页会显式传更大的值。 */
const val DISPLAY_EDGE = 256

/**
 * 歌曲图片：先找外置同名图片，再读内嵌图；都没有就显示占位图。
 * 缓存键里带上文件的修改时间，刮削后自动重新解码。
 *
 * 两类图现在的缓存策略不同（见 [loadCover]）：
 * - [ArtworkKind.COVER]（专辑封面）：先查磁盘缩略图，未命中按显示尺寸解码，
 *   `warmDiskCache = true` 时顺手把 1280 的高清版落盘；
 * - [ArtworkKind.ARTIST]（艺术家图）：**不读也不写磁盘**，每次都从音频文件里实时取，
 *   只把解码结果放进内存 LRU —— 文件里换了图，下一次组合就是新的。
 */
@Composable
fun TrackCover(
    audioPath: String?,
    shape: Shape,
    modifier: Modifier = Modifier,
    /**
     * 找哪张图。默认专辑封面；传 [ArtworkKind.ARTIST] 时走"实时读取、不落盘"那条路。
     */
    kind: ArtworkKind = ArtworkKind.COVER,
    stamp: Long = 0L,
    /**
     * 手动刷新计数。详情页下拉刷新封面时 +1 就够：它进了缓存键，
     * 也进了 produceState 的 key，所以一定会重新解码，而不是命中旧的内存缓存。
     */
    refreshKey: Int = 0,
    /**
     * 显示用的最长边（像素）。列表/网格里的格子只有一两百像素宽，
     * 按 512 解码就是白占四倍内存、白传四倍显示带宽 —— 滑页签时的卡顿有一半来自这里。
     * 详情页那种大图自己传更大的值（例如 1024）。
     */
    targetEdge: Int = DISPLAY_EDGE,
    /** 裁切对齐方式。详情页头图用 TopCenter，保证顶部完整、只裁下半部分。 */
    alignment: Alignment = Alignment.Center,
    /**
     * 是否允许播放动图（GIF / 动画 WebP）。
     * 只在详情页展开封面时打开——列表里播放动图太费性能。
     */
    animated: Boolean = false,
    /**
     * 内存/磁盘都没缓存时，是否顺手生成**高清磁盘缓存**（1280 边长 + JPEG 压缩 + 写文件）。
     *
     * 列表/网格传 false：快速滑动时一屏几十张图同时冷加载，若每张都解 1280 大图、
     * JPEG 压缩、写盘，IO 线程立刻拥堵 —— 表现就是滑动掉帧、图一张张贴出来。
     * 列表只按显示尺寸（256）解码原图进内存缓存，不压缩不写盘，便宜一两个数量级；
     * 高清磁盘缓存等打开详情页（[warmDiskCache] 默认 true）时自然生成。
     *
     * **只对专辑封面有意义**：艺术家图不落盘（见 [loadCover]），传什么值都只进内存。
     */
    warmDiskCache: Boolean = true,
    contentDescription: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext

    // 动图分支：仅当显式要求且系统支持（API 28+）时启用，否则落到下面的静态路径。
    if (animated &&
        !audioPath.isNullOrBlank() &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
    ) {
        var animatedDrawable by remember(audioPath, kind, stamp, refreshKey) {
            mutableStateOf<android.graphics.drawable.Drawable?>(null)
        }
        LaunchedEffect(audioPath, kind, stamp, refreshKey) {
            animatedDrawable = withContext(Dispatchers.IO) { loadAnimatedDrawable(audioPath, kind) }
        }
        val current = animatedDrawable
        if (current != null) {
            Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { view -> view.setImageDrawable(current) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            return
        }
    }
    // stamp 变化（刮削后 updatedAt 会变）就重新读文件，及时显示新图。
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        audioPath, kind, stamp, refreshKey, targetEdge, warmDiskCache
    ) {
        value = if (audioPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadCover(context, audioPath, kind, stamp, refreshKey, targetEdge, warmDiskCache)
            }
        }
    }
    val current = bitmap
    Box(modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (current == null) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Image(
                bitmap = current,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = alignment
            )
        }
    }
}

/**
 * 把歌里的某张图存到相册目录，返回保存路径。
 *
 * 艺术家图也走这里：它现在是"实时读音频"，存到相册同样是把当前文件里那张读出来写出去，
 * 和界面显示的那张必然一致。
 */
suspend fun saveArtworkToPictures(
    context: android.content.Context,
    audioPath: String,
    kind: ArtworkKind,
    displayName: String
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val audio = File(audioPath)
        if (!audio.isFile) return@runCatching null
        val bytes = artworkBytes(audio, kind) ?: return@runCatching null
        // 相册目录，文件管理器可见。
        val dir = File("/storage/emulated/0/Pictures/AudioMeta")
        if (!dir.exists()) dir.mkdirs()
        val safe = displayName.map { if (it in "/\\:*?\"<>|") '_' else it }
            .joinToString("").trim().ifBlank { "cover" }
        val target = File(dir, safe + ".jpg")
        target.writeBytes(bytes)
        target.absolutePath
    }.getOrNull()
}

/**
 * 读内嵌/外置图，只有真正是动图时才返回 Drawable。
 * 静态图返回 null，交给原来的 ImageBitmap 路径，避免多一次解码。
 *
 * 这里天然就是实时的（直接 [ArtworkResolver.resolve]），艺术家图不落盘之后
 * 两条路径的取图方式终于统一了。
 */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.P)
private fun loadAnimatedDrawable(audioPath: String, kind: ArtworkKind): android.graphics.drawable.Drawable? =
    runCatching {
        val audio = File(audioPath)
        if (!audio.isFile) return@runCatching null
        val bytes = artworkBytes(audio, kind) ?: return@runCatching null
        val decoderSource = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        val drawable = android.graphics.ImageDecoder.decodeDrawable(decoderSource)
        // 只有多帧图才会是 AnimatedImageDrawable；GIF / 动画 WebP 走这里。
        if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
            drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
            drawable
        } else {
            null
        }
    }.getOrNull()

private fun loadCover(
    context: android.content.Context,
    audioPath: String,
    kind: ArtworkKind,
    stamp: Long,
    refreshKey: Int = 0,
    targetEdge: Int = DISPLAY_EDGE,
    warmDiskCache: Boolean = true
): ImageBitmap? {
    val audio = File(audioPath)
    if (!audio.isFile) return null
    val size = audio.length()
    val memoryKey = cacheKey(audioPath, kind, stamp, refreshKey, targetEdge)

    coverCache.get(memoryKey)?.let { return it }

    /*
     * 艺术家图：**完全不碰磁盘缓存**（既不读也不写），直接实时读音频里的数据。
     *
     * 为什么（用户要求："图片类只生成歌曲专辑封面的缩略图，艺术家图是实时读取音频内数据"）：
     * 艺术家图是按"人"共享的，磁盘 key 只能绑艺术家名 —— 同一个音频文件里把图换掉，
     * mtime / 大小 / updatedAt 可能纹丝不动（开着「保持文件修改时间」时更是必然不动），
     * 盘上那份旧缩略图于是永远命中，界面一直显示旧头像。不落盘就没有这个坑：
     * 每次组合都拿**当前文件**解一张，文件里是什么就显示什么。
     *
     * 代价是每次都要重新解码，所以只把结果放进内存 LRU（coverCache）。
     * 内存键里带着路径 / mtime / 大小 / updatedAt / refreshKey（见 [cacheKey]），
     * 写入方换完图会换掉 updatedAt（`refreshTrack(full = true)`）或直接清内存缓存
     * （[evictCoverMemoryCache]），所以下一次组合自然会重新读到新图。
     *
     * 这里也刻意不走"解大图 → 存高清 → 再缩小显示"那条路：既然不落盘，
     * 就没有理由为了存一份而先按 1280 解码，只按本次显示尺寸解一次最省。
     */
    if (kind == ArtworkKind.ARTIST) {
        val bytes = artworkBytes(audio, kind) ?: return null
        val bitmap = decode(bytes, targetEdge) ?: return null
        val image = bitmap.asImageBitmap()
        coverCache.put(memoryKey, image)
        return image
    }

    val lastModified = audio.lastModified()
    // 磁盘缓存的 key：sha1("audio|路径|写入时间|mtime|大小")。
    // 里面的"写入时间"就是这里传进来的 stamp —— 少了它，
    // 开着"保持文件修改时间"时写完标签 key 不变，界面会一直读旧图。
    val diskKey = ThumbnailCache.audioCoverKey(audioPath, stamp, lastModified, size)

    // 手动刷新（refreshKey > 0）时跳过磁盘缓存：
    // 外置图可能刚换过，而 key 里只有音频文件的时间戳，会一直读到旧图。
    if (refreshKey <= 0) {
        ThumbnailCache.read(context, diskKey)?.let { cached ->
            // 按**显示尺寸**解码：盘里那份是高清封面（1280 边长），格子里只要 256，
            // inSampleSize 直接省掉大部分内存和解码时间。
            decode(cached, targetEdge)?.let { bitmap ->
                val image = bitmap.asImageBitmap()
                coverCache.put(memoryKey, image)
                return image
            }
        }
    }

    val bytes = artworkBytes(audio, kind) ?: return null

    // 列表/网格的轻量路径：只按显示尺寸解码，直接进内存缓存。
    // 不生成 1280 大图、不做 JPEG 压缩、不写磁盘 —— 滑屏时几十个 item
    // 同时冷加载，省下的正是最大的一块 CPU/IO。高清缓存留给详情页生成。
    if (!warmDiskCache) {
        val small = decode(bytes, targetEdge) ?: return null
        val image = small.asImageBitmap()
        coverCache.put(memoryKey, image)
        return image
    }

    // 盘里那份缩略图按 COVER_THUMB_EDGE 生成：封面要在详情页铺满整屏（用户要高质量图），
    // 存 1280 边长、JPEG 92。解码时取「磁盘目标」和「本次显示目标」里更大的那个，
    // 保证详情页铺满和列表缩略两边都不糊。
    val bitmap = decode(bytes, maxOf(COVER_THUMB_EDGE, targetEdge)) ?: return null
    compress(bitmap, COVER_JPEG_QUALITY)?.let { thumb ->
        ThumbnailCache.write(context, diskKey, thumb)
    }
    val shown = scaleDown(bitmap, targetEdge)
    val image = shown.asImageBitmap()
    coverCache.put(memoryKey, image)
    return image
}

/**
 * 取一张图的原始字节：外置同名文件优先，其次内嵌图；都没有就 null。
 *
 * 三个入口（静态图、动图分支、[saveArtworkToPictures]）读图的规则必须一致，
 * 否则会出现"预览里有图、保存出来是空的"这种莫名其妙的差异。
 */
private fun artworkBytes(audio: File, kind: ArtworkKind): ByteArray? {
    val source = ArtworkResolver.resolve(audio, kind) ?: return null
    return when (source) {
        is ArtworkResolver.Source.External -> runCatching { File(source.path).readBytes() }.getOrNull()
        is ArtworkResolver.Source.Bytes -> source.data
    }
}

/** 落盘的是缩小后的 JPEG，不是原图，避免缓存目录膨胀。 */
private fun compress(bitmap: android.graphics.Bitmap, quality: Int = 85): ByteArray? = runCatching {
    val out = java.io.ByteArrayOutputStream()
    if (bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
}.getOrNull()

/**
 * 落盘缩略图的最长边。
 *
 * 只有**专辑封面**会落盘（艺术家图实时读取、不写磁盘，见 [loadCover]）：
 * 详情页会把它铺满整屏宽，盘里那份就是展开时的图源，所以必须高清（1280）。
 *
 * 艺术家图以前在这里还有个 512 的档位，现在没有用了 —— 它只按显示尺寸解一次，
 * 不再有"存一份给以后用"的需求。
 */
private const val COVER_THUMB_EDGE = 1280

/** 封面 JPEG 质量：92 属于肉眼基本无损档，体积比 85 大约多三成，可接受。 */
private const val COVER_JPEG_QUALITY = 92

/**
 * 解码，并把最长边限制在 [maxEdge] 附近（用 inSampleSize 降采样，不会解出全尺寸再缩）。
 *
 * 判据是"除以 sample 之后仍然**大于** maxEdge 就继续减半" —— 得到的位图不会超过
 * maxEdge 太多，也不会因为要求 256 就把 300 的图硬砍成 150。
 */
private fun decode(bytes: ByteArray, maxEdge: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * 把已经解出来的位图缩到 [maxEdge] 以内（只用于"原图解码后要给列表显示"这一种情况）。
 *
 * 之所以不直接按小尺寸解码原图：那份缩略图还要写到磁盘上给详情页用，
 * 不能因为这次是列表取图就把盘上的也缩成小的。缩放本身比重新解码一次便宜得多。
 */
private fun scaleDown(bitmap: android.graphics.Bitmap, maxEdge: Int): android.graphics.Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (maxEdge <= 0 || longest <= maxEdge) return bitmap
    val scale = maxEdge.toFloat() / longest
    val scaled = android.graphics.Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
