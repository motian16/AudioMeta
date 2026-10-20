package com.audiometa.app.media.tag

import android.content.Context
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths
import java.io.File
import java.security.MessageDigest

/**
 * 元信息页里"值是图片"的那些标签，图片落盘的地方。
 *
 * 有些自定义标签的内容本身就是一张图（封面之外的插图、厂牌 logo、自定义 atom…）。
 * 这种值不能当字符串塞进数据库：
 *
 * - 数据库里只存一个**标记**：`IMG:<sha1>.<ext>`；
 * - 真正的图片放在 `files/元信息图片/` 下，按内容哈希命名 ——
 *   同一张图在几百首歌里出现也只占一份。
 *
 * 哈希命名还带来一个好处：重新扫描时同样的图片算出的名字一样，
 * 不会产生"每扫一次多一份"的垃圾。
 */
object MetaImageStore {

    private const val DIR = com.audiometa.app.util.StoragePaths.DIR_META_IMAGES

    /** 值的前缀。数据库里靠它认出"这是个图片值"。 */
    const val PREFIX = com.audiometa.app.domain.tagrules.TagRules.IMAGE_VALUE_PREFIX

    fun isImageValue(value: String): Boolean = value.startsWith(PREFIX)

    /** 从值里取出文件名（不含前缀）。 */
    fun fileNameOf(value: String): String = value.removePrefix(PREFIX)

    /** 值对应的图片文件；不是图片值或文件不存在时返回 null。 */
    fun fileOf(context: Context, value: String): File? {
        if (!isImageValue(value)) return null
        val name = fileNameOf(value)
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        val file = File(StoragePaths.dir(context, DIR), name)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * 存一张图，返回可以直接入库的标记值。
     *
     * 失败返回 null —— 调用方退回"不记录这个字段"，绝不把半个文件写进去。
     */
    fun store(context: Context, bytes: ByteArray, mime: String): String? = runCatching {
        if (bytes.isEmpty()) return null
        val ext = extensionOf(mime)
        val name = sha1(bytes) + "." + ext
        val dir = StoragePaths.ensuredDir(context, DIR)
        val target = File(dir, name)
        // 内容哈希同名 = 同一张图，已经有了就不重复写。
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            val tmp = File(dir, name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
        PREFIX + name
    }.getOrElse {
        AppLog.d(TAG, "元信息图片落盘失败：" + it.message)
        null
    }

    /**
     * 清掉不再被任何标签引用的图片。
     *
     * [keep] 是数据库里所有值里出现的文件名集合。曲目被删、标签被改之后，
     * 旧的图片就没人引用了 —— 不清理的话这个目录会只增不减。
     */
    fun prune(context: Context, keep: Set<String>): Int {
        var removed = 0
        runCatching {
            val dir = StoragePaths.dir(context, DIR)
            if (!dir.isDirectory) return 0
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp") || file.name !in keep) {
                    if (file.delete()) removed++
                }
            }
        }
        return removed
    }

    private fun extensionOf(mime: String): String = when {
        mime.contains("png", true) -> "png"
        mime.contains("webp", true) -> "webp"
        mime.contains("gif", true) -> "gif"
        mime.contains("bmp", true) -> "bmp"
        else -> "jpg"
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val TAG = "MetaImageStore"
}
