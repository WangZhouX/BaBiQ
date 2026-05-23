package com.wzx.babiq.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 左侧导航栏。
 *
 * P1-4 只开放新对话和设置，搜索/插件/自动化按计划保留为禁用占位。
 */
@Composable
fun Sidebar(
	state: AppState,
	onSelectScreen: (Screen) -> Unit,
) {
	Column(
		modifier = Modifier
			.width(288.dp)
			.fillMaxHeight()
			.background(BaBiQColors.Panel)
			.padding(18.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Text("BaBiQ", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
		SidebarAction("＋ 新对话", enabled = true) { onSelectScreen(Screen.Chat) }
		// 下面三个入口是 P2+ 占位，禁用是为了避免误导用户以为已经接入真实能力。
		SidebarAction("⌕ 搜索", enabled = false) { }
		SidebarAction("◇ 插件", enabled = false) { }
		SidebarAction("◷ 自动化", enabled = false) { }

		Text(
			text = "项目",
			color = BaBiQColors.Muted,
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(top = 18.dp),
		)
		SidebarProject(state.workspace.projectName, "当前工作区")

		Text(
			text = "最近",
			color = BaBiQColors.Muted,
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(top = 18.dp),
		)
		Text(
			text = state.messages.lastOrNull()?.let { "当前对话" } ?: "暂无对话",
			style = MaterialTheme.typography.bodyMedium,
			color = if (state.messages.isEmpty()) BaBiQColors.Muted else BaBiQColors.Ink,
		)
		Spacer(Modifier.weight(1f))
		SidebarAction("⚙ 设置", enabled = true) { onSelectScreen(Screen.Settings) }
	}
}

/**
 * Sidebar 的单行操作入口。
 */
@Composable
private fun SidebarAction(
	text: String,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	Text(
		text = text,
		color = if (enabled) BaBiQColors.Ink else Color(0xFFB0B3B8),
		style = MaterialTheme.typography.bodyMedium,
		modifier = Modifier
			.fillMaxWidth()
			.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
			.padding(vertical = 6.dp),
	)
}

/**
 * 当前工作区展示块。
 */
@Composable
private fun SidebarProject(title: String, subtitle: String) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text("□", color = BaBiQColors.Muted)
		Column {
			Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
			Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		}
	}
}
