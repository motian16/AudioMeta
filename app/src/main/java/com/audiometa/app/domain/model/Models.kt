package com.audiometa.app.domain.model

enum class PictureKind {
    ARTIST,
    FRONT_COVER
}

/** 内部写入模式。界面上不再直接暴露，由"内嵌 / 同时写外置文件"推导。 */
enum class WriteMode {
    EMBED,
    SIDECAR,
    BOTH
}

enum class OverwritePolicy {
    SKIP_EXISTING,
    FORCE,
    FILL_MISSING
}

/** 原图，或按自定义质量重编码。 */
enum class ImageMode {
    ORIGINAL,
    CUSTOM
}

/** 封面/头像的边角样式。 */
enum class CornerStyle(val label: String) {
    CIRCLE("圆形"),
    ROUNDED("圆角"),
    SQUARE("方形");

    companion object {
        fun fromName(name: String?): CornerStyle = entries.firstOrNull { it.name == name } ?: ROUNDED
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/**
 * 主题预设主色。DEFAULT 用内置配色，CUSTOM 用用户自定义颜色。
 */
enum class ThemePreset(val label: String, val seedHex: String?) {
    DEFAULT("默认", null),
    BLUE("蓝色", "FF3482FF"),
    GREEN("绿色", "FF36D167"),
    YELLOW("黄色", "FFFFB21D"),
    ORANGE("橙色", "FFFF5722"),
    PURPLE("紫色", "FF7C4DFF"),
    PINK("粉色", "FFE91E63"),
    TEAL("青色", "FF00BCD4"),
    CUSTOM("自定义", null);

    companion object {
        fun fromName(name: String?): ThemePreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 兜底歌词：额外导出一份外置 lrc，只可选其一。 */
enum class FallbackLyrics(val key: String, val label: String) {
    NONE("none", "不导出"),
    LINE("line", "逐行歌词"),
    WORD("word", "逐字歌词");

    companion object {
        fun fromKey(key: String?): FallbackLyrics =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

enum class DurationFilter {
    NONE,
    SKIP_UNDER_30,
    SKIP_UNDER_60,
    CUSTOM
}

/**
 * 刮削能力。
 *
 * 四项：**专辑图 / 艺术家图片 / 元信息 / 歌词**，全部走插件。
 *
 * 「专辑图」是 1.2 加回来的：插件侧一直有 `searchCovers` 入口
 * （Lyrico 的元信息源都实现了它，返回带 `picUrl` 的歌曲列表），
 * 宿主这边把它接成一项独立能力，选了哪张就把那张写进文件封面。
 *
 * 旧插件包若仍声明 `albumCover` 这类老键，[fromKey] 会映射过去（见 [LEGACY_KEYS]），
 * 插件照常安装、照常可用。
 */
enum class ScrapeCapability(val key: String, val label: String) {
    /** 专辑图（封面）：选一张写入文件，替换已有封面。 */
    COVER("albumCover", "专辑图"),
    ARTIST_IMAGE("artistImage", "艺术家图片"),
    TRACK_META("trackMeta", "元信息"),
    /**
     * 曲风（流派）：只写 `genre` 一个字段。
     *
     * 详情页刮削区按用户要求收窄成「组合 / 专辑图 / 艺术家图 / 曲风 / 歌词」，
     * 整块元信息（日期、语言、版权、注释…）不再从详情页手动刮 —— 只留曲风这一项。
     * 搜索走 `searchSongs`（和元信息同一批源），写入也只写这一个字段。
     */
    GENRE("genre", "曲风"),
    LYRICS("getLyrics", "歌词");

    companion object {
        val ALL_KEYS = entries.map { it.key }.toSet()

        /** 旧能力键到现有能力键的映射，用于兼容已发布的插件包。 */
        private val LEGACY_KEYS = mapOf(
            "artistInfo" to "trackMeta",
            "lyrics" to "getLyrics",
            // 封面在旧版本里叫 cover / searchCovers / albumCoverUrl 之类的键。
            "cover" to "albumCover",
            "searchCovers" to "albumCover",
            "albumCoverUrl" to "albumCover"
        )

        fun fromKey(key: String): ScrapeCapability? {
            entries.firstOrNull { it.key == key }?.let { return it }
            val mapped = LEGACY_KEYS[key] ?: return null
            return entries.firstOrNull { it.key == mapped }
        }

        /** 该键是否是宿主认识的能力（含旧键）。 */
        fun isKnownKey(key: String): Boolean = fromKey(key) != null

        fun fromKeys(keys: Collection<String>): Set<ScrapeCapability> =
            keys.mapNotNull { fromKey(it) }.toSet()
    }
}

data class TrackMeta(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val trackNo: Int = 0,
    /** 碟号。多碟专辑靠它区分，之前完全没读。 */
    val discNo: Int = 0,
    val year: Int = 0,
    /** 完整发行日期字符串（yyyy-MM-dd 之类），比只有年份精确。 */
    val date: String = "",
    val genre: String = "",
    /** 语言，例：chi / zho / eng。刮削元信息里常有，之前丢了。 */
    val language: String = "",
    val composer: String = "",
    val lyricist: String = "",
    val conductor: String = "",
    val comment: String = "",
    val copyright: String = "",
    val isrc: String = "",
    val bpm: String = "",
    val durationMs: Long = 0L,
    val hasArtistImage: Boolean = false,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val hasTranslation: Boolean = false,
    val lyricsType: String = "none",
    val lyrics: String = "",
    /** 翻译歌词正文，用于详情页的歌词弹窗。 */
    val translation: String = "",
    /**
     * 文件里的**全部**文本标签（键 → 值），容器原始写法。
     *
     * 基础字段只覆盖标题/专辑/歌词这一小部分，平台私有标记（酷狗的 kghash、
     * QQ 的 QMQuality、网易云的 NCM 残留…）只能靠遍历整个标签表拿到。
     * 由 [com.audiometa.app.media.tag.TagReader.read] 顺带带出来 ——
     * 它本来就把标签结构完整解析了一遍，不额外读一次文件。
     * 「元信息」页的扫描用的就是这一份。
     */
    val rawTags: Map<String, String> = emptyMap()
) {
    fun hasAnyField(): Boolean =
        title.isNotBlank() || artist.isNotBlank() || albumArtist.isNotBlank() || album.isNotBlank() ||
            trackNo > 0 || discNo > 0 || year > 0 || genre.isNotBlank() || language.isNotBlank() ||
            composer.isNotBlank() || lyricist.isNotBlank() || comment.isNotBlank() ||
            copyright.isNotBlank() || isrc.isNotBlank() || bpm.isNotBlank() || conductor.isNotBlank()

    /**
     * 有几个字段是有内容的。
     *
     * 元信息组合搜索用它说明"这个源给了多少信息"——
     * 每个字段挑内容更全的源时，先看的就是这个数。
     */
    fun fieldCount(): Int = listOf(
        title, artist, albumArtist, album, date, genre, language,
        composer, lyricist, conductor, comment, copyright, isrc, bpm
    ).count { it.isNotBlank() } +
        listOf(trackNo, discNo, year).count { it > 0 }

    /** 匹配只认这三项，其余字段不参与打分（按用户要求）。 */
    fun matchingFields(): Triple<String, String, String> = Triple(title, artist, album)
}

data class PicturePayload(
    val bytes: ByteArray,
    val mime: String,
    val kind: PictureKind,
    val description: String,
    val width: Int = 0,
    val height: Int = 0
)

data class LyricsItem(
    val type: String,
    val lang: String = "",
    val translated: Boolean = false,
    val content: String = ""
)

data class ScanProgress(
    val running: Boolean = false,
    val scannedFiles: Int = 0,
    val foundAudio: Int = 0,
    val currentPath: String = "",
    val message: String = "",
    val finished: Boolean = false
)

data class ScrapeProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val currentTitle: String = "",
    val message: String = "",
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /**
     * 这一轮**跑完的时刻**（毫秒），没跑过是 0。
     *
     * 详情页靠它决定"要不要重读文件"：单曲刮削可能只花几百毫秒，
     * 只看 [running] 的 true→false 会漏（StateFlow 只保证最新值，
     * 主线程忙一下就只看到 false）—— 用户看到的就是"写完了但界面还是旧值，
     * 得退出详情页再进来"。时间戳一定会变，漏不掉。
     */
    val finishedAt: Long = 0L
)

/** 兜底歌词的默认输出目录。 */
/** 留空表示用应用外部目录（Android/data/<包名>/files/兜底歌词）。 */
const val DEFAULT_FALLBACK_LYRICS_DIR = ""

/**
 * 源优先级的键。
 *
 * 封面和歌词刮削已移除，这里只剩「艺术家图片」和「元信息」。
 * 流派**没有**自己的键：它的取值顺序已经固定（Apple → QQ → 网易 → 酷狗，
 * 见 MetaMergePolicy.FIELD_SOURCE_ORDER），单独给一条可拖动的优先级
 * 会和那个固定顺序自相矛盾，所以连设置页那一项一起删掉了。
 */
object PriorityKey {
    const val ARTIST_IMAGE = "artistImage"
    const val TRACK_META = "trackMeta"
    /** 歌词的源优先级（用户要求：歌词也按源排序）。 */
    const val LYRICS = "lyrics"

    val ALL = listOf(ARTIST_IMAGE, TRACK_META, LYRICS)

    fun label(key: String): String = when (key) {
        ARTIST_IMAGE -> "艺术家图片"
        // 详情页的「组合」按标题-专辑-艺术家匹配，专辑图 / 艺术家图 / 曲风
        // 都按这一份顺序取源（默认 Apple Music → QQ，可手动拖动）。
        TRACK_META -> "组合刮削"
        LYRICS -> "歌词"
        else -> key
    }

    /** 这一项在设置里对应的说明。 */
    fun hint(key: String): String = when (key) {
        ARTIST_IMAGE -> "一位艺术家只搜一次，按这个顺序问各个源，谁先给出图就用谁的。"
        TRACK_META -> "组合按标题-专辑-艺术家三项匹配，源顺序默认 Apple Music → QQ，可手动调整。"
        LYRICS -> "按这个顺序问各个源找歌词；匹配度相同时排在前面的优先。"
        else -> ""
    }
}

/**
 * 默认源优先级。用插件 id 表示，未列出的源排在后面。
 *
 * 顺序按用户指定：Apple Music → QQ → 网易 → 酷我。
 * 歌词的顺序是用户单独指定的：QQ音乐 → 网易云 → Apple Music → 酷狗 → MusicBrainz。
 */
val DEFAULT_SOURCE_PRIORITY: Map<String, List<String>> = mapOf(
    PriorityKey.ARTIST_IMAGE to listOf(
        "com.applemusic.source",
        "com.qqmusic.source",
        "com.neteasecloudmusic.source",
        "com.kugou.source"
    ),
    PriorityKey.TRACK_META to listOf(
        "com.applemusic.source",
        "com.qqmusic.source",
        "com.neteasecloudmusic.source",
        "com.kugou.source"
    ),
    PriorityKey.LYRICS to listOf(
        "com.qqmusic.source",
        "com.neteasecloudmusic.source",
        "com.applemusic.source",
        "com.kugou.source",
        "com.musicbrainz.source"
    )
)

/**
 * 歌词的"格式"优先级：先要逐字，其次逐行。
 *
 * 用户要求"默认是逐字+翻译优先"，所以默认值写在代码里（不是设置里），
 * 设置页只是让用户改这个顺序。
 */
val LYRICS_TYPE_KEYS = listOf("word", "line")
val DEFAULT_LYRICS_PRIORITY = listOf("word", "line")

fun lyricsTypeLabel(key: String): String = when (key) {
    "word" -> "逐字"
    "line" -> "逐行"
    "plain" -> "纯文本"
    else -> key
}

object AudioFormats {
    val COMMON = listOf(
        "flac", "mp3", "wav", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wma", "aiff", "aif", "alac"
    )
    val RARE = listOf(
        "ape", "wv", "tta", "tak", "mpc", "ofr", "dsf", "dff", "amr", "awb", "spx", "caf",
        "ac3", "dts", "ra", "mka", "mp4", "mid", "midi", "xm", "mod", "s3m", "it"
    )
    val ALL: List<String> = COMMON + RARE
    val ALL_SET: Set<String> = ALL.toSet()

    /** 完全不支持写入标签的格式，只能靠外置文件。 */
    val NO_EMBED = setOf(
        "amr", "awb", "ac3", "dts", "mid", "midi", "xm", "mod", "s3m", "it",
        "ra", "tak", "ofr", "mpc", "tta", "caf", "mka"
    )

    val ID3_PICTURE = setOf("mp3", "wav", "aiff", "aif", "dsf", "dff")
    val FLAC_PICTURE = setOf("flac")
    val VORBIS_PICTURE = setOf("ogg", "oga", "opus", "spx")
    val MP4_PICTURE = setOf("m4a", "m4b", "mp4", "alac", "aac")
    val WMA_PICTURE = setOf("wma")
    val APE_PICTURE = setOf("ape", "wv")

    /** 能写"艺术家图"的格式。MP4 只有封面 atom，没有艺术家图的标准位置。 */
    val ARTIST_PICTURE_EMBED = ID3_PICTURE + FLAC_PICTURE + VORBIS_PICTURE + APE_PICTURE + WMA_PICTURE

    private val METADATA_EMBED = ID3_PICTURE + FLAC_PICTURE + VORBIS_PICTURE + MP4_PICTURE + WMA_PICTURE + APE_PICTURE
    private val LYRICS_EMBED = ID3_PICTURE + FLAC_PICTURE + VORBIS_PICTURE + MP4_PICTURE + APE_PICTURE

    fun normalize(ext: String): String = ext.lowercase().removePrefix(".")
}

/**
 * 某个格式到底能内嵌什么。界面要如实告诉用户，
 * 因为不少格式只支持外置的封面和歌词。
 */
data class FormatSupport(
    val cover: Boolean,
    val artistImage: Boolean,
    val lyrics: Boolean,
    val meta: Boolean
) {
    val anyEmbedded: Boolean get() = cover || artistImage || lyrics || meta

    val summary: String
        get() {
            if (!anyEmbedded) return "仅支持外置文件"
            val parts = buildList {
                if (cover) add("封面")
                if (artistImage) add("艺术家图")
                if (lyrics) add("歌词")
                if (meta) add("元信息")
            }
            return "可内嵌：" + parts.joinToString("、")
        }
}

fun formatSupportOf(ext: String): FormatSupport {
    val n = AudioFormats.normalize(ext)
    if (n in AudioFormats.NO_EMBED) return FormatSupport(false, false, false, false)
    return FormatSupport(
        cover = n in AudioFormats.ID3_PICTURE || n in AudioFormats.FLAC_PICTURE ||
            n in AudioFormats.VORBIS_PICTURE || n in AudioFormats.MP4_PICTURE ||
            n in AudioFormats.WMA_PICTURE || n in AudioFormats.APE_PICTURE,
        artistImage = n in AudioFormats.ARTIST_PICTURE_EMBED,
        lyrics = n in AudioFormats.ID3_PICTURE || n in AudioFormats.FLAC_PICTURE ||
            n in AudioFormats.VORBIS_PICTURE || n in AudioFormats.MP4_PICTURE ||
            n in AudioFormats.APE_PICTURE,
        meta = true
    )
}
