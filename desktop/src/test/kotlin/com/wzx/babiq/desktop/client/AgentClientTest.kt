package com.wzx.babiq.desktop.client

import com.wzx.babiq.desktop.protocol.JsonRpcRequest
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
	fun `approval respond 发送审批决策`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val delivered = client.respondApproval(
			threadId = "thread-1",
			turnId = "turn-1",
			decision = "approve",
		)

		val request = transport.sent.single()
		assertTrue(delivered)
		assertEquals("approval/respond", request.method)
		assertEquals("approve", request.paramsText("decision"))
	}

	@Test
	fun `listProviders 解析 provider 列表`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val providers = client.listProviders()

		assertEquals("mock-provider", providers.providers.single().id)
		assertEquals("Mock (P1-1 placeholder)", providers.providers.single().label)
	}

	@Test
	fun `setActiveProvider 发送切换请求`() = runTest {
		val transport = FakeAgentTransport()
		val client = AgentClient(transport, backgroundScope)
		client.connect()

		val ok = client.setActiveProvider("qwen", "qwen-plus")

		val request = transport.sent.single()
		assertTrue(ok)
		assertEquals("model/providers/set-active", request.method)
		assertEquals("qwen", request.paramsText("providerId"))
		assertEquals("qwen-plus", request.paramsText("modelId"))
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

	private class FakeAgentTransport(
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
				"model/providers/list" -> buildJsonObject {
					put(
						"providers",
						buildJsonArray {
							add(
								buildJsonObject {
									put("id", "mock-provider")
									put("label", "Mock (P1-1 placeholder)")
								},
							)
						},
					)
				}
				"model/providers/set-active" -> buildJsonObject { put("ok", true) }
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

	private fun JsonRpcRequest.paramsText(name: String): String =
		params.jsonObject[name]!!.jsonPrimitive.content

	private fun JsonRpcRequest.paramsObject(name: String): JsonObject =
		params.jsonObject[name]!!.jsonObject

	private fun JsonObject.paramsText(name: String): String =
		this[name]!!.jsonPrimitive.content
}
