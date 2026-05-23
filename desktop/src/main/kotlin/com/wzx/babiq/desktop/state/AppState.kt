package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ThreadItem

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
	val canSend: Boolean
		get() = connectionState == ConnectionState.Connected &&
			turnState !in setOf(TurnState.Sending, TurnState.Running, TurnState.WaitingApproval)

	val canApprove: Boolean
		get() = connectionState == ConnectionState.Connected && pendingApproval != null

	companion object {
		fun empty(): AppState = AppState()
	}
}
