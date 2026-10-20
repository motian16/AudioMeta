package com.audiometa.app.ui.screen.settings

import androidx.compose.runtime.Composable
import com.audiometa.app.data.prefs.AppSettings
import com.audiometa.app.domain.model.CornerStyle
import com.audiometa.app.domain.model.ThemeMode
import com.audiometa.app.domain.model.ThemePreset

// ---------------- 外观 ----------------
@Composable
internal fun AppearanceSection(settings: AppSettings, vm: SettingsViewModel) {
    SettingsGroup("主题") {
        SettingsRow("主题模式")
        SettingsSegmented(
            options = ThemeMode.entries.map { it.name to it.label },
            selected = settings.themeMode.name,
            onSelect = { vm.setThemeMode(ThemeMode.valueOf(it)) }
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "动态取色",
            checked = settings.dynamicColor,
            summary = "由系统从壁纸推算主题色（Android 12+）",
            onChecked = vm::setDynamicColor
        )
        SettingsDivider()
        SettingsRow("主题预设")
        SettingsSegmented(
            options = ThemePreset.entries.map { it.name to it.label },
            selected = settings.themePreset.name,
            onSelect = { vm.setThemePreset(ThemePreset.valueOf(it)) }
        )
        SettingsDivider()
        SettingsRow("边角样式", "封面、头像以及各页卡片的统一圆角")
        SettingsSegmented(
            options = CornerStyle.entries.map { it.name to it.label },
            selected = settings.cornerStyle.name,
            onSelect = { vm.setCornerStyle(CornerStyle.valueOf(it)) }
        )
    }

    if (settings.themePreset == ThemePreset.CUSTOM) {
        SettingsGroup("自定义颜色", "8 位十六进制，前两位是透明度") {
            SettingsTextField("主色", settings.customPrimary, onValueChange = vm::setCustomPrimary)
            SettingsTextField("背景色", settings.customBackground, onValueChange = vm::setCustomBackground)
            SettingsTextField("卡片色", settings.customSurface, onValueChange = vm::setCustomSurface)
        }
    }

    WidgetStylesGroup()
}

/**
 * 桌面小组件（按用户要求从单独的设置项并进「外观」）。
 *
 * 这里**没有开关**：四个部件是四条独立的 provider，在桌面的
 * 「添加小组件」列表里直接选（和选天气、时钟是一回事），加几个、怎么摆都由桌面管。
 * 所以这一块只是"有哪些排版可选、拉大拉小会多出什么"的说明 ——
 * 以前那个"样式"下拉是做成一个部件加一个设置项时的产物，现在删掉了。
 *
 * 文案里的名字必须和 `widget/RemoteWidgetProvider.kt` 里四个 provider 的
 * `android:label`（`values/widget.xml`）对得上，否则用户在桌面上找不到对应条目。
 */
@Composable
private fun WidgetStylesGroup() {
    SettingsGroup("桌面小组件", "桌面长按空白处添加，四种排版") {
        SettingsRow("卡片", "封面在左，曲名、进度与控制键在右，适合 4x2。")
        SettingsDivider()
        SettingsRow("沉浸封面", "整块封面，文字和按钮压在底部")
        SettingsDivider()
        SettingsRow("极简", "2x1 起：小封面、曲名与播放键")
        SettingsDivider()
        SettingsRow("歌词", "当前歌词大字居中，配进度条和控制键。")
    }
    SettingsNote(
        "尺寸小时只显示曲名和播放键，" +
            "拉大之后会自动补上进度条、上一首/下一首和歌词。"
    )
}
