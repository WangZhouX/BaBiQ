package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.AppSettingsResult
import com.wzx.babiq.desktop.protocol.CapabilitySettingsSetParams
import com.wzx.babiq.desktop.protocol.ExecutionIntent
import com.wzx.babiq.desktop.protocol.MemorySettingsSetParams
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.SettingsUpdateParams
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
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
import java.util.Locale

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

	suspend fun sendMessage(text: String, executionIntentOverride: ExecutionIntent? = null) {
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

		val slashCommand = if (executionIntentOverride == null) SlashCommandParser.parse(prompt) else null
		val executionIntent = executionIntentOverride ?: slashCommand.toExecutionIntent()
		val submittedText = slashCommand.toSubmittedText(prompt)
		val localMessage = ChatMessage.User(id = "local-user-${current.messages.size + 1}", text = submittedText)
		_state.update {
			it.copy(
				turnState = TurnState.Sending,
				draft = "",
				lastError = null,
				bannerMessage = null,
				messages = it.messages + localMessage,
				planState = PlanUiState(),
				subAgentState = SubAgentUiState(),
				orchestrationState = OrchestrationUiState(),
				teamState = TeamUiState(),
			)
		}

		try {
			val threadId = current.currentThreadId ?: gateway.createThread(current.workspace.cwd)
			val turnId = gateway.startTurn(
				threadId = threadId,
				prompt = submittedText,
				providerId = current.providerState.active.providerId.takeIf { selectedProvider ->
					current.providerState.providers.any { it.id == selectedProvider }
				},
				executionIntent = executionIntent,
			)
			_state.update {
				it.copy(
					currentThreadId = threadId,
					currentTurnId = turnId,
					turnState = TurnState.Running,
				)
			}
		} catch (exception: Exception) {
			if (exception.isTransportDisconnectedSignal()) {
				val message = exception.message ?: "后端连接已断开，请稍后重试"
				_state.update {
					it.copy(
						connectionState = ConnectionState.Reconnecting,
						turnState = TurnState.Failed,
						lastError = message,
						bannerMessage = message,
					)
				}
				scheduleReconnect()
				return
			}
			_state.update {
				it.copy(
					turnState = TurnState.Failed,
					lastError = exception.message ?: "发送失败",
					bannerMessage = exception.message ?: "发送失败",
				)
			}
		}
	}

	private fun SlashCommand?.toExecutionIntent(): ExecutionIntent? =
		when (this) {
			is SlashCommand.WorkUnit -> ExecutionIntent.CreateWorkUnit(
				kind = when (kind) {
					WorkUnitKind.Orchestration -> "orchestration"
					WorkUnitKind.Team -> "team"
				},
				name = name,
				goal = goal,
			)
			null -> null
		}

	private fun SlashCommand?.toSubmittedText(fallback: String): String =
		when (this) {
			is SlashCommand.WorkUnit -> goal
			null -> fallback
		}

	suspend fun respondApproval(decision: String, editedArgs: String? = null, scope: String? = null) {
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
				scope = scope,
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
					settingsState = it.settingsState.copy(
						settings = it.settingsState.settings?.copy(activeProviderId = providerId),
						notice = "Provider 已切换，下一轮 turn 生效",
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

	/**
	 * 新增 Provider，并在成功后刷新 Provider 列表。
	 *
	 * API Key 不会写入 AppState，只存在于 params 这个局部对象和发往后端的 JSON-RPC 请求中。
	 */
	suspend fun createProvider(params: ProviderSaveParams) {
		saveProvider(params, updateExisting = false)
	}

	/**
	 * 编辑 Provider，并在成功后刷新 Provider 列表。
	 */
	suspend fun updateProvider(params: ProviderSaveParams) {
		saveProvider(params, updateExisting = true)
	}

	/**
	 * 删除或禁用 Provider，随后刷新列表。
	 */
	suspend fun deleteProvider(providerId: String) {
		_state.update { it.copy(settingsState = it.settingsState.copy(saving = true, error = null, notice = null)) }
		try {
			gateway.deleteProvider(providerId)
			loadProviders()
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						saving = false,
						error = null,
						notice = "Provider 已删除",
					),
				)
			}
		} catch (exception: Exception) {
			setSettingsError(exception.message ?: "删除 Provider 失败")
		}
	}

	/**
	 * 测试 Provider 配置，不修改当前 active provider。
	 */
	suspend fun testProvider(providerId: String) {
		_state.update { it.copy(settingsState = it.settingsState.copy(saving = true, error = null, notice = null)) }
		try {
			val result = gateway.testProvider(providerId)
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						saving = false,
						error = if (result.ok) null else result.message,
						notice = result.message,
					),
				)
			}
		} catch (exception: Exception) {
			setSettingsError(exception.message ?: "测试 Provider 失败")
		}
	}

	/**
	 * 刷新 Anthropic OAuth CLI 状态。
	 *
	 * 该动作只读本机 CLI 状态，不修改 Provider 配置；用户可以在保存 OAuth Provider 前后手动确认登录是否可用。
	 */
	suspend fun refreshProviderOAuthStatus() {
		_state.update {
			it.copy(
				settingsState = it.settingsState.copy(
					providerOAuthLoading = true,
					error = null,
					notice = null,
				),
			)
		}
		try {
			val status = gateway.getProviderOAuthStatus()
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						providerOAuthLoading = false,
						providerOAuthStatus = status,
						error = null,
						notice = status.message,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						providerOAuthLoading = false,
						error = exception.message ?: "读取 Claude OAuth 状态失败",
					),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 启动 Anthropic OAuth CLI 登录流程。
	 */
	suspend fun startProviderOAuthLogin() {
		_state.update {
			it.copy(
				settingsState = it.settingsState.copy(
					providerOAuthLoading = true,
					error = null,
					notice = null,
				),
			)
		}
		try {
			val result = gateway.startProviderOAuthLogin()
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						providerOAuthLoading = false,
						error = if (result.ok) null else result.message,
						notice = result.message,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						providerOAuthLoading = false,
						error = exception.message ?: "启动 Claude OAuth 登录失败",
					),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 保存默认沙箱模式，并立即刷新输入框上下文条；真实执行边界仍从下一轮 turn 开始生效。
	 */
	suspend fun saveSandboxMode(mode: String) {
		_state.update { it.copy(settingsState = it.settingsState.copy(saving = true, error = null, notice = null)) }
		try {
			val policy = gateway.setSandboxPolicy(mode)
			_state.update {
				val previousSettings = it.settingsState.settings ?: defaultSettings(it)
				it.copy(
					workspace = it.workspace.copy(permissionMode = policy.mode, permissionLabel = policy.label),
					settingsState = it.settingsState.copy(
						saving = false,
						settings = previousSettings.copy(sandboxMode = policy.mode),
						error = null,
						notice = "沙箱策略已保存，下一轮 turn 生效",
					),
				)
			}
		} catch (exception: Exception) {
			setSettingsError(exception.message ?: "保存沙箱策略失败")
		}
	}

	/**
	 * 保存默认审批策略；当前运行中的 turn 继续使用启动快照。
	 */
	suspend fun saveApprovalPolicy(policy: String) {
		_state.update { it.copy(settingsState = it.settingsState.copy(saving = true, error = null, notice = null)) }
		try {
			val result = gateway.setApprovalPolicy(policy)
			_state.update {
				val previousSettings = it.settingsState.settings ?: defaultSettings(it)
				it.copy(
					settingsState = it.settingsState.copy(
						saving = false,
						settings = previousSettings.copy(approvalPolicy = result.approvalPolicy),
						error = null,
						notice = "审批策略已保存，下一轮 turn 生效",
					),
				)
			}
		} catch (exception: Exception) {
			setSettingsError(exception.message ?: "保存审批策略失败")
		}
	}

	/**
	 * 开始一个空白新对话。
	 *
	 * 历史列表不能被清空；它代表 SQLite 中已存在的会话，而新对话只重置当前主区上下文。
	 */
	fun newChat() {
		_state.update {
			it.copy(
				currentThreadId = null,
				currentThreadTitle = null,
				currentTurnId = null,
				turnState = TurnState.Idle,
				messages = emptyList(),
				runtimeEvents = emptyList(),
				latestSummary = null,
				pendingApproval = null,
				runRecordState = RunRecordState(),
				contextWindowState = ContextWindowUiState(),
				memoryState = it.memoryState.copy(notice = null, error = null),
				planState = PlanUiState(),
				subAgentState = SubAgentUiState(),
				orchestrationState = OrchestrationUiState(),
				teamState = TeamUiState(),
				workUnitState = WorkUnitUiState(),
				threadHistory = it.threadHistory.copy(selectedThreadId = null),
				bannerMessage = null,
				lastError = null,
			)
		}
	}

	/**
	 * 打开一条历史会话，并用 thread/load 返回的 item 替换当前消息流。
	 */
	suspend fun openThread(threadId: String) {
		_state.update { it.copy(threadHistory = it.threadHistory.copy(loading = true, error = null)) }
		try {
			val loaded = gateway.loadThread(threadId)
			val messages = ChatReducer.messagesFromItems(loaded.items)
			val planState = ChatReducer.planStateFromItems(loaded.items)
			val subAgentState = ChatReducer.subAgentStateFromItems(loaded.items)
			val orchestrationState = ChatReducer.orchestrationStateFromItems(loaded.items)
			val teamState = ChatReducer.teamStateFromItems(loaded.items)
			val workUnitState = ChatReducer.workUnitStateFromItems(loaded.items)
			_state.update {
				it.copy(
					workspace = it.workspace.copy(
						projectName = projectNameFrom(loaded.thread.cwd),
						cwd = loaded.thread.cwd,
					),
					workspaceProjects = it.workspaceProjects.copy(
						items = workspaceProjectItems(it.workspaceProjects.items.map { project -> project.cwd } + loaded.thread.cwd, loaded.thread.cwd),
					),
					currentThreadId = loaded.thread.threadId,
					currentThreadTitle = loaded.thread.title,
					currentTurnId = null,
					turnState = TurnState.Idle,
					messages = messages,
					runtimeEvents = emptyList(),
					latestSummary = loaded.latestSummary ?: ChatReducer.latestSummaryFromItems(loaded.items),
					planState = planState,
					subAgentState = subAgentState,
					orchestrationState = orchestrationState,
					teamState = teamState,
					workUnitState = workUnitState,
					pendingApproval = null,
					runRecordState = if (it.runtimeExpanded) {
						it.runRecordState.copy(loading = true, error = null)
					} else {
						RunRecordState()
					},
					contextWindowState = it.contextWindowState.copy(loading = true, error = null),
					threadHistory = it.threadHistory.copy(
						loading = false,
						error = null,
						selectedThreadId = loaded.thread.threadId,
					),
					bannerMessage = null,
					lastError = null,
				)
			}
			loadContextStatus(loaded.thread.threadId)
			loadWorkUnits(loaded.thread.threadId)
			loadTeams(loaded.thread.threadId)
			loadMemoryStatus(loadAudit = false)
			if (state.value.runtimeExpanded) {
				loadRunRecords(loaded.thread.threadId)
				loadObservabilitySnapshot(state.value.runRecordState.observability.range)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					threadHistory = it.threadHistory.copy(loading = false, error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 软归档一条历史会话。
	 */
	suspend fun archiveThread(threadId: String) {
		try {
			gateway.archiveThread(threadId)
			_state.update {
				val wasCurrentThread = it.currentThreadId == threadId
				it.copy(
					currentThreadId = if (wasCurrentThread) null else it.currentThreadId,
					currentThreadTitle = if (wasCurrentThread) null else it.currentThreadTitle,
					currentTurnId = if (wasCurrentThread) null else it.currentTurnId,
					messages = if (wasCurrentThread) emptyList() else it.messages,
					latestSummary = if (wasCurrentThread) null else it.latestSummary,
					runRecordState = if (wasCurrentThread) RunRecordState() else it.runRecordState,
					contextWindowState = if (wasCurrentThread) ContextWindowUiState() else it.contextWindowState,
					planState = if (wasCurrentThread) PlanUiState() else it.planState,
					subAgentState = if (wasCurrentThread) SubAgentUiState() else it.subAgentState,
					orchestrationState = if (wasCurrentThread) OrchestrationUiState() else it.orchestrationState,
					teamState = if (wasCurrentThread) TeamUiState() else it.teamState,
					workUnitState = if (wasCurrentThread) WorkUnitUiState() else it.workUnitState,
					threadHistory = it.threadHistory.copy(
						items = it.threadHistory.items.filterNot { item -> item.threadId == threadId },
						selectedThreadId = it.threadHistory.selectedThreadId?.takeUnless { selected -> selected == threadId },
						error = null,
					),
					lastError = null,
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					threadHistory = it.threadHistory.copy(error = exception.message),
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
				workspaceProjects = it.workspaceProjects.copy(
					items = workspaceProjectItems(it.workspaceProjects.items.map { project -> project.cwd } + selected, selected),
				),
				// 后端 Thread 与 cwd 绑定，切换目录后必须从新 Thread 开始，避免 UI 历史和后端上下文错位。
				currentThreadId = null,
				currentThreadTitle = null,
				currentTurnId = null,
				turnState = TurnState.Idle,
				messages = emptyList(),
				runtimeEvents = emptyList(),
				latestSummary = null,
				pendingApproval = null,
				runRecordState = RunRecordState(),
				contextWindowState = ContextWindowUiState(),
				planState = PlanUiState(),
				subAgentState = SubAgentUiState(),
				orchestrationState = OrchestrationUiState(),
				teamState = TeamUiState(),
				workUnitState = WorkUnitUiState(),
				lastError = null,
				bannerMessage = "已切换工作目录: $selected",
			)
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			if (current.connectionState == ConnectionState.Connected) {
				persistDefaultWorkspace(selected)
			}
			loadThreadHistory(selected)
			loadWorkspaceProjects(selected)
		}
	}

	fun showScreen(screen: Screen) {
		_state.update { it.copy(screen = screen) }
		if (screen == Screen.Mcp || screen == Screen.Settings) {
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				loadMcpServers()
			}
		}
		if (screen == Screen.Plugins) {
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				loadCapabilityStatus()
				loadSkills()
			}
		}
		if (screen == Screen.Settings || screen == Screen.Search) {
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				loadMemoryStatus(loadAudit = true)
				loadCapabilityStatus()
				loadSkills()
			}
		}
	}

	/**
	 * 按需读取 Skill 正文。
	 *
	 * skills/list 只加载 metadata；只有用户在技能页明确点击“查看正文”或“刷新正文”时，
	 * 才调用后端 skills/get 读取 SKILL.md，避免把大量正文提前塞进桌面状态。
	 */
	fun openSkill(skillId: String) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			val trimmedId = skillId.trim()
			if (trimmedId.isEmpty()) {
				return@launch
			}
			_state.update {
				val metadata = it.skillState.skills.firstOrNull { skill -> skill.id == trimmedId }
				it.copy(
					skillState = it.skillState.copy(
						selectedSkillId = trimmedId,
						selectedSkill = metadata ?: it.skillState.selectedSkill,
						contentLoading = true,
						error = null,
					),
				)
			}
			try {
				val result = gateway.getSkill(trimmedId)
				_state.update {
					val mergedSkills = mergeSkillMetadata(it.skillState.skills, result.skill)
					it.copy(
						skillState = it.skillState.copy(
							loading = false,
							skills = mergedSkills,
							selectedSkillId = result.skill.id,
							selectedSkill = result.skill,
							selectedContent = result.content,
							selectedContentTruncated = result.truncated,
							contentLoading = false,
							error = null,
						),
					)
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						skillState = it.skillState.copy(
							contentLoading = false,
							error = exception.message ?: "读取 Skill 正文失败",
						),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 保存长期记忆开关。
	 *
	 * 这里调用后端 `memory/settings/set`，因此不是单纯 UI 状态切换；下一轮 context read path 和后台 worker
	 * 都会读取后端的运行时开关，保持“设置页变化”和“Agent 实际行为”一致。
	 */
	suspend fun saveMemorySettings(
		enabled: Boolean? = null,
		generateEnabled: Boolean? = null,
		readEnabled: Boolean? = null,
		retrievalEnabled: Boolean? = null,
	) {
		_state.update { it.copy(memoryState = it.memoryState.copy(loading = true, error = null, notice = null)) }
		try {
			val result = gateway.setMemorySettings(
				MemorySettingsSetParams(enabled, generateEnabled, readEnabled, retrievalEnabled),
			)
			_state.update {
				val previousStatus = it.memoryState.status
				it.copy(
					memoryState = it.memoryState.copy(
						loading = false,
						status = previousStatus?.copy(
							enabled = result.enabled,
							generateEnabled = result.generateEnabled,
							readEnabled = result.readEnabled,
							retrievalEnabled = result.retrievalEnabled,
						),
						error = null,
						notice = "长期记忆设置已保存，下一轮上下文组装生效",
					),
				)
			}
			loadMemoryStatus(loadAudit = true)
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					memoryState = it.memoryState.copy(loading = false, error = exception.message ?: "保存长期记忆设置失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 保存单个能力的启用状态或暴露模式。
	 */
	fun saveCapabilitySettings(capabilityId: String, enabled: Boolean? = null, exposureMode: String? = null) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(capabilityState = it.capabilityState.copy(loading = true, error = null, notice = null))
			}
			try {
				gateway.setCapabilitySettings(CapabilitySettingsSetParams(capabilityId, enabled, exposureMode))
				loadCapabilityStatus()
				_state.update {
					it.copy(capabilityState = it.capabilityState.copy(loading = false, notice = "能力设置已保存，下一轮 Agent 生效"))
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						capabilityState = it.capabilityState.copy(loading = false, error = exception.message ?: "保存能力设置失败"),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 手动搜索能力目录，便于用户确认 tool_search 能按需找到哪些工具和 Skill。
	 */
	fun searchCapabilities(query: String) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update { it.copy(capabilityState = it.capabilityState.copy(loading = true, error = null)) }
			try {
				val result = gateway.searchCapabilities(query)
				_state.update {
					it.copy(capabilityState = it.capabilityState.copy(loading = false, searchResults = result.results, error = null))
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						capabilityState = it.capabilityState.copy(loading = false, error = exception.message ?: "搜索能力失败"),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 手动检索长期记忆，用于设置页验证 read path 会命中哪些记忆片段。
	 *
	 * 这个动作只读后端 `memory/search`，不会把结果写入聊天历史，也不会强制下一轮模型一定注入这些片段；
	 * 真正的注入仍由后端 ContextWindowRuntime 根据当前 turn、预算和记忆开关重新装配。
	 */
	fun searchMemory(query: String) {
		val trimmedQuery = query.trim()
		if (trimmedQuery.isEmpty()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(
					memoryState = it.memoryState.copy(
						loading = true,
						error = null,
						searchQuery = trimmedQuery,
					),
				)
			}
			try {
				val result = gateway.searchMemory(trimmedQuery, state.value.currentThreadId)
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(
							loading = false,
							searchQuery = trimmedQuery,
							searchStrategy = result.strategy,
							searchResults = result.references,
							searchTokenEstimate = result.tokenEstimate,
							error = null,
						),
					)
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(
							loading = false,
							error = exception.message ?: "搜索长期记忆失败",
						),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 手动触发一次长期记忆 Phase2 归并。
	 */
	fun consolidateMemory(force: Boolean = true) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update { it.copy(memoryState = it.memoryState.copy(loading = true, error = null, notice = null)) }
			try {
				val result = gateway.consolidateMemory(force)
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(
							loading = false,
							error = null,
							notice = if (result.queued) "长期记忆归并已入队：${result.jobId}" else "当前没有满足条件的归并任务",
						),
					)
				}
				loadMemoryStatus(loadAudit = true)
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(loading = false, error = exception.message ?: "触发长期记忆归并失败"),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 手动刷新一个 MCP server。
	 *
	 * 刷新动作只影响 MCP 状态页和下一轮 Agent 可见工具目录，不会修改正在运行的 turn。
	 */
	/**
	 * 手动触发一次长期记忆 Phase1 idle 扫描。
	 *
	 * 扫描只负责把满足 idle 条件的 thread 入队，实际抽取仍由后端后台流水线执行；完成后刷新 jobs/artifacts，
	 * 让设置页能立即看到新增任务和候选变化。
	 */
	fun scanMemory() {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update { it.copy(memoryState = it.memoryState.copy(loading = true, error = null, notice = null)) }
			try {
				val result = gateway.scanMemory()
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(
							loading = false,
							error = null,
							notice = "长期记忆扫描完成，新增 Phase1 任务 ${result.queuedPhase1Jobs} 个",
						),
					)
				}
				loadMemoryStatus(loadAudit = true)
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						memoryState = it.memoryState.copy(loading = false, error = exception.message ?: "触发长期记忆扫描失败"),
						lastError = exception.message,
					)
				}
			}
		}
	}

	fun refreshMcpServer(serverId: String) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(mcpState = it.mcpState.copy(refreshingServerId = serverId, error = null, notice = null))
			}
			try {
				val refreshed = gateway.refreshMcpServer(serverId).server
				val tools = gateway.listMcpTools(serverId)
				_state.update {
					it.copy(
						mcpState = it.mcpState.copy(
							refreshingServerId = null,
							servers = replaceMcpServer(it.mcpState.servers, refreshed),
							toolsByServer = it.mcpState.toolsByServer + (serverId to tools.tools),
							error = null,
							notice = "MCP server 已刷新",
						),
					)
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						mcpState = it.mcpState.copy(
							refreshingServerId = null,
							error = exception.message ?: "刷新 MCP server 失败",
						),
						lastError = exception.message,
					)
				}
			}
		}
	}

	fun toggleRuntimeDetails() {
		val current = state.value
		val shouldExpand = !current.runtimeExpanded
		_state.update {
			it.copy(
				runtimeExpanded = shouldExpand,
				planState = if (it.planState.visible) it.planState.copy(collapsed = !shouldExpand) else it.planState,
			)
		}
		if (shouldExpand) {
			// 运行详情展开后才拉历史记录和统计快照，避免常规聊天路径承担额外数据库查询。
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				state.value.currentThreadId?.let { threadId ->
					loadRunRecords(threadId)
				}
				loadObservabilitySnapshot(state.value.runRecordState.observability.range)
			}
		}
	}

	/**
	 * 手动移除右侧子 Agent 卡片。
	 *
	 * 这个动作只影响桌面端运行面板，不删除聊天流里的 agentDelegation 消息，也不修改后端运行记录。
	 * 如果后续同一个 delegationId 继续更新，卡片保持隐藏；新的 delegationId 到来时会自动重新显示。
	 */
	fun dismissSubAgentCard() {
		_state.update { it.copy(subAgentState = it.subAgentState.dismissCurrent()) }
	}

	/**
	 * 手动移除右侧流程编排运行卡片。
	 *
	 * 这只隐藏当前桌面端详情卡片，不删除聊天历史、WorkUnit 容器或 SQLite 审计记录。
	 */
	fun dismissOrchestrationCard() {
		val current = state.value.orchestrationState.current ?: return
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				gateway.removeRuntimeItem(current.id, current.type)
				_state.update { it.copy(orchestrationState = it.orchestrationState.dismissCurrent(), lastError = null) }
			} catch (exception: Exception) {
				_state.update { it.copy(lastError = exception.message ?: "移除编排运行记录失败") }
			}
		}
	}

	/**
	 * 手动移除右侧团队运行卡片。
	 *
	 * 该动作会把对应 team item 标记为 removed；team 审计表和工具记录仍然保留。
	 */
	fun dismissTeamCard() {
		val current = state.value.teamState.current ?: return
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				gateway.removeRuntimeItem(current.id, current.type)
				_state.update { it.copy(teamState = it.teamState.dismissCurrent(), lastError = null) }
			} catch (exception: Exception) {
				_state.update { it.copy(lastError = exception.message ?: "移除团队运行记录失败") }
			}
		}
	}

	/**
	 * 手动移除一个编排/团队工作容器。
	 *
	 * 移除只影响当前列表可见性；后端仍保留 SQLite 审计记录，运行中的容器会由后端拒绝。
	 */
	fun selectWorkUnit(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		_state.update {
			it.copy(
				runtimeExpanded = true,
				workUnitState = it.workUnitState.select(workUnitId),
			)
		}
		state.value.currentThreadId?.let { threadId ->
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				loadWorkUnits(threadId)
				_state.update { it.copy(workUnitState = it.workUnitState.select(workUnitId)) }
			}
		}
	}

	/**
	 * 通过主 Agent 启动命名工作容器的当前目标。
	 *
	 * P6-4 的工作容器不是新的执行引擎：点击启动时仍发起一轮普通 turn，
	 * 但会通过 executionIntent 把 workUnitId 交给后端确定性绑定待执行目标。
	 * 主 Agent 只负责继续调用对应的编排/团队工具，启动路径仍然经过审批、沙箱和 SQLite 审计。
	 */
	fun startWorkUnit(workUnitId: String) {
		val item = state.value.workUnitState.items.firstOrNull { it.workUnitId == workUnitId } ?: return
		val prompt = workUnitStartPrompt(item)
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			sendMessage(prompt, ExecutionIntent.StartWorkUnit(workUnitId = item.workUnitId))
		}
	}

	/**
	 * 进入工作容器配置模式。
	 *
	 * 配置本身不启动 turn；右侧详情表单会通过 workunit/goal/update 直接保存待执行目标。
	 */
	fun configureWorkUnit(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		_state.update { it.withWorkUnitConfiguration(workUnitId) }
		val info = state.value.workUnitInfoFor(workUnitId)
		if (info?.status.equals("running", ignoreCase = true)) {
			return
		}
		val shouldRefreshTeams = info?.kind.equals("team", ignoreCase = true)
		val threadId = state.value.currentThreadId ?: info?.threadId ?: return
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			try {
				val result = gateway.prepareWorkUnitGoal(threadId, workUnitId)
				_state.update {
					val refreshed = it.workUnitState.details
						.filterNot { detail -> detail.workUnitId == result.workUnit.workUnitId } + result.workUnit
					val nextWorkUnitState = it.workUnitState
						.replaceAll(refreshed)
						.select(result.workUnit.workUnitId)
						.copy(loading = false, error = null)
					it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						teamState = it.teamState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						lastError = null,
					)
				}
				if (shouldRefreshTeams) {
					loadTeams(
						threadId = threadId,
						preferredTeamId = teamIdForWorkUnit(result.workUnit.workUnitId),
						loadDetail = true,
					)
				} else {
					refreshTeamsIfNeeded(threadId, listOf(result.workUnit))
				}
			} catch (exception: Exception) {
				val message = exception.message ?: "准备工作容器目标失败"
				_state.update {
					it.copy(
						workUnitState = it.workUnitState.copy(loading = false, error = message),
						lastError = message,
					)
				}
			}
		}
	}

	fun clearWorkUnitConfiguration() {
		_state.update {
			it.copy(
				runtimeExpanded = true,
				orchestrationState = it.orchestrationState.clearConfiguration(),
				teamState = it.teamState.clearConfiguration(),
			)
		}
	}

	fun updateWorkUnitGoal(workUnitId: String, goalId: String, goalText: String) {
		val detail = state.value.workUnitState.details.firstOrNull { it.workUnitId == workUnitId }
		val threadId = state.value.currentThreadId ?: detail?.threadId ?: return
		val normalizedGoal = goalText.trim()
		if (workUnitId.isBlank() || goalId.isBlank() || normalizedGoal.isBlank()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(
					runtimeExpanded = true,
					workUnitState = it.workUnitState.select(workUnitId).copy(loading = true, error = null),
				)
			}
			try {
				val result = gateway.updateWorkUnitGoal(threadId, workUnitId, goalId, normalizedGoal)
				_state.update {
					val refreshed = it.workUnitState.details
						.filterNot { detail -> detail.workUnitId == result.workUnit.workUnitId } + result.workUnit
					val nextWorkUnitState = it.workUnitState
						.replaceAll(refreshed)
						.select(result.workUnit.workUnitId)
						.copy(loading = false, error = null)
					it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						teamState = it.teamState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						lastError = null,
					)
				}
			} catch (exception: Exception) {
				val message = exception.message ?: "保存工作容器目标失败"
				_state.update {
					it.copy(
						workUnitState = it.workUnitState.copy(loading = false, error = message),
						lastError = message,
					)
				}
			}
		}
	}

	fun renameWorkUnit(workUnitId: String, name: String) {
		val detail = state.value.workUnitState.details.firstOrNull { it.workUnitId == workUnitId }
		val threadId = state.value.currentThreadId ?: detail?.threadId ?: return
		val normalizedName = name.trim()
		if (workUnitId.isBlank() || normalizedName.isBlank()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(
					runtimeExpanded = true,
					workUnitState = it.workUnitState.select(workUnitId).copy(loading = true, error = null),
				)
			}
			try {
				val result = gateway.updateWorkUnitName(threadId, workUnitId, normalizedName)
				_state.update {
					val renamedWorkUnit = it.orchestrationState.preserveDirtyConfig(result.workUnit)
					val refreshed = it.workUnitState.details
						.filterNot { detail -> detail.workUnitId == renamedWorkUnit.workUnitId } + renamedWorkUnit
					val nextWorkUnitState = it.workUnitState
						.replaceAll(refreshed)
						.select(renamedWorkUnit.workUnitId)
						.copy(loading = false, error = null)
					it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						teamState = it.teamState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						lastError = null,
					)
				}
			} catch (exception: Exception) {
				val message = exception.message ?: "保存工作容器名称失败"
				_state.update {
					it.copy(
						workUnitState = it.workUnitState.copy(loading = false, error = message),
						lastError = message,
					)
				}
			}
		}
	}

	private fun OrchestrationUiState.preserveDirtyConfig(updated: WorkUnitInfo): WorkUnitInfo {
		val draft = configuringWorkUnit
		return if (
			draft != null &&
			configDraftWorkUnitId == updated.workUnitId &&
			updated.kind.equals("orchestration", ignoreCase = true)
		) {
			updated.copy(configJson = draft.configJson, structureJson = draft.structureJson)
		} else {
			updated
		}
	}

	fun markWorkUnitConfigDraftDirty(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		_state.update {
			it.copy(orchestrationState = it.orchestrationState.markConfigDraftDirty(workUnitId))
		}
	}

	fun loadLatestWorkUnitConfig(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		_state.update {
			it.copy(orchestrationState = it.orchestrationState.acceptLatestConfig(workUnitId))
		}
		val threadId = state.value.currentThreadId
			?: state.value.workUnitState.details.firstOrNull { it.workUnitId == workUnitId }?.threadId
			?: return
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			loadWorkUnits(threadId)
		}
	}

	fun keepWorkUnitConfigDraft(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		_state.update {
			it.copy(orchestrationState = it.orchestrationState.keepLocalConfigDraft(workUnitId))
		}
	}

	fun updateWorkUnitConfig(workUnitId: String, configJson: String, structureJson: String? = null) {
		val detail = state.value.workUnitState.details.firstOrNull { it.workUnitId == workUnitId }
		val threadId = state.value.currentThreadId ?: detail?.threadId ?: return
		val normalizedConfig = configJson.trim()
		val normalizedStructure = structureJson?.trim()?.takeIf { it.isNotBlank() }
		if (workUnitId.isBlank() || normalizedConfig.isBlank()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(
					runtimeExpanded = true,
					workUnitState = it.workUnitState.select(workUnitId).copy(loading = true, error = null),
				)
			}
			try {
				val result = gateway.updateWorkUnitConfig(threadId, workUnitId, normalizedConfig, normalizedStructure)
				_state.update {
					val refreshed = it.workUnitState.details
						.filterNot { detail -> detail.workUnitId == result.workUnit.workUnitId } + result.workUnit
					val nextWorkUnitState = it.workUnitState
						.replaceAll(refreshed)
						.select(result.workUnit.workUnitId)
						.copy(loading = false, error = null)
					it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState
							.acceptLatestConfig(result.workUnit.workUnitId)
							.refreshConfiguration(nextWorkUnitState.selectedDetail)
							.markConfigDraftSaved(result.workUnit.workUnitId),
						teamState = it.teamState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						lastError = null,
					)
				}
			} catch (exception: Exception) {
				val message = exception.message ?: "保存工作容器配置失败"
				_state.update {
					it.copy(
						workUnitState = it.workUnitState.copy(loading = false, error = message),
						lastError = message,
					)
				}
			}
		}
	}

	private fun AppState.withWorkUnitConfiguration(workUnitId: String): AppState {
		val info = workUnitInfoFor(workUnitId) ?: return copy(
			runtimeExpanded = true,
			workUnitState = workUnitState.select(workUnitId),
		)
		val selectedWorkUnitState = workUnitState.select(workUnitId)
		return when (info.kind.lowercase(Locale.ROOT)) {
			"team" -> copy(
				runtimeExpanded = true,
				workUnitState = selectedWorkUnitState,
				orchestrationState = orchestrationState.clearConfiguration(),
				teamState = teamState.withConfiguration(info),
			)
			else -> copy(
				runtimeExpanded = true,
				workUnitState = selectedWorkUnitState,
				orchestrationState = orchestrationState.withConfiguration(info),
				teamState = teamState.clearConfiguration(),
			)
		}
	}

	private fun AppState.workUnitInfoFor(workUnitId: String): WorkUnitInfo? =
		workUnitState.details.firstOrNull { it.workUnitId == workUnitId }
			?: workUnitState.items.firstOrNull { it.workUnitId == workUnitId }?.toWorkUnitInfo(
				threadId = currentThreadId.orEmpty(),
				cwd = workspace.cwd,
				sandboxMode = workspace.permissionMode,
			)

	private fun ThreadItem.WorkUnit.toWorkUnitInfo(
		threadId: String,
		cwd: String,
		sandboxMode: String?,
	): WorkUnitInfo =
		WorkUnitInfo(
			workUnitId = workUnitId,
			threadId = threadId,
			kind = kind,
			name = name,
			status = status,
			currentGoalId = currentGoalId,
			cwd = cwd,
			sandboxMode = sandboxMode,
			removed = removed,
			goals = listOfNotNull(
				currentGoal?.takeIf { it.isNotBlank() }?.let { goalText ->
					WorkUnitGoalInfo(
						goalId = currentGoalId ?: "goal_$workUnitId",
						workUnitId = workUnitId,
						goalText = goalText,
						status = "pending",
					)
				},
			),
		)

	fun removeWorkUnit(workUnitId: String) {
		if (workUnitId.isBlank()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(workUnitState = it.workUnitState.copy(loading = true, error = null))
			}
			try {
				val removed = gateway.removeWorkUnit(workUnitId).toThreadItem()
				_state.update {
					val nextWorkUnitState = it.workUnitState.withItem(removed).copy(loading = false, error = null)
					it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState.copy(
							configuringWorkUnit = it.orchestrationState.configuringWorkUnit
								?.takeUnless { detail -> detail.workUnitId == workUnitId },
						),
						teamState = it.teamState.copy(
							configuringWorkUnit = it.teamState.configuringWorkUnit
								?.takeUnless { detail -> detail.workUnitId == workUnitId },
						),
						lastError = null,
					)
				}
			} catch (exception: Exception) {
				val message = exception.message ?: "移除工作容器失败"
				_state.update {
					it.copy(
						workUnitState = it.workUnitState.copy(loading = false, error = message),
						lastError = message,
					)
				}
			}
		}
	}

	private fun workUnitStartPrompt(item: ThreadItem.WorkUnit): String {
		val configJson = state.value.workUnitState.details
			.firstOrNull { it.workUnitId == item.workUnitId }
			?.configJson
			?.takeIf { it.isNotBlank() }
		val structureJson = state.value.workUnitState.details
			.firstOrNull { it.workUnitId == item.workUnitId }
			?.structureJson
			?.takeIf { it.isNotBlank() }
		val targetTool = when (item.kind.lowercase(Locale.ROOT)) {
			"team" -> "coordinate_team"
			else -> "orchestrate_flow"
		}
		val kindLabel = when (item.kind.lowercase(Locale.ROOT)) {
			"team" -> "团队"
			"orchestration" -> "编排"
			else -> item.kind
		}
		return buildString {
			structureJson?.let { append("flow structure snapshot: ").append(it).append('\n') }
			append("启动工作容器「").append(item.name).append("」的当前待执行目标。")
			append("\n类型：").append(kindLabel)
			append("\n容器 id：").append(item.workUnitId)
			item.currentGoalId?.let { append("\n目标 id：").append(it) }
			item.currentGoal?.let { append("\n目标：").append(it) }
			configJson?.let { append("\n配置快照：").append(it) }
			append("\n后端已通过 start_work_unit 执行意图绑定 workUnitId=")
				.append(item.workUnitId)
				.append(" 和当前待执行目标。")
			append("\n请直接调用 ").append(targetTool)
				.append(" 执行该目标，把运行记录写回这个工作容器。")
			append("\n不要绕过当前沙箱、审批和模型设置；如需权限请正常发起审批。")
		}
	}

	/**
	 * 向团队协作中的某个队友发送补充消息。
	 *
	 * 这个动作不会新开 turn，也不修改聊天正文；它只调用后端 `team/message/send`，
	 * 由后端把消息写入团队时间线，再把返回的 teamMessage item 合并到右侧面板。
	 */
	fun sendTeamMessage(toAgent: String, content: String) {
		val teamId = state.value.teamState.activeTeamIdForDirectMessage()
		val trimmed = content.trim()
		if (teamId.isNullOrBlank() || trimmed.isEmpty()) {
			return
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			_state.update {
				it.copy(teamState = it.teamState.copy(sendingDirect = true, directError = null))
			}
			try {
				val result = gateway.sendTeamMessage(teamId, toAgent, trimmed)
				_state.update {
					val nextTeamState = it.teamState
						.copy(selectedTeamId = teamId)
						.withMessage(result.item)
						.copy(sendingDirect = false, directDraft = "")
					it.copy(teamState = nextTeamState)
				}
			} catch (exception: Exception) {
				_state.update {
					it.copy(
						teamState = it.teamState.copy(
							sendingDirect = false,
							directError = exception.message ?: "团队消息发送失败",
						),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 选择右侧运行详情中的某一轮历史 turn。
	 *
	 * UI 只传 turnId，Controller 负责调用后端并更新 selectedDetail，保持 Composable 仍然是纯展示层。
	 */
	private fun TeamUiState.activeTeamIdForDirectMessage(): String? =
		configuringWorkUnit?.workUnitId?.let { workUnitId -> teamIdForWorkUnit(workUnitId) }
			?: current?.teamId
			?: selectedTeamId

	private fun teamIdForWorkUnit(workUnitId: String): String =
		"team_" + workUnitId.removePrefix("wu_")

	fun selectRunTurn(turnId: String) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			loadRunTurnDetail(turnId)
		}
	}

	/**
	 * 切换本地可观测统计窗口。
	 *
	 * 统计窗口只影响右侧运行详情面板，不会修改当前聊天、会话或后端 active provider。
	 */
	fun selectObservabilityRange(range: String) {
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			loadObservabilitySnapshot(range)
		}
	}

	fun applyEvent(event: AgentEvent) {
		val currentThreadBeforeReduce = state.value.currentThreadId
		val currentTurnBeforeReduce = state.value.currentTurnId
		_state.update { ChatReducer.reduce(it, event) }
		val workUnitThreadId = when (val server = (event as? AgentEvent.Server)?.event) {
			is ServerEvent.ItemAdded -> server.threadId.takeIf { server.item.shouldRefreshWorkUnits() }
			is ServerEvent.ItemUpdated -> server.threadId.takeIf { server.item.shouldRefreshWorkUnits() }
			is ServerEvent.ItemCompleted -> server.threadId.takeIf { server.item.shouldRefreshWorkUnits() }
			else -> null
		}
		val shouldRefreshFromEvent = when ((event as? AgentEvent.Server)?.event) {
			is ServerEvent.ItemCompleted -> true
			else -> currentTurnBeforeReduce != null
		}
		if (
			workUnitThreadId != null &&
			workUnitThreadId == state.value.currentThreadId &&
			currentThreadBeforeReduce == workUnitThreadId &&
			shouldRefreshFromEvent
		) {
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				loadWorkUnits(workUnitThreadId)
			}
		}
	}

	/**
	 * 刷新当前会话的团队运行摘要列表。
	 *
	 * 这个动作只影响右侧团队面板，不会把 team/teamMessage 写入主聊天消息列表。
	 */
	fun refreshTeams() {
		val threadId = state.value.currentThreadId ?: return
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			loadTeams(threadId)
		}
	}

	/**
	 * 选择右侧团队面板中的某个团队，并按需读取成员和消息详情。
	 */
	fun selectTeam(teamId: String) {
		val normalizedTeamId = teamId.trim()
		if (normalizedTeamId.isBlank()) {
			return
		}
		_state.update {
			it.copy(
				runtimeExpanded = true,
				teamState = it.teamState.openConversation(normalizedTeamId),
			)
		}
		scope.launch(start = CoroutineStart.UNDISPATCHED) {
			loadTeamDetail(normalizedTeamId)
		}
	}

	private fun ThreadItem.shouldRefreshWorkUnits(): Boolean =
		when (this) {
			is ThreadItem.WorkUnit -> true
			is ThreadItem.Orchestration -> status.isTerminalRuntimeStatus()
			is ThreadItem.Team -> status.isTerminalRuntimeStatus()
			else -> false
		}

	private fun String.isTerminalRuntimeStatus(): Boolean =
		equals("completed", ignoreCase = true) ||
			equals("failed", ignoreCase = true) ||
			equals("canceled", ignoreCase = true)

	private suspend fun connectOnce() {
		// 一次连接尝试只做五件事：建立 WebSocket、订阅事件、读取设置、读取 Provider 列表、读取权限策略。
		// 失败处理和重试节奏放在外层，避免这个函数同时承担太多职责。
		gateway.connect()
		applyEvent(AgentEvent.ConnectionChanged(ConnectionState.Connected))
		startCollectingEvents()
		loadSettings()
		loadProviders()
		loadSandboxPolicy()
		loadWorkspaceProjects(state.value.workspace.cwd)
		loadThreadHistory(state.value.workspace.cwd)
		refreshContextStatusIfAvailable()
		loadMemoryStatus(loadAudit = false)
	}

	/**
	 * 判断异常是否代表底层 WebSocket 已经断开。
	 *
	 * 这类错误不属于本轮业务失败，而是连接生命周期已经失效；Controller 需要切到 Reconnecting，
	 * 否则 UI 会继续显示“已连接”，用户再次发送时只会反复看到传输层错误。
	 */
	private fun Throwable.isTransportDisconnectedSignal(): Boolean {
		val messages = generateSequence(this) { it.cause }
			.mapNotNull { it.message }
			.map { it.lowercase(Locale.ROOT) }
			.toList()
		return messages.any { message ->
			message.contains("后端连接已断开") ||
				message.contains("尚未连接") ||
				message.contains("已断开") ||
				message.contains("channel was cancelled") ||
				message.contains("channel was closed") ||
				message.contains("connection has been closed")
		}
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
				if (event.shouldRefreshThreadHistory()) {
					loadWorkspaceProjects(state.value.workspace.cwd)
					loadThreadHistory(state.value.workspace.cwd)
					refreshRunRecordsIfVisible()
					refreshContextStatusIfAvailable()
				}
			}
		}
	}

	/**
	 * 读取当前会话的上下文窗口摘要。
	 *
	 * 这里只更新 ContextWindowUiState，不修改 messages：P3-2 的上下文窗口是模型调用前的临时输入，
	 * 不是聊天历史本身，避免“给模型看的上下文”和“用户看到的对话记录”互相污染。
	 */
	private suspend fun loadContextStatus(threadId: String) {
		_state.update {
			it.copy(contextWindowState = it.contextWindowState.copy(loading = true, error = null))
		}
		try {
			val status = gateway.getContextStatus(threadId)
			_state.update {
				if (it.currentThreadId == threadId) {
					it.copy(contextWindowState = ContextWindowUiState(loading = false, status = status, error = null))
				} else {
					it
				}
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					contextWindowState = it.contextWindowState.copy(loading = false, error = exception.message ?: "读取上下文窗口失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 有当前会话时刷新上下文窗口摘要；没有 thread 时保持空状态。
	 */
	private suspend fun refreshContextStatusIfAvailable() {
		state.value.currentThreadId?.let { loadContextStatus(it) }
	}

	/**
	 * 读取当前会话的完整工作容器列表。
	 *
	 * 历史 item 只够恢复右侧轻量卡片；目标队列、cwd 和沙箱配置来自 `workunit/list`，
	 * 所以进入详情或打开历史会话时必须刷新这一层，避免“看得到容器但不能配置”的半接入状态。
	 */
	private suspend fun loadWorkUnits(threadId: String) {
		_state.update {
			it.copy(workUnitState = it.workUnitState.copy(loading = true, error = null))
		}
		try {
			val result = gateway.listWorkUnits(threadId)
				_state.update {
					if (it.currentThreadId == threadId) {
						val nextWorkUnitState = it.workUnitState.replaceAll(result.workUnits)
						it.copy(
						workUnitState = nextWorkUnitState,
						orchestrationState = it.orchestrationState.refreshConfiguration(nextWorkUnitState.selectedDetail),
						teamState = it.teamState.refreshConfiguration(nextWorkUnitState.selectedDetail),
					)
				} else {
					it
					}
				}
				refreshTeamsIfNeeded(threadId, result.workUnits)
			} catch (exception: Exception) {
				_state.update {
					it.copy(
					workUnitState = it.workUnitState.copy(loading = false, error = exception.message ?: "读取工作容器失败"),
					lastError = exception.message,
				)
			}
		}
	}

	private suspend fun loadTeams(threadId: String, preferredTeamId: String?, loadDetail: Boolean) {
		loadTeams(threadId)
		if (!loadDetail) {
			return
		}
		val detailTeamId = preferredTeamId
			?.takeIf { teamId -> state.value.teamState.teams.any { it.teamId == teamId } }
			?: state.value.teamState.selectedTeamId?.takeIf { teamId -> state.value.teamState.teams.any { it.teamId == teamId } }
		if (detailTeamId != null) {
			loadTeamDetail(detailTeamId)
		}
	}

	private suspend fun loadTeams(threadId: String) {
		try {
			val result = gateway.listTeams(threadId)
			val teamItems = result.teams.map { it.toThreadItem() }
			_state.update {
				if (it.currentThreadId == threadId) {
					it.copy(teamState = it.teamState.withTeamList(teamItems))
				} else {
					it
				}
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(lastError = exception.message ?: "读取团队列表失败")
			}
		}
	}

	private suspend fun refreshTeamsIfNeeded(threadId: String, workUnits: List<WorkUnitInfo>) {
		if (workUnits.any { it.kind.equals("team", ignoreCase = true) }) {
			loadTeams(threadId)
		}
	}

	private suspend fun loadTeamDetail(teamId: String) {
		try {
			val result = gateway.getTeam(teamId)
			_state.update {
				val shouldApply =
					it.teamState.teams.any { team -> team.teamId == teamId } ||
						it.teamState.current?.teamId == teamId ||
						it.teamState.selectedTeamId == teamId ||
						it.currentThreadId == result.team.threadId
				if (shouldApply) {
					it.copy(
						teamState = it.teamState.withTeamDetail(
							result.toThreadTeam(),
							result.toThreadMessages(),
						),
						lastError = null,
					)
				} else {
					it
				}
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					teamState = it.teamState.copy(directError = exception.message ?: "读取团队详情失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 读取长期记忆状态。
	 *
	 * @param loadAudit true 时同时拉取 jobs/artifacts，适合设置页；聊天页只需要轻量 status chip。
	 */
	private suspend fun loadMemoryStatus(loadAudit: Boolean) {
		_state.update {
			it.copy(memoryState = it.memoryState.copy(loading = true, error = null))
		}
		try {
			val status = gateway.getMemoryStatus()
			val jobs = if (loadAudit) gateway.listMemoryJobs(20).jobs else state.value.memoryState.jobs
			val artifacts = if (loadAudit) gateway.listMemoryArtifacts(20).artifacts else state.value.memoryState.artifacts
			_state.update {
				it.copy(
					memoryState = it.memoryState.copy(
						loading = false,
						status = status,
						jobs = jobs,
						artifacts = artifacts,
						error = null,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					memoryState = it.memoryState.copy(loading = false, error = exception.message ?: "读取长期记忆状态失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 读取 P3-5 能力目录状态。
	 */
	private suspend fun loadCapabilityStatus() {
		_state.update {
			it.copy(capabilityState = it.capabilityState.copy(loading = true, error = null))
		}
		try {
			val status = gateway.getCapabilityStatus()
			_state.update {
				it.copy(capabilityState = it.capabilityState.copy(loading = false, status = status, error = null))
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					capabilityState = it.capabilityState.copy(loading = false, error = exception.message ?: "读取能力目录失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 读取本地 Skill metadata 列表。
	 */
	private suspend fun loadSkills() {
		_state.update {
			it.copy(skillState = it.skillState.copy(loading = true, error = null))
		}
		try {
			val result = gateway.listSkills()
			_state.update {
				it.copy(skillState = it.skillState.copy(loading = false, skills = result.skills, error = null))
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					skillState = it.skillState.copy(loading = false, error = exception.message ?: "读取 Skill 列表失败"),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 读取当前 thread 的运行记录列表、恢复报告，并默认选中最近一轮 turn。
	 */
	private suspend fun loadRunRecords(threadId: String) {
		_state.update {
			it.copy(runRecordState = it.runRecordState.copy(loading = true, error = null))
		}
		try {
			val recovery = gateway.getRecoveryStatus()
			val result = gateway.listRunTurns(threadId)
			val turns = result.turns.map(RunTurnListItem::from)
			val firstTurnId = turns.firstOrNull()?.turnId
			val detail = firstTurnId?.let { gateway.getRunTurn(it) }
			_state.update {
				// 用户可能在异步请求期间切换了会话；threadId 不匹配时丢弃旧结果，避免详情串台。
				if (it.currentThreadId != threadId) {
					it
				} else {
					it.copy(
						runRecordState = it.runRecordState.copy(
							loading = false,
							error = null,
							turns = turns,
							selectedTurnId = firstTurnId,
							selectedDetail = detail,
							recoveryStatus = recovery,
						),
					)
				}
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					runRecordState = it.runRecordState.copy(loading = false, error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	/**
	 * 只刷新某一轮详情，供右侧运行记录列表点击时调用。
	 */
	private suspend fun loadRunTurnDetail(turnId: String) {
		_state.update {
			it.copy(
				runRecordState = it.runRecordState.copy(
					loading = true,
					error = null,
					selectedTurnId = turnId,
					selectedDetail = null,
				),
			)
		}
		try {
			val detail = gateway.getRunTurn(turnId)
			_state.update {
				if (it.runRecordState.selectedTurnId != turnId) {
					it
				} else {
					it.copy(
						runRecordState = it.runRecordState.copy(
							loading = false,
							error = null,
							selectedTurnId = turnId,
							selectedDetail = detail,
						),
					)
				}
			}
		} catch (exception: Exception) {
			_state.update {
				if (it.runRecordState.selectedTurnId != turnId) {
					it
				} else {
					it.copy(
						runRecordState = it.runRecordState.copy(loading = false, error = exception.message),
						lastError = exception.message,
					)
				}
			}
		}
	}

	/**
	 * 当前详情面板可见时，turn 完成或失败事件会触发一次运行记录刷新。
	 */
	private suspend fun refreshRunRecordsIfVisible() {
		val current = state.value
		if (current.runtimeExpanded) {
			current.currentThreadId?.let { loadRunRecords(it) }
			loadObservabilitySnapshot(current.runRecordState.observability.range)
		}
	}

	/**
	 * 读取当前工作目录下的本地统计快照。
	 *
	 * 统计失败时只写入 RunRecordState.observability.error，不改 turnState 和 messages；
	 * 这样观测面板临时不可用也不会破坏聊天主流程。
	 */
	private suspend fun loadObservabilitySnapshot(range: String) {
		val cwd = state.value.workspace.cwd
		_state.update {
			it.copy(
				runRecordState = it.runRecordState.copy(
					observability = it.runRecordState.observability.copy(
						loading = true,
						range = range,
						error = null,
					),
				),
			)
		}
		try {
			val snapshot = gateway.getObservabilitySnapshot(range, cwd)
			_state.update {
				it.copy(
					runRecordState = it.runRecordState.copy(
						observability = it.runRecordState.observability.copy(
							loading = false,
							range = snapshot.range,
							error = null,
							snapshot = snapshot,
						),
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					runRecordState = it.runRecordState.copy(
						observability = it.runRecordState.observability.copy(
							loading = false,
							error = exception.message ?: "读取本地统计失败",
						),
					),
				)
			}
		}
	}

	private suspend fun loadThreadHistory(cwd: String = state.value.workspace.cwd) {
		_state.update { it.copy(threadHistory = it.threadHistory.copy(loading = true, error = null)) }
		try {
			val result = gateway.listThreads(cwd)
			_state.update {
				it.copy(
					threadHistory = it.threadHistory.copy(
						loading = false,
						error = null,
						items = result.threads.map(ThreadListItem::from),
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					threadHistory = it.threadHistory.copy(loading = false, error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	private suspend fun loadWorkspaceProjects(currentCwd: String = state.value.workspace.cwd) {
		_state.update {
			it.copy(workspaceProjects = it.workspaceProjects.copy(loading = true, error = null))
		}
		try {
			val result = gateway.listThreads(cwd = null, limit = 100)
			_state.update { current ->
				val knownCwds = result.threads.map { it.cwd } +
					current.workspace.cwd +
					listOfNotNull(current.settingsState.settings?.defaultCwd) +
					currentCwd
				current.copy(
					workspaceProjects = current.workspaceProjects.copy(
						loading = false,
						error = null,
						items = workspaceProjectItems(knownCwds, current.workspace.cwd),
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					workspaceProjects = it.workspaceProjects.copy(loading = false, error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	private suspend fun loadSettings() {
		_state.update { it.copy(settingsState = it.settingsState.copy(loading = true, error = null)) }
		try {
			val settings = gateway.getSettings()
			_state.update {
				val defaultCwd = settings.defaultCwd?.let(::normalizeWorkspace)
				val workspace = if (defaultCwd.isNullOrBlank()) {
					it.workspace
				} else {
					it.workspace.copy(
						projectName = projectNameFrom(defaultCwd),
						cwd = defaultCwd,
					)
				}
				it.copy(
					workspace = workspace,
					workspaceProjects = it.workspaceProjects.copy(
						items = workspaceProjectItems(
							it.workspaceProjects.items.map { project -> project.cwd } + workspace.cwd,
							workspace.cwd,
						),
					),
					settingsState = it.settingsState.copy(
						loading = false,
						settings = settings,
						error = null,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(loading = false, error = exception.message),
					lastError = exception.message,
				)
			}
		}
	}

	private suspend fun persistDefaultWorkspace(cwd: String) {
		try {
			val updated = gateway.updateSettings(SettingsUpdateParams(defaultCwd = cwd))
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(settings = updated, error = null),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(error = "工作目录已切换，但默认目录保存失败: ${exception.message}"),
					lastError = exception.message,
				)
			}
		}
	}

	private suspend fun saveProvider(params: ProviderSaveParams, updateExisting: Boolean) {
		_state.update { it.copy(settingsState = it.settingsState.copy(saving = true, error = null, notice = null)) }
		try {
			if (updateExisting) {
				gateway.updateProvider(params)
			} else {
				gateway.createProvider(params)
			}
			loadProviders()
			_state.update {
				it.copy(
					settingsState = it.settingsState.copy(
						saving = false,
						error = null,
						notice = "Provider 已保存，下一轮 turn 生效",
						providerDraft = ProviderEditorState(),
					),
				)
			}
		} catch (exception: Exception) {
			setSettingsError(exception.message ?: "保存 Provider 失败")
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
	 * 读取 MCP server 状态和工具列表。
	 *
	 * MCP 是外部进程能力，所以加载失败只写入 mcpState.error，不影响聊天连接状态。
	 */
	private suspend fun loadMcpServers() {
		_state.update { it.copy(mcpState = it.mcpState.copy(loading = true, error = null, notice = null)) }
		try {
			val servers = gateway.listMcpServers().servers
			val toolsByServer = servers.associate { server ->
				val tools = gateway.listMcpTools(server.serverId)
				server.serverId to tools.tools
			}
			_state.update {
				it.copy(
					mcpState = it.mcpState.copy(
						loading = false,
						servers = servers,
						toolsByServer = toolsByServer,
						error = null,
					),
				)
			}
		} catch (exception: Exception) {
			_state.update {
				it.copy(
					mcpState = it.mcpState.copy(loading = false, error = exception.message ?: "读取 MCP 状态失败"),
					lastError = exception.message,
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

	private fun setSettingsError(message: String) {
		_state.update {
			it.copy(
				settingsState = it.settingsState.copy(saving = false, error = message),
				lastError = message,
			)
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

	private fun defaultSettings(state: AppState): AppSettingsResult =
		AppSettingsResult(
			activeProviderId = state.providerState.active.providerId.takeIf { it.isNotBlank() },
			sandboxMode = state.workspace.permissionMode ?: "WORKSPACE_WRITE",
			approvalPolicy = "ON_REQUEST",
			defaultCwd = state.workspace.cwd,
		)

	private fun normalizeWorkspace(cwd: String): String? =
		try {
			cwd.trim().takeIf { it.isNotBlank() }
				?.let { Path.of(it).toAbsolutePath().normalize().toString() }
		} catch (_: InvalidPathException) {
			null
		}

	private fun workspaceProjectItems(cwds: List<String>, currentCwd: String): List<WorkspaceProjectItem> {
		val current = normalizeWorkspace(currentCwd) ?: currentCwd.trim()
		val normalized = (cwds + current)
			.asSequence()
			.mapNotNull { normalizeWorkspace(it) }
			.filter { it.isNotBlank() }
			.distinctBy { workspaceKey(it) }
			.toList()
		return normalized.map {
			WorkspaceProjectItem(
				projectName = projectNameFrom(it),
				cwd = it,
				current = workspaceKey(it) == workspaceKey(current),
			)
		}
	}

	private fun workspaceKey(cwd: String): String =
		cwd.lowercase(Locale.ROOT)

	private fun projectNameFrom(cwd: String): String =
		Path.of(cwd).fileName?.toString()?.ifBlank { null } ?: cwd

	private fun replaceMcpServer(
		servers: List<com.wzx.babiq.desktop.protocol.McpServerInfo>,
		replacement: com.wzx.babiq.desktop.protocol.McpServerInfo,
	): List<com.wzx.babiq.desktop.protocol.McpServerInfo> {
		val replaced = servers.map { if (it.serverId == replacement.serverId) replacement else it }
		return if (replaced.any { it.serverId == replacement.serverId }) replaced else replaced + replacement
	}

	private fun mergeSkillMetadata(
		skills: List<com.wzx.babiq.desktop.protocol.SkillInfo>,
		replacement: com.wzx.babiq.desktop.protocol.SkillInfo,
	): List<com.wzx.babiq.desktop.protocol.SkillInfo> {
		val replaced = skills.map { if (it.id == replacement.id) replacement else it }
		return if (replaced.any { it.id == replacement.id }) replaced else replaced + replacement
	}

	private fun ServerEvent.shouldRefreshThreadHistory(): Boolean =
		this is ServerEvent.TurnCompleted || this is ServerEvent.TurnFailed
}
