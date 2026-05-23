package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.common.EmptyState
import com.wzx.babiq.desktop.ui.common.StatusBadge
import com.wzx.babiq.desktop.ui.common.BadgeTone
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

@Composable
fun ChatScreen(
	state: AppState,
	onSend: (String) -> Unit,
	onRetryConnection: () -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onToggleRuntime: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		TopStatusLine(state, onRetryConnection, onToggleRuntime)
		if (state.messages.isEmpty()) {
			EmptyState(modifier = Modifier.weight(1f))
		} else {
			MessageList(
				messages = state.messages,
				modifier = Modifier.weight(1f),
			)
		}
		Composer(
			state = state,
			onSend = onSend,
			onSelectWorkspace = onSelectWorkspace,
			onSelectProvider = onSelectProvider,
		)
	}
}

@Composable
private fun TopStatusLine(
	state: AppState,
	onRetryConnection: () -> Unit,
	onToggleRuntime: () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		if (state.bannerMessage != null) {
			Text(text = state.bannerMessage, color = BaBiQColors.Warning)
		}
		androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatusBadge(
				text = when (state.connectionState) {
					com.wzx.babiq.desktop.state.ConnectionState.Connected -> "已连接"
					com.wzx.babiq.desktop.state.ConnectionState.Connecting -> "连接中"
					com.wzx.babiq.desktop.state.ConnectionState.Reconnecting -> "重连中"
					com.wzx.babiq.desktop.state.ConnectionState.Disconnected -> "未连接"
				},
				tone = if (state.connectionState == com.wzx.babiq.desktop.state.ConnectionState.Connected) BadgeTone.Success else BadgeTone.Warning,
			)
			StatusBadge("运行详情", BadgeTone.Info, Modifier.clickableNoRipple(onToggleRuntime))
			if (state.connectionState != com.wzx.babiq.desktop.state.ConnectionState.Connected) {
				StatusBadge("重试", BadgeTone.Warning, Modifier.clickableNoRipple(onRetryConnection))
			}
		}
	}
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
	this.then(Modifier.clickable(onClick = onClick))
