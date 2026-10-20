package com.audiometa.app.ui.component

import com.audiometa.app.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表文字规则的单元测试。
 *
 * 用户要求："**在所有列表，无内嵌标签就显示文件名**"——
 * 扫描时如果文件里没有标题标签，入库的 title 会被兜成"去掉扩展名的文件名"，
 * 列表里就跟磁盘上的文件对不上。这里把那条规则钉住。
 */
class TrackTextTest {

    private fun track(path: String, title: String = "", artist: String = "", album: String = "") =
        TrackEntity(path = path, folderPath = path.substringBeforeLast('/'), title = title, artist = artist, album = album)

    @Test
    fun `没有内嵌标题时显示完整文件名`() {
        val t = track("/music/Album/01 Track.mp3", title = "01 Track")
        assertEquals("01 Track.mp3", t.listTitle)
        assertEquals("", t.listSubtitle)
    }

    @Test
    fun `标题为空也显示完整文件名`() {
        val t = track("/music/无名.flac", title = "")
        assertEquals("无名.flac", t.listTitle)
    }

    @Test
    fun `有内嵌标题时用标签里的标题`() {
        val t = track("/music/a.mp3", title = "真的标题", artist = "某人", album = "某专辑")
        assertEquals("真的标题", t.listTitle)
        assertEquals("某人 · 某专辑", t.listSubtitle)
    }

    @Test
    fun `只有艺术家或只有专辑时副标题不拼多余的分隔符`() {
        assertEquals("某人", track("/m/a.mp3", title = "T", artist = "某人").listSubtitle)
        assertEquals("某专辑", track("/m/a.mp3", title = "T", album = "某专辑").listSubtitle)
    }

    @Test
    fun `文件名没有扩展名时也能兜住`() {
        assertEquals("noext", track("/music/noext", title = "").listTitle)
    }
}
