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
	/** 应用背景色，用在主窗口底色，避免纯白长时间阅读刺眼。 */
	val Background = Color(0xFFF7F7F4)
	/** 面板和输入框底色，用来承载主要内容。 */
	val Panel = Color(0xFFFFFFFF)
	/** 分隔线和边框颜色，保持弱对比。 */
	val Border = Color(0xFFE4E2DD)
	/** 主文本颜色。 */
	val Ink = Color(0xFF202124)
	/** 次级文本颜色，例如 helper、时间和说明。 */
	val Muted = Color(0xFF6C6F73)
	/** 主要操作和模型 chip 使用的强调色。 */
	val Accent = Color(0xFF315C9A)
	/** 成功状态颜色，例如已连接。 */
	val Success = Color(0xFF2F6F4E)
	/** 警告状态颜色，例如审批或权限提示。 */
	val Warning = Color(0xFF9A5B13)
	/** 错误状态颜色，例如失败和断线。 */
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
