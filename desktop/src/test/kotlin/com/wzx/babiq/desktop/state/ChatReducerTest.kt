package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ApprovalRequestPayload
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.ThreadItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatReducerTest {

	@Test
	fun `approval request moves state to waiting approval`() {
		val state = AppState.empty().copy(turnState = TurnState.Running)
		val request = sampleApproval()

		val next = ChatReducer.reduce(state, AgentEvent.Server(ServerEvent.ApprovalRequested(request)))

		assertEquals(TurnState.WaitingApproval, next.turnState)
		assertEquals("approval-1", next.pendingApproval?.itemId)
	}

	@Test
	fun `turnSummary updates latest summary and keeps chat history`() {
		val state = AppState.empty().copy(
			messages = listOf(ChatMessage.User("u1", "分析项目")),
			turnState = TurnState.Running,
		)
		val summary = ThreadItem.TurnSummary(
			id = "summary-1",
			status = "completed",
			model = "qwen-plus",
			promptTokens = 100,
			completionTokens = 20,
			totalTokens = 120,
			toolCalls = 2,
			durationMs = 1500,
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", summary)),
		)

		assertEquals(summary, next.latestSummary)
		assertEquals(2, next.messages.size)
		assertIs<ChatMessage.TurnSummary>(next.messages.last())
	}

	@Test
	fun `server userMessage replaces matching optimistic user message`() {
		val state = AppState.empty().copy(
			messages = listOf(ChatMessage.User("local-user-1", "你好啊")),
			turnState = TurnState.Running,
		)
		val serverUserMessage = ThreadItem.UserMessage(
			id = "it-user-1",
			text = "你好啊",
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", serverUserMessage)),
		)

		assertEquals(1, next.messages.size)
		val userMessage = assertIs<ChatMessage.User>(next.messages.single())
		assertEquals("it-user-1", userMessage.id)
		assertEquals("你好啊", userMessage.text)
	}

	@Test
	fun `turn failed preserves messages and records visible error`() {
		val state = AppState.empty().copy(
			messages = listOf(ChatMessage.Agent("a1", "已经读取 README")),
			turnState = TurnState.Running,
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.TurnFailed("thread-1", "turn-1", "模型超时")),
		)

		assertEquals(TurnState.Failed, next.turnState)
		assertEquals("模型超时", next.lastError)
		assertEquals(1, next.messages.size)
	}

	@Test
	fun `reasoning item becomes reasoning message instead of agent text`() {
		val state = AppState.empty().copy(turnState = TurnState.Running)
		val item = ThreadItem.Reasoning(
			id = "it-reasoning-1",
			text = "先查看目录，再决定是否读取文件。",
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", item)),
		)

		val message = assertIs<ChatMessage.Reasoning>(next.messages.single())
		assertEquals("it-reasoning-1", message.id)
		assertEquals("先查看目录，再决定是否读取文件。", message.text)
		assertFalse(message.completed)
	}

	@Test
	fun `turn completed marks reasoning messages completed`() {
		val state = AppState.empty().copy(
			messages = listOf(ChatMessage.Reasoning("it-reasoning-1", "思考过程", completed = false)),
			turnState = TurnState.Running,
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.TurnCompleted("thread-1", "turn-1", "completed")),
		)

		val message = assertIs<ChatMessage.Reasoning>(next.messages.single())
		assertTrue(message.completed)
	}

	@Test
	fun `turn failed marks reasoning messages completed`() {
		val state = AppState.empty().copy(
			messages = listOf(ChatMessage.Reasoning("it-reasoning-1", "reasoning before failure", completed = false)),
			turnState = TurnState.Running,
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.TurnFailed("thread-1", "turn-1", "failed")),
		)

		val message = assertIs<ChatMessage.Reasoning>(next.messages.single())
		assertTrue(message.completed)
	}

	@Test
	fun `history reasoning messages are completed by default`() {
		val messages = ChatReducer.messagesFromItems(
			listOf(ThreadItem.Reasoning("it-reasoning-1", text = "历史思考过程")),
		)

		val message = assertIs<ChatMessage.Reasoning>(messages.single())
		assertEquals("历史思考过程", message.text)
		assertTrue(message.completed)
	}

	@Test
	fun `unknown server event goes to runtime details`() {
		val state = AppState.empty()
		val event = ServerEvent.Unknown("vendor/custom", com.wzx.babiq.desktop.protocol.protocolJson.parseToJsonElement("{}"))

		val next = ChatReducer.reduce(state, AgentEvent.Server(event))

		assertEquals(1, next.runtimeEvents.size)
		assertEquals("vendor/custom", next.runtimeEvents.single().title)
	}

	@Test
	fun `connection change updates disabled state inputs`() {
		val state = AppState.empty()

		val next = ChatReducer.reduce(state, AgentEvent.ConnectionChanged(ConnectionState.Disconnected))

		assertEquals(ConnectionState.Disconnected, next.connectionState)
		assertNotNull(next.bannerMessage)
	}

	@Test
	fun `plan item updates plan state without adding chat message`() {
		val state = AppState.empty().copy(messages = listOf(ChatMessage.User("u1", "实现 P4")))
		val plan = ThreadItem.Plan(
			id = "it-plan-1",
			steps = listOf(
				ThreadItem.PlanStep(order = 1, description = "阅读计划", status = "completed"),
				ThreadItem.PlanStep(order = 2, description = "实现工具", status = "in_progress", activeForm = "正在实现工具"),
			),
			reasoning = "复杂任务需要拆分",
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", plan)),
		)

		assertEquals(1, next.messages.size)
		assertEquals("it-plan-1", next.planState.current?.id)
		assertEquals(false, next.planState.collapsed)
		assertEquals(1, next.planState.completedCount)
		assertEquals(2, next.planState.totalCount)
	}

	@Test
	fun `plan item update replaces current plan and hides when all steps completed`() {
		val state = AppState.empty().copy(
			planState = PlanUiState(
				current = ThreadItem.Plan(
					id = "it-plan-1",
					steps = listOf(ThreadItem.PlanStep(1, "阅读计划", "in_progress", "正在阅读计划")),
				),
			),
		)
		val completed = ThreadItem.Plan(
			id = "it-plan-1",
			steps = listOf(
				ThreadItem.PlanStep(1, "阅读计划", "completed", null),
				ThreadItem.PlanStep(2, "实现工具", "completed", null),
			),
		)

		val next = ChatReducer.reduce(
			state,
			AgentEvent.Server(ServerEvent.ItemUpdated("thread-1", "turn-1", completed)),
		)

		assertEquals(null, next.planState.current)
		assertEquals(false, next.planState.collapsed)
		assertEquals(0, next.messages.size)
	}

	@Test
	fun `plan state from history uses latest unfinished plan and ignores completed plan`() {
		val oldPlan = ThreadItem.Plan(
			id = "it-plan-old",
			steps = listOf(ThreadItem.PlanStep(1, "旧任务", "in_progress", "正在处理旧任务")),
		)
		val completedPlan = ThreadItem.Plan(
			id = "it-plan-completed",
			steps = listOf(ThreadItem.PlanStep(1, "完成任务", "completed", null)),
		)
		val latestPlan = ThreadItem.Plan(
			id = "it-plan-latest",
			steps = listOf(ThreadItem.PlanStep(1, "新任务", "pending", null)),
		)

		val state = ChatReducer.planStateFromItems(listOf(oldPlan, completedPlan, latestPlan))

		assertEquals("it-plan-latest", state.current?.id)
		assertEquals(1, state.totalCount)
	}

	@Test
	fun `agent delegation item updates chat and runtime subagent state`() {
		val item = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "running",
			mode = "READ_ONLY_TOOL",
			summary = "正在只读查看目录",
			toolCallCount = 2,
			tokenEstimate = 321,
		)

		val next = ChatReducer.reduce(
			AppState.empty(),
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", item)),
		)

		val message = assertIs<ChatMessage.Tool>(next.messages.single())
		assertEquals("it-agent-1", message.id)
		assertEquals("explorer", next.subAgentState.current?.childAgent)
		assertEquals(false, next.subAgentState.terminal)
		assertEquals(1, next.runtimeEvents.size)
	}

	@Test
	fun `subagent state from history uses latest delegation item`() {
		val oldItem = ThreadItem.AgentDelegation(
			id = "it-agent-old",
			delegationId = "delegation-old",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "completed",
			mode = "READ_ONLY_TOOL",
		)
		val latestItem = oldItem.copy(id = "it-agent-new", delegationId = "delegation-new", status = "running")

		val state = ChatReducer.subAgentStateFromItems(listOf(oldItem, latestItem))

		assertEquals("delegation-new", state.current?.delegationId)
		assertEquals(false, state.terminal)
	}

	@Test
	fun `subagent state from history ignores terminal delegation items`() {
		val completed = ThreadItem.AgentDelegation(
			id = "it-agent-completed",
			delegationId = "delegation-completed",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "completed",
			mode = "READ_ONLY_TOOL",
		)
		val failed = completed.copy(
			id = "it-agent-failed",
			delegationId = "delegation-failed",
			status = "failed",
		)

		val state = ChatReducer.subAgentStateFromItems(listOf(completed, failed))

		assertEquals(null, state.current)
		assertFalse(state.visible)
	}

	@Test
	fun `dismissed subagent remains hidden for same delegation and resets for new delegation`() {
		val firstItem = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "completed",
			mode = "READ_ONLY_TOOL",
		)
		val dismissedState = AppState.empty().copy(
			subAgentState = SubAgentUiState(current = firstItem, dismissedDelegationId = "delegation-1"),
		)

		val sameDelegation = ChatReducer.reduce(
			dismissedState,
			AgentEvent.Server(ServerEvent.ItemUpdated("thread-1", "turn-1", firstItem.copy(summary = "更新后的摘要"))),
		)
		assertFalse(sameDelegation.subAgentState.visible)
		assertEquals("delegation-1", sameDelegation.subAgentState.dismissedDelegationId)

		val secondItem = firstItem.copy(
			id = "it-agent-2",
			delegationId = "delegation-2",
			status = "running",
		)
		val nextDelegation = ChatReducer.reduce(
			sameDelegation,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-2", secondItem)),
		)

		assertTrue(nextDelegation.subAgentState.visible)
		assertEquals("delegation-2", nextDelegation.subAgentState.current?.delegationId)
		assertEquals(null, nextDelegation.subAgentState.dismissedDelegationId)
	}

	@Test
	fun `orchestration item updates runtime state without adding chat message`() {
		val item = ThreadItem.Orchestration(
			id = "it_orch_1",
			orchestrationId = "orch_1",
			title = "并行检查登录页",
			topology = "parallel",
			status = "running",
			summary = "流程已审批并开始执行",
			approved = true,
			frozen = true,
			nodes = listOf(
				ThreadItem.OrchestrationNode(
					nodeId = "node_scan",
					name = "scan",
					displayName = "扫描",
					status = "completed",
					mode = "READ_ONLY_TOOL",
					task = "读取文件",
					toolCallCount = 2,
				),
				ThreadItem.OrchestrationNode(
					nodeId = "node_write",
					name = "write",
					displayName = "修改",
					status = "running",
					mode = "WORKSPACE_TOOL",
					task = "写入文件",
				),
			),
		)

		val next = ChatReducer.reduce(
			AppState.empty().copy(messages = listOf(ChatMessage.User("u1", "并行检查"))),
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", item)),
		)

		assertEquals(1, next.messages.size)
		assertEquals("orch_1", next.orchestrationState.current?.orchestrationId)
		assertEquals(true, next.orchestrationState.visible)
		assertEquals(1, next.runtimeEvents.size)
		assertEquals("Flow:parallel", next.runtimeEvents.single().title)
	}

	@Test
	fun `dismissed orchestration remains hidden for same flow and resets for new flow`() {
		val firstItem = ThreadItem.Orchestration(
			id = "it_orch_1",
			orchestrationId = "orch_1",
			title = "failed flow",
			topology = "sequential",
			status = "failed",
			nodes = emptyList(),
		)
		val dismissedState = AppState.empty().copy(
			orchestrationState = OrchestrationUiState(current = firstItem, dismissedOrchestrationId = "orch_1"),
		)

		val sameFlow = ChatReducer.reduce(
			dismissedState,
			AgentEvent.Server(ServerEvent.ItemUpdated("thread-1", "turn-1", firstItem.copy(summary = "updated"))),
		)
		assertFalse(sameFlow.orchestrationState.visible)
		assertEquals("orch_1", sameFlow.orchestrationState.dismissedOrchestrationId)

		val secondItem = firstItem.copy(
			id = "it_orch_2",
			orchestrationId = "orch_2",
			status = "running",
		)
		val nextFlow = ChatReducer.reduce(
			sameFlow,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-2", secondItem)),
		)

		assertTrue(nextFlow.orchestrationState.visible)
		assertEquals("orch_2", nextFlow.orchestrationState.current?.orchestrationId)
		assertEquals(null, nextFlow.orchestrationState.dismissedOrchestrationId)
	}

	@Test
	fun `team item updates runtime team state without adding chat message`() {
		val item = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "团队协作",
			status = "running",
			summary = "正在协调成员",
			approved = true,
			frozen = true,
			currentAgent = "explorer",
			round = 1,
			maxRounds = 5,
			members = listOf(
				ThreadItem.TeamMember(
					memberId = "member_explorer",
					name = "explorer",
					displayName = "探索成员",
					status = "running",
					mode = "READ_ONLY_TOOL",
					task = "读取目录",
					toolCallCount = 2,
					tokenEstimate = 128,
					summary = "正在读取",
				),
			),
		)

		val next = ChatReducer.reduce(
			AppState.empty().copy(messages = listOf(ChatMessage.User("u1", "组织团队处理"))),
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", item)),
		)

		assertEquals(1, next.messages.size)
		assertEquals("team_1", next.teamState.current?.teamId)
		assertEquals("explorer", next.teamState.current?.currentAgent)
		assertTrue(next.teamState.visible)
		assertEquals(1, next.runtimeEvents.size)
		assertEquals("Team:团队协作", next.runtimeEvents.single().title)
	}

	@Test
	fun `dismissed team remains hidden for same team and resets for new team`() {
		val firstItem = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "failed team",
			status = "failed",
			members = emptyList(),
		)
		val dismissedState = AppState.empty().copy(
			teamState = TeamUiState(current = firstItem, dismissedTeamId = "team_1"),
		)

		val sameTeam = ChatReducer.reduce(
			dismissedState,
			AgentEvent.Server(ServerEvent.ItemUpdated("thread-1", "turn-1", firstItem.copy(summary = "updated"))),
		)
		assertFalse(sameTeam.teamState.visible)
		assertEquals("team_1", sameTeam.teamState.dismissedTeamId)

		val secondItem = firstItem.copy(
			id = "it_team_2",
			teamId = "team_2",
			status = "running",
		)
		val nextTeam = ChatReducer.reduce(
			sameTeam,
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-2", secondItem)),
		)

		assertTrue(nextTeam.teamState.visible)
		assertEquals("team_2", nextTeam.teamState.current?.teamId)
		assertEquals(null, nextTeam.teamState.dismissedTeamId)
	}

	@Test
	fun `team message item appends runtime timeline without adding chat message`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "团队协作",
			status = "running",
			members = emptyList(),
		)
		val message = ThreadItem.TeamMessage(
			id = "it_team_msg_1",
			messageId = "msg_1",
			teamId = "team_1",
			fromAgent = "user",
			toAgent = "explorer",
			messageType = "direct_user",
			content = "请重点看 README",
			round = 2,
			createdAt = "2026-06-01T10:00:00Z",
		)

		val next = ChatReducer.reduce(
			AppState.empty().copy(teamState = TeamUiState(current = team), messages = listOf(ChatMessage.User("u1", "继续"))),
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", message)),
		)

		assertEquals(1, next.messages.size)
		assertEquals("msg_1", next.teamState.messages.single().messageId)
		assertEquals("explorer", next.teamState.selectedAgent)
		assertEquals(1, next.runtimeEvents.size)
		assertEquals("TeamMessage:direct_user", next.runtimeEvents.single().title)
	}

	@Test
	fun `team state from history keeps latest team and matching messages`() {
		val oldTeam = ThreadItem.Team(id = "it_team_old", teamId = "team_old", title = "旧团队", status = "completed")
		val latestTeam = ThreadItem.Team(id = "it_team_new", teamId = "team_new", title = "新团队", status = "running")
		val oldMessage = ThreadItem.TeamMessage(
			id = "it_msg_old",
			messageId = "msg_old",
			teamId = "team_old",
			fromAgent = "supervisor",
			toAgent = "all",
			messageType = "system",
			content = "旧消息",
		)
		val latestMessage = oldMessage.copy(
			id = "it_msg_new",
			messageId = "msg_new",
			teamId = "team_new",
			content = "新消息",
		)

		val state = ChatReducer.teamStateFromItems(listOf(oldTeam, oldMessage, latestTeam, latestMessage))

		assertEquals("team_new", state.current?.teamId)
		assertEquals(listOf("msg_new"), state.messages.map { it.messageId })
	}

	@Test
	fun `work unit item updates runtime state without adding chat message`() {
		val item = ThreadItem.WorkUnit(
			id = "it_workunit_1",
			workUnitId = "wu_1",
			kind = "orchestration",
			name = "login-flow",
			status = "idle",
			currentGoalId = "wug_1",
			currentGoal = "split login page",
			goalCount = 1,
			removed = false,
		)

		val next = ChatReducer.reduce(
			AppState.empty().copy(messages = listOf(ChatMessage.User("u1", "create work unit"))),
			AgentEvent.Server(ServerEvent.ItemAdded("thread-1", "turn-1", item)),
		)

		assertEquals(1, next.messages.size)
		assertEquals(listOf("wu_1"), next.workUnitState.items.map { it.workUnitId })
		assertEquals(1, next.runtimeEvents.size)
		assertEquals("WorkUnit:login-flow", next.runtimeEvents.single().title)
	}

	@Test
	fun `removed work unit item disappears from runtime state`() {
		val existing = ThreadItem.WorkUnit(
			id = "it_workunit_1",
			workUnitId = "wu_1",
			kind = "team",
			name = "review-team",
			status = "idle",
			currentGoal = "review docs",
			goalCount = 1,
			removed = false,
		)
		val removed = existing.copy(status = "removed", removed = true)

		val next = ChatReducer.reduce(
			AppState.empty().copy(workUnitState = WorkUnitUiState(items = listOf(existing))),
			AgentEvent.Server(ServerEvent.ItemUpdated("thread-1", "turn-1", removed)),
		)

		assertEquals(emptyList(), next.workUnitState.items)
	}

	@Test
	fun `work unit state from history keeps visible units and ignores removed units`() {
		val visible = ThreadItem.WorkUnit(
			id = "it_workunit_visible",
			workUnitId = "wu_visible",
			kind = "orchestration",
			name = "login-flow",
			status = "idle",
			currentGoal = "split login page",
			goalCount = 2,
			removed = false,
		)
		val removed = visible.copy(
			id = "it_workunit_removed",
			workUnitId = "wu_removed",
			name = "old-flow",
			status = "removed",
			removed = true,
		)

		val state = ChatReducer.workUnitStateFromItems(listOf(removed, visible))

		assertEquals(listOf("wu_visible"), state.items.map { it.workUnitId })
	}

	private fun sampleApproval() = ApprovalRequestPayload(
		threadId = "thread-1",
		turnId = "turn-1",
		itemId = "approval-1",
		toolName = "exec_shell",
		arguments = """{"command":"git status"}""",
		description = "需要执行 shell 命令",
	)
}
