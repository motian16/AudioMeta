package com.audiometa.app.media.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 图片尺寸解析。
 *
 * 网格角标要显示「300×300」，但只读文件头就够，不能为了角标把整张图拉下来。
 * 这里用真实结构的字节流验证解析结果。
 */
class ImageSizeProbeTest {

    @Test
    fun `解析 JPEG 的 SOF0 宽高`() {
        val jpeg = jpegBytes(1200, 800, sofMarker = 0xC0)
        assertEquals(1200 to 800, ImageSizeProbe.jpeg(jpeg))
    }

    @Test
    fun `解析 JPEG 的 SOF2 渐进式宽高`() {
        val jpeg = jpegBytes(1024, 768, sofMarker = 0xC2)
        assertEquals(1024 to 768, ImageSizeProbe.jpeg(jpeg))
    }

    @Test
    fun `JPEG 前面的应用段按长度跳过而不是逐字节乱扫`() {
        // APP0 + 一个大 APP1（EXIF 常见），里面故意塞 0xFFC0 这样的字节，
        // 逐字节扫描会被这些假标记骗到，按段长度跳就不会。
        val jpeg = jpegBytes(300, 200, sofMarker = 0xC0, decoyBeforeSof = true)
        assertEquals(300 to 200, ImageSizeProbe.jpeg(jpeg))
    }

    @Test
    fun `DHT 段不会被误当成 SOF`() {
        val jpeg = jpegBytes(640, 480, sofMarker = 0xC0, insertDht = true)
        assertEquals(640 to 480, ImageSizeProbe.jpeg(jpeg))
    }

    @Test
    fun `解析 PNG 的 IHDR 宽高`() {
        val png = pngBytes(500, 500)
        assertEquals(500 to 500, ImageSizeProbe.png(png))
    }

    @Test
    fun `解析 GIF 的小端宽高`() {
        val gif = gifBytes(320, 240)
        assertEquals(320 to 240, ImageSizeProbe.gif(gif))
    }

    @Test
    fun `非图片数据返回 null`() {
        assertNull(ImageSizeProbe.probe(ByteArray(64)))
        assertNull(ImageSizeProbe.jpeg(ByteArray(64)))
        assertNull(ImageSizeProbe.png(ByteArray(8)))
    }

    @Test
    fun `流式读取能在读到压缩数据前就拿到宽高`() {
        val jpeg = jpegBytes(777, 555, sofMarker = 0xC0) + ByteArray(200_000) { 0x7F }
        val stream = ByteArrayInputStream(jpeg)
        assertEquals(777 to 555, ImageSizeProbe.read(stream, limit = 256 * 1024))
    }

    // ---------- 下面都是构造真实结构的测试数据 ----------

    /** 组装一个最小但结构合法的 JPEG：SOI + APP0 + 可选段 + SOF + SOS。 */
    private fun jpegBytes(
        width: Int,
        height: Int,
        sofMarker: Int,
        decoyBeforeSof: Boolean = false,
        insertDht: Boolean = false
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        // APP0 JFIF
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        out.write("JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        if (decoyBeforeSof) {
            // 段内容里塞入假的 FFC0 和假的宽高，验证解析器不会被骗。
            val decoy = ByteArray(300)
            decoy[10] = 0xFF.toByte()
            decoy[11] = 0xC0.toByte()
            decoy[12] = 0x00
            decoy[13] = 0x11
            decoy[14] = 0x08
            decoy[15] = 0x00
            decoy[16] = 0x01 // 假高度 1
            decoy[17] = 0x00
            decoy[18] = 0x01 // 假宽度 1
            out.write(segment(0xE1, decoy))
        }
        if (insertDht) {
            // 霍夫曼表：标记是 0xC4，必须被排除在 SOF 之外。
            out.write(segment(0xC4, ByteArray(20) { 0x11 }))
        }
        // SOF：长度 17（含自身），精度 8，高 2 字节，宽 2 字节，3 个分量。
        out.write(byteArrayOf(0xFF.toByte(), sofMarker.toByte(), 0x00, 0x11, 0x08))
        out.write(byteArrayOf((height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf(0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01))
        // SOS
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C, 0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3F, 0x00))
        out.write(ByteArray(32) { 0x5A })
        return out.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x0D))
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf((width shr 24).toByte(), (width shr 16).toByte(), (width shr 8).toByte(), width.toByte()))
        out.write(byteArrayOf((height shr 24).toByte(), (height shr 16).toByte(), (height shr 8).toByte(), height.toByte()))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        return out.toByteArray()
    }

    private fun gifBytes(width: Int, height: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf((width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte()))
        out.write(byteArrayOf((height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()))
        return out.toByteArray()
    }
}
