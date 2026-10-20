package com.audiometa.app.domain.usecase

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
import com.audiometa.app.data.db.ArtistEntity
import com.audiometa.app.data.db.ScrapeLogEntity
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.FallbackLyrics
import com.audiometa.app.util.StoragePaths
import com.audiometa.app.domain.model.LyricsItem
import com.audiometa.app.domain.model.OverwritePolicy
import com.audiometa.app.domain.model.PriorityKey
import com.audiometa.app.domain.model.ScrapeCapability
import com.audiometa.app.domain.model.ScrapeProgress
import com.audiometa.app.domain.model.TrackMeta
import com.audiometa.app.domain.model.lacksBasics
import com.audiometa.app.media.image.ImageEmbedder
import com.audiometa.app.media.image.ThumbnailCache
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.plugin.model.InstalledPlugin
import com.audiometa.app.plugin.model.PluginFunction
import com.audiometa.app.plugin.runtime.PluginException
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private enum class ScrapeOutcome { SUCCESS, SKIPPED, FAILED }

/** 一个源对一次能力查询的完整结果，用于并发收集后按优先级挑选。 */
private data class SourceHit(
    val plugin: InstalledPlugin,
    val array: JSONArray?,
    val error: Throwable?,
    val startedAt: Long
)

/** 一个源给出的元信息，保留来源以便按字段优先级合并。 */
private data class MetaHit(
    val plugin: InstalledPlugin,
    val meta: TrackMeta
)

/**
 * 一位艺术家最终用哪张图。
 *
 * [sources] 记录是不是真的从插件搜到的（[fromPlugin]），
 * 以及是否已经写进过文件，用来给日志和跳过判断提供依据。
 */
private data class ArtistImage(
    val bytes: ByteArray?,
    val mime: String,
    val plugin: InstalledPlugin?,
    val fromPlugin: Boolean,
    val note: String
)

/**
 * 用户指定的艺术家图地址下载结果。
 *
 * [bytes] 非空时 [url] 是**真正下载成功**的那个地址（钉进去的地址失败时会退到同一候选的其它地址，
 * 日志里必须记成实际用的那个，否则排查时会以为写进去的就是点的那张）。
 * [bytes] 为空时 [failure] 逐个说明每个地址为什么失败。
 */
private data class PinnedDownload(
    val bytes: ByteArray?,
    val url: String,
    val failure: String
)

class ScrapeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 用户确认过的计划："能力|插件" → 候选 JSON。为空表示按匹配分数自动挑。 */
    private var forcedPlan: Map<String, String> = emptyMap()

    /**
     * 用户选中的那一条歌词（原样 JSON）。
     *
     * 歌词不走 [forcedPlan]：那条路是"拿候选再去问插件、再解析"，
     * 而歌词在挑选界面里**正文已经拿到了**，直接写就行，不必再问一次源。
     */
    private var forcedLyrics: String? = null

    /**
     * 本次任务是否忽略"文件里已有内容"的判断。
     *
     * 详情页的三个手动刮削入口会置 true：用户点了入口就是要重写，
     * 不该因为文件里已有艺术家图 / 元信息齐全就被判成"已存在"跳过。
     */
    private var forceOverwrite: Boolean = false

    /**
     * 本次任务里，用户**逐项**指定要"覆盖"的能力。
     *
     * 用户在刮削窗口里把某一项点成「关闭 / 补全 / 覆盖」，这个集合是
     * **本次窗口的临时状态**（默认空 = 全部只补缺）：
     * - 空集合 → 文件里已有的内容不动，只补缺的字段（[OverwritePolicy.FILL_MISSING]）；
     * - 能力在集合里 → 这一项无条件重写（[OverwritePolicy.FORCE]）。
     *
     * 以前这里读的是持久化设置里的 `overwriteCapabilities`，而那个设置
     * 由刮削窗口写、又被别的入口读，于是"上次点过覆盖"会一直生效。用户要求
     * 每次打开窗口都默认关闭、必须手动指定，所以改成随这次意图一起传进来；
     * 那个持久化字段本身也因为没有读取方而整个删掉了。
     */
    private var runOverwrite: Set<ScrapeCapability> = emptySet()

    /**
     * 本次任务里「艺术家 → 该艺术家的曲目」。
     *
     * 艺术家图按艺术家刮削：一位艺术家只搜一次，结果写进这位艺术家的所有歌曲，
     * 保证同一艺术家的歌曲嵌入的是同一张图。
     */
    private val artistTracks = mutableMapOf<String, MutableList<TrackEntity>>()

    /** 本次任务里已经解析过的艺术家图，避免同一位艺术家重复搜、重复下载。 */
    private val artistImages = mutableMapOf<String, ArtistImage>()

    /**
     * 被"同艺术家的刮削"带进来的合作曲 → 把它带进来的那位艺术家名。
     *
     * 用户的歌里 artist 字段常写成 `A/B`、`A feat. B`：这些歌属于艺术家 A，
     * 但拿整串去搜图是搜不到的，而这次刮的就是 A 的图 —— 所以找图时用 A 这个名字。
     */
    private val artistTargets = mutableMapOf<Long, String>()

    /** 一位艺术家一把锁：并发处理同一艺术家的多首歌时只解析一次。 */
    private val artistLocks = mutableMapOf<String, Mutex>()

    private fun parsePlan(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            val map = mutableMapOf<String, String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val capability = item.optString("c")
                val pluginId = item.optString("p")
                val json = item.optString("j")
                if (capability.isNotBlank() && pluginId.isNotBlank() && json.isNotBlank()) {
                    map[capability + "|" + pluginId] = json
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            progress.value = progress.value.copy(message = "正在取消", cancelled = true)
            return START_NOT_STICKY
        }
        val ids = intent?.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        val capabilities = intent?.getStringArrayListExtra(EXTRA_CAPABILITIES)
            ?.mapNotNull { ScrapeCapability.fromKey(it) }?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ScrapeCapability.entries.toSet()
        forcedPlan = parsePlan(intent?.getStringExtra(EXTRA_PLAN))
        forcedLyrics = intent?.getStringExtra(EXTRA_LYRICS)
        forceOverwrite = intent?.getBooleanExtra(EXTRA_FORCE_OVERWRITE, false) ?: false
        runOverwrite = intent?.getStringArrayListExtra(EXTRA_OVERWRITE)
            ?.mapNotNull { ScrapeCapability.fromKey(it) }?.toSet().orEmpty()
        startForeground(NOTIFICATION_ID, notification("准备刮削", 0, ids.size.coerceAtLeast(1)))
        job?.cancel()
        cancelled.set(false)
        job = scope.launch {
            run(ids, capabilities)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun run(ids: List<Long>, capabilities: Set<ScrapeCapability>) {
        // 用户挑好的歌词：正文已经在手上，直接写，不走搜索。
        forcedLyrics?.takeIf { it.isNotBlank() }?.let { payload ->
            writePickedLyrics(ids, payload)
            return
        }
        val locator = ServiceLocator
        val settings = locator.settings.settings.first()
        val selected = if (ids.isEmpty()) emptyList() else locator.tracks.getByIds(ids)
        val selectedIds = selected.mapTo(HashSet()) { it.id }
        val plugins = locator.plugins.loadEnabled()

        /*
         * 艺术家图是"一位艺术家一张"，**默认写进这位艺术家名下的每一首歌**。
         *
         * 用户明确要求：不管是网络选图还是相册选图，都是给这位艺术家的
         * 全部歌曲嵌入艺术家图。所以点了「艺术家图片」之后，这次任务要处理的
         * 曲目要扩成"所有选中曲目的艺术家名下的全部曲目" —— 只在选中的那一首上
         * 写图，用户切到同一歌手的别的歌会看到头像时有时无。
         *
         * "属于这位艺术家"按**合作写法**判断（见 [artistFieldMatches]）：
         * artist 字段是 `A/B`、`A feat. B`、`A & B`、`A、B` 的歌也算 A 的歌。
         * 以前这里比的是整串相等，那些合作曲全被漏掉了。
         *
         * 其它能力（元信息）不受影响，仍然只处理用户选中的那些曲目。
         */
        // 上一次任务里"合作曲 → 把它带进来的艺术家"的记录不能留到这一次。
        // **必须在展开之前清**：下面会往 artistTargets 里写合作曲的归属。
        artistTargets.clear()
        val tracks = if (ScrapeCapability.ARTIST_IMAGE in capabilities && selected.isNotEmpty()) {
            val names = selected.map { primaryArtist(it.artist) }.filter { it.isNotEmpty() }.toSet()
            val expanded = if (names.isEmpty()) {
                selected
            } else {
                val extra = mutableListOf<TrackEntity>()
                locator.tracks.allOnce().forEach { track ->
                    // 每位艺术家只认**首位艺术家**（用户规则）：`A/B` 的歌只属于 A，
                    // 给 B 刮图不会碰它，同一个文件不会再被写入两张不同的图。
                    val hit = primaryArtist(track.artist).takeIf { it in names }
                    if (hit == null) {
                        // 对不上的只保留用户明确选中的（例如艺术家字段是空的那些）。
                        if (track.id in selectedIds) extra += track
                        return@forEach
                    }
                    // 合作曲是"被带进来"的：找图时用命中的那位艺术家名，
                    // 否则会拿 "A/B" 整串去搜，搜不到就等于这张图又漏了一首。
                    if (track.id !in selectedIds) artistTargets[track.id] = hit
                    extra += track
                }
                if (extra.size > selected.size) {
                    AppLog.i(
                        TAG,
                        "艺术家图按艺术家展开：选中 " + selected.size + " 首 → " +
                            "实际处理 " + extra.size + " 首（" + names.size + " 位艺术家）"
                    )
                }
                extra
            }
            expanded
        } else {
            selected
        }

        // 顺手清理旧日志，防止 scrape_logs 无限膨胀。
        runCatching {
            val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
            val removed = locator.logs.deleteOlderThan(cutoff)
            if (removed > 0) AppLog.d(TAG, "已清理 " + removed + " 条过期刮削日志")
        }

        progress.value = ScrapeProgress(running = true, total = tracks.size, message = "开始刮削")
        AppLog.i(
            TAG,
            "开始刮削：曲目 " + tracks.size +
                " 首，插件 " + plugins.size + " 个，能力 " + capabilities.joinToString("/") { it.label } +
                // 覆盖项单独记一行：写入范围出问题时，看日志就能分清是"没搜到"还是"被判成已存在"。
                if (runOverwrite.isEmpty()) "，全部只补缺"
                else "，覆盖 " + runOverwrite.joinToString("/") { it.label }
        )
        if (plugins.isEmpty()) {
            progress.value = ScrapeProgress(
                finished = true,
                finishedAt = System.currentTimeMillis(),
                total = tracks.size,
                message = "没有可用的插件源，请先到「设置 - 插件」订阅或导入"
            )
            AppLog.w(TAG, "没有可用的插件源")
            return
        }

        val done = AtomicInteger(0)
        val success = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val semaphore = Semaphore(settings.concurrency.coerceIn(1, MAX_CONCURRENCY))
        val locale = Locale.getDefault().toLanguageTag().ifBlank { "en" }

        // 艺术家图是「按艺术家」而不是「按歌曲」刮的：
        // 先把这次要处理的曲目按艺术家分组，同一位艺术家只搜一次，
        // 拿到的图写进这位艺术家的所有曲目，保证同一艺术家的歌曲嵌入的是同一张图。
        artistTracks.clear()
        artistImages.clear()
        val wantedArtistImage = ScrapeCapability.ARTIST_IMAGE in capabilities
        if (wantedArtistImage) {
            tracks.forEach { track ->
                val name = artistTargets[track.id] ?: primaryArtist(track.artist)
                if (name.isNotEmpty()) {
                    artistTracks.getOrPut(name) { mutableListOf() } += track
                }
            }
            AppLog.i(
                TAG,
                "艺术家图按艺术家刮削：本次涉及 " + artistTracks.size + " 位艺术家"
            )
        }

        kotlinx.coroutines.coroutineScope {
            tracks.map { track ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (cancelled.get()) return@withPermit
                        progress.value = progress.value.copy(
                            currentTitle = track.title,
                            message = "正在处理 " + track.title
                        )
                        val outcome = try {
                            /*
                             * 艺术家图和其他项一起勾选时，**只有用户明确选中的曲目**
                             * 才处理其他项；被"同一艺术家的全部歌曲"带进来的那些
                             * 只嵌入艺术家图，避免批量封面/歌词扩散到整位歌手。
                             */
                            val requested = if (
                                ScrapeCapability.ARTIST_IMAGE in capabilities &&
                                selectedIds.isNotEmpty() &&
                                track.id !in selectedIds
                            ) {
                                setOf(ScrapeCapability.ARTIST_IMAGE)
                            } else {
                                capabilities
                            }
                            scrapeOne(
                                trackId = track.id,
                                settings = settings,
                                plugins = plugins,
                                requested = requested,
                                locale = locale,
                                artistNameOverride = artistTargets[track.id]
                            )
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            AppLog.e(TAG, "刮削失败 " + track.title, error)
                            locator.logs.insert(
                                ScrapeLogEntity(
                                    trackId = track.id,
                                    sourceId = "",
                                    capability = "all",
                                    status = "error",
                                    message = error.message ?: "未知错误"
                                )
                            )
                            markFailed(track.id, track, error.message ?: "刮削失败")
                            ScrapeOutcome.FAILED
                        }
                        when (outcome) {
                            ScrapeOutcome.SUCCESS -> success.incrementAndGet()
                            ScrapeOutcome.SKIPPED -> skipped.incrementAndGet()
                            ScrapeOutcome.FAILED -> failed.incrementAndGet()
                        }
                        val current = done.incrementAndGet()
                        progress.value = progress.value.copy(
                            done = current,
                            success = success.get(),
                            failed = failed.get(),
                            skipped = skipped.get()
                        )
                        updateNotification(current, tracks.size)
                    }
                }
            }.forEach { it.join() }
        }

        progress.value = progress.value.copy(
            running = false,
            finished = true,
            // 完成时刻：详情页靠它重读文件（只盯 running 的 true→false 会漏，
            // 单曲刮削几百毫秒就跑完了，StateFlow 只保证最新值）。
            finishedAt = System.currentTimeMillis(),
            cancelled = cancelled.get(),
            done = done.get(),
            success = success.get(),
            failed = failed.get(),
            skipped = skipped.get(),
            message = if (cancelled.get()) {
                "刮削已取消"
            } else {
                "刮削完成：成功 " + success.get() + "，跳过 " + skipped.get() + "，失败 " + failed.get()
            }
        )
        AppLog.i(TAG, "刮削结束：成功 " + success.get() + " 跳过 " + skipped.get() + " 失败 " + failed.get())
    }

    /**
     * 一个能力对全部候选源的搜索，按源优先级把结果收齐。
     *
     * 上一次改动的关键点：所有能力的搜索在 [scrapeOne] 里并行发出，
     * 这个方法只负责「一个能力内部的多个源并发」。
     */
    private suspend fun runSearches(
        capability: ScrapeCapability,
        existing: TrackMeta,
        plugins: List<InstalledPlugin>,
        settings: AppSettings,
        fileName: String,
        locale: String
    ): List<SourceHit> {
        val locator = ServiceLocator
        val functionName = PluginFunction.of(capability) ?: return emptyList()
        val candidates = orderPlugins(capability, plugins, settings)
            .filter { capability in it.manifest.scrapeCapabilities() }
        if (candidates.isEmpty()) return emptyList()
        return kotlinx.coroutines.coroutineScope {
            candidates.map { plugin ->
                async(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    var array: JSONArray? = null
                    var error: Throwable? = null
                    try {
                        val request = buildRequest(capability, existing, plugin, fileName)
                        // 单个源限时。Apple Music 的接口在国内经常连不上，
                        // 没有超时的话它会把整次组合刮削拖到几十秒。
                        array = kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                            locator.runtime.asArray(
                                locator.runtime.invoke(plugin, functionName, request, locale)
                            )
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        // 超时不算致命错误，只记这一条，其它源照常。
                        error = java.io.IOException("超时（超过 " + (SOURCE_TIMEOUT_MS / 1000) + " 秒）")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (t: Throwable) {
                        error = t
                    }
                    SourceHit(plugin, array, error, startedAt)
                }
            }.awaitAll()
        }
    }

    private suspend fun markFailed(trackId: Long, track: TrackEntity, message: String) {
        ServiceLocator.tracks.updateAfterScrape(
            id = trackId,
            hasArtist = track.hasArtistImage,
            hasCover = track.hasCover,
            hasLyrics = track.hasLyrics,
            hasTranslation = track.hasTranslation,
            lyricsType = track.lyricsType,
            artistImagePath = track.artistImagePath,
            coverPath = track.coverPath,
            lyricsPath = track.lyricsPath,
            title = track.title,
            artist = track.artist,
            album = track.album,
            year = track.year,
            trackNo = track.trackNo,
            state = 2,
            message = message
        )
    }

    /**
     * 取一位艺术家的图片，整个任务里只做一次。
     *
     * 查找顺序：
     * 1. **用户明确选过** → 直接用选中的那一条里的图片地址（[pinnedArtistImage]）；
     * 2. 本次任务已经解析过 → 直接用（保证同一艺术家的所有歌曲写入同一张图）；
     * 3. 这位艺术家已入库的曲目里已经有艺术家图 → 直接读出来复用，
     *    这样「只给其中一首刮过图」的情况下，其余的歌曲会补上同一张；
     * 4. 都没命中 → 才去问插件源。
     *
     * 第 1 步是这次修的：以前这里**完全不看** [forcedPlan]，无论用户在结果列表里
     * 选了什么，都拿艺术家名重新搜一遍。于是"选中一张图 → 确认 → 文件没变"，
     * 而且如果文件里本来就有艺术家图、覆盖策略又是"只补缺"，会直接被判成"已存在"跳过。
     *
     * 每位艺术家一把锁：并发处理同一艺术家的多首歌时不会重复搜、重复下载。
     */
    private suspend fun artistImageFor(
        artistName: String,
        settings: AppSettings,
        plugins: List<InstalledPlugin>,
        locale: String,
        fileOf: (TrackEntity) -> String,
        /** 本地这首歌的专辑名，用来在同名艺术家之间分辨。 */
        localAlbum: String = "",
        /** 本地这首歌的歌名，同上。 */
        localTitle: String = ""
    ): ArtistImage {
        val lock = artistLocks.getOrPut(artistName) { Mutex() }
        return lock.withLock {
            artistImages[artistName]?.let { return@withLock it }

            // 1) 用户选过就用用户选的，不再自作主张。
            pinnedArtistImage(artistName)?.let { pinned ->
                artistImages[artistName] = pinned
                return@withLock pinned
            }
            // 走到这里只有两种可能：用户压根没指定（组合刮削的自动搜索，正常），
            // 或者**指定了但用不上**（地址没有 / 名字对不上 / 下载失败）。
            // 后者在 pinnedArtistImage 里已经逐条记了原因，这里再补一条汇总，
            // 让"用户挑的那张被忽略、正在自动搜索"在日志里一眼可见 ——
            // 以前这一步是静默的，用户只看到"我点的那张没写进去，写进去的是排名第一那张"。
            if (hasForcedSelection(ScrapeCapability.ARTIST_IMAGE)) {
                AppLog.w(TAG, "艺术家图：用户指定的候选没能用上，退回自动搜索（" + artistName + "）")
            }

            // 这次把「艺术家图片」点成「覆盖」（或从详情页手动进来）时重新搜一张；
            // 否则能复用已有的艺术家图就复用，省一次网络请求。
            if (ScrapeCapability.ARTIST_IMAGE !in runOverwrite && !forceOverwrite) {
                existingArtistImageBytes(artistName, fileOf)?.let { cached ->
                    val result = ArtistImage(
                        bytes = cached.first,
                        mime = cached.second,
                        plugin = null,
                        fromPlugin = false,
                        note = "复用已有的艺术家图"
                    )
                    artistImages[artistName] = result
                    return@withLock result
                }
            }

            val candidates = orderPlugins(ScrapeCapability.ARTIST_IMAGE, plugins, settings)
                .filter { ScrapeCapability.ARTIST_IMAGE in it.manifest.scrapeCapabilities() }
            if (candidates.isEmpty()) {
                val result = ArtistImage(null, "image/jpeg", null, false, "没有插件提供艺术家图片")
                artistImages[artistName] = result
                return@withLock result
            }

            var lastNote = "没有命中艺术家图"
            for (plugin in candidates) {
                if (cancelled.get()) break
                val startedAt = System.currentTimeMillis()
                val outcome = fetchArtistImage(plugin, artistName, locale, localAlbum, localTitle) ?: continue
                val (bytes, note) = outcome
                if (bytes != null) {
                    // 插件调用成功，记在插件 id 下，方便排查「是源的问题还是软件的问题」。
                    log(0L, plugin, ScrapeCapability.ARTIST_IMAGE, "ok", note, startedAt)
                    resetFail(plugin)
                    val result = ArtistImage(bytes, sniffMime(bytes), plugin, true, note)
                    artistImages[artistName] = result
                    return@withLock result
                }
                lastNote = note
                log(0L, plugin, ScrapeCapability.ARTIST_IMAGE, "miss", note, startedAt)
            }
            val result = ArtistImage(null, "image/jpeg", null, false, lastNote)
            artistImages[artistName] = result
            result
        }
    }

    /**
     * 用户明确指定的艺术家图。
     *
     * 从 [forcedPlan] 里取出这个能力的候选 JSON，按和自动搜索相同的字段规则解析出图片地址并下载 ——
     * 这样"手动指定"和"自动挑"用的是同一套解析，不会再出现"列表里看得到图、写进文件却是空的"。
     *
     * 地址**优先用 [PICKED_IMAGE_URL_KEY]**：那是 `ScrapePickerScreen` 把用户在网格里点的那张图
     * 钉进来的地址（见 `ScrapeCandidate.pinPickedImageUrl`），其次才是候选自带的
     * imageUrls / imageUrl …（见 [urlsOf]）。"看到哪张就写哪张"靠这个键保证。
     *
     * 任何一步不满足都返回 null，让它退回去走自动搜索，而不是把这次刮削整个作废。
     * 但**每一条 return null 都必须留下日志**：以前这里是静默的，
     * 用户看到的是"我明明点了一张，写进去的却是自动搜出来排名第一那个源的图"，
     * 而日志里一个字都没有，根本没法判断是挑选被丢了、还是地址下载失败。
     */
    private suspend fun pinnedArtistImage(artistName: String): ArtistImage? {
        val entry = forcedPlan.entries.firstOrNull {
            it.key.startsWith(ScrapeCapability.ARTIST_IMAGE.key + "|")
        } ?: run {
            // 用户没为这个能力指定过候选时也会走到这里（组合刮削就是），属于正常情况，只记 debug。
            AppLog.d(TAG, "没有指定艺术家图候选，按源优先级自动搜索")
            return null
        }
        val pluginId = entry.key.substringAfter('|')
        val plugin = runCatching {
            ServiceLocator.plugins.loadEnabled().firstOrNull { it.pluginId == pluginId }
        }.getOrNull()
        val item = runCatching { JSONObject(entry.value) }.getOrElse { error ->
            AppLog.w(
                TAG,
                "指定的艺术家图候选 JSON 解析失败，退回自动搜索：源=" + pluginId +
                    "，原因=" + (error.message ?: error.javaClass.simpleName)
            )
            return null
        }

        // 用户在图格里点的那张图（ScrapePickerScreen 钉进来的内部键）。留一份用于日志。
        val pickedUrl = item.optString(PICKED_IMAGE_URL_KEY).trim()

        // 艺术家图是"一位艺术家一张"。批量刮削时这一条候选只属于搜它的那位艺术家，
        // 不该糊到同批次别的艺术家头上——核对不上就当没有指定，走各自的自动搜索。
        //
        // ⚠ 这里用 [sameArtistText]（归一化去标点空格 + 互相包含），**不能**用严格相等：
        // 严格相等（trim + 忽略大小写）和自动搜索那条路（[artistNameMatches]）判定不一致，
        // 于是"候选名字只是多了个英文别名或句点"（`周杰伦 Jay Chou`、`周杰伦. `）这种
        // 明明点对了的候选会被丢掉，静默退回自动搜索 —— 写进文件的就是排名第一那个源的图。
        // 换成和自动搜索同一套判定后，"周杰伦"依旧不会被"群星"顶上，但不再误杀。
        val pinnedName = listOf("artist", "singer", "artistName", "name", "title")
            .firstNotNullOfOrNull { key ->
                item.optString(key).takeIf { it.isNotBlank() }
            }
            .orEmpty()
        if (pinnedName.isNotBlank() && !sameArtistText(pinnedName, artistName)) {
            AppLog.w(
                TAG,
                "指定的艺术家图候选名字对不上本地艺术家，退回自动搜索：本地「" + artistName +
                    "」，候选「" + pinnedName + "」，源=" + pluginId
            )
            return null
        }

        val urls = urlsOf(item)
        if (urls.isEmpty()) {
            // 候选里一个图片地址都没有（或字段名不认识）。把候选里**出现过的字段名**打出来——
            // 只打键名，不打全量 JSON（候选可能很大，值里还可能有正文）。
            // 有"疑似图片字段"却没用上时单独点出来，方便分清是插件换了字段名还是这条候选本来就没图。
            AppLog.w(
                TAG,
                "指定的艺术家图候选里没有图片地址，退回自动搜索：源=" + pluginId +
                    "，候选=" + pinnedName +
                    (if (pickedUrl.isNotBlank()) "，图格里点选的地址=" + pickedUrl else "，没有记录到点选地址") +
                    "，候选字段=[" + fieldNamesOf(item).joinToString(",") + "]" +
                    suspectImageFieldNote(item)
            )
            return null
        }
        val download = downloadPinned(urls)
        // 先取成局部 val：下面要用字节本身（smart cast 只对局部 val 稳）。
        val bytes = download.bytes
        if (bytes == null) {
            AppLog.w(
                TAG,
                "指定的艺术家图地址全部下载失败，退回自动搜索：源=" + pluginId +
                    "，共 " + urls.size + " 个地址（" + urls.joinToString(" | ") + "），失败原因=" + download.failure
            )
            return null
        }
        AppLog.i(
            TAG,
            "艺术家图使用用户指定的候选：" + artistName + "（" + download.url +
                "，地址来自图格中点选" +
                (if (pickedUrl.isNotBlank() && download.url != pickedUrl) "——点选的那个下载失败，退到同一候选的其它地址" else "") +
                "）"
        )
        return ArtistImage(
            bytes = bytes,
            mime = sniffMime(bytes),
            plugin = plugin,
            fromPlugin = true,
            note = "使用指定的艺术家图"
        )
    }

    /**
     * 依次下载用户指定的图片地址，返回"第一个成功的字节 + 实际使用的地址"或"全部失败的原因"。
     *
     * 单独写一个而不改 [downloadFirstAvailable]：那条路是自动搜索用的，只要知道成没成；
     * 这条路失败必须在日志里说清试了哪些地址、每一个为什么失败 ——
     * 否则"挑选被退回自动搜索"在日志里看不出任何痕迹。
     */
    private fun downloadPinned(urls: List<String>): PinnedDownload {
        val reasons = mutableListOf<String>()
        for (url in urls) {
            val result = runCatching { ServiceLocator.runtime.downloadBytes(url) }
            val bytes = result.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return PinnedDownload(bytes, url, "")
            val reason = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "内容为空"
            reasons += url + " → " + reason
        }
        return PinnedDownload(null, "", reasons.joinToString("；"))
    }

    /**
     * 候选里出现过的字段名（顶层 + `fields` 子对象的键，子对象加 `fields.` 前缀）。
     *
     * 排查"插件把图片地址换了字段名"时用：**只打键名、不打值** ——
     * 值可能是正文或很长的地址，全量 JSON 打进日志既看不清也撑日志。
     */
    private fun fieldNamesOf(item: JSONObject): List<String> {
        val out = mutableListOf<String>()
        item.keys().forEach { key ->
            if (key != "fields") out += key
        }
        item.optJSONObject("fields")?.keys()?.forEach { key -> out += "fields." + key }
        return out.sorted()
    }

    /**
     * "候选里带着疑似图片字段、但艺术家图解析没采用它们"的日志片段；没有就返回空串。
     *
     * 各源字段名不统一，插件换个名字（picUrl / image / cover / photo / img…）时，
     * 宿主这边只会表现为"没有图片地址"，又是一次看不出原因的静默退回。这里至少留一条线索。
     *
     * ⚠ 只是记日志，**不**把它们加进 [urlsOf]：`picUrl` 按插件格式约定是「专辑封面」，
     * 拿它兜底会把封面写进艺术家图字段（这个 bug 修过一次，见 [urlsOf] 里的注释）。
     * 同理也不该把它钉进候选 —— 网格里显示的本来就不是它。
     */
    private fun suspectImageFieldNote(item: JSONObject): String {
        val fields = item.optJSONObject("fields")
        val suspects = SUSPECT_IMAGE_KEYS.filter { key ->
            item.optString(key).isNotBlank() || fields?.optString(key)?.isNotBlank() == true
        }
        if (suspects.isEmpty()) return ""
        return "，注意：候选里有疑似图片字段 [" + suspects.joinToString(",") + "] 但没被采用"
    }

    /**
     * 问一个插件要这位艺术家的图。
     *
     * 关键词只用艺术家名：拿「歌名 + 歌手」去搜歌手图，很容易被歌名带偏，
     * 搜出来的是别的歌手。这一点和 Lyrico 客户端「按艺术家搜」的做法一致。
     *
     * [localAlbum] / [localTitle] 用来在**同名艺术家**之间分辨：
     * 关键词还是只有歌手名（不污染搜索），但候选排序时，
     * 专辑吻合的、带本地这首歌的候选会被排在前面。
     */
    private suspend fun fetchArtistImage(
        plugin: InstalledPlugin,
        targetArtist: String,
        locale: String,
        localAlbum: String = "",
        localTitle: String = ""
    ): Pair<ByteArray?, String>? {
        val locator = ServiceLocator
        val request = buildArtistRequest(plugin, targetArtist)
        return try {
            val array = kotlinx.coroutines.withTimeout(SOURCE_TIMEOUT_MS) {
                locator.runtime.asArray(
                    locator.runtime.invoke(plugin, PluginFunction.SEARCH_ARTIST_IMAGES, request, locale)
                )
            }
            if (array.length() == 0) {
                null to "结果为空"
            } else {
                val urls = pickArtistImageUrls(array, targetArtist, localAlbum, localTitle)
                if (urls.isEmpty()) {
                    null to "结果里没有艺术家图地址"
                } else {
                    val bytes = downloadFirstAvailable(locator.runtime, urls)
                    if (bytes == null) null to "所有候选图片都下载失败" else bytes to "命中 " + targetArtist
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            null to "超时（超过 " + (SOURCE_TIMEOUT_MS / 1000) + " 秒）"
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            AppLog.w(TAG, plugin.name + " 取艺术家图失败：" + message)
            bumpFail(plugin)
            null to message
        }
    }

    /** 艺术家图请求：keyword / title / artist 全部只填艺术家名。 */
    private fun buildArtistRequest(plugin: InstalledPlugin, artistName: String): JSONObject {
        val config = runCatching { JSONObject(plugin.configJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val song = JSONObject()
            .put("id", "local-artist")
            .put("title", artistName)
            .put("artist", artistName)
            .put("album", "")
            .put("duration", 0)
        return JSONObject()
            .put("keyword", artistName)
            .put("title", artistName)
            .put("artist", artistName)
            .put("album", "")
            .put("page", 1)
            // 歌手图靠歌曲线索反查，多取一些才够筛出正确的歌手。
            .put("pageSize", ARTIST_SEARCH_PAGE_SIZE)
            .put("separator", "/")
            .put("config", config)
            .put("song", song)
    }

    /**
     * 这位艺术家已有的图。
     *
     * 先看数据库里登记过的曲目（包含不在本次任务里的），再从文件里把图读出来：
     * 外置图直接读文件，内嵌图读标签里的艺术家图。
     */
    private suspend fun existingArtistImageBytes(
        targetArtist: String,
        fileOf: (TrackEntity) -> String
    ): Pair<ByteArray, String>? {
        val locator = ServiceLocator
        val candidates = runCatching { locator.tracks.observeAll().first() }
            .getOrDefault(emptyList())
            // 合作曲（"A/B"）名下的图同样是这位艺术家的图，别漏掉。
            .filter { artistFieldMatches(it.artist, targetArtist) }
        for (track in candidates) {
            if (!track.hasArtistImage && track.artistImagePath.isNullOrBlank()) continue
            val candidateFile = File(fileOf(track))
            if (!candidateFile.isFile) continue
            val source = com.audiometa.app.media.image.ArtworkResolver
                .resolve(candidateFile, com.audiometa.app.media.image.ArtworkKind.ARTIST) ?: continue
            val candidateBytes = when (source) {
                is com.audiometa.app.media.image.ArtworkResolver.Source.External ->
                    runCatching { File(source.path).readBytes() }.getOrNull()
                is com.audiometa.app.media.image.ArtworkResolver.Source.Bytes -> source.data
            }
            if (candidateBytes != null && candidateBytes.isNotEmpty()) {
                return candidateBytes to com.audiometa.app.media.image.ArtworkResolver.mimeOf(candidateBytes)
            }
        }
        return null
    }

    private suspend fun scrapeOne(
        trackId: Long,
        settings: AppSettings,
        plugins: List<InstalledPlugin>,
        requested: Set<ScrapeCapability>,
        locale: String,
        /**
         * 艺术家图用的名字。
         *
         * 只给"被同艺术家的刮削带进来的合作曲"填（见 [artistTargets]）：
         * 它自己的字段可能是 `A/B`，但这次刮的是 A 的图。
         */
        artistNameOverride: String? = null
    ): ScrapeOutcome {
        val locator = ServiceLocator
        val track = locator.tracks.getById(trackId) ?: return ScrapeOutcome.FAILED
        val file = File(track.path)
        if (!file.exists()) {
            markFailed(track.id, track, "文件不存在")
            return ScrapeOutcome.FAILED
        }
        val existing = TagReader().read(file)

        // 【死锁】音频内三项基础数据一项都没有：没有任何可用的搜索关键词，
        // 艺术家图、元信息都**不发起网络搜索**，直接记为跳过。
        // 想补数据请先在详情页「元信息」里手动编辑（那里才能改这三项）。
        if (existing.lacksBasics()) {
            AppLog.i(TAG, "跳过 " + file.name + "：音频内没有标题/艺术家/专辑基础数据，不进行网络搜索")
            locator.tracks.updateAfterScrape(
                track.id, existing.hasArtistImage, existing.hasCover, existing.hasLyrics,
                existing.hasTranslation, existing.lyricsType,
                track.artistImagePath, track.coverPath, track.lyricsPath,
                existing.title, existing.artist, existing.album, existing.year, existing.trackNo,
                1, "音频内没有标题/艺术家/专辑基础数据，已跳过网络搜索（请在详情页手动补全）"
            )
            return ScrapeOutcome.SKIPPED
        }

        // 匹配用的期望值：有标签用标签，没标签文件名会在 MusicMatch 里兜底。
        val want = TrackMatcher.Want(
            title = existing.title,
            artist = existing.artist,
            album = existing.album,
            fileName = file.name,
            durationMs = existing.durationMs
        )
        // 艺术家图的归属名：标签里的歌手优先，没标签时退回文件名推断。
        // 合作曲被同艺术家的刮削带进来时用带它进来的那位名字（见 [artistTargets]）。
        val artistName = artistNameOverride?.trim().orEmpty()
            .ifBlank { primaryArtist(existing.artist.ifBlank { track.artist }) }

        val wanted = requested.toMutableSet()

        var artistBytes: ByteArray? = null
        var artistMime = "image/jpeg"
        // 专辑图（封面）：这次搜到并要写进文件的那一张。
        var coverBytes: ByteArray? = null
        var coverMime = "image/jpeg"
        val metaHits = mutableListOf<MetaHit>()
        val notes = mutableListOf<String>()
        var obtainedAnything = false
        var attempted = false

        // 确定这次真正要跑的能力。
        // 封面和歌词的网络刮削已移除，这里只会有「艺术家图片」和「元信息」。
        val plan = ScrapeCapability.entries.filter { capability ->
            if (capability !in wanted) return@filter false
            if (shouldSkip(capability, existing, settings)) {
                notes += capability.label + "已存在"
                return@filter false
            }
            val available = orderPlugins(capability, plugins, settings)
                .any { capability in it.manifest.scrapeCapabilities() }
            if (!available) {
                notes += "没有插件提供" + capability.label
                return@filter false
            }
            true
        }

        // 两项能力并行发出：艺术家图（按艺术家搜一次）和元信息（多源并行搜）。
        if (plan.isNotEmpty()) attempted = true

        var lyricsTask: kotlinx.coroutines.Deferred<LyricsSearchResult?>? = null
        kotlinx.coroutines.coroutineScope {
            val artistTask = if (ScrapeCapability.ARTIST_IMAGE in plan) {
                async(Dispatchers.IO) {
                    artistImageFor(
                        artistName = artistName,
                        settings = settings,
                        plugins = plugins,
                        locale = locale,
                        fileOf = { it.path },
                        // 同名艺术家靠专辑和歌名分辨，把本地这两项传下去。
                        localAlbum = existing.album,
                        localTitle = existing.title
                    )
                }
            } else {
                null
            }
            val metaCapability = when {
                ScrapeCapability.TRACK_META in plan -> ScrapeCapability.TRACK_META
                // 详情页的「曲风」：只搜曲风、只写 genre 一个字段。
                ScrapeCapability.GENRE in plan -> ScrapeCapability.GENRE
                else -> null
            }
            val metaTask = if (metaCapability != null) {
                async(Dispatchers.IO) {
                    runSearches(metaCapability, existing, plugins, settings, file.name, locale)
                }
            } else {
                null
            }
            /*
             * 专辑图：插件的 `searchCovers` 返回的就是**带 picUrl 的歌曲列表**，
             * 所以这条任务和元信息走同一套搜索，只是拿到结果后取图而不是取字段。
             */
            val coverTask = if (ScrapeCapability.COVER in plan) {
                async(Dispatchers.IO) {
                    runSearches(ScrapeCapability.COVER, existing, plugins, settings, file.name, locale)
                }
            } else {
                null
            }

            /*
             * 歌词：批量刮削以前只把 LYRICS 列进 plan，却没有对应的搜索/写入任务，
             * 用户勾了歌词也什么都不发生。这里补上：三基项严格匹配后取分数最高的一条，
             * 选中的候选直接进最后的 embed。
             */
            lyricsTask = if (ScrapeCapability.LYRICS in plan) {
                async(Dispatchers.IO) {
                    runCatching { LyricsSearch().searchResult(track.id) }.getOrNull()
                }
            } else {
                null
            }

            // 艺术家图：整个任务里每位艺术家只解析一次，结果在同艺术家的歌之间共享。
            if (artistTask != null) {
                val shared = artistTask.await()
                if (shared.bytes != null) {
                    artistBytes = shared.bytes
                    artistMime = shared.mime
                    obtainedAnything = true
                    notes += shared.note
                    if (shared.plugin != null) resetFail(shared.plugin)
                } else {
                    notes += "艺术家图片未命中（" + shared.note + "）"
                }
            }

            // 专辑图：每个源挑出匹配度最高、且确实带图地址的那一条，
            // 再在这些候选里选分辨率最高的一张（和自动搜封面的取舍规则一致）。
            if (coverTask != null) {
                val hits = coverTask.await()
                val options = mutableListOf<CoverOption>()
                for (hit in hits) {
                    if (cancelled.get()) break
                    val plugin = hit.plugin
                    val failure = hit.error
                    if (failure != null) {
                        val message = failure.message ?: failure.javaClass.simpleName
                        log(track.id, plugin, ScrapeCapability.COVER, "error", message, hit.startedAt)
                        AppLog.w(TAG, plugin.name + " 执行 searchCovers 失败：" + message)
                        bumpFail(plugin)
                        continue
                    }
                    val raw = hit.array ?: continue
                    // 用户在结果网格里点了某一张时，这个源换成他点的那一条。
                    val forced = forcedPlan[ScrapeCapability.COVER.key + "|" + plugin.pluginId]
                    val array = if (forced != null) {
                        runCatching { JSONArray().put(JSONObject(forced)) }.getOrDefault(raw)
                    } else {
                        raw
                    }
                    if (array.length() == 0) {
                        log(track.id, plugin, ScrapeCapability.COVER, "miss", "没有结果", hit.startedAt)
                        continue
                    }
                    val best = coverCandidates(array, want).firstOrNull()
                    if (best == null) {
                        log(
                            track.id, plugin, ScrapeCapability.COVER, "miss",
                            "没有够像的封面（最高 " + percent(bestCoverScore(array, want)) + "）",
                            hit.startedAt
                        )
                        continue
                    }
                    options += CoverOption(plugin, best.first, best.second)
                    log(
                        track.id, plugin, ScrapeCapability.COVER, "ok",
                        "命中封面（" + percent(best.second) + "）",
                        hit.startedAt
                    )
                    resetFail(plugin)
                }
                val chosen = bestCoverByResolution(options)
                if (chosen != null) {
                    val bytes = downloadFirstAvailable(locator.runtime, listOf(chosen.url))
                    if (bytes != null && bytes.isNotEmpty()) {
                        coverBytes = bytes
                        coverMime = sniffMime(bytes)
                        obtainedAnything = true
                        notes += "专辑图 " + percent(chosen.score) + "（" + chosen.plugin.name + "）"
                    } else {
                        notes += "专辑图下载失败"
                    }
                } else if (options.isEmpty()) {
                    notes += "专辑图未命中"
                }
            }

            // 元信息 / 曲风：把所有源的结果收齐，再按字段挑内容更全的那个。
            //
            // 曲风（[ScrapeCapability.GENRE]）走的是**同一套搜索**，但只取 genre
            // 一个字段：`TrackMeta(genre = …)` 里别的字段都是空的，合并时自然
            // 不会覆盖文件里已有的日期/注释这类内容。
            val capabilityInUse = metaCapability
            if (metaTask != null && capabilityInUse != null) {
                val hits = metaTask.await()
                var matched = false
                for (hit in hits) {
                    if (cancelled.get()) break
                    val plugin = hit.plugin
                    val failure = hit.error
                    if (failure != null) {
                        val message = failure.message ?: failure.javaClass.simpleName
                        log(track.id, plugin, capabilityInUse, "error", message, hit.startedAt)
                        AppLog.w(TAG, plugin.name + " 执行 searchSongs 失败：" + message)
                        bumpFail(plugin)
                        continue
                    }
                    val raw = hit.array ?: continue
                    // 指定候选时，把这个源的结果换成用户选中的那一条。
                    val forced = forcedPlan[capabilityInUse.key + "|" + plugin.pluginId]
                    val array = if (forced != null) {
                        runCatching { JSONArray().put(JSONObject(forced)) }.getOrDefault(raw)
                    } else {
                        raw
                    }
                    if (array.length() == 0) {
                        log(track.id, plugin, capabilityInUse, "miss", "没有结果", hit.startedAt)
                        continue
                    }
                    // 取匹配度最高的那条。排序只看歌曲名 / 艺术家 / 专辑，
                    // 其它字段不参与打分。
                    val best = bestItem(array, want) ?: JSONObject()
                    var parsed = songToMeta(best, existing)
                    if (capabilityInUse == ScrapeCapability.GENRE) {
                        parsed = TrackMeta(genre = cleanGenre(parsed.genre))
                    }
                    if (parsed.hasAnyField()) {
                        metaHits += MetaHit(plugin, parsed)
                        matched = true
                        obtainedAnything = true
                        log(
                            track.id, plugin, capabilityInUse, "ok",
                            plugin.name + " 命中 " + parsed.fieldCount() + " 个字段",
                            hit.startedAt
                        )
                        resetFail(plugin)
                    } else {
                        log(track.id, plugin, capabilityInUse, "miss", "结果不可用", hit.startedAt)
                    }
                }
                if (!matched) notes += capabilityInUse.label + "未命中"
            }
        }

        // 元信息按字段合并：每个字段挑"内容更全"的那个源，并列时按源优先级。
        val mergedMeta = mergeMetaFromHits(metaHits, existing, settings)

        val lyricsItems = mutableListOf<LyricsItem>()
        val lyricsDeferred = lyricsTask
        if (lyricsDeferred != null) {
            val results = lyricsDeferred.await()
            val forcedLyricsPlan = forcedPlan.entries
                .firstOrNull { it.key.startsWith(ScrapeCapability.LYRICS.key + "|") }
                ?.value
            val picked = if (forcedLyricsPlan != null) {
                runCatching { JSONObject(forcedLyricsPlan) }.getOrNull()
            } else {
                val wantTitle = existing.title.ifBlank { track.title }
                val wantArtist = existing.artist.ifBlank { track.artist }
                val wantAlbum = existing.album.ifBlank { track.album }
                results?.candidates
                    ?.filter {
                        it.lyrics.isNotBlank() &&
                            strictTagMatch(it.title, it.artist, it.album, wantTitle, wantArtist, wantAlbum)
                    }
                    ?.maxByOrNull { it.score }
                    ?.let { candidate ->
                        JSONObject()
                            .put("lyrics", candidate.lyrics)
                            .put("translation", candidate.translation)
                            .put("type", candidate.type)
                    }
            }
            if (picked != null) {
                val lyrics = picked.optString("lyrics")
                if (lyrics.isNotBlank()) {
                    val type = picked.optString("type").ifBlank { "line" }
                    val translation = picked.optString("translation")
                    lyricsItems += LyricsItem(type = type, lang = "", translated = false, content = lyrics)
                    if (translation.isNotBlank() && settings.downloadTranslation) {
                        lyricsItems += LyricsItem(
                            type = type, lang = "", translated = true, content = translation
                        )
                    }
                    obtainedAnything = true
                    notes += "歌词已命中"
                }
            } else {
                notes += "歌词未命中"
            }
        }

        if (!obtainedAnything) {
            val summary = notes.joinToString("；").ifBlank { "没有可写入的数据" }
            val outcome = if (!attempted) ScrapeOutcome.SKIPPED else ScrapeOutcome.FAILED
            locator.tracks.updateAfterScrape(
                track.id, existing.hasArtistImage, existing.hasCover, existing.hasLyrics, existing.hasTranslation,
                existing.lyricsType, track.artistImagePath, track.coverPath, track.lyricsPath,
                existing.title, existing.artist, existing.album, existing.year, existing.trackNo,
                if (outcome == ScrapeOutcome.SKIPPED) 1 else 3,
                summary
            )
            return outcome
        }

        val embed = locator.embedder.embed(
            ImageEmbedder.EmbedRequest(
                file = file,
                artistBytes = artistBytes,
                artistMime = artistMime,
                // 专辑图：只有这次真的搜到了图才传，否则保持文件里原有的封面不动。
                coverBytes = coverBytes,
                coverMime = coverMime,
                lyrics = lyricsItems,
                meta = mergedMeta,
                existing = existing,
                mode = settings.effectiveWriteMode(file.extension),
                imageMode = settings.imageMode,
                imageQuality = settings.imageQuality,
                policy = writePolicy(settings),
                keepModTime = settings.keepModTime
            )
        )
        /*
         * 刚写完，专辑封面缩略图缓存要立刻失效：它的 key 绑的是音频文件本身，
         * 不重建的话用户选完候选回到详情页，看到的是旧封面。
         * 艺术家图不需要这一步 —— 它不落盘，走实时读取 + 内存 LRU。
         */
        runCatching { ThumbnailCache.rebuild(this) }

        val after = TagReader().read(file)

        /*
         * 写完之后核对一遍，把"到底写进去没有"说清楚。
         *
         * 之前这里完全没有校验：embed() 内部有若干条静默返回的路径
         * （格式不支持内嵌、策略判定"已存在"、图片为空…），返回一个
         * embedded=false 的空结果，界面上却依然显示"已写入"。
         * 用户看到的就是"点了一堆，文件一点没变"。
         * 现在把这些情况写成明确的文字，进日志也进详情页的标签状态。
         */
        val verified = buildList {
            if (coverBytes != null) {
                add(if (after.hasCover) "专辑图 已写入" else "专辑图 未写入（格式不支持内嵌封面）")
            }
            if (artistBytes != null) {
                if (after.hasArtistImage) add("艺术家图 已写入")
                else if (embed.sidecarArtist != null) add("艺术家图 已写外置文件")
                else add("艺术家图 未写入（格式不支持内嵌时看外置文件）")
            }
            if (lyricsItems.isNotEmpty()) {
                add(if (after.hasLyrics) "歌词 已写入" else "歌词 未写入（格式不支持内嵌歌词）")
            }
            if (mergedMeta?.hasAnyField() == true) {
                // 元信息按字段粒度写，对几个关键字段复核一次。
                val changed = listOf(
                    "日期" to (after.date.isNotBlank() && after.date != existing.date),
                    "流派" to (after.genre.isNotBlank() && after.genre != existing.genre),
                    "语言" to (after.language.isNotBlank() && after.language != existing.language),
                    "作曲" to (after.composer.isNotBlank() && after.composer != existing.composer),
                    "作词" to (after.lyricist.isNotBlank() && after.lyricist != existing.lyricist),
                    "版权" to (after.copyright.isNotBlank() && after.copyright != existing.copyright),
                    "注释" to (after.comment.isNotBlank() && after.comment != existing.comment)
                ).filter { it.second }.map { it.first }
                add(
                    if (changed.isEmpty()) {
                        "元信息 未变化（文件里已有这些字段，或源里没有更新的值）"
                    } else {
                        "元信息 已写入 " + changed.joinToString("/")
                    }
                )
            }
        }
        AppLog.i(
            TAG,
            "写入核对 " + file.name + "：" + verified.joinToString("；") +
                "；内嵌=" + embed.embedded +
                "；模式=" + settings.effectiveWriteMode(file.extension) +
                "；策略=" + writePolicy(settings) +
                "；保持修改时间=" + settings.keepModTime +
                "；格式=" + file.extension
        )
        if (embed.notes.isNotEmpty()) {
            AppLog.d(TAG, "写入备注 " + file.name + "：" + embed.notes.joinToString("；"))
        }

        // 艺术家表只记首位艺术家：一首歌只属于一位艺术家（用户规则）。
        val finalArtist = primaryArtist(after.artist.ifBlank { track.artist })
        // 只有真的拿到艺术家图才写艺术家记录。
        // 之前是 imageLocalPath = sidecarArtist ?: sidecarCover —— 拿歌曲封面冒充艺术家图，
        // 结果艺术家网格里显示的全是专辑封面。没有艺术家图时留空，界面会用占位图。
        if (finalArtist.isNotBlank() && (embed.sidecarArtist != null || embed.artistHash != null)) {
            // ★ 先读旧的再合并：往 `artists` 表 upsert 一整行会把没填的字段清成 null。
            //   内嵌写入时 sidecarArtist 是 null，直接覆盖就把之前存的外置图路径抹掉了 ——
            //   艺术家网格里那位歌手的头像会莫名其妙变回占位图。
            val previous = runCatching { locator.artists.getByName(finalArtist) }.getOrNull()
            locator.artists.upsert(
                ArtistEntity(
                    id = previous?.id ?: 0L,
                    name = finalArtist,
                    imageLocalPath = embed.sidecarArtist ?: previous?.imageLocalPath,
                    imageSourceId = previous?.imageSourceId,
                    imageHash = embed.artistHash ?: previous?.imageHash
                )
            )
        }
        locator.tracks.updateAfterScrape(
            id = track.id,
            hasArtist = after.hasArtistImage || embed.sidecarArtist != null,
            hasCover = after.hasCover,
            // 歌词不再从网络补，这两个字段只反映文件里已有的内容。
            hasLyrics = after.hasLyrics,
            hasTranslation = after.hasTranslation,
            lyricsType = after.lyricsType,
            artistImagePath = embed.sidecarArtist ?: track.artistImagePath,
            coverPath = track.coverPath,
            lyricsPath = track.lyricsPath,
            title = after.title.ifBlank { track.title },
            artist = after.artist.ifBlank { track.artist },
            album = after.album.ifBlank { track.album },
            year = after.year,
            trackNo = after.trackNo,
            state = 1,
            // 把核对结果放在最前面：用户最想知道的是"到底写进去没有"，
            // 而不是"搜索完成"这种过程信息。
            message = (verified + notes.filter { it.isNotBlank() } + embed.notes)
                .filter { it.isNotBlank() }
                .joinToString("；")
                .ifBlank { "已写入" }
        )
        // 用户要求："所有对某项歌曲进行的写入操作，都会重新读取一次标签，刷新缓存数据"。
        // 这里刚写进过文件，重新完整读一遍：歌词缓存、开头静音、
        // 以及「元信息」页要用的自定义标签都会跟着刷新。
        runCatching { locator.indexer.refreshTrack(track.id) }
        return ScrapeOutcome.SUCCESS
    }

    /**
     * 写入用户挑好的那一条歌词。
     *
     * 歌词是纯文本，不涉及图片和封面，所以这里不走通用的处理流程：
     * 读文件 → 把歌词交给 embedder → 更新数据库 → 重新索引，一条直线，出错也好定位。
     */
    private suspend fun writePickedLyrics(ids: List<Long>, payload: String) {
        val locator = ServiceLocator
        val settings = locator.settings.settings.first()
        val tracks = if (ids.isEmpty()) emptyList() else locator.tracks.getByIds(ids)
        progress.value = ScrapeProgress(running = true, total = tracks.size, message = "正在写入歌词")

        val item = runCatching { org.json.JSONObject(payload) }.getOrNull()
        val lyrics = item?.optString("lyrics").orEmpty()
        if (item == null || lyrics.isBlank()) {
            progress.value = ScrapeProgress(
                finished = true,
                finishedAt = System.currentTimeMillis(),
                message = "这条歌词是空的，没有写入"
            )
            return
        }
        val translation = item.optString("translation").ifBlank { item.optString("trans") }
        val type = item.optString("type").ifBlank { "line" }
        val withTranslation = translation.isNotBlank() && settings.downloadTranslation

        var ok = 0
        var failed = 0
        tracks.forEach { track ->
            val file = File(track.path)
            if (!file.isFile) return@forEach
            val existing = runCatching { TagReader().read(file) }.getOrDefault(TrackMeta())
            val result = runCatching {
                locator.embedder.embed(
                    ImageEmbedder.EmbedRequest(
                        file = file,
                        // 封面和艺术家图这次不动。
                        lyrics = buildList {
                            add(LyricsItem(type = type, lang = "", translated = false, content = lyrics))
                            if (withTranslation) {
                                add(LyricsItem(type = type, lang = "", translated = true, content = translation))
                            }
                        },
                        existing = existing,
                        mode = settings.effectiveWriteMode(file.extension),
                        policy = OverwritePolicy.FORCE,
                        keepModTime = settings.keepModTime
                    )
                )
            }
            result.fold(
                onSuccess = {
                    ok++
                    runCatching {
                        locator.tracks.updateAfterScrape(
                            id = track.id,
                            hasArtist = existing.hasArtistImage,
                            hasCover = existing.hasCover,
                            hasLyrics = true,
                            hasTranslation = withTranslation,
                            lyricsType = type,
                            artistImagePath = track.artistImagePath,
                            coverPath = track.coverPath,
                            lyricsPath = track.lyricsPath,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            year = track.year,
                            trackNo = track.trackNo,
                            state = 1,
                            message = "歌词已写入（" + com.audiometa.app.domain.model.lyricsTypeLabel(type) +
                                (if (withTranslation) " + 翻译）" else "）")
                        )
                        // 写完重新读一遍标签并刷新缓存（用户要求：所有写入都这样）。
                        locator.indexer.refreshTrack(track.id)
                    }
                    AppLog.i(TAG, "歌词已写入 " + file.name)
                },
                onFailure = {
                    failed++
                    AppLog.e(TAG, "歌词写入失败 " + file.name, it)
                }
            )
        }
        progress.value = ScrapeProgress(
            running = false,
            finished = true,
            // 完成时刻：详情页据此重读文件（见 ScrapeProgress.finishedAt）。
            finishedAt = System.currentTimeMillis(),
            total = tracks.size,
            done = ok + failed,
            success = ok,
            failed = failed,
            message = "歌词写入完成：成功 " + ok + " 首" + if (failed > 0) "，失败 " + failed + " 首" else ""
        )
    }

    /** 按能力取出该内容的源优先级，未列出的源保持原有顺序排在后面。 */
    private fun orderPlugins(
        capability: ScrapeCapability,
        plugins: List<InstalledPlugin>,
        settings: AppSettings
    ): List<InstalledPlugin> {
        val key = when (capability) {
            ScrapeCapability.ARTIST_IMAGE -> PriorityKey.ARTIST_IMAGE
            ScrapeCapability.TRACK_META -> PriorityKey.TRACK_META
            ScrapeCapability.LYRICS -> PriorityKey.LYRICS
            // 曲风和专辑图都是"按歌搜"的，和元信息共用同一份源顺序
            // （默认 Apple Music → QQ，用户可在设置里手动拖动）。
            ScrapeCapability.GENRE -> PriorityKey.TRACK_META
            ScrapeCapability.COVER -> PriorityKey.TRACK_META
        }
        // 拖动排序只影响显示；刮削优先级只认标签规则里的 sourcePriority。
        val order = settings.sourcePriority[key].orEmpty()
        if (order.isEmpty()) return plugins
        val byId = plugins.associateBy { it.pluginId }
        val ranked = order.mapNotNull { byId[it] }
        val rankedIds = ranked.map { it.pluginId }.toSet()
        return ranked + plugins.filter { it.pluginId !in rankedIds }
    }

    /**
     * 元信息按字段合并：**每个字段单独挑"内容更全"的那个源**。
     *
     * 这是用户明确要求的规则：
     * - 各源并行搜索（[runSearches] 里已经并发发出）；
     * - 收齐后逐个字段比较，谁给的值"内容更多"就用谁；
     * - 内容同样多时按源优先级（Apple Music → QQ → 网易 → 酷我）定先后；
     * - 文件里已有的字段不参与竞争，只在源提供了**更长/更完整**的值时才覆盖。
     *
     * "内容更多"怎么比：先比非空长度，长度一样再按源优先级。
     * 对日期这类字段这正好合适（"2023-05-12" 比 "2023" 全），
     * 对流派/作曲这类多值字段（"A/B" 比 "A" 全）也合适。
     */
    /**
     * 清洗曲风值。规则本体在 [MetaMergePolicy.cleanGenre]——
     * 弹窗预览和真正写入必须用同一套，所以只留这一个实现。
     */
    private fun cleanGenre(raw: String): String = MetaMergePolicy.cleanGenre(raw)

    private fun mergeMetaFromHits(
        hits: List<MetaHit>,
        existing: TrackMeta,
        settings: AppSettings
    ): TrackMeta? {
        if (hits.isEmpty()) return null

        // 源优先级：数字越小越优先。
        val priority = buildMap {
            PriorityKey.TRACK_META.let { key ->
                settings.sourcePriority[key].orEmpty().forEachIndexed { index, id -> put(id, index) }
            }
        }
        fun rankOf(hit: MetaHit): Int = priority[hit.plugin.pluginId] ?: Int.MAX_VALUE

        /**
         * 挑这个字段的值。
         *
         * 用户规则：**哪个源给的内容更长就用哪个**（"什么最多就取什么" ——
         * 例如注释是酷狗给得最长，注释就取酷狗的）。长度一样时才按源优先级。
         *
         * 以前是先按 [MetaMergePolicy] 那张"字段质量表"排序、长度只在同级内比较，
         * 于是"注释/版权"这类字段永远拿到 Apple 的短值，用户要的"取字数最多的"
         * 从来没生效过。质量表仍然用于**同长度的平手判定**。
         */
        fun pickText(field: String, selector: (TrackMeta) -> String): String {
            val candidates = hits
                .mapNotNull { hit -> selector(hit.meta).trim().takeIf { it.isNotBlank() }?.let { hit to it } }
            if (candidates.isEmpty()) return ""

            /** 先按字段专属优先级，再按用户设置的全局源优先级。 */
            fun orderOf(id: String): Int =
                MetaMergePolicy.orderOf(field, id, rankOf(candidates.first { it.first.plugin.pluginId == id }.first))

            // 最长优先；一样长时优先级高的胜（minWith + 长度降序，等价于"最长且最优先"）。
            return candidates.minWithOrNull(
                compareByDescending<Pair<MetaHit, String>> { it.second.length }
                    .thenBy { orderOf(it.first.plugin.pluginId) }
            )?.second.orEmpty()
        }

        fun pickNumber(field: String, selector: (TrackMeta) -> Int): Int {
            val candidates = hits
                .mapNotNull { hit -> selector(hit.meta).takeIf { v -> v > 0 }?.let { hit to it } }
            if (candidates.isEmpty()) return 0
            fun orderOf(id: String): Int =
                MetaMergePolicy.orderOf(field, id, rankOf(candidates.first { it.first.plugin.pluginId == id }.first))
            return candidates.minWithOrNull(
                compareBy<Pair<MetaHit, Int>> { orderOf(it.first.plugin.pluginId) }
                    .thenByDescending { it.second }
            )?.second ?: 0
        }

        /*
         * 流派：**固定优先级** Apple Music → QQ → 网易 → 酷狗（用户明确指定）。
         * 前一个源没有流派值才看下一个；这四个都没有（例如只有酷我/音乐库源提供流派）
         * 才按同一张表里的次序继续往下看 —— 「流派优先级」这个设置页已经按用户要求撤掉，
         * 所以这里不再读 `sourcePriority`，整条规则只剩 [MetaMergePolicy] 一处定义。
         * Apple 返回的是层级分类（"国语流行 / 音乐 / 国际流行"），用 cleanGenre 洗掉一级分类。
         */
        val genre = hits
            .sortedBy { MetaMergePolicy.orderOf("genre", it.plugin.pluginId, rankOf(it)) }
            .firstNotNullOfOrNull { hit ->
                cleanGenre(hit.meta.genre).takeIf { value -> value.isNotBlank() }
            }
            .orEmpty()

        /*
         * 标题 / 艺术家 / 专辑**不参与刮削合并**（红线）。
         *
         * 弹窗上写着「基础标签（只读，不会修改）」，这里以前却照样把它们挑出来、
         * 再以"覆盖"策略写进文件 —— 于是给一位艺术家刮图时，他名下所有歌的标题
         * 都被改成了同一次搜索匹配到的那首。这三个字段只有详情页的元信息手动编辑能改。
         */
        val incoming = TrackMeta(
            title = "",
            artist = "",
            albumArtist = pickText("albumArtist") { it.albumArtist },
            album = "",
            trackNo = pickNumber("trackNo") { it.trackNo },
            discNo = pickNumber("discNo") { it.discNo },
            year = pickNumber("date") { it.year },
            date = pickText("date") { it.date },
            // 流派走它自己的优先级，不参与 fieldOwner。
            genre = genre,
            language = pickText("language") { it.language },
            composer = pickText("composer") { it.composer },
            lyricist = pickText("lyricist") { it.lyricist },
            conductor = pickText("conductor") { it.conductor },
            comment = pickText("comment") { it.comment },
            copyright = pickText("copyright") { it.copyright },
            isrc = pickText("isrc") { it.isrc },
            bpm = pickText("bpm") { it.bpm }
        )
        AppLog.d(
            TAG,
            "元信息合并：参与源 " + hits.size + " 个（" +
                hits.joinToString("、") { it.plugin.name + " " + it.meta.fieldCount() + " 字段" } + "）"
        )
        return mergeMeta(null, incoming, existing)
    }

    private fun buildRequest(
        capability: ScrapeCapability,
        existing: TrackMeta,
        plugin: InstalledPlugin,
        fileName: String,
        /** 歌词专用：已经匹配好的那首歌，原样交给插件去取词。 */
        matchedSong: JSONObject? = null
    ): JSONObject {
        val title = existing.title.trim()
        val artist = existing.artist.trim()
        val album = existing.album.trim()
        val config = runCatching { JSONObject(plugin.configJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        // 歌词：用匹配到的那首歌当 song，插件就会按它的 id 精确取词，
        // 不再依赖插件自己再搜一次。
        val song = matchedSong ?: JSONObject()
            .put("id", "local-song")
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("duration", existing.durationMs)
        // 有标签用「标题 歌手」，没标签用文件名（xx - xx 会按 xx xx 搜）。
        val keyword = TrackMatcher.buildQuery(title, artist, fileName)
        val pageSize = when (capability) {
            ScrapeCapability.ARTIST_IMAGE -> ARTIST_SEARCH_PAGE_SIZE
            // 元信息要多取几条才够比对。
            else -> 10
        }
        return JSONObject()
            .put("keyword", keyword)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("page", 1)
            .put("pageSize", pageSize)
            .put("separator", "/")
            .put("config", config)
            .put("song", song)
    }

    /** 把插件的候选数组转成可打分的列表。 */
    private fun toCandidates(array: JSONArray): List<TrackMatcher.Candidate> {
        val out = mutableListOf<TrackMatcher.Candidate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val fields = item.optJSONObject("fields")
            fun pick(vararg keys: String): String {
                keys.forEach { key ->
                    item.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                    fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return ""
            }
            out += TrackMatcher.Candidate(
                title = pick("title", "name", "song"),
                artist = pick("artist", "singer", "artistName"),
                album = pick("album", "albumName"),
                durationMs = item.optLong("duration", 0L)
            )
        }
        return out
    }

    /** 分数最高的那条候选；没有候选返回 null。 */
    private fun bestItem(array: JSONArray, want: TrackMatcher.Want): JSONObject? {
        // 三基项严格匹配后才参与排序：不再"取最像的一条"写进文件。
        val strict = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .filter { item ->
                strictTagMatch(
                    title = candidateTitle(item),
                    artist = candidateArtist(item),
                    album = candidateAlbum(item),
                    wantTitle = want.title,
                    wantArtist = want.artist,
                    wantAlbum = want.album
                )
            }
        if (strict.isEmpty()) return null
        val ranked = strict.mapIndexed { index, item ->
            item to TrackMatcher.score(
                TrackMatcher.Candidate(
                    title = candidateTitle(item),
                    artist = candidateArtist(item),
                    album = candidateAlbum(item),
                    durationMs = item.optLong("duration", 0L)
                ),
                want,
                index
            )
        }
        return ranked.maxByOrNull { it.second }?.first
    }

    /**
     * 封面：只从三项严格匹配的候选里取图，再按分辨率挑。
     *
     * @return (封面地址, 匹配分数)；没有三项全中的候选时为空列表。
     */
    private fun coverCandidates(array: JSONArray, want: TrackMatcher.Want): List<Pair<String, Double>> {
        val candidates = toCandidates(array)
        return TrackMatcher.rank(candidates, want)
            .filter { ranked ->
                val item = array.optJSONObject(ranked.index) ?: return@filter false
                strictTagMatch(
                    title = candidateTitle(item),
                    artist = candidateArtist(item),
                    album = candidateAlbum(item),
                    wantTitle = want.title,
                    wantArtist = want.artist,
                    wantAlbum = want.album
                )
            }
            .mapNotNull { ranked ->
                val item = array.optJSONObject(ranked.index) ?: return@mapNotNull null
                val url = urlsForCover(item) ?: return@mapNotNull null
                url to ranked.score
            }
    }

    private fun candidateTitle(item: JSONObject): String =
        item.optString("title").ifBlank { item.optJSONObject("fields")?.optString("title").orEmpty() }

    private fun candidateArtist(item: JSONObject): String =
        item.optString("artist").ifBlank { item.optJSONObject("fields")?.optString("artist").orEmpty() }

    private fun candidateAlbum(item: JSONObject): String =
        item.optString("album").ifBlank { item.optJSONObject("fields")?.optString("album").orEmpty() }

    /** 匹配度最高的候选分数；没有任何候选时为 -1。 */
    private fun bestCoverScore(array: JSONArray, want: TrackMatcher.Want): Double =
        TrackMatcher.rank(toCandidates(array), want).firstOrNull()?.score ?: -1.0

    /**
     * 一个够可靠的封面候选：哪个平台、图片地址、匹配分数。
     */
    private data class CoverOption(
        val plugin: InstalledPlugin,
        val url: String,
        val score: Double
    )

    /**
     * 从多个平台的结果里挑分辨率最高的封面。
     *
     * 用户的要求：先按「同专辑 / 同艺术家 / 同歌曲」筛，再在这些候选中选分辨率最高的平台。
     * 图片尺寸读文件头就能拿到，不用把整张图都解码。
     */
    private suspend fun bestCoverByResolution(candidates: List<CoverOption>): CoverOption? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val runtime = ServiceLocator.runtime
        data class Measured(val option: CoverOption, val width: Int, val height: Int) {
            val square: Boolean get() = width > 0 && height > 0 &&
                kotlin.math.abs(width - height) <= kotlin.math.max(2, kotlin.math.min(width, height) / 50)
            val bigSquare: Boolean get() = width >= 3000 && height >= 3000 && square
            val shorterSide: Int get() = kotlin.math.min(width, height)
        }
        return kotlinx.coroutines.coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    val size = runCatching {
                        val bytes = runtime.downloadBytes(candidate.url)
                        com.audiometa.app.media.image.ImageSizeProbe.probe(bytes)
                    }.getOrNull()
                    Measured(candidate, size?.first ?: 0, size?.second ?: 0)
                }
            }.awaitAll()
                // 用户规则：3000×3000 优先，其次 1:1，再按短边分辨率。
                .sortedWith(
                    compareByDescending<Measured> { it.bigSquare }
                        .thenByDescending { it.square }
                        .thenByDescending { it.shorterSide }
                        .thenByDescending { it.option.score }
                )
                .firstOrNull()
                ?.option
        }
    }

    /** 封面字段：picUrl / cover_url / coverUrl（不含艺术家图专用字段）。 */
    private fun urlsForCover(item: JSONObject): String? {
        val fields = item.optJSONObject("fields")
        val keys = listOf("picUrl", "cover_url", "coverUrl", "imageUrl", "image_url")
        for (key in keys) {
            item.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * 歌词：先从搜索结果里挑出最像的那首歌。
     *
     * 分数不过线时返回 null，让调用方换下一个源，而不是拿一首不相干的歌去取词。
     *
     * @return 选中的歌曲 JSON（直接塞进下一次请求的 song 字段）与最高分数。
     */
    private fun matchSongForLyrics(array: JSONArray, want: TrackMatcher.Want): Pair<JSONObject?, Double> {
        val ranked = TrackMatcher.rank(toCandidates(array), want)
            .filter { ranked ->
                val item = array.optJSONObject(ranked.index) ?: return@filter false
                strictTagMatch(
                    title = candidateTitle(item),
                    artist = candidateArtist(item),
                    album = candidateAlbum(item),
                    wantTitle = want.title,
                    wantArtist = want.artist,
                    wantAlbum = want.album
                )
            }
        if (ranked.isEmpty()) return null to -1.0
        val best = ranked.first()
        return array.optJSONObject(best.index) to best.score
    }

    private fun percent(score: Double): String =
        (score * 100).toInt().coerceIn(0, 100).toString() + "%"

    /**
     * 艺术家图的候选地址，按优先级排列。
     *
     * 插件可以给 imageUrls 数组（例如 QQ 的 800/500/300），也可以只给一个 imageUrl。
     *
     * 匹配时把「歌手名」同时当作标题和歌手来比：
     * 插件返回的艺术家结果里，QQ 的 name 就是歌手名，网易的 name 也是，
     * 但有的源把歌手名放在 title 字段。两边都比一次，才不会因为字段名不同而整个漏掉。
     */
    /**
     * 从搜索结果里挑出这位艺术家的图片地址。
     *
     * 排序用 [TrackMatcher.artistScore]：在"歌手名像不像"之外，
     * 再看专辑吻合与曲目吻合。同名艺术家靠这两个判据区分——
     * 否则两个同名的歌手分数一样，谁在前面谁赢，很容易刮错人。
     */
    private fun pickArtistImageUrls(
        array: JSONArray,
        artistName: String,
        localAlbum: String = "",
        localTitle: String = ""
    ): List<String> {
        val target = artistName.trim()
        if (target.isBlank()) return emptyList()
        val artistWant = TrackMatcher.Want(
            title = target,
            artist = target,
            album = localAlbum,
            fileName = "",
            durationMs = 0L
        )
        val candidates = toCandidates(array)
        val ranked = candidates.mapIndexed { index, candidate ->
            val item = array.optJSONObject(index)
            val tracks = item?.let { trackTitlesOf(it) }.orEmpty()
            Triple(index, candidate, TrackMatcher.artistScore(
                candidate = candidate,
                want = artistWant,
                rankIndex = index,
                localAlbum = localAlbum,
                localTitle = localTitle,
                candidateTracks = tracks
            ))
        }.sortedByDescending { it.third }

        if (ranked.isEmpty()) return emptyList()
        val bestIndex = ranked.first().first
        val bestItem = array.optJSONObject(bestIndex) ?: return emptyList()

        // ⚠️ 硬性名字闸门：候选的艺术家名**必须**和目标对得上，否则宁可不返回图。
        //
        // 为什么必须有这一道：实测 Apple Music 搜「周杰伦」时，返回的头两条是
        // John Legend 和袁咏琳 —— 周杰伦压根不在结果里。而 Apple 正好排在
        // 源优先级第一位，于是艺术家图刮削会把 **John Legend 的照片写成周杰伦的**。
        //
        // 上面那个 artistScore 只是"给匹配的加分"，只有一个候选时它必然被选中，
        // 分数高低都拦不住。写错人的照片比不出图糟糕得多，所以这里直接一票否决：
        // 名字对不上就返回空，上层会记为"未命中"。
        if (!artistNameMatches(bestItem, target)) {
            AppLog.d(
                TAG,
                "艺术家图候选名字不匹配，放弃：想要「" + target + "」，候选「" +
                    candidateArtistName(bestItem) + "」"
            )
            return emptyList()
        }
        return urlsOf(bestItem)
    }

    /**
     * 候选里的艺术家名。
     *
     * 各源字段名不一致（name / artist / singer / fields.artist），能取到哪个用哪个。
     */
    private fun candidateArtistName(item: JSONObject): String {
        val fields = item.optJSONObject("fields")
        listOf("name", "artist", "singer", "artistName", "albumArtist").forEach { key ->
            item.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            // fields 可能不存在；optString 在 JSONObject? 上要安全调用。
            fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    /**
     * 候选艺术家名和目标是否算同一个。
     *
     * 归一化规则和 [TrackMatcher] 保持一致：去掉空白、标点、大小写差异，
     * 再做包含判断。这样「周杰伦」对上「周杰倫」这类简繁差异是**对不上**的
     * ——简体繁体确实是不同的字面，硬匹配会出错。这种情况靠上层用另一种写法再搜一次，
     * 而不是在这里放宽标准。
     */
    private fun artistNameMatches(item: JSONObject, target: String): Boolean {
        val candidate = candidateArtistName(item)
        if (candidate.isBlank()) {
            // 候选连名字都没给：无法确认，按不匹配处理。
            return false
        }
        return sameArtistText(candidate, target)
    }

    private fun sameArtistText(a: String, b: String): Boolean {
        val x = normalizeArtistText(a)
        val y = normalizeArtistText(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // 完全相等，或者一方包含另一方（"周杰伦" vs "周杰伦 Jay Chou"）。
        return x == y || x.contains(y) || y.contains(x)
    }

    /**
     * 这首曲目算不算挂在艺术家 [target] 名下。
     *
     * 合作写法（`A/B`、`A feat. B`、`A、B`…）拆开之后任一段相同就算 ——
     * 判断规则和艺术家页的分组、归一化方式共用 [com.audiometa.app.domain.model.ArtistNames]，
     * 不在这里另写一套。
     */
    private fun artistFieldMatches(artistField: String, target: String): Boolean =
        com.audiometa.app.domain.model.ArtistNames.matches(artistField, target)

    /**
     * 三基项严格匹配：标题、专辑、艺术家都要对上才允许写文件。
     *
     * 空白项一律视为不匹配 —— 缺一项就没有"符合三项"可言，宁可这次不写。
     */
    private fun strictTagMatch(
        title: String,
        artist: String,
        album: String,
        wantTitle: String,
        wantArtist: String,
        wantAlbum: String
    ): Boolean {
        if (wantTitle.isBlank() || wantArtist.isBlank() || wantAlbum.isBlank()) return false
        if (title.isBlank() || artist.isBlank() || album.isBlank()) return false
        return sameTagText(title, wantTitle) &&
            sameTagText(album, wantAlbum) &&
            artistFieldMatches(artist, wantArtist)
    }

    private fun sameTagText(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * 一首歌的**首位艺术家**。
     *
     * 用户规则：一首歌有多位艺术家（`/`、`&`、中英文逗号分隔）时，
     * 它只属于**首位**那一位 —— 艺术家列表里只出现在这一位名下，
     * 艺术家图也只写这一位的图。否则给 A 刮完再给 B 刮，
     * 同一个文件会被反复写入不同艺术家的图，最后一次把之前的覆盖掉。
     */
    private fun primaryArtist(raw: String): String =
        com.audiometa.app.domain.model.ArtistNames.of(raw).firstOrNull()?.trim().orEmpty()
            .ifBlank { raw.trim() }

    private fun normalizeArtistText(text: String): String =
        com.audiometa.app.domain.model.ArtistNames.normalize(text)

    /**
     * 候选结果里带的曲目名。
     *
     * 各源字段名不一致，能取到哪个用哪个；取不到就返回空列表，
     * 曲目吻合这个判据自然不生效（不会凭空加分）。
     */
    private fun trackTitlesOf(item: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val fields = item.optJSONObject("fields")
        listOfNotNull(item.optJSONArray("songs"), item.optJSONArray("tracks"), fields?.optJSONArray("songs"))
            .forEach { list ->
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        listOf("songName", "trackTitle", "song")
            .forEach { key ->
                item.optString(key).takeIf { it.isNotBlank() }?.let { out += it }
                fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        return out
    }

    /** 从单条候选里取出艺术家图地址（按优先级）。 */
    private fun urlsOf(item: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        val fields = item.optJSONObject("fields")
        // ① 用户在结果网格里点的那张图：ScrapePickerScreen 把网格显示的那个地址钉在这个内部键上
        //    （见 PICKED_IMAGE_URL_KEY / ScrapeCandidate.pinPickedImageUrl）。
        //    放最前 —— "看到哪张就写哪张"，不再由下面的字段顺序替用户决定。
        //    自动搜索拿到的候选是插件原始返回，不会有这个键，这条分支自然跳过，行为不变。
        //    只查顶层：这个键是宿主自己 put 进去的，不会出现在 fields 子对象里。
        item.optString(PICKED_IMAGE_URL_KEY).takeIf { it.isNotBlank() }?.let { out += it }
        // ② QQ 这类源把多个尺寸给成 imageUrls 数组，按数组顺序（大 → 小）逐个试。
        listOfNotNull(item.optJSONArray("imageUrls"), fields?.optJSONArray("imageUrls")).forEach { list ->
            for (j in 0 until list.length()) {
                list.optString(j).takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        // 这里只认艺术家图专用字段。picUrl 按插件格式约定是「专辑封面」，
        // 拿它兜底会把封面写进艺术家图片字段——之前就是这个 bug。
        listOf("imageUrl", "artist_image_url", "artistImageUrl", "avatar", "avatarUrl").forEach { key ->
            item.optString(key).takeIf { it.isNotBlank() }?.let { out += it }
            fields?.optString(key)?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.toList()
    }

    private fun downloadFirstAvailable(
        runtime: com.audiometa.app.plugin.runtime.PluginRuntime,
        urls: List<String>
    ): ByteArray? {
        urls.forEach { url ->
            runCatching { runtime.downloadBytes(url) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun songToMeta(song: JSONObject, existing: TrackMeta): TrackMeta =
        MetaFields.fromSong(song)
    private fun shouldSkip(capability: ScrapeCapability, existing: TrackMeta, settings: AppSettings): Boolean {
        // 详情页的手动入口：一律不跳过。
        //
        // 这是"点了没反应"的真正原因：以前详情页点「组合刮削 / 艺术家图片 / 元信息」
        // 会走这里，文件里只要已有艺术家图、或标题+艺术家已齐全，就被判成"已存在"，
        // 整项能力被过滤掉，连搜索都不会发生。用户看到的是一点动静都没有。
        if (forceOverwrite) return false
        // 用户明确选了一条候选（单首刮削挑结果）时也不跳过：
        // 既然点了确认，意图就是"按我选的写"。
        if (hasForcedSelection(capability)) return false
        // 用户在这次窗口里把这一项点成「覆盖」就无条件重写；否则只补缺。
        if (capability in runOverwrite) return false
        return when (capability) {
            ScrapeCapability.ARTIST_IMAGE -> existing.hasArtistImage
            // 专辑图：文件里已经有封面且这次没被要求覆盖时就没必要再搜。
            ScrapeCapability.COVER -> existing.hasCover
            // 元信息只要缺任一项关键内容就值得再搜一次。
            ScrapeCapability.LYRICS -> false
            // 曲风：文件里已经有曲风就不重复搜（详情页的「覆盖」开关会绕过这里）。
            ScrapeCapability.GENRE -> existing.genre.isNotBlank()
            ScrapeCapability.TRACK_META ->
                existing.title.isNotBlank() &&
                    existing.artist.isNotBlank() &&
                    existing.fieldCount() >= META_COMPLETE_FIELD_COUNT
        }
    }

    /** 这次任务里，用户是否为这个能力指定过具体候选。 */
    private fun hasForcedSelection(capability: ScrapeCapability): Boolean =
        forcedPlan.keys.any { it.startsWith(capability.key + "|") }

    /**
     * 这次写入用哪种策略。
     *
     * 以前这里读的是一个全局的「覆盖策略」设置，它和刮削页里 "每种内容各自的
     * 关闭/补全/覆盖" 是同一件事，两处都能设就是重复 —— 那个设置已按用户要求移除。
     * 现在只看这次任务本身的意图：
     *
     * - 详情页的手动入口（[forceOverwrite]）→ 覆盖：点了就是要写进去；
     * - 用户在结果列表里明确选了一条候选 → 覆盖：按我选的写；
     * - 刮削窗口里这次把某项点成「覆盖」（[runOverwrite]）→ 覆盖；
     * - 其余 → 只补缺，不动文件里已有的字段。
     */
    private fun writePolicy(settings: AppSettings): OverwritePolicy =
        if (forceOverwrite ||
            ScrapeCapability.COVER in runOverwrite ||
            ScrapeCapability.ARTIST_IMAGE in runOverwrite ||
            ScrapeCapability.GENRE in runOverwrite ||
            ScrapeCapability.TRACK_META in runOverwrite ||
            hasForcedSelection(ScrapeCapability.COVER) ||
            hasForcedSelection(ScrapeCapability.ARTIST_IMAGE) ||
            hasForcedSelection(ScrapeCapability.GENRE) ||
            hasForcedSelection(ScrapeCapability.TRACK_META)
        ) {
            OverwritePolicy.FORCE
        } else {
            OverwritePolicy.FILL_MISSING
        }

    private fun sniffMime(bytes: ByteArray): String {
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            bytes.size >= 8 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            bytes.size >= 12 && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(8) == 0x57 -> "image/webp"
            bytes.size >= 6 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun mergeMeta(current: TrackMeta?, incoming: TrackMeta, existing: TrackMeta): TrackMeta {
        fun pick(newValue: String, old: String, fileValue: String): String =
            newValue.ifBlank { old.ifBlank { fileValue } }
        val base = current ?: TrackMeta()
        return TrackMeta(
            title = pick(incoming.title, base.title, existing.title),
            artist = pick(incoming.artist, base.artist, existing.artist),
            albumArtist = pick(incoming.albumArtist, base.albumArtist, existing.albumArtist),
            album = pick(incoming.album, base.album, existing.album),
            trackNo = incoming.trackNo.takeIf { it > 0 } ?: base.trackNo.takeIf { it > 0 } ?: existing.trackNo,
            discNo = incoming.discNo.takeIf { it > 0 } ?: base.discNo.takeIf { it > 0 } ?: existing.discNo,
            year = incoming.year.takeIf { it > 0 } ?: base.year.takeIf { it > 0 } ?: existing.year,
            date = pick(incoming.date, base.date, existing.date),
            genre = pick(incoming.genre, base.genre, existing.genre),
            language = pick(incoming.language, base.language, existing.language),
            composer = pick(incoming.composer, base.composer, existing.composer),
            lyricist = pick(incoming.lyricist, base.lyricist, existing.lyricist),
            conductor = pick(incoming.conductor, base.conductor, existing.conductor),
            comment = pick(incoming.comment, base.comment, existing.comment),
            copyright = pick(incoming.copyright, base.copyright, existing.copyright),
            isrc = pick(incoming.isrc, base.isrc, existing.isrc),
            bpm = pick(incoming.bpm, base.bpm, existing.bpm)
        )
    }

    private suspend fun log(
        trackId: Long,
        plugin: InstalledPlugin,
        capability: ScrapeCapability,
        status: String,
        message: String,
        started: Long
    ) {
        val cost = System.currentTimeMillis() - started
        // trackId 为 0 表示这不是某一首歌的记录（例如按艺术家搜的图），
        // 只写运行日志，不写进歌曲日志表。
        if (trackId > 0) {
            ServiceLocator.logs.insert(
                ScrapeLogEntity(
                    trackId = trackId,
                    sourceId = plugin.pluginId,
                    capability = capability.key,
                    status = status,
                    message = message,
                    costMs = cost
                )
            )
        }
        // 同时写进运行日志，便于在「设置 - 调试」里一眼看清每个源的结果。
        val text = plugin.name + " / " + capability.label + " / " + statusLabel(status) +
            " / " + cost + "ms" + if (message.isBlank()) "" else "：" + message
        when (status) {
            "error", "denied" -> AppLog.w(TAG, text)
            "ok" -> AppLog.i(TAG, text)
            else -> AppLog.d(TAG, text)
        }
    }

    private fun statusLabel(status: String): String = when (status) {
        "ok" -> "命中"
        "miss" -> "未命中"
        "error" -> "错误"
        "denied" -> "被拒绝"
        else -> status
    }

    private suspend fun bumpFail(plugin: InstalledPlugin) {
        val record = ServiceLocator.pluginRepo.getSource(plugin.pluginId, plugin.pluginId) ?: return
        ServiceLocator.pluginRepo.setFailStreak(plugin.pluginId, plugin.pluginId, record.failStreak + 1)
    }

    private suspend fun resetFail(plugin: InstalledPlugin) {
        ServiceLocator.pluginRepo.setFailStreak(plugin.pluginId, plugin.pluginId, 0)
    }

    private fun updateNotification(done: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification("已完成 " + done + " / " + total, done, total.coerceAtLeast(1)))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scrape_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.scrape_channel_desc)
        nm.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, ScrapeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在刮削元数据")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setContentIntent(open)
            .addAction(0, "取消", cancel)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "audiometa_scrape"
        const val NOTIFICATION_ID = 1002
        const val ACTION_CANCEL = "com.audiometa.app.SCRAPE_CANCEL"
        const val EXTRA_IDS = "ids"
        const val EXTRA_CAPABILITIES = "capabilities"

        /** 本次要「覆盖」的能力子集（见 [start] 的参数说明）。 */
        const val EXTRA_OVERWRITE = "overwrite"

        // 单首刮削：用户在搜索结果里挑中的那一条。
        const val EXTRA_FORCED_PLUGIN = "forced_plugin"
        const val EXTRA_FORCED_CAPABILITY = "forced_capability"
        const val EXTRA_FORCED_JSON = "forced_json"

        /** 组合刮削确认后的计划：[{"c":能力,"p":插件,"j":候选JSON}]，一次可带多项。 */
        const val EXTRA_PLAN = "plan"
        const val EXTRA_LYRICS = "lyrics"

        /** 详情页手动入口置 true：忽略"已有内容就跳过"的策略，一律重写。 */
        const val EXTRA_FORCE_OVERWRITE = "force_overwrite"
        private const val TAG = "ScrapeService"
        private const val MAX_CONCURRENCY = 4

        /**
         * 疑似图片字段：**只用来在日志里提醒"候选里有这个字段但没被采用"**，
         * 绝不参与艺术家图地址解析 —— `picUrl` 按插件格式约定是专辑封面，
         * 加进 [urlsOf] 会把封面写进艺术家图字段（这个 bug 修过一次）。
         */
        private val SUSPECT_IMAGE_KEYS = listOf(
            "picUrl", "pic", "image", "cover", "coverUrl",
            "photo", "img", "imgUrl", "thumb", "thumbnail"
        )

        /** 搜艺术家图时多取一些候选，才够从歌曲线索里筛出正确的歌手。 */
        private const val ARTIST_SEARCH_PAGE_SIZE = 10

        /**
         * 元信息"已经够全"的字段数门槛。
         *
         * 达到这个数就不再重复搜。定在 5：标题、艺术家、专辑、日期、流派
         * 这几项齐了，再搜的边际收益很低；低于这个数就值得去补。
         */
        private const val META_COMPLETE_FIELD_COUNT = 5

        /**
         * 匹配分数下限，和 Lyrico 示例客户端保持一致：
         * 封面 0.65，歌词 0.72。低于这个分数宁可跳过，也不写错内容。
         */
        private const val COVER_MATCH_THRESHOLD = 0.65
        private const val LYRICS_MATCH_THRESHOLD = 0.72
        /** 单个源的单次查询上限。搜索是整条链路最慢的一步，卡太死会让一次刮削拖到几十秒。 */
        private const val SOURCE_TIMEOUT_MS = 8_000L
        /** 刮削日志保留 14 天。 */
        private const val LOG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        val progress = MutableStateFlow(ScrapeProgress())
        private val cancelled = AtomicBoolean(false)

        fun start(
            context: Context,
            ids: List<Long>,
            capabilities: Set<ScrapeCapability>,
            /**
             * 本次要「覆盖」的能力子集。
             *
             * 来自刮削窗口里用户**当场**点的那几项（默认空 = 全部只补缺）。
             * 之所以随意图传进来而不是读持久化设置：用户要求每次打开窗口都默认关闭、
             * 必须手动指定，而持久化设置会让"上次点过覆盖"一直生效。
             */
            overwrite: Set<ScrapeCapability> = emptySet()
        ) {
            val intent = Intent(context, ScrapeService::class.java).apply {
                putExtra(EXTRA_IDS, ids.toLongArray())
                putStringArrayListExtra(EXTRA_CAPABILITIES, ArrayList(capabilities.map { it.key }))
                putStringArrayListExtra(EXTRA_OVERWRITE, ArrayList(overwrite.map { it.key }))
            }
            context.startForegroundService(intent)
        }

        /**
         * 用指定候选刮削：不再自己挑，直接用用户在结果列表里选中的那一条。
         *
         * 详情页的三个刮削入口（组合刮削 / 艺术家图片 / 元信息）走的就是这里，
         * 它们**默认覆盖**——用户点了这个入口就是"我要按这次搜到的结果写进去"，
         * 不该再因为文件里已经有内容而被跳过。
         */
        fun startWithCandidate(
            context: Context,
            trackId: Long,
            capability: ScrapeCapability,
            pluginId: String,
            candidateJson: String,
            /** 详情页那个"覆盖 / 跳过"横向开关：开 = 覆盖已有的，关 = 只补缺。 */
            forceOverwrite: Boolean = true
        ) {
            startWithPlan(
                context = context,
                trackId = trackId,
                capabilities = setOf(capability),
                plan = listOf(Triple(capability.key, pluginId, candidateJson)),
                forceOverwrite = forceOverwrite
            )
        }

        /**
         * 按确认过的计划写入。
         * plan 里每一项是 (能力, 插件 id, 候选 JSON)，每个能力都会用用户确认的那一条。
         *
         * @param forceOverwrite 详情页的手动入口传 true：忽略"文件里已经有内容就跳过"的
         *   策略。以前这个标记不存在，于是详情页点「组合刮削」时，
         *   只要文件里已有艺术家图、或标题+艺术家已齐全，整个能力就被判成"已存在"跳过，
         *   用户看到的就是"点了没反应"。
         */
        fun startWithPlan(
            context: Context,
            trackId: Long,
            capabilities: Set<ScrapeCapability>,
            plan: List<Triple<String, String, String>>,
            forceOverwrite: Boolean = false
        ) {
            val array = JSONArray()
            plan.forEach { (capability, pluginId, json) ->
                array.put(
                    JSONObject()
                        .put("c", capability)
                        .put("p", pluginId)
                        .put("j", json)
                )
            }
            val intent = Intent(context, ScrapeService::class.java).apply {
                putExtra(EXTRA_IDS, longArrayOf(trackId))
                putStringArrayListExtra(EXTRA_CAPABILITIES, ArrayList(capabilities.map { it.key }))
                putExtra(EXTRA_PLAN, array.toString())
                putExtra(EXTRA_FORCE_OVERWRITE, forceOverwrite)
            }
            // 服务起不来时不能静默失败：用户点完候选回到详情页，
            // 看到的就是"什么都没发生"。至少要留下一条日志。
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "刮削服务启动失败：" + (t.message ?: t.javaClass.simpleName), t)
            }
        }

        /**
         * 写入选中的歌词。
         *
         * 歌词正文在挑选界面里已经拿到了，所以这里直接把整条候选 JSON 带过去，
         * 服务里不再问插件 —— 少一次网络请求，也避免"挑的和写的不一致"。
         */
        fun startWithLyrics(
            context: Context,
            trackId: Long,
            candidateJson: String,
            /** 同上：详情页的"覆盖 / 跳过"开关。 */
            forceOverwrite: Boolean = true
        ) {
            val intent = Intent(context, ScrapeService::class.java).apply {
                putExtra(EXTRA_IDS, longArrayOf(trackId))
                putStringArrayListExtra(EXTRA_CAPABILITIES, arrayListOf(ScrapeCapability.LYRICS.key))
                putExtra(EXTRA_LYRICS, candidateJson)
                putExtra(EXTRA_FORCE_OVERWRITE, forceOverwrite)
            }
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "歌词写入服务启动失败：" + (t.message ?: t.javaClass.simpleName), t)
            }
        }

        fun cancel(context: Context) {
            cancelled.set(true)
            context.startService(Intent(context, ScrapeService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
