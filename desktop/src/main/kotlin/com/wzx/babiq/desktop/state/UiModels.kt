package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ApprovalRequestPayload
import com.wzx.babiq.desktop.protocol.AppSettingsResult
import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.McpServerInfo
import com.wzx.babiq.desktop.protocol.McpToolInfo
import com.wzx.babiq.desktop.protocol.MemoryArtifactInfo
import com.wzx.babiq.desktop.protocol.MemoryJobInfo
import com.wzx.babiq.desktop.protocol.MemoryReferenceInfo
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.protocol.ObservabilitySnapshotResult
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.protocol.RunRecoveryStatusResult
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.RunTurnSummaryInfo
import com.wzx.babiq.desktop.protocol.SkillInfo
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.ThreadSummaryInfo
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
	Search,
	Settings,
	Mcp,
}

/**
 * 聊天主区可渲染的消息模型。
 *
 * 它不是后端 wire item 的一比一复制，而是 UI 友好的展示模型：例如 tool/file 会变成卡片，
 * turnSummary 会变成本轮运行反馈条。
 */
sealed interface ChatMessage {
	/** Compose LazyColumn 用 id 来稳定列表项，避免更新时整列表闪动。 */
	val id: String

	/**
	 * 用户消息气泡。
	 *
	 * @property id 列表项唯一 id。
	 * @property text 用户输入的完整文本。
	 */
	data class User(
		override val id: String,
		val text: String,
	) : ChatMessage

	/**
	 * Agent 文本消息。
	 *
	 * @property id 列表项唯一 id。
	 * @property text 当前已显示的助手文本。
	 * @property streaming true 表示来自 textDelta 的临时增量，后续 full 消息会覆盖它。
	 */
	data class Agent(
		override val id: String,
		val text: String,
		val streaming: Boolean = false,
	) : ChatMessage

	/**
	 * 工具执行展示卡，例如 shell 命令或 MCP 调用。
	 *
	 * @property id 列表项唯一 id。
	 * @property title 卡片标题，通常是工具名或命令名。
	 * @property status 工具执行状态，例如 running/completed/failed。
	 * @property detail UI 展示的简短详情，例如命令文本、退出码或摘要。
	 */
	data class Tool(
		override val id: String,
		val title: String,
		val status: String,
		val detail: String,
	) : ChatMessage

	/**
	 * 文件变更展示卡。
	 *
	 * @property id 列表项唯一 id。
	 * @property action 文件动作，例如 read/write/patch。
	 * @property path 被访问或修改的文件路径。
	 * @property status 文件动作状态。
	 * @property preview 可选内容预览，避免直接把大文件塞进聊天列表。
	 */
	data class FileChange(
		override val id: String,
		val action: String,
		val path: String,
		val status: String,
		val preview: String?,
	) : ChatMessage

	/**
	 * 一轮任务结束后的 token、耗时和工具调用摘要。
	 *
	 * @property id 列表项唯一 id。
	 * @property summary 后端 turnSummary 原始结构，渲染层会复用其中 token、duration、toolCalls 字段。
	 */
	data class TurnSummary(
		override val id: String,
		val summary: ThreadItem.TurnSummary,
	) : ChatMessage
}

/**
 * 右侧运行详情面板里的事件。
 *
 * raw 保留未知 JSON，方便协议新增字段时调试，而不要求 UI 立即完全支持。
 *
 * @property id 运行事件唯一 id。
 * @property title 面板列表中显示的标题。
 * @property detail 面板列表中显示的正文摘要。
 * @property raw 后端原始事件 JSON，主要用于调试和后续兼容新增字段。
 */
data class RuntimeEvent(
	val id: String,
	val title: String,
	val detail: String,
	val raw: JsonElement? = null,
)

