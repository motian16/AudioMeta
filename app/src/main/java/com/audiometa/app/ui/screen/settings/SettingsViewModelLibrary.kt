package com.audiometa.app.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.DurationFilter
import com.audiometa.app.media.scan.ScanLauncher
import com.audiometa.app.ui.screen.onboarding.treePath
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * 重新扫描：重读曲库 + 重扫未知标签。
 *
 * 用户要求"音频库重新扫描也包含元信息页的扫描" ——
 * 元信息页的扫描本来就是逐首读文件标签，和重新扫描是同一件事，
 * 分两处各扫一遍纯属浪费。这里一次跑完，元信息页打开时直接看到新结果。
 */
internal fun SettingsViewModel.rescanEverything() {
    val context = getApplication<Application>()
    viewModelScope.launch {
        /*
         * 1. 重建曲目索引（走前台服务，耗时较长）。
         *
         * ## 失败信息必须写进 ScanProgress
         *
         * 这里以前把异常写进 `cacheMessage` —— 那个只在「关于 - 缓存」页显示，
         * 而用户点「重新扫描」时站在**音乐库页**。于是前台服务启动失败时，
         * 用户的体验是"点了按钮，什么都没发生，一个字都没有"，
         * 连"失败"都看不见。
         *
         * 现在写进 [com.audiometa.app.domain.model.ScanProgress]：
         * `ScanProgressBlock` 就在按钮正下方，扫不动时立刻显示原因。
         */
        runCatching {
            ScanLauncher.start(
                context,
                lastKnownRoots(),
                lastKnownFormats(),
                lastKnownExcluded(),
                lastKnownMinDuration(),
                false
            )
        }

        // 2. 顺带把未知标签重扫一遍。
        //    等索引跑完再做，否则读到的还是旧的曲目表。
        runCatching {
            while (ServiceLocator.scanner.progress.value.running) {
                kotlinx.coroutines.delay(300)
            }
            rescanMetaTags()
        }
    }
}

/**
 * 只重扫未知标签（元信息页那一项），不碰曲目索引。
 *
 * **整个循环必须在 IO 线程上。** 它以前裸在调用方的 `viewModelScope.launch`
 * 里，也就是跑在主线程上：Room 对**每一个** DAO 方法（包括 suspend 的）
 * 都会先做一次 `assertNotMainThread()`，命中就抛
 * `IllegalStateException: Cannot access database on the main thread`。
 * 以前没有规则时 [com.audiometa.app.domain.tagrules.TagRules.classify]
 * 直接返回空表，库表根本不会变大，这个坑被掩盖着；现在没有规则也会逐首入库，
 * 几百首就是几百次主线程写库 —— 用户看到的就是"点重新扫描直接闪退"。
 * 就算不抛，几百次文件解析放在主线程上也必然 ANR。
 */
internal suspend fun SettingsViewModel.rescanMetaTags() = withContext(Dispatchers.IO) {
    val reader = ServiceLocator.tagReader
    val tracks = ServiceLocator.tracks.allOnce()
    tracks.forEach { track ->
        val file = java.io.File(track.path)
        val entries = if (file.isFile) {
            runCatching { reader.readAllTags(file) }.getOrDefault(emptyMap())
                .let { raw ->
                    if (raw.isEmpty()) emptyList()
                    else com.audiometa.app.domain.tagrules.TagRules.classify(
                        raw,
                        includeStandard = false,
                        // 值空的标签也留下 —— 和元信息页的「重新扫描」、
                        // 后台补全保持一致，否则同一首歌三个入口扫出来的行数不一样。
                        keepBlankValues = true
                    )
                }
        } else {
            emptyList()
        }
        ServiceLocator.metaTags.replaceForTrack(
            trackId = track.id,
            rows = entries.map {
                com.audiometa.app.data.db.MetaTagEntity(
                    trackId = track.id,
                    key = it.key,
                    value = it.value.take(2000),
                    platform = it.platform.orEmpty(),
                    tool = it.tool.orEmpty(),
                    hints = it.hints.joinToString("\n")
                )
            }
        )
    }
    ServiceLocator.metaTags.pruneTo(tracks.map { it.id })
}

