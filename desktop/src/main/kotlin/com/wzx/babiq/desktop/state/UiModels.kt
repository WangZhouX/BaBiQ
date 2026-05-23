package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ApprovalRequestPayload
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ThreadItem
import kotlinx.serialization.json.JsonElement

/** WebSocket 连接状态，直接驱动顶部连接徽标和发送/审批可用性。 */
enum class ConnectionState {
	Disconnected,
	Connecting,
	Connected,
	Reconnecting,
}

/** 当前 turn 的生命周期状态。 */
enum class TurnState {
	Idle,
	Sending,
	Running,
	WaitingApproval,
	Completed,
	Failed,
	Canceled,
}

/** 主内容区域当前显示的页面。 */
enum class Screen {
	Chat,
	Settings,
}

/**
 * 聊天主区可渲染的消息模型。
 *
 * 它不是后端 wire item 的一比一复制，而是 UI 友好的展示模型：例如 tool/file 会变成卡片，
 * turnSummary 会变成成本反馈条。
 */
sealed interface ChatMessage {
	/** Compose LazyColumn 用 id 来稳定列表项，避免更新时整列表闪动。 */
	val id: String

	/** 用户消息气泡。 */
	data class User(
		override val id: String,
		val text: String,
	) : ChatMessage

	/** Agent 文本消息；streaming=true 表示来自 textDelta 的临时增量。 */
	data class Agent(
		override val id: String,
		val text: String,
		val streaming: Boolean = false,
	) : ChatMessage

	/** 工具执行展示卡，例如 shell 命令或 MCP 调用。 */
	data class Tool(
		override val id: String,
		val title: String,
		val status: String,
		val detail: String,
	) : ChatMessage

	/** 文件变更展示卡。 */
	data class FileChange(
		override val id: String,
		val action: String,
		val path: String,
		val status: String,
		val preview: String?,
	) : ChatMessage

	/** 一轮任务结束后的 token、耗时和成本摘要。 */
	data class TurnSummary(
		override val id: String,
		val summary: ThreadItem.TurnSummary,
	) : ChatMessage
}

/**
 * 右侧运行详情面板里的事件。
 *
 * raw 保留未知 JSON，方便协议新增字段时调试，而不要求 UI 立即完全支持。
 */
data class RuntimeEvent(
	val id: String,
	val title: String,
	val detail: String,
	val raw: JsonElement? = null,
)

/**
 * 当前工作区上下文。
 *
 * cwd 是真正影响后端执行边界的字段；projectName、branch、worktree 等主要用于 P1-4 UI 展示，
 * 后续 P2/P3 可以逐步接入真实项目列表和 Git 状态。
 */
data class WorkspaceContext(
	val projectName: String = "BaBiQ",
	val cwd: String = "E:\\BaBiQ",
	val mode: String = "本地模式",
	val branch: String = "master",
	val worktree: String = "worktree",
	val permission: String = "完全访问权限",
)

/**
 * UI 当前选择的 provider/model。
 */
data class ProviderSelection(
	val providerId: String = "mock-provider",
	val modelId: String? = null,
	val label: String = "Mock (P1-1 placeholder)",
)

/**
 * Provider 下拉框所需的完整状态。
 */
data class ProviderState(
	val providers: List<ProviderInfo> = emptyList(),
	val active: ProviderSelection = ProviderSelection(),
	val loading: Boolean = false,
	val error: String? = null,
)

/**
 * UI 内部使用的待审批模型。
 *
 * 它从协议 payload 转换而来，保留弹窗需要的字段即可。
 */
data class PendingApproval(
	val threadId: String,
	val turnId: String,
	val itemId: String,
	val toolName: String,
	val arguments: String,
	val description: String,
) {
	companion object {
		/** 把后端 approval/request payload 转成 UI 状态模型。 */
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

/**
 * 输入 reducer 的事件类型。
 *
 * Server 是后端事件，ConnectionChanged 是传输层状态，RequestFailed 是 Controller 主动报告的请求错误。
 */
sealed interface AgentEvent {
	data class Server(val event: com.wzx.babiq.desktop.protocol.ServerEvent) : AgentEvent
	data class ConnectionChanged(val state: ConnectionState) : AgentEvent
	data class RequestFailed(val message: String) : AgentEvent
}
