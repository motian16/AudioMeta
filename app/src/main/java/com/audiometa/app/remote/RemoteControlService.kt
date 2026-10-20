package com.audiometa.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.audiometa.app.MainActivity
import com.audiometa.app.R
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 遥控连接的前台保活服务。
 *
 * ## 为什么需要它
 *
 * 遥控连接是**进程级**的 WebSocket，但 Android 会在后台把没有前台服务的进程
 * 回收掉（尤其国产 ROM）。以前连接逻辑再健壮也没用：进程都没了，心跳和重连
 * 自然一起停。这里用一个 `connectedDevice` 类型的前台服务把进程钉住，
 * 系统清后台时会把它当成"正在使用的连接"而不是普通缓存进程。
 *
 * ## 它不是媒体通知
 *
 * 通知是 LOW 重要性、静音、不可滑掉的状态条，没有播放控制，也不注册
 * MediaSession/MediaStyle。关掉遥控开关时服务一起停，通知随之消失。
 *
 * ## 服务只做三件事
 *
 * 1. 进程活着：`startForeground` 钉住进程；
 * 2. 网络恢复时立刻 `nudge()`，不用等 60 秒退避；
 * 3. 在通知里显示当前连接/曲目状态。
 *
 * 它**不持有**连接本身 —— 连接仍是 `ServiceLocator.remoteControl` 的单例，
 * 所以 Activity 销毁/重建不会把连接带走。
 */
class RemoteControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Wi-Fi/蜂窝同时可用时 onAvailable 会连发；3 秒内只叫醒一次就够了。 */
    @Volatile
    private var lastNudgeAt: Long = 0L

    /**
     * 亮屏/解锁时叫醒连接：Doze 期间心跳可能被推迟，回前台第一件事应该是恢复链路，
     * 而不是等下一次 75 秒心跳超时。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            nudgeOnce("亮屏")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat(buildNotification("正在连接电脑…"))) {
            return START_NOT_STICKY
        }

        // 服务可能是系统杀进程后按 START_STICKY 拉起来的：重新按当前设置
        // 触发一次连接/重连，不依赖界面是否还活着。
        scope.launch {
            runCatching {
                val prefs = ServiceLocator.settings.settings.first().remote
                if (!prefs.enabled) {
                    stopSelf()
                    return@launch
                }
                ServiceLocator.remoteControl.apply(prefs)
                ServiceLocator.remoteControl.nudge()
            }.onFailure {
                AppLog.w(TAG, "保活服务恢复连接失败：" + it.message)
            }
        }

        registerNetworkWatcher()
        observeStatus()
        registerScreenWatcher()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        networkCallback?.let { callback ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        statusJob?.cancel()
        statusJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Android 12+ 后台启动限制：服务被系统拉起但此刻不允许转前台。
            // 必须主动停掉：startForegroundService 之后不转前台会在 5 秒后 ANR/崩溃。
            AppLog.w(TAG, "转前台失败（后台启动限制）：" + it.message)
            stopSelf()
        }.isSuccess
    }

    private fun observeStatus() {
        statusJob?.cancel()
        statusJob = scope.launch {
            combine(
                ServiceLocator.remoteControl.state,
                ServiceLocator.remoteControl.track
            ) { state, track -> state to track }.collect { (state, track) ->
                val text = when {
                    state is RemoteConnectionState.Connected && track.hasTrack ->
                        listOf(track.title, track.artist)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    state is RemoteConnectionState.Connected -> "已连接电脑"
                    state is RemoteConnectionState.Reconnecting ->
                        state.reason?.ifBlank { "正在重连…" } ?: "正在重连…"
                    state is RemoteConnectionState.Unauthorized -> "等待配对码"
                    else -> "远程控制已就绪"
                }
                updateNotification(text)
            }
        }
    }

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络刚恢复：退避循环可能还在睡，直接叫醒它。
                nudgeOnce("网络恢复")
            }
        }
        networkCallback = callback
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        }.onFailure {
            AppLog.w(TAG, "注册网络回调失败：" + it.message)
            networkCallback = null
        }
    }

    private fun registerScreenWatcher() {
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            AppLog.w(TAG, "注册亮屏回调失败：" + it.message)
        }
    }

    /** 多个来源（网络恢复/亮屏）共用去抖，避免同一秒里重复拨号。 */
    private fun nudgeOnce(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastNudgeAt < NUDGE_DEBOUNCE_MS) return
        lastNudgeAt = now
        AppLog.d(TAG, "唤醒重连：$reason")
        ServiceLocator.remoteControl.nudge()
    }

    private fun updateNotification(text: String) {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AudioMeta 远程控制")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.remote_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL_ID = "audiometa_remote"
        private const val NOTIFICATION_ID = 1004
        private const val NUDGE_DEBOUNCE_MS = 3_000L

        /** 用户打开遥控开关时调用（界面在前台，允许启动前台服务）。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteControlService::class.java)
                )
            }.onFailure {
                AppLog.w(TAG, "启动保活服务失败：" + it.message)
            }
        }

        /** 关掉遥控开关时调用。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteControlService::class.java)) }
        }
    }
}
