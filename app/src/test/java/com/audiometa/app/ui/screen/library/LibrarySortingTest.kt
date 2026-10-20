package com.audiometa.app.ui.screen.library

import com.audiometa.app.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 曲库排序的单测。
 *
 * 这一层刚被重写过（排序键从"每次比较现算"改成"先算一张表"），
 * 而排序结果是用户天天看的东西 —— 改错了不会有编译错误，只会"顺序不对"。
 * 这里把三条容易写坏的规矩钉住：
 *
 * 1. 六个排序字段各自按什么排；
 * 2. **降序只翻主键**，同值时仍然按标题升序；
 * 3. 空列表/单元素直接返回（不用建表、不用排）。
 */
class LibrarySortingTest {

    private fun track(
        id: Long,
        name: String,
        title: String = "",
        artist: String = "",
        year: Int = 0,
        duration: Long = 0L,
        size: Long = 0L,
        modified: Long = 0L
    ) = TrackEntity(
        id = id,
        path = "/music/$name",
        folderPath = "/music",
        title = title,
        artist = artist,
        year = year,
        durationMs = duration,
        fileSize = size,
        lastModified = modified
    )

    private fun layout(field: SortField, descending: Boolean) =
        TabLayout(sortKey = field.name, sortDescending = descending)

    @Test
    fun `文件名排序按文件名而不是整条路径`() {
        val list = listOf(
            track(1, "b/02 - Newest.mp3", title = "Newest"),
            track(2, "a/10 - First.mp3", title = "First"),
            track(3, "c/01 - Middle.mp3", title = "Middle")
        )
        val sorted = LibrarySorting.tracks(list, layout(SortField.FILE_NAME, descending = false))
        // 按**文件名**排：01 - Middle / 02 - Newest / 10 - First。
        // （如果错按整条路径排，顺序会是 a/10 - First、b/02 - Newest、c/01 - Middle。）
        assertEquals(
            listOf("Middle", "Newest", "First"),
            sorted.map { it.title }
        )
    }

    @Test
    fun `降序只翻主键 同值时标题仍然升序`() {
        // 三首年份相同、标题不同的歌：按年份降序时，年份一样 → 标题仍然是升序。
        val list = listOf(
            track(1, "c.mp3", title = "Charlie", year = 2000),
            track(2, "a.mp3", title = "Alpha", year = 2000),
            track(3, "b.mp3", title = "Bravo", year = 1990)
        )
        val sorted = LibrarySorting.tracks(list, layout(SortField.YEAR, descending = true))
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), sorted.map { it.title })
    }

    @Test
    fun `六个排序字段都按各自的值排`() {
        val list = listOf(
            track(1, "x.mp3", title = "B", artist = "Zed", year = 1990, duration = 300, size = 30, modified = 3),
            track(2, "y.mp3", title = "A", artist = "Alice", year = 2010, duration = 100, size = 10, modified = 1),
            track(3, "z.mp3", title = "C", artist = "Mike", year = 2000, duration = 200, size = 20, modified = 2)
        )
        fun titles(field: SortField) = LibrarySorting.tracks(list, layout(field, false)).map { it.title }

        // 修改时间升序：A(1) → C(2) → B(3)。
        assertEquals(listOf("A", "C", "B"), titles(SortField.MODIFIED))
        // 文件名升序：x.mp3(B) → y.mp3(A) → z.mp3(C)。
        assertEquals(listOf("B", "A", "C"), titles(SortField.FILE_NAME))
        assertEquals(listOf("A", "C", "B"), titles(SortField.ARTIST))
        assertEquals(listOf("B", "C", "A"), titles(SortField.YEAR))
        assertEquals(listOf("A", "C", "B"), titles(SortField.DURATION))
        assertEquals(listOf("A", "C", "B"), titles(SortField.SIZE))
    }

    @Test
    fun `艺术家与曲风默认按数量从多到少 同数量按名字`() {
        val artists = listOf(
            ArtistEntry(name = "Zed", trackCount = 2, imagePath = null),
            ArtistEntry(name = "Alice", trackCount = 5, imagePath = null),
            ArtistEntry(name = "Bob", trackCount = 2, imagePath = null)
        )
        val byCount = LibrarySorting.artists(
            artists,
            TabLayout(sortKey = ArtistSort.COUNT.name, sortDescending = true)
        )
        assertEquals(listOf("Alice", "Bob", "Zed"), byCount.map { it.name })

        val byName = LibrarySorting.artists(
            artists,
            TabLayout(sortKey = ArtistSort.NAME.name, sortDescending = false)
        )
        assertEquals(listOf("Alice", "Bob", "Zed"), byName.map { it.name })

        val genres = listOf(
            GenreEntry(name = "Rock", trackCount = 3),
            GenreEntry(name = "Pop", trackCount = 9),
            GenreEntry(name = "Jazz", trackCount = 3)
        )
        val genresByCount = LibrarySorting.genres(
            genres,
            TabLayout(sortKey = GenreSort.COUNT.name, sortDescending = true)
        )
        assertEquals(listOf("Pop", "Jazz", "Rock"), genresByCount.map { it.name })
    }

    @Test
    fun `空列表和单元素原样返回`() {
        assertEquals(emptyList<TrackEntity>(), LibrarySorting.tracks(emptyList(), layout(SortField.YEAR, true)))
        val one = listOf(track(1, "a.mp3", title = "A"))
        assertEquals(one, LibrarySorting.tracks(one, layout(SortField.SIZE, true)))
    }
}
