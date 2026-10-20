package com.audiometa.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.audiometa.app.util.AppLog
import java.io.File

/**
 * 相册（DCIM/Pictures）读写。
 *
 * 覆盖图片固定存到 /storage/emulated/0/Pictures/AudioMeta/：
 * - Android 10+ 走 MediaStore 插入，免存储权限，相册和文件管理器都能立刻看到；
 * - Android 9 及以下直接写文件，再用 MediaScanner 登记一次。
 */
object ImageStore {

    const val ALBUM = "AudioMeta"

    /** Pictures/AudioMeta。Environment 的常量不是编译期常量，只能用 val。 */
    val RELATIVE_DIR: String = Environment.DIRECTORY_PICTURES + "/" + ALBUM

    /** 图片本体，用于解码预览。 */
    data class Loaded(val bytes: ByteArray, val mime: String)

    /**
     * 把图片字节写进相册目录。
     *
     * @return 落盘路径；失败返回 null。
     */
    fun saveToAlbum(context: Context, displayName: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val safe = sanitize(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safe, bytes)
        } else {
            saveViaFile(context, safe, bytes)
        }
    }

    private fun saveViaMediaStore(context: Context, safe: String, bytes: ByteArray): String? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safe + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // MediaStore 的绝对路径就是 Pictures/AudioMeta/xxx.jpg，直接用约定路径回报，
        // 免得再查一次 ContentResolver。
        File(Environment.getExternalStorageDirectory(), RELATIVE_DIR + "/" + safe + ".jpg").absolutePath
    }.onFailure { AppLog.w(TAG, "写入相册失败：" + it.message) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun saveViaFile(context: Context, safe: String, bytes: ByteArray): String? = runCatching {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        val target = File(dir, safe + ".jpg")
        target.writeBytes(bytes)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
        target.absolutePath
    }.onFailure { AppLog.w(TAG, "写入相册失败：" + it.message) }.getOrNull()

    /** 从相册/文件选择器给的 uri 读出图片字节。 */
    fun read(context: Context, uri: Uri): Loaded? = runCatching {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null
        val mime = resolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.startsWith("image/") }
            ?: sniff(bytes)
        Loaded(bytes, mime)
    }.onFailure { AppLog.w(TAG, "读取所选图片失败：" + it.message) }.getOrNull()

    private fun sniff(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            bytes.size >= 8 && at(0) == 0x89 && at(1) == 0x50 -> "image/png"
            bytes.size >= 12 && at(0) == 0x52 && at(8) == 0x57 -> "image/webp"
            bytes.size >= 6 && at(0) == 0x47 && at(1) == 0x49 -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /** 文件名里的非法字符换成下划线，空名兜底成 artwork。 */
    private fun sanitize(raw: String): String =
        raw.map { if (it in "/\\:*?\"<>|\n\r\t") '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
            .ifBlank { "artwork" }
            .take(80)

    private const val TAG = "ImageStore"
}
