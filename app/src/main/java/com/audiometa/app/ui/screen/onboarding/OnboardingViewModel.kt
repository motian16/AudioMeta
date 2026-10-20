package com.audiometa.app.ui.screen.onboarding

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.DurationFilter
import com.audiometa.app.media.scan.ScanLauncher
import com.audiometa.app.media.scan.ScanService
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 引导页里一个"可以一键添加"的候选目录。 */
data class RootCandidate(
    /** 真实文件系统路径，添加后就是曲库的一个根目录。 */
    val path: String,
    /** 已存在于曲库。 */
    val added: Boolean,
    /** 界面上显示的名字（只是路径最后一段，避免整行都是长路径）。 */
    val label: String
)

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = ServiceLocator.settings
    val settings = settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.audiometa.app.data.prefs.AppSettings())
    val scanProgress = ServiceLocator.scanner.progress

    /**
     * 候选目录（只包含**磁盘上真的存在**的那些）。
     *
     * 计算放在 IO 线程：`File.isDirectory` 对每一次调用都是一次 stat 系统调用，
     * 共享存储上尤其慢（还可能触发 MediaProvider 的查询）。放在主线程上,
     * 引导页第一帧就要等它，而且这里是"每个候选各一次"。
     */
    private val _candidates = MutableStateFlow<List<RootCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()

    /**
     * 引导页上的一次性提示（选目录失败、目录已在曲库…）。
     *
     * 以前 `treePath()` 的 `IllegalArgumentException` 会在 `viewModelScope.launch`
     * 里逃出去 —— 协程没有父作用域捕获，**直接就是一次闪退**：
     * 用户选了一个网盘/云盘目录，应用当场消失。现在所有失败都落成一句话。
     */
    private val _notice = MutableStateFlow("")
    val notice = _notice.asStateFlow()

    private var candidatesChecked = false

    fun clearNotice() {
        _notice.value = ""
    }

    /**
     * 检查候选目录。**只跑一次**：引导页会重组很多次，
     * 每次重组都去 stat 一遍磁盘是没必要的。
     */
    fun checkCandidates() {
        if (candidatesChecked) return
        candidatesChecked = true
        refreshCandidates(force = true)
    }

    /** 重新算一遍"哪些候选已加入"（从系统设置页回来时用）。 */
    fun refreshCandidates(force: Boolean = false) {
        if (!force && !candidatesChecked) return
        candidatesChecked = true
        viewModelScope.launch {
            val existing = runCatching { settingsStore.settings.first().roots }
                .getOrDefault(emptyList())
            val found = withContext(Dispatchers.IO) {
                WELL_KNOWN_MUSIC_DIRS.mapNotNull { path ->
                    // 只列真的存在的目录：引导页上出现一行"点了没反应"的候选
                    // 比不出现更糟。
                    val ok = runCatching { File(path).isDirectory }.getOrDefault(false)
                    if (!ok) return@mapNotNull null
                    RootCandidate(
                        path = path,
                        added = path in existing,
                        label = File(path).name.ifBlank { path }
                    )
                }
            }
            AppLog.d(TAG, "引导页候选目录 " + found.size + " 个")
            _candidates.value = found
        }
    }

    /**
     * 记住系统选择器给的目录。
     *
     * 两个失败点都必须兜住：
     * 1. [treePath] 对网盘/云盘目录会**主动抛异常**（它拼不出本机路径）；
     * 2. 写设置可能失败（DataStore 磁盘错误）。
     * 以前这两处都在协程里裸奔，异常逃出去 = 闪退。
     */
    fun addRoot(uri: Uri) {
        viewModelScope.launch {
            val path = runCatching { treePath(uri) }.getOrElse { error ->
                AppLog.w(TAG, "目录无法换算成本机路径：" + error.message)
                _notice.value = error.message ?: "这个位置不是本机存储，换一个目录试试"
                return@launch
            }
            val already = runCatching { settingsStore.settings.first().roots.contains(path) }
                .getOrDefault(false)
            if (already) {
                _notice.value = "这个目录已经在曲库里了"
                return@launch
            }
            runCatching { settingsStore.addRoot(path, uri.toString()) }
                .onFailure {
                    AppLog.w(TAG, "加入目录失败：" + it.message)
                    _notice.value = "加入目录失败：" + (it.message ?: "未知错误")
                }
            refreshCandidates()
        }
    }

    /**
     * 添加一个"已知路径"的目录。
     *
     * 和系统选择器走的是**同一条路**（[com.audiometa.app.data.prefs.SettingsStore.addRoot]），
     * 只是没有 SAF uri：一个纯文件路径 + 空 uri。扫描只认路径，
     * 所以这样加进来的根目录和用选择器加的完全等价（读标签、增量判断都一样）。
     */
    fun addRootPath(path: String) {
        viewModelScope.launch {
            runCatching { settingsStore.addRoot(path, "") }
                .onFailure {
                    _notice.value = "加入目录失败：" + (it.message ?: "未知错误")
                    return@launch
                }
            // 立刻标成"已添加"，用户不必等设置流回来才知道点中了。
            _candidates.value = _candidates.value.map {
                if (it.path == path) it.copy(added = true) else it
            }
        }
    }

    /** 移除一个已经加入的目录（引导页上点 ×）。 */
    fun removeRoot(path: String) {
        viewModelScope.launch {
            runCatching { settingsStore.removeRoot(path) }
                .onFailure { _notice.value = "移除失败：" + (it.message ?: "未知错误") }
            _candidates.value = _candidates.value.map {
                if (it.path == path) it.copy(added = false) else it
            }
        }
    }

    fun setDuration(filter: DurationFilter) {
        viewModelScope.launch { settingsStore.setDurationFilter(filter) }
    }

    /**
     * 发起首次扫描。
     *
     * 走统一的 [com.audiometa.app.media.scan.ScanLauncher]：启动失败（服务没注册、
     * 系统限制前台服务等）会写进扫描进度，界面能看到原因 ——
     * 以前这里直接 `ScanService.start`，抛异常就是闪退。
     */
    fun startScan() {
        val current = settings.value
        if (current.roots.isEmpty()) {
            _notice.value = "先选一个音乐文件夹，再开始扫描"
            return
        }
        ScanLauncher.start(
            getApplication(),
            current.roots,
            current.formats,
            current.excluded,
            current.minDurationMs(),
            false
        )
    }

    /** 取消正在进行的扫描（前台服务的取消动作，和设置页同一个入口）。 */
    fun cancelScan() {
        runCatching { ScanService.cancel(getApplication()) }
            .onFailure { _notice.value = "取消失败：" + (it.message ?: "未知错误") }
    }

    fun finish(done: () -> Unit) {
        viewModelScope.launch {
            runCatching { settingsStore.setOnboardingDone(true) }
                .onFailure {
                    _notice.value = "保存失败，请重试：" + (it.message ?: "未知错误")
                    return@launch
                }
            done()
        }
    }

    private companion object {
        const val TAG = "OnboardingViewModel"

        /**
         * 一键添加的候选目录。
         *
         * 都是 Android 约定的公共音乐/下载位置；**只有真的存在的才会显示**，
         * 所以这份表可以放心地写宽一点，不用为各家 ROM 的差异做分支。
         */
        val WELL_KNOWN_MUSIC_DIRS = listOf(
            "/storage/emulated/0/Music",
            "/storage/emulated/0/Music/Download",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents"
        )
    }
}
