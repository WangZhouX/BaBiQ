package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.ThreadItem

/**
 * Reducer 是“事件 -> 新状态”的纯函数集合。
 *
 * 这里不做网络请求、不启动协程、不访问 Compose API，只根据输入 state 和 event 返回新 state。
 * 这种模式适合聊天 UI：后端事件可能很多，但只要 reducer 稳定，界面行为就容易回归测试。
 */
object ChatReducer {

	fun reduce(state: AppState, event: AgentEvent): AppState =
		when (event) {
			is AgentEvent.ConnectionChanged -> reduceConnection(state, event.state)
			is AgentEvent.RequestFailed -> state.copy(
				lastError = event.message,
				bannerMessage = event.message,
				turnState = TurnState.Failed,
			)
			is AgentEvent.Server -> reduceServerEvent(state, event.event)
		}

	private fun reduceConnection(state: AppState, connectionState: ConnectionState): AppState {
		val banner = when (connectionState) {
			ConnectionState.Connected -> null
			ConnectionState.Connecting -> "正在连接后端..."
			ConnectionState.Reconnecting -> "连接已断开，正在重连..."
			ConnectionState.Disconnected -> "连接已断开，发送和审批已暂停"
		}
		return state.copy(connectionState = connectionState, bannerMessage = banner)
	}

	private fun reduceServerEvent(state: AppState, event: ServerEvent): AppState =
		when (event) {
			// turn/started 是后端确认开始执行的信号；本地 optimistic user message 已经在 Controller 里追加。
			is ServerEvent.TurnStarted -> state.copy(
				currentThreadId = event.threadId,
				currentTurnId = event.turnId,
				turnState = TurnState.Running,
				lastError = null,
			)

			is ServerEvent.ItemAdded -> state.withItem(event.item)
			is ServerEvent.ItemUpdated -> state.withItem(event.item)
			is ServerEvent.ItemCompleted -> state.withItem(event.item)
			// 审批请求会把 turn 暂停在 WaitingApproval，直到用户点 approve/deny/edit。
			is ServerEvent.ApprovalRequested -> state.copy(
				turnState = TurnState.WaitingApproval,
				pendingApproval = PendingApproval.from(event.request),
			)

			is ServerEvent.TurnCompleted -> state.copy(
				currentThreadId = event.threadId,
				currentTurnId = event.turnId,
				turnState = when (event.status.lowercase()) {
					"canceled" -> TurnState.Canceled
					else -> TurnState.Completed
				},
				pendingApproval = null,
			)

			is ServerEvent.TurnFailed -> state.copy(
				currentThreadId = event.threadId,
				currentTurnId = event.turnId,
				turnState = TurnState.Failed,
				pendingApproval = null,
				lastError = event.reason,
				bannerMessage = event.reason,
			)

			is ServerEvent.Unknown -> state.copy(
				runtimeEvents = state.runtimeEvents + RuntimeEvent(
					id = "runtime-${state.runtimeEvents.size + 1}",
					title = event.method,
					detail = "收到桌面端尚未识别的后端事件",
					raw = event.params,
				),
			)
		}

	private fun AppState.withItem(item: ThreadItem): AppState =
		when (item) {
			// P1-3B 后端只在 turn 结束后发 turnSummary，所以主区成本条不会在 idle/running 时凭空出现。
			is ThreadItem.TurnSummary -> copy(
				latestSummary = item,
				messages = messages.upsert(ChatMessage.TurnSummary(item.id, item)),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "TurnSummary",
					detail = "模型 ${item.model}，tokens ${item.totalTokens}，工具 ${item.toolCalls} 次",
				),
			)

			is ThreadItem.Unknown -> copy(
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = item.type,
					detail = "收到未来协议 item，已保留到运行详情",
					raw = item.raw,
				),
			)

			else -> copy(messages = messages.upsert(item.toChatMessage()))
		}

	private fun ThreadItem.toChatMessage(): ChatMessage =
		when (this) {
			is ThreadItem.UserMessage -> ChatMessage.User(id, text)
			is ThreadItem.AgentMessage -> ChatMessage.Agent(
				id = id,
				text = text ?: textDelta.orEmpty(),
				streaming = textDelta != null && text == null,
			)

			is ThreadItem.CommandExecution -> ChatMessage.Tool(
				id = id,
				title = command,
				status = status,
				detail = listOfNotNull(stdout, stderr).joinToString("\n").ifBlank { "等待执行结果" },
			)

			is ThreadItem.FileChange -> ChatMessage.FileChange(id, action, path, status, contentPreview)
			is ThreadItem.Reasoning -> ChatMessage.Agent(id, text)
			is ThreadItem.TurnSummary -> ChatMessage.TurnSummary(id, this)
			is ThreadItem.Unknown -> ChatMessage.Tool(id, type, "unknown", raw.toString())
		}

	/**
	 * item/updated 可能多次推送同一个 id。这里用 upsert 保持消息列表稳定：
	 * 已有消息更新原位置，新消息追加到底部，避免 Compose 列表闪动。
	 */
	private fun List<ChatMessage>.upsert(message: ChatMessage): List<ChatMessage> {
		val existingIndex = indexOfFirst { it.id == message.id }
		if (existingIndex < 0) {
			return this + message
		}
		return toMutableList().also { messages -> messages[existingIndex] = message }
	}
}