/**
 * 运行详情面板里的持久化运行记录状态。
 *
 * P1-4 的 runtimeEvents 只代表“当前内存里刚收到的事件”，P2-4 增加这一层后，
 * 用户重新打开历史会话也能看到 SQLite 中保存过的 turn、审批和工具调用。
 *
 * @property loading true 表示正在读取 run/turns/list 或 run/turn/get。
 * @property error 最近一次读取运行记录失败的错误信息。
 * @property turns 当前会话的历史 turn 摘要，来自 run/turns/list。
 * @property selectedTurnId 当前详情面板选中的历史 turn id；为空时表示还没有可展示详情。
 * @property selectedDetail 当前选中 turn 的完整详情，来自 run/turn/get。
 * @property recoveryStatus 后端最近一次启动恢复报告，用于解释 interrupted/expired 状态。
 * @property observability 当前工作目录的本地可观测统计快照。
 */
data class RunRecordState(
	val loading: Boolean = false,
	val error: String? = null,
	val turns: List<RunTurnListItem> = emptyList(),
	val selectedTurnId: String? = null,
	val selectedDetail: RunTurnDetailResult? = null,
	val recoveryStatus: RunRecoveryStatusResult? = null,
	val observability: ObservabilityState = ObservabilityState(),
)

/**
 * 聊天页上下文窗口状态。
 *
 * 它是 P3-2 “当前窗口管理”的 UI 摘要层：后端保存完整快照，桌面端只在输入框附近展示最近一次窗口 token 使用率，
 * 从而让用户知道下一轮模型是否已经接近窗口上限，又不把审计数据混进聊天消息列表。
 *
 * @property loading true 表示正在读取 context/status。
 * @property status 后端 thread 级上下文窗口摘要；为空表示当前会话还没有模型输入快照。
 * @property error 最近一次读取失败原因；失败不会阻塞聊天发送。
 */
data class ContextWindowUiState(
	val loading: Boolean = false,
	val status: ContextStatusResult? = null,
	val error: String? = null,
)

/**
 * 长期记忆流水线的桌面端状态。
 *
 * 这层状态只展示后端 memory 系列接口返回的审计摘要，不把长期记忆正文塞进聊天消息列表；
 * 真正会注入模型的内容仍由后端 ContextWindowRuntime 在 read path 中按预算安装。
 *
 * @property loading true 表示正在读取 memory/status、memory/jobs/list 或 memory/artifacts/list。
 * @property status 后端长期记忆开关、候选数量和最新 generation 摘要；为空表示尚未加载。
 * @property jobs 最近后台任务，设置页用于审计 Phase1/Phase2 是否在推进。
 * @property artifacts 最近 Markdown 产物，设置页用于确认 read path 可用的 summary 版本。
 * @property searchQuery 设置页最近一次长期记忆检索 query；它只用于用户测试 read path，不会写入聊天历史。
 * @property searchStrategy 后端 memory/search 返回的检索策略，帮助用户确认当前走 summary-only 还是增强检索。
 * @property searchResults 设置页 memory/search 的引用片段结果；它们是审计预览，不代表下一轮一定注入模型。
 * @property searchTokenEstimate 最近一次检索结果的 token 估算，用于提醒用户 read path 会消耗多少上下文预算。
 * @property error 最近一次读取或写入长期记忆设置失败的错误。
 * @property notice 最近一次长期记忆操作的短提示。
 */
data class MemoryUiState(
	val loading: Boolean = false,
	val status: MemoryStatusResult? = null,
	val jobs: List<MemoryJobInfo> = emptyList(),
	val artifacts: List<MemoryArtifactInfo> = emptyList(),
	val searchQuery: String = "",
	val searchStrategy: String? = null,
	val searchResults: List<MemoryReferenceInfo> = emptyList(),
	val searchTokenEstimate: Int = 0,
	val error: String? = null,
	val notice: String? = null,
)

/**
 * P3-5 统一能力目录状态。
 *
 * @property loading true 表示正在读取 capability/status。
 * @property status 后端能力目录计数和列表，和 Agent 运行时使用同一份 SQLite 事实源。
 * @property searchResults 设置页最近一次手动搜索的能力结果。
 * @property error 最近一次能力目录读写失败原因。
 * @property notice 最近一次能力目录操作提示。
 */
