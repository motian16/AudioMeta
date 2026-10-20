package com.audiometa.app.media.lyrics

import com.audiometa.app.util.AppLog
import java.io.File

/**
 * 兜底歌词：把选定类型的歌词额外导出到统一目录，并保持歌曲原有的目录结构。
 *
 * 例：/storage/emulated/0/Download/巴赫旧约.flac
 *   -> /storage/emulated/0/Music/兜底歌词/Download/巴赫旧约.lrc
 *
 * 非主存储卷（例如 SD 卡）会在最前面加上卷名，避免不同卷上的同名目录互相覆盖。
 */
class FallbackLyricsWriter(private val primaryRoot: String = DEFAULT_PRIMARY_ROOT) {

    fun targetFile(songPath: String, baseDir: String): File {
        val relative = relativeSongPath(songPath) ?: sanitizeName(songPath)
        val target = File(baseDir, relative)
        val stem = target.nameWithoutExtension
        val parent = target.parentFile
        return if (parent == null) File(baseDir, stem + ".lrc") else File(parent, stem + ".lrc")
    }

    fun exists(songPath: String, baseDir: String): Boolean = targetFile(songPath, baseDir).isFile

    fun write(songPath: String, baseDir: String, content: String): File? = runCatching {
        val target = targetFile(songPath, baseDir)
        target.parentFile?.mkdirs()
        target.writeText(content)
        AppLog.d(TAG, "兜底歌词已写入 " + target.absolutePath)
        target
    }.onFailure {
        AppLog.e(TAG, "兜底歌词写入失败：" + songPath, it)
    }.getOrNull()

    private fun relativeSongPath(path: String): String? {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith(primaryRoot + "/")) {
            return normalized.removePrefix(primaryRoot + "/")
        }
        val storage = "/storage/"
        if (normalized.startsWith(storage)) {
            val rest = normalized.removePrefix(storage)
            val volume = rest.substringBefore('/')
            val inner = rest.substringAfter('/', "")
            if (volume.isNotBlank() && inner.isNotBlank()) return volume + "/" + inner
        }
        return null
    }

    private fun sanitizeName(path: String): String =
        path.replace('\\', '_').replace('/', '_').replace(':', '_').trimStart('_')

    companion object {
        const val TAG = "FallbackLyrics"
        const val DEFAULT_PRIMARY_ROOT = "/storage/emulated/0"
    }
}
