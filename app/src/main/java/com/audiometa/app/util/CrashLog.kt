package com.audiometa.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常的落盘记录。
 *
 * **为什么要有这个文件**：应用在用户手上闪退时，开发者手上什么都没有 ——
 * 没有 adb、没有 logcat，用户能描述的只有"点了一下就没了"。
 * 前面几轮"扫描固定文件夹崩溃"的报告都卡在这里：看不到是哪一行抛的、
 * 抛的是什么，只能靠猜。有了这份文件，下次的报告只需要用户把
 * `Android/data/com.audiometa.app/files/crash.log` 发过来。
 *
 * 写入路径走 [StoragePaths.externalRoot]（外部目录不可用时它会退回私有目录），
 * 和设置页「调试」里显示的那一行是同一个来源。
 *
 * 刻意**不引入任何依赖**：崩溃处理程序里能少做一件事就少做一件，
 * 数据库、协程、日志缓冲此刻都可能是坏的，只有 `File` 和 `Throwable` 还能信。
 */
object CrashLog {

    /** 日志文件里最多攒多少字节，超了就从头砍掉一半。 */
    private const val MAX_BYTES = 256L * 1024L

    private var installed = false

    /** 崩溃日志文件。位置见 [StoragePaths.externalRoot] 的说明。 */
    fun file(context: Context): File = File(StoragePaths.externalRoot(context), FILE_NAME)

    /** 是否已经记到过崩溃（设置页用来说明"这里有没有东西可看"）。 */
    fun exists(context: Context): Boolean =
        runCatching { file(context).length() > 0L }.getOrDefault(false)

    /**
     * 取**还没有给用户看过**的上一次崩溃。
     *
     * 和 [recentText] 的区别：这里记住"哪一次崩溃已经提示过"（用 SharedPreferences
     * 存文件修改时间），同一次崩溃只打扰用户一次。崩溃处理程序只追加、不改时间戳，
     * 所以 mtime 变化 = 又崩了一次。
     *
     * 返回 null：没有崩溃、太久以前、或者这次已经提示过。
     */
    fun pendingReport(
        context: Context,
        withinMs: Long = 7L * 24 * 60 * 60 * 1000,
        maxChars: Int = 6_000
    ): String? = runCatching {
        val target = file(context)
        if (!target.isFile || target.length() <= 0L) return null
        if (System.currentTimeMillis() - target.lastModified() > withinMs) return null
        val seen = prefs(context).getLong(KEY_SEEN_MTIME, 0L)
        if (seen == target.lastModified()) return null
        val text = target.readText()
        if (text.isBlank()) null else text.takeLast(maxChars)
    }.getOrNull()

