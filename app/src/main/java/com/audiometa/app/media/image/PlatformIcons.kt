package com.audiometa.app.media.image

import android.content.Context
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePaths
import java.io.File

/**
 * 平台图标。
 *
 * 「元信息」页每个平台分组左边原来是一个统一的方形占位图（一个标签图标），
 * 现在换成平台自己的图标。两个来源，**按顺序**找：
 *
 * 1. **订阅带来的那一批**（[StoragePaths.platformIconsDir]，私有目录）：
 *    「更新订阅」时从订阅包 `icons/` 目录里解出来的那批 png。它是最全也最新的一份 ——
 *    仓库里能对到分组的十张图（qq / netease / kugou / apple / apple-notes /
 *    musicbrainz / mora / bilibili / touhou / ffmpeg）都在这里；
 * 2. **已安装的插件包**（`plugins/<插件id>/icon.png`，或插件自带的
 *    `plugins/<插件id>/icon/<名字>.png`）—— 只装了某个平台插件、还没更新过订阅时的兜底。
 *
 * 匹配方式（用户指定）：按图标**文件名**判断是哪个平台，再拿它去对
 * 元信息页那一项的中文名。例如 `qq.png` ↔ 「QQ 音乐」，`bilibili.png` ↔ 「哔哩哔哩」。
 *
 * 认平台有**三条**路，按顺序试，任何一条认出来就算：
 *
 * 1. **分组名**（[keyFor]）：`QQ 音乐` → `qq`。括号里的补充说明、斜杠后面的别名
 *    都不算名字（`Spotify（第三方工具写入）` → spotify，`YouTube / YouTube Music` → youtube）；
 * 2. **分组里的标签键**（[keyFromTagKeys]）：某个分组名认不出来、但组里的键全是
 *    `KUGOU_*` 这种带平台前缀的写法时，照样能落到 `kugou` 图标；
 * 3. **插件包**（[fromPluginPackage]）：按插件 id（`com.qqmusic.source` → qq）
 *    或插件自带的那张 `icon.png`。
 *
 * 都认不出来 / 两边都没有图时，界面用通用占位图，不会留空白；同时**只记一条**
 * debug 日志（[logMissOnce]），让用户能从日志页分清"图根本没下到"还是"名字对不上"。
 *
 * 历史：APK 里曾内置过一批平台贴图（assets/platform_icons/）当兜底，
 * 后按用户要求全部移除 —— 这些第三方图标不属于 App 自身内容，
 * 现在改成**跟着订阅走**（App 里一张都不带）。
 */
object PlatformIcons {

