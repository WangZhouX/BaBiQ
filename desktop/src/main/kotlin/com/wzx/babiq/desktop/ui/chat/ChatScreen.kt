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
import com.wzx.babiq.desktop.ui.runtime.buildPlanReminderPill
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 聊天页主屏。
 *
 * 它只负责组合顶部状态、消息列表和输入框；业务动作都通过回调交给 ChatController。
 */
@Composable
fun ChatScreen(
	state: AppState,
	onSend: (String) -> Unit,
	onRetryConnection: () -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onSelectProvider: (String, String?) -> Unit,
	onChangeSandboxMode: (String) -> Unit,
	onToggleRuntime: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(34.dp),
		verticalArrangement = Arrangement.spacedBy(18.dp),
	) {
		TopStatusLine(state, onRetryConnection, onToggleRuntime)
		if (state.messages.isEmpty()) {
			// 没有消息时显示首页空状态；这也是 P1-4 的首屏体验。
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
			onChangeSandboxMode = onChangeSandboxMode,
		)
	}
}

/**
 * 顶部连接状态和运行详情入口。
 */
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
			buildPlanReminderPill(state.planState)?.let { reminder ->
				StatusBadge(reminder, BadgeTone.Info, Modifier.clickableNoRipple(onToggleRuntime))
			}
			if (state.connectionState != com.wzx.babiq.desktop.state.ConnectionState.Connected) {
				StatusBadge("重试", BadgeTone.Warning, Modifier.clickableNoRipple(onRetryConnection))
			}
		}
	}
}

/**
 * 给 StatusBadge 加一个轻量点击修饰符，当前不定制 ripple。
 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
	this.then(Modifier.clickable(onClick = onClick))
