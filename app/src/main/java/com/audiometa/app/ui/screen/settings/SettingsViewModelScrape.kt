package com.audiometa.app.ui.screen.settings

import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.domain.model.FallbackLyrics

/** 上移/下移歌词优先级。 */
internal fun SettingsViewModel.moveLyricsPriority(key: String, up: Boolean) {
    val current = state.value.settings.normalizedLyricsPriority().toMutableList()
    val index = current.indexOf(key)
    val target = if (up) index - 1 else index + 1
    if (index < 0 || target !in current.indices) return
    val item = current.removeAt(index)
    current.add(target, item)
    edit { ServiceLocator.settings.setLyricsPriority(current) }
}

/**
 * 拖动排序：把第 [from] 项挪到第 [to] 项的位置。
 *
 * 拖动过程中 from/to 是**当前位置**，所以要先把这一项从列表里摘出来再插进去，
 * 否则向下拖时下标会错位。
 */
internal fun SettingsViewModel.moveSourcePriorityTo(key: String, from: Int, to: Int) {
    val order = state.value.orderedPlugins(key).map { it.pluginId }.toMutableList()
    if (from !in order.indices || to !in order.indices || from == to) return
    val item = order.removeAt(from)
    order.add(to, item)
    edit { ServiceLocator.settings.setSourcePriority(state.value.settings.sourcePriority + (key to order)) }
}

/** 拖动排序：歌词格式（逐字 / 逐行）。 */
internal fun SettingsViewModel.moveLyricsType(from: Int, to: Int) {
    val order = state.value.settings.normalizedLyricsPriority().toMutableList()
    if (from !in order.indices || to !in order.indices || from == to) return
    val item = order.removeAt(from)
    order.add(to, item)
    edit { ServiceLocator.settings.setLyricsPriority(order) }
}

internal fun SettingsViewModel.setTranslation(value: Boolean) = edit { ServiceLocator.settings.setDownloadTranslation(value) }
internal fun SettingsViewModel.setFallbackLyrics(value: FallbackLyrics) = edit { ServiceLocator.settings.setFallbackLyrics(value) }
internal fun SettingsViewModel.setFallbackDir(value: String) = edit { ServiceLocator.settings.setFallbackDir(value) }
