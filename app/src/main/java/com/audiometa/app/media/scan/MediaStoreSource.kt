package com.audiometa.app.media.scan

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.audiometa.app.util.AppLog
import com.audiometa.app.util.StoragePermissions

/**
 * Android 媒体库里的音频目录。
 *
 * 用户要求曲库来源**二选一**：要么用「音乐库」里手动添加的固定文件夹，
 * 要么用 Android 媒体库。选媒体库时，这里把系统已经扫出来的音频
 * 归纳成一组目录，交给和固定文件夹完全相同的扫描流程处理 ——
 * 后面的标签读取、排除规则、增量索引一行都不用改。
 *
 * 用 Android 11+ 的 `MediaStore.Audio.Media.RELATIVE_PATH`（相对路径）而不是
 * `DATA`（绝对路径）：`DATA` 在新系统上已经不可靠，而且不带存储卷前缀。
 * 两列都试，哪个有值用哪个。
 *
 * 这里的每一步都**不许把异常抛出去**：调用方是前台扫描服务，
 * 抛出去就是整个应用崩掉。读不到就把原因装进 [Result.problem] 交给界面显示 ——
 * "媒体库里没有音频"和"没权限读媒体库"对用户是两件事，不能都表现成"扫描没反应"。
 */
object MediaStoreSource {

    /**
     * 读取结果。
     *
     * @param dirs 媒体库里所有含音频的目录（绝对路径）。
     * @param problem 非空表示这次**没读成**（缺权限、查询失败…），内容是给用户看的一句话。
     */
    data class Result(val dirs: List<String> = emptyList(), val problem: String = "")

    /** 缺权限时给用户看的那句话（会被显示在扫描进度那一块里）。 */
    private const val PERMISSION_HINT =
        "没有读取媒体库的权限：请在「设置 - 音乐库 - 权限」里授予「媒体文件权限」"

    /**
     * 现在能不能读媒体库。
     *
     * 「所有文件访问」也算有权限：拿到它以后查 MediaStore 不再需要音频权限。
     * 界面在开始扫描前用它决定要不要先弹权限申请 —— 服务里申请不了权限，
     * 所以这一步必须在界面上做（见设置页的「重新扫描」）。
     *
     * 判断收拢在 [StoragePermissions.canReadMedia]（和设置页、权限引导页共用同一份）。
     */
    fun canRead(context: Context): Boolean = StoragePermissions.canReadMedia(context)

    /** 媒体库里所有含音频的目录（绝对路径）。取不到就返回空列表 + 原因。 */
    fun audioDirectories(context: Context): Result {
        if (!canRead(context)) return Result(problem = PERMISSION_HINT)

        // 只要音乐，不要录音/铃声/提示音这些。
        val strict = runQuery(context, musicOnly = true)
        if (strict.dirs != null) return Result(dirs = strict.dirs)

        /*
         * 加 is_music 过滤的那次失败了，再不加筛选试一次。
         *
         * 各家 ROM 的媒体库实现并不完全一致，个别机器上 `is_music` 这一列会直接
         * 让整条查询被拒（"Invalid column"）。多几个录音、铃声目录，总好过
         * 整个"媒体库"来源什么都扫不出来。
         */
        val relaxed = runQuery(context, musicOnly = false)
        if (relaxed.dirs != null) {
            AppLog.w(TAG, "按 is_music 过滤的查询失败（" + strict.error + "），已退回不过滤")
            return Result(dirs = relaxed.dirs)
        }

        AppLog.w(TAG, "读取媒体库失败：" + relaxed.error)
        return Result(problem = describe(relaxed.error))
    }

    /**
     * 查一次媒体库。
     *
     * @return [Outcome.dirs] 为 null 表示这次失败了，原因在 [Outcome.error] 里。
     */
    private fun runQuery(context: Context, musicOnly: Boolean): Outcome = runCatching {
        val projection = buildList {
            add(MediaStore.Audio.Media.DATA)
            // RELATIVE_PATH 是 Android 10 才加的列。低版本把它写进 projection，
            // 整条查询会以 "Invalid column relative_path" 被拒 —— 那样这个来源
            // 在老机器上永远是空的（而且以前只在日志里留一行，界面上看不出原因）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        val out = linkedSetOf<String>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            if (musicOnly) MediaStore.Audio.Media.IS_MUSIC + " != 0" else null,
            null,
            null
        )?.use { cursor ->
            // 系统不认这两列时 getColumnIndex 会给 -1，各自判一次，别直接拿 -1 去取。
            val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relativeIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                val data = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                val dir = when {
                    // 没有分隔符（拿到的不是路径）时给空串，当它没读到。
                    !data.isNullOrBlank() -> data.substringBeforeLast('/', "")
                    relativeIndex >= 0 -> {
                        // RELATIVE_PATH 形如 "Music/Album/"，要拼成绝对路径。
                        val rel = cursor.getString(relativeIndex).orEmpty().trimEnd('/')
                        if (rel.isBlank()) null else "/storage/emulated/0/$rel"
                    }
                    else -> null
                }
                if (!dir.isNullOrBlank()) out += dir
            }
        }
        Outcome(dirs = out.toList())
    }.getOrElse {
        Outcome(error = it.message ?: it.javaClass.simpleName)
    }

    /** 查询失败的原始信息翻译成用户能照着做的一句话。 */
    private fun describe(error: String): String = when {
        error.contains("permission", true) || error.contains("denied", true) ||
            error.contains("SecurityException", true) -> PERMISSION_HINT
        error.isBlank() -> "读取系统媒体库失败"
        else -> "读取系统媒体库失败：" + error
    }

    private class Outcome(val dirs: List<String>? = null, val error: String = "")

    private const val TAG = "MediaStoreSource"
}
