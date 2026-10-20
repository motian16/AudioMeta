package com.audiometa.app.media.image

/**
 * 从图片字节流里读宽高。
 *
 * 只解析文件头，不需要把整张图下载下来：网格里一屏十几张图，
 * 为了显示「300×300」这种角标把每张图都拉一遍太浪费。
 */
object ImageSizeProbe {

    /**
     * PNG 的宽高就写在固定位置（IHDR）。
     * 签名 8 字节 + 长度 4 + "IHDR" 4 + 宽 4 + 高 4。
     */
    fun png(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        for (i in signature.indices) {
            if (bytes[i] != signature[i]) return null
        }
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    /**
     * JPEG 从 SOI 开始按段走，遇到 SOFn 就取宽高。
     *
     * 必须按段长度跳：逐字节找 0xFF 会把压缩数据里的字节当成标记，
     * 也会撞上 APPn / COM 段里的内容。
     */
    fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            // 0xFF 是填充字节，0xD0..0xD7 是 RSTn，都不带长度字段。
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            // 走到压缩数据说明前面没有 SOF，放弃。
            if (marker == 0xDA) return null
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            // SOF0..SOF15，排除 DHT(0xC4) / JPG(0xC8) / DAC(0xCC)。
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= bytes.size) return null
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) width to height else null
            }
            i += 2 + length
        }
        return null
    }

    /** WebP：VP8X / VP8L / VP8 三种块各有一种写法。 */
    fun webp(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val fourCc = String(bytes, 12, 4, Charsets.US_ASCII)
        runCatching {
            when (fourCc) {
                "VP8X" -> {
                    val w = readInt24(bytes, 24) + 1
                    val h = readInt24(bytes, 27) + 1
                    if (w > 0 && h > 0) w to h else null
                }
                "VP8 " -> {
                    val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0x3F) shl 8))
                    val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0x3F) shl 8))
                    if (w > 0 && h > 0) w to h else null
                }
                "VP8L" -> {
                    val b0 = bytes[21].toInt() and 0xFF
                    val b1 = bytes[22].toInt() and 0xFF
                    val b2 = bytes[23].toInt() and 0xFF
                    val b3 = bytes[24].toInt() and 0xFF
                    val w = 1 + (((b1 and 0x3F) shl 8) or b0)
                    val h = 1 + (((b3 and 0x0F) shl 10) or (b2 shl 2) or ((b1 and 0xC0) shr 6))
                    if (w > 0 && h > 0) w to h else null
                }
                else -> null
            }
        }.getOrNull()?.let { return it }
        return null
    }

    /** GIF：宽高是小端 16 位，紧跟在 "GIF89a" 之后。 */
    fun gif(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() || bytes[2] != 'F'.code.toByte()) {
            return null
        }
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return if (width > 0 && height > 0) width to height else null
    }

    /** 依次按 PNG / JPEG / WebP / GIF 试。 */
    fun probe(bytes: ByteArray): Pair<Int, Int>? =
        png(bytes) ?: jpeg(bytes) ?: webp(bytes) ?: gif(bytes)

    /**
     * 从流里读宽高，最多读 [limit] 字节。
     *
     * 网络请求用它：图片头通常几 KB 就够，读完不用把整张图拉下来。
     * 只读到能解析出结果为止，剩下的交给调用方关闭。
     */
    fun read(stream: java.io.InputStream, limit: Int = 256 * 1024): Pair<Int, Int>? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val count = runCatching { stream.read(chunk, 0, minOf(chunk.size, limit - total)) }.getOrDefault(-1)
            if (count <= 0) break
            buffer.write(chunk, 0, count)
            total += count
            // 每读一块就试一次：够解析了立刻返回，不等整个文件。
            val bytes = buffer.toByteArray()
            probe(bytes)?.let { return it }
            // 已经读到压缩数据前面还是没有 SOF，就没必要继续读了。
            if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                containsSos(bytes)
            ) {
                return null
            }
        }
        return probe(buffer.toByteArray())
    }

    private fun containsSos(bytes: ByteArray): Boolean {
        var i = 2
        while (i + 3 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return true
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker == 0xD8 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return false
            i += 2 + length
        }
        return false
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
}
