package com.audiometa.app.ui.screen.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRight
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.domain.model.ImageMode
import com.audiometa.app.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- 缓存 ----------------

@Composable
internal fun CacheDetailPage(vm: SettingsViewModel) {
    val stats = vm.cacheStats
    val loading = vm.cacheStatsLoading
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(bottom = 24.dp)
    ) {
        SettingsGroup("本地缓存总计") {
            SettingsRow(
                title = (stats?.trackCount ?: 0).toString() + " 首",
                summary = stats?.let {
                    // 缩略图只有专辑封面这一类会占磁盘：艺术家图是实时读音频里的数据，
                    // 没有磁盘缓存，所以这里不再报"头像 N 张"（那会是个恒为 0 的数字）。
                    "封面 " + it.audioCoverFiles +
                        " 张 · 歌词 " + it.lyricsRows + " 首 · 静音档案 " + it.silenceProfiles + " 首"
                } ?: "正在统计…"
            ) {
                OutlinedButton(onClick = { vm.loadCacheStats() }, enabled = !loading) {
                    Text(if (loading) "统计中" else "重新统计")
                }
            }
        }

        SettingsGroup(
            "分类刷新",
            "增量刷新：只清失效、补缺的缓存"
        ) {
            CacheKind.entries.forEachIndexed { index, kind ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    title = kind.title,
                    summary = cacheSummary(stats, kind)
                ) {
                    TextButton(onClick = { vm.refreshCache(kind) }) { Text("刷新") }
                }
            }
        }
        val cacheMsg by vm.cacheMessage.collectAsState()
    cacheMsg.takeIf { it.isNotBlank() }?.let { SettingsNote(it) }
        SettingsNote("全量重建请到「音乐库 - 重新扫描」")
    }
}

private fun cacheSummary(stats: CacheStats?, kind: CacheKind): String {
    val s = stats ?: return "—"
    fun size(bytes: Long) = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> (bytes / 1024L).toString() + " KB"
        else -> bytes.toString() + " B"
    }
    return when (kind) {
        CacheKind.AUDIO_COVERS -> s.audioCoverFiles.toString() + " 张 · " + size(s.audioCoverBytes)
        // 如实反映新行为：艺术家图不落盘，没有可统计的大小，只有一个内存缓存
        // （点"刷新"就是清掉它，下次显示时重新从音频里读）。
        CacheKind.ARTIST_IMAGES -> "实时读取 · 不占磁盘"
        CacheKind.LYRICS -> s.lyricsRows.toString() + " 首 · " + size(s.lyricsBytes)
        CacheKind.SILENCE -> s.silenceProfiles.toString() + " 首 · " + size(s.silenceBytes)
        CacheKind.ORPHANS -> "清理没人引用的封面缩略图、图片、缓存行与临时文件"
    }
}

// ---------------- 关于 ----------------

@Composable
internal fun AboutSection(vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var banner by rememberSaveable { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SettingsGroup("版本") {
        SettingsRow("当前版本", com.audiometa.app.di.ServiceLocator.versionName)
        SettingsDivider()
        SettingsRow("缓存", "分类统计与增量刷新", onClick = { vm.openCachePage() }) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SettingsGroup("数据") {
        SettingsRow(
            title = "清空曲库",
            summary = "只清数据库和缓存，不动磁盘上的音频文件"
        ) {
            TextButton(onClick = { vm.clearLibrary() }) { Text("清空") }
        }
        SettingsDivider()
        SettingsRow(
            title = "重建全部缓存",
            summary = "封面、歌词、静音档案一次重建"
        ) {
            TextButton(onClick = { vm.refreshAllCaches() }) { Text("重建") }
        }
        SettingsDivider()
        SettingsRow(
            title = "后台重新索引",
            summary = "不重新遍历目录，只补齐标签与歌词"
        ) {
            TextButton(onClick = { vm.reindexBackground() }) { Text("开始") }
        }
    }

    SettingsGroup("联系") {
        SettingsRow(
            title = "邮箱",
            summary = com.audiometa.app.util.AboutIntents.EMAIL,
            icon = Icons.Outlined.MailOutline,
            onClick = {
                scope.launch {
                    val opened = withContext(Dispatchers.IO) {
                        com.audiometa.app.util.AboutIntents.sendEmail(context)
                    }
                    if (!opened) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                            com.audiometa.app.util.AboutIntents.EMAIL
                        ))
                        banner = "没有邮件客户端，地址已复制"
                    }
                }
            }
        )
        SettingsDivider()
        SettingsRow(
            title = "QQ 群",
            summary = com.audiometa.app.util.AboutIntents.QQ_GROUP,
            icon = Icons.Outlined.Sell,
            onClick = {
                scope.launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.audiometa.app.util.AboutIntents.openQqGroup(context)
                    }
                    if (!ok) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                            com.audiometa.app.util.AboutIntents.QQ_GROUP
                        ))
                        banner = "没有可用的 QQ，群号已复制"
                    }
                }
            }
        )
    }
    if (banner.isNotBlank()) SettingsNote(banner)
    val cacheMsg by vm.cacheMessage.collectAsState()
    cacheMsg.takeIf { it.isNotBlank() }?.let { SettingsNote(it) }
}

