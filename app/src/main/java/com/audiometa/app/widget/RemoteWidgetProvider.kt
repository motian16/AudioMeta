package com.audiometa.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.audiometa.app.MainActivity
import com.audiometa.app.R
import com.audiometa.app.data.prefs.RemoteConnectionState
import com.audiometa.app.data.prefs.RemoteTrack
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.remote.RemoteTrackResolver
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面小组件：看电脑上正在放什么，并直接遥控。
 *
 * ## 为什么是四个 provider，而不是"一个组件 + 一个风格开关"
 *
 * 上一版只有一个组件，风格存在设置里（`AppSettings.widgetStyle`）。用户的实际
 * 体验是：桌面上只能放**一个**布局，改风格要去设置页翻，而且改完桌面上那块
 * 不会跟着变（要等下次播放状态变化才重画）。现在改成四个独立组件 ——
 * 桌面的「添加小组件」列表里就是四条，想用哪种放哪种，还能同时放好几块
 * （例如桌面一块歌词、别处一块极简）。
 *
 * 做法是：本文件里的 [AudioMetaWidgetProvider] 是抽象基类，四个子类各自只
 * 回答一件事 —— 用哪张布局（[layoutRes]）。回调、遥控、刷新、渲染全部在基类里。
 * 四个子类必须在 AndroidManifest 里各声明一个 `<receiver>`，否则桌面的
 * 组件列表里看不到它们（系统是照着 manifest 找 provider 的）。
 *
 * ## 卡片那个子类为什么还叫 RemoteWidgetProvider
 *
 * 老版本只有一个组件，类名就是 `RemoteWidgetProvider`。桌面上**已经放着**的组件
 * 实例在系统里记的是 `ComponentName(包名, com.audiometa.app.widget.RemoteWidgetProvider)`，
 * 这个类名一旦删掉，那块组件升级后会显示"加载失败"，用户只能删了重加。
 * 所以让"卡片"这个子类**沿用老类名**：升级后旧实例直接变成新的卡片布局，
 * 什么都不用做。另外三个新名字。
 *
 * ## 布局契约（改布局前务必先看这段）
 *
 * 四张布局（`widget_card` / `widget_immersive` / `widget_compact` /
 * `widget_lyrics`）**必须声明完全相同的 id 集合**。原因是 RemoteViews 的机制：
 * 代码这边只是把"给 id=xxx 设置文字"打包成一串动作，真正 findViewById 发生在
 * **桌面进程**里 inflate 的时候；某个 id 在那张布局里不存在，那里就是 null，
 * 整块部件会变成 "Problem loading widget"，而不是安安静静少显示一行。
 *
 * 所以每张布局里都声明了全部 14 个 id（歌词那几行在不用的布局里是 0dp + GONE）。
 * 只增不改：往里加 id 时，四张布局一起加。
 *
 * ## 尺寸适配
 *
 * 尺寸从 [AppWidgetManager.getAppWidgetOptions] 现读（见 [readSize]），
 * 宽度和**高度**都参与判断：这版按"能放几行"决定显示哪些元素。
 * 布局里该撑开的地方都用 `layout_weight`，所以部件被拉大时是内容铺满，
 * 不会在底部留一块空白 —— 这正是用户报告的问题之一。
 *
 * ## 进度条与歌词从哪来
 *
 * 宿主（Salt Player API dev20）不推总时长，所以进度条的 max 只能取**本机曲库
 * 匹配到的时长**（[RemoteTrackResolver.resolve] 的 `durationMs`）；匹配不到就
 * 整行 `GONE`，不画一条永远不动的假进度条。位置取 PC 推的
 * `RemoteControlClient.track.value.positionMs`。
 *
 * 歌词同样来自本机匹配（内嵌 / 外挂 / 兜底），当前行按播放位置实时取；
 * PC 推的 `track.lyricLine` 只作为本机没匹配到时的兜底。
 * [RemoteTrackResolver.resolve] 是 suspend 且要读文件，**只能在协程的 IO 线程里调**，
 * 绝不能放在 `onUpdate` 里同步调 —— 组件回调跑在主线程，读盘会把桌面卡住。
 */
