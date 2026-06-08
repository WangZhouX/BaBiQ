package com.wzx.babiq.desktop.client

import com.wzx.babiq.desktop.app.DesktopConfig
import com.wzx.babiq.desktop.protocol.ApprovalRespondParams
import com.wzx.babiq.desktop.protocol.AppSettingsResult
import com.wzx.babiq.desktop.protocol.ApprovalPolicyResult
import com.wzx.babiq.desktop.protocol.ApprovalPolicySetParams
import com.wzx.babiq.desktop.protocol.CapabilitySearchResult
import com.wzx.babiq.desktop.protocol.CapabilitySettingsSetParams
import com.wzx.babiq.desktop.protocol.CapabilitySettingsSetResult
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.ContextSnapshotInfo
import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.ExecutionIntent
import com.wzx.babiq.desktop.protocol.JsonRpcRequest
import com.wzx.babiq.desktop.protocol.JsonRpcResponse
import com.wzx.babiq.desktop.protocol.McpServerRefreshParams
import com.wzx.babiq.desktop.protocol.McpServerRefreshResult
import com.wzx.babiq.desktop.protocol.McpServersListResult
import com.wzx.babiq.desktop.protocol.McpToolsListResult
import com.wzx.babiq.desktop.protocol.MemoryArtifactsListResult
import com.wzx.babiq.desktop.protocol.MemoryConsolidateResult
import com.wzx.babiq.desktop.protocol.MemoryJobsListResult
import com.wzx.babiq.desktop.protocol.MemoryScanResult
import com.wzx.babiq.desktop.protocol.MemorySearchResult
import com.wzx.babiq.desktop.protocol.MemorySettingsSetParams
import com.wzx.babiq.desktop.protocol.MemorySettingsSetResult
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.protocol.ObservabilityCostsResult
import com.wzx.babiq.desktop.protocol.ObservabilitySnapshotResult
import com.wzx.babiq.desktop.protocol.ObservabilityToolsResult
import com.wzx.babiq.desktop.protocol.ProviderDeleteParams
import com.wzx.babiq.desktop.protocol.ProviderDeleteResult
import com.wzx.babiq.desktop.protocol.ProviderListResult
import com.wzx.babiq.desktop.protocol.ProviderMutationResult
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.protocol.ProviderTestParams
import com.wzx.babiq.desktop.protocol.ProviderTestResult
import com.wzx.babiq.desktop.protocol.RunRecoveryStatusResult
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.RunTurnListResult
import com.wzx.babiq.desktop.protocol.SandboxPolicySetParams
import com.wzx.babiq.desktop.protocol.SandboxPolicyResult
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.SettingsUpdateParams
import com.wzx.babiq.desktop.protocol.SkillGetResult
import com.wzx.babiq.desktop.protocol.SkillListResult
import com.wzx.babiq.desktop.protocol.TeamMessageSendParams
import com.wzx.babiq.desktop.protocol.TeamMessageSendResult
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.protocol.ThreadArchiveResult
import com.wzx.babiq.desktop.protocol.ThreadListResult
import com.wzx.babiq.desktop.protocol.ThreadLoadResult
import com.wzx.babiq.desktop.protocol.WorkUnitConfigUpdateParams
import com.wzx.babiq.desktop.protocol.WorkUnitConfigUpdateResult
import com.wzx.babiq.desktop.protocol.WorkUnitListParams
import com.wzx.babiq.desktop.protocol.WorkUnitListResult
import com.wzx.babiq.desktop.protocol.WorkUnitGoalUpdateParams
import com.wzx.babiq.desktop.protocol.WorkUnitGoalUpdateResult
import com.wzx.babiq.desktop.protocol.WorkUnitRemoveParams
import com.wzx.babiq.desktop.protocol.WorkUnitRemoveResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * UI 只依赖这个网关接口，不直接依赖 Ktor 或 JSON-RPC 编解码。
 *
 * 这是 Kotlin/Compose 项目里常用的“端口-适配器”写法：Controller 面向接口编程，
 * 单元测试可以注入 FakeGateway，真实运行时再注入 AgentClient + KtorAgentTransport。
 */
interface AgentGateway {
	/** 后端 notification 事件流，例如 item/added、turn/completed、approval/request。 */
	val events: Flow<ServerEvent>

	/** 建立连接，并开始监听后端事件。 */
	suspend fun connect()

