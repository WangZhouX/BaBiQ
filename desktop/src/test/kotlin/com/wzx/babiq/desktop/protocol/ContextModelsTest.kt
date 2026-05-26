package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextModelsTest {

	@Test
	fun `上下文状态 DTO 支持 thread 级窗口摘要`() {
		val json = """
			{
			  "threadId": "thr_1",
			  "windowOrdinal": 0,
			  "modelContextWindow": 32768,
			  "autoCompactThreshold": 22937,
			  "lastSnapshotId": "ctxsnap_1",
			  "lastEstimatedTokens": 1200,
			  "lastActualPromptTokens": 1300,
			  "usageRatio": 0.039,
			  "status": "ok"
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(ContextStatusResult.serializer(), json)

		assertEquals("thr_1", result.threadId)
		assertEquals("ctxsnap_1", result.lastSnapshotId)
		assertEquals(1300L, result.lastActualPromptTokens)
		assertEquals("ok", result.status)
	}

	@Test
	fun `上下文快照 DTO 支持纳入和排除条目`() {
		val json = """
			{
			  "snapshotId": "ctxsnap_1",
			  "threadId": "thr_1",
			  "turnId": "turn_1",
			  "phase": "pre_model_call",
			  "providerId": "deepseek",
			  "model": "deepseek-v4-pro",
			  "cwd": "E:\\BaBiQ",
			  "windowOrdinal": 0,
			  "modelContextWindow": 32768,
			  "autoCompactThreshold": 22937,
			  "estimatedTokens": 1200,
			  "actualPromptTokens": 1300,
			  "includedItemCount": 1,
			  "excludedItemCount": 1,
			  "usageRatio": 0.039,
			  "inputPreview": "分析项目",
			  "createdAt": "2026-05-26T08:00:00Z",
			  "items": [
			    {
			      "sourceId": "item_user",
			      "sourceType": "history_item",
			      "priority": "HISTORY",
			      "included": true,
			      "reason": "最近历史",
			      "tokenEstimate": 100
			    },
			    {
			      "sourceId": "item_old",
			      "sourceType": "history_item",
			      "priority": "HISTORY",
			      "included": false,
			      "reason": "超过预算",
			      "tokenEstimate": 900
			    }
			  ]
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(ContextSnapshotInfo.serializer(), json)

		assertEquals("ctxsnap_1", result.snapshotId)
		assertEquals(2, result.items.size)
		assertTrue(result.items.first().included)
		assertFalse(result.items.last().included)
	}
}
