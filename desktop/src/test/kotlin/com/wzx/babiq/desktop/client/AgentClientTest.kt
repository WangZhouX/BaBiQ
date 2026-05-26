package com.wzx.babiq.desktop.client

import com.wzx.babiq.desktop.protocol.JsonRpcRequest
import com.wzx.babiq.desktop.protocol.ContextSnapshotInfo
import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.McpServerRefreshResult
import com.wzx.babiq.desktop.protocol.McpServersListResult
import com.wzx.babiq.desktop.protocol.McpToolsListResult
import com.wzx.babiq.desktop.protocol.ObservabilityCostsResult
import com.wzx.babiq.desktop.protocol.ObservabilitySnapshotResult
import com.wzx.babiq.desktop.protocol.ObservabilityToolsResult
import com.wzx.babiq.desktop.protocol.ProviderDeleteResult
import com.wzx.babiq.desktop.protocol.ProviderSaveParams
import com.wzx.babiq.desktop.protocol.ProviderTestResult
import com.wzx.babiq.desktop.protocol.RunRecoveryStatusResult
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.RunTurnListResult
import com.wzx.babiq.desktop.protocol.SandboxPolicyResult
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.SettingsUpdateParams
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentClientTest {

	@Test
	fun `createThread 发送 thread create 并返回 threadId`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val threadId = client.createThread("E:\\BaBiQ")

		assertEquals("thread-1", threadId)
		assertEquals("thread/create", transport.sent.single().method)
		assertEquals("E:\\BaBiQ", transport.sent.single().paramsText("cwd"))
	}

	@Test
	fun `startTurn 发送 turn start 并返回 turnId`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val turnId = client.startTurn("thread-1", "分析项目结构", providerId = "qwen")

		val request = transport.sent.single()
		assertEquals("turn-1", turnId)
		assertEquals("turn/start", request.method)
		assertEquals("thread-1", request.paramsText("threadId"))
		assertEquals("分析项目结构", request.paramsObject("input").paramsText("text"))
		assertEquals("qwen", request.paramsText("providerId"))
	}

	@Test
	fun `approval respond 发送审批决策和 scope`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val delivered = client.respondApproval(
			threadId = "thread-1",
			turnId = "turn-1",
			decision = "always",
			scope = "session",
		)

		val request = transport.sent.single()
		assertTrue(delivered)
		assertEquals("approval/respond", request.method)
		assertEquals("always", request.paramsText("decision"))
		assertEquals("session", request.paramsText("scope"))
	}

	@Test
	fun `listProviders 解析 provider 列表`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val providers = client.listProviders()

		assertEquals("provider/list", transport.sent.single().method)
		assertEquals("mock-provider", providers.providers.single().id)
		assertEquals("Mock (P1-1 placeholder)", providers.providers.single().label)
	}

	@Test
	fun `setActiveProvider 通过 settings update 持久化切换请求`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val ok = client.setActiveProvider("qwen", "qwen-plus")

		val request = transport.sent.single()
		assertTrue(ok)
		assertEquals("settings/update", request.method)
		assertEquals("qwen", request.paramsText("activeProviderId"))
	}

	@Test
	fun `settings get 和 update 使用本地设置协议`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val settings = client.getSettings()
		val updated = client.updateSettings(SettingsUpdateParams(sandboxMode = "READ_ONLY"))

		assertEquals("settings/get", transport.sent[0].method)
		assertEquals("settings/update", transport.sent[1].method)
		assertEquals("DANGER_FULL_ACCESS", settings.sandboxMode)
		assertEquals("READ_ONLY", updated.sandboxMode)
	}

	@Test
	fun `provider create delete test 使用 provider 协议且不回显 apiKey`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val created = client.createProvider(sampleProviderSaveParams())
		val tested: ProviderTestResult = client.testProvider("custom-openai")
		val deleted: ProviderDeleteResult = client.deleteProvider("custom-openai")

		assertEquals("provider/create", transport.sent[0].method)
		assertEquals("sk-secret", transport.sent[0].paramsText("apiKey"))
		assertEquals("custom-openai", created.id)
		assertEquals(true, created.hasApiKey)
		assertEquals(null, created.apiKey)
		assertEquals("provider/test", transport.sent[1].method)
		assertTrue(tested.ok)
		assertEquals("provider/delete", transport.sent[2].method)
		assertTrue(deleted.archived || deleted.ok)
	}

	@Test
	fun `sandbox 和 approval policy 可以写入后端`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val sandbox = client.setSandboxPolicy("WORKSPACE_WRITE")
		val approval = client.setApprovalPolicy("NEVER")

		assertEquals("sandbox/policy/set", transport.sent[0].method)
		assertEquals("WORKSPACE_WRITE", sandbox.mode)
		assertEquals("approval/policy/set", transport.sent[1].method)
		assertEquals("NEVER", approval.approvalPolicy)
	}

	@Test
	fun `getSandboxPolicy 拉取后端权限策略`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val policy: SandboxPolicyResult = client.getSandboxPolicy()

		val request = transport.sent.single()
		assertEquals("sandbox/policy", request.method)
		assertEquals("DANGER_FULL_ACCESS", policy.mode)
		assertEquals("完全访问权限", policy.label)
	}

	@Test
	fun `listThreads 发送 thread list 并解析最近会话`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val result = client.listThreads("E:\\BaBiQ")

		val request = transport.sent.single()
		assertEquals("thread/list", request.method)
		assertEquals("E:\\BaBiQ", request.paramsText("cwd"))
		assertEquals("thr_1", result.threads.single().threadId)
	}

	@Test
	fun `listThreads 不传 cwd 时请求所有工作区历史`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val result = client.listThreads(cwd = null, limit = 100)

		val request = transport.sent.single()
		assertEquals("thread/list", request.method)
		assertFalse(request.params.jsonObject.containsKey("cwd"))
		assertEquals("100", request.paramsText("limit"))
		assertEquals("thr_1", result.threads.single().threadId)
	}

	@Test
	fun `loadThread 发送 thread load 并解析历史 item`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val result = client.loadThread("thr_1")

		val request = transport.sent.single()
		assertEquals("thread/load", request.method)
		assertEquals("thr_1", request.paramsText("threadId"))
		assertIs<ThreadItem.UserMessage>(result.items.single())
	}

	@Test
	fun `archiveThread 发送 thread archive`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val result = client.archiveThread("thr_1")

		val request = transport.sent.single()
		assertEquals("thread/archive", request.method)
		assertEquals("thr_1", request.paramsText("threadId"))
		assertTrue(result.archived)
	}

	@Test
	fun `运行记录接口可以读取列表 详情和恢复状态`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val turns: RunTurnListResult = client.listRunTurns("thr_1")
		val detail: RunTurnDetailResult = client.getRunTurn("turn_1")
		val recovery: RunRecoveryStatusResult = client.getRecoveryStatus()

		assertEquals("run/turns/list", transport.sent[0].method)
		assertEquals("thr_1", transport.sent[0].paramsText("threadId"))
		assertEquals("turn_1", turns.turns.single().turnId)
		assertEquals("run/turn/get", transport.sent[1].method)
		assertEquals("turn_1", detail.turn.turnId)
		assertEquals("cmd", detail.toolCalls.single().toolName)
		assertEquals("ctxsnap_1", detail.contextSnapshot?.snapshotId)
		assertEquals("run/recovery/status", transport.sent[2].method)
		assertEquals(1, recovery.interruptedTurns)
	}

	@Test
	fun `上下文窗口接口可以读取状态和快照`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val status: ContextStatusResult = client.getContextStatus("thr_1")
		val snapshot: ContextSnapshotInfo = client.getContextSnapshot("ctxsnap_1")

		assertEquals("context/status", transport.sent[0].method)
		assertEquals("thr_1", transport.sent[0].paramsText("threadId"))
		assertEquals("ctxsnap_1", status.lastSnapshotId)
		assertEquals("context/snapshot/get", transport.sent[1].method)
		assertEquals("ctxsnap_1", transport.sent[1].paramsText("snapshotId"))
		assertEquals(1, snapshot.items.size)
	}

	@Test
	fun `本地可观测接口可以读取统计快照 工具和模型用量`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val snapshot: ObservabilitySnapshotResult = client.getObservabilitySnapshot("30d", "E:\\BaBiQ")
		val tools: ObservabilityToolsResult = client.getObservabilityTools("all")
		val costs: ObservabilityCostsResult = client.getObservabilityCosts("7d", "E:\\BaBiQ")

		assertEquals("observability/snapshot", transport.sent[0].method)
		assertEquals("30d", transport.sent[0].paramsText("range"))
		assertEquals("E:\\BaBiQ", transport.sent[0].paramsText("cwd"))
		assertEquals(2, snapshot.totals.turns)
		assertEquals("observability/tools", transport.sent[1].method)
		assertEquals("read_file", tools.tools.single().toolName)
		assertEquals("observability/costs", transport.sent[2].method)
		assertEquals("deepseek-v4-pro", costs.models.single().model)
	}

	@Test
	fun `MCP 接口可以读取 server 工具并刷新`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val servers: McpServersListResult = client.listMcpServers()
		val tools: McpToolsListResult = client.listMcpTools("local-filesystem")
		val refreshed: McpServerRefreshResult = client.refreshMcpServer("local-filesystem")

		assertEquals("mcp/servers/list", transport.sent[0].method)
		assertEquals("local-filesystem", servers.servers.single().serverId)
		assertEquals("mcp/tools/list", transport.sent[1].method)
		assertEquals("local-filesystem", transport.sent[1].paramsText("serverId"))
		assertEquals("read_file", tools.tools.single().toolName)
		assertEquals("mcp/servers/refresh", transport.sent[2].method)
		assertEquals("connected", refreshed.server.status)
	}

	@Test
	fun `json rpc error 会转成 AgentClientException`() = runTest {
		val transport = FakeAgentTransport(errorMethods = setOf("thread/create"))
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val error = assertFailsWith<AgentClientException> {
			client.createThread("")
		}

		assertEquals(-32602, error.code)
		assertTrue(error.message.contains("缺少必填字段"))
	}

	@Test
	fun `服务端通知会进入 events flow`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		transport.emitNotification(
			"""
			{
			  "jsonrpc": "2.0",
			  "method": "turn/started",
			  "params": { "threadId": "thread-1", "turnId": "turn-1" }
			}
			""".trimIndent(),
		)

		val event = client.events.first()
		assertIs<ServerEvent.TurnStarted>(event)
	}

	private inner class FakeAgentTransport(
		private val errorMethods: Set<String> = emptySet(),
	) : AgentTransport {
		override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
		val sent = mutableListOf<JsonRpcRequest>()

		override suspend fun connect() = Unit

		override suspend fun send(text: String) {
			val request = protocolJson.decodeFromString(JsonRpcRequest.serializer(), text)
			sent += request
			incoming.emit(responseFor(request))
		}

		suspend fun emitNotification(text: String) {
			incoming.emit(text)
		}

		override fun close() = Unit

		private fun responseFor(request: JsonRpcRequest): String {
			if (request.method in errorMethods) {
				return """{"jsonrpc":"2.0","id":${request.id},"error":{"code":-32602,"message":"缺少必填字段"}}"""
			}
			val result = when (request.method) {
				"thread/create" -> buildJsonObject { put("threadId", "thread-1") }
				"turn/start" -> buildJsonObject { put("turnId", "turn-1") }
				"approval/respond" -> buildJsonObject { put("delivered", true) }
				"provider/list" -> buildJsonObject {
					put(
						"providers",
						buildJsonArray {
							add(
								buildJsonObject {
									put("id", "mock-provider")
									put("label", "Mock (P1-1 placeholder)")
									put("displayName", "Mock (P1-1 placeholder)")
									put("type", "OPENAI_COMPATIBLE")
									put("baseUrl", "https://relay.example.com/v1")
									put("model", "mock-model")
									put("contextWindow", 64000)
									put("enabled", true)
									put("hasApiKey", true)
								},
							)
						},
					)
				}
				"settings/get" -> settingsResult()
				"settings/update" -> settingsResult(
					activeProviderId = request.params.jsonObject["activeProviderId"]?.jsonPrimitive?.content ?: "deepseek-official",
					sandboxMode = request.params.jsonObject["sandboxMode"]?.jsonPrimitive?.content ?: "READ_ONLY",
					approvalPolicy = request.params.jsonObject["approvalPolicy"]?.jsonPrimitive?.content ?: "ON_REQUEST",
				)
				"provider/create" -> providerMutationResult()
				"provider/delete" -> buildJsonObject {
					put("ok", true)
					put("providerId", "custom-openai")
					put("archived", true)
				}
				"provider/test" -> buildJsonObject {
					put("ok", true)
					put("providerId", "custom-openai")
					put("message", "Provider 配置可用")
				}
				"sandbox/policy/set" -> buildJsonObject {
					put("mode", request.paramsText("mode"))
					put("label", "工作区可写")
				}
				"approval/policy/set" -> buildJsonObject {
					put("approvalPolicy", request.paramsText("approvalPolicy"))
				}
				"sandbox/policy" -> buildJsonObject {
					put("mode", "DANGER_FULL_ACCESS")
					put("label", "完全访问权限")
				}
				"thread/list" -> buildJsonObject {
					put(
						"threads",
						buildJsonArray {
							add(
								buildJsonObject {
									put("threadId", "thr_1")
									put("title", "分析 BaBiQ 项目结构")
									put("cwd", "E:\\BaBiQ")
									put("providerId", "deepseek")
									put("model", "deepseek-v4-pro")
									put("status", "active")
									put("lastTurnStatus", "COMPLETED")
									put("updatedAt", "2026-05-24T08:00:00Z")
									put("messageCount", 1)
								},
							)
						},
					)
				}
				"thread/load" -> buildJsonObject {
					put(
						"thread",
						buildJsonObject {
							put("threadId", "thr_1")
							put("title", "分析 BaBiQ 项目结构")
							put("cwd", "E:\\BaBiQ")
							put("status", "active")
						},
					)
					put(
						"items",
						buildJsonArray {
							add(buildJsonObject {
								put("id", "it_user")
								put("type", "userMessage")
								put("text", "你好")
							})
						},
					)
				}
				"thread/archive" -> buildJsonObject {
					put("ok", true)
					put("threadId", "thr_1")
					put("archived", true)
				}
				"run/turns/list" -> buildJsonObject {
					put(
						"turns",
						buildJsonArray {
							add(runTurnSummary())
						},
					)
				}
				"run/turn/get" -> buildJsonObject {
					put("turn", runTurnSummary())
					put(
						"items",
						buildJsonArray {
							add(buildJsonObject {
								put("id", "it_user")
								put("type", "userMessage")
								put("text", "分析项目")
							})
						},
					)
					put(
						"summary",
						buildJsonObject {
							put("id", "summary-1")
							put("type", "turnSummary")
							put("status", "COMPLETED")
							put("model", "deepseek-v4-pro")
							put("promptTokens", 12)
							put("completionTokens", 8)
							put("totalTokens", 20)
							put("toolCalls", 1)
							put("durationMs", 2000)
						},
					)
					put(
						"approvals",
						buildJsonArray {
							add(buildJsonObject {
								put("approvalId", "approval-1")
								put("toolName", "cmd")
								put("argsJson", "{}")
								put("decision", "approve")
								put("status", "resolved")
								put("createdAt", "2026-05-24T08:00:00Z")
								put("resolvedAt", "2026-05-24T08:00:01Z")
							})
						},
					)
					put(
						"toolCalls",
						buildJsonArray {
							add(buildJsonObject {
								put("toolCallId", "tool-1")
								put("toolName", "cmd")
								put("argsJson", "{}")
								put("status", "completed")
								put("resultPreview", "ok")
								put("startedAt", "2026-05-24T08:00:01Z")
								put("completedAt", "2026-05-24T08:00:02Z")
							})
						},
					)
					put("contextSnapshot", contextSnapshot())
				}
				"run/recovery/status" -> buildJsonObject {
					put("lastRecoveredAt", "2026-05-24T08:10:00Z")
					put("interruptedTurns", 1)
					put("expiredTurns", 0)
					put("expiredApprovals", 0)
				}
				"context/status" -> contextStatus()
				"context/snapshot/get" -> contextSnapshot()
				"observability/snapshot" -> observabilitySnapshot(request.paramsText("range"))
				"observability/tools" -> buildJsonObject {
					put("range", request.paramsText("range"))
					put(
						"tools",
						buildJsonArray {
							add(toolStats())
						},
					)
				}
				"observability/costs" -> buildJsonObject {
					put("range", request.paramsText("range"))
					put(
						"models",
						buildJsonArray {
							add(modelStats())
						},
					)
				}
				"mcp/servers/list" -> mcpServers()
				"mcp/tools/list" -> mcpTools(request.paramsText("serverId"))
				"mcp/servers/refresh" -> buildJsonObject {
					put("server", mcpServer(status = "connected", toolCount = 1))
				}
				else -> buildJsonObject { put("ok", true) }
			}
			return protocolJson.encodeToString(
				kotlinx.serialization.json.JsonObject.serializer(),
				buildJsonObject {
					put("jsonrpc", "2.0")
					put("id", request.id)
					put("result", result)
				},
			)
		}
	}

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

	private fun settingsResult(
		activeProviderId: String = "deepseek-official",
		sandboxMode: String = "DANGER_FULL_ACCESS",
		approvalPolicy: String = "ON_REQUEST",
	) = buildJsonObject {
		put("activeProviderId", activeProviderId)
		put("sandboxMode", sandboxMode)
		put("approvalPolicy", approvalPolicy)
		put("defaultCwd", "E:\\BaBiQ")
	}

	private fun providerMutationResult() = buildJsonObject {
		put("id", "custom-openai")
		put("label", "自定义 OpenAI")
		put("displayName", "自定义 OpenAI")
		put("type", "OPENAI_COMPATIBLE")
		put("baseUrl", "https://relay.example.com/v1")
		put("model", "deepseek-chat")
		put("contextWindow", 128000)
		put("enabled", true)
		put("hasApiKey", true)
		put("active", false)
	}

	private fun runTurnSummary() = buildJsonObject {
		put("turnId", "turn_1")
		put("threadId", "thr_1")
		put("status", "COMPLETED")
		put("inputText", "分析项目")
		put("cwd", "E:\\BaBiQ")
		put("providerId", "deepseek")
		put("model", "deepseek-v4-pro")
		put("startedAt", "2026-05-24T08:00:00Z")
		put("completedAt", "2026-05-24T08:00:03Z")
	}

	private fun contextStatus() = buildJsonObject {
		put("threadId", "thr_1")
		put("windowOrdinal", 0)
		put("modelContextWindow", 32768)
		put("autoCompactThreshold", 22937)
		put("lastSnapshotId", "ctxsnap_1")
		put("lastEstimatedTokens", 1200)
		put("lastActualPromptTokens", 1300)
		put("usageRatio", 0.039)
		put("status", "ok")
	}

	private fun contextSnapshot() = buildJsonObject {
		put("snapshotId", "ctxsnap_1")
		put("threadId", "thr_1")
		put("turnId", "turn_1")
		put("phase", "pre_model_call")
		put("providerId", "deepseek")
		put("model", "deepseek-v4-pro")
		put("cwd", "E:\\BaBiQ")
		put("windowOrdinal", 0)
		put("modelContextWindow", 32768)
		put("autoCompactThreshold", 22937)
		put("estimatedTokens", 1200)
		put("actualPromptTokens", 1300)
		put("includedItemCount", 1)
		put("excludedItemCount", 0)
		put("usageRatio", 0.039)
		put("inputPreview", "分析项目")
		put("createdAt", "2026-05-26T08:00:00Z")
		put(
			"items",
			buildJsonArray {
				add(
					buildJsonObject {
						put("sourceId", "it_user")
						put("sourceType", "history_item")
						put("priority", "HISTORY")
						put("included", true)
						put("reason", "最近历史")
						put("tokenEstimate", 100)
					},
				)
			},
		)
	}

	private fun observabilitySnapshot(range: String) = buildJsonObject {
		put("range", range)
		put(
			"totals",
			buildJsonObject {
				put("turns", 2)
				put("failedTurns", 1)
				put("promptTokens", 120)
				put("completionTokens", 80)
				put("totalTokens", 200)
			},
		)
		put(
			"byProvider",
			buildJsonArray {
				add(providerStats())
			},
		)
		put(
			"byModel",
			buildJsonArray {
				add(modelStats())
			},
		)
		put(
			"byTool",
			buildJsonArray {
				add(toolStats())
			},
		)
		put(
			"byStatus",
			buildJsonArray {
				add(buildJsonObject {
					put("status", "COMPLETED")
					put("turns", 1)
				})
			},
		)
	}

	private fun providerStats() = buildJsonObject {
		put("providerId", "deepseek")
		put("turns", 2)
		put("failedTurns", 1)
		put("promptTokens", 120)
		put("completionTokens", 80)
		put("totalTokens", 200)
	}

	private fun modelStats() = buildJsonObject {
		put("providerId", "deepseek")
		put("model", "deepseek-v4-pro")
		put("turns", 2)
		put("failedTurns", 1)
		put("promptTokens", 120)
		put("completionTokens", 80)
		put("totalTokens", 200)
	}

	private fun toolStats() = buildJsonObject {
		put("toolName", "read_file")
		put("calls", 2)
		put("failures", 0)
		put("avgDurationMs", 300)
	}

	private fun mcpServers() = buildJsonObject {
		put(
			"servers",
			buildJsonArray {
				add(mcpServer(status = "connected", toolCount = 1))
			},
		)
	}

	private fun mcpServer(status: String, toolCount: Int) = buildJsonObject {
		put("serverId", "local-filesystem")
		put("displayName", "本地文件 MCP")
		put("transport", "stdio")
		put("enabled", true)
		put("status", status)
		put("toolCount", toolCount)
	}

	private fun mcpTools(serverId: String) = buildJsonObject {
		put("serverId", serverId)
		put(
			"tools",
			buildJsonArray {
				add(
					buildJsonObject {
						put("serverId", serverId)
						put("toolName", "read_file")
						put("namespacedName", "mcp.$serverId.read_file")
						put("description", "Read file")
						put("inputSchema", buildJsonObject { put("type", "object") })
						put("enabled", true)
					},
				)
			},
		)
	}

	private fun JsonRpcRequest.paramsText(name: String): String =
		params.jsonObject[name]!!.jsonPrimitive.content

	private fun JsonRpcRequest.paramsObject(name: String): JsonObject =
		params.jsonObject[name]!!.jsonObject

	private fun JsonObject.paramsText(name: String): String =
		this[name]!!.jsonPrimitive.content
}
