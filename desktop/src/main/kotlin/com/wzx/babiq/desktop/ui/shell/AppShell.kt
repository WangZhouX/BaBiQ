package com.wzx.babiq.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.ui.chat.ChatScreen
import com.wzx.babiq.desktop.ui.runtime.RuntimeDetailsPanel
import com.wzx.babiq.desktop.ui.settings.McpSettingsPanel
import com.wzx.babiq.desktop.ui.settings.SettingsPanel
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 应用外壳布局。
 *
 * 左侧固定 Sidebar，中间按 screen 切换主内容，右侧运行详情按需展开。
 */
@Composable
fun AppShell(
	state: AppState,
	onSend: (String) -> Unit,
	onRetryConnection: () -> Unit,
	onSelectScreen: (Screen) -> Unit,
	onNewChat: () -> Unit,
	onOpenThread: (String) -> Unit,
	onArchiveThread: (String) -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onCreateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
	onSaveSandboxMode: (String) -> Unit,
	onSaveApprovalPolicy: (String) -> Unit,
	onRefreshMcpServer: (String) -> Unit,
	onToggleRuntime: () -> Unit,
	onSelectRunTurn: (String) -> Unit,
	onSelectObservabilityRange: (String) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxSize()
			.background(BaBiQColors.Background),
	) {
		Sidebar(
			state = state,
			onSelectScreen = onSelectScreen,
			onNewChat = onNewChat,
			onOpenThread = onOpenThread,
			onArchiveThread = onArchiveThread,
			onSelectWorkspace = onSelectWorkspace,
		)
		// 中间区域占满剩余空间，Chat 和 Settings 互斥显示。
		Box(modifier = Modifier.weight(1f)) {
			when (state.screen) {
				Screen.Chat -> ChatScreen(
					state = state,
					onSend = onSend,
					onRetryConnection = onRetryConnection,
					onSelectWorkspace = onSelectWorkspace,
					onSelectProvider = onSelectProvider,
					onChangeSandboxMode = onSaveSandboxMode,
					onToggleRuntime = onToggleRuntime,
				)

				Screen.Settings -> SettingsPanel(
					state = state,
					onSelectWorkspace = onSelectWorkspace,
					onSelectProvider = { providerId -> onSelectProvider(providerId, null) },
					onCreateProvider = onCreateProvider,
					onDeleteProvider = onDeleteProvider,
					onTestProvider = onTestProvider,
					onSaveSandboxMode = onSaveSandboxMode,
					onSaveApprovalPolicy = onSaveApprovalPolicy,
				)

				Screen.Mcp -> McpSettingsPanel(
					state = state,
					onRefreshServer = onRefreshMcpServer,
				)
			}
		}
		// RuntimeDetailsPanel 是辅助面板，收起后不占布局宽度。
		if (state.runtimeExpanded) {
			RuntimeDetailsPanel(
				state = state,
				modifier = Modifier.width(320.dp),
				onClose = onToggleRuntime,
				onSelectRunTurn = onSelectRunTurn,
				onSelectObservabilityRange = onSelectObservabilityRange,
			)
		}
	}
}
