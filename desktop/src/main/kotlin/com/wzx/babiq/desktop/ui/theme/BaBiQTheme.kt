package com.wzx.babiq.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object BaBiQColors {
	val Background = Color(0xFFF7F7F4)
	val Panel = Color(0xFFFFFFFF)
	val Border = Color(0xFFE4E2DD)
	val Ink = Color(0xFF202124)
	val Muted = Color(0xFF6C6F73)
	val Accent = Color(0xFF315C9A)
	val Success = Color(0xFF2F6F4E)
	val Warning = Color(0xFF9A5B13)
	val Danger = Color(0xFF9A2D2D)
}

private val colorScheme = lightColorScheme(
	primary = BaBiQColors.Accent,
	secondary = BaBiQColors.Success,
	background = BaBiQColors.Background,
	surface = BaBiQColors.Panel,
	onPrimary = Color.White,
	onSecondary = Color.White,
	onBackground = BaBiQColors.Ink,
	onSurface = BaBiQColors.Ink,
)

@Composable
fun BaBiQTheme(content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = colorScheme,
		content = content,
	)
}
