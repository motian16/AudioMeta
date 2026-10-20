package com.audiometa.app.media.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * FLAC 的 Vorbis Comment 手动解析。
 *
 * 用户拿 MediaInfo 对比过：文件里明明有的标签（例如 mora 文件里的
 * `Album/Composer`）软件没读出来 —— 解析库对自定义字段的处理不完全可靠。
 * 这里自己按容器格式扫一遍，读到什么就是什么。
 */
class VorbisCommentTest {

    /** 造一个只有文件头 + VORBIS_COMMENT 的 FLAC，够解析器用就行。 */
    private fun flacWith(comments: List<String>): File {
        val vendor = "reference libFLAC 1.3.2".toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        // vendor 长度（小端 u32）+ vendor
        body.write(u32(vendor.size))
        body.write(vendor)
        // 条目数
        body.write(u32(comments.size))
        comments.forEach { line ->
            val bytes = line.toByteArray(Charsets.UTF_8)
            body.write(u32(bytes.size))
            body.write(bytes)
        }
        val block = body.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // 最后一块标志(0x80) | 类型 4 = VORBIS_COMMENT，后面跟 3 字节大端长度
        out.write(0x80 or 4)
        out.write((block.size shr 16) and 0xFF)
        out.write((block.size shr 8) and 0xFF)
        out.write(block.size and 0xFF)
        out.write(block)

        val file = File.createTempFile("audiometa-test", ".flac")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    @Test
    fun `能读出全部自定义字段包括带斜杠的键名`() {
        val file = flacWith(
            listOf(
                "TITLE=某首歌",
                "ALBUM/COMPOSER=KOKIA",
                "45B1D925-1448-5784-B4DA-B89901050A13=10006001",
                "FF8CA75F-2D68-52EB-85D6-1580486025A4=72778133",
                "ORGANIZATION=JASRAC / Victor"
            )
        )
        val tags = TagReader().readRawVorbisComments(file)
        // 带斜杠的键名最容易在库那一层被吃掉，这里必须原样出现。
        assertEquals("KOKIA", tags["ALBUM/COMPOSER"])
        assertEquals("10006001", tags["45B1D925-1448-5784-B4DA-B89901050A13"])
        assertEquals("72778133", tags["FF8CA75F-2D68-52EB-85D6-1580486025A4"])
        assertEquals("JASRAC / Victor", tags["ORGANIZATION"])
        assertEquals("某首歌", tags["TITLE"])
    }

    @Test
    fun `空值和没有等号的条目会被跳过而不是写进结果`() {
        val file = flacWith(
            listOf(
                "MOOD=",
                "GENRENUMBER=",
                "没有等号",
                "REAL=value"
            )
        )
        val tags = TagReader().readRawVorbisComments(file)
        assertTrue(tags.keys.none { it == "MOOD" })
        assertTrue(tags.keys.none { it == "GENRENUMBER" })
        assertEquals("value", tags["REAL"])
    }

    @Test
    fun `不是 FLAC 的文件返回空而不是报错`() {
        val file = File.createTempFile("audiometa-test", ".mp3")
        file.deleteOnExit()
        file.writeBytes(ByteArray(64))
        assertTrue(TagReader().readRawVorbisComments(file).isEmpty())
    }
}
