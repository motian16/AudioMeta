package com.audiometa.app.media.tag

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Builds a FLAC METADATA_BLOCK_PICTURE payload (without the block header).
 * Used for Vorbis comments and as a reference layout.
 */
object FlacPicture {
    fun encode(
        pictureType: Int,
        mime: String,
        description: String,
        width: Int,
        height: Int,
        data: ByteArray,
        colorDepth: Int = 24,
        colors: Int = 0
    ): ByteArray {
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
        val descBytes = description.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(data.size + mimeBytes.size + descBytes.size + 32)
        out.writeInt(pictureType)
        out.writeInt(mimeBytes.size)
        out.write(mimeBytes)
        out.writeInt(descBytes.size)
        out.write(descBytes)
        out.writeInt(width)
        out.writeInt(height)
        out.writeInt(colorDepth)
        out.writeInt(colors)
        out.writeInt(data.size)
        out.write(data)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}