    /**
     * 别名 → 图标文件名（不含扩展名）。
     *
     * 键统一转成小写、去掉空格和 `.`、`-`、`_` 之后再查，
     * 所以 "QQ 音乐" / "QQ音乐" / "qqmusic" / "qq" 都会落到 `qq`。
     */
    private val ALIASES: Map<String, String> = buildMap {
        // 注意：这里的辅助函数**不能叫 put**。
        // 叫 put 的话里面那句 put(...) 会解析到它自己（局部声明优先于
        // MutableMap.put），变成无限递归，第一次访问本对象就 StackOverflow，
        // 表现就是"一打开元信息页就闪退"。
        fun alias(iconKey: String, vararg names: String) {
            names.forEach { name ->
                val key = normalize(name)
                if (key.isNotEmpty()) this[key] = iconKey
            }
        }

        // ---- 仓库 icon/ 里**已经有图**的 10 个分组 ----
        alias("qq", "qq", "qqmusic", "qq 音乐", "qq音乐", "com.qqmusic.source")
        alias("netease", "netease", "neteasecloudmusic", "网易云音乐", "网易云",
            "com.neteasecloudmusic.source")
        alias("kugou", "kugou", "kugoumusic", "酷狗音乐", "酷狗", "com.kugou.source")
        alias("apple", "apple", "applemusic", "itunes", "apple music / itunes", "apple music",
            "com.applemusic.source")
        alias("apple-notes", "applenotes", "苹果语音备忘录", "语音备忘录")
        alias("musicbrainz", "musicbrainz", "com.musicbrainz.source", "acoustid")
        alias("mora", "mora", "mora.jp")
        alias("bilibili", "bilibili", "哔哩哔哩", "b站")
        /*
         * 非平台的两组也各有一张图标（用户放进仓库的 plugins-src/icon/）：
         * 同人音乐 → touhou.png，编码函数库 → ffmpeg.png。
         * 这里按**分组名和组里用到的键名**都挂上，规则里改名字也还能认到。
         */
        alias("touhou", "同人音乐", "同人", "doujin", "touhou", "东方", "例大祭")
        alias(
            "ffmpeg",
            "编码函数库", "编码器", "编码工具", "encodedlibrary", "encoder", "encodedby",
            "encodersettings", "ffmpeg", "lavf", "lame"
        )

        // 从上游插件里拆出来的「艺术家图片」插件（只提供 searchArtistImages）。
        // 它们各自带一张和平台插件一样的 icon.png，所以按插件 id 也要能认到平台，
        // 否则用户只装了艺术家图片插件时，元信息页那个分组就退回占位图了。
        alias("qq", "com.metaaudio.artistimage.qq")
        alias("netease", "com.metaaudio.artistimage.netease")
        alias("kugou", "com.metaaudio.artistimage.kugou")
        alias("apple", "com.metaaudio.artistimage.apple")

        /*
         * ---- 仓库里**暂时还没有图**的平台 / 工具 ----
         *
         * 这些分组今天对不到任何 png（界面照旧用占位图），但键按
         * "仓库以后放一张同名 png 就能直接用"的命名先挂好：仓库 `icon/` 里加上
         * `kuwo.png`、`spotify.png`… 那几栏立刻就有图，App 这边一行都不用改。
         *
         * 顺序无所谓（键名互不重叠，重叠会取后写的那个），摆在一起是为了下次
         * 仓库补图时一眼能看出该叫什么名字。
         */
        alias("kuwo", "酷我音乐", "酷我", "kuwo", "kuwomusic", "com.kuwo.source")
        alias("spotify", "spotify（第三方工具写入）", "spotify", "com.spotify.source")
        alias("amazon", "amazon", "amazonmusic", "amazon music", "com.amazon.source")
        alias("bandcamp", "bandcamp", "com.bandcamp.source")
        alias("beatport", "beatport", "com.beatport.source")
        alias("discogs", "discogs", "com.discogs.source")
        alias("qobuz", "qobuz", "com.qobuz.source")
        alias("soundcloud", "soundcloud", "com.soundcloud.source")
        alias("tidal", "tidal", "com.tidal.source")
        alias("youtube", "youtube / youtube music", "youtube", "youtube music",
            "youtubemusic", "com.youtube.source")
        alias("ytdlp", "yt-dlp / youtube-dl", "yt-dlp", "youtube-dl", "ytdlp", "youtubedl")
        alias("musicip", "musicip / musicmagic", "musicip", "musicmagic", "com.musicip.source")
        alias("voicememo", "voicememo", "voicememouuid", "voice memo uuid")
        alias("eac", "exactaudiocopy", "eac")
        alias("beets", "beets")
        alias("replaygain", "回放增益", "replaygain", "replay gain")
        alias("siteurl", "站点url", "siteurl", "site url")
        alias("daw", "专业音频工作站 / 影视素材", "专业音频工作站", "影视素材", "daw")
        alias("album-tracks", "专辑曲目")
        alias("unknown-image", "未知图片")
    }

    /** 括号里的补充说明：`Spotify（第三方工具写入）` 里的那一截不算名字。 */
    private val BRACKETED = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")

    /** 标签键拆段：`KUGOU_QUALITY`、`----:com.apple.iTunes:X` 都按非字母数字切开。 */
    private val KEY_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(" ", "").replace(".", "").replace("-", "").replace("_", "")

