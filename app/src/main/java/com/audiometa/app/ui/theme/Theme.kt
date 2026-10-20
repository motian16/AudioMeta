package com.audiometa.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiometa.app.domain.model.CornerStyle
import com.audiometa.app.domain.model.ThemeMode
import com.audiometa.app.domain.model.ThemePreset
import androidx.core.view.WindowCompat

private val Ink = Color(0xFF12141A)
private val Paper = Color(0xFFF4F1EA)
private val Accent = Color(0xFFC45C26)
private val AccentDark = Color(0xFFE7A06A)

/*
 * 中性色补齐成完整的 M3 色板。
 *
 * 之前只填了 background/surface/surfaceVariant，NavigationBar、BottomSheet、
 * 菜单这些组件会退回到 Material 默认的紫灰底色，和这套暖纸/墨色完全不搭。
 * 这里把 surfaceContainer 系列的每一级都写出来，组件取哪一级都不会跑色。
 */
val DarkColors: ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Ink,
    secondary = Color(0xFF7EA1B5),
    onSecondary = Ink,
    tertiary = Color(0xFF9DB8A4),
    onTertiary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Color(0xFF1C1F27),
    onSurface = Paper,
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFB9B2A6),
    surfaceDim = Color(0xFF12141A),
    surfaceBright = Color(0xFF3A3F4B),
    surfaceContainerLowest = Color(0xFF0D0F14),
    surfaceContainerLow = Color(0xFF171A21),
    surfaceContainer = Color(0xFF1F232C),
    surfaceContainerHigh = Color(0xFF262A34),
    surfaceContainerHighest = Color(0xFF2E323D),
    outline = Color(0xFF3C414C),
    outlineVariant = Color(0xFF2C313B),
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    inversePrimary = Accent,
    scrim = Color.Black,
    error = Color(0xFFFF8A80),
    onError = Ink
)

val LightColors: ColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF3E6478),
    onSecondary = Color.White,
    tertiary = Color(0xFF5B7A6A),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E1D6),
    onSurfaceVariant = Color(0xFF5E584E),
    surfaceDim = Color(0xFFDED7CA),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF7F1),
    surfaceContainer = Color(0xFFF1ECE3),
    surfaceContainerHigh = Color(0xFFE9E3D8),
    surfaceContainerHighest = Color(0xFFE2DBcf),
    outline = Color(0xFFC9C1B4),
    outlineVariant = Color(0xFFDDD6C9),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = AccentDark,
    scrim = Color.Black
)

/** 中文界面下把行高放宽一点，密集列表和两行说明读起来不挤。 */
private val BaseTypography = Typography()
private val AppTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    ),
    titleMedium = BaseTypography.titleMedium.copy(lineHeight = 24.sp),
    titleSmall = BaseTypography.titleSmall.copy(lineHeight = 20.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 22.sp),
    bodySmall = BaseTypography.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = BaseTypography.labelLarge.copy(lineHeight = 20.sp),
    labelMedium = BaseTypography.labelMedium.copy(lineHeight = 16.sp),
    labelSmall = BaseTypography.labelSmall.copy(lineHeight = 16.sp)
)

/** 卡片、菜单、底部窗口统一用一套圆角，避免每个页面各写一个数字。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)


/** 按设置把封面/头像裁成圆形、圆角或方形。 */
fun cornerShape(style: CornerStyle, corner: androidx.compose.ui.unit.Dp = 12.dp): androidx.compose.ui.graphics.Shape =
    when (style) {
        CornerStyle.CIRCLE -> androidx.compose.foundation.shape.CircleShape
        CornerStyle.ROUNDED -> androidx.compose.foundation.shape.RoundedCornerShape(corner)
        CornerStyle.SQUARE -> androidx.compose.ui.graphics.RectangleShape
    }

/** 解析 #RRGGBB 或 #AARRGGBB；不合法返回 null。 */
fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) Color(value or 0xFF000000L) else Color(value)
}

/** 主色上的文字/图标用黑还是白，按亮度选。 */
fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF16181D) else Color.White

/**
 * 用预设主色生成配色。只替换主色系，中性色沿用基础配色，
 * 这样每个预设都有辨识度，又不会破坏对比度。
 */
private fun themed(base: ColorScheme, seed: Color): ColorScheme {
    val surface = base.surface
    val dark = base.background.luminance() < 0.5f
    val containerAlpha = if (dark) 0.30f else 0.16f
    return base.copy(
        primary = seed,
        onPrimary = onColorFor(seed),
        primaryContainer = seed.copy(alpha = containerAlpha).compositeOver(surface),
        onPrimaryContainer = base.onSurface,
        secondary = seed.copy(alpha = if (dark) 0.75f else 0.85f).compositeOver(surface),
        onSecondary = base.onSurface,
        surfaceTint = seed,
        inversePrimary = seed
    )
}

/**
 * @param mode 跟随系统 / 浅色 / 深色。应用默认深色。
 * @param dynamicColor Android 12+ 从壁纸取色，优先级高于预设。
 * @param preset 预设主色。
 * @param customPrimary 自定义主色，仅在 preset 为 CUSTOM 时生效。
 * @param customBackground 自定义背景色。
 * @param customSurface 自定义卡片色。
 */
@Composable
fun AudioMetaTheme(
    mode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    preset: ThemePreset = ThemePreset.DEFAULT,
    customPrimary: Color? = null,
    customBackground: Color? = null,
    customSurface: Color? = null,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val base = if (dark) DarkColors else LightColors

    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> {
            val seed = when {
                preset == ThemePreset.CUSTOM -> customPrimary
                else -> preset.seedHex?.let { parseHexColor(it) }
            }
            var result = if (seed != null) themed(base, seed) else base
            if (customBackground != null) {
                // 容器色也跟着自定义背景抬升，否则导航栏/菜单还是原来的暖灰，跟背景脱节。
                val lift = if (customBackground.luminance() < 0.5f) Color.White else Color.Black
                fun level(alpha: Float) = lift.copy(alpha = alpha).compositeOver(customBackground)
                result = result.copy(
                    background = customBackground,
                    onBackground = onColorFor(customBackground),
                    surfaceDim = customBackground,
                    surfaceBright = level(0.10f),
                    surfaceContainerLowest = customBackground,
                    surfaceContainerLow = level(0.03f),
                    surfaceContainer = level(0.06f),
                    surfaceContainerHigh = level(0.09f),
                    surfaceContainerHighest = level(0.12f)
                )
            }
            if (customSurface != null) {
                result = result.copy(
                    surface = customSurface,
                    onSurface = onColorFor(customSurface),
                    surfaceVariant = customSurface,
                    onSurfaceVariant = onColorFor(customSurface).copy(alpha = 0.75f)
                )
            }
            result
        }
    }
    SystemBarsEffect(dark)
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 系统栏跟随主题：深色主题用浅色图标，浅色主题用深色图标。
 *
 * 边到边之后系统栏是透明的，如果图标颜色不跟着换，浅色主题下状态栏
 * 就是"浅色背景 + 浅色图标"，时间和电量基本看不见。
 */
@Composable
private fun SystemBarsEffect(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
