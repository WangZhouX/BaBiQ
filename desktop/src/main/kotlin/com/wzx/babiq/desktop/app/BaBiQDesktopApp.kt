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

@Composable
fun BaBiQDesktopApp() {
	val controller = remember {
		ChatController(
			gateway = AgentClient(KtorAgentTransport()),
		)
	}
	val state by controller.state.collectAsState()
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
