package com.audiometa.app.media.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.audiometa.app.MainActivity
import com.audiometa.app.R
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.ScanProgress
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ServiceLocator.scanner.cancel()
            ServiceLocator.indexer.cancel()
            ServiceLocator.silenceIndexer.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 只跑后台补全（不重新遍历目录）：App 启动时和搜索页都会用到。
        if (intent?.action == ACTION_ENRICH) {
            startForeground(NOTIFICATION_ID, buildNotification("正在后台补全", 0, 0))
            job?.cancel()
            // silence 默认 true；「设置 - 音频库 - 重新扫描」传 false，
            // 那个入口不动静音档案（只在缓存页单独刷新）。
            val withSilence = intent?.getBooleanExtra(EXTRA_SILENCE, true) ?: true
            job = scope.launch {
                // 同扫描分支：这个协程没有父协程，异常逃出去就是闪退。
                // 补全本来就是增量的，这次失败下次打开应用会接着跑，
                // 没有任何理由为它把应用带走。
                try {
                    runEnrichment(withSilence)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.e(TAG, "后台补全失败", t)
                    ServiceLocator.scanner.progress.value = ServiceLocator.scanner.progress.value.copy(
                        finished = true,
                        running = false,
                        message = "后台补全失败：" + t.shortDescription()
                    )
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        /*
         * 前台服务通知。
         *
         * **这一步可能会抛。** 安卓 12+ 对前台服务卡得很紧：从后台启动、
         * 没在 5 秒内挂上通知、通知渠道被系统限制……都会抛
         * RemoteServiceException，而异常从 onStartCommand 抛出去就是进程级崩溃，
         * 用户看到的是"点了一下重新扫描，应用直接没了"。
         *
         * 兜住它并把原因写进扫描进度 —— 「音乐库」页就在按钮下面显示这个字段，
         * 至少让用户知道为什么扫不了，而不是"点了没反应"。
         */
        val notificationOk = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("准备扫描", 0, 0))
        }.onFailure { error ->
            AppLog.e(TAG, "前台服务通知挂不上", error)
            ServiceLocator.scanner.progress.value = ScanProgress(
                finished = true,
                running = false,
                message = "扫描无法启动：系统不允许前台服务（" +
                    (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName) +
                    "）。检查通知权限与省电限制后重试"
            )
        }.isSuccess
        if (!notificationOk) {
            stopSelf()
            return START_NOT_STICKY
        }
        val roots = intent?.getStringArrayListExtra(EXTRA_ROOTS).orEmpty()
        val formats = intent?.getStringArrayListExtra(EXTRA_FORMATS)?.toSet().orEmpty()
        val excluded = intent?.getStringArrayListExtra(EXTRA_EXCLUDED)?.toSet().orEmpty()
        val minDuration = intent?.getLongExtra(EXTRA_MIN_DURATION, 0L) ?: 0L
        val excludeSubdirs = intent?.getBooleanExtra(EXTRA_EXCLUDE_SUBDIRS, false) ?: false
        job?.cancel()
        job = scope.launch {
            /*
             * 内置的"默认排除"。
             *
             * 用户要求：像 …/Music/Recordings 这类目录**只在首次扫描时**自动排除，
             * 扫到就默认勾上排除；用户手动取消勾选之后，以后不再自动排除。
             * 所以这里看的是一个一次性标记，跑过就不再跑。
             */
            /*
             * 曲库来源二选一（用户要求）：
             *   FOLDERS      用「音乐库」里手动添加的固定目录；
             *   MEDIA_STORE  用 Android 媒体库（系统扫出来的音频目录）。
             * 选媒体库时**完全忽略**用户加的目录，避免两种来源混在一起。
             */
            val settingsNow = ServiceLocator.settings.settings.first()
            val useMediaStore = settingsNow.librarySource ==
                com.audiometa.app.data.prefs.SettingsStore.SOURCE_MEDIA_STORE
            val mediaStore = if (useMediaStore) {
                MediaStoreSource.audioDirectories(this@ScanService)
            } else {
                MediaStoreSource.Result()
            }
            val effectiveRoots = if (useMediaStore) mediaStore.dirs else roots
            if (effectiveRoots.isEmpty()) {
                /*
                 * 没有可扫的目录：直接结束，别开一个啥也不干的扫描。
                 *
                 * 要把原因**显示出来**：读媒体库失败（没权限、查询被拒）以前只在
                 * 日志里写一行，界面上看到的是"点了重新扫描什么都没发生"，
                 * 用户会当成软件坏了。写进扫描进度，设置页那一块就显示出来了。
                 */
                val reason = when {
                    mediaStore.problem.isNotBlank() -> mediaStore.problem
                    useMediaStore -> "Android 媒体库里没有音频，取消本次扫描"
                    else -> "还没有添加音乐目录，取消本次扫描"
                }
                AppLog.w(TAG, reason)
                ServiceLocator.scanner.progress.value = ScanProgress(
                    finished = true,
                    message = reason
                )
                stopSelf()
                return@launch
            }

            /*
             * 首次扫描的"内置排除"。
             *
             * 这一步会**写设置**（DataStore），而磁盘写是会失败的 ——
             * 存储满、被别的进程占着、DataStore 文件损坏，都会抛 IOException。
             * 以前它裸在扫描任务里，抛出去就是整个应用崩掉：用户看到的
             * 就是"点了一下重新扫描，应用直接闪退"。所以它也只是"锦上添花"
             * 的功能：套不上就套不上，扫描照跑。
             */
            val effectiveExcluded = runCatching { applyBuiltInAutoExclude(effectiveRoots, excluded) }
                .onFailure { AppLog.w(TAG, "内置默认排除未能套用（继续扫描）：" + it.shortDescription()) }
                .getOrDefault(excluded)

            val progressJob = scope.launch {
                ServiceLocator.scanner.progress.collectLatest { p ->
                    notify(
                        buildNotification(
                            p.message.ifBlank { p.currentPath },
                            p.foundAudio,
                            p.scannedFiles
                        )
                    )
                }
            }
            AppLog.i(
                TAG,
                "开始扫描：目录 " + effectiveRoots.size + " 个、格式 " + formats.size +
                    " 种、排除 " + effectiveExcluded.size + " 个、时长下限 " + minDuration + "ms" +
                    "、只看第一层 " + (if (excludeSubdirs) "是" else "否")
            )
            try {
                /*
                 * 遍历 + 读标签。**这里必须兜住所有异常。**
                 *
                 * 本协程跑在 Service 自己的 CoroutineScope 里，没有父协程，
                 * 异常逃出去就是进程级崩溃（Android 上未捕获异常 = 闪退）。
                 * 扫描里可能抛的东西很多：单个文件的解析器抽风、数据库写失败、
                 * 查询踩到限制、目录在遍历中途被拔掉……这些都不该让用户丢掉整个应用，
                 * 更不该留下"卡在扫描中"再也退不出来的状态。
                 */
                ServiceLocator.scanner.scan(
                    effectiveRoots, formats, effectiveExcluded, minDuration, excludeSubdirs
                )
                AppLog.i(TAG, "扫描结束：" + ServiceLocator.scanner.progress.value.message)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 用户点了取消或又发起了一次扫描：正常退出，不算失败。
                AppLog.i(TAG, "本次扫描被取消")
                throw cancelled
            } catch (fatal: Throwable) {
                val why = fatal.shortDescription()
                AppLog.e(TAG, "扫描失败", fatal)
                // 用 scan() 已经写进去的那一份打底：它带着已经扫了多少个文件、
                // 找到了多少首 —— 崩溃现场的信息比一个干净的空对象有用得多。
                ServiceLocator.scanner.progress.value = ServiceLocator.scanner.progress.value.copy(
                    running = false,
                    finished = true,
                    message = "扫描失败：" + why
                )
            } finally {
                // 通知收集器到这里就没用了：后面只剩收尾，进度不会再变。
                // 收尾**之后**还会再 stopForeground 一次，见下面。
                progressJob.cancel()
            }

            /*
             * 扫完清一次孤立文件（用户要求）：删掉的目录留下的缩略图、
             * 元信息图片、缓存行不该一直堆着。
             *
             * 它自己每一步都兜了异常，这里再兜一层是为了"什么都不漏"：
             * 这一步失败绝不能把应用带走。**取消**要照常抛出去 ——
             * 用户点了取消或者又发起了新扫描时，这里得停下来。
             */
            runCatching { ServiceLocator.orphanCleaner.clean(this@ScanService) }
                .onFailure {
                    AppLog.w(TAG, "孤立文件清理失败：" + it.shortDescription())
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }

            // 扫描一结束就进后台补全。列表这时候已经能用了，
            // 歌词/曲风在用户翻列表的时候慢慢补齐。
            //
            // **静音档案不在这里做**（用户明确要求）：
            // runEnrichment(false) 只补歌词 —— "设置 - 重新扫描"这个入口
            // 不包括刷新静音档案。静音档案只增量补充，入口在
            // 「设置 - 关于 - 缓存 - 开头静音档案 - 刷新」。
            //
            // 补全失败不影响扫描结果（索引已经落库了），所以只写进日志 +
            // 扫描进度里带一句，不往外抛。
            try {
                runEnrichment(withSilence = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.e(TAG, "后台补全未能开始", t)
                ServiceLocator.scanner.progress.value = ServiceLocator.scanner.progress.value.copy(
                    message = ServiceLocator.scanner.progress.value.message +
                        "（后面那步后台补全没能启动：" + t.shortDescription() + "）"
                )
            } finally {
                // 无论成功、失败还是被取消，前台通知都要收掉 ——
                // 漏掉这一句会留下一条永远停在"正在扫描音频"的通知，
                // 而且 Android 会一直把进程按"前台服务"养着。
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * 两段补全：先歌词（搜索要用），再静音档案（播放要用）。
     *
     * 依次跑而不是并发：并发会让磁盘和硬件解码器互相抢，
     * 而且歌词先完成对用户的价值更高。
     */
    /**
     * 后台补全：歌词解析状态 + 开头静音档案。
     *
     * @param withSilence false 时跳过静音档案那一段。
     *   「设置 - 音频库 - 重新扫描」走的就是这条路 —— 用户明确要求
     *   那个入口不包括刷新静音档案，静音只在缓存页单独刷。
     */
    private suspend fun runEnrichment(withSilence: Boolean = true) {
        val lyricsJob = scope.launch {
            ServiceLocator.indexer.progress.collectLatest { p ->
                if (p.running) {
                    notify(
                        buildNotification(
                            "补全歌词 " + p.done + "/" + p.total,
                            p.done,
                            p.total
                        )
                    )
                }
            }
        }
        ServiceLocator.indexer.refresh()
        lyricsJob.cancel()

        if (!withSilence) {
            AppLog.d(TAG, "本次补全跳过静音档案（由缓存页单独刷新）")
            return
        }

        val paths = ServiceLocator.tracks.idPathPairs()
        val targets = ServiceLocator.tracks.silenceTargets().map {
            com.audiometa.app.media.audio.SilenceTarget(it.id, it.path, it.title)
        }
        val silenceJob = scope.launch {
            ServiceLocator.silenceIndexer.progress.collectLatest { p ->
                if (p.running) {
                    notify(
                        buildNotification(
                            "预计算开头静音 " + p.done + "/" + p.total,
                            p.done,
                            p.total
                        )
                    )
                }
            }
        }
        // refresh 本身是增量的：只算库里还没有档案的那些，已有的不动。
        ServiceLocator.silenceIndexer.refresh(targets)
        // 曲库变小了，顺手清掉已经没用的档案。档案按 trackId 存，所以传 id 集合。
        ServiceLocator.silenceIndexer.prune(paths.map { it.id }.toSet())
        silenceJob.cancel()
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }.onFailure { AppLog.d(TAG, "刷新扫描通知失败：" + it.shortDescription()) }
    }

    private fun buildNotification(text: String, found: Int, scanned: Int): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在扫描音频")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText("已发现 $found / 已查看 $scanned\n$text"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.scan_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.scan_channel_desc)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScanService"
        const val CHANNEL_ID = "audiometa_scan"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.audiometa.app.SCAN_CANCEL"
        const val ACTION_ENRICH = "com.audiometa.app.ENRICH"
        const val EXTRA_ROOTS = "roots"
        const val EXTRA_FORMATS = "formats"
        const val EXTRA_EXCLUDED = "excluded"
        const val EXTRA_MIN_DURATION = "minDuration"
        const val EXTRA_EXCLUDE_SUBDIRS = "excludeSubdirs"

        /** 是否顺带补静音档案。默认 true；设置里的重新扫描传 false。 */
        const val EXTRA_SILENCE = "silence"

        fun start(context: Context, roots: List<String>, formats: Set<String>, excluded: Set<String>, minDuration: Long, excludeSubdirs: Boolean) {
            val intent = Intent(context, ScanService::class.java).apply {
                putStringArrayListExtra(EXTRA_ROOTS, ArrayList(roots))
                putStringArrayListExtra(EXTRA_FORMATS, ArrayList(formats))
                putStringArrayListExtra(EXTRA_EXCLUDED, ArrayList(excluded))
                putExtra(EXTRA_MIN_DURATION, minDuration)
                putExtra(EXTRA_EXCLUDE_SUBDIRS, excludeSubdirs)
            }
            context.startForegroundService(intent)
        }

        /**
         * 首次扫描时套用 APK 内置的默认排除目录。
         *
         * 规则（用户指定）：
         * - 按**文件夹名**匹配（`Recordings`、`CallRecord`…），命中就自动勾上排除；
         * - **只在首次扫描时做一次**，跑过就把标记置上，以后不再自动排除；
         * - 用户手动取消勾选过的路径记在 `autoExcludeKeep` 里，永不自动勾。
         *
         * @return 加上自动排除之后的排除集合。
         */
        private suspend fun applyBuiltInAutoExclude(
            roots: List<String>,
            current: Set<String>
        ): Set<String> {
            val settings = ServiceLocator.settings
            val snapshot = settings.settings.first()
            if (snapshot.autoExcludeDone) return current

            val names = com.audiometa.app.data.prefs.SettingsStore.BUILT_IN_EXCLUDE_NAMES
            val found = mutableSetOf<String>()
            // 只往下找有限几层：这些目录都在音乐目录的浅层，不必把整棵树走完。
            roots.forEach { root -> collectAutoExclude(File(root), names, snapshot.autoExcludeKeep, found, 0) }

            settings.setAutoExcludeDone(true)
            if (found.isEmpty()) return current
            val merged = current + found
            settings.setExcluded(merged)
            AppLog.i(TAG, "首次扫描自动排除 " + found.size + " 个内置目录：" + found.joinToString("、"))
            return merged
        }

        private fun collectAutoExclude(
            dir: File,
            names: Set<String>,
            keep: Set<String>,
            out: MutableSet<String>,
            depth: Int
        ) {
            if (depth > 4) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            children.forEach { child ->
                if (!child.isDirectory) return@forEach
                val path = child.absolutePath
                if (child.name in names) {
                    // 用户取消过勾选的不再自动排除。
                    if (path !in keep) out += path
                    // 命中的整棵子树都不用再往下找了。
                    return@forEach
                }
                collectAutoExclude(child, names, keep, out, depth + 1)
            }
        }

        /**
         * 只跑后台补全，不重新遍历目录。
         *
         * @param silence 是否顺带增量补充开头静音档案。
         *   默认 true（应用启动、补全流程需要它）；
         *   「设置 - 音频库 - 重新扫描」传 false —— 用户明确要求那个入口
         *   **不动静音档案**，静音档案只在「设置 - 关于 - 缓存」里单独刷新。
         */
        fun enrich(context: Context, silence: Boolean = true) {
            if (ServiceLocator.indexer.isRunning || ServiceLocator.silenceIndexer.isRunning) return
            val intent = Intent(context, ScanService::class.java)
                .setAction(ACTION_ENRICH)
                .putExtra(EXTRA_SILENCE, silence)
            // 从后台（Application.onCreate 之类）拉起前台服务时，系统可能直接拒绝，
            // 抛 ForegroundServiceStartNotAllowedException。那不是错误状态：
            // 补全是增量的，下次正常打开应用会接着跑。
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                AppLog.d(TAG, "后台补全本次未能启动：" + (t.message ?: t.javaClass.simpleName))
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ScanService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }
    }
}