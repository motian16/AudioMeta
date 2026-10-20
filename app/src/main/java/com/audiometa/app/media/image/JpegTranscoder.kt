package com.audiometa.app.media.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 图片处理。
 *
 * - 自定义质量：重编码为 JPEG，quality 由用户指定，并限制最长边。
 * - 原图：**分辨率保持不变**；但如果源是 PNG 就转成 JPEG，
 *   因为 PNG 体积大得多，而且不少格式与播放器对内嵌 PNG 支持不好。
 */
class JpegTranscoder(private val maxEdge: Int = 3000) {

    data class Result(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

    fun toJpeg(source: ByteArray, quality: Int): Result = encode(source, quality, maxEdge)

    fun original(source: ByteArray, mime: String): Result {
        val (width, height) = probe(source)
        val isPng = mime.contains("png", ignoreCase = true) || looksLikePng(source)
        if (isPng) return encode(source, PNG_TO_JPEG_QUALITY, UNLIMITED_EDGE)
        return Result(source, mime.ifBlank { "image/jpeg" }, width, height)
    }

    fun probe(source: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    private fun encode(source: ByteArray, quality: Int, limit: Int): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, limit)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, opts)
            ?: error("无法解码图片")
        val scaled = scaleIfNeeded(decoded, limit)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
            error("JPEG 重编码失败")
        }
        val width = scaled.width
        val height = scaled.height
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return Result(out.toByteArray(), "image/jpeg", width, height)
    }

    private fun looksLikePng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    private fun calculateSample(width: Int, height: Int, max: Int): Int {
        if (width <= 0 || height <= 0 || max >= UNLIMITED_EDGE) return 1
        var sample = 1
        while (width / sample > max * 2 || height / sample > max * 2) sample *= 2
        return sample
    }

    private fun scaleIfNeeded(bitmap: Bitmap, max: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val edge = maxOf(w, h)
        if (edge <= max) return bitmap
        val scale = max.toFloat() / edge
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private companion object {
        /** 原图转码时不缩放，用一个足够大的上限表示"不限"。 */
        const val UNLIMITED_EDGE = 100_000
        const val PNG_TO_JPEG_QUALITY = 100
    }
}