// ---------------- 写入 / 性能 / 调试 ----------------

@Composable
internal fun WriteSection(settings: AppSettings, vm: SettingsViewModel) {
    SettingsGroup("写到哪") {
        SettingsSwitchRow(
            title = "写入文件内嵌标签",
            checked = settings.writeEmbedded,
            onChecked = vm::setWriteEmbedded
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "同时写外置文件",
            summary = "封面写 cover.jpg、歌词写同名 .lrc",
            checked = settings.writeExternal,
            onChecked = vm::setWriteExternal
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "保持文件修改时间",
            summary = "还原 mtime，避免同步工具误判",
            checked = settings.keepModTime,
            onChecked = vm::setKeepModTime
        )
    }

    SettingsGroup("图片") {
        SettingsRow("处理方式", "原样保留、统一裁剪还是按比例缩放")
        SettingsSegmented(
            options = ImageMode.entries.map { it.name to imageModeLabel(it) },
            selected = settings.imageMode.name,
            onSelect = { vm.setImageMode(ImageMode.valueOf(it)) }
        )
        SettingsDivider()
        SettingsSliderRow(
            title = "图片质量",
            value = settings.imageQuality,
            valueRange = 40..100,
            valueText = settings.imageQuality.toString() + "%",
            onValueChange = vm::setImageQuality
        )
    }
}

@Composable
internal fun PerformanceSection(settings: AppSettings, vm: SettingsViewModel) {
    SettingsGroup("并发") {
        SettingsRow(
            "同时处理的歌曲数",
            summary = "2 到 4 比较稳，太高会被源站限速"
        )
        // 分段按钮比滑杆更紧凑，点哪一档就是哪一档，不用先拖再对数字。
        SettingsSegmented(
            options = (1..4).map { it.toString() to it.toString() },
            selected = settings.concurrency.toString(),
            onSelect = { vm.setConcurrency(it.toIntOrNull() ?: settings.concurrency) }
        )
    }
    SettingsGroup("多源策略") {
        SettingsSwitchRow(
            title = "收集全部源的结果",
            summary = "关掉时某个源拿到结果就停，打开会问完所有源",
            checked = settings.collectAll,
            onChecked = vm::setCollectAll
        )
    }
}

