package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ApprovalRequestPayload
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ThreadItem
import kotlinx.serialization.json.JsonElement

enum class ConnectionState {
	Disconnected,
	Connecting,
	Connected,
	Reconnecting,
}

enum class TurnState {
	Idle,
	Sending,
	Running,
	WaitingApproval,
	Completed,
	Failed,
	Canceled,
}

enum class Screen {
	Chat,
	Settings,
}

sealed interface ChatMessage {
	val id: String

	data class User(
		override val id: String,
		val text: String,
	) : ChatMessage

	data class Agent(
		override val id: String,
		val text: String,
		val streaming: Boolean = false,
	) : ChatMessage

	data class Tool(
		override val id: String,
		val title: String,
		val status: String,
		val detail: String,
	) : ChatMessage

	data class FileChange(
		override val id: String,
		val action: String,
		val path: String,
		val status: String,
		val preview: String?,
	) : ChatMessage

	data class TurnSummary(
		override val id: String,
		val summary: ThreadItem.TurnSummary,
	) : ChatMessage
}

data class RuntimeEvent(
	val id: String,
	val title: String,
	val detail: String,
	val raw: JsonElement? = null,
)

data class WorkspaceContext(
	val projectName: String = "BaBiQ",
	val cwd: String = "E:\\BaBiQ",
	val mode: String = "本地模式",
	val branch: String = "master",
	val worktree: String = "worktree",
	val permission: String = "完全访问权限",
)

data class ProviderSelection(
	val providerId: String = "mock-provider",
	val modelId: String? = null,
	val label: String = "Mock (P1-1 placeholder)",
)

data class ProviderState(
	val providers: List<ProviderInfo> = emptyList(),
	val active: ProviderSelection = ProviderSelection(),
	val loading: Boolean = false,
	val error: String? = null,
)

data class PendingApproval(
	val threadId: String,
	val turnId: String,
	val itemId: String,
	val toolName: String,
	val arguments: String,
	val description: String,
) {
	companion object {
		fun from(payload: ApprovalRequestPayload): PendingApproval =
			PendingApproval(
				threadId = payload.threadId,
				turnId = payload.turnId,
				itemId = payload.itemId,
				toolName = payload.toolName,
				arguments = payload.arguments,
				description = payload.description,
			)
	}
}

sealed interface AgentEvent {
	data class Server(val event: com.wzx.babiq.desktop.protocol.ServerEvent) : AgentEvent
	data class ConnectionChanged(val state: ConnectionState) : AgentEvent
	data class RequestFailed(val message: String) : AgentEvent
}
