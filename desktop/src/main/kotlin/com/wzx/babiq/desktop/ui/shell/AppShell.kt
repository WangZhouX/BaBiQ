package com.wzx.babiq.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.ui.chat.ChatScreen
import com.wzx.babiq.desktop.ui.runtime.RuntimeDetailsPanel
import com.wzx.babiq.desktop.ui.search.SearchPanel
import com.wzx.babiq.desktop.ui.settings.McpSettingsPanel
import com.wzx.babiq.desktop.ui.settings.SettingsPanel
import com.wzx.babiq.desktop.ui.skills.SkillLibraryPanel
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import java.awt.Cursor

internal val DefaultRuntimePanelWidth = 360.dp
internal val MinRuntimePanelWidth = 320.dp
internal val MaxRuntimePanelWidth = 760.dp
internal val RuntimePanelResizeHandleWidth = 18.dp
internal val RuntimePanelResizeRailWidth = 4.dp

internal fun resizeRuntimePanelWidth(current: Dp, dragDeltaDp: Dp): Dp =
	(current - dragDeltaDp).coerceIn(MinRuntimePanelWidth, MaxRuntimePanelWidth)

internal fun shouldShowRuntimePanel(state: AppState): Boolean =
	state.runtimeExpanded

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
	onUpdateProvider: (ProviderSaveParams) -> Unit,
	onDeleteProvider: (String) -> Unit,
	onTestProvider: (String) -> Unit,
	onSaveSandboxMode: (String) -> Unit,
	onSaveApprovalPolicy: (String) -> Unit,
	onSaveMemorySettings: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onScanMemory: () -> Unit,
	onConsolidateMemory: () -> Unit,
	onSearchMemory: (String) -> Unit,
	onSaveCapabilitySettings: (String, Boolean?, String?) -> Unit,
	onSearchCapabilities: (String) -> Unit,
	onOpenSkill: (String) -> Unit,
	onRefreshMcpServer: (String) -> Unit,
	onToggleRuntime: () -> Unit,
	onDismissSubAgent: () -> Unit,
	onSelectWorkUnit: (String) -> Unit,
	onConfigureWorkUnit: (String) -> Unit,
	onStartWorkUnit: (String) -> Unit,
	onRemoveWorkUnit: (String) -> Unit,
	onUpdateWorkUnitGoal: (String, String, String) -> Unit,
	onSendTeamMessage: (String, String) -> Unit,
	onSelectRunTurn: (String) -> Unit,
	onSelectObservabilityRange: (String) -> Unit,
) {
	var runtimePanelWidth by remember { mutableStateOf(DefaultRuntimePanelWidth) }
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

				Screen.Search -> SearchPanel(
					state = state,
					onSearchMemory = onSearchMemory,
					onSaveCapabilitySettings = onSaveCapabilitySettings,
					onSearchCapabilities = onSearchCapabilities,
				)

				Screen.Plugins -> SkillLibraryPanel(
					state = state,
					onOpenSkill = onOpenSkill,
					onSaveCapabilitySettings = onSaveCapabilitySettings,
				)

				Screen.Settings -> SettingsPanel(
					state = state,
					onBackToChat = { onSelectScreen(Screen.Chat) },
					onSelectWorkspace = onSelectWorkspace,
					onSelectProvider = { providerId -> onSelectProvider(providerId, null) },
					onCreateProvider = onCreateProvider,
					onUpdateProvider = onUpdateProvider,
					onDeleteProvider = onDeleteProvider,
					onTestProvider = onTestProvider,
					onSaveSandboxMode = onSaveSandboxMode,
					onSaveApprovalPolicy = onSaveApprovalPolicy,
					onRefreshMcpServer = onRefreshMcpServer,
					onSaveMemorySettings = onSaveMemorySettings,
					onScanMemory = onScanMemory,
					onConsolidateMemory = onConsolidateMemory,
					onSearchMemory = onSearchMemory,
					onSaveCapabilitySettings = onSaveCapabilitySettings,
					onSearchCapabilities = onSearchCapabilities,
				)

				Screen.Mcp -> McpSettingsPanel(
					state = state,
					onRefreshServer = onRefreshMcpServer,
				)
			}
		}
		// RuntimeDetailsPanel 的渲染只受 runtimeExpanded 控制；旧运行数据不能覆盖用户的收起动作。
		if (shouldShowRuntimePanel(state)) {
			RuntimePanelResizeHandle(
				onDragDelta = { delta -> runtimePanelWidth = resizeRuntimePanelWidth(runtimePanelWidth, delta) },
			)
			RuntimeDetailsPanel(
				state = state,
				modifier = Modifier.width(runtimePanelWidth),
				onClose = onToggleRuntime,
				onDismissSubAgent = onDismissSubAgent,
				onSelectWorkUnit = onSelectWorkUnit,
				onConfigureWorkUnit = onConfigureWorkUnit,
				onStartWorkUnit = onStartWorkUnit,
				onRemoveWorkUnit = onRemoveWorkUnit,
				onUpdateWorkUnitGoal = onUpdateWorkUnitGoal,
				onSendTeamMessage = onSendTeamMessage,
				onSelectRunTurn = onSelectRunTurn,
				onSelectObservabilityRange = onSelectObservabilityRange,
			)
		}
	}
}

@Composable
private fun RuntimePanelResizeHandle(
	onDragDelta: (Dp) -> Unit,
) {
	val density = LocalDensity.current
	Box(
		modifier = Modifier
			.width(RuntimePanelResizeHandleWidth)
			.fillMaxHeight()
			.background(BaBiQColors.Background)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
			.pointerInput(Unit) {
				detectDragGestures { change, dragAmount ->
					change.consume()
					onDragDelta(with(density) { dragAmount.x.toDp() })
				}
			},
		contentAlignment = Alignment.Center,
	) {
		Box(
			modifier = Modifier
				.width(RuntimePanelResizeRailWidth)
				.fillMaxHeight()
				.background(BaBiQColors.Border.copy(alpha = 0.65f), RoundedCornerShape(2.dp)),
		)
	}
}
