package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ThreadItem

/**
 * AppState 是桌面端唯一的“界面真相源”。
 *
 * Compose 会订阅 StateFlow<AppState> 自动重组，所以这里尽量保持不可变 data class：
 * 每次状态变化都 copy 出新对象，而不是原地修改列表或字段。这样状态流更容易测试，也更接近函数式 UI 的写法。
 */
data class AppState(
	val screen: Screen = Screen.Chat,
	val connectionState: ConnectionState = ConnectionState.Disconnected,
	val turnState: TurnState = TurnState.Idle,
	val workspace: WorkspaceContext = WorkspaceContext(),
	val providerState: ProviderState = ProviderState(),
	val currentThreadId: String? = null,
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
	 * 审批提交必须同时满足“后端已连接”和“确实有待审批请求”。
	 * 如果断线，按钮会禁用，用户不会误以为审批已经送达。
	 */
	val canApprove: Boolean
		get() = connectionState == ConnectionState.Connected && pendingApproval != null

	companion object {
		fun empty(): AppState = AppState()
	}
}
