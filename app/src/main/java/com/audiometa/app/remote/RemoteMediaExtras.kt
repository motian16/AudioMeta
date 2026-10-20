package com.audiometa.app.remote

/**
 * PC 插件下发的完整歌词时间轴（`trackExtra` 消息）。
 *
 * 只在手机本机曲库没有歌词时使用 —— 本机有就用本机的，避免网络传一份
 * 和本地文件不一致的歌词。封面同理（见 RemoteControlClient.pcCover）。
 */
data class RemoteLyrics(
    /** 与插件 `state.key` 一致：`id|path|title|artist`。 */
    val key: String,
    val lines: List<RemoteLyricLine>
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 取某个位置对应的三行 + 翻译。
     *
     * 时间轴可能不严格有序（宿主偶尔会补一行），所以不假设顺序，扫一遍找
     * "开始时间 <= position 的最后一个"。
     */
    fun trioAt(positionMs: Long): RemoteTrackResolver.LyricTrio {
        if (lines.isEmpty()) return RemoteTrackResolver.LyricTrio()
        var index = -1
        var bestStart = Long.MIN_VALUE
        for (i in lines.indices) {
            val start = lines[i].startMs
            if (start <= positionMs && start >= bestStart) {
                bestStart = start
                index = i
            }
        }
        if (index < 0) index = 0
        val current = lines[index]
        return RemoteTrackResolver.LyricTrio(
            previous = lines.getOrNull(index - 1)?.main.orEmpty(),
            current = current.main,
            next = lines.getOrNull(index + 1)?.main.orEmpty(),
            translation = current.translation
        )
    }
}

data class RemoteLyricLine(
    val startMs: Long,
    val endMs: Long,
    val main: String,
    val translation: String
)