    /**
     * 平台名 / 插件 id → 图标文件名（不含扩展名）；认不出来返回 null。
     *
     * 先按名字整体查；查不到再去掉括号里的补充说明、只取斜杠前面那一段再查 ——
     * 规则里把分组写成 `Spotify（第三方工具写入）`、`YouTube / YouTube Music`
     * 这种"名字比图标名长"的样子时，照样能对到图。
     */
    fun keyFor(label: String): String? {
        val name = normalize(label)
        if (name.isEmpty()) return null
        ALIASES[name]?.let { return it }

        val stripped = normalize(label.replace(BRACKETED, ""))
        if (stripped.isNotEmpty() && stripped != name) ALIASES[stripped]?.let { return it }

        val head = name.substringBefore('/').substringBefore('（').substringBefore('(')
        if (head.isNotEmpty() && head != name && head != stripped) ALIASES[head]?.let { return it }
        return null
    }

    /**
     * 单个标签键 → 平台图标名。
     *
     * 三种写法都认：
     * - 键名本身就是平台名（`neteasecloudmusic` → netease）；
     * - 带平台前缀（`KUGOU_QUALITY` → kugou、`MUSICBRAINZ_ALBUMID` → musicbrainz）；
     * - 平台名藏在中间（`----:com.apple.iTunes:CONDUCTOR` → apple）。
     */
    private fun keyFromTagKey(tagKey: String): String? {
        val raw = tagKey.trim()
        if (raw.isEmpty()) return null
        keyFor(raw)?.let { return it }

        val tokens = raw.split(KEY_SEPARATORS).filter { it.isNotBlank() }
        tokens.forEach { token -> keyFor(token)?.let { return it } }
        // 相邻两段拼起来再看一次：`voice-memo-uuid` → voicememo。
        for (i in 0 until tokens.size - 1) {
            keyFor(tokens[i] + tokens[i + 1])?.let { return it }
        }
        return null
    }

    /**
     * 从"分组里的标签键"再猜一次平台（用户要求的兜底）。
     *
     * 一个分组名认不出来时（规则改了名、或干脆没有规则），只要组里那几个键
     * 都能推出**同一个**平台，就用它。要求整组一致是故意的：「未知标签」那张卡
     * 里常常混着几十种键，偶尔冒出一个 `MUSICBRAINZ_ALBUMID` 不该把整张卡
     * 换成 MusicBrainz 的图标。
     */
    private fun keyFromTagKeys(keys: List<String>): String? {
        if (keys.isEmpty()) return null
        val guesses = keys.map { keyFromTagKey(it) }
        if (guesses.any { it == null }) return null
        val first = guesses.first()
        return if (guesses.all { it == first }) first else null
    }

    /**
     * 取某个平台分组的图标字节。
     *
     * 先看**订阅带来的那一批**（`platform_icons/`），没有再翻已安装的插件包。
     * 认不出这个平台、两边都没有图标、或读取过程出任何问题，都返回 null，
     * 界面继续用原来的占位图 —— 取个图标绝不该把页面搞崩。
     */
    fun bytes(context: Context, label: String): ByteArray? = bytes(context, label, emptyList())

    /**
     * 取某个平台分组的图标字节（带"这一组里的标签键"）。
     *
     * [keys] 是分组下的标签键（例如 `KUGOU_QUALITY`），只在**分组名认不出来**、
     * 或认出来的平台一张图都没有时用来再猜一次平台。传空列表就是只用分组名。
     */
    fun bytes(context: Context, label: String, keys: List<String>): ByteArray? = runCatching {
        val candidates = candidateKeys(label, keys)
        for (key in candidates) {
            fromSubscription(context, key)?.let { return@runCatching it }
            fromPluginPackage(context, key)?.let { return@runCatching it }
        }
        /*
         * 到这儿就是"这张卡没有图标"：认不出平台，或者认出来了但本地一张图都没有
         * （订阅还没下到 / 用户清了数据）。两种情况的处理办法完全不同，所以留一条
         * 日志把事实写清楚 —— 每个分组名在本次运行里只记一次，滚动和重组不会刷屏。
         */
        logMissOnce(context, label, candidates)
        null
    }.getOrNull()

