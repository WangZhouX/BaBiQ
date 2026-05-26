package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 会话历史协议模型测试。
 *
 * P2-2 要求历史 item 和实时 WebSocket item 使用同一套 ThreadItem 模型，避免 UI 恢复历史时出现两套解析逻辑。
 */
class ThreadHistoryModelsTest {

	@Test
	fun `thread list result 可以解析最近会话摘要`() {
		val payload = """
			{
			  "threads": [
			    {
			      "threadId": "thr_1",
			      "title": "分析 BaBiQ 项目结构",
			      "cwd": "E:\\BaBiQ",
			      "providerId": "deepseek",
			      "model": "deepseek-v4-pro",
			      "status": "active",
			      "lastTurnStatus": "COMPLETED",
			      "updatedAt": "2026-05-24T08:00:00Z",
			      "messageCount": 3
			    }
			  ],
			  "nextCursor": null
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(ThreadListResult.serializer(), payload)

		assertEquals("thr_1", result.threads.single().threadId)
		assertEquals("分析 BaBiQ 项目结构", result.threads.single().title)
		assertEquals(3, result.threads.single().messageCount)
	}

	@Test
	fun `thread load result 复用 ThreadItem 解析历史消息`() {
		val payload = """
			{
			  "thread": {"threadId":"thr_1","title":"新对话","cwd":"E:\\BaBiQ","status":"active"},
			  "items": [
			    {"id":"it_user","type":"userMessage","text":"你好"},
			    {"id":"it_agent","type":"agentMessage","text":"你好，有什么可以帮忙？"}
			  ],
			  "latestSummary": null,
			  "nextBeforeItemId": null
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(ThreadLoadResult.serializer(), payload)

		assertEquals("thr_1", result.thread.threadId)
		assertIs<ThreadItem.UserMessage>(result.items.first())
		assertIs<ThreadItem.AgentMessage>(result.items.last())
	}

	@Test
	fun `thread load result 可以解析上下文压缩事件`() {
		val payload = """
			{
			  "thread": {"threadId":"thr_1","title":"新对话","cwd":"E:\\BaBiQ","status":"active"},
			  "items": [
			    {
			      "id":"it_compact",
			      "type":"contextCompaction",
			      "compactionId":"ctxcmp_1",
			      "status":"SUCCESS",
			      "summaryId":"ctxsum_1",
			      "windowOrdinal":1,
			      "estimatedTokensBefore":90000,
			      "estimatedTokensAfter":1200,
			      "message":"上下文已自动压缩为短期摘要"
			    }
			  ],
			  "latestSummary": null,
			  "nextBeforeItemId": null
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(ThreadLoadResult.serializer(), payload)
		val item = assertIs<ThreadItem.ContextCompaction>(result.items.single())

		assertEquals("ctxcmp_1", item.compactionId)
		assertEquals("ctxsum_1", item.summaryId)
		assertEquals(1, item.windowOrdinal)
	}
}
