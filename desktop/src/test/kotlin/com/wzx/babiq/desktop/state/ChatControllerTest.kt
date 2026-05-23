package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.ProviderListResult
import com.wzx.babiq.desktop.protocol.ServerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

class ChatControllerTest {

	@Test
	fun `sendMessage 没有 thread 时先创建 thread 再启动 turn`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)
		controller.connect()

		controller.sendMessage("分析项目")

		assertEquals(listOf("connect", "listProviders", "createThread:E:\\BaBiQ", "startTurn:thread-1:分析项目:null"), gateway.calls)
		assertEquals("thread-1", controller.state.value.currentThreadId)
		assertEquals("turn-1", controller.state.value.currentTurnId)
		assertTrue(controller.state.value.messages.any { it is ChatMessage.User && it.text == "分析项目" })
	}

	@Test
	fun `running 状态禁止重复发送`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)
		controller.connect()
		controller.applyEvent(AgentEvent.Server(ServerEvent.TurnStarted("thread-1", "turn-1")))

		controller.sendMessage("第二条")

		assertFalse(gateway.calls.any { it.startsWith("startTurn") })
		assertEquals("当前 turn 仍在运行，暂不能发送新任务", controller.state.value.lastError)
	}

	@Test
	fun `disconnected 状态禁止发送并保留草稿`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)

		controller.sendMessage("离线任务")

		assertTrue(gateway.calls.isEmpty())
		assertEquals("离线任务", controller.state.value.draft)
		assertEquals("后端未连接，无法发送任务", controller.state.value.lastError)
	}

	@Test
	fun `respondApproval 发送审批后关闭待审批并回到 running`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)
		controller.connect()
		controller.applyEvent(AgentEvent.Server(ServerEvent.ApprovalRequested(sampleApproval())))

		controller.respondApproval("approve")

		assertEquals("approval:thread-1:turn-1:approve:null", gateway.calls.last())
		assertNull(controller.state.value.pendingApproval)
		assertEquals(TurnState.Running, controller.state.value.turnState)
	}

	@Test
	fun `connect 失败会进入 disconnected 并显示错误`() = runTest {
		val gateway = FakeGateway(connectFails = true)
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals(ConnectionState.Disconnected, controller.state.value.connectionState)
		assertEquals("连接后端失败: boom", controller.state.value.lastError)
	}

	private fun sampleApproval() = com.wzx.babiq.desktop.protocol.ApprovalRequestPayload(
		threadId = "thread-1",
		turnId = "turn-1",
		itemId = "approval-1",
		toolName = "exec_shell",
		arguments = """{"command":"git status"}""",
		description = "需要执行 shell 命令",
	)

	private class FakeGateway(
		private val connectFails: Boolean = false,
	) : AgentGateway {
		override val events = MutableSharedFlow<ServerEvent>()
		val calls = mutableListOf<String>()

		override suspend fun connect() {
			calls += "connect"
			if (connectFails) error("boom")
		}

		override suspend fun createThread(cwd: String): String {
			calls += "createThread:$cwd"
			return "thread-1"
		}

		override suspend fun startTurn(threadId: String, prompt: String, providerId: String?): String {
			calls += "startTurn:$threadId:$prompt:$providerId"
			return "turn-1"
		}

		override suspend fun respondApproval(threadId: String, turnId: String, decision: String, editedArgs: String?): Boolean {
			calls += "approval:$threadId:$turnId:$decision:$editedArgs"
			return true
		}

		override suspend fun listProviders(): ProviderListResult {
			calls += "listProviders"
			return ProviderListResult()
		}

		override suspend fun setActiveProvider(providerId: String, modelId: String?): Boolean {
			calls += "setActive:$providerId:$modelId"
			return true
		}
	}
}