	/** 创建一个与 cwd 绑定的后端 Thread，后续 turn 都在这个工作目录里执行。 */
	suspend fun createThread(cwd: String): String

	/** 启动一轮对话；providerId 为空时由后端使用当前激活模型。 */
	suspend fun startTurn(
		threadId: String,
		prompt: String,
		providerId: String? = null,
		executionIntent: ExecutionIntent? = null,
	): String

	/** 回传工具审批结果，editedArgs 只在“修改后批准”时携带，scope 只在 always 时使用。 */
	suspend fun respondApproval(
		threadId: String,
		turnId: String,
		decision: String,
		editedArgs: String? = null,
		scope: String? = null,
	): Boolean

	/** 读取后端本地设置快照，设置页和下一轮 turn 默认值都基于它。 */
	suspend fun getSettings(): AppSettingsResult

	/** 局部更新本地设置；空字段表示保留原值。 */
	suspend fun updateSettings(update: SettingsUpdateParams): AppSettingsResult

	/** 新增 Provider，API Key 只在这个请求中短暂传输。 */
	suspend fun createProvider(params: ProviderSaveParams): ProviderMutationResult

	/** 编辑 Provider；apiKey 为空表示沿用后端已有密钥。 */
	suspend fun updateProvider(params: ProviderSaveParams): ProviderMutationResult

	/** 删除或禁用 Provider，不删除历史会话。 */
	suspend fun deleteProvider(providerId: String): ProviderDeleteResult

	/** 轻量测试 Provider 配置是否可被后端模型工厂使用。 */
	suspend fun testProvider(providerId: String): ProviderTestResult

	/** 读取后端当前可用 Provider/Model 列表。 */
	suspend fun listProviders(): ProviderListResult

	/** 读取后端真实沙箱/权限策略，用于输入框上下文条展示权限 chip。 */
	suspend fun getSandboxPolicy(): SandboxPolicyResult

	/** 写入默认沙箱策略，下一轮 turn 生效。 */
	suspend fun setSandboxPolicy(mode: String): SandboxPolicyResult

	/** 写入默认审批策略，下一轮 turn 生效。 */
	suspend fun setApprovalPolicy(approvalPolicy: String): ApprovalPolicyResult

	/** 设置后端当前激活 Provider/Model，下一轮 turn 生效。 */
	suspend fun setActiveProvider(providerId: String, modelId: String? = null): Boolean

	/** 按工作目录读取最近会话列表；cwd 为空时返回所有工作区，供 Sidebar 汇总项目列表。 */
	suspend fun listThreads(cwd: String? = null, includeArchived: Boolean = false, limit: Int = 30): ThreadListResult

	/** 加载指定会话的历史 item，供用户点击最近对话时恢复聊天流。 */
	suspend fun loadThread(threadId: String, limit: Int = 200, beforeItemId: String? = null): ThreadLoadResult

	/** 读取当前会话最近一次上下文窗口状态，输入框上下文条用它展示 token 使用率。 */
	suspend fun getContextStatus(threadId: String): ContextStatusResult

	/** 读取单个上下文快照详情，运行详情面板和后续审计页按需调用。 */
	suspend fun getContextSnapshot(snapshotId: String): ContextSnapshotInfo

	/** 软归档会话，让默认最近列表隐藏它但不删除历史数据。 */
	suspend fun archiveThread(threadId: String): ThreadArchiveResult

	/** 按会话读取历史 turn 运行记录，运行详情面板展开时使用。 */
	suspend fun listRunTurns(threadId: String, limit: Int = 20, cursor: String? = null): RunTurnListResult

	/** 读取单个历史 turn 的 item、审批、工具调用和摘要详情。 */
	suspend fun getRunTurn(turnId: String): RunTurnDetailResult

	/** 读取后端最近一次启动恢复报告，帮助 UI 说明 interrupted/expired 的来源。 */
	suspend fun getRecoveryStatus(): RunRecoveryStatusResult

	/** 读取本地可观测统计总览，运行详情面板默认调用该接口。 */
	suspend fun getObservabilitySnapshot(range: String = "7d", cwd: String? = null): ObservabilitySnapshotResult

	/** 读取工具维度统计，预留给后续工具明细面板按需刷新。 */
	suspend fun getObservabilityTools(range: String = "7d", cwd: String? = null): ObservabilityToolsResult

	/** 读取 Provider/Model 用量统计；协议名仍叫 costs，但 UI 只展示 token 聚合。 */
	suspend fun getObservabilityCosts(range: String = "7d", cwd: String? = null): ObservabilityCostsResult