private suspend fun SettingsViewModel.lastKnownRoots(): List<String> = ServiceLocator.settings.settings.first().roots
private suspend fun SettingsViewModel.lastKnownFormats(): Set<String> = ServiceLocator.settings.settings.first().formats
private suspend fun SettingsViewModel.lastKnownExcluded(): Set<String> = ServiceLocator.settings.settings.first().excluded
private suspend fun SettingsViewModel.lastKnownMinDuration(): Long = ServiceLocator.settings.settings.first().minDurationMs()

/** 进某个目录的子文件夹页。 */
internal fun SettingsViewModel.openSubfolders(rootPath: String) {
    subfolderRoot = rootPath
    openSection(SettingsSection.SUBFOLDERS)
    loadFolderStats()
}

/**
 * 每个目录的音频统计：**含所有下级**的音频文件数 + 其中第一个文件名。
 *
 * 用户要求：子目录列表按"里面音频文件多到少"排序（要算上所有层级），
 * 行下面的小字显示其中一个音频文件的名字（取排序后的第一位）。
 * 曲目表里本来就有 folderPath，一次遍历往上累加即可，不用读文件。
 */
internal fun SettingsViewModel.loadFolderStats() {
    viewModelScope.launch {
        val stats = withContext(Dispatchers.IO) {
            val tracks = ServiceLocator.tracks.allOnce()
            val counts = HashMap<String, Int>()
            val samples = HashMap<String, String>()
            tracks.forEach { track ->
                var dir = track.folderPath
                val name = track.path.substringAfterLast('/')
                while (dir.isNotBlank()) {
                    counts[dir] = (counts[dir] ?: 0) + 1
                    val current = samples[dir]
                    if (current == null || name < current) samples[dir] = name
                    val cut = dir.lastIndexOf('/')
                    if (cut <= 0) break
                    dir = dir.substring(0, cut)
                }
            }
            counts.keys.associateWith { FolderStat(counts[it] ?: 0, samples[it].orEmpty()) }
        }
        folderStats = stats
    }
}

/**
 * 从子文件夹页返回「音乐库」。
 *
 * 有**子页根目录**这个前置状态才动作：本函数同时被 SettingsScreen 的
 * `onDispose` 调用（Tab 切换离开设置页时），那时 subfolderRoot 已经被
 * exitSettings 清空。若无条件 openSection(LIBRARY)，会把 exitSettings
 * 刚写入的 null 又覆盖成 LIBRARY —— 下次进设置的第一帧读到的就是音乐库页，
 * 表现为"闪过音频库设置页再回主页"，这是该闪烁反复修不掉的真正根因。
 */
internal fun SettingsViewModel.backFromSubfolders() {
    if (subfolderRoot == null) return
    subfolderRoot = null
    openSection(SettingsSection.LIBRARY)
}

/**
 * 把用户选中的文件夹加进曲库。
 *
 * ## 为什么必须在这里校验
 *
 * 以前是这一句：
 * ```kotlin
 * val path = runCatching { treePath(uri) }.getOrDefault(uri.toString())
 * ```
 * `treePath` 抛异常时（选的不是本地存储、提供方给的 docId 不带 `:` 等），
 * 兜底值是 **`uri.toString()`** —— 也就是把 `content://…` 这串东西
 * **当成文件路径存了下来**。
 *
 * 后果是：扫描器拿到 `File("content://…")`，`exists()` 为 false 或
 * `listFiles()` 返回 null，于是**一个文件都进不去**，界面上永远
 * "已扫描 0 个文件 · 找到音频 0 首"，而且**不报错**。
 *
 * 现在：解析不出来、或者解析出来的目录根本不可读，就**拒绝加入并说明原因**，
 * 不再往配置里塞一个坏路径。
 */
