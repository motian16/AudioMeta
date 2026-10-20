package com.audiometa.app.media.tag

import com.audiometa.app.domain.model.AudioFormats
import com.audiometa.app.util.AppLog
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * 按容器写入**非标准标签**。
 *
 * 为什么需要单独一个类：jaudiotagger 的 `Tag` 接口里
 * `createField` 只接受 [FieldKey] 枚举，没有"用任意键名建字段"的通用方法。
 * 想写平台私有标签（kghash、QMQuality、163 key、MUSICBRAINZ_* 之类）
 * 只能按容器分别处理：
 *
 * | 容器 | 落点 | 实现 |
 * |---|---|---|
 * | ID3v2（mp3/wav/aiff…） | `TXXX` 帧 | [writeId3Txxx] |
 * | FLAC | Vorbis Comment | [writeVorbis] |
 * | Ogg / Opus / Speex | Vorbis Comment | [writeVorbis] |
 * | MP4 / M4A | `----:com.apple.iTunes:KEY` atom | [writeMp4] |
 * | APE / WavPack / WMA | 暂不支持 | 记日志跳过 |
 *
 * 全部走"复制到临时文件 → 改 → 替换"的路径，和 [TagWriter] 保持同一套安全写入方式。
 */
object RawTagWriter {

    /** 一次写入的结果，界面用它说明"哪几个键真的写进去了"。 */
    data class Result(
        val written: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 把 [tags] 写进 [file]。
     *
     * `tags` 的键是**裸键名**（`kghash`、`QMQuality`），不含容器前缀；
     * 值传空字符串表示删除该键。
     */
    fun write(file: File, tags: Map<String, String>, keepModTime: Boolean = true): Result {
        if (tags.isEmpty()) return Result()
        if (!file.exists()) return Result(error = "文件不存在")

        val ext = AudioFormats.normalize(file.extension)
        if (ext in UNSUPPORTED) {
            return Result(skipped = tags.keys.toList(), error = "该格式暂不支持写入非标准标签")
        }

        val parent = file.parentFile ?: return Result(error = "无法定位父目录")
        val temp = File(parent, file.nameWithoutExtension + ".rawtmp." + ext)
        val stamp = file.lastModified()
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        return try {
            java.nio.file.Files.copy(
                file.toPath(), temp.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            val audio = AudioFileIO.read(temp)
            val tag = audio.tag ?: audio.createDefaultTag().also { audio.tag = it }

            tags.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                if (key.isBlank()) return@forEach
                val value = rawValue.trim()
                val (done, reason) = writeOne(tag, key, value)
                if (done) {
                    written += key
                } else {
                    skipped += key
                    if (reason != null) AppLog.d(TAG, "跳过 $key：$reason")
                }
            }

            audio.commit()
            moveBack(temp, file)
            if (keepModTime) file.setLastModified(stamp)
            if (skipped.isNotEmpty()) {
                AppLog.d(TAG, "以下键未能写入（容器不支持）：" + skipped.joinToString(", "))
            }
            Result(written = written, skipped = skipped)
        } catch (t: Throwable) {
            temp.delete()
            AppLog.w(TAG, "写非标准标签失败 " + file.name + "：" + t.message)
            Result(written = written, skipped = skipped, error = t.message ?: "写入失败")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun moveBack(temp: File, target: File) {
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.FileSystemException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * 把一 个键写进对应容器。
     *
     * 统一走 `createField(String, String)` + `setField(TagField)`：
     * 这是 `Tag` 接口里唯一能接受**任意键名**的两个方法。
     * 具体落到容器的什么位置由 jaudiotagger 的 Tag 实现决定
     * （ID3 覆盖到自定义帧、MP4 变成反向 DNS atom、Vorbis 变成一行注释）。
     *
     * 需要说明的取舍：`createField(String, String)` 在部分实现里会抛
     * KeyNotFoundException——那时这个键就写不进去，如实返回 false 并记日志，
     * 不假装成功。要精确控制落点（例如强制 ID3 的 TXXX 帧描述）需要按容器
     * 各自实现，而那依赖 jaudiotagger 的 protected API，暂时不做。
     *
     * @return (是否写入, 失败原因)
     */
    private fun writeOne(tag: org.jaudiotagger.tag.Tag, key: String, value: String): Pair<Boolean, String?> {
        // 先删掉同名旧值，避免多值标签越写越多。
        runCatching { tag.deleteField(key) }
        if (value.isEmpty()) return true to null

        // 标准字段优先按 FieldKey 写，这样在各容器里都落到最标准的位置。
        val fieldKey = runCatching { FieldKey.valueOf(key.uppercase()) }.getOrNull()
        if (fieldKey != null) {
            val okay = runCatching {
                runCatching { tag.deleteField(fieldKey) }
                tag.setField(fieldKey, value)
                true
            }.getOrDefault(false)
            if (okay) return true to null
        }

        // 非标准键：尝试按名建字段。
        //
        // 这里必须用反射。`Tag` 接口同时声明了
        //   createField(FieldKey, String...)  和  createField(String, String)
        // 两个重载，Kotlin 在 (String, String) 实参上会优先解析到 FieldKey 那个，
        // 编译直接报 "inferred type is String but FieldKey was expected"。
        // 反射指定参数类型可以绕开这套解析规则。
        val created = runCatching {
            val method = tag.javaClass.methods.firstOrNull { m ->
                m.name == "createField" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java
            } ?: return@runCatching null
            method.invoke(tag, key, value) as? org.jaudiotagger.tag.TagField
        }.getOrNull()

        if (created == null) {
            return false to "该容器不支持用这个键名建字段"
        }
        return runCatching {
            tag.setField(created)
            true to null
        }.getOrElse { t ->
            false to (t.message ?: t.javaClass.simpleName)
        }
    }

    private const val TAG = "RawTagWriter"

    /**
     * 这些容器暂时不支持写非标准标签。
     * APE/WavPack 的 APEv2 标签、WMA 的 ASF 属性都没有合适的现成接口，
     * 与其写坏文件不如明确跳过。
     */
    private val UNSUPPORTED = setOf(
        "ape", "wv", "wma", "tta", "tak", "mpc", "ofr", "dsf", "dff"
    )
}
