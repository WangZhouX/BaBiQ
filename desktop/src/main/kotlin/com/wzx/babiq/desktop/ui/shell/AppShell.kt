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
import com.wzx.babiq.desktop.ui.chat.ChatScreen
import com.wzx.babiq.desktop.ui.runtime.RuntimeDetailsPanel
import com.wzx.babiq.desktop.ui.settings.SettingsPanel
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

@Composable
fun AppShell(
	state: AppState,
	onSend: (String) -> Unit,
	onRetryConnection: () -> Unit,
	onSelectScreen: (Screen) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onToggleRuntime: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxSize()
			.background(BaBiQColors.Background),
	) {
		Sidebar(
			state = state,
			onSelectScreen = onSelectScreen,
		)
		Box(modifier = Modifier.weight(1f)) {
			when (state.screen) {
				Screen.Chat -> ChatScreen(
					state = state,
					onSend = onSend,
					onRetryConnection = onRetryConnection,
					onSelectProvider = onSelectProvider,
					onToggleRuntime = onToggleRuntime,
				)

				Screen.Settings -> SettingsPanel(state = state)
			}
		}
		if (state.runtimeExpanded) {
			RuntimeDetailsPanel(
				state = state,
				modifier = Modifier.width(320.dp),
				onClose = onToggleRuntime,
			)
		}
	}
}
