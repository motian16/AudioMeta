package com.audiometa.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单。
 *
 * 前台服务能把进程钉住，但国产 ROM 还有一层"省电策略"会直接冻结/清理后台。
 * 让用户把本应用加进"不优化"名单是最标准、也最有效的一步。
 *
 * 不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 之外的任何特殊权限：
 * 只打开系统自带的确认页，用户自己决定。
 */
object BatteryOptimization {

    /** 当前是否已在白名单。读不到时按"已允许"处理，避免反复提示。 */
    fun isIgnoring(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * 打开系统的"忽略电池优化"确认页。
     *
     * 部分 ROM 裁掉了这个 Action，调用失败时返回 false，由界面退回
     * "应用信息 - 电池"页面，绝不抛 ActivityNotFoundException。
     */
    fun requestIgnore(context: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true

        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback) }.isSuccess
    }
}
