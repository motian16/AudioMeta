package com.audiometa.app

import android.app.Application
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用进程。
 *
 * **这里几乎什么都不做，是刻意的。**
 *
 * 进程被拉起来不等于"用户打开了应用"。桌面上移除/重排应用图标、系统统计
 * 组件用量、包管理器重新解析 MAIN/LAUNCHER，都会让系统把我们的进程拉起来。
 * 这些场景里系统是**同步等我们应答**的（binder 回来之前它就卡着），
 * 而 `Application.onCreate` 跑在主线程上 —— 以前这里开 Room、收 DataStore、
 * 起协程读文件、还要 startForegroundService，几百毫秒起步，
 * 桌面那边看到的就是"移除图标时桌面自己重启了、特别卡"。
 *
 * 所以现在 `onCreate` 只留两件事：一个赋值（[ServiceLocator.init]）
 * 和一个纯内存的标志位。所有真实工作都挪到界面**画完第一帧之后**
 * （见 [onFirstFrame]，由 MainActivity 触发）；应用没被真正打开，
 * 就一次文件 IO、一次数据库查询、一次前台服务都不会发生。
 */
class App : Application() {

    /**
     * 进程级的一次性初始化开关。
     *
     * 旋转屏幕、从后台回来都会让 Activity 重建，[onFirstFrame] 会被反复调用；
     * 但"读设置、迁移目录、清临时文件"这些一辈子做一次就够。
     */
    private val deferredStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // 只记下 applicationContext，不解引用任何 by lazy ——
        // 一旦碰了它们（数据库、DataStore、插件运行时）就等于把上面说的那些
        // 重活又搬回来了。
        ServiceLocator.init(this)

        /*
         * 崩溃记录。
         *
         * **必须在这里装，不能等第一帧之后**：启动阶段（读设置、开数据库、
         * 拉前台服务）出问题同样是闪退，而那正是最难复现的一类。
         * 它只做一次赋值，不触发任何 IO —— 和上面"onCreate 只留两件事"
         * 的原则不冲突；真正的文件写入只在异常真的发生时进行。
         */
        com.audiometa.app.util.CrashLog.install(this)

