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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.state.ThreadListItem
import com.wzx.babiq.desktop.state.WorkspaceProjectItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 左侧导航栏。
 *
 * 项目列表来自后端历史会话中真实出现过的 cwd，并把当前工作区合并进去；这样重启后也能看到曾经新增过的工作目录。
 * 搜索已经接入 P3 搜索工作台；自动化仍然只是后续阶段的禁用占位。
 */
@Composable
fun Sidebar(
	state: AppState,
	onSelectScreen: (Screen) -> Unit,
	onNewChat: () -> Unit,
	onOpenThread: (String) -> Unit,
	onArchiveThread: (String) -> Unit,
	onSelectWorkspace: (String) -> Unit,
) {
	Column(
		modifier = Modifier
			.width(288.dp)
			.fillMaxHeight()
			.background(BaBiQColors.Panel)
			.padding(18.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		if (showSidebarBrandTitle()) {
			Text("BaBiQ", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
		}
		SidebarAction("+ 新对话", enabled = true) {
			onSelectScreen(Screen.Chat)
			onNewChat()
		}
		sidebarNavigationItems().forEach { item ->
			SidebarAction(item.label, enabled = item.enabled) {
				item.screen?.let(onSelectScreen)
			}
		}

		Text(
			text = "项目",
			color = BaBiQColors.Muted,
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(top = 18.dp),
		)
		SidebarProjects(
			items = state.workspaceProjects.items,
			loading = state.workspaceProjects.loading,
			error = state.workspaceProjects.error,
			fallback = WorkspaceProjectItem(
				projectName = state.workspace.projectName,
				cwd = state.workspace.cwd,
				current = true,
			),
			canSwitch = state.canSwitchWorkspace,
			onSelectWorkspace = onSelectWorkspace,
		)

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
		SidebarAction("设置", enabled = true) { onSelectScreen(Screen.Settings) }
	}
}

fun showSidebarBrandTitle(): Boolean = false

/**
 * Sidebar 顶部的固定导航项。
 *
 * 这里把“搜索是否可点、点到哪个 Screen”从 Composable 中抽出来，避免后续再出现 UI 文案可见但没有真实路由的问题。
 *
 * @property label 侧边栏展示文案。
 * @property screen 点击后进入的产品页；为空表示当前只是未来阶段占位。
 * @property enabled false 时只展示灰色文案，不绑定点击事件。
 */
data class SidebarNavigationItem(
	val label: String,
	val screen: Screen?,
	val enabled: Boolean,
)

/**
 * 构造侧边栏固定导航项。
 *
 * 00 交互总览-P3 是 Figma 索引页，不会出现在这里；这里只保留真实产品入口。
 */
fun sidebarNavigationItems(): List<SidebarNavigationItem> =
	listOf(
		SidebarNavigationItem("搜索", Screen.Search, enabled = true),
		SidebarNavigationItem("插件", Screen.Plugins, enabled = true),
		SidebarNavigationItem("本地 MCP", Screen.Mcp, enabled = true),
		SidebarNavigationItem("自动化", screen = null, enabled = false),
	)

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
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
	)
}

/**
 * 项目列表展示块。
 *
 * 全局列表加载失败时仍展示当前工作区，避免用户误以为当前 cwd 丢失。
 */
@Composable
private fun SidebarProjects(
	items: List<WorkspaceProjectItem>,
	loading: Boolean,
	error: String?,
	fallback: WorkspaceProjectItem,
	canSwitch: Boolean,
	onSelectWorkspace: (String) -> Unit,
) {
	val visibleItems = items.ifEmpty { listOf(fallback) }
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		visibleItems.forEach { item ->
			SidebarProject(
				item = item,
				canSwitch = canSwitch,
				onSelectWorkspace = onSelectWorkspace,
			)
		}
		when {
			loading -> Text("加载中...", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			error != null -> Text(error, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB45309), maxLines = 2)
		}
	}
}

/**
 * 单个工作区展示行。
 *
 * 非当前工作区可以直接点击切换；运行中的 turn 会由 AppState.canSwitchWorkspace 统一禁止切换。
 */
@Composable
private fun SidebarProject(
	item: WorkspaceProjectItem,
	canSwitch: Boolean,
	onSelectWorkspace: (String) -> Unit,
) {
	val clickable = canSwitch && !item.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				color = if (item.current) Color(0xFFE8EEF8) else Color.Transparent,
				shape = RoundedCornerShape(6.dp),
			)
			.then(if (clickable) Modifier.clickable { onSelectWorkspace(item.cwd) } else Modifier)
			.padding(horizontal = 6.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text("▫", color = BaBiQColors.Muted)
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = item.projectName,
				style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = if (item.current) "当前工作区" else item.cwd,
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * 最近会话区域。
 *
 * loading、error、empty 都在这里收口，避免 Sidebar 主体堆满分支。
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
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = listOfNotNull(item.lastTurnStatus, "${item.messageCount} 项").joinToString(" · "),
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
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
