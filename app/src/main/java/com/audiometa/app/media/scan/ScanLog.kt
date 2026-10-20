package com.audiometa.app.media.scan

/**
 * 给用户和日志用的一句话错误描述。
 *
 * 只写 `message` 是不够的：SQLite / Room / 解析库抛出来的异常经常 `message` 为空
 * （`SQLiteException` 的一些子类就是这样），日志里只剩一个空行，等于没记。
 * 所以类名一定要带上 —— 排查时"是什么异常"比"说了什么"更重要。
 *
 * 放在这里而不是各自的文件里：扫描链路（服务、扫描器、清理器）都要用，
 * 三份一样的实现迟早会走散。
 */
internal fun Throwable.shortDescription(): String =
    javaClass.simpleName + (message?.takeIf { it.isNotBlank() }?.let { "：" + it } ?: "")
