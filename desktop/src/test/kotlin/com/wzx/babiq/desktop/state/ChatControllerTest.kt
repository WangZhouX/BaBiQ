package com.wzx.babiq.desktop.state

import com.wzx.babiq.desktop.client.AgentGateway
import com.wzx.babiq.desktop.protocol.AppSettingsResult
import com.wzx.babiq.desktop.protocol.ApprovalPolicyResult
import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.protocol.CapabilitySearchResult
import com.wzx.babiq.desktop.protocol.CapabilitySettingsSetParams
import com.wzx.babiq.desktop.protocol.CapabilitySettingsSetResult
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.ContextSnapshotInfo
import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.McpServerInfo
import com.wzx.babiq.desktop.protocol.McpServerRefreshResult
import com.wzx.babiq.desktop.protocol.McpServersListResult
import com.wzx.babiq.desktop.protocol.McpToolInfo
import com.wzx.babiq.desktop.protocol.McpToolsListResult
import com.wzx.babiq.desktop.protocol.MemoryArtifactsListResult
import com.wzx.babiq.desktop.protocol.MemoryArtifactInfo
import com.wzx.babiq.desktop.protocol.MemoryConsolidateResult
import com.wzx.babiq.desktop.protocol.MemoryJobInfo
import com.wzx.babiq.desktop.protocol.MemoryJobsListResult
import com.wzx.babiq.desktop.protocol.MemorySearchResult
import com.wzx.babiq.desktop.protocol.MemorySettingsSetParams
import com.wzx.babiq.desktop.protocol.MemorySettingsSetResult
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.protocol.ModelUsageStatsInfo
import com.wzx.babiq.desktop.protocol.ObservabilityCostsResult
import com.wzx.babiq.desktop.protocol.ObservabilitySnapshotResult
import com.wzx.babiq.desktop.protocol.ObservabilityToolsResult
import com.wzx.babiq.desktop.protocol.ObservabilityTotalsInfo
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
import com.wzx.babiq.desktop.protocol.SkillListResult
import com.wzx.babiq.desktop.protocol.SkillGetResult
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
				"listThreads:<all>",
				"listThreads:E:\\BaBiQ",
				"getMemoryStatus",
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

		assertEquals(listOf("connect", "getSettings", "listProviders", "getSandboxPolicy", "listThreads:<all>", "listThreads:E:\\BaBiQ", "getMemoryStatus"), gateway.calls)
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
	fun `connect applies default cwd and builds workspace project list`() = runTest {
		val gateway = FakeGateway(
			settings = AppSettingsResult("deepseek-official", "WORKSPACE_WRITE", "ON_REQUEST", "H:\\aaa"),
			history = ThreadListResult(
				threads = listOf(
					ThreadSummaryInfo(
						threadId = "thr_aaa",
						title = "aaa 新对话",
						cwd = "H:\\aaa",
						updatedAt = "2026-05-25T08:00:00Z",
						messageCount = 3,
					),
				),
			),
			allHistory = ThreadListResult(
				threads = listOf(
					ThreadSummaryInfo(
						threadId = "thr_aaa",
						title = "aaa 新对话",
						cwd = "H:\\aaa",
						updatedAt = "2026-05-25T08:00:00Z",
						messageCount = 3,
					),
					ThreadSummaryInfo(
						threadId = "thr_repo",
						title = "BaBiQ 新对话",
						cwd = "E:\\BaBiQ",
						updatedAt = "2026-05-24T08:00:00Z",
						messageCount = 2,
					),
				),
			),
		)
		val controller = ChatController(gateway, backgroundScope)

		controller.connect()

		assertEquals("H:\\aaa", controller.state.value.workspace.cwd)
		assertEquals("aaa", controller.state.value.workspace.projectName)
		assertEquals(listOf("H:\\aaa", "E:\\BaBiQ"), controller.state.value.workspaceProjects.items.map { it.cwd })
		assertEquals("H:\\aaa", controller.state.value.workspaceProjects.items.single { it.current }.cwd)
		assertTrue(gateway.calls.contains("listThreads:<all>"))
		assertTrue(gateway.calls.contains("listThreads:H:\\aaa"))
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
	fun `打开本地 MCP 页面时加载 server 和工具列表`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.showScreen(Screen.Mcp)
		advanceUntilIdle()

		assertEquals(Screen.Mcp, controller.state.value.screen)
		assertEquals(listOf("listMcpServers", "listMcpTools:local-filesystem"), gateway.calls)
		assertEquals("local-filesystem", controller.state.value.mcpState.servers.single().serverId)
		assertEquals("read_file", controller.state.value.mcpState.toolsByServer["local-filesystem"]?.single()?.toolName)
	}

	@Test
	fun `刷新 MCP server 后更新状态和工具列表`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.refreshMcpServer("local-filesystem")
		advanceUntilIdle()

		assertEquals(listOf("refreshMcp:local-filesystem", "listMcpTools:local-filesystem"), gateway.calls)
		assertEquals("connected", controller.state.value.mcpState.servers.single().status)
		assertEquals("read_file", controller.state.value.mcpState.toolsByServer["local-filesystem"]?.single()?.toolName)
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

		assertEquals(listOf("loadThread:thr_history", "getContextStatus:thr_history", "getMemoryStatus"), gateway.calls)
		assertEquals("thr_history", controller.state.value.currentThreadId)
		assertEquals("历史会话", controller.state.value.currentThreadTitle)
		assertEquals("thr_history", controller.state.value.threadHistory.selectedThreadId)
		assertEquals("你好", (controller.state.value.messages.single() as ChatMessage.User).text)
		assertEquals("ctxsnap_1", controller.state.value.contextWindowState.status?.lastSnapshotId)
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
				contextWindowState = ContextWindowUiState(status = sampleContextStatus("thr_old")),
				threadHistory = ThreadHistoryState(
					items = listOf(ThreadListItem("thr_old", "旧会话", "E:\\BaBiQ", "active", null, "刚刚", 1)),
					selectedThreadId = "thr_old",
				),
			),
		)

		controller.newChat()

		assertNull(controller.state.value.currentThreadId)
		assertTrue(controller.state.value.messages.isEmpty())
		assertNull(controller.state.value.contextWindowState.status)
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
		val gateway = FakeGateway(
			history = ThreadListResult(
				threads = listOf(
					ThreadSummaryInfo(
						threadId = "thr_other",
						title = "Other 新对话",
						cwd = "D:\\Projects\\Other",
						updatedAt = "2026-05-25T08:00:00Z",
						messageCount = 1,
					),
				),
			),
			allHistory = ThreadListResult(
				threads = listOf(
					ThreadSummaryInfo(
						threadId = "thr_other",
						title = "Other 新对话",
						cwd = "D:\\Projects\\Other",
						updatedAt = "2026-05-25T08:00:00Z",
						messageCount = 1,
					),
					ThreadSummaryInfo(
						threadId = "thr_babiq",
						title = "BaBiQ 新对话",
						cwd = "E:\\BaBiQ",
						updatedAt = "2026-05-24T08:00:00Z",
						messageCount = 1,
					),
				),
			),
		)
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
			gateway.calls.filterNot { it == "updateSettings:D:\\Projects\\Other" || it == "listThreads:<all>" },
		)
		assertTrue(gateway.calls.contains("updateSettings:D:\\Projects\\Other"))
		assertEquals(listOf("D:\\Projects\\Other", "E:\\BaBiQ"), controller.state.value.workspaceProjects.items.map { it.cwd })
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
	fun `打开设置页时加载长期记忆状态和审计列表`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.showScreen(Screen.Settings)
		advanceUntilIdle()

		assertEquals(
			listOf("getMemoryStatus", "listMemoryJobs:20", "listMemoryArtifacts:20", "getCapabilityStatus", "listSkills"),
			gateway.calls,
		)
		assertEquals(5, controller.state.value.memoryState.status?.cleanCandidateCount)
		assertEquals("phase2:1", controller.state.value.memoryState.jobs.single().jobKey)
		assertEquals("memory_summary.md", controller.state.value.memoryState.artifacts.single().artifactPath)
		assertEquals(1, controller.state.value.capabilityState.status?.totalCount)
	}

	@Test
	fun `保存长期记忆设置会调用后端并刷新审计状态`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.saveMemorySettings(readEnabled = false)
		advanceUntilIdle()

		assertEquals(listOf("setMemorySettings", "getMemoryStatus", "listMemoryJobs:20", "listMemoryArtifacts:20"), gateway.calls)
		assertEquals("长期记忆设置已保存，下一轮上下文组装生效", controller.state.value.memoryState.notice)
		assertEquals("memjob_1", controller.state.value.memoryState.jobs.single().jobId)
	}

	@Test
	fun `手动触发长期记忆归并会调用后端并刷新审计状态`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(gateway, backgroundScope, initialState = AppState(connectionState = ConnectionState.Connected))

		controller.consolidateMemory(force = true)
		advanceUntilIdle()

		assertEquals(listOf("consolidateMemory:true", "getMemoryStatus", "listMemoryJobs:20", "listMemoryArtifacts:20"), gateway.calls)
		assertEquals("长期记忆归并已入队：memjob_1", controller.state.value.memoryState.notice)
		assertEquals("memart_1", controller.state.value.memoryState.artifacts.single().artifactId)
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

		assertEquals(
			listOf("getRecoveryStatus", "listRunTurns:thr_history", "getRunTurn:turn-1", "getObservabilitySnapshot:7d:E:\\BaBiQ"),
			gateway.calls,
		)
		assertEquals("turn-1", controller.state.value.runRecordState.selectedTurnId)
		assertEquals(1, controller.state.value.runRecordState.turns.size)
		assertEquals("cmd", controller.state.value.runRecordState.selectedDetail?.toolCalls?.single()?.toolName)
		assertEquals(3L, controller.state.value.runRecordState.observability.snapshot?.totals?.turns)
	}

	@Test
	fun `切换可观测 range 只刷新统计快照并保留聊天状态`() = runTest {
		val gateway = FakeGateway()
		val controller = ChatController(
			gateway,
			backgroundScope,
			initialState = AppState(
				connectionState = ConnectionState.Connected,
				currentThreadId = "thr_history",
				messages = listOf(ChatMessage.User("msg-1", "保留我")),
			),
		)

		controller.selectObservabilityRange("30d")

		assertEquals(listOf("getObservabilitySnapshot:30d:E:\\BaBiQ"), gateway.calls)
		assertEquals("30d", controller.state.value.runRecordState.observability.range)
		assertEquals(3L, controller.state.value.runRecordState.observability.snapshot?.totals?.turns)
		assertEquals("保留我", (controller.state.value.messages.single() as ChatMessage.User).text)
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
		private val allHistory: ThreadListResult = history,
		private val loadedThread: ThreadLoadResult = ThreadLoadResult(
			ThreadMetaInfo("thread-1", "测试会话", "E:\\BaBiQ", "active"),
		),
		private val runTurns: RunTurnListResult = RunTurnListResult(
			turns = listOf(sampleRunTurn("turn-1")),
		),
		private val contextStatus: ContextStatusResult = sampleContextStatus("thr_history"),
		private val observabilitySnapshot: ObservabilitySnapshotResult = ObservabilitySnapshotResult(
			range = "7d",
			totals = ObservabilityTotalsInfo(turns = 3, failedTurns = 1, promptTokens = 120, completionTokens = 80, totalTokens = 200),
			byModel = listOf(ModelUsageStatsInfo(providerId = "deepseek", model = "deepseek-v4-pro", turns = 3, totalTokens = 200)),
		),
		private val recoveryStatus: RunRecoveryStatusResult = RunRecoveryStatusResult(
			lastRecoveredAt = "2026-05-24T08:10:00Z",
			interruptedTurns = 1,
		),
		private val mcpServers: McpServersListResult = McpServersListResult(
			servers = listOf(sampleMcpServer()),
		),
		private val mcpTools: McpToolsListResult = McpToolsListResult(
			serverId = "local-filesystem",
			tools = listOf(sampleMcpTool()),
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
			calls += update.defaultCwd?.let { "updateSettings:$it" } ?: "updateSettings"
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

		override suspend fun listThreads(cwd: String?, includeArchived: Boolean, limit: Int): ThreadListResult {
			calls += "listThreads:${cwd ?: "<all>"}"
			return if (cwd == null) allHistory else history
		}

		override suspend fun loadThread(threadId: String, limit: Int, beforeItemId: String?): ThreadLoadResult {
			calls += "loadThread:$threadId"
			return loadedThread
		}

		override suspend fun getContextStatus(threadId: String): ContextStatusResult {
			calls += "getContextStatus:$threadId"
			return contextStatus.copy(threadId = threadId)
		}

		override suspend fun getContextSnapshot(snapshotId: String): ContextSnapshotInfo {
			calls += "getContextSnapshot:$snapshotId"
			return sampleContextSnapshot(snapshotId)
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
				contextSnapshot = sampleContextSnapshot("ctxsnap_1"),
			)
		}

		override suspend fun getRecoveryStatus(): RunRecoveryStatusResult {
			calls += "getRecoveryStatus"
			return recoveryStatus
		}

		override suspend fun getObservabilitySnapshot(range: String, cwd: String?): ObservabilitySnapshotResult {
			calls += "getObservabilitySnapshot:$range:$cwd"
			return observabilitySnapshot.copy(range = range)
		}

		override suspend fun getObservabilityTools(range: String, cwd: String?): ObservabilityToolsResult =
			ObservabilityToolsResult(range = range)

		override suspend fun getObservabilityCosts(range: String, cwd: String?): ObservabilityCostsResult =
			ObservabilityCostsResult(range = range)

		override suspend fun listMcpServers(): McpServersListResult {
			calls += "listMcpServers"
			return mcpServers
		}

		override suspend fun listMcpTools(serverId: String): McpToolsListResult {
			calls += "listMcpTools:$serverId"
			return mcpTools.copy(serverId = serverId)
		}

		override suspend fun refreshMcpServer(serverId: String): McpServerRefreshResult {
			calls += "refreshMcp:$serverId"
			return McpServerRefreshResult(sampleMcpServer(status = "connected", toolCount = 1))
		}

		override suspend fun getMemoryStatus(): MemoryStatusResult {
			calls += "getMemoryStatus"
			return MemoryStatusResult(
				enabled = true,
				generateEnabled = true,
				readEnabled = true,
				rootDir = "E:\\BaBiQ\\.babiq\\memories",
				pendingJobs = 1,
				cleanCandidateCount = 5,
				lastSummaryArtifactId = "memart_1",
				phase2Generation = 1,
			)
		}

		override suspend fun setMemorySettings(params: MemorySettingsSetParams): MemorySettingsSetResult {
			calls += "setMemorySettings"
			return MemorySettingsSetResult(
				enabled = params.enabled ?: true,
				generateEnabled = params.generateEnabled ?: true,
				readEnabled = params.readEnabled ?: true,
			)
		}

		override suspend fun listMemoryJobs(limit: Int): MemoryJobsListResult {
			calls += "listMemoryJobs:$limit"
			return MemoryJobsListResult(
				jobs = listOf(
					MemoryJobInfo(
						jobId = "memjob_1",
						jobType = "PHASE2_CONSOLIDATE",
						jobKey = "phase2:1",
						generation = 1,
						status = "PENDING",
						createdAt = "2026-05-27T00:00:00Z",
					),
				),
			)
		}

		override suspend fun listMemoryArtifacts(limit: Int): MemoryArtifactsListResult {
			calls += "listMemoryArtifacts:$limit"
			return MemoryArtifactsListResult(
				artifacts = listOf(
					MemoryArtifactInfo(
						artifactId = "memart_1",
						artifactType = "MEMORY_SUMMARY_MD",
						artifactPath = "memory_summary.md",
						version = 1,
						tokenEstimate = 120,
						createdAt = "2026-05-27T00:00:00Z",
					),
				),
			)
		}

		override suspend fun consolidateMemory(force: Boolean): MemoryConsolidateResult {
			calls += "consolidateMemory:$force"
			return MemoryConsolidateResult(queued = true, jobId = "memjob_1", generation = 1, status = "QUEUED")
		}

		override suspend fun getCapabilityStatus(): CapabilityStatusResult {
			calls += "getCapabilityStatus"
			return CapabilityStatusResult(
				totalCount = 1,
				enabledCount = 1,
				visibleCount = 1,
				deferredCount = 0,
				capabilities = listOf(sampleCapability()),
			)
		}

		override suspend fun searchCapabilities(query: String, limit: Int): CapabilitySearchResult {
			calls += "searchCapabilities:$query:$limit"
			return CapabilitySearchResult("FALLBACK_LEXICAL", listOf(sampleCapability(exposureMode = "DEFERRED")))
		}

		override suspend fun setCapabilitySettings(params: CapabilitySettingsSetParams): CapabilitySettingsSetResult {
			calls += "setCapability:${params.capabilityId}:${params.enabled}:${params.exposureMode}"
			return CapabilitySettingsSetResult(sampleCapability(params.capabilityId, params.exposureMode ?: "VISIBLE"))
		}

		override suspend fun listSkills(): SkillListResult {
			calls += "listSkills"
			return SkillListResult(emptyList())
		}

		override suspend fun getSkill(skillId: String): SkillGetResult {
			calls += "getSkill:$skillId"
			return SkillGetResult(
				skill = com.wzx.babiq.desktop.protocol.SkillInfo(
					id = skillId,
					namespace = "local",
					name = "demo",
					description = "demo",
					sourceDirectory = "E:\\skills",
					skillFile = "E:\\skills\\SKILL.md",
					contentHash = "hash",
				),
				content = "# demo",
			)
		}

		override suspend fun searchMemory(query: String, threadId: String?): MemorySearchResult {
			calls += "searchMemory:$query:$threadId"
			return MemorySearchResult("LEXICAL")
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

	private fun sampleContextStatus(threadId: String): ContextStatusResult =
		ContextStatusResult(
			threadId = threadId,
			windowOrdinal = 0,
			modelContextWindow = 32768,
			autoCompactThreshold = 22937,
			lastSnapshotId = "ctxsnap_1",
			lastEstimatedTokens = 1200,
			lastActualPromptTokens = 1300,
			usageRatio = 0.039,
			status = "ok",
		)

	private fun sampleContextSnapshot(snapshotId: String): ContextSnapshotInfo =
		ContextSnapshotInfo(
			snapshotId = snapshotId,
			threadId = "thr_history",
			turnId = "turn-1",
			phase = "pre_model_call",
			modelContextWindow = 32768,
			autoCompactThreshold = 22937,
			estimatedTokens = 1200,
			includedItemCount = 1,
			excludedItemCount = 0,
			usageRatio = 0.039,
			createdAt = "2026-05-26T08:00:00Z",
		)

	private fun sampleMcpServer(
		status: String = "connected",
		toolCount: Int = 1,
	): McpServerInfo =
		McpServerInfo(
			serverId = "local-filesystem",
			displayName = "本地文件 MCP",
			transport = "stdio",
			enabled = true,
			status = status,
			toolCount = toolCount,
		)

	private fun sampleMcpTool(): McpToolInfo =
		McpToolInfo(
			serverId = "local-filesystem",
			toolName = "read_file",
			namespacedName = "mcp.local-filesystem.read_file",
			description = "Read file",
			enabled = true,
		)

	private fun sampleCapability(
		capabilityId: String = "local.exec_shell",
		exposureMode: String = "VISIBLE",
	): CapabilityInfo =
		CapabilityInfo(
			capabilityId = capabilityId,
			type = "LOCAL_TOOL",
			namespace = "local",
			name = "exec_shell",
			displayName = "exec_shell",
			description = "执行 Shell 命令",
			exposureMode = exposureMode,
			enabled = true,
		)
}
