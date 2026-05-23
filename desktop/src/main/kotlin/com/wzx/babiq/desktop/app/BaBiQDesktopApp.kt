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
fun BaBiQDesktopApp() {
	// Controller 是 UI 的“状态和动作中枢”，remember 保证窗口重组时不会重新建连接。
	val controller = remember {
		ChatController(
			gateway = AgentClient(KtorAgentTransport()),
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
			onSelectWorkspace = { cwd -> controller.selectWorkspace(cwd) },
			onSelectProvider = { providerId, modelId ->
				scope.launch { controller.selectProvider(providerId, modelId) }
			},
			onToggleRuntime = { controller.toggleRuntimeDetails() },
		)
		ApprovalDialog(
			approval = state.pendingApproval,
			canSubmit = state.canApprove,
			onDismiss = { },
			onDecision = { decision, editedArgs ->
				scope.launch { controller.respondApproval(decision, editedArgs) }
			},
		)
	}
}
