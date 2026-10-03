package com.atri.vc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 亚托莉主题色：深海蓝底 + 青蓝主色 + 琥珀强调
val AtriBg = Color(0xFF0E1726)
val AtriSurface = Color(0xFF16233A)
val AtriSurface2 = Color(0xFF1D2E4A)
val AtriCyan = Color(0xFF5FD3F3)
val AtriAmber = Color(0xFFFFD166)
val AtriPink = Color(0xFFFF8FA3)
// 注意事项 / 危险提示：深蓝底上仍然醒目、且对比度达标（~5:1）的红
val AtriWarn = Color(0xFFFF5252)
val AtriText = Color(0xFFE8F1FA)
val AtriTextDim = Color(0xFF8FA3BD)

private val DarkColors = darkColorScheme(
    primary = AtriCyan,
    onPrimary = Color(0xFF06202B),
    secondary = AtriAmber,
    onSecondary = Color(0xFF2A1F00),
    tertiary = AtriPink,
    background = AtriBg,
    onBackground = AtriText,
    surface = AtriSurface,
    onSurface = AtriText,
    surfaceVariant = AtriSurface2,
    onSurfaceVariant = AtriTextDim,
    outline = Color(0xFF35507A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    secondary = Color(0xFF9A6B00),
    background = Color(0xFFF6F9FC),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101C2C),
    onBackground = Color(0xFF101C2C),
    surfaceVariant = Color(0xFFE3ECF6),
    onSurfaceVariant = Color(0xFF4A5C72),
)

@Composable
fun AtriTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val use = if (isSystemInDarkTheme() && !dark) false else dark
    MaterialTheme(colorScheme = if (use) DarkColors else LightColors, content = content)
}
