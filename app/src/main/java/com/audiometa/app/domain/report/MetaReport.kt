package com.audiometa.app.domain.report

import com.audiometa.app.data.db.MetaTagEntity
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.tagrules.TagEntry
import com.audiometa.app.domain.tagrules.TagRules
import com.audiometa.app.domain.tagrules.TagSummary
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 元信息报告（Markdown）。
 *
 * 用户要求（设置 - 调试 - 发送报告）：
 * - **复制**：按来源分组复制出一份报告，报告用 Markdown 语法；
 * - **发送**：只发「未知」那一组 —— 也就是不在元信息规则内、被 App 归到
 *   「未知标签 / 未知图片」的标签。这些才是需要加规则的，其它都是已经认识的数据，
 *   没必要跟着一起发出去。
 *
 * 报告长得像这样：
 *
 * ```markdown
 * # AudioMeta 元信息报告
 *
 * - 生成时间：2026-10-19 06:10
 * - 应用版本：1.2（6）
 * - 曲库：128 首 / 自定义标签 23 种
 *
 * ## 网易云音乐
 *
 * ### lyric_source
 * - 出现：12 首
 * - 取值：`netease`
 * - 说明：规则里还没有这个标签的说明
 *
 * ### …
 * ```
 */
object MetaReport {

    /** 报告里一条取值最多列几个（同一个键可能有几十种取值）。 */
    private const val MAX_VALUES = 12

    /** 报告里一个标签最多列几首曲目。 */
    private const val MAX_TRACKS = 20

    /**
     * 组一份 Markdown 报告。
     *
     * @param unknownOnly true = 只要「未知标签 / 未知图片」那几组（"发送"用）；
     *   false = 全部来源分组（"复制"用）。
     * @return 报告正文；曲库里一条自定义标签都没有时返回空串（调用方据此提示）。
     */
    suspend fun markdown(unknownOnly: Boolean): String = withContext(Dispatchers.IO) {
        val rows = runCatching { ServiceLocator.metaTags.all() }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext ""
        val tracks = runCatching { ServiceLocator.tracks.allOnce() }.getOrDefault(emptyList())
            .associateBy { it.id }

        val summaries = TagRules.summarize(rows.map { it.toEntry() })
        if (summaries.isEmpty()) return@withContext ""

        val kept = if (unknownOnly) summaries.filter { isUnknownGroup(it.group) } else summaries
        if (kept.isEmpty()) return@withContext ""

        // 键 → 这条键在曲库里出现过的行（取值、曲目都从它来）。
        val byKey = rows.groupBy { TagRules.displayKey(it.key).lowercase() }
        val builder = StringBuilder()
        builder.appendLine("# AudioMeta 元信息报告")
        builder.appendLine()
        builder.appendLine("- 生成时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        builder.appendLine("- 应用版本：" + versionLabel())
        builder.appendLine(
            "- 曲库：" + tracks.size + " 首 / 自定义标签 " + kept.size + " 种" +
                if (unknownOnly) "（仅未知组）" else ""
        )
        builder.appendLine()

        kept.groupBy { it.group }.forEach { (group, tags) ->
            builder.appendLine("## " + group)
            builder.appendLine()
            tags.forEach { tag ->
                builder.appendLine("### " + tag.key)
                builder.appendLine("- 出现：" + tag.count + " 首")
                // 规则里没有说明才写这一行：有说明的标签已经认识，不需要别人再查。
                if (tag.keyInfo?.description.isNullOrBlank()) {
                    builder.appendLine("- 说明：规则里还没有这个标签的说明")
                }
                // 原始键名（和显示键名不同时才有意义，例如 `----:com.apple.iTunes:…`）。
                tag.rawKeys.firstOrNull { it != tag.key }?.let {
                    builder.appendLine("- 原始键名：`" + it + "`")
                }
                val values = byKey[tag.key.lowercase()].orEmpty().map { it.value }.distinct()
                if (values.isEmpty()) {
                    builder.appendLine("- 取值：（无值）")
                } else {
                    builder.appendLine("- 取值：")
                    values.take(MAX_VALUES).forEach { value ->
                        builder.appendLine("  - " + describe(value))
                    }
                    if (values.size > MAX_VALUES) {
                        builder.appendLine("  - …另有 " + (values.size - MAX_VALUES) + " 种")
                    }
                }
                // 曲目：让报告拿出去能对上具体是哪首歌（用户要求）。
                val hits = byKey[tag.key.lowercase()].orEmpty()
                    .mapNotNull { tracks[it.trackId] }
                    .distinctBy { it.id }
                if (hits.isNotEmpty()) {
                    builder.appendLine("- 曲目：")
                    hits.take(MAX_TRACKS).forEach { track ->
                        builder.appendLine(
                            "  - " + track.title.ifBlank { track.path.substringAfterLast('/') } +
                                " — " + track.artist.ifBlank { "未知艺术家" } +
                                " · " + track.album.ifBlank { "未知专辑" } +
                                " (`" + track.path.substringAfterLast('/') + "`)"
                        )
                    }
                    if (hits.size > MAX_TRACKS) {
                        builder.appendLine("  - …另有 " + (hits.size - MAX_TRACKS) + " 首")
                    }
                }
                builder.appendLine()
            }
        }
        AppLog.i(
            "MetaReport",
            "生成元信息报告：unknownOnly=" + unknownOnly + "，标签 " + kept.size + " 种，字数 " + builder.length
        )
        builder.toString().trimEnd() + "\n"
    }

    /**
     * 「未知」组的判定。
     *
     * 规则没覆盖到的键会被 [TagSummary.group] 归到「未知标签」，
     * 图片类标签归到「未知图片」那一组（组名可能被规则文件改写）。
     * 两者都是"不认识的东西"，所以用前缀判断，不写死具体名字。
     */
    private fun isUnknownGroup(name: String): Boolean =
        name == TagSummary.UNKNOWN_GROUP ||
            name == TagSummary.IMAGE_GROUP ||
            name.startsWith("未知")

    /** 报告里一行取值：图片标记换成可读的文字，普通值用行内代码包起来。 */
    private fun describe(raw: String): String = when {
        com.audiometa.app.media.tag.MetaImageStore.isImageValue(raw) ->
            "（图片：" + com.audiometa.app.media.tag.MetaImageStore.fileNameOf(raw) + "）"
        raw.isBlank() -> "（无值）"
        else -> "`" + raw.replace("`", "'") + "`"
    }

    private fun versionLabel(): String =
        runCatching {
            com.audiometa.app.di.ServiceLocator.versionName + "（" +
                com.audiometa.app.di.ServiceLocator.versionCode + "）"
        }.getOrDefault(com.audiometa.app.di.ServiceLocator.versionName)

    /**
     * 数据库里的一行 → 规则的输入。
     *
     * 和「元信息」页用的是同一份映射（那边有一份等价的私有实现），
     * 这里自己写一份是为了不让 domain 层反向依赖 UI 包。
     */
    private fun MetaTagEntity.toEntry() = TagEntry(
        key = key,
        value = value,
        platform = platform.takeIf { it.isNotBlank() },
        tool = tool.takeIf { it.isNotBlank() },
        hints = hints.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
        isStandard = false
    )
}