	/** 读取本地 MCP server 状态列表。 */
	suspend fun listMcpServers(): McpServersListResult

	/** 读取指定 MCP server 的工具列表。 */
	suspend fun listMcpTools(serverId: String): McpToolsListResult

	/** 手动刷新指定 MCP server 的连接和工具目录。 */
	suspend fun refreshMcpServer(serverId: String): McpServerRefreshResult

	/** 读取长期记忆流水线状态，供设置页和后续审计入口展示。 */
	suspend fun getMemoryStatus(): MemoryStatusResult

	/** 局部更新长期记忆开关。 */
	suspend fun setMemorySettings(params: MemorySettingsSetParams): MemorySettingsSetResult

	/** 读取最近长期记忆后台任务。 */
	suspend fun listMemoryJobs(limit: Int = 20): MemoryJobsListResult

	/** 读取最近长期记忆产物。 */
	suspend fun listMemoryArtifacts(limit: Int = 20): MemoryArtifactsListResult

	/** 手动触发长期记忆 Phase2 归并。 */
	suspend fun consolidateMemory(force: Boolean = false): MemoryConsolidateResult

	/** 手动触发长期记忆 Phase1 idle 扫描，用于设置页“立即扫描”。 */
	suspend fun scanMemory(): MemoryScanResult

	/** 读取统一能力目录状态，设置页用它控制工具、MCP 和 Skill 的暴露模式。 */
	suspend fun getCapabilityStatus(): CapabilityStatusResult

	/** 搜索能力 metadata，供设置页调试按需能力发现。 */
	suspend fun searchCapabilities(query: String, limit: Int = 8): CapabilitySearchResult

	/** 更新单个能力的开关或暴露模式，下一轮 Agent 装配工具时生效。 */
	suspend fun setCapabilitySettings(params: CapabilitySettingsSetParams): CapabilitySettingsSetResult

	/** 读取本地 Skill metadata 列表。 */
	suspend fun listSkills(): SkillListResult

	/** 按 id 读取单个 Skill 正文。 */
	suspend fun getSkill(skillId: String): SkillGetResult

	/** 手动检索长期记忆，用于设置页确认 read path 的候选来源。 */
	suspend fun searchMemory(query: String, threadId: String? = null): MemorySearchResult

	/** 读取当前会话中仍可见的编排/团队工作容器。 */
	suspend fun listWorkUnits(threadId: String): WorkUnitListResult

	/** 手动移除一个已完成或空闲的工作容器；运行中的容器由后端拒绝移除。 */
	suspend fun removeWorkUnit(workUnitId: String): WorkUnitRemoveResult

	/** 直接保存工作容器中的待执行目标，供右侧详情面板配置使用。 */
	suspend fun updateWorkUnitGoal(
		threadId: String,
		workUnitId: String,
		goalId: String,
		goalText: String,
	): WorkUnitGoalUpdateResult

	/** 保存编排节点或团队成员配置快照；不会启动执行。 */
	suspend fun updateWorkUnitConfig(
		threadId: String,
		workUnitId: String,
		configJson: String,
	): WorkUnitConfigUpdateResult

	/** 向当前团队协作中的某个队友直发补充消息。 */
	suspend fun sendTeamMessage(teamId: String, toAgent: String, content: String): TeamMessageSendResult
}

/**
 * AgentClient 负责把类型安全的方法调用翻译成 JSON-RPC 2.0 报文。
 *
 * pending 表保存“请求 id -> 等待中的响应”，后端返回同一个 id 时再唤醒对应协程；
 * 不带 id 的 notification 则进入 events 流，由 ChatController 交给 reducer 更新界面。
 *
 * @param transport WebSocket 传输层，负责收发纯文本帧；AgentClient 只关心 JSON-RPC 语义。
 * @param scope 内部 collector 使用的协程作用域，默认放在 Default 线程池。
 * @param config 桌面端连接配置，提供请求超时等参数。
 */
