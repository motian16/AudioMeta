package com.audiometa.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别，同时也是设置页里可选的过滤级别。 */
enum class LogLevel(val label: String) {
    VERBOSE("详细"),
    DEBUG("调试"),
    INFO("一般"),
    WARN("警告"),
    ERROR("错误");

    companion object {
        fun fromName(name: String?): LogLevel = entries.firstOrNull { it.name == name } ?: INFO
    }
}

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String = AppLog.timeText(time) + " " + level.name + "/" + tag + ": " + message
}

/**
 * 进程内的环形日志缓冲 + **可选的落盘日志文件**。
 *
 * 缓冲（最近 [CAPACITY] 条）给设置里的"调试"页查看和复制；
 * 落盘那一份给用户拿去排查 —— 用户报过"调试模式的日志文件并未正常生成"，
 * 因为以前这里**只**有内存缓冲，界面却显示了一个 `logs/` 路径。
 *
 * ## 什么时候写文件
 *
 * 只有用户在设置里打开「记录运行日志」才写（[enabled]）。
 * 关闭时不写盘，内存里也只留 WARN 及以上，避免长期开着占内存。
 *
 * 打开的那一刻会把**已有缓冲整体倒进文件**：设置是异步读出来的，
 * 打开前那段（冷启动、读设置、建数据库）的日志本来会丢，
 * 倒一次就能让文件从这一次运行的开始就是完整的。
 *
 * ## 记什么
 *
 * 不只记刮削。凡是走 [log] 的都是"App 运行日志"：插件/规则更新、
 * 扫描与后台补全、遥控连接、设置变更、播放错误……
 * [CrashLog] 还会把 Activity 生命周期和未捕获异常也送进来
 * （见 [CrashLog.install]），这样一份文件就能还原"用户当时在干什么"。
 */
object AppLog {
    private const val CAPACITY = 2000

    /**
     * 日志文件上限。
     *
     * 超过就滚动一次：`app.log` → `app.log.1`（旧的 .1 直接删）。
     * 只留两份、每份 512KB，够放几万行，也不会让用户的存储被日志吃掉。
     */
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val buffer = ArrayDeque<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** 记录门槛：低于这个级别的日志直接丢弃。 */
    @Volatile
    var minLevel: LogLevel = LogLevel.INFO

    /**
     * 落盘开关（设置里的「记录运行日志」）。
     *
     * 打开的那一次会把缓冲倒进文件，见类注释。
     * `@Volatile`：写它的是主线程上的设置流，读它的是任意工作线程上的 [log]。
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val turnedOn = value && !field
            field = value
            if (turnedOn) runCatching { flushToFile() }
        }

    /** 日志文件（惰性创建，不在这里做 IO）。null 表示还没 [attach]。 */
    @Volatile
    private var logFile: File? = null

    private val fileLock = Any()

    /**
     * 记下日志文件的位置。由 [CrashLog.install] 在 `App.onCreate` 里调用 ——
     * 那里**只算一个路径字符串**，不建目录、不打开文件，所以不会把
     * "桌面移除图标时同步等我们应答"的那条启动路径拖慢。
     */
    fun attach(context: Context) {
        if (logFile != null) return
        logFile = runCatching {
            File(StoragePaths.externalRoot(context), LOG_DIR_NAME + "/" + LOG_FILE_NAME)
        }.getOrNull()
    }

    /** 日志文件的完整路径（设置页显示用）。没 attach 过就现算一个。 */
    fun filePath(context: Context): String {
        attach(context)
        return logFile?.absolutePath ?: ""
    }

    /** 日志文件当前大小（字节）；不存在返回 0。 */
    fun fileSize(context: Context): Long =
        runCatching { File(filePath(context)).takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L)

    /** 清空日志文件（连带滚动的 .1 一起删）。返回是否清成功。 */
    fun clearFile(context: Context): Boolean = synchronized(fileLock) {
        runCatching {
            val base = File(filePath(context))
            val rolled = File(base.absolutePath + ROLL_SUFFIX)
            rolled.delete()
            base.delete()
            true
        }.getOrDefault(false)
    }

    fun log(level: LogLevel, tag: String, message: String) {
        if (!shouldKeep(level)) return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        if (enabled) runCatching { appendToFile(entry.format()) }
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)

    fun e(tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) {
            message
        } else {
            message + "：" + error.javaClass.simpleName + ": " + error.message
        }
        log(LogLevel.ERROR, tag, text)
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** 复制用：按级别过滤后拼成纯文本。 */
    fun snapshotText(minLevel: LogLevel): String {
        val list = synchronized(buffer) { buffer.toList() }
        val filtered = list.filter { it.level.ordinal >= minLevel.ordinal }
        if (filtered.isEmpty()) return "（没有符合条件的日志）"
        return filtered.joinToString("\n") { it.format() }
    }

    /**
     * 记不记这一条。
     *
     * **只看级别，不看落盘开关**：内存缓冲一直留着（上限 [CAPACITY] 条），
     * 这样用户刚打开「记录运行日志」时，能把这一次运行**从头**倒进文件；
     * 之前按开关丢弃，文件的开头就缺了冷启动那一段 —— 而那一段恰恰最有用。
     * 落盘与否由 [log] 里单独判断 [enabled]。
     */
    private fun shouldKeep(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /** 把当前缓冲整体写进文件（打开开关时用一次）。 */
    private fun flushToFile() {
        val target = logFile ?: return
        val text = synchronized(buffer) { buffer.toList() }
        if (text.isEmpty()) return
        synchronized(fileLock) {
            write(target, text.joinToString("\n") { it.format() } + "\n", append = true)
        }
    }

    private fun appendToFile(line: String) {
        val target = logFile ?: return
        synchronized(fileLock) { write(target, line + "\n", append = true) }
    }

    /** 单次写入 + 滚动。所有异常由调用方的 runCatching 兜住。 */
    private fun write(target: File, text: String, append: Boolean) {
        target.parentFile?.mkdirs()
        if (target.isFile && target.length() + text.length > MAX_FILE_BYTES) {
            // 滚动：当前这份改名为 .1（旧的 .1 覆盖掉），新的一份从头开始写。
            val rolled = File(target.absolutePath + ROLL_SUFFIX)
            rolled.delete()
            target.renameTo(rolled)
        }
        if (append) target.appendText(text) else target.writeText(text)
    }

    fun timeText(time: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))

    /** 目录名/文件名：和设置页显示的是同一份来源，不会"界面说这儿、其实写那儿"。 */
    const val LOG_DIR_NAME = "logs"
    const val LOG_FILE_NAME = "app.log"
    private const val ROLL_SUFFIX = ".1"
}
