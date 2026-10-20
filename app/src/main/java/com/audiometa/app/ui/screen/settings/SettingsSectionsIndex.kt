package com.audiometa.app.ui.screen.settings

import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.PluginRecordEntity
import com.audiometa.app.data.prefs.AppSettings

/**
 * 设置分类。parent 不为空的是二级页，不出现在根列表里，
 * 但仍然可以被搜索命中。
 */
enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val parent: SettingsSection? = null
) {
    APPEARANCE("外观", "动态取色、主题与小组件"),
    /**
     * 音乐库：**这一层就是全部**。
     *
     * 用户要求："直接显示添加的文件夹列表，和重新扫描，不再额外嵌套层级"。
     * 所以目录列表、子目录排除、扫描按钮全部收在这一页里，
     * DIRECTORIES / EXCLUDE 只作为旧入口保留（点了也落到同一页）。
     */
    LIBRARY("音乐库", "目录、排除与扫描"),
    FORMATS("音频格式", "各格式支持的内嵌能力", LIBRARY),
    /** 旧入口：合并进「音乐库」，不再单独成页。 */
    DIRECTORIES("音乐目录", "添加目录、排除子目录", LIBRARY),
    SCRAPE("刮削", "歌词、性能与并发"),
    /**
     * 子文件夹排除页。
     *
     * 点「音乐库」里的某个目录进来，是**独立一页**（用户明确要求新页面，
     * 不是在原页面里展开），所以它也是一个 section，靠返回键回上一级。
     */
    SUBFOLDERS("子文件夹", "勾选即排除", LIBRARY),
    PLUGINS("插件", "订阅仓库、插件包与网络镜像"),
    /**
     * 插件清单按能力分成的两页。
     *
     * 「元信息源」= 声明了 searchSongs 的插件，「艺术家图源」= 声明了 searchArtistImages 的插件；
     * 两个都声明的插件两边都出现。它们是 PLUGINS 的下一层，不在设置主页单独占一行。
     */
    PLUGIN_META("元信息源", "声明了 searchSongs 的插件", PLUGINS),
    PLUGIN_ARTIST_IMAGE("艺术家图源", "声明了 searchArtistImages 的插件", PLUGINS),
    /**
     * 标签识别规则的人话版。
     *
     * 用户要求：规则不再只是一份 JSON —— 「插件列表」里给它一个入口，
     * 进去能看到每个分组认领了哪些键、右侧固定显示哪个键、值有哪些译名。
     * 规则本身只读，跟着「更新订阅」一起更新。
     */
    TAG_RULES("标签规则", "这份规则都认了哪些标签", PLUGINS),
    EXCLUDE("排除目录", "按目录部分排除，不影响其它目录", LIBRARY),
    /*
     * 「桌面小组件」不再单独占一项：按用户要求并进「外观」。
     *
     * 小组件现在是**四个独立部件**（桌面"添加小组件"列表里直接选），
     * 不再有"样式"这个设置。
     */
    WRITE("写入", "图片质量、内嵌与外置文件"),
    /**
     * 性能设置。已并入「刮削」页（用户要求），不再单独占主页一项；
     * 仍保留为 section 是为了让搜索"并发/多源"能直接跳到对应内容。
     */
    PERFORMANCE("性能", "并发与多源策略", SCRAPE),
    /**
     * 网络遥控：连电脑上 Salt Player 的 SPW 插件，遥控播放。
     *
     * **独立一项，不挂在「插件」下面**（用户明确要求给它单独一项）。
     * 它和刮削插件不是一回事：刮削插件是标签数据源（在手机上跑 JS），
     * 这个是连另一台电脑上的播放器。
     *
     * 这里只放**设置**（地址、端口、自动连接这些一次性配置）；
     * 日常操作在底部「控制」标签里，两处共用同一个连接。
     */
    REMOTE("网络遥控", "连电脑上的 Salt Player 遥控播放"),
    /**
     * 局域网设备 + 每个设备的可用地址列表。
     *
     * 是 [REMOTE] 的下一层：在「网络遥控」里点某台设备就进这里。
     * 地址按**连接优先级**排（局域网 → IPv6 → 公网 IPv4），
     * 并标出哪些不是公网（外网用不了）。
     */
    DEVICES("局域网设备", "可用地址与优先级", REMOTE),
    DEBUG("调试", "运行日志与记录级别"),
    ABOUT("关于", "版本与数据清理");

    companion object {
        /**
         * 设置主页能看到的项。
         *
         * 排掉三类：有 parent 的二级项、合并掉的旧入口、
         * 以及只作为跳转目标的 [SUBFOLDERS]。
         */
        private val MERGED = setOf(DIRECTORIES, EXCLUDE, SUBFOLDERS)
        val roots: List<SettingsSection> = entries.filter { it.parent == null && it !in MERGED }
    }
}

data class SettingEntry(val section: SettingsSection, val title: String, val keywords: String)

/** 一个目录的音频统计。 */
data class FolderStat(
    /** 含所有下级目录在内的音频文件数。 */
    val audioCount: Int = 0,
    /** 其中排序第一位的文件名，列表里当小字提示用。 */
    val sampleFileName: String = ""
)

