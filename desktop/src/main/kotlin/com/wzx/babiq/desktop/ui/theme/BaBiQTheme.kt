package com.wzx.babiq.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * BaBiQ 当前 UI 调色板。
 *
 * 先集中在一个 object 中，后续如果引入设计 token 或深色主题，可以从这里迁移。
 */
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

/**
 * Material3 颜色方案，把自定义色值映射到 Compose 组件可理解的语义槽位。
 */
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

/**
 * 应用主题入口，所有页面都应包在这个 Composable 下。
 */
@Composable
fun BaBiQTheme(content: @Composable () -> Unit) {
	MaterialTheme(
		colorScheme = colorScheme,
		content = content,
	)
}
