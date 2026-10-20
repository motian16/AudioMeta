package com.audiometa.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 「关于」页要用的外部跳转。
 *
 * 都能返回"到底有没有跳成功"，因为界面要按结果决定是否弹提示：
 * 跳成功就静默（动作本身就是反馈），跳不成才用系统底部横幅提示一下。
 */
object AboutIntents {

    const val EMAIL = "motian16@qq.com"
    const val QQ_GROUP = "765569609"

    /**
     * 加群链接。
     *
     * 走的是 QQ 官方的加群页 qm.qq.com——**交给 QQ 自己打开**，
     * 而不是丢给浏览器：QQ 装了的话这个域名由 QQ 接管，直接就是群页面。
     */
    const val QQ_GROUP_URL = "https://qm.qq.com/q/QKY6Pwfoek"

    /** QQ 的包名。手机 QQ 和 TIM 都试。 */
    private val QQ_PACKAGES = listOf("com.tencent.mobileqq", "com.tencent.tim")

    /**
     * 发邮件到 [EMAIL]。支持点击后调用手机的邮箱客户端。
     *
     * 先试 mailto（会列出所有邮件客户端让用户选）；手机上没有配置任何邮箱时
     * mailto 会抛 ActivityNotFoundException，这时退回用浏览器打开 QQ 邮箱网页版。
     *
     * @return true 表示确实起来了某个能发邮件的应用。
     */
    fun sendEmail(context: Context, subject: String = "AudioMeta 反馈", body: String = ""): Boolean {
        val mailto = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$EMAIL")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            // 正文（元信息报告）直接填好：用户点「发送」就应该已经是一封完整的邮件。
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
        }
        if (mailto.resolveActivity(context.packageManager) != null) {
            if (startQuietly(context, mailto)) return true
        }
        // 没有邮件客户端：退回网页版邮箱。
        return openUrl(context, "https://mail.qq.com/")
    }

    /**
     * 打开 QQ 群页面。
     *
     * 按用户要求**直接调 QQ**，不再转到浏览器：
     * 1. 先试 QQ 官方的加群 scheme（`mqqapi://card/show_pslcard`），
     *    这个一步就能跳到群资料卡，最直接；
     * 2. 不行就退到 qm.qq.com 的加群链接，但**限定由 QQ 打开**（`setPackage`），
     *    这样仍然是 QQ 自己接管的群页面，不会落到浏览器；
     * 3. QQ 和 TIM 都没装才返回 false，调用方去复制群号 + 弹横幅。
     *
     * @return true 表示确实由 QQ 打开了。
     */
    fun openQqGroup(context: Context, groupNumber: String = QQ_GROUP): Boolean {
        val installed = QQ_PACKAGES.filter { isInstalled(context, it) }
        if (installed.isEmpty()) return false

        installed.forEach { packageName ->
            // 1) 官方加群 scheme：直接到群资料卡。
            val scheme = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                        "&uin=$groupNumber&card_type=group&source=qrcode"
                )
            ).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startQuietly(context, scheme)) return true

            // 2) 退到加群链接，但仍然限定 QQ 打开。
            val link = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startQuietly(context, link)) return true
        }
        return false
    }

    /** 起来就 true，起不来（没装 / 被拦）就 false，不往外抛异常。 */
    private fun startQuietly(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.recoverCatching { t ->
        if (t is ActivityNotFoundException) false else throw t
    }.getOrDefault(false)

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 通用外链：系统默认浏览器。 */
    fun openUrl(context: Context, url: String): Boolean = startQuietly(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