    /** 这次要试哪些图标名（按优先级去重）：分组名 → 组里的标签键。 */
    private fun candidateKeys(label: String, keys: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        keyFor(label)?.let { out += it }
        keyFromTagKeys(keys)?.let { out += it }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // 未命中的诊断
    // ------------------------------------------------------------------

    /**
     * 已经记过日志的分组名。
     *
     * 界面是**边滚动边重组**的：同一个分组名一次运行里会被问很多遍，不记下来
     * 就会把日志页刷满（用户上次就是被这种刷屏日志坑过）。所以按名字只记一次。
     */
    private val loggedMisses: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun logMissOnce(context: Context, label: String, candidates: List<String>) {
        if (!loggedMisses.add(label)) return
        runCatching {
            val dir = StoragePaths.platformIconsDir(context)
            val files = pngEntries(dir)
            /*
             * 一句日志要把两种完全不同的情况分开：
             * - 「认出来的图标名=（认不出）」= **名字对不上**（别名表该补）；
             * - 「认出来的图标名=qq，有 0 张 png」= **图没下到**（该去点更新订阅）。
             * 顺带把本地那几张的文件名带上（最多 8 个），用户一眼就能比出是哪种。
             */
            val names = files.take(8).joinToString("、") { it.name } +
                if (files.size > 8) "…" else ""
            AppLog.d(
                TAG,
                "平台图标没命中：分组「" + label + "」→ 认出来的图标名=" +
                    candidates.joinToString("/").ifEmpty { "（认不出）" } +
                    "；" + StoragePaths.DIR_PLATFORM_ICONS + "/ 里有 " + files.size + " 张 png" +
                    (if (names.isEmpty()) "（目录不存在或没有 png）" else "：" + names) +
                    "（" + dir.absolutePath + "）"
            )
        }
    }

    // ------------------------------------------------------------------
    // 订阅带来的那一批
    // ------------------------------------------------------------------

    /**
     * 图标目录里的 png（**含一级子目录**）。
     *
     * 为什么要翻子目录：早期版本/手工拷贝进来的图标可能是带着目录前缀落盘的
     * （`platform_icons/icons/qq.png`）。只列当前一层的话，这种目录会被当成
     * "一张图都没有"，界面全是占位图而且没有任何报错。
     */
    private fun pngEntries(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles().orEmpty()
        val direct = children.filter { it.isFile && it.extension.equals("png", true) }
        val nested = children.filter { it.isDirectory }.flatMap { sub ->
            sub.listFiles().orEmpty().filter { it.isFile && it.extension.equals("png", true) }
        }
        return direct + nested
    }

    /**
     * 文件名（不含目录前缀和扩展名）归一化后的样子：`icons/qq.png` 也当成 `qq`。
     */
    private fun iconNameOf(file: File): String =
        normalize(file.name.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.'))

    /**
     * 从订阅装进来的图标目录里找。
     *
     * 文件名就是图标名（`bilibili.png` → bilibili）；也走一遍别名表，
     * 所以 `apple-notes.png` 这种带连字符的一样能对到「语音备忘录」。
     */
    private fun fromSubscription(context: Context, key: String): ByteArray? {
        val files = pngEntries(StoragePaths.platformIconsDir(context))
        if (files.isEmpty()) return null
        val target = normalize(key)
        val hit = files.firstOrNull { iconNameOf(it) == target }
            ?: files.firstOrNull { keyFor(it.nameWithoutExtension) == key }
            ?: return null
        return runCatching { hit.readBytes() }.getOrNull()
    }

    /**
     * 把订阅里的图标落到 [StoragePaths.platformIconsDir]。
     *
     * @param files 文件名（`bilibili.png`）→ 图片字节；名字来自订阅（外部内容），
     *   所以只取末段、只收 `.png`，不让它带路径出去。
     * @return 真正写进去几张；一张都没写成返回 0。
     */
    fun install(context: Context, files: Map<String, ByteArray>): Int {
        if (files.isEmpty()) return 0
        val dir = StoragePaths.platformIconsDir(context)
        var written = 0
        files.forEach { (rawName, bytes) ->
            /*
             * 订阅里给的名字可能**带着目录前缀**（index.json 写的是
             * `icon/qq.png`，包里是 `icons/qq.png`）。只取末段再落盘：
             * 写成 `platform_icons/qq.png`，读取那边按文件名才对得上；
             * 顺带把反斜杠也当分隔符，免得出一个叫 `icons\qq.png` 的怪文件
             * （Linux 上反斜杠是合法文件名字符，不处理的话它永远匹配不到）。
             */
            val name = rawName.replace('\\', '/').substringAfterLast('/').trim()
            if (name.isEmpty() || name.contains("..")) return@forEach
            if (!name.endsWith(".png", true) || bytes.isEmpty()) return@forEach
            runCatching {
                if (!dir.isDirectory) dir.mkdirs()
                File(dir, name).writeBytes(bytes)
                written++
            }
        }
        if (written > 0) AppLog.i(TAG, "平台图标已更新 " + written + " 张：" + dir.absolutePath)
        return written
    }

    /**
     * 本地一张平台图标都没有。
     *
     * 两种情况：刚从老版本升上来（图标是新加的），或者用户清了数据。
     * 「更新订阅」据此知道**该为图标下一次订阅包** —— 否则"插件和规则都是最新的"
     * 会让整次更新直接跳过，图标永远补不上。
     */
    fun hasIconsOnDisk(context: Context): Boolean = runCatching {
        // 用和读取同一套"翻一层子目录"的找法：不然图标在子目录里时，
        // 这里会说"没有图"，每次都白下一次整包。
        pngEntries(StoragePaths.platformIconsDir(context)).isNotEmpty()
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 已安装的插件包（兜底）
    // ------------------------------------------------------------------

    /**
     * 从插件包里找。
     *
     * 插件目录结构是 `plugins/<插件id>/icon.png`（安装时按 manifest 的 icon 字段落盘）。
     * 有的插件包还带了 `plugins/<插件id>/icon/<名字>.png` 这种目录
     * （原版 Lyrico 插件包就是这个结构），两种都认。
     */
    private fun fromPluginPackage(context: Context, key: String): ByteArray? {
        val root = StoragePaths.pluginsRoot(context)
        if (!root.isDirectory) return null
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()

        // 1) 插件 id 对得上：com.qqmusic.source → qq
        dirs.firstNotNullOfOrNull { dir -> if (keyFor(dir.name) == key) readIconIn(dir) else null }
            ?.let { return it }
        // 2) 目录名直接就是图标名：plugins/qq/
        dirs.firstNotNullOfOrNull { dir ->
            if (normalize(dir.name) == normalize(key)) readIconIn(dir) else null
        }?.let { return it }
        // 3) 插件里带了 icon/ 子目录：plugins/<任意>/icon/qq.png
        //    文件名也过一遍别名表，`apple-notes.png` 这种带连字符的照样能对上。
        dirs.forEach { dir ->
            val iconDir = File(dir, "icon")
            if (!iconDir.isDirectory) return@forEach
            iconDir.listFiles()?.firstOrNull { file ->
                file.isFile && file.extension.equals("png", true) &&
                    (iconNameOf(file) == normalize(key) || keyFor(file.nameWithoutExtension) == key)
            }?.let { file ->
                runCatching { file.readBytes() }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * 某个已安装插件目录里的那张图标（插件清单里每一行左边用的就是它）。
     *
     * [iconName] 是 manifest 的 `icon` 字段（默认 `icon.png`）：
     * 先按它找，找不到再退回目录里第一张 png —— 和 [fromPluginPackage] 走同一份逻辑，
     * 不再各写一个读法。出任何问题都返回 null，界面用通用图标兜底。
     */
    fun pluginIconBytes(dir: File, iconName: String = "icon.png"): ByteArray? =
        runCatching { readIconIn(dir, iconName) }.getOrNull()

    /** 插件目录里那张主图标。manifest 里叫 icon.png，个别插件用别的名字。 */
    private fun readIconIn(dir: File, iconName: String = ""): ByteArray? {
        if (iconName.isNotBlank()) {
            val named = File(dir, iconName)
            if (named.isFile) return runCatching { named.readBytes() }.getOrNull()
        }
        val preferred = File(dir, "icon.png")
        if (preferred.isFile) return runCatching { preferred.readBytes() }.getOrNull()
        return dir.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("png", true) }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    private const val TAG = "PlatformIcons"
}
