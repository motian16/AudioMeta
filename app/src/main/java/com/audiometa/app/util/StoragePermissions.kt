package com.audiometa.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 存储相关权限的**唯一判断与跳转入口**。
 *
 * ## 为什么收拢到这里
 *
 * 以前同样的判断在三处各写了一份（媒体库来源、设置页权限行、权限引导页），
 * 表达式略有出入 —— 比如"API 30 以下算不算有所有文件访问"一处写成 true、
 * 另一处去查运行时权限。这种分叉迟早长成"界面说已授权、扫描却读不到"的
 * 自相矛盾，而且排查的人要同时读三个文件才能发现条件不一样。
 *
 * 判断逻辑只有一份，跳转也只有一份；调用方只认这里的结论。
 */
object StoragePermissions {

    /**
     * 有没有「所有文件访问」。
     *
     * 安卓 11（API 30）起，读 `/storage/emulated/0` 下任意目录都需要它；
     * 更早的版本没有这个概念，直接算已有。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 媒体音频读取权限（运行时授予的那种）。 */
    fun hasMediaAccess(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 现在能不能读媒体库。
     *
     * 「所有文件访问」也算有权限：拿到它以后查 MediaStore 不再需要音频权限。
     * 没有它时退回检查运行时的音频权限。
     */
    fun canReadMedia(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            return true
        }
        return hasMediaAccess(context)
    }

    /** 跳到本应用的「所有文件访问」授权页；ROM 没有这个页面时退回总入口。 */
    fun openAllFilesAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }
}
