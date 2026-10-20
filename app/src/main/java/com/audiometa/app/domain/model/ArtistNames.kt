package com.audiometa.app.domain.model

/**
 * 合作写法的艺术家名拆分。
 *
 * 一首歌的 artist 字段经常写着好几位艺术家（`A/B`、`A feat. B`、`A & B`、`A、B`…）。
 * 把整串当成一个艺术家名的话，艺术家页会冒出一堆"A/B"这种没人认识的条目，
 * 刮艺术家图时也会漏掉这些合作曲。
 *
 * 这段逻辑原来只在 [com.audiometa.app.ui.screen.library.LibraryViewModel] 里，
 * 现在刮削也要按同一套规则判断"这首歌是不是这位艺术家的"，两边拆法一旦不一致
 * 就会出现"艺术家页里看得到、刮削却漏掉"的怪现象，所以提到领域层共用一份。
 */
object ArtistNames {

    /**
     * 合作写法的分隔符。
     *
     * 顺序有讲究：先把 "feat." / "ft." / "with" 这类词切掉，
     * 再切 "/"、"、"、"&"、";" 这些符号，最后才处理单独的 " x "（怕误伤名字里的 x）。
     *
     * `feat` / `ft` 后面**不能**用 `\b` 收尾：`\.?` 吃掉那个点之后，
     * "." 和空格之间没有词边界，正则会退回去只匹配 "feat"，把点留在原地 ——
     * 于是 "A feat. B" 拆出来是 ["A", ". B"]，那位艺术家就叫 ". B"，
     * 按名字匹配时谁也认不出来。改用"后面跟空格或结尾"的前瞻。
     */
    private val SPLIT = Regex(
        "\\s*(?:/|／|、|;|；|&|＆|\\+|,|，|\\bfeat\\.?(?=\\s|$)|\\bft\\.?(?=\\s|$)|\\bwith\\b|\\bx\\b)\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * 把 artist 字段拆成单个艺术家名。
     *
     * 拆不动（只有一个名字）就原样返回一条；字段为空返回空列表 ——
     * "未知艺术家"这类占位是界面的事，不在这里编。
     */
    fun of(raw: String): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val parts = text.split(SPLIT)
            .map { it.trim() }
            // 只剩标点的碎片（例如拆 "A / / B" 留下的"/"）不是艺术家名，丢掉。
            .filter { it.isNotBlank() && normalize(it, stripArticle = false).isNotEmpty() }
            .distinct()
        return parts.ifEmpty { listOf(text) }
    }

    /**
     * 艺术家名的归一化：大小写、空格、标点都不算差别。
     *
     * 原本只在 [com.audiometa.app.domain.usecase.ScrapeService] 里给候选排序用，
     * 现在"这首歌属不属于这位艺术家"也要用同一套归一化，所以搬到这里共用一份 ——
     * 两处各写一套迟早会出现"排序认为是一个人、写图时又认为不是"。
     *
     * @param stripArticle 要不要把开头的 the / a / an 去掉。
     *   候选排序要（"The Beatles" 和 "Beatles" 是同一支乐队）；
     *   判断"这首歌属不属于这位艺术家"**不要** —— 那个字母可能**就是名字本身**
     *   （叫 "A"、"The" 的歌手并不罕见），去掉之后两边都是空串，
     *   等于把这位艺术家的歌整个漏掉。
     */
    /**
     * 归一化时要抹掉的空白与标点。
     *
     * 编译成常量，**不要**在 [normalize] 里现写 `Regex(...)` ——
     * 那个函数按"歌 × 艺术家 × 每次刷新"被调用，每次现编译两条正则是纯粹的浪费
     *（滑页签卡顿里就有它一份）。
     */
    private val PUNCTUATION = Regex("[\\s\\p{Punct}、。・·，,;；&＆/／|｜]+")
    private val LEADING_ARTICLE = Regex("^(the|a|an)")

    fun normalize(text: String, stripArticle: Boolean = true): String {
        val folded = text.lowercase()
            .replace(PUNCTUATION, "")
            .trim()
        if (!stripArticle) return folded
        return folded.replace(LEADING_ARTICLE, "")
    }

    /**
     * 这首曲目算不算挂在艺术家 [artist] 名下。
     *
     * 两种情况都算：
     * - **整串相同**：`A & B`、`Simon & Garfunkel` 本身就是组合名，拆开反而谁都对不上；
     * - **拆开后某一段相同**：`A/B`、`A feat. B`、`A、B` 这些写法里，
     *   这首歌同时属于 A 和 B（艺术家页也是这么分组的）。
     *
     * 只比"相等"，不套包含判断：包含会把 Jay 算成 Jay-Z 的歌，
     * 而这里的结果决定"这张艺术家图要不要写进这个文件"，宁可漏也不能错。
     */
    fun matches(artistField: String, artist: String): Boolean {
        val want = normalize(artist, stripArticle = false)
        if (want.isEmpty()) return false
        if (normalize(artistField, stripArticle = false) == want) return true
        return of(artistField).any { normalize(it, stripArticle = false) == want }
    }
}
