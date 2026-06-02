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

	/**
	 * 把 thread/load 返回的历史 item 批量转换成聊天消息。
	 *
	 * 这里复用实时 item 的转换规则，保证历史恢复和 WebSocket 推送看到的 UI 形态一致。
	 */
	fun messagesFromItems(items: List<ThreadItem>): List<ChatMessage> =
		items.filterNot {
			it is ThreadItem.Plan ||
				it is ThreadItem.WorkUnit ||
				it is ThreadItem.Orchestration ||
				it is ThreadItem.Team ||
				it is ThreadItem.TeamMessage
		}
			.map { it.toChatMessage() }
			.markReasoningCompleted()

	/**
	 * 从历史 item 中恢复最新未完成计划。
	 *
	 * plan item 是运行辅助状态，不进入聊天主流。历史加载时只取最后一条 plan；如果最后一条已经全部 completed，
	 * 说明该计划已经收束，右侧面板也不需要继续常驻。
	 */
	fun planStateFromItems(items: List<ThreadItem>): PlanUiState {
		val latestPlan = items.filterIsInstance<ThreadItem.Plan>().lastOrNull()
			?: return PlanUiState()
		return PlanUiState(current = latestPlan).hideIfCompleted()
	}

	/**
	 * 从历史 item 中恢复最近一次子 Agent 委派状态。
	 *
	 * 委派 item 会进入聊天流，但右侧面板也需要一份结构化状态，方便和计划区一起展示当前执行层级。
	 */
	fun subAgentStateFromItems(items: List<ThreadItem>): SubAgentUiState =
		SubAgentUiState(current = items.filterIsInstance<ThreadItem.AgentDelegation>().lastOrNull())

	/**
	 * 从历史 item 中恢复最近一次流程编排状态。
	 *
	 * 编排 item 不进入聊天流，但历史会话重新打开时右侧运行详情仍应该能看到最后一次拓扑和节点状态。
	 */
	fun orchestrationStateFromItems(items: List<ThreadItem>): OrchestrationUiState =
		OrchestrationUiState(current = items.filterIsInstance<ThreadItem.Orchestration>().lastOrNull())

	/**
	 * 从历史 item 中恢复最近一次团队协作状态。
	 *
	 * 只保留最新 teamId 对应的 teamMessage，避免打开历史会话时多个团队运行的消息交织在一起。
	 */
	fun teamStateFromItems(items: List<ThreadItem>): TeamUiState {
		val latestTeam = items.filterIsInstance<ThreadItem.Team>().lastOrNull()
			?: return TeamUiState()
		val messages = items.filterIsInstance<ThreadItem.TeamMessage>()
			.filter { it.teamId == latestTeam.teamId }
		return TeamUiState().withTeam(latestTeam).copy(messages = messages)
	}

	fun workUnitStateFromItems(items: List<ThreadItem>): WorkUnitUiState =
		items.filterIsInstance<ThreadItem.WorkUnit>()
			.fold(WorkUnitUiState()) { state, item -> state.withItem(item) }

	/**
	 * 从历史 item 中找出最新 turnSummary。
	 */
	fun latestSummaryFromItems(items: List<ThreadItem>): ThreadItem.TurnSummary? =
		items.filterIsInstance<ThreadItem.TurnSummary>().lastOrNull()

	/**
	 * reducer 唯一入口：任何状态变化都从这里进入，便于测试和排查。
	 */
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

	/**
	 * 把连接状态翻译成用户可见的 banner 文案。
	 */
	private fun reduceConnection(state: AppState, connectionState: ConnectionState): AppState {
		val banner = when (connectionState) {
			ConnectionState.Connected -> null
			ConnectionState.Connecting -> "正在连接后端..."
			ConnectionState.Reconnecting -> "连接已断开，正在重连..."
			ConnectionState.Disconnected -> "连接已断开，发送和审批已暂停"
		}
		return state.copy(connectionState = connectionState, bannerMessage = banner)
	}

	/**
	 * 后端 ServerEvent 到 AppState 的主分派函数。
	 */
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
				messages = state.messages.markReasoningCompleted(),
			)

			is ServerEvent.TurnFailed -> state.copy(
				currentThreadId = event.threadId,
				currentTurnId = event.turnId,
				turnState = TurnState.Failed,
				pendingApproval = null,
				lastError = event.reason,
				bannerMessage = event.reason,
				messages = state.messages.markReasoningCompleted(),
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

	/**
	 * 将后端 ThreadItem 合并到聊天列表和运行详情。
	 */
	private fun AppState.withItem(item: ThreadItem): AppState =
		when (item) {
			// P1-3B 后端只在 turn 结束后发 turnSummary，所以主区运行反馈条不会在 idle/running 时凭空出现。
			is ThreadItem.TurnSummary -> copy(
				latestSummary = item,
				messages = messages.upsert(ChatMessage.TurnSummary(item.id, item)),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "TurnSummary",
					detail = "模型 ${item.model}，tokens ${item.totalTokens}，工具 ${item.toolCalls} 次",
				),
			)

			is ThreadItem.ContextCompaction -> copy(
				messages = messages.upsert(item.toChatMessage()),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "ContextCompaction",
					detail = listOfNotNull(
						item.status?.let { "状态 $it" },
						item.summaryId?.let { "摘要 $it" },
						item.windowOrdinal?.let { "窗口 #$it" },
					).joinToString("，").ifBlank { item.message ?: "上下文压缩事件" },
				),
			)

			is ThreadItem.Plan -> copy(
				planState = PlanUiState(current = item, collapsed = false).hideIfCompleted(),
			)

			is ThreadItem.AgentDelegation -> copy(
				messages = messages.upsert(item.toChatMessage()),
				subAgentState = subAgentState.withCurrent(item),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "SubAgent:${item.childAgent}",
					detail = listOfNotNull(
						"${item.parentAgent} -> ${item.childAgent}",
						"状态 ${item.status}",
						"模式 ${item.mode}",
						item.toolCallCount?.let { "只读工具 $it 次" },
						item.tokenEstimate?.let { "token 估算 $it" },
						item.summary,
					).joinToString("\n"),
				),
			)

			is ThreadItem.WorkUnit -> copy(
				workUnitState = workUnitState.withItem(item),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "WorkUnit:${item.name}",
					detail = listOfNotNull(
						item.kind,
						"状态 ${item.status}",
						item.currentGoal?.let { "目标 $it" },
						"目标数 ${item.goalCount}",
					).joinToString("\n"),
				),
			)

			is ThreadItem.Orchestration -> copy(
				orchestrationState = OrchestrationUiState(current = item),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "Flow:${item.topology}",
					detail = listOfNotNull(
						item.title,
						"状态 ${item.status}",
						"节点 ${item.nodes.size}",
						if (item.approved == true && item.frozen == true) "已审批并冻结" else null,
						item.summary,
					).joinToString("\n"),
				),
			)

			is ThreadItem.Team -> copy(
				teamState = teamState.withTeam(item),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "Team:${item.title}",
					detail = listOfNotNull(
						"状态 ${item.status}",
						item.currentAgent?.let { "当前 $it" },
						item.round?.let { round -> item.maxRounds?.let { max -> "轮次 $round/$max" } ?: "轮次 $round" },
						if (item.approved == true && item.frozen == true) "已审批并冻结" else null,
						item.summary,
					).joinToString("\n"),
				),
			)

			is ThreadItem.TeamMessage -> copy(
				teamState = teamState.withMessage(item),
				runtimeEvents = runtimeEvents + RuntimeEvent(
					id = item.id,
					title = "TeamMessage:${item.messageType}",
					detail = listOfNotNull(
						"${item.fromAgent} -> ${item.toAgent}",
						item.round?.let { "第 $it 轮" },
						item.content,
					).joinToString("\n"),
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

			is ThreadItem.UserMessage -> copy(
				messages = messages.reconcileUserMessage(ChatMessage.User(item.id, item.text)),
			)
			else -> copy(messages = messages.upsert(item.toChatMessage()))
		}

	/**
	 * 把协议 item 转成 UI 气泡模型。
	 */
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
			is ThreadItem.Reasoning -> ChatMessage.Reasoning(id, text)
			is ThreadItem.TurnSummary -> ChatMessage.TurnSummary(id, this)
			is ThreadItem.Plan -> ChatMessage.Tool(id, "计划", "updated", steps.joinToString("\n") { it.description })
			is ThreadItem.AgentDelegation -> ChatMessage.Tool(
				id = id,
				title = "子 Agent · $childAgent",
				status = status,
				detail = listOfNotNull(
					summary,
					"父级 $parentAgent",
					"模式 $mode",
					toolCallCount?.let { "只读工具 $it 次" },
					tokenEstimate?.let { "token 估算 $it" },
				).joinToString("\n").ifBlank { "正在委派子 Agent" },
			)
			is ThreadItem.WorkUnit -> ChatMessage.Tool(
				id = id,
				title = "工作容器 · $name",
				status = status,
				detail = listOfNotNull(
					currentGoal,
					"类型 $kind",
					"目标数 $goalCount",
				).joinToString("\n").ifBlank { "等待配置和启动" },
			)
			is ThreadItem.Orchestration -> ChatMessage.Tool(
				id = id,
				title = "流程编排 · $topology",
				status = status,
				detail = listOfNotNull(
					title,
					summary,
					"节点 ${nodes.size}",
				).joinToString("\n").ifBlank { "正在运行流程编排" },
			)
			is ThreadItem.Team -> ChatMessage.Tool(
				id = id,
				title = "团队协作 · $title",
				status = status,
				detail = listOfNotNull(
					summary,
					currentAgent?.let { "当前 $it" },
					"成员 ${members.size}",
				).joinToString("\n").ifBlank { "正在运行团队协作" },
			)
			is ThreadItem.TeamMessage -> ChatMessage.Tool(
				id = id,
				title = "团队消息 · $messageType",
				status = "completed",
				detail = "$fromAgent -> $toAgent\n$content",
			)
			is ThreadItem.ContextCompaction -> ChatMessage.Tool(
				id = id,
				title = "上下文压缩",
				status = status ?: "SUCCESS",
				detail = listOfNotNull(
					message,
					summaryId?.let { "摘要 $it" },
					windowOrdinal?.let { "窗口 #$it" },
					estimatedTokensBefore?.let { before ->
						estimatedTokensAfter?.let { after -> "token $before -> $after" }
					},
				).joinToString("\n").ifBlank { "旧历史已压缩为短期摘要" },
			)
			is ThreadItem.Unknown -> ChatMessage.Tool(id, type, "unknown", raw.toString())
		}

	/**
	 * 最新计划全部完成后主动隐藏，避免右侧面板长期停在已经结束的 TODO 上。
	 */
	private fun PlanUiState.hideIfCompleted(): PlanUiState =
		if (allCompleted) PlanUiState() else this

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

	/**
	 * turn 进入终态或加载历史会话时，把 reasoning 标记为已完成。
	 *
	 * 已完成的 reasoning 在 UI 中默认折叠，既保留可展开审计能力，也避免历史对话被长篇思考过程撑开。
	 */
	private fun List<ChatMessage>.markReasoningCompleted(): List<ChatMessage> =
		map { message ->
			if (message is ChatMessage.Reasoning) {
				message.copy(completed = true)
			} else {
				message
			}
		}

	/**
	 * 发送消息时 Controller 会先追加一条 local-user-* 临时气泡，后端随后会发正式 userMessage。
	 * 如果两者文本一致，就用服务端 item id 替换本地临时 id，避免界面把同一条用户输入显示两遍。
	 */
	private fun List<ChatMessage>.reconcileUserMessage(message: ChatMessage.User): List<ChatMessage> {
		val existingIndex = indexOfFirst { it.id == message.id }
		if (existingIndex >= 0) {
			return toMutableList().also { messages -> messages[existingIndex] = message }
		}

		val optimisticIndex = indexOfLast {
			it is ChatMessage.User && it.id.startsWith("local-user-") && it.text == message.text
		}
		if (optimisticIndex < 0) {
			return this + message
		}
		return toMutableList().also { messages -> messages[optimisticIndex] = message }
	}
}
