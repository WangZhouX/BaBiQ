package com.wzx.babiq.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.wzx.babiq.desktop.client.AgentClient
import com.wzx.babiq.desktop.client.KtorAgentTransport
import com.wzx.babiq.desktop.state.ChatController
import com.wzx.babiq.desktop.state.Screen
import com.wzx.babiq.desktop.ui.approval.ApprovalDialog
import com.wzx.babiq.desktop.ui.shell.AppShell
import com.wzx.babiq.desktop.ui.theme.BaBiQTheme
import kotlinx.coroutines.launch

/**
 * 桌面端的组合根节点。
 *
 * remember 让 ChatController 在重组时保持同一个实例；collectAsState 把 StateFlow 接入 Compose。
 * 之后 UI 的按钮回调只发意图给 Controller，真正的网络调用都放到协程里执行，避免阻塞 UI 线程。
 */
@Composable
fun BaBiQDesktopApp(config: DesktopConfig = DesktopConfig()) {
	// Controller 是 UI 的“状态和动作中枢”，remember 保证窗口重组时不会重新建连接。
	val controller = remember(config) {
		ChatController(
			gateway = AgentClient(
				transport = KtorAgentTransport(config),
				config = config,
			),
		)
	}
	// 把 StateFlow 转成 Compose State；AppState 变化后界面会自动重组。
	val state by controller.state.collectAsState()
	// UI 回调里启动协程，避免网络请求阻塞 Compose 主线程。
	val scope = rememberCoroutineScope()

	LaunchedEffect(controller) {
		controller.connect()
	}

	BaBiQTheme {
		AppShell(
			state = state,
			onSend = { text -> scope.launch { controller.sendMessage(text) } },
			onRetryConnection = { scope.launch { controller.connect() } },
			onSelectScreen = { screen -> controller.showScreen(screen) },
			onNewChat = { controller.newChat() },
			onOpenThread = { threadId -> scope.launch { controller.openThread(threadId) } },
			onArchiveThread = { threadId -> scope.launch { controller.archiveThread(threadId) } },
			onSelectWorkspace = { cwd -> controller.selectWorkspace(cwd) },
			onSelectProvider = { providerId, modelId ->
				scope.launch { controller.selectProvider(providerId, modelId) }
			},
			onCreateProvider = { params -> scope.launch { controller.createProvider(params) } },
			onUpdateProvider = { params -> scope.launch { controller.updateProvider(params) } },
			onDeleteProvider = { providerId -> scope.launch { controller.deleteProvider(providerId) } },
			onTestProvider = { providerId -> scope.launch { controller.testProvider(providerId) } },
			onRefreshProviderOAuthStatus = { scope.launch { controller.refreshProviderOAuthStatus() } },
			onStartProviderOAuthLogin = { scope.launch { controller.startProviderOAuthLogin() } },
			onSaveSandboxMode = { mode -> scope.launch { controller.saveSandboxMode(mode) } },
			onSaveApprovalPolicy = { policy -> scope.launch { controller.saveApprovalPolicy(policy) } },
			onSaveMemorySettings = { enabled, generateEnabled, readEnabled, retrievalEnabled ->
				scope.launch { controller.saveMemorySettings(enabled, generateEnabled, readEnabled, retrievalEnabled) }
			},
			onScanMemory = { controller.scanMemory() },
			onConsolidateMemory = { controller.consolidateMemory(force = true) },
			onSearchMemory = { query -> controller.searchMemory(query) },
			onSaveCapabilitySettings = { capabilityId, enabled, exposureMode ->
				controller.saveCapabilitySettings(capabilityId, enabled, exposureMode)
			},
			onSearchCapabilities = { query -> controller.searchCapabilities(query) },
			onOpenSkill = { skillId -> controller.openSkill(skillId) },
			onRefreshMcpServer = { serverId -> controller.refreshMcpServer(serverId) },
			onToggleRuntime = { controller.toggleRuntimeDetails() },
			onDismissSubAgent = { controller.dismissSubAgentCard() },
			onDismissOrchestration = { controller.dismissOrchestrationCard() },
			onDismissTeam = { controller.dismissTeamCard() },
			onSelectWorkUnit = { workUnitId -> controller.selectWorkUnit(workUnitId) },
			onConfigureWorkUnit = { workUnitId -> controller.configureWorkUnit(workUnitId) },
			onStartWorkUnit = { workUnitId -> controller.startWorkUnit(workUnitId) },
			onRemoveWorkUnit = { workUnitId -> controller.removeWorkUnit(workUnitId) },
			onUpdateWorkUnitGoal = { workUnitId, goalId, goalText ->
				controller.updateWorkUnitGoal(workUnitId, goalId, goalText)
			},
			onUpdateWorkUnitConfig = { workUnitId, configJson, structureJson ->
				controller.updateWorkUnitConfig(workUnitId, configJson, structureJson)
			},
			onMarkWorkUnitConfigDraftDirty = { workUnitId -> controller.markWorkUnitConfigDraftDirty(workUnitId) },
			onLoadLatestWorkUnitConfig = { workUnitId -> controller.loadLatestWorkUnitConfig(workUnitId) },
			onKeepWorkUnitConfigDraft = { workUnitId -> controller.keepWorkUnitConfigDraft(workUnitId) },
			onBackToWorkUnitList = { controller.clearWorkUnitConfiguration() },
			onSendTeamMessage = { toAgent, content -> controller.sendTeamMessage(toAgent, content) },
			onSelectRunTurn = { turnId -> controller.selectRunTurn(turnId) },
			onSelectObservabilityRange = { range -> controller.selectObservabilityRange(range) },
		)
		ApprovalDialog(
			approval = state.pendingApproval,
			canSubmit = state.canApprove,
			onDismiss = { },
			onDecision = { decision, editedArgs ->
				scope.launch { controller.respondApproval(decision, editedArgs, scope = if (decision == "always") "session" else null) }
			},
		)
	}
}
