package com.audiometa.app.domain.usecase

import com.audiometa.app.domain.match.LocalSong
import com.audiometa.app.domain.match.MusicMatch
import com.audiometa.app.domain.match.RemoteSong

/**
 * 刮削用的搜索词构建与候选排序。
 *
 * 底层是移植自 Lyrico 示例客户端的 MusicMatch（版本噪声清洗、片段拆分、
 * 覆盖率惩罚、时长校验、名次加分）。这里只做本项目类型的适配。
 *
 * 规则：
 * 1. 有标签 →「标题 + 歌手」
 * 2. 没标签 → 文件名。形如 xx - xx（- 两侧带不带空格都算）会拆成「xx xx」，
 *    开头的音轨号（01. / 03_ ）也会被去掉。
 * 3. 评分综合歌曲、歌手与时长；单首刮削用 rank() 列出候选，
 *    批量刮削用 bestIndex() 直接取最高分。
 */
object TrackMatcher {

    fun fileNameStem(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 文件名 → 搜索词。 */
    fun queryFromFileName(fileName: String): String {
        val segments = MusicMatch.parseFileNameSegments(fileName)
        return if (segments.isEmpty()) fileNameStem(fileName) else segments.joinToString(" ")
    }

    /** 标签 + 文件名 → 搜索词。 */
    fun buildQuery(title: String, artist: String, fileName: String): String {
        val queries = MusicMatch.buildSearchQueries(
            title.takeIf { it.isNotBlank() },
            artist.takeIf { it.isNotBlank() }
        )
        return queries.firstOrNull() ?: queryFromFileName(fileName)
    }

    /** 搜索结果里的一条候选。 */
    data class Candidate(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationMs: Long = 0L
    )

    /** 本地期望值。 */
    data class Want(
        val title: String,
        val artist: String,
        val album: String = "",
        val fileName: String,
        val durationMs: Long = 0L
    )

    private fun localOf(want: Want) = LocalSong(
        fileName = want.fileName,
        title = want.title.takeIf { it.isNotBlank() },
        artist = want.artist.takeIf { it.isNotBlank() },
        durationMilliseconds = want.durationMs
    )

    /** 0..1 的匹配分数。rankIndex 是候选在原始结果里的位次，用于名次加分。 */
    fun score(candidate: Candidate, want: Want, rankIndex: Int = -1): Double = MusicMatch.calculateMatchScore(
        result = RemoteSong(
            title = candidate.title.takeIf { it.isNotBlank() },
            artist = candidate.artist.takeIf { it.isNotBlank() },
            duration = candidate.durationMs
        ),
        song = localOf(want),
        rankIndex = rankIndex
    )

    /** 带分数的候选，按分数从高到低。单首刮削的结果列表用这个。 */
    data class Ranked(val index: Int, val candidate: Candidate, val score: Double)

    fun rank(candidates: List<Candidate>, want: Want): List<Ranked> =
        candidates.mapIndexed { index, candidate -> Ranked(index, candidate, score(candidate, want, index)) }
            .sortedByDescending { it.score }

    /** 分数最高的下标；空列表返回 -1。批量刮削用这个。 */
    fun bestIndex(candidates: List<Candidate>, want: Want): Int {
        if (candidates.isEmpty()) return -1
        var best = 0
        var bestScore = -1.0
        candidates.forEachIndexed { index, candidate ->
            val value = score(candidate, want, index)
            if (value > bestScore) {
                bestScore = value
                best = index
            }
        }
        return best
    }

    /**
     * 艺术家图的候选打分。
     *
     * 为什么不能直接用 [score]：
     * 艺术家图的候选里 `name`/`title` 就是歌手名，专辑字段往往为空，
     * 所以基础分几乎只反映"名字像不像"。遇到**重名艺术家**就分不出来——
     * 两个同名的歌手，分数一样，谁在前面谁赢。
     *
     * 所以这里在歌手名匹配之上再叠两个判据：
     * - **专辑吻合**（权重最高）：同名歌手通常不在同一张专辑里；
     * - **曲目吻合**：候选结果里带的歌名，和本地这首歌对不对得上。
     *
     * 这两个判据只在本地有对应信息时才生效：标签里没有专辑/歌名就不加分，
     * 免得凭空把某个候选抬上去。
     *
     * @param localAlbum 本地这首歌的专辑名，可为空
     * @param localTitle 本地这首歌的歌名，可为空
     * @param candidateTracks 候选结果里带的曲目名（各源字段不一，没有就传空）
     */
    fun artistScore(
        candidate: Candidate,
        want: Want,
        rankIndex: Int = -1,
        localAlbum: String = "",
        localTitle: String = "",
        candidateTracks: List<String> = emptyList()
    ): Double {
        val base = score(candidate, want, rankIndex)
        var bonus = 0.0

        val candidateAlbum = candidate.album.trim()
        if (localAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            if (sameText(candidateAlbum, localAlbum)) bonus += ALBUM_BONUS
        }

        if (localTitle.isNotBlank() && candidateTracks.isNotEmpty()) {
            val hit = candidateTracks.any { sameText(it, localTitle) }
            if (hit) bonus += TRACK_BONUS
        }

        return base + bonus
    }

    /** 归一化后比较：忽略大小写、空白、全半角括号差异。 */
    private fun sameText(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("[\\s\\u3000]+"), "")
        .replace(Regex("[（(].*?[）)]"), "")
        .trim()

    /** 专辑全中比歌名全中更说明问题：同名歌手基本不会撞在同一张专辑上。 */
    private const val ALBUM_BONUS = 0.25
    private const val TRACK_BONUS = 0.12
}