data class CapabilityUiState(
	val loading: Boolean = false,
	val status: CapabilityStatusResult? = null,
	val searchResults: List<CapabilityInfo> = emptyList(),
	val error: String? = null,
	val notice: String? = null,
)

/**
 * 本地 Skill metadata 状态。
 *
 * @property loading true 表示正在读取 skills/list。
 * @property skills 当前可见 Skill metadata，不包含完整正文。
 * @property selectedContent 最近一次按需读取的 Skill 正文片段。
 * @property error 最近一次 Skill 读取失败原因。
 */
data class SkillUiState(
	val loading: Boolean = false,
	val skills: List<SkillInfo> = emptyList(),
	val selectedContent: String? = null,
	val error: String? = null,
)

/**
 * 运行详情面板里的本地可观测统计状态。
 *
 * 这部分统计和单个 turn 详情不同：它按当前工作目录汇总 SQLite 历史记录，
 * 用于让用户快速看到最近 7 天、30 天或全部范围内的运行规模、失败数和 token 用量。
 *
 * @property loading true 表示正在请求 observability/snapshot。
 * @property range 当前统计窗口，合法值为 7d、30d 或 all。
 * @property error 最近一次统计请求失败的错误；不会阻塞聊天主流程。
 * @property snapshot 后端返回的统计快照；为空表示尚未加载或加载失败。
 */
data class ObservabilityState(
	val loading: Boolean = false,
	val range: String = "7d",
	val error: String? = null,
	val snapshot: ObservabilitySnapshotResult? = null,
)

/**
 * 运行记录列表的 UI 友好模型。
 *
 * 它从协议 DTO 裁剪出列表需要的字段，让 Composable 不直接关心后端字段裁剪和空值兜底。
 *
 * @property turnId 后端 turn id，点击时用于 run/turn/get。
 * @property statusLabel 面向用户展示的状态文案。
 * @property inputPreview 用户输入摘要，避免长 prompt 撑开右侧面板。
 * @property modelLabel 本轮模型展示名，缺失时显示“未记录模型”。
 * @property timeLabel 开始到结束的简短时间文本。
 * @property recoveryReason 恢复收束原因；非恢复记录为空。
 */
data class RunTurnListItem(
	val turnId: String,
	val statusLabel: String,
	val inputPreview: String,
	val modelLabel: String,
	val timeLabel: String,
	val recoveryReason: String? = null,
) {
	companion object {
		/**
		 * 把后端运行摘要转换成右侧列表项。
		 */
		fun from(summary: RunTurnSummaryInfo): RunTurnListItem =
			RunTurnListItem(
				turnId = summary.turnId,
				statusLabel = summary.status.statusLabel(),
				inputPreview = summary.inputText.ifBlank { "空输入" }.take(80),
				modelLabel = summary.model ?: summary.providerId ?: "未记录模型",
				timeLabel = buildString {
					append(summary.startedAt.shortIsoTime())
					summary.completedAt?.let { append(" -> ").append(it.shortIsoTime()) }
				},
				recoveryReason = summary.recoveryReason,
			)
	}
}

/**
 * 将后端状态枚举映射成用户能读懂的中文标签。
 */
private fun String.statusLabel(): String =
	when (uppercase()) {
		"COMPLETED" -> "已完成"
		"FAILED" -> "失败"
		"CANCELED" -> "已取消"
		"INTERRUPTED" -> "已中断"
		"EXPIRED" -> "已过期"
		"RUNNING" -> "运行中"
		"WAITING_APPROVAL" -> "等待审批"
		"SENDING" -> "发送中"
		else -> this
	}

/**
 * 裁剪 ISO 时间字符串，保持列表紧凑。
 */
private fun String.shortIsoTime(): String =
	take(19).replace("T", " ")

