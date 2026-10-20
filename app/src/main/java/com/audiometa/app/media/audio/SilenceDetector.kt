package com.audiometa.app.media.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.util.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 开头静音的检测结果。
 *
 * [skipMs] 之所以叫"预计算"，是因为它**不该在按下播放时才算**：
 * 解码开头几百毫秒来测能量要几十到几百毫秒，正好落在"点了播放却迟迟没声"的
 * 那段时间里，等于把要修的问题又放大一遍。所以扫描补全阶段后台算好，
 * 播放时直接 seek。
 */
data class SilenceProfile(
    /** 跳过多少毫秒。0 表示不做处理。 */
    val skipMs: Int = 0,
    /** 判定用的开头静音长度，界面提示用。 */
    val silentHeadMs: Int = 0,
    /** 是否真的算过（区分"算过但结论是不用跳"和"还没算"）。 */
    val analyzed: Boolean = false
) {
    val hasSkip: Boolean get() = skipMs > 0

    companion object {
        val NONE = SilenceProfile()
    }
}

/**
 * 开头静音检测。
 *
 * 思路：把开头一小段解码出来（不是整首），按 10ms 一帧算 RMS，
 * 找到第一个"连续 [SUSTAIN_MS] 都超过阈值"的位置，那之前就是静音。
 *
 * 为什么不用整首：整首解码一首 5 分钟的歌要几秒，几百首就是几十分钟，
 * 而我们只关心开头。
 */
object SilenceDetector {

    /** 只看开头这么多毫秒。绝大多数"前面没声音"的问题都在几秒内。 */
    private const val WINDOW_MS = 12_000L

    /** 帧长。10ms 的粒度足够定位，也不会让帧数爆炸。 */
    private const val FRAME_MS = 10

    /** 需要连续有声音多久才算"真的开始了"，避免把一声咳嗽当成开头。 */
    private const val SUSTAIN_MS = 120

    /** 音量阈值：比整段峰值低这么多分贝以下就算静音。 */
    private const val GATE_BELOW_PEAK_DB = 26.0

    /** 绝对下限。整首都极轻的录音不该被误判成全程静音。 */
    private const val ABSOLUTE_GATE_DB = -52.0

    /** 尾巴留一点，别把第一个音切掉。 */
    private const val LEAD_IN_MS = 120

    /** 少于这么长的静音不值得跳——跳了反而像"少了一段"。 */
    private const val MIN_USEFUL_SKIP_MS = 400

    /**
     * 分析一个音频文件。耗时与 [WINDOW_MS] 成正比，与整首长度无关。
     * 失败一律返回 [SilenceProfile.NONE]，调用方不用处理异常。
     */
    fun analyze(path: String): SilenceProfile = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return@runCatching SilenceProfile.NONE
        val ext = AudioFormats.normalize(file.extension)
        if (ext !in SUPPORTED) {
            // 这套解码路径只覆盖系统一定能解的容器；其它格式直接放弃检测，
            // 不为了一个优化去引第三方解码器。
            return@runCatching SilenceProfile(analyzed = true)
        }
        val energies = sampleEnergies(file)
        if (energies.size < SUSTAIN_MS / FRAME_MS * 2) {
            return@runCatching SilenceProfile(analyzed = true)
        }
        decide(energies)
    }.getOrElse {
        AppLog.d(TAG, "静音检测失败 " + File(path).name + "：" + it.message)
        SilenceProfile(analyzed = true)
    }

    /** 从帧能量序列里判断要跳多久。抽出来是为了能单独跑单元测试。 */
    internal fun decide(energies: List<Double>): SilenceProfile {
        val peak = energies.maxOrNull() ?: 0.0
        if (peak <= 0.0) return SilenceProfile(analyzed = true)
        val peakDb = 20.0 * kotlin.math.log10(peak)
        val gateDb = maxOf(peakDb - GATE_BELOW_PEAK_DB, ABSOLUTE_GATE_DB)
        val gate = Math.pow(10.0, gateDb / 20.0)

        val framesForSustain = (SUSTAIN_MS / FRAME_MS).coerceAtLeast(1)
        var consecutive = 0
        var startFrame = -1
        energies.forEachIndexed { index, value ->
            if (value >= gate) {
                if (consecutive == 0) startFrame = index
                consecutive++
                if (consecutive >= framesForSustain) {
                    val silentHeadMs = startFrame * FRAME_MS
                    if (silentHeadMs < MIN_USEFUL_SKIP_MS) {
                        return SilenceProfile(silentHeadMs = silentHeadMs, analyzed = true)
                    }
                    val skip = (silentHeadMs - LEAD_IN_MS).coerceAtLeast(0)
                    return SilenceProfile(
                        skipMs = skip,
                        silentHeadMs = silentHeadMs,
                        analyzed = true
                    )
                }
            } else {
                consecutive = 0
                startFrame = -1
            }
        }
        // 整段窗口里都没等到持续的声音：可能是很长的前奏或者纯静音，
        // 不做处理，交给用户自己判断比乱跳安全。
        return SilenceProfile(analyzed = true)
    }

    /**
     * 解码开头 [WINDOW_MS]，返回每 [FRAME_MS] 的 RMS。
     *
     * 只取第一个音轨，按输出声道数把样本归一到 -1..1。
     */
    private fun sampleEnergies(file: File): List<Double> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return emptyList()
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val samplesPerFrame = (sampleRate.toLong() * FRAME_MS / 1000L).toInt().coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val sums = ArrayList<Double>()
            var pending = 0L
            var pendingSquares = 0.0
            var producedMs = 0L

            while (producedMs < WINDOW_MS) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val scale = channels.coerceAtLeast(1)
                            while (shorts.hasRemaining()) {
                                val value = shorts.get() / 32768.0
                                pendingSquares += value * value
                                pending++
                                if (pending >= samplesPerFrame * scale) {
                                    sums += sqrt(pendingSquares / pending)
                                    pending = 0L
                                    pendingSquares = 0.0
                                    producedMs += FRAME_MS
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // 半帧也算一帧，短文件不至于因为不满一帧就丢结果。
            if (pending > 0) sums += sqrt(pendingSquares / pending)
            return sums
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private const val TIMEOUT_US = 5_000L
    private const val TAG = "SilenceDetector"

    /**
     * 只对系统解码器确定支持的容器做检测。
     * ape / wv / tta 这些系统不认，强行解只会一直超时。
     */
    private val SUPPORTED = setOf(
        "mp3", "m4a", "m4b", "aac", "mp4", "flac", "ogg", "oga", "opus", "wav", "3gp", "amr"
    )
}