abstract class AudioMetaWidgetProvider : AppWidgetProvider() {

    /** 这个组件用哪张布局。四个子类的**唯一**差异。 */
    abstract val layoutRes: Int

    /**
     * 排版风格。
     *
     * 由 [layoutRes] 反推，子类不用再声明第二遍 —— 少一处"加了布局忘了写风格"的
     * 地方，也保证布局和排版规则永远对得上。
     */
    internal val flavor: WidgetFlavor
        get() = when (layoutRes) {
            R.layout.widget_immersive -> WidgetFlavor.IMMERSIVE
            R.layout.widget_compact -> WidgetFlavor.COMPACT
            R.layout.widget_lyrics -> WidgetFlavor.LYRICS
            else -> WidgetFlavor.CARD
        }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            // 单个实例出错不能影响其它实例，也不能把系统回调的线程带崩。
            runCatching { render(context, appWidgetManager, id, this) }
                .onFailure { AppLog.w(WIDGET_TAG, "组件更新回调失败：" + it.message) }
        }
    }

    /**
     * 尺寸变了就重画。
     *
     * 用户报告的"不会根据尺寸调节而适配"就是这里没跟着新尺寸重画：老代码只读了
     * 宽度，把部件拉高之后内容还是那几行，底部留一块空白。这里不直接用
     * [newOptions]，而是让 [render] 现读一次 options —— 两条路径（初次绑定 /
     * 尺寸变化）读的是同一份数据，不会出现"某次回调少给了某个键"的情况。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        runCatching { render(context, appWidgetManager, appWidgetId, this) }
            .onFailure { AppLog.w(WIDGET_TAG, "组件尺寸变化重画失败：" + it.message) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 组件按钮发来的动作
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (ServiceLocator.remoteControl.track.value.playing) {
                    ServiceLocator.remoteControl.pause()
                } else {
                    ServiceLocator.remoteControl.play()
                }
            }
            ACTION_NEXT -> ServiceLocator.remoteControl.next()
            ACTION_PREV -> ServiceLocator.remoteControl.previous()
            ACTION_REFRESH -> ServiceLocator.remoteControl.requestState()
            ACTION_OPEN -> {
                openApp(context)
                return
            }
            else -> return
        }
        // 点完按钮立刻按新状态重画一次，不等服务端回推。
        refreshAllInstances(context)
    }

    companion object {
        const val ACTION_TOGGLE = "com.audiometa.app.widget.TOGGLE"
        const val ACTION_NEXT = "com.audiometa.app.widget.NEXT"
        const val ACTION_PREV = "com.audiometa.app.widget.PREV"
        const val ACTION_REFRESH = "com.audiometa.app.widget.REFRESH"

        /** 点组件主体：打开应用（不是刷新，见 [openApp] 的说明）。 */
        const val ACTION_OPEN = "com.audiometa.app.widget.OPEN"

        /**
         * 让组件跟随播放状态自动重画。
         *
         * 在 [com.audiometa.app.App] 里启动一次，进程活着就一直跑：
         * 曲目 / 播放状态 / 播放位置一变就刷新**四个** provider 的所有实例，
         * 用户不用手动点刷新，进度条也会跟着走。
         *
         * 注意：companion 不会被 Kotlin 继承，所以 `App.kt` 里必须写
         * `AudioMetaWidgetProvider.observe(this)`；写成某个子类（哪怕是旧名字
         * `RemoteWidgetProvider`）是编译不过的。
         */
        fun observe(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                combine(
                    ServiceLocator.remoteControl.track,
                    ServiceLocator.remoteControl.state
                ) { track: RemoteTrack, state: RemoteConnectionState -> track to state }
                    .collect {
                        runCatching { refreshAllInstances(appContext) }
                            .onFailure { AppLog.w(WIDGET_TAG, "小组件自动重画失败：" + it.message) }
                    }
            }
            // 插件正常播放时不再每秒推 state，组件进度条要靠本机每秒重画一次推进。
            // 这是本地 RemoteViews 刷新，不走网络。
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    if (ServiceLocator.remoteControl.track.value.playing &&
                        ServiceLocator.remoteControl.state.value.isLive
                    ) {
                        runCatching { refreshAllInstances(appContext) }
                            .onFailure { AppLog.w(WIDGET_TAG, "小组件进度重画失败：" + it.message) }
                    }
                }
            }
        }

        /**
         * 立刻重画桌面上所有组件实例（四种风格都刷）。
         *
         * 设置页需要"改完设置回桌面就能看到新样子"时调用。
         * 只读组件状态、只调 updateAppWidget，**不写任何组件启用状态** ——
         * 那才是"桌面重启"的来源。
         *
         * `fun refreshAllInstances(context: Context)` 的签名保持不变，
         * 只是宿主从 RemoteWidgetProvider 换成了基类：
         * 设置页里那一行要写成 `AudioMetaWidgetProvider.refreshAllInstances(context)`。
         */
        fun refreshAllInstances(context: Context) {
            val appContext = context.applicationContext
            val mgr = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
            for (provider in META_AUDIO_WIDGETS) {
                val cls = provider::class.java
                // 每个 provider 单独兜异常：一块组件已经被用户删掉是很正常的事
                //（getAppWidgetOptions 对已经不存在的 id 会抛 IllegalArgumentException），
                // 不能让另外三种风格跟着一起不刷新。
                runCatching {
                    val ids = mgr.getAppWidgetIds(ComponentName(appContext, cls))
                    if (ids.isEmpty()) return@runCatching
                    // 直接把每个实例重画一遍，不再绕一圈广播：广播要等系统调度，
                    // 换歌之后组件常常慢半拍。
                    for (id in ids) render(appContext, mgr, id, provider)
                }.onFailure {
                    AppLog.w(WIDGET_TAG, "重画小组件失败（" + cls.simpleName + "）：" + it.message)
                }
            }
        }
    }
}

