package com.audiometa.app.data.db

/**
 * 一条 SQL 里最多带多少个绑定变量。
 *
 * `WHERE id IN (:ids)` / `NOT IN (:ids)` 会被 Room 展开成"列表里每一项一个 `?`"，
 * 而 SQLite 对绑定变量个数有上限：老系统（SQLite < 3.32，也就是 Android 11 及以下）
 * 是 **999**，超了直接抛 `too many SQL variables`。
 *
 * 「使用安卓媒体库」这个来源一次能把整个存储里的音频都扫出来，几千首很常见 ——
 * 于是扫描收尾的"清理已失效曲目"、补全前的"清理缓存孤儿行"全都会撞上这个上限，
 * 表现就是扫描到一半（或刚扫完）整个应用崩掉，而固定文件夹那几百首从来不会。
 * 所以凡是要把 id / 路径列表塞进 SQL 的地方都按这个大小分块。
 */
internal const val SQL_CHUNK = 800

/**
 * 按 [SQL_CHUNK] 切块逐段执行 [block]，返回各段结果之和。
 *
 * 只用于**按 id 删除**这种天然可加的操作；`NOT IN (…)` 那种语义不能直接切
 * （分段删会把别的段里还有效的行删掉），所以调用方要先在内存里算出失效的 id。
 */
internal suspend fun <T> List<T>.forEachChunk(block: suspend (List<T>) -> Int): Int {
    var total = 0
    for (chunk in chunked(SQL_CHUNK)) total += block(chunk)
    return total
}
