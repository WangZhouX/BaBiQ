package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RunRecordModelsTest {

	@Test
	fun `运行记录 DTO 支持缺省和可空字段`() {
		val json = """
			{
			  "turns": [
			    {
			      "turnId": "turn-1",
			      "threadId": "thr-1",
			      "status": "INTERRUPTED",
			      "inputText": "分析项目",
			      "cwd": "E:\\BaBiQ",
			      "startedAt": "2026-05-24T08:00:00Z",
			      "recoveryReason": "server_restart"
			    }
			  ]
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(RunTurnListResult.serializer(), json)

		assertEquals("turn-1", result.turns.single().turnId)
		assertEquals("server_restart", result.turns.single().recoveryReason)
		assertNull(result.nextCursor)
	}

	@Test
	fun `运行详情复用 ThreadItem 解析历史 item 和 summary`() {
		val json = """
			{
			  "turn": {
			    "turnId": "turn-1",
			    "threadId": "thr-1",
			    "status": "COMPLETED",
			    "inputText": "你好",
			    "cwd": "E:\\BaBiQ",
			    "startedAt": "2026-05-24T08:00:00Z"
			  },
			  "items": [
			    { "id": "it-user", "type": "userMessage", "text": "你好" }
			  ],
			  "summary": {
			    "id": "sum-1",
			    "type": "turnSummary",
			    "status": "COMPLETED",
			    "model": "deepseek-v4-pro",
			    "promptTokens": 1,
			    "completionTokens": 2,
			    "totalTokens": 3,
			    "toolCalls": 0,
			    "durationMs": 900
			  },
			  "approvals": [],
			  "toolCalls": []
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(RunTurnDetailResult.serializer(), json)

		assertIs<ThreadItem.UserMessage>(result.items.single())
		assertEquals("deepseek-v4-pro", result.summary?.model)
	}
}
