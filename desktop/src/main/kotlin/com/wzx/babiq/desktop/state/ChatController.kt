package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatController(
	private val gateway: AgentGateway,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	initialState: AppState = AppState.empty(),
) {
	private val _state = MutableStateFlow(initialState)
	private var collectingEvents = false

	val state: StateFlow<AppState> = _state

	suspend fun connect() {
		applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Connecting))
		try {
			gateway.connect()
			applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Connected))
			startCollectingEvents()
			loadProviders()
		} catch (exception: Exception) {
			applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Disconnected))
			_state.update {
				it.copy(
					lastError = "连接后端失败: ${exception.message}",
					bannerMessage = "连接后端失败: ${exception.message}",
				)
			}
		}
	}

	suspend fun sendMessage(text: String) {
		val prompt = text.trim()
		if (prompt.isBlank()) {
			return
		}
		val current = state.value
		if (current.connectionState != ConnectionState.Connected) {
			_state.update {
				it.copy(
					draft = text,
					lastError = "后端未连接，无法发送任务",
					bannerMessage = "后端未连接，无法发送任务",
				)
			}
			return
		}
		if (!current.canSend) {
			_state.update {
				it.copy(
					draft = text,
					lastError = "当前 turn 仍在运行，暂不能发送新任务",
					bannerMessage = "当前 turn 仍在运行，暂不能发送新任务",
				)
			}
			return
		}

		val localMessage = ChatMessage.User(id = "local-user-${current.messages.size + 1}", text = prompt)
		_state.update {
			it.copy(
				turnState = TurnState.Sending,
				draft = "",
				lastError = null,
				bannerMessage = null,
				messages = it.messages + localMessage,
			)
		}

		try {
			val threadId = current.currentThreadId ?: gateway.createThread(current.workspace.cwd)
			val turnId = gateway.startTurn(
				threadId = threadId,
				prompt = prompt,
				providerId = current.providerState.active.providerId.takeIf { selectedProvider ->
					current.providerState.providers.any { it.id == selectedProvider }
				},
			)
			_state.update {
				it.copy(
					currentThreadId = threadId,
					currentTurnId = turnId,
					turnState = TurnState.Running,
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					turnState = TurnState.Failed,
					lastError = exception.message ?: "发送失败",
					bannerMessage = exception.message ?: "发送失败",
				)
			}
		}
	}

	suspend fun respondApproval(decision: String, editedArgs: String? = null) {
		val approval = state.value.pendingApproval
		if (state.value.connectionState != ConnectionState.Connected) {
			_state.update {
				it.copy(
					lastError = "连接恢复后才能提交审批",
					bannerMessage = "连接恢复后才能提交审批",
				)
			}
			return
		}
		if (approval == null) {
			_state.update { it.copy(lastError = "当前没有待审批请求") }
			return
		}

		try {
			gateway.respondApproval(
				threadId = approval.threadId,
				turnId = approval.turnId,
				decision = decision,
				editedArgs = editedArgs,
			)
			_state.update {
				it.copy(
					pendingApproval = null,
					turnState = TurnState.Running,
					lastError = null,
					bannerMessage = null,
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					lastError = exception.message ?: "审批提交失败",
					bannerMessage = exception.message ?: "审批提交失败",
				)
			}
		}
	}

	suspend fun selectProvider(providerId: String, modelId: String? = null) {
		try {
			gateway.setActiveProvider(providerId, modelId)
			_state.update {
				it.copy(
					providerState = it.providerState.copy(
						active = ProviderSelection(providerId, modelId, modelId ?: providerId),
						error = null,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					providerState = it.providerState.copy(error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	fun showScreen(screen: Screen) {
		_state.update { it.copy(screen = screen) }
	}

	fun toggleRuntimeDetails() {
		_state.update { it.copy(runtimeExpanded = !it.runtimeExpanded) }
	}

	fun applyEvent(event: AgentEvent) {
		_state.update { ChatReducer.reduce(it, event) }
	}

	private fun startCollectingEvents() {
		if (collectingEvents) {
			return
		}
		collectingEvents = true
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			gateway.events.collect { event ->
				applyEvent(AgentEvent.Server(event))
			}
		}
	}

	private suspend fun loadProviders() {
		_state.update { it.copy(providerState = it.providerState.copy(loading = true, error = null)) }
		try {
			val providers = gateway.listProviders().providers
			_state.update {
				it.copy(
					providerState = it.providerState.copy(
						providers = providers,
						active = providers.activeSelection() ?: it.providerState.active,
						loading = false,
						error = null,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					providerState = it.providerState.copy(loading = false, error = exception.message),
				)
			}
		}
	}

	private fun List<ProviderInfo>.activeSelection(): ProviderSelection? {
		val provider = firstOrNull { it.active } ?: firstOrNull() ?: return null
		val model = provider.models.firstOrNull { it.active } ?: provider.models.firstOrNull()
		return ProviderSelection(
			providerId = provider.id,
			modelId = model?.id,
			label = model?.label ?: provider.label,
		)
	}
}
