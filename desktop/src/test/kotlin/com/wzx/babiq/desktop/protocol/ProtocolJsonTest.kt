package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProtocolJsonTest {

	@Test
	fun `可以解析 item added turnSummary 通知`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "summary-1",
			      "type": "turnSummary",
			      "status": "completed",
			      "model": "qwen-plus",
			      "promptTokens": 1824,
			      "completionTokens": 386,
			      "totalTokens": 2210,
			      "toolCalls": 5,
			      "estimatedCostUsd": 0.0021,
			      "durationMs": 8200
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val summary = assertIs<ThreadItem.TurnSummary>(added.item)

		assertEquals("thread-1", added.threadId)
		assertEquals("turn-1", added.turnId)
		assertEquals("summary-1", summary.id)
		assertEquals(1824, summary.promptTokens)
		assertEquals(386, summary.completionTokens)
		assertEquals(0.0021, summary.estimatedCostUsd)
	}

	@Test
	fun `未知通知会保留原始参数用于运行详情`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "vendor/custom",
			  "params": { "message": "hello" }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val unknown = assertIs<ServerEvent.Unknown>(event)

		assertEquals("vendor/custom", unknown.method)
		assertEquals("hello", unknown.params.jsonObject["message"]?.jsonPrimitive?.content)
	}

	@Test
	fun `可以解析成功和错误响应`() {
		val okJson = """{"jsonrpc":"2.0","id":7,"result":{"threadId":"thread-1"}}"""
		val errorJson = """{"jsonrpc":"2.0","id":8,"error":{"code":-32602,"message":"缺少必填字段: cwd","data":{"field":"cwd"}}}"""

		val ok = protocolJson.decodeFromString(JsonRpcResponse.serializer(), okJson)
		val error = protocolJson.decodeFromString(JsonRpcResponse.serializer(), errorJson)

		assertEquals(7, ok.id)
		assertEquals("thread-1", ok.result?.jsonObject?.get("threadId")?.jsonPrimitive?.content)
		assertNull(ok.error)
		assertEquals(8, error.id)
		assertEquals(-32602, error.error?.code)
		assertIs<JsonObject>(error.error?.data)
	}
}