/**
 * 当前工作区上下文。
 *
 * cwd 是真正影响后端执行边界的字段；projectName 来自当前目录名，用于 UI 展示。
 * permissionMode/permissionLabel 来自后端 sandbox/policy，不再由前端写死。
 *
 * @property projectName 当前项目显示名。
 * @property cwd 后端 thread/create 使用的真实工作目录，也是工具沙箱的执行边界。
 * @property permissionMode 后端真实沙箱模式，例如 DANGER_FULL_ACCESS；未连接前为空。
 * @property permissionLabel 用户可读权限文案，例如“完全访问权限”；未连接前为空。
 */
data class WorkspaceContext(
	val projectName: String = "BaBiQ",
	val cwd: String = "E:\\BaBiQ",
	val permissionMode: String? = null,
	val permissionLabel: String? = null,
)

/**
 * 左侧项目列表中的一个工作区。
 *
 * @property projectName 从 cwd 末级目录推导出的显示名，由 ChatController 生成并被 Sidebar 读取。
 * @property cwd 后端 thread 绑定的真实工作目录，点击项目时会作为下一轮 turn 的执行边界。
 * @property current true 表示这是当前输入框和新建会话正在使用的工作目录。
 */
data class WorkspaceProjectItem(
	val projectName: String,
	val cwd: String,
	val current: Boolean = false,
)

/**
 * 左侧项目列表状态。
 *
 * @property loading true 表示正在从后端全局 thread/list 汇总历史 cwd。
 * @property error 最近一次读取工作区列表失败的错误；失败不阻塞当前聊天。
 * @property items 已知工作区，包含当前工作区以及 SQLite 历史会话里出现过的 cwd。
 */
data class WorkspaceProjectState(
	val loading: Boolean = false,
	val error: String? = null,
	val items: List<WorkspaceProjectItem> = emptyList(),
)

/**
 * UI 当前选择的 provider/model。
 *
 * @property providerId 后端 provider id，例如 dashscope-default。
 * @property modelId provider 下具体模型 id；为空表示使用 provider 默认模型。
 * @property label 给用户看的组合展示文本。
 */
data class ProviderSelection(
	val providerId: String = "mock-provider",
	val modelId: String? = null,
	val label: String = "Mock (P1-1 placeholder)",
)

/**
 * Provider 下拉框所需的完整状态。
 *
 * @property providers 后端返回的 provider 列表。
 * @property active 当前 UI 选中的 provider/model。
 * @property loading true 表示正在从后端刷新模型列表。
 * @property error 刷新或切换 provider 失败时的错误文本。
 */
data class ProviderState(
	val providers: List<ProviderInfo> = emptyList(),
	val active: ProviderSelection = ProviderSelection(),
	val loading: Boolean = false,
	val error: String? = null,
)

/**
 * 设置页状态。
 *
 * @property loading true 表示正在读取后端 settings/get。
 * @property saving true 表示正在保存 Provider、沙箱或审批策略。
 * @property settings 后端当前设置快照；为空表示尚未连接或读取失败。
 * @property providerDraft Provider 新增/编辑表单草稿，API Key 只存在于桌面内存和保存请求里。
 * @property error 设置页最近一次错误。
 * @property notice 设置页短提示，例如“Provider 已保存”。
 */
data class SettingsState(
	val loading: Boolean = false,
	val saving: Boolean = false,
	val settings: AppSettingsResult? = null,
	val providerDraft: ProviderEditorState = ProviderEditorState(),
	val error: String? = null,
	val notice: String? = null,
)

/**
 * 本地 MCP 页面状态。
 *
 * P2-6 只做只读展示和手动刷新，不允许用户在 UI 中输入任意 command 后立即启动外部进程。
 *
 * @property loading true 表示正在读取 mcp/servers/list 或 mcp/tools/list。
 * @property refreshingServerId 当前正在手动刷新的 server；为空表示没有刷新动作。
 * @property servers 后端配置中的 MCP server 状态列表。
 * @property toolsByServer serverId -> 工具列表。
 * @property error 最近一次读取或刷新失败原因。
 * @property notice 最近一次成功提示。
 */
