package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.McpServerInfo
import com.wzx.babiq.desktop.protocol.McpToolInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 本地 MCP 状态页。
 *
 * P2-6 的定位是“最小可用 MCP client”：页面只展示后端已配置的本地 stdio server 和工具列表，
 * 不提供 marketplace，也不允许用户在 UI 中输入任意命令并立即执行。
 */
@Composable
fun McpSettingsPanel(
	state: AppState,
	onRefreshServer: (String) -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		Text("本地 MCP", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
		Text("展示后端配置的本地 stdio MCP server。新增或修改 server 仍需要在后端受信任配置中完成。", color = BaBiQColors.Muted)
		state.mcpState.notice?.let { Text(it, color = BaBiQColors.Success) }
		state.mcpState.error?.let { Text("MCP 错误: $it", color = BaBiQColors.Danger) }

		when {
			state.mcpState.loading -> Text("正在读取 MCP 状态...", color = BaBiQColors.Muted)
			state.mcpState.servers.isEmpty() -> Text("当前没有已配置的 MCP server。", color = BaBiQColors.Muted)
			else -> state.mcpState.servers.forEach { server ->
				McpServerCard(
					server = server,
					tools = state.mcpState.toolsByServer[server.serverId].orEmpty(),
					refreshing = state.mcpState.refreshingServerId == server.serverId,
					onRefreshServer = onRefreshServer,
				)
			}
		}
	}
}

/**
 * 单个 MCP server 状态卡。
 */
@Composable
private fun McpServerCard(
	server: McpServerInfo,
	tools: List<McpToolInfo>,
	refreshing: Boolean,
	onRefreshServer: (String) -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(server.displayName, fontWeight = FontWeight.Bold)
				Text(statusLabel(server.status), color = statusColor(server.status))
				Text("${server.toolCount} 个工具", color = BaBiQColors.Muted)
			}
			Text("id: ${server.serverId} · transport: ${server.transport}", color = BaBiQColors.Muted)
			server.lastError?.let { Text(it, color = BaBiQColors.Danger) }
			Button(
				enabled = !refreshing,
				onClick = { onRefreshServer(server.serverId) },
			) {
				Text(if (refreshing) "刷新中..." else "刷新连接")
			}
			if (tools.isEmpty()) {
				Text("暂无工具", color = BaBiQColors.Muted)
			} else {
				tools.forEach { tool -> McpToolRow(tool) }
			}
		}
	}
}

/**
 * MCP 工具只读展示行。
 */
@Composable
private fun McpToolRow(tool: McpToolInfo) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(tool.toolName, fontWeight = FontWeight.Medium)
		Text(tool.namespacedName, color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
		if (tool.description.isNotBlank()) {
			Text(tool.description, color = BaBiQColors.Muted)
		}
	}
}

private fun statusLabel(status: String): String =
	when (status) {
		"connected" -> "已连接"
		"configured" -> "已配置"
		"failed" -> "连接失败"
		"disabled" -> "未启用"
		else -> status
	}

private fun statusColor(status: String) =
	when (status) {
		"connected" -> BaBiQColors.Success
		"failed" -> BaBiQColors.Danger
		else -> BaBiQColors.Muted
	}
