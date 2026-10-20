package com.audiometa.app.ui.component

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 一次性操作反馈统一走系统横幅（Toast）。
 *
 * 为什么不再用"页面里的常驻文字"：那些提示不会自己消失，
 * 要退出页面再进来才清掉，而且会占掉一块版面。
 * 操作类反馈（"已复制 N 项"、"这批没有新标签"）本来就是瞬时的，
 * 用系统 Toast 几秒后自动消失，不污染界面。
 *
 * 用法：状态里存一个一次性消息，[ToastEffect] 收到后弹一次并清空。
 */
@Composable
fun ToastEffect(message: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onConsumed()
        }
    }
}

/**
 * 记住一个"一次性提示"。
 *
 * 返回的 setter 直接调用即可，界面里配一个 [ToastEffect] 就完成了弹出 + 清空。
 */
class OneShotNotice {
    var text by mutableStateOf<String?>(null)
        private set

    fun show(message: String) {
        text = message
    }

    fun consume() {
        text = null
    }
}

@Composable
fun rememberOneShotNotice(): OneShotNotice = remember { OneShotNotice() }

/** 命令行式的小工具：把文本放进系统剪贴板。 */
fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
}
