package com.audiometa.app.media.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.audiometa.app.util.AppLog
import java.io.File

/**
 * 从封面图里取色。
 *
 * 详情页展开封面后左右滑动会出现一条进度条，用户要求它的配色**跟着封面底部的颜色走**，
 * 这样进度条看起来像是封面自己延伸出来的，而不是贴上去的一条外来色块。
 */
object CoverColorSampler {

    private const val TAG = "CoverColorSampler"

    /** 一张封面的取色结果。 */
    data class Palette(
        /** 底部主色（进度条已播放部分）。 */
        val trackColor: Color,
        /** 底部主色的暗化版本（进度条未播放部分）。 */
        val restColor: Color,
        /** 进度条上的文字色：底色太亮就用深色字。 */
        val onTrackColor: Color
    ) {
        companion object {
            /** 取不到图时的兜底色，跟随主题。 */
            fun fallback(primary: Color, surface: Color) = Palette(
                trackColor = primary,
                restColor = surface,
                onTrackColor = if (primary.luminance() > 0.6f) Color.Black else Color.White
            )
        }
    }

    /**
     * 采样封面底部。
     *
     * @param bottomFraction 从图片高度的哪个位置开始算"底部"。0.82 表示只看最下面 18%。
     * @param fallbackPrimary 取不到图时使用的主色。
     */
    fun sample(
        bytes: ByteArray,
        fallbackPrimary: Color,
        fallbackSurface: Color,
        bottomFraction: Float = 0.82f
    ): Palette {
        val bitmap = runCatching { decodeSmall(bytes) }.getOrNull()
            ?: return Palette.fallback(fallbackPrimary, fallbackSurface)
        return try {
            fromBitmap(bitmap, bottomFraction)
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    fun sampleFile(
        file: File?,
        fallbackPrimary: Color,
        fallbackSurface: Color,
        bottomFraction: Float = 0.82f
    ): Palette {
        if (file == null || !file.isFile) return Palette.fallback(fallbackPrimary, fallbackSurface)
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return Palette.fallback(fallbackPrimary, fallbackSurface)
        return sample(bytes, fallbackPrimary, fallbackSurface, bottomFraction)
    }

    /** 直接用已经解析出来的图片源取色。 */
    fun sampleSource(
        audioPath: String,
        kind: ArtworkKind,
        fallbackPrimary: Color,
        fallbackSurface: Color,
        bottomFraction: Float = 0.82f
    ): Palette {
        val audio = File(audioPath)
        if (!audio.isFile) return Palette.fallback(fallbackPrimary, fallbackSurface)
        val source = runCatching { ArtworkResolver.resolve(audio, kind) }.getOrNull()
            ?: return Palette.fallback(fallbackPrimary, fallbackSurface)
        val bytes = when (source) {
            is ArtworkResolver.Source.External ->
                runCatching { File(source.path).readBytes() }.getOrNull()
            is ArtworkResolver.Source.Bytes -> source.data
        } ?: return Palette.fallback(fallbackPrimary, fallbackSurface)
        return sample(bytes, fallbackPrimary, fallbackSurface, bottomFraction)
    }

    private fun fromBitmap(bitmap: Bitmap, bottomFraction: Float): Palette {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return Palette(Color.Gray, Color.DarkGray, Color.White)
        val startY = (height * bottomFraction.coerceIn(0f, 0.98f)).toInt()
            .coerceIn(0, height - 1)
        val stepY = ((height - startY) / 6).coerceAtLeast(1)
        val stepX = (width / 24).coerceAtLeast(1)

        // 按亮度排序取"中间那一档"，而不是简单平均：
        // 平均会把黑边和主体混成一片灰，中间档更接近肉眼看到的主色调。
        val samples = ArrayList<Int>()
        var y = startY
        while (y < height) {
            var x = 0
            while (x < width) {
                samples += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return Palette(Color.Gray, Color.DarkGray, Color.White)

        samples.sortBy { luminanceOf(it) }
        val pick = samples[samples.size / 2]
        val base = Color(
            red = android.graphics.Color.red(pick) / 255f,
            green = android.graphics.Color.green(pick) / 255f,
            blue = android.graphics.Color.blue(pick) / 255f,
            alpha = 1f
        )
        // 纯黑/纯白做主色会显得像"没取到色"，往中间收一点。
        val adjusted = base.adjustForContrast()
        return Palette(
            trackColor = adjusted,
            restColor = adjusted.mix(Color.Black, 0.62f),
            onTrackColor = if (adjusted.luminance() > 0.62f) Color.Black else Color.White
        )
    }

    /** 解码到最长边 96 像素：只需要颜色，不需要细节，越小越快。 */
    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun luminanceOf(argb: Int): Float {
        val r = android.graphics.Color.red(argb) / 255f
        val g = android.graphics.Color.green(argb) / 255f
        val b = android.graphics.Color.blue(argb) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

    /**
     * 让颜色和背景分得开：太暗的提亮，太亮的压暗。
     * 封面底部常见的是纯黑（很多专辑图下方是暗场），直接拿来当进度条会看不见。
     */
    private fun Color.adjustForContrast(): Color {
        val lum = luminance()
        return when {
            lum < 0.16f -> mix(Color.White, 0.34f)
            lum > 0.9f -> mix(Color.Black, 0.18f)
            else -> this
        }
    }

    private fun Color.mix(other: Color, ratio: Float): Color {
        val r = ratio.coerceIn(0f, 1f)
        return Color(
            red = red * (1f - r) + other.red * r,
            green = green * (1f - r) + other.green * r,
            blue = blue * (1f - r) + other.blue * r,
            alpha = 1f
        )
    }

    /** 供调试：取色结果的十六进制表示。 */
    fun hex(color: Color): String = String.format("#%08X", color.toArgb())
}
