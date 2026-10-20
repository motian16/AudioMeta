package com.audiometa.app.media.audio

import android.content.Context
import com.audiometa.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 静音跳过时间的后台预计算。
 *
 * 和歌词补全分开跑、排在它后面：歌词影响的是「搜索能不能用」，
 * 静音跳过只影响「播放开头顺不顺」，先来后到很清楚。
 *
 * 单线程足够：解码开头 12 秒大约几十毫秒一首，几百首也就十几秒。
 * 并发解码会抢硬件解码器，反而更慢。
 */
class SilenceIndexer(private val context: Context) {

    val progress = MutableStateFlow(SilenceIndexProgress())
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * 给这些曲目补算静音档案；已经算过的直接跳过。
     *
     * @param tracks 需要检查的曲目（id + 路径 + 标题）。
     */
    suspend fun refresh(tracks: List<SilenceTarget>) = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        cancelled.set(false)
        try {
            val pending = tracks.filter { !SilenceProfileStore.get(context, it.id).analyzed }
            if (pending.isEmpty()) {
                progress.value = SilenceIndexProgress(finished = true, message = "开头静音已全部预计算")
                return@withContext
            }
            progress.value = SilenceIndexProgress(
                running = true, total = pending.size, done = 0,
                message = "正在预计算开头静音"
            )
            // 键改成 trackId：档案现在按曲目 id 存，不再按路径。
            val batch = LinkedHashMap<Long, SilenceProfile>()
            var done = 0
            var skippable = 0
            pending.forEach { target ->
                if (cancelled.get()) return@forEach
                val profile = SilenceDetector.analyze(target.path)
                batch[target.id] = profile
                if (profile.hasSkip) skippable++
                done++
                progress.value = progress.value.copy(done = done, currentTitle = target.title)
                // 攒一批再落库：几百首逐个开事务没必要。
                if (batch.size >= FLUSH_EVERY) {
                    SilenceProfileStore.putAll(context, batch)
                    batch.clear()
                }
            }
            SilenceProfileStore.putAll(context, batch)
            progress.value = progress.value.copy(
                running = false,
                finished = true,
                currentTitle = "",
                message = buildString {
                    append(if (cancelled.get()) "已暂停，" else "预计算完成，")
                    append("共 " + done + " 首")
                    if (skippable > 0) append("，其中 " + skippable + " 首会在播放时跳过开头")
                }
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "静音预计算中断：" + t.message)
            progress.value = progress.value.copy(running = false, message = "预计算已中断")
        } finally {
            running.set(false)
        }
    }

    /** 曲库清理后同步清理档案，避免表无限长大。 */
    suspend fun prune(aliveIds: Set<Long>) = withContext(Dispatchers.IO) {
        SilenceProfileStore.prune(context, aliveIds)
    }

    private companion object {
        const val FLUSH_EVERY = 25
        const val TAG = "SilenceIndexer"
    }
}

/** 预计算要看的曲目。只带必要的三个字段，避免把整行都传进来。 */
data class SilenceTarget(val id: Long, val path: String, val title: String)

data class SilenceIndexProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val currentTitle: String = "",
    val message: String = "",
    val finished: Boolean = false
)
