package com.audiometa.app.plugin.install

import android.content.Context
import android.net.Uri
import com.audiometa.app.data.repo.PluginRepository
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginExportException(message: String) : Exception(message)

/** 把已安装插件的目录原样打包成 zip，方便备份和分享。 */
class PluginExporter(
    private val context: Context,
    private val repository: PluginRepository
) {
    suspend fun export(pluginId: String, destination: Uri): String {
        val record = repository.getPlugin(pluginId)
            ?: throw PluginExportException("插件不存在: " + pluginId)
        val dir = File(record.installPath)
        if (!dir.isDirectory) throw PluginExportException("安装目录不存在: " + record.installPath)
        val name = record.pluginId + "-" + record.pluginVersion + ".zip"
        context.contentResolver.openOutputStream(destination)?.use { out ->
            writeZip(dir, out)
        } ?: throw PluginExportException("无法写入导出位置")
        return name
    }

    private fun writeZip(root: File, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