class AgentClient(
	private val transport: AgentTransport,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val config: DesktopConfig = DesktopConfig(),
) : AgentGateway, AutoCloseable {
	// JSON-RPC request id 单调递增，保证同一连接里的响应能和请求配对。
	private val nextId = AtomicLong(1)

	// pending 保存“已经发出去但还没收到 response”的请求。
	private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

	// 后端 notification 没有 id，不能唤醒 pending，只能作为事件广播给状态层。
	private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 128)

	// 防止重复 connect() 时启动多个 collector，导致同一后端事件被消费多次。
	private var collecting = false

	override val events: Flow<ServerEvent> = _events

	/**
	 * 连接 WebSocket 并启动后端消息收集。
	 */
	override suspend fun connect() {
		transport.connect()
		if (!collecting) {
			collecting = true
			// 使用 UNDISPATCHED 让 collector 在 connect() 返回前就开始订阅。
			// 这能避免测试或高速后端“先发响应、后启动 collector”造成的竞态。
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				transport.incoming.collect(::handleIncoming)
			}
		}
	}

	/**
	 * 调用后端 `thread/create`，把桌面端选中的 cwd 固化到后端 Thread。
	 */
	override suspend fun createThread(cwd: String): String {
		val response = request(
			method = "thread/create",
			params = buildJsonObject { put("cwd", cwd) },
		)
		return response.requireResult().jsonObject.requiredText("threadId")
	}

	/**
	 * 调用后端 `turn/start`。
	 *
	 * 注意 providerId 是可选的：为空时后端使用当前 active provider；不为空时本轮显式指定。
	 */
	override suspend fun startTurn(
		threadId: String,
		prompt: String,
		providerId: String?,
		executionIntent: ExecutionIntent?,
	): String {
		val params = buildJsonObject {
			put("threadId", threadId)
			put("input", buildJsonObject { put("text", prompt) })
			if (!providerId.isNullOrBlank()) {
				put("providerId", providerId)
			}
			if (executionIntent != null) {
				put("executionIntent", protocolJson.encodeToJsonElement(ExecutionIntent.serializer(), executionIntent))
			}
		}
		val response = request("turn/start", params)
		return response.requireResult().jsonObject.requiredText("turnId")
	}

	/**
	 * 调用后端 `approval/respond`，让 Human-in-the-loop 暂停点继续运行。
	 */
	override suspend fun respondApproval(
		threadId: String,
		turnId: String,
		decision: String,
		editedArgs: String?,
		scope: String?,
	): Boolean {
		val response = request(
			method = "approval/respond",
			params = protocolJson.encodeToJsonElement(
				ApprovalRespondParams.serializer(),
				ApprovalRespondParams(threadId, turnId, decision, editedArgs, scope),
			),
		)
		val result = response.requireResult().jsonObject
		return result["delivered"]?.jsonPrimitive?.booleanOrNull
			?: result["ok"]?.jsonPrimitive?.booleanOrNull
			?: true
	}

	/**
	 * 调用后端 `settings/get`，读取当前本地设置。
	 */
	override suspend fun getSettings(): AppSettingsResult {
		val response = request("settings/get", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(AppSettingsResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `settings/update`，只提交非空字段。
	 */
	override suspend fun updateSettings(update: SettingsUpdateParams): AppSettingsResult {
		val response = request(
			method = "settings/update",
			params = protocolJson.encodeToJsonElement(SettingsUpdateParams.serializer(), update),
		)
		return protocolJson.decodeFromJsonElement(AppSettingsResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `provider/create`，新增本地 Provider。
	 */
	override suspend fun createProvider(params: ProviderSaveParams): ProviderMutationResult {
		val response = request(
			method = "provider/create",
			params = protocolJson.encodeToJsonElement(ProviderSaveParams.serializer(), params),
		)
		return protocolJson.decodeFromJsonElement(ProviderMutationResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `provider/update`，更新 Provider 非敏感字段或替换 API Key。
	 */
	override suspend fun updateProvider(params: ProviderSaveParams): ProviderMutationResult {
		val response = request(
			method = "provider/update",
			params = protocolJson.encodeToJsonElement(ProviderSaveParams.serializer(), params),
		)
		return protocolJson.decodeFromJsonElement(ProviderMutationResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `provider/delete`，P2-3 语义是禁用 Provider。
	 */
	override suspend fun deleteProvider(providerId: String): ProviderDeleteResult {
		val response = request(
			method = "provider/delete",
			params = protocolJson.encodeToJsonElement(ProviderDeleteParams.serializer(), ProviderDeleteParams(providerId)),
		)
		return protocolJson.decodeFromJsonElement(ProviderDeleteResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `provider/test`，做不发起真实聊天的轻量检查。
	 */
	override suspend fun testProvider(providerId: String): ProviderTestResult {
		val response = request(
			method = "provider/test",
			params = protocolJson.encodeToJsonElement(ProviderTestParams.serializer(), ProviderTestParams(providerId)),
		)
		return protocolJson.decodeFromJsonElement(ProviderTestResult.serializer(), response.requireResult())
	}

	/**
	 * 拉取后端模型列表，供 UI 下拉框渲染。
	 */
	override suspend fun listProviders(): ProviderListResult {
		val response = request("provider/list", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(ProviderListResult.serializer(), response.requireResult())
	}

	/**
	 * 拉取后端当前沙箱策略，避免前端写死“完全访问权限”。
	 */
	override suspend fun getSandboxPolicy(): SandboxPolicyResult {
		val response = request("sandbox/policy", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(SandboxPolicyResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `sandbox/policy/set`，修改默认沙箱策略。
	 */
	override suspend fun setSandboxPolicy(mode: String): SandboxPolicyResult {
		val response = request(
			method = "sandbox/policy/set",
			params = protocolJson.encodeToJsonElement(SandboxPolicySetParams.serializer(), SandboxPolicySetParams(mode)),
		)
		return protocolJson.decodeFromJsonElement(SandboxPolicyResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `approval/policy/set`，修改默认审批策略。
	 */
	override suspend fun setApprovalPolicy(approvalPolicy: String): ApprovalPolicyResult {
		val response = request(
			method = "approval/policy/set",
			params = protocolJson.encodeToJsonElement(
				ApprovalPolicySetParams.serializer(),
				ApprovalPolicySetParams(approvalPolicy),
			),
		)
		return protocolJson.decodeFromJsonElement(ApprovalPolicyResult.serializer(), response.requireResult())
	}

	/**
	 * 切换后端 active provider/model。
	 */
	override suspend fun setActiveProvider(providerId: String, modelId: String?): Boolean {
		updateSettings(SettingsUpdateParams(activeProviderId = providerId))
		return true
	}

	/**
	 * 调用后端 `thread/list`，读取当前工作目录下的真实最近会话。
	 */
	override suspend fun listThreads(cwd: String?, includeArchived: Boolean, limit: Int): ThreadListResult {
		val response = request(
			method = "thread/list",
			params = buildJsonObject {
				if (!cwd.isNullOrBlank()) {
					put("cwd", cwd)
				}
				put("includeArchived", includeArchived)
				put("limit", limit)
			},
		)
		return protocolJson.decodeFromJsonElement(ThreadListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `thread/load`，并把历史 item 解码成 ThreadItem。
	 */
	override suspend fun loadThread(threadId: String, limit: Int, beforeItemId: String?): ThreadLoadResult {
		val response = request(
			method = "thread/load",
			params = buildJsonObject {
				put("threadId", threadId)
				put("limit", limit)
				if (!beforeItemId.isNullOrBlank()) {
					put("beforeItemId", beforeItemId)
				}
			},
		)
		return protocolJson.decodeFromJsonElement(ThreadLoadResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `context/status`，读取 thread 级上下文窗口摘要。
	 */
	override suspend fun getContextStatus(threadId: String): ContextStatusResult {
		val response = request(
			method = "context/status",
			params = buildJsonObject { put("threadId", threadId) },
		)
		return protocolJson.decodeFromJsonElement(ContextStatusResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `context/snapshot/get`，读取一次模型调用前的上下文快照。
	 */
	override suspend fun getContextSnapshot(snapshotId: String): ContextSnapshotInfo {
		val response = request(
			method = "context/snapshot/get",
			params = buildJsonObject { put("snapshotId", snapshotId) },
		)
		return protocolJson.decodeFromJsonElement(ContextSnapshotInfo.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `thread/archive`，执行软归档。
	 */
	override suspend fun archiveThread(threadId: String): ThreadArchiveResult {
		val response = request(
			method = "thread/archive",
			params = buildJsonObject { put("threadId", threadId) },
		)
		return protocolJson.decodeFromJsonElement(ThreadArchiveResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `run/turns/list`，读取当前会话的历史运行记录。
	 *
	 * @param threadId 当前打开的会话 id。
	 * @param limit 本次最多读取多少条摘要，P2-4 默认只拉最近 20 条，避免详情面板一次过重。
	 * @param cursor 后续分页游标；P2-4 暂时传 null。
	 */
	override suspend fun listRunTurns(threadId: String, limit: Int, cursor: String?): RunTurnListResult {
		val response = request(
			method = "run/turns/list",
			params = buildJsonObject {
				put("threadId", threadId)
				put("limit", limit)
				if (!cursor.isNullOrBlank()) {
					put("cursor", cursor)
				}
			},
		)
		return protocolJson.decodeFromJsonElement(RunTurnListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `run/turn/get`，读取单个 turn 的完整运行详情。
	 */
	override suspend fun getRunTurn(turnId: String): RunTurnDetailResult {
		val response = request(
			method = "run/turn/get",
			params = buildJsonObject { put("turnId", turnId) },
		)
		return protocolJson.decodeFromJsonElement(RunTurnDetailResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `run/recovery/status`，读取启动恢复的最近报告。
	 */
	override suspend fun getRecoveryStatus(): RunRecoveryStatusResult {
		val response = request("run/recovery/status", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(RunRecoveryStatusResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `observability/snapshot`，读取本地 SQLite 运行统计总览。
	 */
	override suspend fun getObservabilitySnapshot(range: String, cwd: String?): ObservabilitySnapshotResult {
		val response = request("observability/snapshot", observabilityParams(range, cwd))
		return protocolJson.decodeFromJsonElement(ObservabilitySnapshotResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `observability/tools`，读取工具调用聚合。
	 */
	override suspend fun getObservabilityTools(range: String, cwd: String?): ObservabilityToolsResult {
		val response = request("observability/tools", observabilityParams(range, cwd))
		return protocolJson.decodeFromJsonElement(ObservabilityToolsResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `observability/costs`，读取模型用量聚合。
	 *
	 * 后端方法名暂时保持兼容，桌面端不要在这里二次计算或展示价格。
	 */
	override suspend fun getObservabilityCosts(range: String, cwd: String?): ObservabilityCostsResult {
		val response = request("observability/costs", observabilityParams(range, cwd))
		return protocolJson.decodeFromJsonElement(ObservabilityCostsResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `mcp/servers/list`，读取本地 MCP server 状态。
	 */
	override suspend fun listMcpServers(): McpServersListResult {
		val response = request("mcp/servers/list", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(McpServersListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `mcp/tools/list`，读取某个 server 的工具目录。
	 */
	override suspend fun listMcpTools(serverId: String): McpToolsListResult {
		val response = request("mcp/tools/list", buildJsonObject { put("serverId", serverId) })
		return protocolJson.decodeFromJsonElement(McpToolsListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `mcp/servers/refresh`，手动重连并刷新工具列表。
	 */
	override suspend fun refreshMcpServer(serverId: String): McpServerRefreshResult {
		val response = request(
			method = "mcp/servers/refresh",
			params = protocolJson.encodeToJsonElement(
				McpServerRefreshParams.serializer(),
				McpServerRefreshParams(serverId),
			),
		)
		return protocolJson.decodeFromJsonElement(McpServerRefreshResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/status`，读取长期记忆流水线状态。
	 */
	override suspend fun getMemoryStatus(): MemoryStatusResult {
		val response = request("memory/status", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(MemoryStatusResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/settings/set`，局部更新长期记忆开关。
	 */
	override suspend fun setMemorySettings(params: MemorySettingsSetParams): MemorySettingsSetResult {
		val response = request(
			method = "memory/settings/set",
			params = protocolJson.encodeToJsonElement(MemorySettingsSetParams.serializer(), params),
		)
		return protocolJson.decodeFromJsonElement(MemorySettingsSetResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/jobs/list`，读取后台任务审计列表。
	 */
	override suspend fun listMemoryJobs(limit: Int): MemoryJobsListResult {
		val response = request("memory/jobs/list", buildJsonObject { put("limit", limit) })
		return protocolJson.decodeFromJsonElement(MemoryJobsListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/artifacts/list`，读取长期记忆产物列表。
	 */
	override suspend fun listMemoryArtifacts(limit: Int): MemoryArtifactsListResult {
		val response = request("memory/artifacts/list", buildJsonObject { put("limit", limit) })
		return protocolJson.decodeFromJsonElement(MemoryArtifactsListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/consolidate`，手动触发 Phase2。
	 */
	override suspend fun consolidateMemory(force: Boolean): MemoryConsolidateResult {
		val response = request("memory/consolidate", buildJsonObject { put("force", force) })
		return protocolJson.decodeFromJsonElement(MemoryConsolidateResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `capability/status`，读取统一能力目录。
	 */
	override suspend fun getCapabilityStatus(): CapabilityStatusResult {
		val response = request("capability/status", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(CapabilityStatusResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `capability/search`，验证按需能力发现效果。
	 */
	override suspend fun searchCapabilities(query: String, limit: Int): CapabilitySearchResult {
		val response = request(
			method = "capability/search",
			params = buildJsonObject {
				put("query", query)
				put("limit", limit)
			},
		)
		return protocolJson.decodeFromJsonElement(CapabilitySearchResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `capability/settings/set`，更新能力暴露策略。
	 */
	override suspend fun setCapabilitySettings(params: CapabilitySettingsSetParams): CapabilitySettingsSetResult {
		val response = request(
			method = "capability/settings/set",
			params = protocolJson.encodeToJsonElement(CapabilitySettingsSetParams.serializer(), params),
		)
		return protocolJson.decodeFromJsonElement(CapabilitySettingsSetResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `skills/list`，读取本地 Skill metadata。
	 */
	override suspend fun listSkills(): SkillListResult {
		val response = request("skills/list", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(SkillListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `skills/get`，按需读取 Skill 正文。
	 */
	override suspend fun getSkill(skillId: String): SkillGetResult {
		val response = request("skills/get", buildJsonObject { put("skillId", skillId) })
		return protocolJson.decodeFromJsonElement(SkillGetResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `memory/search`，手动检索长期记忆 artifact。
	 */
	override suspend fun searchMemory(query: String, threadId: String?): MemorySearchResult {
		val response = request(
			method = "memory/search",
			params = buildJsonObject {
				put("query", query)
				if (!threadId.isNullOrBlank()) {
					put("threadId", threadId)
				}
			},
		)
		return protocolJson.decodeFromJsonElement(MemorySearchResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `workunit/list`，恢复当前 thread 的编排/团队容器列表。
	 */
	override suspend fun listWorkUnits(threadId: String): WorkUnitListResult {
		val response = request(
			method = "workunit/list",
			params = protocolJson.encodeToJsonElement(
				WorkUnitListParams.serializer(),
				WorkUnitListParams(threadId),
			),
		)
		return protocolJson.decodeFromJsonElement(WorkUnitListResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `workunit/remove`，让已完成或待配置的容器从当前 UI 列表消失。
	 */
	override suspend fun removeWorkUnit(workUnitId: String): WorkUnitRemoveResult {
		val response = request(
			method = "workunit/remove",
			params = protocolJson.encodeToJsonElement(
				WorkUnitRemoveParams.serializer(),
				WorkUnitRemoveParams(workUnitId),
			),
		)
		return protocolJson.decodeFromJsonElement(WorkUnitRemoveResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `workunit/goal/update`，让右侧工作容器详情可以直接保存待执行目标。
	 */
	override suspend fun updateWorkUnitGoal(
		threadId: String,
		workUnitId: String,
		goalId: String,
		goalText: String,
	): WorkUnitGoalUpdateResult {
		val response = request(
			method = "workunit/goal/update",
			params = protocolJson.encodeToJsonElement(
				WorkUnitGoalUpdateParams.serializer(),
				WorkUnitGoalUpdateParams(threadId, workUnitId, goalId, goalText),
			),
		)
		return protocolJson.decodeFromJsonElement(WorkUnitGoalUpdateResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `workunit/config/update`，保存 Inspector 中的编排节点/团队成员配置快照。
	 */
	override suspend fun updateWorkUnitConfig(
		threadId: String,
		workUnitId: String,
		configJson: String,
	): WorkUnitConfigUpdateResult {
		val response = request(
			method = "workunit/config/update",
			params = protocolJson.encodeToJsonElement(
				WorkUnitConfigUpdateParams.serializer(),
				WorkUnitConfigUpdateParams(threadId, workUnitId, configJson),
			),
		)
		return protocolJson.decodeFromJsonElement(WorkUnitConfigUpdateResult.serializer(), response.requireResult())
	}

	/**
	 * 调用后端 `team/message/send`，把用户补充信息写入团队协作时间线。
	 */
	override suspend fun sendTeamMessage(teamId: String, toAgent: String, content: String): TeamMessageSendResult {
		val response = request(
			method = "team/message/send",
			params = protocolJson.encodeToJsonElement(
				TeamMessageSendParams.serializer(),
				TeamMessageSendParams(teamId, toAgent, content),
			),
		)
		return protocolJson.decodeFromJsonElement(TeamMessageSendResult.serializer(), response.requireResult())
	}

	/**
	 * 构造 observability 系列接口共用的参数。
	 */
	private fun observabilityParams(range: String, cwd: String?): JsonElement =
		buildJsonObject {
			put("range", range)
			if (!cwd.isNullOrBlank()) {
				put("cwd", cwd)
			}
		}

	/**
	 * 中断正在等待审批的 turn。当前 UI 还没有暴露按钮，但协议客户端先保留能力。
	 */
	suspend fun interruptTurn(turnId: String): Boolean {
		val response = request("turn/interrupt", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	/**
	 * 取消正在运行的 turn。当前 UI 还没有暴露按钮，但协议客户端先保留能力。
	 */
	suspend fun cancelTurn(turnId: String): Boolean {
		val response = request("turn/cancel", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	/**
	 * 释放底层 WebSocket/HTTP client 资源。
	 */
	override fun close() {
		transport.close()
	}

	/**
	 * 发送一个 JSON-RPC request，并等待同 id 的 response。
	 */
	private suspend fun request(method: String, params: JsonElement): JsonRpcResponse {
		val id = nextId.getAndIncrement()
		val deferred = CompletableDeferred<JsonRpcResponse>()
		pending[id] = deferred
		val request = JsonRpcRequest(id = id, method = method, params = params)
		try {
			// 先注册 pending 再发送，确保响应即使立刻回来也能找到等待者。
			transport.send(protocolJson.encodeToString(request))
			val response = withTimeout(config.requestTimeout) { deferred.await() }
			// 后端返回 JSON-RPC error 时，转成普通异常，Controller 可以统一展示错误提示。
			response.error?.let { error ->
				throw AgentClientException(error.code, error.message)
			}
			return response
		} catch (exception: Exception) {
			if (exception.isTransportDisconnectedSignal()) {
				throw AgentClientException(-32098, "后端连接已断开，请重新连接后重试")
			}
			throw exception
		} finally {
			pending.remove(id)
		}
	}

	/**
	 * 处理后端发来的一个原始 JSON 文本帧。
	 */
	private suspend fun handleIncoming(text: String) {
		val root = protocolJson.parseToJsonElement(text).jsonObject
		val id = root["id"]?.jsonPrimitive?.content?.toLongOrNull()
		if (id != null && ("result" in root || "error" in root)) {
			// JSON-RPC response：交还给发起 request 的协程。
			val response = protocolJson.decodeFromString(JsonRpcResponse.serializer(), text)
			pending.remove(id)?.complete(response)
			return
		}

		if ("method" in root) {
			// JSON-RPC notification：没有 request/response 配对，直接作为后端事件广播。
			_events.emit(protocolJson.decodeFromString(ServerEvent.serializer(), text))
		}
	}

	/**
	 * 调用后端 `memory/scan`，手动触发 Phase1 idle 扫描。
	 */
	override suspend fun scanMemory(): MemoryScanResult {
		val response = request("memory/scan", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(MemoryScanResult.serializer(), response.requireResult())
	}

	/**
	 * 将底层 WebSocket/CIO 的关闭信号统一转换为可重连错误。
	 *
	 * Ktor 在旧 session 被关闭后可能抛出英文 "Channel was cancelled"，如果原样冒泡到 UI，
	 * 用户会看到“已连接但发送没反应”。这里只识别传输关闭类信号，业务错误仍保留后端原始 JSON-RPC error。
	 */
	private fun Throwable.isTransportDisconnectedSignal(): Boolean {
		val messages = generateSequence(this) { it.cause }
			.mapNotNull { it.message }
			.map { it.lowercase() }
			.toList()
		return messages.any { message ->
			message.contains("后端连接已断开") ||
				message.contains("尚未连接") ||
				message.contains("已断开") ||
				message.contains("channel was cancelled") ||
				message.contains("channel was closed") ||
				message.contains("connection has been closed") ||
				(message.contains("websocket") && message.contains("closed"))
		}
	}
}

/**
 * JSON-RPC error 在桌面端的异常表示。
 */
class AgentClientException(
	val code: Int,
	override val message: String,
) : RuntimeException(message)

/**
 * 从 JSON object 中读取必填字符串字段；缺字段说明后端响应不满足协议。
 */
private fun kotlinx.serialization.json.JsonObject.requiredText(name: String): String =
	this[name]?.jsonPrimitive?.content ?: error("JSON-RPC result 缺少字段: $name")
