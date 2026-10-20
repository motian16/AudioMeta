package com.audiometa.app.ui.screen.settings

import androidx.compose.runtime.Composable
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.domain.model.FallbackLyrics

// ---------------- 刮削 ----------------

@Composable
internal fun ScrapeConfigSection(
    settings: AppSettings,
    plugins: List<com.audiometa.app.data.db.PluginRecordEntity>,
    vm: SettingsViewModel
) {
    // 源优先级已移到「标签规则」里随订阅更新，这里不再提供入口。
    SettingsGroup("歌词") {
        SettingsSwitchRow(
            title = "下载歌词时一并取翻译",
            checked = settings.downloadTranslation,
            summary = "源提供翻译时，逐字和逐行都会一起写入",
            onChecked = vm::setTranslation
        )
        SettingsDivider()
        SettingsRow("兜底歌词", "文件里读不到歌词时，额外导出一份到统一目录")
        SettingsSegmented(
            options = FallbackLyrics.entries.map { it.key to it.label },
            selected = settings.fallbackLyrics.key,
            onSelect = { key -> vm.setFallbackLyrics(FallbackLyrics.fromKey(key)) }
        )
        if (settings.fallbackLyrics != FallbackLyrics.NONE) {
            SettingsTextField(
                label = "兜底歌词目录",
                value = settings.fallbackDir,
                supporting = "留空表示用默认目录，会保持歌曲原有的目录结构",
                onValueChange = vm::setFallbackDir
            )
        }
    }
}