@Composable
internal fun DebugSection(settings: AppSettings, vm: SettingsViewModel) {
    // 「日志文件」那一块要用它读路径和大小；提到函数顶部，下面不再重复声明。
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsGroup("日志", "把运行日志写进文件，方便排查") {
        SettingsSwitchRow(
            title = "记录运行日志",
            checked = settings.debugLogging,
            onChecked = vm::setDebugLogging
        )
        /*
         * 「日志级别」和下面「日志文件」那一整块**只在开关打开时才出现**
         * （用户要求："只有在调试开启时才显示底下的内容"）。
         * 关着的时候它们没有意义：不写盘，级别也就无从谈起。
         */
        if (settings.debugLogging) {
            SettingsDivider()
            SettingsRow("日志级别", "低于这个级别的日志不写入")
            SettingsSegmented(
                options = LogLevel.entries.map { it.name to it.label },
                selected = settings.debugLevel.name,
                onSelect = { vm.setDebugLevel(LogLevel.valueOf(it)) }
            )
        }

        SettingsDivider()
        /*
         * 原来的「调试模式」改成「发送报告」。
         *
         * 开关只负责"出现按钮"，按钮本身在**元信息页**的顶栏上（用户要求）：
         * 复制键左边多一个发送键，点了调邮件客户端，正文**只带未知标签**。
         */
        SettingsSwitchRow(
            title = "发送报告",
            summary = "元信息页顶栏出现发送键，可把未知标签发到 " +
                com.audiometa.app.util.AboutIntents.EMAIL,
            checked = settings.debugMode,
            onChecked = vm::setDebugMode
        )
    }

    if (settings.debugLogging) {
        // 路径/大小都从 AppLog 现读：界面显示的位置和真正写入的位置是同一个来源，
        // 不会出现"界面说在这儿、其实写在那儿"（用户报过日志文件根本没生成）。
        var logPath by remember(context) { mutableStateOf("") }
        var logSize by remember(context) { mutableStateOf(0L) }
        var notice by remember(context) { mutableStateOf("") }
        // 进出这一页时各刷一次；中间不需要轮询，看的时候点一下"刷新"就够。
        var ticket by remember(context) { mutableStateOf(0) }
        LaunchedEffect(ticket) {
            logPath = com.audiometa.app.util.AppLog.filePath(context)
            logSize = com.audiometa.app.util.AppLog.fileSize(context)
        }

        SettingsGroup("日志文件") {
            SettingsRow(
                "运行日志",
                logPath + if (logSize > 0) {
                    "（" + formatSize(logSize) + "）"
                } else {
                    "（还没有写入）"
                }
            ) {
                TextButton(onClick = { ticket++ }) { Text("刷新") }
            }
            SettingsDivider()
            /*
             * 复制：文件在 Android/data 里，很多手机的文件管理器进不去。
             * 直接给一份文本，用户粘到聊天窗口就能发出来 —— 这比"路径在那儿，
             * 你自己去找"有用得多（用户报"日志文件没生成"时，多半是根本找不到）。
             */
            SettingsRow(
                title = "复制运行日志",
                summary = "按当前日志级别过滤后复制，可以直接粘贴发出来"
            ) {
                TextButton(onClick = {
                    val text = com.audiometa.app.util.AppLog.snapshotText(settings.debugLevel)
                    com.audiometa.app.ui.component.copyToClipboard(context, "运行日志", text)
                    notice = "已复制运行日志（" + text.lineSequence().count() + " 行）"
                }) { Text("复制") }
            }
            SettingsDivider()
            SettingsRow(
                title = "清空运行日志",
                summary = "只删日志文件，不动曲库和缓存"
            ) {
                TextButton(onClick = {
                    val ok = com.audiometa.app.util.AppLog.clearFile(context)
                    notice = if (ok) "已清空运行日志" else "清空失败"
                    ticket++
                }) { Text("清空") }
            }
            SettingsDivider()
            /*
             * 崩溃记录。
             *
             * 只显示"路径 + 有没有内容"：这是给用户照着找文件用的，
             * 报告内容本身贴在这里没有意义（几百行，还挤占页面）。
             * 文件路径由 CrashLog 提供，和实际写入的位置是同一个来源。
             */
            val crashPath = remember(context) {
                com.audiometa.app.util.CrashLog.file(context).absolutePath
            }
            val crashExists = remember(context, ticket) {
                com.audiometa.app.util.CrashLog.exists(context)
            }
            SettingsRow(
                "崩溃记录",
                crashPath + if (crashExists) "（已记录到崩溃）" else "（暂无记录）"
            )
        }
        if (notice.isNotBlank()) SettingsNote(notice)
    }
}

/** 文件大小的短写法：日志最多两份 512KB，不需要更细的单位。 */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> (bytes / (1024L * 1024L)).toString() + " MB"
    bytes >= 1024L -> (bytes / 1024L).toString() + " KB"
    else -> bytes.toString() + " B"
}

/** 图片处理方式的中文名。 */
private fun imageModeLabel(mode: ImageMode): String = when (mode) {
    ImageMode.ORIGINAL -> "原样保留"
    ImageMode.CUSTOM -> "自定义"
}
