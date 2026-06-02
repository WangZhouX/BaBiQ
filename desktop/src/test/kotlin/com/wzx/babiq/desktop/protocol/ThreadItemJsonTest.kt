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

	@Test
	fun `can parse work unit item`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it_workunit_1",
			      "type": "workUnit",
			      "workUnitId": "wu_1",
			      "kind": "orchestration",
			      "name": "login-flow",
			      "status": "idle",
			      "currentGoalId": "wug_1",
			      "currentGoal": "split login page",
			      "goalCount": 2,
			      "removed": false
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.WorkUnit>(added.item)

		assertEquals("wu_1", item.workUnitId)
		assertEquals("orchestration", item.kind)
		assertEquals("login-flow", item.name)
		assertEquals("split login page", item.currentGoal)
		assertEquals(2, item.goalCount)
		assertEquals(false, item.removed)
	}

	@Test
	fun `可以解析 orchestration item 的拓扑和节点状态`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it_orch_1",
			      "type": "orchestration",
			      "orchestrationId": "orch_1",
			      "title": "并行检查登录页",
			      "topology": "parallel",
			      "status": "running",
			      "summary": "流程已审批并开始执行",
			      "approved": true,
			      "frozen": true,
			      "nodes": [
			        {
			          "nodeId": "node_scan",
			          "name": "scan",
			          "displayName": "扫描",
			          "status": "completed",
			          "mode": "READ_ONLY_TOOL",
			          "task": "读取文件",
			          "toolCallCount": 2,
			          "tokenEstimate": 300,
			          "summary": "已读取 index.html"
			        },
			        {
			          "nodeId": "node_write",
			          "name": "write",
			          "displayName": "修改",
			          "status": "running",
			          "mode": "WORKSPACE_TOOL",
			          "task": "写入文件",
			          "model": "deepseek-v4-pro"
			        }
			      ]
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.Orchestration>(added.item)

		assertEquals("orch_1", item.orchestrationId)
		assertEquals("parallel", item.topology)
		assertEquals(true, item.approved)
		assertEquals(2, item.nodes.size)
		assertEquals("WORKSPACE_TOOL", item.nodes.last().mode)
	}

	@Test
	fun `可以解析 team item 的成员状态`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it_team_1",
			      "type": "team",
			      "teamId": "team_1",
			      "title": "团队协作",
			      "status": "running",
			      "summary": "正在协调成员",
			      "approved": true,
			      "frozen": true,
			      "currentAgent": "explorer",
			      "round": 1,
			      "maxRounds": 5,
			      "members": [
			        {
			          "memberId": "member_explorer",
			          "name": "explorer",
			          "displayName": "探索成员",
			          "status": "running",
			          "mode": "READ_ONLY_TOOL",
			          "task": "读取目录",
			          "toolCallCount": 2,
			          "tokenEstimate": 128,
			          "summary": "正在读取"
			        }
			      ]
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.Team>(added.item)

		assertEquals("team_1", item.teamId)
		assertEquals("explorer", item.currentAgent)
		assertEquals(5, item.maxRounds)
		assertEquals("探索成员", item.members.single().displayName)
		assertEquals(2, item.members.single().toolCallCount)
	}

	@Test
	fun `可以解析 teamMessage item 的直发消息`() {
		val json = """
			{
			  "jsonrpc": "2.0",
			  "method": "item/added",
			  "params": {
			    "threadId": "thread-1",
			    "turnId": "turn-1",
			    "item": {
			      "id": "it_team_msg_1",
			      "type": "teamMessage",
			      "messageId": "msg_1",
			      "teamId": "team_1",
			      "fromAgent": "user",
			      "toAgent": "explorer",
			      "messageType": "direct_user",
			      "content": "请重点看 README",
			      "round": 2,
			      "createdAt": "2026-06-01T10:00:00Z"
			    }
			  }
			}
		""".trimIndent()

		val event = protocolJson.decodeFromString(ServerEvent.serializer(), json)
		val added = assertIs<ServerEvent.ItemAdded>(event)
		val item = assertIs<ThreadItem.TeamMessage>(added.item)

		assertEquals("msg_1", item.messageId)
		assertEquals("user", item.fromAgent)
		assertEquals("explorer", item.toAgent)
		assertEquals("direct_user", item.messageType)
		assertEquals("请重点看 README", item.content)
	}
}