    /** 记下"这次崩溃已经提示过了"，下次启动不再弹同一个。 */
    fun markSeen(context: Context) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_SEEN_MTIME, file(context).lastModified())
                .apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("crash_report", Context.MODE_PRIVATE)

    private const val KEY_SEEN_MTIME = "seen_mtime"

    /**
     * 装上未捕获异常处理程序。
     *
     * 只在 [com.audiometa.app.App.onCreate] 里调用一次：装得太晚（例如等到
     * 第一帧之后）会漏掉启动阶段的崩溃，而这里只做一次赋值，没有 IO。
     *
     * **记完必须把异常交还给原来的处理程序**（那个才负责结束进程、
     * 显示系统的"应用已停止"对话框）。吞掉它会让应用卡在一个坏掉的进程里，
     * 用户看到的就成了"整个手机都不对劲"，比闪退更难查。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler(
            Handler(appContext, Thread.getDefaultUncaughtExceptionHandler())
        )
        /*
         * 日志这一行**刻意不带路径**。
         *
         * 以前这里是 AppLog.d(TAG, "崩溃日志已启用：" + file(appContext).absolutePath)，
         * 而 file() 内部要调 getExternalFilesDir —— 那是一次跨进程的存储查询，
         * 外部存储没挂载时还会阻塞。这一行正好落在 Application.onCreate 里，
         * 也就是桌面移除图标时**同步等我们应答**的那条路径上（见 App.kt 的说明）。
         * 路径在设置页「调试」里照样看得到，没必要在启动路径上算它。
         */
        AppLog.d(TAG, "崩溃日志已启用")

        /*
         * 运行日志的落盘位置，以及"用户当时在干什么"。
         *
         * 这里只做两件轻活：
         *   1. [AppLog.attach] 记一个路径字符串（不建目录、不开文件）；
         *   2. 挂 Activity 生命周期回调，把"打开/切走/回来"记进运行日志。
         *
         * 为什么挂在这里而不是 App.onCreate 里另写一段：`install` 本来就在
         * `App.onCreate` 里被调用一次，而它拿到的就是 Application —— 回调可以
         * 直接从这儿注册，不必再往启动路径上添活。用户要求"日志不止记录刮削，
         * APP 运行的日志也算上"，生命周期是最能还原现场的一类。
         */
        AppLog.attach(appContext)
        runCatching {
            (appContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
                LifecycleLogger
            )
        }
        AppLog.i(TAG, "运行日志已就绪：" + AppLog.filePath(appContext))
    }

    /**
     * Activity 生命周期 → 运行日志。
     *
     * 只记 onCreate / onStart / onResume / onPause / onStop / onDestroy 这六个
     * 关键点，并且**只记类名**（带包名的全名对排查没帮助，还占一行宽度）。
     * 级别是 DEBUG：用户把日志级别调到"一般"以上时它们自然不出现。
     */
    private object LifecycleLogger : android.app.Application.ActivityLifecycleCallbacks {
        private fun name(activity: android.app.Activity): String =
            activity.javaClass.simpleName

        override fun onActivityCreated(activity: android.app.Activity, saved: android.os.Bundle?) =
            AppLog.d(TAG, "界面创建：" + name(activity))

        override fun onActivityStarted(activity: android.app.Activity) =
            AppLog.d(TAG, "界面可见：" + name(activity))

        override fun onActivityResumed(activity: android.app.Activity) =
            AppLog.d(TAG, "界面前台：" + name(activity))

        override fun onActivityPaused(activity: android.app.Activity) =
            AppLog.d(TAG, "界面暂停：" + name(activity))

        override fun onActivityStopped(activity: android.app.Activity) =
            AppLog.d(TAG, "界面离开：" + name(activity))

        override fun onActivityDestroyed(activity: android.app.Activity) =
            AppLog.d(TAG, "界面销毁：" + name(activity))

        override fun onActivitySaveInstanceState(
            activity: android.app.Activity,
            outState: android.os.Bundle
        ) = Unit
    }

    /**
     * 真正的处理程序。
     *
     * 写成具名类而不是 lambda，是因为它要**持有上一个处理程序**并在处理完之后
     * 交还给它；用 lambda 的话"自己"在初始化表达式里还不可见。
     */
    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            // 写日志本身也可能出问题（磁盘满、目录没了）——
            // 那是"没记上"，绝不能因此改变崩溃本身的处理流程。
            runCatching { append(context, thread, error) }
            /*
             * 同时送一份进运行日志。
             *
             * 用户要的是"一份能看明白当时发生了什么"的日志文件，而崩溃那一行
             * 正好解释了"为什么后面的日志没了"。这里**只记一行**（类名 + 消息），
             * 完整堆栈仍然只在崩溃文件里 —— 崩溃处理程序里少做一件事都是赚的。
             */
            runCatching {
                AppLog.e(TAG, "未捕获异常（" + thread.name + "）：" +
                    error.javaClass.name + ": " + error.message)
            }
            if (previous != null && previous !== this) {
                previous.uncaughtException(thread, error)
            } else {
                // 没有可交还的处理程序（例如崩溃发生在系统装好它之前）。
                // 真到了这里也不能让进程带着坏状态继续跑。
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 把一次崩溃追加到文件末尾。任何异常都由调用方兜住。 */
    private fun append(context: Context, thread: Thread, error: Throwable) {
        val target = file(context)
        target.parentFile?.mkdirs()
        // 多个线程同时崩（或者崩溃时别的线程还在往里写）时不能让两段文本互相插进去。
        synchronized(lock) {
            trimIfTooBig(target)
            target.appendText(report(thread, error))
        }
    }

    /** 生成一段可读的崩溃记录。 */
    private fun report(thread: Thread, error: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val trace = StringWriter()
        // PrintWriter 要 flush 才会把内容推进 StringWriter（它有自动冲刷，
        // 但 println 之外的方法不一定走那条路），这里显式关一次最稳。
        PrintWriter(trace).use { error.printStackTrace(it) }
        return buildString {
            appendLine("========== " + time + " ==========")
            appendLine("线程：" + thread.name + "（id=" + thread.id + "）")
            appendLine("版本：" + versionText())
            appendLine("异常：" + error.javaClass.name + ": " + error.message)
            appendLine(trace.toString().trimEnd())
            appendLine()
        }
    }

    /**
     * 版本号。
     *
     * 崩溃现场最需要知道"用户装的是哪一版"，但这里**不能碰包管理器**：
     * 崩溃处理程序跑在可能已经半死的进程里，多一次 binder 调用就多一次
     * 卡住的机会。所以版本从 [ServiceLocator] 里那个已经算好的缓存取，
     * 取不到就算了。
     */
    private fun versionText(): String =
        runCatching { com.audiometa.app.di.ServiceLocator.versionName + "(" +
            com.audiometa.app.di.ServiceLocator.versionCode + ")" }.getOrDefault("未知")

    /** 超过上限就砍掉前半段：最需要的是**最近**这一次崩溃。 */
    private fun trimIfTooBig(target: File) {
        if (!target.isFile || target.length() <= MAX_BYTES) return
        val text = runCatching { target.readText() }.getOrNull() ?: return
        val half = text.length / 2
        // 从中间往后找第一个记录分隔行，避免把一条记录切成两半。
        val cut = text.indexOf(RECORD_SEPARATOR, half).takeIf { it > 0 } ?: half
        runCatching { target.writeText(text.substring(cut)) }
    }

    private const val FILE_NAME = "crash.log"
    private const val RECORD_SEPARATOR = "=========="
    private const val TAG = "CrashLog"

    private val lock = Any()
}