/**
 * 卡片：左边封面、右边文字，进度条与一排按钮在下面。信息最全。
 *
 * **类名沿用老版本的 `RemoteWidgetProvider`**（见文件头注释）：
 * 桌面上已经放着的那块旧组件在系统里记的就是这个 ComponentName，
 * 保留它，旧实例升级后不用重加就变成新的卡片布局。
 *
 * 默认 2x2（拉宽到 4x2 会补出上一首 / 下一首）。
 */
class RemoteWidgetProvider : AudioMetaWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_card
}

/**
 * 沉浸封面：整块就是专辑封面，文字与按钮压在底部渐变上。
 *
 * 默认 2x2。封面永远 `match_parent`，所以放到多大都不会留空白。
 */
class WidgetImmersiveProvider : AudioMetaWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_immersive
}

/**
 * 极简：小封面 + 曲名 + 播放键，背景几乎透明，最小 2x1。
 *
 * 默认 2x1（1 格高放不下进度条，所以拉大到 2x2 以上才补进度条与上一首 / 下一首）。
 */
class WidgetCompactProvider : AudioMetaWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_compact
}

/** 歌词优先：歌词大字居中，带原文 / 译文，进度条与三个按钮。默认 4x2。 */
class WidgetLyricsProvider : AudioMetaWidgetProvider() {
    override val layoutRes: Int = R.layout.widget_lyrics
}

/** 四种排版。只用来在渲染时分支，不存进设置 —— 风格是"选了哪个组件"，不是设置项。 */
internal enum class WidgetFlavor { CARD, IMMERSIVE, COMPACT, LYRICS }

