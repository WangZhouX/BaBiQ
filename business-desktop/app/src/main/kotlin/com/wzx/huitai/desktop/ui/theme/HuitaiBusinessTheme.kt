package com.wzx.huitai.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HuitaiBlueGrayColors = lightColorScheme(
    primary = Color(0xFF2462AE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EFFF),
    onPrimaryContainer = Color(0xFF163A78),
    secondary = Color(0xFF536078),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF17233D),
    surface = Color.White,
    onSurface = Color(0xFF26364F),
    surfaceVariant = Color(0xFFF7F9FC),
    onSurfaceVariant = Color(0xFF60708B),
    outline = Color(0xFFD8DEE8),
    error = Color(0xFFB3261E),
)

/** 为业务桌面提供与已确认原型一致的克制蓝灰色基础主题。 */
@Composable
fun HuitaiBusinessTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HuitaiBlueGrayColors,
        typography = Typography(),
        content = content,
    )
}
