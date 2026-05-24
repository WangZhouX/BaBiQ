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

	private fun sampleApproval() = ApprovalRequestPayload(
		threadId = "thread-1",
		turnId = "turn-1",
		itemId = "approval-1",
		toolName = "exec_shell",
		arguments = """{"command":"git status"}""",
		description = "需要执行 shell 命令",
	)
}