/**
 * 四个组件，按"桌面上出现的顺序"排。
 *
 * 这里用的是**实例**，不是 `Class` + 反射：反射在 R8 之后要额外 keep 规则，
 * 而且布局 id 会在"子类的 layoutRes"和"这张表"里各写一份，容易改漏一处。
 * 这些实例只当"类 + 布局 id"的载体用，不会收到任何回调（系统用它自己创建的实例）。
 *
 * 顺序和 AndroidManifest 里四个 `<receiver>` 的顺序一致，方便对照。
 */
private val META_AUDIO_WIDGETS: List<AudioMetaWidgetProvider> = listOf(
    RemoteWidgetProvider(),
    WidgetImmersiveProvider(),
    WidgetCompactProvider(),
    WidgetLyricsProvider()
)

/**
 * 点组件主体 = 打开应用。
 *
 * 这是"桌面图标被移除之后还能进应用"的**唯一**退路：用户在小米澎湃上把图标从桌面
 * 移除（系统把启动组件置成 disabled）之后，桌面上的组件仍然在，点一下就能进应用。
 * 应用内已经没有任何"恢复/隐藏图标"的入口（那个隐藏按钮曾把用户关在应用外面，
 * 整个功能已删除），所以这条退路要留着。
 */
private fun openApp(context: Context) {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    runCatching { context.startActivity(intent) }
        .onFailure { AppLog.w(WIDGET_TAG, "从小组件打开应用失败：" + it.message) }
}

/**
 * 重画一块组件。
 *
 * 全程在协程里：先在 IO 上把"本地匹配到的封面 / 时长"取好，再回主线程画。
 * 绝不在这里同步读盘 —— 那会让桌面在等 binder 应答时被拖住。
 *
 * 尺寸在**进协程之前**读：读一次 options 是一次 binder 调用，而且进协程之后再读
 * 可能用户已经又拉了一次，画出来的和刚拿到的那次回调对不上。
 */
private fun render(
    context: Context,
    mgr: AppWidgetManager,
    widgetId: Int,
    provider: AudioMetaWidgetProvider
) {
    val appContext = context.applicationContext
    val size = readSize(appContext, mgr, widgetId)
    val providerClass = provider::class.java
    val layoutRes = provider.layoutRes
    val flavor = provider.flavor

    scope.launch {
        val track = ServiceLocator.remoteControl.track.value
        val connection = ServiceLocator.remoteControl.state.value
        val snapshot = snapshotFor(appContext, track)
        val views = buildViews(
            context = appContext,
            providerClass = providerClass,
            layoutRes = layoutRes,
            flavor = flavor,
            size = size,
            track = track,
            connection = connection,
            snapshot = snapshot
        )
        runCatching { mgr.updateAppWidget(widgetId, views) }
            .onFailure { AppLog.w(WIDGET_TAG, "小组件更新失败：" + it.message) }
    }
}

/**
 * 组件当前尺寸，两个方向都要。
 *
 * 只按宽度判断是上一版最大的问题：宽度没变、只把高度拉大时什么都不做，
 * 于是"放到最大还是那几行 + 底下一块空白"。
 */
private class WidgetSize(val widthDp: Int, val heightDp: Int) {

    /** 宽到能把"上一首 / 播放 / 下一首"和文字排在一行（4 列起）。 */
    val wide: Boolean get() = widthDp >= 200

    /**
     * 高到能多放一行（副标题 / 进度条 / 歌词）。大约 2 行格子起。
     *
     * 130dp 这个数是从两种排布算出来的：一行格子实际只有 60~100dp，
     * 两行通常 150~200dp。1 格高时"曲名 + 进度条 + 三个按钮"根本排不下，
     * 硬塞的结果就是把曲名挤成半行 —— 所以进度条和歌词都等 tall 才出现。
     */
    val tall: Boolean get() = heightDp >= 130
}

