package com.audiometa.app.domain.usecase

/**
 * 组合刮削：**每个字段优先用哪个源**。
 *
 * 这份表不是猜的，是用 `scripts/plugin-report.mjs` 对五个源发真实请求，
 * 既看字段覆盖率也看字段内容质量测出来的（见 `docs/插件实测结果.md`）：
 *
 * - **专辑名**：Apple 全对；QQ / 网易 / 酷狗在单曲或数字发行时会把歌名填进专辑字段。Apple 必须第一。
 * - **艺术家名**：网易云会把 `周杰伦` 返回成 `Jay`，写进去等于把歌手名改坏，排最后。
 * - **trackNo / discNo**：Apple 给的是**专辑内序号**，QQ 和网易在单曲上给 1。
 * - **流派**：Apple 的最全（用户也点名"优先 am"）。
 * - **composer**：只有 Apple 稳定给。
 * - **albumArtist**：MusicBrainz 给得最全。
 *
 * 表里没出现的源自动排在专属列表之后，按用户设置的源优先级决定相对顺序 ——
 * 也就是说"谁有值就用谁的"，只是有一个质量优先的次序。
 *
 * ## 取值的实际规则（用户指定）
 *
 * - **流派**：固定 Apple Music → QQ → 网易 → 酷狗，前一个没有值才看下一个；
 * - **其它字段**：取**内容最长**的那个源（"什么最多就取什么"，
 *   例如注释是酷狗给得最长，注释就取酷狗的）；长度一样时，才用下面这张表
 *   和用户设置的源优先级来分先后。
 *
 * 所以这张表现在只负责**平手时的次序**，不再决定"一定用某个源"。
 *
 * ## 为什么单独抽出来
 *
 * 以前这份表写在 `ScrapeService` 里，但比对的是**插件 id**（`com.applemusic.source`）
 * 而表里写的是**短名**（`apple`）—— 于是 `indexOf` 永远返回 -1，整张表一次都没生效过，
 * 所有字段都退化成"按用户设置的源优先级"。用户看到的正是"流派没优先用 Apple"。
 * 现在短名归一化放在这里，`ScrapeService`（真正写入）和 `ScrapePreview`（确认弹窗）
 * 共用同一份策略：**弹窗里看到的顺序就是写进去的顺序**。
 */
object MetaMergePolicy {

    /** 字段 → 源短名的优先顺序。 */
    val FIELD_SOURCE_ORDER: Map<String, List<String>> = mapOf(
        // Apple 的专辑名质量碾压其它家
        "album" to listOf("apple", "qq", "kugou", "netease"),
        // 只有网易会返回英文艺名，放最后
        "artist" to listOf("qq", "apple", "kugou", "netease"),
        "title" to listOf("qq", "apple", "kugou", "netease"),
        // QQ 的日期与多数源一致
        "date" to listOf("qq", "apple", "kugou", "netease"),
        // Apple 给专辑内序号，其余在单曲上给 1
        "trackNo" to listOf("apple", "qq", "netease"),
        "discNo" to listOf("apple", "qq", "netease"),
        // 流派：**固定** Apple Music → QQ → 网易 → 酷狗（用户指定，不看用户设的源优先级）。
        "genre" to listOf("apple", "qq", "netease", "kugou"),
        "composer" to listOf("apple"),
        "albumArtist" to listOf("musicbrainz")
    )

    /**
     * 插件 id → 表里用的短名。
     *
     * `com.applemusic.source` → `apple`、`com.neteasecloudmusic.source` → `netease`。
     * 认不出来的插件退回 id 的最后一段，绝不会抛出。
     */
    fun shortName(pluginId: String): String {
        val id = pluginId.lowercase()
        return when {
            id.contains("applemusic") || id.contains("apple") -> "apple"
            id.contains("qqmusic") || id.contains("qq") -> "qq"
            id.contains("netease") -> "netease"
            id.contains("kugou") -> "kugou"
            id.contains("kuwo") -> "kuwo"
            id.contains("musicbrainz") -> "musicbrainz"
            id.contains("soda") -> "soda"
            else -> id.substringAfterLast('.').ifBlank { id }
        }
    }

    /** 这个字段有没有专属顺序表。 */
    fun hasFieldOrder(field: String): Boolean = !FIELD_SOURCE_ORDER[field].isNullOrEmpty()

    /**
     * 某个源在某个字段上的排序号，**越小越优先**。
     *
     * 专属表里的排前面（0、1、2…），表外的排在其后（`表长 + 100 + 全局优先级`），
     * 于是"表外的源"之间仍然按用户设置的源优先级分先后。
     */
    fun orderOf(field: String, pluginId: String, globalRank: Int): Int {
        val specific = FIELD_SOURCE_ORDER[field].orEmpty()
        val index = specific.indexOf(shortName(pluginId))
        if (index >= 0) return index
        return specific.size + 100 + globalRank
    }

    /**
     * 清洗曲风值。
     *
     * Apple Music 返回的是**层级分类**，用 ` / ` 分隔，例如：
     *
     * ```
     * 周杰伦 晴天      国语流行 / 音乐 / 国际流行
     * Adele Hello     国际流行 / 音乐
     * 米津玄師 Lemon    J-Pop / 音乐
     * ```
     *
     * 其中 `音乐` 是 Apple 的一级分类（Music），不是曲风，留着会让每首歌
     * 都多出一个没意义的"音乐"标签。这里把它剔除，其余部分保留。
     *
     * 放到这里是因为**真正写入**（[ScrapeService]）和**确认弹窗**
     * （[ScrapePreview]）必须用同一套清洗规则，否则弹窗里看到的
     * 和写进文件的不一样。
     */
    fun cleanGenre(raw: String): String {
        val parts = raw.split("/", "／", "|", "｜")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 一级分类不算曲风。
            .filter { it != "音乐" && it.lowercase() != "music" }
            .distinct()
        return parts.joinToString("/")
    }

    /**
     * 组合刮削确认弹窗里要逐行对比的字段（字段键 → 中文标签）。
     *
     * 顺序就是弹窗里的显示顺序（用户指定：艺术家图片之后按这个排）。
     */
    val PREVIEW_FIELDS: List<Pair<String, String>> = listOf(
        "genre" to "流派",
        "trackNo" to "音轨",
        "discNo" to "碟号",
        "date" to "日期",
        "language" to "语言",
        "copyright" to "版权",
        "comment" to "注释",
        "composer" to "作曲"
    )
}
