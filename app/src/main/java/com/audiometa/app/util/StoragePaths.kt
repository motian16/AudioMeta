package com.audiometa.app.util

import android.content.Context
import java.io.File

/**
 * 应用所有**非数据库**数据的落盘位置。
 *
 * 分两处放，界线是"这是应用自己管理的内容，还是用户可以随便动的东西"：
 *
 * ```
 * 私有目录 /data/user/0/<包名>/files/      ← 插件、标签规则
 *     plugins/<插件id>/                    插件源码
 *     tag_rules.json                       标签识别规则
 *
 * 外部目录 Android/data/<包名>/files/       ← 缓存和临时件
 *     thumbnails/audio_covers|temp         
 *     meta_images/                         「元信息」页里图片类标签的图
 *     fallback_lyrics/                      兜底歌词（用户可见、可改路径）
 *     temp/                                 一次性临时文件
 * ```
 *
 * **为什么插件和规则放私有目录**（用户要求）：它们是应用的运行依赖，
 * 放在 `Android/data` 里会被文件管理器误改、被清理工具当成垃圾顺手删掉，
 * 一删插件就全没了。缓存放外面则是为了用户能看见占用、也能手动清。
 *
 * **目录名一律英文**：中文目录名在部分文件管理器、MTP、脚本里有编码问题。
 * 老版本用过中文名，[migrateLegacyLayout] 会改名搬过来。
 *
 * **不创建空目录**：路径计算不 mkdirs，由真正要写文件的一方创建。
 */
object StoragePaths {

    const val DIR_PLUGINS = "plugins"
    const val DIR_PLATFORM_ICONS = "platform_icons"
    const val DIR_THUMBNAILS = "thumbnails"
    const val DIR_META_IMAGES = "meta_images"
    const val DIR_FALLBACK_LYRICS = "fallback_lyrics"
    const val DIR_TEMP = "temp"

    /** 私有根目录 `/data/user/0/<包名>/files`。 */
    fun privateRoot(context: Context): File = context.filesDir

    /** 外部根目录。外部存储不可用时退回私有目录，避免功能直接失效。 */
    fun externalRoot(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun dirFrom(root: File, vararg segments: String): File {
        var file = root
        segments.forEach { file = File(file, it) }
        return file
    }

    /** 外部目录下的子目录，**不创建**。 */
    fun dir(context: Context, vararg segments: String): File =
        dirFrom(externalRoot(context), *segments)

    /** 取一个子目录并确保存在，只在真的要写文件时调用。 */
    fun ensuredDir(context: Context, vararg segments: String): File =
        dir(context, *segments).apply { if (!exists()) mkdirs() }

    // ---- 插件（私有目录）----

    fun pluginsRoot(context: Context): File = dirFrom(privateRoot(context), DIR_PLUGINS)

    fun pluginDir(context: Context, pluginId: String): File = File(pluginsRoot(context), pluginId)

    // ---- 标签规则（私有目录）----

    fun tagRulesFile(context: Context): File = File(privateRoot(context), "tag_rules.json")

    // ---- 平台图标（私有目录）----

    /**
     * 「元信息」页每个分组左边那张平台图（`bilibili.png`、`qq.png`…）。
     *
     * 和插件、规则一样属于**运行依赖**，所以放私有目录：跟着订阅一起更新，
     * 也不能被文件管理器或清理工具顺手删掉（删了分组就只剩占位图）。
     */
    fun platformIconsDir(context: Context): File =
        dirFrom(privateRoot(context), DIR_PLATFORM_ICONS)

    // ---- 缩略图（外部目录）----

    fun thumbnailsRoot(context: Context): File = dir(context, DIR_THUMBNAILS)

    // ---- 兜底歌词（外部目录）----

    fun fallbackLyricsDir(context: Context): File = dir(context, DIR_FALLBACK_LYRICS)

    fun resolveFallbackLyricsDir(context: Context, configured: String): String =
        if (configured.isBlank()) fallbackLyricsDir(context).absolutePath else configured

    // ---- 临时文件（外部目录）----

    fun tempDir(context: Context): File = dir(context, DIR_TEMP)

    /** 清掉所有空目录（历史遗留的空文件夹）。 */
    fun pruneEmptyDirectories(context: Context) {
        listOf(externalRoot(context), privateRoot(context)).forEach { root ->
            if (!root.isDirectory) return@forEach
            runCatching {
                root.listFiles()?.filter { it.isDirectory }?.forEach { pruneRecursively(it) }
            }
        }
    }

    private fun pruneRecursively(dir: File) {
        dir.listFiles()?.filter { it.isDirectory }?.forEach { pruneRecursively(it) }
        val remaining = dir.listFiles()
        if (remaining != null && remaining.isEmpty()) runCatching { dir.delete() }
    }

    /**
     * 一次性把老布局搬成新布局。
     *
     * 走过的路径：
     * - 目录名从中文改成英文：`缩略图/`、`插件/`、`兜底歌词/`、`临时/`、`元信息图片/`
     * - **插件和规则从 `Android/data` 搬进私有目录**（这一版的要求）
     *
     * 只在目标不存在时搬，不覆盖新数据。
     */
    fun migrateLegacyLayout(context: Context) {
        val external = externalRoot(context)
        runCatching {
            listOf(
                "缩略图" to DIR_THUMBNAILS,
                "插件" to DIR_PLUGINS,
                "兜底歌词" to DIR_FALLBACK_LYRICS,
                "临时" to DIR_TEMP,
                "元信息图片" to DIR_META_IMAGES
            ).forEach { (old, new) -> moveDir(external, old, File(external, new)) }

            // 插件目录 → 私有目录
            moveDir(external, DIR_PLUGINS, pluginsRoot(context))

            // 规则文件 → 私有目录
            val oldRules = File(external, "tag_rules.json")
            val newRules = tagRulesFile(context)
            if (oldRules.isFile && !newRules.exists()) {
                runCatching {
                    oldRules.copyTo(newRules, overwrite = false)
                    oldRules.delete()
                }
            }
        }.onFailure { AppLog.d(TAG, "迁移旧目录失败（不影响使用）：" + it.message) }
    }

    private fun moveDir(root: File, old: String, target: File) {
        val from = File(root, old)
        if (!from.isDirectory || from.absolutePath == target.absolutePath) return
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            if (from.renameTo(target)) {
                AppLog.i(TAG, "目录已迁移：" + from.absolutePath + " → " + target.absolutePath)
            }
            return
        }
        target.mkdirs()
        from.listFiles()?.forEach { child ->
            val to = File(target, child.name)
            if (to.exists()) return@forEach
            if (!child.renameTo(to)) {
                runCatching {
                    if (child.isDirectory) child.copyRecursively(to, overwrite = false)
                    else child.copyTo(to, overwrite = false)
                    child.deleteRecursively()
                }
            }
        }
        if (from.listFiles().isNullOrEmpty()) from.delete()
    }

    private const val TAG = "StoragePaths"
}
