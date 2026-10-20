package com.audiometa.app.media.scan

import com.audiometa.app.data.db.FolderEntity
import com.audiometa.app.data.db.TrackEntity
import com.audiometa.app.data.repo.FolderRepository
import com.audiometa.app.data.repo.TrackRepository
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.domain.model.ScanProgress
import com.audiometa.app.media.image.ArtworkResolver
import com.audiometa.app.media.tag.TagReader
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 曲库扫描。
 *
 * 只有第一阶段：遍历目录、认音频、读基础标签（标题/专辑/艺术家/封面）。
 * 读歌词、逐字翻译这些耗时的活在扫描结束之后由 [LibraryIndexer] 在后台慢慢做，
 * 界面不用等它们，扫完立刻就能用。
 */
class MediaScanner(
    private val trackRepository: TrackRepository,
    private val folderRepository: FolderRepository,
    private val tagReader: TagReader = TagReader()
) {
    val progress = MutableStateFlow(ScanProgress())
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun scan(
        roots: List<String>,
        formats: Set<String>,
        excluded: Set<String>,
        minDurationMs: Long,
        excludeSubdirs: Boolean
    ) = withContext(Dispatchers.IO) {
        cancelled.set(false)
        progress.value = ScanProgress(running = true, message = "开始扫描")
        val found = AtomicInteger(0)
        val scanned = AtomicInteger(0)
        val unchanged = AtomicInteger(0)
        // 下面这些集合只在当前调度器上修改；worker 协程只负责返回结果。
        val seenPaths = mutableListOf<String>()
        val folderCounts = mutableMapOf<String, Int>()
        val batch = mutableListOf<TrackEntity>()
        val existing = trackRepository.allByPath()

        /*
         * 每个扫描根为什么没扫到东西。
         *
         * 这件事以前是**完全静默**的：`File.listFiles()` 在没读权限时返回 null，
         * 遍历直接 `continue`，最后一首都没有、界面却只说"扫描完成，共 0 首"。
         * 用户看到的是"我明明选了文件夹，一首都没有" —— 而真正的原因
         * （没给「所有文件访问」权限 / 目录选错了 / 格式没勾）一个字都没说。
         *
         * 这里按根目录收集原因，扫完汇总进进度消息。
         */
        val rootProblems = mutableListOf<String>()

        /*
         * 每个根进去之后**实际遍历到了几个目录、跳过了几个**。
         *
         * "已扫描 0 个文件" 有两种完全不同的原因，以前长得一模一样：
         *   1. 目录读不到（listFiles 返回 null）；
         *   2. 目录读得到，但**被排除规则整棵子树跳过了**
         *      —— [isExcluded] 命中父目录时，`path.startsWith(ex + "/")`
         *      会把所有下级一起排除掉。
         * 第 2 种最阴：权限完全正常、目录也在，就是一个文件都不进。
         *
         * 所以这里把"遍历到的目录数 / 被排除跳过的目录数"记下来，
         * 扫完写进进度消息 —— 用户一眼能看出是哪一种。
         */
        val walkedDirs = java.util.concurrent.atomic.AtomicInteger(0)
        val skippedExcluded = java.util.concurrent.atomic.AtomicInteger(0)

        try {
            for (rootPath in roots) {
                if (cancelled.get()) break
                val root = File(rootPath)
                val problem = diagnoseRoot(root)
                if (problem != null) {
                    rootProblems += problem
                    progress.value = progress.value.copy(message = problem)
                    continue
                }
                walk(
                    dir = root, root = root, formats = formats, excluded = excluded,
                    excludeSubdirs = excludeSubdirs, minDurationMs = minDurationMs, existing = existing,
                    found = found, scanned = scanned, unchanged = unchanged,
                    seenPaths = seenPaths, folderCounts = folderCounts, batch = batch,
                    walkedDirs = walkedDirs, skippedExcluded = skippedExcluded
                )
            }
            flush(batch)
            val removed = trackRepository.deleteMissing(seenPaths)
            folderCounts.forEach { (path, count) -> folderRepository.updateCount(path, count) }
            progress.value = progress.value.copy(
                running = false,
                finished = true,
                scannedFiles = scanned.get(),
                foundAudio = found.get(),
                message = buildScanMessage(
                    cancelled = cancelled.get(),
                    found = found.get(),
                    scanned = scanned.get(),
                    unchanged = unchanged.get(),
                    removed = removed,
                    rootProblems = rootProblems,
                    formatCount = formats.size,
                    walkedDirs = walkedDirs.get(),
                    skippedExcluded = skippedExcluded.get(),
                    roots = roots
                )
            )
        } catch (t: Throwable) {
            /*
             * 扫描中途失败时**必须**把 running 摘掉。
             *
             * 以前没有这个兜底：任何一步抛异常，"正在扫描"就会一直挂着 ——
             * 设置页的「重新扫描」按钮从此永远是灰的（它看 running），
             * `SettingsViewModel` 那个等待扫描结束的循环也就再也退不出来。
             * 异常本身继续往上抛，由 [com.audiometa.app.media.scan.ScanService]
             * 记录并把原因写进进度。
             */
            progress.value = progress.value.copy(
                running = false,
                finished = true,
                scannedFiles = scanned.get(),
                foundAudio = found.get(),
                message = "扫描中断：" + t.shortDescription()
            )
            throw t
        }
    }

    /**
     * 扫之前先判断这个根目录**能不能扫**，不能就给出人话原因。
     *
     * 返回 null 表示可以扫。
     *
     * ## 为什么必须有这个
     *
     * `File.listFiles()` 在**没有读权限**时返回 `null`（不是空数组），
     * 遍历因此静默跳过整个目录，最后报"扫描完成，共 0 首"。
     * 用户完全无从判断是"选错目录了"、"没给权限"还是"格式没勾"——
     * 这三种原因的处理方式完全不同，之前却长得一模一样。
     *
     * 更重要的是：**安卓 11 起读 `/storage/emulated/0` 下的任意目录需要
     * 「所有文件访问」权限**。用 SAF 选文件夹拿到的是 Uri 授权，
     * 它**不能**让 `java.io.File` 读到文件 —— 这是最容易踩的坑，
     * 所以这里专门把它认出来并直说怎么做。
     */
    private fun diagnoseRoot(root: File): String? {
        if (!root.exists()) {
            return "目录不存在：" + root.absolutePath + "（可能被移动或删除了，重新选一次）"
        }
        if (!root.isDirectory) {
            return "选的不是文件夹：" + root.absolutePath
        }
        // 没权限时 listFiles() 返回 null，这是唯一可靠的判断方式
        if (root.listFiles() == null) {
            return "读不到这个目录：" + root.absolutePath +
                "。到系统的「所有文件访问」里给本应用授权，或换一个目录"
        }
        return null
    }

    /**
     * 拼扫完之后那句给用户看的话。
     *
     * 挑出来单独写是因为它有**明确优先级**：
     *   1. 中断 → 只说中断
     *   2. 一个根都没扫成（都有问题）→ 说清每个问题，**不要**说"共 0 首"
     *      （"共 0 首"会让用户以为扫过了、只是没歌）
     *   3. 扫了但没找到 → 提示格式勾选与目录是否选对
     *   4. 正常 → 报数量
     */
    private fun buildScanMessage(
        cancelled: Boolean,
        found: Int,
        scanned: Int,
        unchanged: Int,
        removed: Int,
        rootProblems: List<String>,
        formatCount: Int,
        walkedDirs: Int,
        skippedExcluded: Int,
        roots: List<String>
    ): String {
        if (cancelled) return "扫描已中断，共 $found 首"

        // 每个根都扫不成：这才是"选了文件夹却什么都没有"的真正原因
        if (rootProblems.isNotEmpty() && scanned == 0 && found == 0) {
            return rootProblems.joinToString("；")
        }

        /*
         * **一个目录都没进去** —— 这是最需要说清楚的一种情况。
         *
         * 区分两种成因，并把实际用的根路径打出来：
         *   - 被排除规则整棵跳过（权限和目录都正常，最容易被误判成"软件坏了"）；
         *   - 根目录本身读不到（listFiles 返回 null）。
         */
        if (scanned == 0 && found == 0 && walkedDirs == 0) {
            return if (skippedExcluded > 0) {
                "一个目录都没扫：目录被「排除规则」整棵跳过了（跳过 $skippedExcluded 个）。" +
                    "到「设置 - 排除」里取消勾选，或在「子文件夹」页把被排除的目录恢复。" +
                    "本次根目录：" + roots.joinToString("、")
            } else {
                "一个目录都没扫：根目录读不到。本次根目录：" + roots.joinToString("、") +
                    "（若路径看着不对，把音乐目录移除后重新选一次）"
            }
        }

        return buildString {
            append("扫描完成，共 ").append(found).append(" 首")
            if (unchanged > 0) append("（未变化 ").append(unchanged).append(" 首）")
            if (removed > 0) append("，清理失效 ").append(removed).append(" 条")
            if (skippedExcluded > 0) {
                append("，跳过 ").append(skippedExcluded).append(" 个被排除的目录")
            }
            if (found == 0 && scanned > 0) {
                // 目录能读、文件也遍历到了，但一个都不认 —— 多半是格式没勾
                append("。扫到 ").append(scanned).append(" 个文件但没有可识别的音频")
                append("（当前只认 ").append(formatCount).append(" 种格式，到「管理音频格式」里检查）")
            } else if (scanned == 0 && rootProblems.isEmpty()) {
                append("。目录里没有文件（遍历了 ").append(walkedDirs).append(" 个目录）")
            }
            if (rootProblems.isNotEmpty()) {
                append("。另有目录读不到：").append(rootProblems.joinToString("；"))
            }
            append("。歌词等耗时内容正在后台补全")
        }
    }

    private suspend fun flush(batch: MutableList<TrackEntity>) {
        if (batch.isEmpty()) return
        val copy = batch.toList()
        batch.clear()
        trackRepository.upsertAll(copy)
    }

    /**
     * 遍历一棵目录树。
     *
     * **必须是显式栈，不能是递归。** 这里以前是自己调用自己的递归函数，
     * 两个问题都会真的把应用带走：
     *
     * 1. **目录层数没有上限**。用户目录里层层嵌套（解压出来的素材包、
     *    网盘同步留下的 `a/a/a/…`）时递归深度跟着层数走，爆栈抛的是
     *    `StackOverflowError` —— 它是 Error 不是 Exception，而且抛出来的时候
     *    栈已经用光了，同一个栈帧里的 `catch (Throwable)` 经常来不及兜住，
     *    表现就是"扫固定文件夹直接闪退"，外围那些 try/catch 全都白写。
     * 2. **符号链接成环**。`/storage/emulated/0` 下面某些 ROM、以及同步工具
     *    建的链接会指回祖先目录，递归会一圈一圈走到爆栈为止；而且这种环
     *    在栈溢出之前就已经把目录无限展开一遍，扫描永远结束不了。
     *
     * 所以改成显式工作队列 + 两个闸门：深度上限 [MAX_DEPTH]（到了只记日志、
     * 不再往下走，**已经扫到的文件照常入库**，不能被误判成"已消失"而删掉），
     * 以及按 `canonicalPath` 的已访问集合（同一个真实目录只进一次，
     * 环自然终止）。canonicalPath 解析失败（权限、IO 出错）时退回绝对路径：
     * 认不出"是不是同一个"时宁可多走一遍，也不能漏掉真目录。
     *
     * 其余行为刻意保持不变：同样的过滤、同样的 [BATCH_SIZE] 分批、
     * 同样先处理本层文件再进子目录。
     */
    private suspend fun walk(
        dir: File,
        root: File,
        formats: Set<String>,
        excluded: Set<String>,
        excludeSubdirs: Boolean,
        minDurationMs: Long,
        existing: Map<String, TrackEntity>,
        found: AtomicInteger,
        scanned: AtomicInteger,
        unchanged: AtomicInteger,
        seenPaths: MutableList<String>,
        folderCounts: MutableMap<String, Int>,
        batch: MutableList<TrackEntity>,
        walkedDirs: java.util.concurrent.atomic.AtomicInteger,
        skippedExcluded: java.util.concurrent.atomic.AtomicInteger
    ) {
        // (目录, 递归深度)。用 ArrayList 当栈：removeAt(lastIndex) 就是 pop。
        val stack = ArrayList<Pair<File, Int>>()
        stack += dir to 0
        // 已经进过的真实目录。每次 walk（每个扫描根）一份，互不干扰。
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            if (cancelled.get()) return
            val (current, depth) = stack.removeAt(stack.lastIndex)

            // 目录身份用规范化路径：符号链接指向同一个真实目录时只算一个。
            val identity = canonicalOrAbsolute(current)
            if (!visited.add(identity)) continue
            // 排除目录按**界面里显示的路径**判断（规范化后可能指向别处，
            // 用它去比会漏掉用户真正勾掉的那一条）。
            val path = current.absolutePath
            if (isExcluded(path, excluded)) {
                // 记下来：被排除跳过的目录数。整棵子树都不再往下走。
                skippedExcluded.incrementAndGet()
                continue
            }
            walkedDirs.incrementAndGet()

            folderRepository.upsert(
                FolderEntity(
                    path = path,
                    parentPath = current.parentFile?.absolutePath.orEmpty(),
                    name = current.name,
                    excluded = false,
                    depth = relativeDepth(root, current),
                    lastScanAt = System.currentTimeMillis()
                )
            )
            val children = current.listFiles() ?: continue
            val files = children.filter { it.isFile }
            val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }

            val hits = processFiles(files, formats, minDurationMs, existing, scanned, unchanged, current)
            // 未变化的文件 track 为 null，但 path 必须计入 seenPaths，
            // 否则下一次扫描会把它们当成"已消失"删掉。
            batch += hits.mapNotNull { it.track }
            seenPaths += hits.map { it.path }
            folderCounts[current.absolutePath] = (folderCounts[current.absolutePath] ?: 0) + hits.size
            found.addAndGet(hits.size)
            if (batch.size >= BATCH_SIZE) flush(batch)

            // 只看第一层：根那层处理完就结束。
            if (excludeSubdirs && depth > 0) continue
            if (depth + 1 > MAX_DEPTH) {
                // 只停止**继续下探**：这一层已经扫到的文件照常入库，
                // 它们不会被 deleteMissing 当成消失的曲目删掉。
                AppLog.w(TAG, "目录层数超过 $MAX_DEPTH 层，不再深入：" + current.absolutePath)
                continue
            }
            // 倒序入栈：先弹出来的还是原来的第一个子目录，
            // 遍历顺序（也就是进度里 currentPath 的走法）和递归版本一致。
            for (i in dirs.indices.reversed()) stack += dirs[i] to (depth + 1)
        }
    }

    /**
     * 目录的规范化路径；解析失败退回绝对路径。
     *
     * canonicalPath 会跟着符号链接走到真实位置，所以"链接指回祖先"这种环
     * 会得到和祖先一样的字符串，从而在 visited 里被认出来。
     */
    private fun canonicalOrAbsolute(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private suspend fun processFiles(
        files: List<File>,
        formats: Set<String>,
        minDurationMs: Long,
        existing: Map<String, TrackEntity>,
        scanned: AtomicInteger,
        unchanged: AtomicInteger,
        dir: File
    ): List<ScanHit> = coroutineScope {
        val semaphore = Semaphore(PARALLELISM)
        files.map { file ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    scanned.incrementAndGet()
                    progress.value = progress.value.copy(
                        scannedFiles = scanned.get(),
                        currentPath = file.absolutePath
                    )
                    val ext = AudioFormats.normalize(file.extension)
                    if (ext !in formats) return@withPermit null

                    // 修改时间和大小都没变就沿用已有记录，避免每次全量重读标签。
                    val cached = existing[file.absolutePath]
                    if (cached != null &&
                        cached.lastModified == file.lastModified() &&
                        cached.fileSize == file.length()
                    ) {
                        unchanged.incrementAndGet()
                        return@withPermit ScanHit(file.absolutePath, null)
                    }

                    /*
                     * 单个文件出问题不能带走整次扫描。
                     *
                     * `processFiles` 是一组并发子协程 + `awaitAll()`：
                     * 只要里面任何一个抛异常，`awaitAll` 会立刻把**其余全部取消**，
                     * 异常再一路穿过 `scan()` 抛给前台服务 —— 一个损坏的音频文件
                     * 就能让用户看到"扫到一半闪退"，而且再也分不清是哪首歌干的。
                     * 所以这里逐个文件兜住，把文件名和原因写进日志后跳过它。
                     */
                    val hit = runCatching {
                        val meta = tagReader.readBasic(file)
                        if (minDurationMs > 0 && meta.durationMs in 1 until minDurationMs) {
                            return@runCatching null
                        }
                        // 外置封面/艺术家图只是"看文件在不在"，不读内容，很便宜，
                        // 顺带在这里定下来，列表刷新时立刻就能显示。
                        val externalCover = ArtworkResolver.externalCoverPath(file)
                        val externalArtist = ArtworkResolver.externalArtistPath(file)

                        ScanHit(
                            file.absolutePath,
                            TrackEntity(
                                path = file.absolutePath,
                                folderPath = dir.absolutePath,
                                title = meta.title.ifBlank { file.nameWithoutExtension },
                                artist = meta.artist.ifBlank { tagReader.guessArtist(file) },
                                albumArtist = meta.albumArtist,
                                album = meta.album,
                                trackNo = meta.trackNo,
                                year = meta.year,
                                durationMs = meta.durationMs,
                                format = ext,
                                fileSize = file.length(),
                                lastModified = file.lastModified(),
                                // 曲风在第一阶段就读到了（Retriever 有这个键），
                                // 所以曲风页签不用等后台补全就能显示。
                                genres = TrackEntity.joinGenres(listOf(meta.genre)),
                                // 内嵌图要读标签才知道，第一阶段先不判；
                                // 有外置图就先算有图，界面不会先闪一下占位图。
                                hasArtistImage = externalArtist != null,
                                hasCover = externalCover != null,
                                artistImagePath = externalArtist,
                                coverPath = externalCover,
                                // 歌词索引标记清零：文件是新的或变过，后台要重新处理。
                                lyricsIndexedAt = 0L
                            )
                        )
                    }.onFailure {
                        AppLog.w(TAG, "跳过读不动的文件 " + file.absolutePath + "：" + it.shortDescription())
                        if (it is kotlinx.coroutines.CancellationException) throw it
                    }.getOrNull()
                    // 出问题的文件也要算进"仍然存在"：不记的话下一轮扫描
                    // 会把它当成已经消失的曲目，把库里已有的记录删掉。
                    hit ?: ScanHit(file.absolutePath, null)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun isExcluded(path: String, excluded: Set<String>): Boolean {
        if (path in excluded) return true
        return excluded.any { ex -> path == ex || path.startsWith(ex.trimEnd('/') + "/") }
    }

    private fun relativeDepth(root: File, dir: File): Int {
        val rootPath = root.absolutePath
        val dirPath = dir.absolutePath
        if (dirPath == rootPath) return 0
        val rest = dirPath.removePrefix(rootPath).trim('/')
        return rest.split('/').count { it.isNotBlank() }
    }

    /** 一次文件判定结果：track 为 null 表示内容没变，只需要把路径记进"仍存在"。 */
    private data class ScanHit(val path: String, val track: TrackEntity?)

    companion object {
        private const val PARALLELISM = 4
        private const val BATCH_SIZE = 200

        /**
         * 目录递归的硬上限。
         *
         * 正常音乐目录三层就到底了，64 层足够；它只是防"无限嵌套/链接成环"
         * 把一次扫描拖成永远跑不完。到了这一层只记一条日志并停止下探，
         * 已经发现的文件照常入库。
         */
        private const val MAX_DEPTH = 64

        /** 日志标签：单个文件跳过、扫描中断都记在这里，方便和 ScanService 对照。 */
        private const val TAG = "MediaScanner"
    }
}
