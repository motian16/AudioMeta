package com.audiometa.app.domain.usecase

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺术家图按艺术家刮削时的候选排序。
 *
 * 回归用：QQ 源返回的艺术家条目里 `name` 就是歌手名，没有 `artist` 字段，
 * 图片在 `imageUrls` 数组里（大 → 小）。以前这里拿 `title`/`album` 去比、
 * 还只读单个 `imageUrl`，于是永远选不中正确的歌手，界面上也就一直没有艺术家图。
 */
class ArtistImageMatchTest {

    /** QQ 源 searchArtistImages 的真实返回形状（截取关键字段）。 */
    private val qqResponse = """
        [
          {
            "id": "4558",
            "name": "周杰伦",
            "imageUrl": "https://y.qq.com/music/photo_new/T001R800x800M0000025NhlN2yWrP4.jpg",
            "imageUrls": [
              "https://y.qq.com/music/photo_new/T001R800x800M0000025NhlN2yWrP4.jpg",
              "https://y.qq.com/music/photo_new/T001R500x500M0000025NhlN2yWrP4.jpg",
              "https://y.qq.com/music/photo_new/T001R300x300M0000025NhlN2yWrP4.jpg"
            ],
            "internal": { "qq_singer_mid": "0025NhlN2yWrP4" }
          },
          {
            "id": "51128649",
            "name": "周杰伦. ",
            "imageUrl": "https://y.qq.com/music/photo_new/T001R800x800M0000006LI4l1MTc5b.jpg",
            "imageUrls": [
              "https://y.qq.com/music/photo_new/T001R800x800M0000006LI4l1MTc5b.jpg"
            ]
          },
          {
            "id": "999",
            "name": "吉拉朵",
            "imageUrl": "https://y.qq.com/music/photo_new/T001R800x800M00000xxxxx.jpg",
            "imageUrls": [
              "https://y.qq.com/music/photo_new/T001R800x800M00000xxxxx.jpg"
            ]
          }
        ]
    """.trimIndent()

    /** 插件返回的 name/title 会先变成 Candidate 的 title（见 ScrapeService.toCandidates）。 */
    private fun candidatesFromPluginArray(): List<TrackMatcher.Candidate> {
        val array = JSONArray(qqResponse)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            TrackMatcher.Candidate(
                title = item.optString("name"),
                artist = item.optString("artist"),
                album = "",
                durationMs = 0L
            )
        }
    }

    @Test
    fun `精确同名歌手排在相似名字前面`() {
        val want = TrackMatcher.Want(
            title = "周杰伦",
            artist = "周杰伦",
            album = "",
            fileName = "七里香.flac",
            durationMs = 0L
        )
        val ranked = TrackMatcher.rank(candidatesFromPluginArray(), want)
        assertEquals("周杰伦", ranked.first().candidate.title)
        assertEquals(0, ranked.first().index)
        // 同名歌手必须落在"可靠"区间，不能被相似名字挤掉。
        assertTrue(ranked.first().score > 0.9)
        // 名字只差一个符号的候选取分几乎相同（都是同一张图），
        // 但完全不相关的候选必须排在后面。
        assertEquals("吉拉朵", ranked.last().candidate.title)
    }

    @Test
    fun `名字完全不相关的候选分数明显偏低`() {
        val want = TrackMatcher.Want(
            title = "周杰伦",
            artist = "周杰伦",
            album = "",
            fileName = "七里香.flac",
            durationMs = 0L
        )
        val ranked = TrackMatcher.rank(candidatesFromPluginArray(), want)
        val unrelated = ranked.first { it.candidate.title == "吉拉朵" }
        assertTrue(unrelated.score < 0.6)
    }

    @Test
    fun `英文歌手名也能匹配到中文返回`() {
        val want = TrackMatcher.Want(
            title = "Jay Chou",
            artist = "Jay Chou",
            album = "",
            fileName = "Qilixiang.flac",
            durationMs = 0L
        )
        val ranked = TrackMatcher.rank(candidatesFromPluginArray(), want)
        // 没有一个候选真的叫 Jay Chou，所以分数不应该虚高到"可靠"区间。
        assertTrue(ranked.first().score < 0.72)
    }
}
