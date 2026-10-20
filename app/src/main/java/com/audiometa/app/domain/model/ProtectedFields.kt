package com.audiometa.app.domain.model

/**
 * 刮削**不许改**的字段 —— 一条红线。
 *
 * 详情页组合刮削弹窗的上半部分写着「基础标签（只读，不会修改）」，指的是
 * **标题 / 艺术家 / 专辑**这三项。但写入侧以前根本没有这条规矩：
 *
 * - `TagWriter.applyMeta` 会把 `TrackMeta` 里的标题、艺术家、专辑一起写进文件；
 * - 详情页的刮削入口又是"覆盖"策略（`forceOverwrite = true`）；
 * - 艺术家图刮削还会**扩展到该艺术家名下的全部曲目**。
 *
 * 三件事叠在一起就是一个数据破坏级的 bug：给一位艺术家刮图，他名下所有歌的
 * 内嵌标题都变成了同一次搜索匹配到的那一首。
 *
 * 现在规则是唯一的一份：**刮削只能写入 [isProtected] 之外的字段**，
 * 标题/艺术家/专辑只有详情页「元信息」区域的手动编辑（`RawTagWriter`）能改。
 */
object ProtectedFields {

    /** 受保护的 jaudiotagger 字段名（和 `FieldKey` 的枚举名一致）。 */
    private val KEYS = setOf("TITLE", "ARTIST", "ALBUM")

    /** 界面上给用户看的说法。 */
    private val LABELS = mapOf(
        "TITLE" to "标题",
        "ARTIST" to "艺术家",
        "ALBUM" to "专辑"
    )

    /** 这个字段名（`FieldKey` 的枚举名，大小写不敏感）是不是刮削不能碰的。 */
    fun isProtected(fieldKeyName: String): Boolean = fieldKeyName.trim().uppercase() in KEYS

    /** 中文标签，用来写日志和界面提示。 */
    fun labelOf(fieldKeyName: String): String =
        LABELS[fieldKeyName.trim().uppercase()] ?: fieldKeyName

    /**
     * 这份元信息里有哪些受保护字段带了值（返回中文名）。
     *
     * 写入侧用它记一条日志：不是因为要写它们，而是为了在有人把这三个字段
     * 塞回刮削管线时立刻看得见。
     */
    fun protectedOf(meta: TrackMeta): List<String> = buildList {
        if (meta.title.isNotBlank()) add(LABELS.getValue("TITLE"))
        if (meta.artist.isNotBlank()) add(LABELS.getValue("ARTIST"))
        if (meta.album.isNotBlank()) add(LABELS.getValue("ALBUM"))
    }

    /** 手动编辑的键值里混进受保护字段时挑出来（给界面提示用）。 */
    fun protectedIn(tags: Map<String, String>): List<String> =
        tags.keys.filter { isProtected(it) }.map { labelOf(it) }
}

/**
 * 【死锁】音频文件的三项基础数据（标题/艺术家/专辑）是不是**一项都没有**。
 *
 * 一项都没有就没有任何可用的搜索关键词 —— 这样的文件在所有会联网的刮削入口
 * （批量刮削、结果挑选、歌词搜索）里都**不发起任何网络搜索**，直接跳过；
 * 想补数据请先在歌曲详情页的「元信息」里手动编辑（那是唯一能改这三项的地方）。
 */
fun TrackMeta.lacksBasics(): Boolean =
    title.isBlank() && artist.isBlank() && album.isBlank()
