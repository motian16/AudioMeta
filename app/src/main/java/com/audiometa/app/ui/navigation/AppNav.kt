package com.audiometa.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部标签：**音频库 - 控制 - 设置**。
 *
 * 用户改过一次结构：
 * - 原来的「插件」标签改成「**控制**」，插件页的内容（源列表、导入、排序、导出、
 *   配置）全部移植到「设置 - 插件 - 元信息源 / 艺术家图源」里 ——
 *   插件管理属于设置，不该占一个底部主标签；
 * - 「控制」现在是**空白页**，留着以后放新功能（用户明确说"待开发新功能"）。
 *
 * 「刮削」仍然不是标签：它是从屏幕底部上拉出现的窗口，
 * 入口是音频库顶栏的刮削图标，以及多选之后的「去刮削」。
 */
enum class MainTab(val label: String, val icon: ImageVector) {
    LIBRARY("音频库", Icons.Outlined.LibraryMusic),
    CONTROL("控制", Icons.Outlined.Tune),
    SETTINGS("设置", Icons.Outlined.Settings)
}