/**
 * 读组件当前尺寸。
 *
 * 系统的四个 OPTION 不是"最小 / 最大允许值"，而是**两个方向上的当前尺寸**：
 * 竖屏时用 `(MIN_WIDTH, MAX_HEIGHT)`，横屏时用 `(MAX_WIDTH, MIN_HEIGHT)`
 * （横屏桌面更宽、更矮）。只读 `MIN_WIDTH` 是最常见的写法，但那样在横屏下
 * 会把 4x2 的部件当成 2x2 画 —— 用户看到的就是"拉到最大还是小布局"。
 *
 * 各家桌面对这几个键的填充并不完全一致，所以每个值都留了回退：取不到就退回
 * 另一个，都取不到就按 0 处理（渲染时会走最小尺寸的形态，不会崩）。
 */
private fun readSize(context: Context, mgr: AppWidgetManager, widgetId: Int): WidgetSize {
    val options = runCatching { mgr.getAppWidgetOptions(widgetId) }.getOrNull()
        ?: return WidgetSize(0, 0)

    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val width = (if (landscape) maxWidth else minWidth).takeIf { it > 0 }
        ?: maxWidth.takeIf { it > 0 }
        ?: minWidth
    val height = (if (landscape) minHeight else maxHeight).takeIf { it > 0 }
        ?: minHeight.takeIf { it > 0 }
        ?: maxHeight
    return WidgetSize(width, height)
}

/**
 * 一首歌里**与时间无关**的解析结果：封面、本机时长。
 *
 * 为什么要缓存：播放位置一变（`pos` 每秒都在推）就要重画，而
 * [RemoteTrackResolver.resolve] 里的 `loadCover` 每次都会真的去解码一遍封面文件。
 * 同一首歌只解析一次，纯位置跳动时直接用缓存 —— 否则每秒解码一张专辑图，
 * 低端机上组件会明显卡顿。
 *
 * 歌词**不进缓存**：它跟着播放位置走，而且是纯内存查表（见 [lyricFrameAt]）。
 */
private class TrackSnapshot(val key: String, val cover: Bitmap?, val durationMs: Long)

/** 同一首歌的判定键：五个字段都一样才算还是这首歌。 */
private fun trackKey(track: RemoteTrack): String =
    listOf(track.id, track.title, track.artist, track.album, track.path).joinToString("|")

/**
 * 上一支歌的解析结果。
 *
 * 只留一条：组件一次只会显示"当前正在放的那首"，多留没意义。
 * 并发写最多让两处重复解析一次，不会读到半个对象，所以不加锁。
 */
@Volatile
private var cachedSnapshot: TrackSnapshot? = null

/** 取当前曲目的封面 / 时长；同一首歌直接复用上次的结果。 */
private suspend fun snapshotFor(context: Context, track: RemoteTrack): TrackSnapshot {
    val key = trackKey(track)
    cachedSnapshot?.takeIf { it.key == key }?.let { return it }

    if (!track.hasTrack) {
        return TrackSnapshot(key, null, 0L).also { cachedSnapshot = it }
    }

    val now = withContext(Dispatchers.IO) {
        // 匹配不到、解析失败都只是"没有封面 / 没有时长"，不影响其它内容。
        runCatching { RemoteTrackResolver.resolve(context, track) }.getOrNull()
    }
    val snapshot = TrackSnapshot(
        key = key,
        cover = now?.cover?.let { shrinkCover(it) },
        durationMs = now?.durationMs ?: 0L
    )
    cachedSnapshot = snapshot
    return snapshot
}

/** 某一时刻的四行歌词：上一行 / 当前行 / 下一行 / 翻译。 */
private class LyricFrame(
    val previous: String = "",
    val current: String = "",
    val next: String = "",
    val translation: String = ""
)

/**
 * 按播放位置取歌词行。
 *
 * [RemoteTrackResolver.trioAt] 只查**内存缓存**（`resolve` 已经把这支歌的歌词装进去了），
 * 不读文件，所以可以跟着每秒的位置刷新调用。
 * 缓存里没有（例如别处刚解析了另一首、或者本机没这首歌）就退回宿主推的
 * `track.lyricLine` —— 显示得糙一点，但不会一片空白。
 */