/** 搜索索引。keywords 里放同义词，方便用不同说法都能找到。 */
val SETTINGS_INDEX: List<SettingEntry> = listOf(
    SettingEntry(SettingsSection.APPEARANCE, "动态取色", "动态颜色 主题 壁纸 取色 monet dynamic color wallpaper"),
    SettingEntry(SettingsSection.APPEARANCE, "主题模式", "深色 浅色 跟随系统 theme dark light mode"),
    SettingEntry(SettingsSection.APPEARANCE, "边角样式", "圆形 圆角 方形 头像 封面 corner shape circle square"),
    SettingEntry(SettingsSection.LIBRARY, "音乐目录", "目录 文件夹 路径 添加 扫描 root folder path"),
    SettingEntry(SettingsSection.LIBRARY, "排除目录", "子目录 排除 部分 excluding subfolder"),
    SettingEntry(SettingsSection.LIBRARY, "时长过滤", "时长 秒 跳过 过滤 duration filter seconds"),
    SettingEntry(SettingsSection.FORMATS, "音频格式", "格式 扩展名 flac mp3 m4a 内嵌 外置 format ext"),
    SettingEntry(SettingsSection.FORMATS, "各格式支持的内嵌能力", "内嵌 支持 封面 歌词 元信息 support embedded"),
    SettingEntry(SettingsSection.LIBRARY, "所有文件访问权限", "权限 存储 permission storage all files"),
    SettingEntry(SettingsSection.SCRAPE, "歌词优先级", "歌词 逐字 逐行 优先 顺序 lyrics priority word line"),
    SettingEntry(SettingsSection.SCRAPE, "下载歌词翻译", "翻译 双语 中文 translation bilingual"),
    SettingEntry(SettingsSection.SCRAPE, "兜底歌词", "兜底 外置 lrc 导出 备份 fallback export"),
    SettingEntry(SettingsSection.SCRAPE, "兜底歌词目录", "目录 路径 兜底 lrc directory path"),
    SettingEntry(SettingsSection.PLUGINS, "导入插件 zip", "插件 导入 zip 安装 import plugin"),
    SettingEntry(SettingsSection.PLUGINS, "插件仓库地址", "仓库 订阅 index.json 更新 repo subscribe"),
    SettingEntry(SettingsSection.PLUGINS, "标签识别规则", "规则 识别 别名 平台 说明 tag rules 订阅 自动 更新"),
    SettingEntry(SettingsSection.PLUGINS, "镜像与连接测试", "镜像 中转 加速 前缀 proxy mirror prefix gh-proxy 测试 连通 可用性 test connection check 上次 成功 记录 last working mirror"),
    SettingEntry(SettingsSection.REMOTE, "PC 地址", "遥控 远程 主机 ip 地址 局域网 tailscale 100 端口 port host address remote"),
    SettingEntry(SettingsSection.REMOTE, "远程控制开关", "启用 开关 遥控 remote control enable"),
    SettingEntry(SettingsSection.REMOTE, "自动连接", "自动 重连 auto connect reconnect"),
    SettingEntry(SettingsSection.REMOTE, "播放控制", "播放 暂停 上一首 下一首 进度 play pause next previous seek"),
    SettingEntry(SettingsSection.WRITE, "写入方式", "内嵌 外置 标签 文件 embed external tag"),
    SettingEntry(SettingsSection.APPEARANCE, "桌面小组件", "部件 小组件 桌面 歌词 进度条 widget lyrics"),
    SettingEntry(SettingsSection.WRITE, "图片处理", "图片 封面 质量 jpg png 原图 quality image original"),
    // 「覆盖策略」这一项已经移除：现在只有刮削窗口里按内容逐项选的「补全 / 覆盖」，
    // 而且每次打开都从「关闭」开始，没有一个全局开关可以搜。
    SettingEntry(SettingsSection.WRITE, "保持修改时间", "修改时间 时间戳 mtime modified time"),
    SettingEntry(SettingsSection.PERFORMANCE, "并发数", "并发 线程 速度 同时 concurrency parallel threads"),
    SettingEntry(SettingsSection.PERFORMANCE, "收集全部源", "全部源 收集 多源 collect all sources"),
    SettingEntry(SettingsSection.DEBUG, "调试日志", "调试 日志 debug log 记录"),
    SettingEntry(SettingsSection.DEBUG, "日志级别", "级别 详细 一般 警告 错误 level verbose warn error"),
    SettingEntry(SettingsSection.ABOUT, "清空曲库索引", "清空 曲库 索引 重置 clear library reset")
)

fun searchSettings(query: String): List<SettingEntry> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return emptyList()
    return SETTINGS_INDEX.filter {
        it.title.lowercase().contains(needle) || it.keywords.lowercase().contains(needle) ||
            it.section.title.lowercase().contains(needle)
    }
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val query: String = "",
    val section: SettingsSection? = null,
    val results: List<SettingEntry> = emptyList(),
    val plugins: List<PluginRecordEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList()
) {
    /** 按某个优先级键排出完整顺序：显式排过的在前，其余按插件列表顺序接上。 */
    fun orderedPlugins(key: String): List<PluginRecordEntity> {
        val ids = plugins.map { it.pluginId }
        val explicit = settings.sourcePriority[key].orEmpty().filter { it in ids }
        val ranked = explicit.mapNotNull { id -> plugins.firstOrNull { it.pluginId == id } }
        return ranked + plugins.filter { it.pluginId !in explicit }
    }
}
