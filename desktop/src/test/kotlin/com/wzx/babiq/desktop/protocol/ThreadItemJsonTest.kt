package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ThreadItemJsonTest {

	@Test
	fun `未知 item type 不会让协议解析崩溃`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it-unknown",
			      "type": "futureItem",
			      "payload": { "value": 42 }
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.Unknown>(added.item)

		assertEquals("it-unknown", item.id)
		assertEquals("futureItem", item.type)
		assertEquals(42, item.raw["payload"]?.toString()?.contains("42")?.let { if (it) 42 else -1 })
	}

	@Test
	fun `可以解析 approval request 通知`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "approval/request",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "itemId": "approval-1",
			    "toolName": "exec_shell",
			    "arguments": "{\"command\":\"git status\"}",
			    "description": "需要执行 shell 命令"
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val approval = assertIs<ServerEvent.ApprovalRequested>(event)

		assertEquals("approval-1", approval.request.itemId)
		assertEquals("exec_shell", approval.request.toolName)
		assertEquals("""{"command":"git status"}""", approval.request.arguments)
	}

	@Test
	fun `可以解析 provider list 响应`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "id": 11,
			  "result": {
			    "providers": [
			      {
			        "id": "qwen",
			        "label": "Qwen",
			        "active": true,
			        "models": [
			          { "id": "qwen-plus", "label": "Qwen Plus", "active": true }
			        ]
			      }
			    ]
			  }
			}
		""".trimIndent()

		val response = protocolJson.decodeFromString(JsonRpcResponse.serializer(), json)
		val providerList = protocolJson.decodeFromJsonElement(ProviderListResult.serializer(), response.requireResult())

		assertEquals("qwen", providerList.providers.single().id)
		assertEquals("qwen-plus", providerList.providers.single().models.single().id)
	}

	@Test
	fun `可以解析 plan item 的步骤状态和进行时文案`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it-plan-1",
			      "type": "plan",
			      "reasoning": "复杂任务需要拆分",
			      "steps": [
			        { "order": 1, "description": "阅读计划", "status": "completed" },
			        { "order": 2, "description": "实现工具", "status": "in_progress", "activeForm": "正在实现工具" }
			      ]
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val plan = assertIs<ThreadItem.Plan>(added.item)

		assertEquals("it-plan-1", plan.id)
		assertEquals(null, plan.goal)
		assertEquals("completed", plan.steps.first().status)
		assertEquals("正在实现工具", plan.steps.last().activeForm)
	}
	@Test
	fun `can parse agent delegation item`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it-agent-1",
			      "type": "agentDelegation",
			      "delegationId": "delegation-1",
			      "parentAgent": "babiq_agent",
			      "childAgent": "explorer",
			      "status": "running",
			      "mode": "READ_ONLY_TOOL",
			      "summary": "正在只读查看目录",
			      "toolCallCount": 2,
			      "tokenEstimate": 321
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.AgentDelegation>(added.item)

		assertEquals("delegation-1", item.delegationId)
		assertEquals("explorer", item.childAgent)
		assertEquals("READ_ONLY_TOOL", item.mode)
		assertEquals(2, item.toolCallCount)
	}
}
