package com.audiometa.app.ui.component

import com.audiometa.app.data.db.TrackEntity

/**
 * 列表里显示曲目文字的统一规则。
 *
 * 用户要求："**在所有列表，无内嵌标签就显示文件名**"。
 *
 * 背景：扫描时如果文件里没有标题标签，入库的 `title` 会被兜成"去掉扩展名的文件名"。
 * 于是列表里看到的是一串没有扩展名的半截名字，跟磁盘上的文件对不上 ——
 * 用户想按列表里的名字去文件管理器找文件时找不到。
 * 所以这里统一判断：**标题看起来是从文件名兜出来的，就直接显示完整文件名（带扩展名）**。
 *
 * 这几项刻意做成 [TrackEntity] 的扩展属性而不是各页面各写一遍：
 * 歌曲列表、网格、艺术家/曲风的曲目列表、搜索结果、元信息标签下的曲目列表
 * 全都用同一套规则，不会出现"这个页面显示文件名、那个页面显示半截名字"。
 */

/** 磁盘上的文件名（含扩展名）。 */
val TrackEntity.fileName: String
    get() = path.substringAfterLast('/').ifBlank { path }

/** 去掉扩展名的文件名。扫描时的兜底标题就是它。 */
val TrackEntity.fileStem: String
    get() = fileName.substringBeforeLast('.')

/**
 * 标题是否来自"文件名兜底"（也就是文件里没有标题标签）。
 *
 * 判据两条：标题为空，或者标题正好等于去掉扩展名的文件名。
 * 后者理论上可能误判（标签标题恰好和文件名同名的文件），代价只是多显示一个扩展名。
 */
val TrackEntity.titleFromFileName: Boolean
    get() = title.isBlank() || title.trim() == fileStem

/**
 * 列表里的主标题。
 *
 * 没有内嵌标题标签时显示**完整文件名**（用户要求），否则显示标签里的标题。
 */
val TrackEntity.listTitle: String
    get() = if (titleFromFileName) fileName else title

/**
 * 列表里的副标题：艺术家 · 专辑。
 *
 * 两个都空（说明文件里确实没有内嵌标签）时返回空串，界面直接不画这一行 ——
 * 与其显示"未知艺术家 · 未知专辑"这种占位字，不如把位置让给文件名。
 */
val TrackEntity.listSubtitle: String
    get() = listOf(artist, album)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" · ")