data class McpState(
	val loading: Boolean = false,
	val refreshingServerId: String? = null,
	val servers: List<McpServerInfo> = emptyList(),
	val toolsByServer: Map<String, List<McpToolInfo>> = emptyMap(),
	val error: String? = null,
	val notice: String? = null,
)

/**
 * Provider 表单草稿。
 *
 * @property providerId Provider 唯一标识。
 * @property displayName 用户可读名称。
 * @property type Provider 类型。
 * @property baseUrl OpenAI 兼容接口地址。
 * @property model 默认模型。
 * @property apiKey 用户输入的明文 API Key；保存后会清空，后端不会回填。
 * @property contextWindowText 表单里的上下文窗口文本，提交时再转成 Int。
 */
data class ProviderEditorState(
	val providerId: String = "",
	val displayName: String = "",
	val type: String = "OPENAI_COMPATIBLE",
	val baseUrl: String = "",
	val model: String = "",
	val apiKey: String = "",
	val contextWindowText: String = "0",
) {
	/**
	 * 把 UI 草稿转成协议参数。
	 */
	fun toSaveParams(): ProviderSaveParams =
		ProviderSaveParams(
			providerId = providerId.trim(),
			displayName = displayName.trim(),
			type = type.trim(),
			baseUrl = baseUrl.trim(),
			model = model.trim(),
			apiKey = apiKey.trim().ifBlank { null },
			contextWindow = contextWindowText.toIntOrNull() ?: 0,
			enabled = true,
		)
}

/**
 * Sidebar 中展示的一条最近会话。
 *
 * 它从后端 ThreadSummaryInfo 转换而来，只保留 UI 需要的字段；这样 UI 不直接依赖协议 DTO 的全部细节。
 *
 * @property threadId 会话 id，点击时用于 thread/load。
 * @property title 会话标题。
 * @property cwd 会话所属工作目录。
 * @property status 会话状态，例如 active。
 * @property lastTurnStatus 最近一轮 turn 状态。
 * @property updatedLabel 面向用户的更新时间文本。
 * @property messageCount 已保存 item 数量。
 */
data class ThreadListItem(
	val threadId: String,
	val title: String,
	val cwd: String,
	val status: String,
	val lastTurnStatus: String?,
	val updatedLabel: String,
	val messageCount: Long,
) {
	companion object {
		/** 把后端会话摘要转成 UI 列表项。 */
		fun from(summary: ThreadSummaryInfo): ThreadListItem =
			ThreadListItem(
				threadId = summary.threadId,
				title = summary.title,
				cwd = summary.cwd,
				status = summary.status,
				lastTurnStatus = summary.lastTurnStatus,
				updatedLabel = summary.updatedAt.take(16).replace("T", " "),
				messageCount = summary.messageCount,
			)
	}
}

/**
 * 最近会话列表状态。
 *
 * @property loading true 表示正在从后端刷新 thread/list。
 * @property error 最近一次加载或归档失败的错误。
 * @property items 当前工作目录下的最近会话。
 * @property selectedThreadId 当前聊天主区打开的历史会话 id。
 */
data class ThreadHistoryState(
	val loading: Boolean = false,
	val error: String? = null,
	val items: List<ThreadListItem> = emptyList(),
	val selectedThreadId: String? = null,
)

/**
 * UI 内部使用的待审批模型。
 *
 * 它从协议 payload 转换而来，保留弹窗需要的字段即可。
 *
 * @property threadId 审批所属 thread id。
 * @property turnId 审批所属 turn id。
 * @property itemId 后端 approval item id，便于日志或未来多审批定位。
 * @property toolName 触发审批的工具名。
 * @property arguments 工具原始参数 JSON 字符串，编辑后批准会基于它修改。
 * @property description 后端给用户看的审批说明。
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
