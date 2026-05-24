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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wzx.babiq.desktop.state.ThreadListItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 左侧导航栏。
 *
 * P2-6 起插件占位升级为“本地 MCP”状态页；搜索和自动化仍保留为禁用占位。
 */
@Composable
fun Sidebar(
	state: AppState,
	onSelectScreen: (Screen) -> Unit,
	onNewChat: () -> Unit,
	onOpenThread: (String) -> Unit,
	onArchiveThread: (String) -> Unit,
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
		SidebarAction("＋ 新对话", enabled = true) {
			onSelectScreen(Screen.Chat)
			onNewChat()
		}
		// 搜索和自动化仍是 P2+ 占位，禁用是为了避免误导用户以为已经接入真实能力。
		SidebarAction("⌕ 搜索", enabled = false) { }
		SidebarAction("◇ 本地 MCP", enabled = true) { onSelectScreen(Screen.Mcp) }
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
		RecentThreads(
			items = state.threadHistory.items,
			loading = state.threadHistory.loading,
			error = state.threadHistory.error,
			selectedThreadId = state.threadHistory.selectedThreadId,
			onOpenThread = onOpenThread,
			onArchiveThread = onArchiveThread,
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

/**
 * 最近会话区域。
 *
 * loading/error/empty 都在这里收口，避免 Sidebar 主体堆满分支。
 */
@Composable
private fun RecentThreads(
	items: List<ThreadListItem>,
	loading: Boolean,
	error: String?,
	selectedThreadId: String?,
	onOpenThread: (String) -> Unit,
	onArchiveThread: (String) -> Unit,
) {
	when {
		loading -> Text("加载中...", style = MaterialTheme.typography.bodyMedium, color = BaBiQColors.Muted)
		error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
		items.isEmpty() -> Text("暂无对话", style = MaterialTheme.typography.bodyMedium, color = BaBiQColors.Muted)
		else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			items.forEach { item ->
				RecentThreadRow(
					item = item,
					selected = item.threadId == selectedThreadId,
					onOpenThread = onOpenThread,
					onArchiveThread = onArchiveThread,
				)
			}
		}
	}
}

/**
 * 最近会话单行。
 *
 * 归档入口独立放在右侧，避免标题文字和操作挤在一起。
 */
@Composable
private fun RecentThreadRow(
	item: ThreadListItem,
	selected: Boolean,
	onOpenThread: (String) -> Unit,
	onArchiveThread: (String) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				color = if (selected) Color(0xFFE8EEF8) else Color.Transparent,
				shape = RoundedCornerShape(6.dp),
			)
			.padding(horizontal = 8.dp, vertical = 7.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Column(
			modifier = Modifier
				.weight(1f)
				.clickable { onOpenThread(item.threadId) },
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = item.title,
				style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
				color = BaBiQColors.Ink,
				maxLines = 1,
			)
			Text(
				text = listOfNotNull(item.lastTurnStatus, "${item.messageCount} 项").joinToString(" · "),
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
				maxLines = 1,
			)
		}
		Text(
			text = "归档",
			style = MaterialTheme.typography.labelSmall,
			color = BaBiQColors.Muted,
			modifier = Modifier.clickable { onArchiveThread(item.threadId) },
		)
	}
}
