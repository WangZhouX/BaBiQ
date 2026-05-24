package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.AppSettingsResult
import com.wzx.babiq.desktop.protocol.ApprovalPolicyResult
import com.wzx.babiq.desktop.protocol.ProviderListResult
import com.wzx.babiq.desktop.protocol.ProviderMutationResult
import com.wzx.babiq.desktop.protocol.ProviderDeleteResult
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.protocol.ProviderTestResult
import com.wzx.babiq.desktop.protocol.RunRecoveryStatusResult
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.RunTurnListResult
import com.wzx.babiq.desktop.protocol.RunTurnSummaryInfo
import com.wzx.babiq.desktop.protocol.SandboxPolicyResult
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.SettingsUpdateParams
import com.wzx.babiq.desktop.protocol.ThreadArchiveResult
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.ThreadListResult
import com.wzx.babiq.desktop.protocol.ThreadLoadResult
import com.wzx.babiq.desktop.protocol.ThreadMetaInfo
import com.wzx.babiq.desktop.protocol.ThreadSummaryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

	@Test
	fun `sendMessage 没有 thread 时先创建 thread 再启动 turn`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)
		controller.connect()

		controller.sendMessage("分析项目")

		assertEquals(
			listOf(
				"connect",
				"getSettings",
				"listProviders",
				"getSandboxPolicy",
				"listThreads:E:\\BaBiQ",
				"createThread:E:\\BaBiQ",
				"startTurn:thread-1:分析项目:null",
			),
			gateway.calls,
		)
		assertEquals("thread-1", controller.state.value.currentThreadId)
		assertEquals("turn-1", controller.state.value.currentTurnId)
		assertTrue(controller.state.value.messages.any { it is ChatMessage.User && it.text == "分析项目" })
	}

	@Test
	fun `connect 成功后拉取后端权限策略并更新工作区权限展示`() = runTest {
		val gateway = FakeGateway(policy = SandboxPolicyResult("DANGER_FULL_ACCESS", "完全访问权限"))
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals(listOf("connect", "getSettings", "listProviders", "getSandboxPolicy", "listThreads:E:\\BaBiQ"), gateway.calls)
		assertEquals("DANGER_FULL_ACCESS", controller.state.value.workspace.permissionMode)
		assertEquals("完全访问权限", controller.state.value.workspace.permissionLabel)
	}

	@Test
	fun `connect 成功后加载本地 settings`() = runTest {
		val gateway = FakeGateway(settings = AppSettingsResult("deepseek-official", "WORKSPACE_WRITE", "ON_REQUEST", "E:\\BaBiQ"))
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals("deepseek-official", controller.state.value.settingsState.settings?.activeProviderId)
		assertEquals("ON_REQUEST", controller.state.value.settingsState.settings?.approvalPolicy)
	}

	@Test
	fun `connect 成功后加载最近会话列表`() = runTest {
		val gateway = FakeGateway(
			history = ThreadListResult(
				threads = listOf(
					ThreadSummaryInfo(
						threadId = "thr_history",
						title = "历史会话",
						cwd = "E:\\BaBiQ",
						updatedAt = "2026-05-24T08:00:00Z",
						messageCount = 2,
					),
				),
			),
		)
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals("thr_history", controller.state.value.threadHistory.items.single().threadId)
		assertEquals("历史会话", controller.state.value.threadHistory.items.single().title)
	}

	@Test
	fun `openThread 加载历史 item 并替换当前消息流`() = runTest {
		val gateway = FakeGateway(
			loadedThread = ThreadLoadResult(
				thread = ThreadMetaInfo("thr_history", "历史会话", "E:\\BaBiQ", "active"),
				items = listOf(ThreadItem.UserMessage("it_user", text = "你好")),
			),
		)
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(connectionState = ConnectionState.Connected),
		)

		controller.openThread("thr_history")

		assertEquals("loadThread:thr_history", gateway.calls.single())
		assertEquals("thr_history", controller.state.value.currentThreadId)
		assertEquals("历史会话", controller.state.value.currentThreadTitle)
		assertEquals("thr_history", controller.state.value.threadHistory.selectedThreadId)
		assertEquals("你好", (controller.state.value.messages.single() as ChatMessage.User).text)
	}

	@Test
	fun `newChat 只清空当前会话不清空历史列表`() = runTest {
		val controller = ChatController(
			FakeGateway(),
			backgroundScope,
			initialState = AppState(
				currentThreadId = "thr_old",
				currentThreadTitle = "旧会话",
				messages = listOf(ChatMessage.User("it_user", "旧消息")),
				threadHistory = ThreadHistoryState(
					items = listOf(ThreadListItem("thr_old", "旧会话", "E:\\BaBiQ", "active", null, "刚刚", 1)),
					selectedThreadId = "thr_old",
				),
			),
		)

		controller.newChat()

		assertNull(controller.state.value.currentThreadId)
		assertTrue(controller.state.value.messages.isEmpty())
		assertEquals(1, controller.state.value.threadHistory.items.size)
		assertNull(controller.state.value.threadHistory.selectedThreadId)
	}

	@Test
	fun `archiveThread 归档后从最近列表移除`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(
				connectionState = ConnectionState.Connected,
				currentThreadId = "thr_old",
				threadHistory = ThreadHistoryState(
					items = listOf(ThreadListItem("thr_old", "旧会话", "E:\\BaBiQ", "active", null, "刚刚", 1)),
					selectedThreadId = "thr_old",
				),
			),
		)

		controller.archiveThread("thr_old")

		assertEquals("archiveThread:thr_old", gateway.calls.single())
		assertTrue(controller.state.value.threadHistory.items.isEmpty())
		assertNull(controller.state.value.currentThreadId)
	}

	@Test
	fun `selectWorkspace 切换工作目录后下一轮使用新 cwd 创建 thread`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(
				connectionState = ConnectionState.Connected,
				currentThreadId = "old-thread",
				currentTurnId = "old-turn",
				messages = listOf(ChatMessage.User("old-user", "旧目录里的消息")),
			),
		)

		controller.selectWorkspace("D:\\Projects\\Other")
		controller.sendMessage("分析新目录")

		assertEquals("Other", controller.state.value.workspace.projectName)
		assertEquals("D:\\Projects\\Other", controller.state.value.workspace.cwd)
		assertEquals(
			listOf("listThreads:D:\\Projects\\Other", "createThread:D:\\Projects\\Other", "startTurn:thread-1:分析新目录:null"),
			gateway.calls,
		)
		assertFalse(controller.state.value.messages.any { it.id == "old-user" })
	}

	@Test
	fun `selectWorkspace 运行中的 turn 不允许切换工作目录`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(
				connectionState = ConnectionState.Connected,
				turnState = TurnState.Running,
				workspace = WorkspaceContext(cwd = "E:\\BaBiQ"),
			),
		)

		controller.selectWorkspace("D:\\Projects\\Other")

		assertEquals("E:\\BaBiQ", controller.state.value.workspace.cwd)
		assertEquals("当前 turn 仍在运行，结束后才能切换工作目录", controller.state.value.lastError)
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

		assertEquals("approval:thread-1:turn-1:approve:null:null", gateway.calls.last())
		assertNull(controller.state.value.pendingApproval)
		assertEquals(TurnState.Running, controller.state.value.turnState)
	}

	@Test
	fun `respondApproval 支持始终允许的 session scope`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope)
		controller.connect()
		controller.applyEvent(AgentEvent.Server(ServerEvent.ApprovalRequested(sampleApproval())))

		controller.respondApproval("always", scope = "session")

		assertEquals("approval:thread-1:turn-1:always:null:session", gateway.calls.last())
		assertNull(controller.state.value.pendingApproval)
	}

	@Test
	fun `保存 provider 后刷新 provider 列表并保留设置页状态`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.createProvider(sampleProviderSaveParams())

		assertEquals(listOf("createProvider:custom-openai", "listProviders"), gateway.calls)
		assertEquals("Provider 已保存，下一轮 turn 生效", controller.state.value.settingsState.notice)
	}

	@Test
	fun `保存沙箱和审批策略后更新上下文条与设置状态`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.saveSandboxMode("READ_ONLY")
		controller.saveApprovalPolicy("NEVER")

		assertEquals("READ_ONLY", controller.state.value.workspace.permissionMode)
		assertEquals("只读模式", controller.state.value.workspace.permissionLabel)
		assertEquals("NEVER", controller.state.value.settingsState.settings?.approvalPolicy)
		assertEquals(listOf("setSandbox:READ_ONLY", "setApproval:NEVER"), gateway.calls)
	}

	@Test
	fun `展开运行详情后加载恢复状态和历史 turn 详情`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(
				connectionState = ConnectionState.Connected,
				currentThreadId = "thr_history",
			),
		)

		controller.toggleRuntimeDetails()

		assertEquals(listOf("getRecoveryStatus", "listRunTurns:thr_history", "getRunTurn:turn-1"), gateway.calls)
		assertEquals("turn-1", controller.state.value.runRecordState.selectedTurnId)
		assertEquals(1, controller.state.value.runRecordState.turns.size)
		assertEquals("cmd", controller.state.value.runRecordState.selectedDetail?.toolCalls?.single()?.toolName)
	}

	@Test
	fun `点击历史 turn 后只刷新选中 turn 详情`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(connectionState = ConnectionState.Connected),
		)

		controller.selectRunTurn("turn-2")

		assertEquals(listOf("getRunTurn:turn-2"), gateway.calls)
		assertEquals("turn-2", controller.state.value.runRecordState.selectedTurnId)
		assertEquals("turn-2", controller.state.value.runRecordState.selectedDetail?.turn?.turnId)
	}

	@Test
	fun `connect 失败会进入 reconnecting 并显示错误`() = runTest {
		val gateway = FakeGateway(connectFails = true)
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals(ConnectionState.Reconnecting, controller.state.value.connectionState)
		assertEquals("连接后端失败: boom", controller.state.value.lastError)
	}

	@Test
	fun `connect 失败后按退避策略自动重连并保留草稿`() = runTest {
		val gateway = FakeGateway(connectFailuresBeforeSuccess = 1)
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()
		controller.sendMessage("恢复后再发")

		assertEquals(ConnectionState.Reconnecting, controller.state.value.connectionState)
		assertEquals("恢复后再发", controller.state.value.draft)
		assertEquals(1, gateway.calls.count { it == "connect" })

		advanceTimeBy(1_001)
		advanceUntilIdle()

		assertEquals(ConnectionState.Connected, controller.state.value.connectionState)
		assertEquals(2, gateway.calls.count { it == "connect" })
		assertEquals("恢复后再发", controller.state.value.draft)
	}

	private fun sampleApproval() = com.wzx.babiq.desktop.protocol.ApprovalRequestPayload(
		threadId = "thread-1",
		turnId = "turn-1",
		itemId = "approval-1",
		toolName = "exec_shell",
		arguments = """{"command":"git status"}""",
		description = "需要执行 shell 命令",
	)

	private fun sampleProviderSaveParams() = ProviderSaveParams(
		providerId = "custom-openai",
		displayName = "自定义 OpenAI",
		type = "OPENAI_COMPATIBLE",
		baseUrl = "https://relay.example.com/v1",
		model = "deepseek-chat",
		apiKey = "sk-secret",
		contextWindow = 128000,
		enabled = true,
	)

	private fun sampleProviderMutationResult(providerId: String) = ProviderMutationResult(
		id = providerId,
		label = "自定义 OpenAI",
		displayName = "自定义 OpenAI",
		type = "OPENAI_COMPATIBLE",
		baseUrl = "https://relay.example.com/v1",
		model = "deepseek-chat",
		contextWindow = 128000,
		enabled = true,
		hasApiKey = true,
		active = false,
	)

	private inner class FakeGateway(
		private val connectFails: Boolean = false,
		private var connectFailuresBeforeSuccess: Int = 0,
		private val settings: AppSettingsResult = AppSettingsResult("deepseek-official", "WORKSPACE_WRITE", "ON_REQUEST", "E:\\BaBiQ"),
		private val policy: SandboxPolicyResult = SandboxPolicyResult("WORKSPACE_WRITE", "工作区可写"),
		private val history: ThreadListResult = ThreadListResult(),
		private val loadedThread: ThreadLoadResult = ThreadLoadResult(
			ThreadMetaInfo("thread-1", "测试会话", "E:\\BaBiQ", "active"),
		),
		private val runTurns: RunTurnListResult = RunTurnListResult(
			turns = listOf(sampleRunTurn("turn-1")),
		),
		private val recoveryStatus: RunRecoveryStatusResult = RunRecoveryStatusResult(
			lastRecoveredAt = "2026-05-24T08:10:00Z",
			interruptedTurns = 1,
		),
	) : AgentGateway {
		override val events = MutableSharedFlow<ServerEvent>()
		val calls = mutableListOf<String>()

		override suspend fun connect() {
			calls += "connect"
			if (connectFails || connectFailuresBeforeSuccess > 0) {
				connectFailuresBeforeSuccess = (connectFailuresBeforeSuccess - 1).coerceAtLeast(0)
				error("boom")
			}
		}

		override suspend fun createThread(cwd: String): String {
			calls += "createThread:$cwd"
			return "thread-1"
		}

		override suspend fun startTurn(threadId: String, prompt: String, providerId: String?): String {
			calls += "startTurn:$threadId:$prompt:$providerId"
			return "turn-1"
		}

		override suspend fun respondApproval(
			threadId: String,
			turnId: String,
			decision: String,
			editedArgs: String?,
			scope: String?,
		): Boolean {
			calls += "approval:$threadId:$turnId:$decision:$editedArgs:$scope"
			return true
		}

		override suspend fun getSettings(): AppSettingsResult {
			calls += "getSettings"
			return settings
		}

		override suspend fun updateSettings(update: SettingsUpdateParams): AppSettingsResult {
			calls += "updateSettings"
			return settings.copy(
				activeProviderId = update.activeProviderId ?: settings.activeProviderId,
				sandboxMode = update.sandboxMode ?: settings.sandboxMode,
				approvalPolicy = update.approvalPolicy ?: settings.approvalPolicy,
				defaultCwd = update.defaultCwd ?: settings.defaultCwd,
			)
		}

		override suspend fun createProvider(params: ProviderSaveParams): ProviderMutationResult {
			calls += "createProvider:${params.providerId}"
			return sampleProviderMutationResult(params.providerId)
		}

		override suspend fun updateProvider(params: ProviderSaveParams): ProviderMutationResult {
			calls += "updateProvider:${params.providerId}"
			return sampleProviderMutationResult(params.providerId)
		}

		override suspend fun deleteProvider(providerId: String): ProviderDeleteResult {
			calls += "deleteProvider:$providerId"
			return ProviderDeleteResult(ok = true, providerId = providerId, archived = true)
		}

		override suspend fun testProvider(providerId: String): ProviderTestResult {
			calls += "testProvider:$providerId"
			return ProviderTestResult(ok = true, providerId = providerId, message = "Provider 配置可用")
		}

		override suspend fun listProviders(): ProviderListResult {
			calls += "listProviders"
			return ProviderListResult()
		}

		override suspend fun getSandboxPolicy(): SandboxPolicyResult {
			calls += "getSandboxPolicy"
			return policy
		}

		override suspend fun setSandboxPolicy(mode: String): SandboxPolicyResult {
			calls += "setSandbox:$mode"
			return SandboxPolicyResult(mode, if (mode == "READ_ONLY") "只读模式" else "工作区可写")
		}

		override suspend fun setApprovalPolicy(approvalPolicy: String): ApprovalPolicyResult {
			calls += "setApproval:$approvalPolicy"
			return ApprovalPolicyResult(approvalPolicy)
		}

		override suspend fun setActiveProvider(providerId: String, modelId: String?): Boolean {
			calls += "setActive:$providerId:$modelId"
			return true
		}

		override suspend fun listThreads(cwd: String, includeArchived: Boolean, limit: Int): ThreadListResult {
			calls += "listThreads:$cwd"
			return history
		}

		override suspend fun loadThread(threadId: String, limit: Int, beforeItemId: String?): ThreadLoadResult {
			calls += "loadThread:$threadId"
			return loadedThread
		}

		override suspend fun archiveThread(threadId: String): ThreadArchiveResult {
			calls += "archiveThread:$threadId"
			return ThreadArchiveResult(ok = true, threadId = threadId, archived = true)
		}

		override suspend fun listRunTurns(threadId: String, limit: Int, cursor: String?): RunTurnListResult {
			calls += "listRunTurns:$threadId"
			return runTurns
		}

		override suspend fun getRunTurn(turnId: String): RunTurnDetailResult {
			calls += "getRunTurn:$turnId"
			return RunTurnDetailResult(
				turn = sampleRunTurn(turnId),
				toolCalls = listOf(
					com.wzx.babiq.desktop.protocol.RunToolCallInfo(
						toolCallId = "tool-$turnId",
						toolName = "cmd",
						argsJson = "{}",
						status = "completed",
						startedAt = "2026-05-24T08:00:01Z",
						completedAt = "2026-05-24T08:00:02Z",
					),
				),
			)
		}

		override suspend fun getRecoveryStatus(): RunRecoveryStatusResult {
			calls += "getRecoveryStatus"
			return recoveryStatus
		}
	}

	private fun sampleRunTurn(turnId: String): RunTurnSummaryInfo =
		RunTurnSummaryInfo(
			turnId = turnId,
			threadId = "thr_history",
			status = "COMPLETED",
			inputText = "分析项目",
			cwd = "E:\\BaBiQ",
			providerId = "deepseek",
			model = "deepseek-v4-pro",
			startedAt = "2026-05-24T08:00:00Z",
			completedAt = "2026-05-24T08:00:03Z",
		)
}
