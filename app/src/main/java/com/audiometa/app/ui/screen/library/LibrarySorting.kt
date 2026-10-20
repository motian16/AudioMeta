package com.audiometa.app.ui.screen.library

import com.audiometa.app.data.db.TrackEntity

/**
 * 音频库三个页签的排序。
 *
 * 抽成纯函数是为了能直接跑单测：这是"用户看得见的顺序"，
 * 而它有两个容易写错的地方 ——
 *
 * 1. **降序时只翻主键**：兜底键（标题 / 名字）仍然升序。
 *    以前是 `comparator.reversed().thenBy { 标题 }`，行为就是这个；
 *    顺手把兜底键也反过来，同值的那一批顺序就变了。
 * 2. **排序键只算一次**：`compareBy { it.path.substringAfterLast('/').lowercase() }`
 *    会在每次比较时重跑 keySelector。5000 首要比较约 6 万次，每次都分配临时字符串，
 *    滑页签时全砸在同一帧里 —— 这是曲库卡顿的一部分。所以这里先把键算进一张表，
 *    比较时只查表。
 */
internal object LibrarySorting {

    /** 曲目排序。六个字段：修改时间、文件名、艺术家名、年份、音频长短、大小。 */
    fun tracks(list: List<TrackEntity>, layout: TabLayout): List<TrackEntity> {
        if (list.size < 2) return list
        val field = layout.songSort()
        val titles = HashMap<Long, String>(list.size * 2)
        val keys = HashMap<Long, Comparable<Any>>(list.size * 2)
        list.forEach { track ->
            titles[track.id] = track.title.lowercase()
            @Suppress("UNCHECKED_CAST")
            keys[track.id] = (when (field) {
                // 「修改时间」用文件本身的 mtime —— 和文件管理器里看到的是同一个值。
                SortField.MODIFIED -> track.lastModified
                SortField.FILE_NAME -> track.path.substringAfterLast('/').lowercase()
                SortField.ARTIST -> track.artist.lowercase()
                SortField.YEAR -> track.year
                SortField.DURATION -> track.durationMs
                SortField.SIZE -> track.fileSize
            }) as Comparable<Any>
        }
        val descending = layout.sortDescending
        return list.sortedWith { a, b ->
            val primary = keys.getValue(a.id).compareTo(keys.getValue(b.id))
            val ordered = if (descending) -primary else primary
            if (ordered != 0) ordered else titles.getValue(a.id).compareTo(titles.getValue(b.id))
        }
    }

    /** 艺术家排序：数量 / 艺术家名（默认数量）。 */
    fun artists(list: List<ArtistEntry>, layout: TabLayout): List<ArtistEntry> {
        if (list.size < 2) return list
        val names = HashMap<String, String>(list.size * 2)
        list.forEach { names[it.name] = it.name.lowercase() }
        val byCount = layout.artistSort() != ArtistSort.NAME
        val descending = layout.sortDescending
        return list.sortedWith { a, b ->
            val primary = if (byCount) {
                a.trackCount.compareTo(b.trackCount)
            } else {
                names.getValue(a.name).compareTo(names.getValue(b.name))
            }
            val ordered = if (descending) -primary else primary
            if (ordered != 0) ordered else names.getValue(a.name).compareTo(names.getValue(b.name))
        }
    }

    /** 曲风排序。规则与艺术家一致：数量 / 名字（默认数量）。 */
    fun genres(list: List<GenreEntry>, layout: TabLayout): List<GenreEntry> {
        if (list.size < 2) return list
        val names = HashMap<String, String>(list.size * 2)
        list.forEach { names[it.name] = it.name.lowercase() }
        val byCount = layout.genreSort() != GenreSort.NAME
        val descending = layout.sortDescending
        return list.sortedWith { a, b ->
            val primary = if (byCount) {
                a.trackCount.compareTo(b.trackCount)
            } else {
                names.getValue(a.name).compareTo(names.getValue(b.name))
            }
            val ordered = if (descending) -primary else primary
            if (ordered != 0) ordered else names.getValue(a.name).compareTo(names.getValue(b.name))
        }
    }
}
