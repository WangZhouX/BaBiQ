package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 重连退避策略是一个很小的策略对象。
 *
 * Kotlin 里把这种纯配置 + 纯函数写成 data class 很常见：调用方可以在测试中注入更短的延迟，
 * 生产环境则使用默认的 1s -> 2s -> 4s -> 8s -> 10s 上限，避免后端暂时不可用时疯狂重试。
 *
 * @property initialDelayMs 第一次自动重连前等待多久，单位毫秒。
 * @property maxDelayMs 指数退避允许增长到的最大等待时间，单位毫秒。
 */
data class ReconnectPolicy(
	val initialDelayMs: Long = 1_000,
	val maxDelayMs: Long = 10_000,
) {
	fun nextDelayAfter(previousDelayMs: Long): Long =
		(previousDelayMs * 2).coerceAtMost(maxDelayMs)
}

/**
 * ChatController 是 Compose UI 和 AgentGateway 之间的协调层。
 *
 * 这里刻意不让 Composable 直接调用 JSON-RPC 或改 reducer 状态：UI 只表达用户意图，
 * Controller 负责异步调用后端，Reducer 负责把协议事件折叠成稳定的 AppState。
 * 这种分层能让协议、状态和界面分别测试，也方便你学习 Kotlin 时逐层阅读。
 *
 * @param gateway Agent 后端访问入口，真实运行时是 AgentClient，测试时可以换成 Fake。
 * @param scope Controller 自己启动协程的作用域，统一管理连接监听、发送请求和重连任务。
 * @param initialState 初始 UI 状态，测试可以传入特定状态验证某个分支。
 * @param reconnectPolicy 自动重连退避策略，避免断线时过于频繁地请求后端。
 */
class ChatController(
	private val gateway: AgentGateway,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	initialState: AppState = AppState.empty(),
	private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {
	// 私有可变状态流；只有 Controller/Reducer 可以写，Composable 只能读公开的 state。
	private val _state = MutableStateFlow(initialState)
	// 是否已经开始收集后端事件；防止多次 connect 后同一个事件被重复处理。
	private var collectingEvents = false
	// 当前自动重连任务；手动重连或连接成功时需要取消旧任务。
	private var reconnectJob: Job? = null

	/** 对 UI 暴露的只读状态流，Compose 会 collect 它并自动刷新界面。 */
	val state: StateFlow<AppState> = _state

	suspend fun connect() {
		// 用户点击“重试”时应立即尝试一次，所以先取消后台自动重连任务。
		reconnectJob?.cancel()
		reconnectJob = null
		applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Connecting))
		try {
			connectOnce()
		} catch (exception: Exception) {
			handleConnectionFailure(exception)
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

	fun selectWorkspace(cwd: String) {
		val selected = normalizeWorkspace(cwd)
		if (selected == null) {
			_state.update {
				it.copy(
					lastError = "工作目录无效: $cwd",
					bannerMessage = "工作目录无效: $cwd",
				)
			}
			return
		}
		val current = state.value
		if (current.turnState in setOf(TurnState.Sending, TurnState.Running, TurnState.WaitingApproval)) {
			_state.update {
				it.copy(
					lastError = "当前 turn 仍在运行，结束后才能切换工作目录",
					bannerMessage = "当前 turn 仍在运行，结束后才能切换工作目录",
				)
			}
			return
		}
		if (selected == current.workspace.cwd) {
			return
		}

		_state.update {
			it.copy(
				workspace = it.workspace.copy(
					projectName = projectNameFrom(selected),
					cwd = selected,
				),
				// 后端 Thread 与 cwd 绑定，切换目录后必须从新 Thread 开始，避免 UI 历史和后端上下文错位。
				currentThreadId = null,
				currentTurnId = null,
				turnState = TurnState.Idle,
				messages = emptyList(),
				runtimeEvents = emptyList(),
				latestSummary = null,
				pendingApproval = null,
				lastError = null,
				bannerMessage = "已切换工作目录: $selected",
			)
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

	private suspend fun connectOnce() {
		// 一次连接尝试只做四件事：建立 WebSocket、订阅事件、读取 Provider 列表、读取权限策略。
		// 失败处理和重试节奏放在外层，避免这个函数同时承担太多职责。
		gateway.connect()
		applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Connected))
		startCollectingEvents()
		loadProviders()
		loadSandboxPolicy()
	}

	private fun handleConnectionFailure(exception: Exception) {
		_state.update {
			it.copy(
				connectionState = ConnectionState.Reconnecting,
				lastError = "连接后端失败: ${exception.message}",
				bannerMessage = "连接后端失败: ${exception.message}",
			)
		}
		scheduleReconnect()
	}

	private fun scheduleReconnect() {
		if (reconnectJob?.isActive == true) {
			return
		}
		reconnectJob = scope.launch {
			var delayMs = reconnectPolicy.initialDelayMs
			while (true) {
				// 重连期间保留 messages 和 draft，只改变连接提示；这样用户输入不会因为网络波动丢失。
				_state.update {
					it.copy(
						connectionState = ConnectionState.Reconnecting,
						bannerMessage = "连接已断开，${delayMs / 1_000} 秒后自动重试",
					)
				}
				delay(delayMs)
				try {
					connectOnce()
					reconnectJob = null
					return@launch
				} catch (exception: Exception) {
					// 这里不把 turnState 改成 Failed，因为失败的是传输连接，不一定代表后端 turn 已失败。
					_state.update {
						it.copy(
							connectionState = ConnectionState.Reconnecting,
							lastError = "连接后端失败: ${exception.message}",
							bannerMessage = "连接后端失败: ${exception.message}",
						)
					}
					delayMs = reconnectPolicy.nextDelayAfter(delayMs)
				}
			}
		}
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

	/**
	 * 从后端读取真实沙箱权限，并写入工作区上下文。
	 *
	 * 权限 chip 只是辅助信息，拉取失败不应该让 WebSocket 连接失败；因此这里仅记录错误。
	 */
	private suspend fun loadSandboxPolicy() {
		try {
			val policy = gateway.getSandboxPolicy()
			_state.update {
				it.copy(
					workspace = it.workspace.copy(
						permissionMode = policy.mode,
						permissionLabel = policy.label,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update { it.copy(lastError = exception.message) }
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

	private fun normalizeWorkspace(cwd: String): String? =
		try {
			cwd.trim().takeIf { it.isNotBlank() }
				?.let { Path.of(it).toAbsolutePath().normalize().toString() }
		} catch (_: InvalidPathException) {
			null
		}

	private fun projectNameFrom(cwd: String): String =
		Path.of(cwd).fileName?.toString()?.ifBlank { null } ?: cwd
}
