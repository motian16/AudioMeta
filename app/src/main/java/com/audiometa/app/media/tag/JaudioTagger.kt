package com.audiometa.app.media.tag

import com.audiometa.app.util.AppLog
import org.jaudiotagger.tag.TagOptionSingleton
import java.util.concurrent.atomic.AtomicBoolean

/**
 * jaudiotagger 的进程级初始化。
 *
 * **必须在任何一次读/写标签之前跑**：它默认用 `StandardArtwork` 处理图片，
 * 而那个类依赖 `javax.imageio.ImageIO` —— Android 上根本没有这个类，
 * 读内嵌图或者写封面时会抛 `NoClassDefFoundError`
 *（`Error` 不是 `Exception`，`catch (e: Exception)` 兜不住）。
 * 打开 Android 模式之后它改用 `AndroidArtwork`（走 BitmapFactory）。
 *
 * 为什么单独一个文件、而不是留在 `Application.onCreate` 里：
 *
 * 进程被系统顺手拉起来（桌面上移除图标、包管理器重新解析组件）时不该做
 * 任何多余的事。初始化本身很便宜，但挪进 [TagReader] / [TagWriter] 的
 * 初始化块之后，它就只在**真的要读标签**的那一刻发生 ——
 * 和"这两个谁先被用到"无关，也不会漏。
 */
internal object JaudioTagger {

    private val done = AtomicBoolean(false)

    fun ensureAndroidMode() {
        if (done.get()) return
        // 失败了不置位：下次读标签时再试一遍，总比一直用坏的模式强。
        val okay = runCatching { TagOptionSingleton.getInstance().setAndroid(true) }
            .onFailure { AppLog.w(TAG, "jaudiotagger 进入 Android 模式失败：" + it.message) }
            .isSuccess
        if (okay) done.set(true)
    }

    private const val TAG = "JaudioTagger"
}