        /*
         * 远程控制的配置同步：**进程级**，不等第一帧。
         *
         * 为什么必须挪出 [onFirstFrame]：那里的收集跑在界面驱动的路径上，
         * 而且界面一销毁就没人再喂配置了。用户报的是"App 在后台就断连"——
         * 遥控连接本身是进程级资源（`ServiceLocator.remoteControl` 是单例），
         * 但**驱动它的配置流挂在界面生命周期上**，界面一停就断了后续
         * 的重连/重扫编排。
         *
         * 这里只读 DataStore（不碰数据库、不建目录、不做文件 IO），
         * 所以放在 onCreate 不会把"桌面移除图标时同步等我们应答"那条路拖慢。
         * 真正的连接动作仍然由 [RemoteControlClient] 在它自己的进程级作用域里干。
         */
        scope.launch {
            runCatching {
                ServiceLocator.settings.settings.collect { settings ->
                    /*
                     * 只喂**窄配置对象**，而且 RemoteControlClient.apply()
                     * 自己会判断"连接参数到底变没变"，没变就什么都不做 ——
                     * 上一版把 apply() 挂在 LaunchedEffect(整个 AppSettings) 上，
                     * 任何设置变化都会重跑并重连，界面因此闪烁。
                     */
                    /*
                     * 固定内网 IP 模式不在此直连：启动要走
                     * 「固定局域网 IP（与域名并发）→ IPv6 → 公网」三段编排，
                     * 由设置 ViewModel 在首次读到配置时发起。
                     */
                    if (settings.remote.enabled &&
                        settings.remote.hasTarget &&
                        !settings.remote.fixedLan
                    ) {
                        ServiceLocator.remoteControl.apply(settings.remote)
                    }
                }
            }.onFailure { AppLog.w(TAG, "远程控制配置同步失败：" + it.shortDescription()) }
        }
    }

    /**
     * 界面真正显示出来之后的第一次初始化。
     *
     * 由 [MainActivity] 在第一帧回调里调用 —— 到这个时候桌面/系统的
     * binder 请求早就应答完了，我们再慢也只影响自己。
     *
     * 全部跑在 IO 线程上，且每一步都各自兜住异常：启动阶段的任何失败
     * 都只该在日志里留一行，不该让用户看到一个打不开的应用。
     */
    fun onFirstFrame() {
        if (!deferredStarted.compareAndSet(false, true)) return
        scope.launch {
            val settings = runCatching { ServiceLocator.settings.settings.first() }
                .onFailure { AppLog.w(TAG, "读取设置失败，本次启动用默认值：" + it.shortDescription()) }
                .getOrNull()
            if (settings != null) {
                AppLog.enabled = settings.debugLogging
                AppLog.minLevel = settings.debugLevel
                // 遥控开着就把前台保活服务拉起来：此时首帧已经画完（用户在界面上），
                // 不受 Android 12+ 的后台启动前台服务限制。
                if (settings.remote.enabled) {
                    com.audiometa.app.remote.RemoteControlService.start(this@App)
                }
            }

            /*
             * 一次性迁移：镜像表的内置默认值换过两轮（最早两个 → 六个 → 现在这四个
             * 真能转发 Release 资产的）。只有存下来的**正好**等于某一版老默认值时才替换 ——
             * 用户自己加过、删过的表一个都不动。
             */
            runCatching {
                val stored = settings?.mirrorPrefixes.orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (stored in com.audiometa.app.data.prefs.SettingsStore.LEGACY_MIRROR_PREFIXES) {
                    ServiceLocator.settings.setMirrorPrefixes(
                        com.audiometa.app.data.prefs.SettingsStore.DEFAULT_MIRROR_PREFIXES
                    )
                }
            }.onFailure { AppLog.d(TAG, "镜像表迁移失败（不影响使用）：" + it.shortDescription()) }

            // 标签识别规则：读订阅时装进来的那一份，没有就是没规则。
            // 放在这里而不是 onCreate：它会读私有目录里的 tag_rules.json
            //（没有的话还会去翻 assets），都是文件 IO。
            // 界面侧唯一会用到它的是「元信息」页，那时这一步早就跑完了。
            runCatching { com.audiometa.app.domain.tagrules.TagRules.load(this@App) }
                .onFailure { AppLog.w(TAG, "标签规则加载失败：" + it.shortDescription()) }

            // 存储维护：
            //   1. 把旧版本的目录名迁到新布局（缩略图/ → thumbnails/，插件/ → plugins/）
            //   2. 清掉空的、没有意义的目录
            //   3. 缩略图做一次容量淘汰、清临时文件
            runCatching {
                com.audiometa.app.util.StoragePaths.migrateLegacyLayout(this@App)
                com.audiometa.app.media.image.ThumbnailCache.maintain(this@App)
                com.audiometa.app.util.StoragePaths.pruneEmptyDirectories(this@App)
            }.onFailure { AppLog.d(TAG, "存储维护失败（不影响使用）：" + it.shortDescription()) }

            enrichInBackground()
        }

        // 日志开关跟随设置：用户可能在调试页里改（见 SettingsViewModel.setDebugLogging）。
        // 这里**刻意不再碰桌面图标 / 组件启用状态**：
        // 以前每次设置变化都去对齐一次图标，只要组件状态被写一次，
        // 桌面就会重新枚举一遍启动项 —— 用户看到的现象正是
        // "从桌面移除图标之后桌面重启、很卡"。
        // 现在读写成对出现的地方只有一处：用户在「设置 - 桌面小组件」里
        // 主动点「恢复 / 隐藏桌面图标」（见 util/LauncherIcon）。
        //
        // **远程控制的配置同步不在这里**：它在 onCreate 里跑（进程级）。
        // 这一份只管日志开关，界面销毁后停了也不影响连接。
        scope.launch {
            runCatching {
                ServiceLocator.settings.settings.collect { settings ->
                    AppLog.enabled = settings.debugLogging
                    AppLog.minLevel = settings.debugLevel
                }
            }.onFailure { AppLog.d(TAG, "日志开关跟随设置失败：" + it.shortDescription()) }
        }

        // 小组件跟随播放状态自动重画（进程活着就一直跑）
        runCatching { com.audiometa.app.widget.AudioMetaWidgetProvider.observe(this) }
            .onFailure { AppLog.d(TAG, "小组件监听启动失败：" + it.shortDescription()) }
    }

    /**
     * 界面显示之后再推进后台补全。
     *
     * 从 `Application.onCreate` 挪过来的：进程被系统顺手拉起时不该有任何
     * 后台动作（尤其是 startForegroundService），只有用户真的打开了应用才做。
     */
    private suspend fun enrichInBackground() {
        runCatching {
            val settings = ServiceLocator.settings.settings.first()
            val total = ServiceLocator.database.trackDao().count()
            if (total <= 0) return@runCatching

            // 一次性迁移：「元信息」的标签识别现在是曲库后台补全的一部分，
            // 但老库里所有曲目的歌词索引标记都已经不为 0，补全不会再碰它们，
            // 元信息页就会一直是空的。这里整体清零一次，让后台按新流程重跑。
            if (settings.metaScanEpoch < com.audiometa.app.data.prefs.SettingsStore.META_SCAN_EPOCH) {
                ServiceLocator.tracks.resetLyricsIndex()
                ServiceLocator.settings.setMetaScanEpoch(
                    com.audiometa.app.data.prefs.SettingsStore.META_SCAN_EPOCH
                )
                AppLog.i(TAG, "「元信息」标签识别已升级，曲目索引将整体重建一次")
            }

            val pending = ServiceLocator.tracks.needingLyricsIndex()
            if (pending.isEmpty()) {
                val targets = ServiceLocator.tracks.silenceTargets()
                val missing = targets.any {
                    !com.audiometa.app.media.audio.SilenceProfileStore
                        .get(this@App, it.id).analyzed
                }
                if (!missing) return@runCatching
            }
            com.audiometa.app.media.scan.ScanService.enrich(this@App)
        }.onFailure {
            AppLog.d(TAG, "后台补全未能开始（不影响使用，下次打开会继续）：" + it.shortDescription())
        }
    }

    private companion object {
        const val TAG = "App"

        /** IO 线程的一次性工作，不需要取消（进程结束就没了）。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun Throwable.shortDescription(): String =
            javaClass.simpleName + (message?.takeIf { it.isNotBlank() }?.let { "：" + it } ?: "")
    }
}
