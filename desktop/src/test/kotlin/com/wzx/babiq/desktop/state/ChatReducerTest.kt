package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.protocol.ApprovalRequestPayload
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.ThreadItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

	private fun sampleApproval() = ApprovalRequestPayload(
		threadId = "thread-1",
		turnId = "turn-1",
		itemId = "approval-1",
		toolName = "exec_shell",
		arguments = """{"command":"git status"}""",
		description = "需要执行 shell 命令",
	)
}
