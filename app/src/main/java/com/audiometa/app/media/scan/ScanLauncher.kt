package com.audiometa.app.media.scan

import android.content.Context
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.util.AppLog

/**
 * 「发起一次曲库扫描」的**唯一入口**。
 *
 * ## 为什么必须有这一层
 *
 * 扫描要走前台服务（[ScanService]），而 `startForegroundService` 可能**同步抛异常**：
 * 服务没在 Manifest 里注册（这个坑真实发生过：声明丢了之后，每一次扫描都失败，
 * 界面永远停在"已扫描 0 个文件"）、系统限制后台拉起前台服务等。
 *
 * 以前每个调用点（设置页、引导页）各自 `runCatching` 一遍，引导页那处漏了，
 * 用户一点"开始首次扫描"就闪退。把启动收拢到这里，兜底只写一次，漏不掉。
 *
 * 失败原因会写进 [com.audiometa.app.domain.model.ScanProgress.message]，
 * 由界面上的扫描进度块显示。
 */
object ScanLauncher {

    private const val TAG = "ScanLauncher"

    fun start(
        context: Context,
        roots: List<String>,
        formats: Set<String>,
        excluded: Set<String>,
        minDuration: Long,
        excludeSubdirs: Boolean
    ) {
        runCatching {
            ScanService.start(context, roots, formats, excluded, minDuration, excludeSubdirs)
        }.onFailure { error ->
            AppLog.e(TAG, "扫描服务启动失败", error)
            ServiceLocator.scanner.progress.value =
                ServiceLocator.scanner.progress.value.copy(
                    running = false,
                    finished = true,
                    message = "扫描未能启动：" + describe(error)
                )
        }
    }

    /**
     * 启动异常翻译成一句话。系统异常的 message 是一长串英文
     * （"Not allowed to start service Intent { cmp=… }: Service not registered"），
     * 原样塞给用户等于没说；取冒号后的关键短语，至少能认出是哪类问题。
     */
    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: return error.javaClass.simpleName
        return message.substringAfterLast(": ").ifBlank { message }
    }
}