internal fun SettingsViewModel.addRoot(uri: Uri) {
    val ctx = getApplication<Application>()

    val path = runCatching { treePath(uri) }.getOrElse { error ->
        libraryNotice = "这个位置不能用：无法解析出本机路径（" +
            (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName) +
            "）。请选「内部存储」里的文件夹，不要选网盘或云盘"
        return
    }

    // 解析出来的必须是一个**真实可读的目录**，否则加进去也是白扫
    val dir = java.io.File(path)
    val problem = when {
        !dir.exists() -> "这个位置在本机不存在：$path"
        !dir.isDirectory -> "选的不是文件夹：$path"
        dir.listFiles() == null -> "读不到这个文件夹：$path（检查「所有文件访问」权限）"
        else -> null
    }
    if (problem != null) {
        libraryNotice = problem
        AppLog.w("Settings", "拒绝加入音乐目录：" + problem + "，来源 " + uri)
        return
    }

    libraryNotice = ""
    AppLog.i("Settings", "加入音乐目录：" + path)
    edit { ServiceLocator.settings.addRoot(path, uri.toString()) }
}

internal fun SettingsViewModel.clearLibraryNotice() {
    libraryNotice = ""
}

internal fun SettingsViewModel.removeRoot(path: String) = edit { ServiceLocator.settings.removeRoot(path) }

/**
 * 取消勾选某个"内置默认排除"目录时记一笔。
 *
 * 用户要求"可手动取消勾选，此后不会再自动排除"——记下这条路径，
 * 以后首次扫描的自动排除就跳过它。
 */
internal fun SettingsViewModel.keepAutoExclude(path: String) {
    viewModelScope.launch { ServiceLocator.settings.markAutoExcludeKept(path) }
}

/** 曲库来源：固定文件夹 / Android 媒体库，二选一。 */
internal fun SettingsViewModel.setLibrarySource(value: String) {
    edit { ServiceLocator.settings.setLibrarySource(value) }
}

internal fun SettingsViewModel.toggleFormat(ext: String, enabled: Boolean) {
    val current = state.value.settings.formats.toMutableSet()
    val normalized = AudioFormats.normalize(ext)
    if (enabled) current += normalized else current -= normalized
    if (current.isEmpty()) return
    edit { ServiceLocator.settings.setFormats(current) }
}

internal fun SettingsViewModel.useCommonFormats() = edit { ServiceLocator.settings.setFormats(AudioFormats.COMMON.toSet()) }
internal fun SettingsViewModel.useAllFormats() = edit { ServiceLocator.settings.setFormats(AudioFormats.ALL_SET) }

internal fun SettingsViewModel.setDuration(filter: DurationFilter) = edit { ServiceLocator.settings.setDurationFilter(filter) }
internal fun SettingsViewModel.setCustomMinSeconds(seconds: Int) =
    edit { ServiceLocator.settings.setCustomMinSeconds(seconds.coerceIn(0, 3600)) }

internal fun SettingsViewModel.setExcludeSubdirs(value: Boolean) = edit { ServiceLocator.settings.setExcludeSubdirs(value) }

/** 按具体子目录部分排除：只影响该目录及其子树。 */
internal fun SettingsViewModel.toggleFolderExcluded(path: String, excluded: Boolean) {
    edit {
        ServiceLocator.settings.toggleExcluded(path, excluded)
        ServiceLocator.folders.setExcludedTree(path, excluded)
    }
}

internal fun SettingsViewModel.setSkipSilentIntro(value: Boolean) = edit { ServiceLocator.settings.setSkipSilentIntro(value) }

internal fun SettingsViewModel.clearLibrary() {
    viewModelScope.launch {
        ServiceLocator.tracks.clear()
        ServiceLocator.folders.clear()
        ServiceLocator.logs.clear()
        ServiceLocator.lyricsCache.clear()
        com.audiometa.app.media.audio.SilenceProfileStore.clear(getApplication())
    }
}
