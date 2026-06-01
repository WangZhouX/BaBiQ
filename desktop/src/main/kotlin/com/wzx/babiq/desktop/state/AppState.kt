package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ThreadItem

/**
 * AppState 是桌面端唯一的“界面真相源”。
 *
 * Compose 会订阅 StateFlow<AppState> 自动重组，所以这里尽量保持不可变 data class：
 * 每次状态变化都 copy 出新对象，而不是原地修改列表或字段。这样状态流更容易测试，也更接近函数式 UI 的写法。
 *
 * @property screen 当前主区域展示哪个页面，例如聊天页或设置页。
 * @property connectionState 桌面端与后端 WebSocket 的连接状态。
 * @property turnState 当前 turn 的生命周期状态，决定发送、取消、审批按钮是否可用。
 * @property workspace 当前工作区上下文，包含 cwd 和后端返回的权限模式等输入框上下文条信息。
 * @property workspaceProjects 左侧项目列表状态，由历史会话 cwd 和当前工作区合并而来。
 * @property providerState 后端可用模型列表、当前选中模型以及加载/错误状态。
 * @property settingsState 设置页需要的本地设置快照、保存状态和表单草稿。
 * @property mcpState 本地 MCP server 状态和工具列表。
 * @property threadHistory Sidebar 最近会话列表的真实后端数据。
 * @property runRecordState 右侧运行详情面板读取到的持久化 turn、审批、工具调用和恢复报告。
 * @property contextWindowState 当前会话最近一次上下文窗口摘要，用于输入框上下文条和后续审计入口。
 * @property memoryState 长期记忆流水线状态，用于输入栏 chip 和设置页审计，不直接污染聊天消息。
 * @property capabilityState P3-5 统一能力目录状态，用于控制按需工具和 Skill 暴露。
 * @property skillState 本地 Skill metadata 状态，用于设置页查看按需装配来源。
 * @property planState 当前 turn 的计划可视化状态，来自 `plan` 协议 item，只在运行面板展示。
 * @property subAgentState 当前 turn 最近一次子 Agent 委派状态，用于右侧运行面板展示层级执行。
 * @property orchestrationState 当前 turn 最近一次流程编排状态，用于右侧运行面板展示多节点执行拓扑。
 * @property teamState 当前 turn 最近一次团队协作状态，用于右侧运行面板展示 supervisor、成员和直发消息。
 * @property currentThreadId 后端 thread id；为空表示还没有为当前工作区创建会话。
 * @property currentThreadTitle 当前打开会话的标题；新对话或未加载历史时为空。
 * @property currentTurnId 当前正在执行或刚结束的 turn id，用于取消、审批和事件归属。
 * @property messages 聊天主列表里的用户消息、助手消息、工具消息和摘要消息。
 * @property runtimeEvents 运行详情抽屉里的过程事件，例如工具调用、文件变更和错误。
 * @property latestSummary 最近一轮 turnSummary，用于底部运行反馈条。
 * @property pendingApproval 当前等待用户处理的工具审批请求。
 * @property bannerMessage 顶部临时提示，例如断线、重连或发送失败。
 * @property lastError 最近一次错误文本，方便设置页或调试面板展示。
 * @property draft 输入框草稿；发送失败或断线时会保留用户输入。
 * @property runtimeExpanded 运行详情面板是否展开。
 */
data class AppState(
	val screen: Screen = Screen.Chat,
	val connectionState: ConnectionState = ConnectionState.Disconnected,
	val turnState: TurnState = TurnState.Idle,
	val workspace: WorkspaceContext = WorkspaceContext(),
	val workspaceProjects: WorkspaceProjectState = WorkspaceProjectState(),
	val providerState: ProviderState = ProviderState(),
	val settingsState: SettingsState = SettingsState(),
	val mcpState: McpState = McpState(),
	val threadHistory: ThreadHistoryState = ThreadHistoryState(),
	val runRecordState: RunRecordState = RunRecordState(),
	val contextWindowState: ContextWindowUiState = ContextWindowUiState(),
	val memoryState: MemoryUiState = MemoryUiState(),
	val capabilityState: CapabilityUiState = CapabilityUiState(),
	val skillState: SkillUiState = SkillUiState(),
	val planState: PlanUiState = PlanUiState(),
	val subAgentState: SubAgentUiState = SubAgentUiState(),
	val orchestrationState: OrchestrationUiState = OrchestrationUiState(),
	val teamState: TeamUiState = TeamUiState(),
	val currentThreadId: String? = null,
	val currentThreadTitle: String? = null,
	val currentTurnId: String? = null,
	val messages: List<ChatMessage> = emptyList(),
	val runtimeEvents: List<RuntimeEvent> = emptyList(),
	val latestSummary: ThreadItem.TurnSummary? = null,
	val pendingApproval: PendingApproval? = null,
	val bannerMessage: String? = null,
	val lastError: String? = null,
	val draft: String = "",
	val runtimeExpanded: Boolean = false,
) {
	/**
	 * 发送按钮是否可用由状态推导，不由 UI 自己拼条件。
	 * 这种 computed property 能把业务规则集中在状态模型里，避免多个 Composable 写出不同判断。
	 */
	val canSend: Boolean
		get() = connectionState == ConnectionState.Connected &&
			turnState !in setOf(TurnState.Sending, TurnState.Running, TurnState.WaitingApproval)

	/**
	 * 工作目录是下一轮任务的执行边界；只要没有正在运行或等待审批的 turn，就允许提前切换。
	 * 它不依赖 WebSocket 连接状态，这样用户可以先选好目录，再启动或重连后端。
	 */
	val canSwitchWorkspace: Boolean
		get() = turnState !in setOf(TurnState.Sending, TurnState.Running, TurnState.WaitingApproval)

	/**
	 * 审批提交必须同时满足“后端已连接”和“确实有待审批请求”。
	 * 如果断线，按钮会禁用，用户不会误以为审批已经送达。
	 */
	val canApprove: Boolean
		get() = connectionState == ConnectionState.Connected && pendingApproval != null

	/**
	 * 设置写入会影响下一轮 turn 的默认值；运行中或等待审批时先禁止编辑，避免用户误会当前 turn 会立即改变。
	 */
	val canEditSettings: Boolean
		get() = connectionState == ConnectionState.Connected &&
			turnState !in setOf(TurnState.Sending, TurnState.Running, TurnState.WaitingApproval)

	companion object {
		fun empty(): AppState = AppState()
	}
}