private fun lyricFrameAt(positionMs: Long, fallbackLine: String): LyricFrame {
    val trio = runCatching { RemoteTrackResolver.trioAt(positionMs) }
        .getOrDefault(RemoteTrackResolver.LyricTrio())
    return LyricFrame(
        previous = trio.previous,
        current = trio.current.ifBlank { fallbackLine },
        next = trio.next,
        translation = trio.translation
    )
}

/**
 * 组件上的播放位置：以最近一帧 state 为基线，按手机墙钟往前推。
 *
 * 插件正常播放时不再每秒发 state，所以不能直接拿 `track.positionMs` 画进度条；
 * 收到暂停/跳转/播放结束的新 state 时会重新给基线。
 */
private fun widgetPosition(track: RemoteTrack, durationMs: Long): Long {
    if (!track.playing || track.positionUpdatedAt <= 0L) return track.positionMs
    val elapsed = (System.currentTimeMillis() - track.positionUpdatedAt).coerceAtLeast(0L)
    val position = track.positionMs + elapsed
    return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
}

/** 按风格挑布局、按尺寸填内容、挂点击。纯内存操作，可以反复调用。 */
private fun buildViews(
    context: Context,
    providerClass: Class<out AppWidgetProvider>,
    layoutRes: Int,
    flavor: WidgetFlavor,
    size: WidgetSize,
    track: RemoteTrack,
    connection: RemoteConnectionState,
    snapshot: TrackSnapshot
): RemoteViews {
    val views = RemoteViews(context.packageName, layoutRes)
    val hasTrack = track.hasTrack
    val position = widgetPosition(track, snapshot.durationMs)

    // ---- 标题：没有在放的歌时显示连接状态，不让组件变成一块空白 ----
    val title = when {
        hasTrack && track.title.isNotBlank() -> track.title
        // 有曲目但没有标题（老宿主只给了路径）：拿文件名顶上，总比空着强。
        hasTrack -> track.path.substringAfterLast('\\').substringAfterLast('/')
        connection.isLive -> context.getString(R.string.widget_idle)
        else -> context.getString(R.string.widget_disconnected)
    }
    views.setTextViewText(R.id.widget_title, title)

    val subtitle = if (hasTrack) {
        listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
    } else {
        connection.label
    }
    views.setTextViewText(R.id.widget_subtitle, subtitle)

    // ---- 歌词 ----
    val frame = if (hasTrack) lyricFrameAt(position, track.lyricLine) else LyricFrame()
    val hasLyrics = frame.current.isNotBlank() || frame.translation.isNotBlank()
    views.setTextViewText(R.id.widget_lyric_prev, frame.previous)
    views.setTextViewText(
        R.id.widget_lyric_current,
        frame.current.ifBlank { context.getString(R.string.widget_no_lyrics) }
    )
    views.setTextViewText(R.id.widget_lyric_translation, frame.translation)
    views.setTextViewText(R.id.widget_lyric_next, frame.next)

    // ---- 封面：本地匹配到的专辑图；没有就用占位图 ----
    if (snapshot.cover != null) {
        views.setImageViewBitmap(R.id.widget_cover, snapshot.cover)
    } else if (flavor == WidgetFlavor.IMMERSIVE) {
        // 沉浸风格里占位图会把整块涂成一块底色，比"没有图"更难看，所以只清空。
        views.setImageViewBitmap(R.id.widget_cover, null)
    } else {
        views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
    }

    // ---- 进度条：位置来自 PC，总时长来自本机曲库匹配 ----
    // 匹配不到时长就整行收起来：宁可不显示，也不画一条永远停在 0 的假进度条。
    // 太矮（1 格高）也不显示：那一行会挤掉曲名，用户要的是"小尺寸只显示一部分"。
    // 本机曲库匹配到的时长最准；没有时用插件协议 v2 带的 duration 兜底。
    val duration = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs
    val showProgress = hasTrack && duration > 0L && size.tall &&
        (flavor != WidgetFlavor.COMPACT || size.wide)
    if (showProgress) {
        val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        views.setProgressBar(
            R.id.widget_progress,
            max,
            position.coerceIn(0L, duration).toInt(),
            false
        )
        views.setTextViewText(
            R.id.widget_time,
            formatTime(position) + " / " + formatTime(duration)
        )
    }
    views.setViewVisibility(R.id.widget_progress_row, visibility(showProgress))

    // ---- 播放/暂停图标 ----
    views.setImageViewResource(
        R.id.widget_toggle,
        if (track.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    )

    // ---- 按风格、按尺寸决定显示哪些元素 ----
    applyLayoutRules(views, flavor, size, hasTrack, hasLyrics, frame.translation.isNotBlank())

    // ---- 点击 ----
    views.setOnClickPendingIntent(
        R.id.widget_toggle,
        action(context, providerClass, AudioMetaWidgetProvider.ACTION_TOGGLE)
    )
    views.setOnClickPendingIntent(
        R.id.widget_prev,
        action(context, providerClass, AudioMetaWidgetProvider.ACTION_PREV)
    )
    views.setOnClickPendingIntent(
        R.id.widget_next,
        action(context, providerClass, AudioMetaWidgetProvider.ACTION_NEXT)
    )
    // 整块 = 打开应用（图标被移除后的退路，见 openApp）。
    views.setOnClickPendingIntent(
        R.id.widget_root,
        action(context, providerClass, AudioMetaWidgetProvider.ACTION_OPEN)
    )

    return views
}

/**
 * 每种排版在每种尺寸下显示什么。
 *
 * 规则集中在这里，是为了让"什么尺寸显示什么"一眼可查，而不是散在几十行
 * `setViewVisibility` 里。四张布局的 id 相同，所以这里对每个 id 都必须给出
 * 明确的可见性 —— 漏掉一个，它在换了风格的那次重画里就会保持上一次的状态。
 */
private fun applyLayoutRules(
    views: RemoteViews,
    flavor: WidgetFlavor,
    size: WidgetSize,
    hasTrack: Boolean,
    hasLyrics: Boolean,
    hasTranslation: Boolean
) {
    // 上一首 / 下一首：窄的时候收起来，只留播放键（用户要的"小尺寸只显示一部分"）。
    val showStep = size.wide && hasTrack

    when (flavor) {
        // 卡片：文字三行，进度条 + 一排按钮。
        WidgetFlavor.CARD -> {
            views.setViewVisibility(R.id.widget_subtitle, visibility(size.wide || size.tall || !hasTrack))
            // 歌词行只在"又大又有歌词"时补出来，小尺寸不挤掉曲名。
            views.setViewVisibility(
                R.id.widget_lyric_current,
                visibility(size.wide && size.tall && hasLyrics)
            )
            views.setViewVisibility(R.id.widget_lyric_prev, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_next, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_translation, View.GONE)
            views.setViewVisibility(R.id.widget_prev, visibility(showStep))
            views.setViewVisibility(R.id.widget_next, visibility(showStep))
        }

        // 沉浸：封面铺满，底部压着标题 + 进度 + 按钮；够高时歌词也压上去。
        WidgetFlavor.IMMERSIVE -> {
            views.setViewVisibility(R.id.widget_subtitle, visibility(size.wide || size.tall || !hasTrack))
            views.setViewVisibility(
                R.id.widget_lyric_current,
                visibility(size.tall && hasLyrics)
            )
            views.setViewVisibility(R.id.widget_lyric_prev, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_next, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_translation, View.GONE)
            views.setViewVisibility(R.id.widget_prev, visibility(showStep))
            views.setViewVisibility(R.id.widget_next, visibility(showStep))
        }

        // 极简：2x1 只有"小封面 + 曲名 + 播放键"，拉宽才补副标题、上一首 / 下一首。
        WidgetFlavor.COMPACT -> {
            views.setViewVisibility(R.id.widget_subtitle, visibility(size.wide || !hasTrack))
            views.setViewVisibility(
                R.id.widget_lyric_current,
                visibility(size.wide && hasLyrics)
            )
            views.setViewVisibility(R.id.widget_lyric_prev, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_next, View.GONE)
            views.setViewVisibility(R.id.widget_lyric_translation, View.GONE)
            views.setViewVisibility(R.id.widget_prev, visibility(showStep))
            views.setViewVisibility(R.id.widget_next, visibility(showStep))
        }

        // 歌词优先：歌词区永远在（没歌词时显示"暂无歌词"占位，而不是塌掉中间那块）。
        WidgetFlavor.LYRICS -> {
            views.setViewVisibility(R.id.widget_subtitle, visibility(size.wide || !hasTrack))
            views.setViewVisibility(R.id.widget_lyric_current, View.VISIBLE)
            // 上一行 / 下一行 / 译文都要高度：放不下时只留当前行。
            views.setViewVisibility(
                R.id.widget_lyric_prev,
                visibility(size.tall && hasLyrics)
            )
            views.setViewVisibility(
                R.id.widget_lyric_next,
                visibility(size.tall && hasLyrics)
            )
            views.setViewVisibility(
                R.id.widget_lyric_translation,
                visibility(size.tall && hasTranslation)
            )
            views.setViewVisibility(R.id.widget_prev, visibility(showStep))
            views.setViewVisibility(R.id.widget_next, visibility(showStep))
        }
    }
}

private fun visibility(show: Boolean): Int = if (show) View.VISIBLE else View.GONE

/** 毫秒 → `m:ss`（超过一小时才写 `h:mm:ss`）。手写拼接，不走 Locale。 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    val mm = if (hours > 0L) minutes.toString().padStart(2, '0') else minutes.toString()
    return if (hours > 0L) "$hours:$mm:${seconds.toString().padStart(2, '0')}"
    else "$mm:${seconds.toString().padStart(2, '0')}"
}

/**
 * 把封面缩到能塞进 RemoteViews 的大小。
 *
 * 用 RGB_565：一个像素两字节，240×240 只要 112KB，跨进程传得动；
 * ARGB_8888 是它的两倍，慢一点的低端机上偶尔会把事务撑爆。
 * 现在一秒可能重画一次（进度条），所以这张图**不能**不压。
 */
private fun shrinkCover(source: Bitmap): Bitmap? = runCatching {
    val longest = maxOf(source.width, source.height)
    if (longest <= 0) return@runCatching null
    val scale = WIDGET_COVER_PX.toFloat() / longest
    val target = if (scale >= 1f) {
        source
    } else {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
    if (target.config == Bitmap.Config.RGB_565) target else target.copy(Bitmap.Config.RGB_565, false)
}.getOrNull()

/**
 * 构造一个"发回**这个子类**组件"的 PendingIntent。
 *
 * `Intent(context, providerClass)` 里的类必须是具体子类：写死基类的话，
 * 广播会送到基类（它没有在 manifest 里注册），子类永远收不到点击 ——
 * 表现就是"按钮点不动"。
 *
 * 每个动作 + 每个组件单独一个 requestCode，否则 PendingIntent 会被复用成同一个，
 * 点哪个按钮效果都一样（这是组件开发常见的坑）。
 */
private fun action(
    context: Context,
    providerClass: Class<out AppWidgetProvider>,
    action: String
): PendingIntent {
    val intent = Intent(context, providerClass).setAction(action)
    val requestCode = (providerClass.name + '#' + action).hashCode()
    return PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** 传进 RemoteViews 的封面最长边（像素），见 [shrinkCover] 的说明。 */
private const val WIDGET_COVER_PX = 240

private const val WIDGET_TAG = "AudioMetaWidget"

/** 画组件用的进程级协程作用域：进程活着就一直有效。 */
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
