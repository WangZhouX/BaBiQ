package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * observability 协议模型解码测试。
 *
 * <p>这类测试相当于前后端协议样例：后端只要保持同名字段，桌面端就能稳定解析统计快照。</p>
 */
class ObservabilityModelsTest {

	@Test
	fun `snapshot result 可以解析 totals model tool 和 status`() {
		val result = protocolJson.decodeFromString(
			ObservabilitySnapshotResult.serializer(),
			"""
			{
			  "range": "7d",
			  "totals": {
			    "turns": 2,
			    "failedTurns": 1,
			    "promptTokens": 120,
			    "completionTokens": 80,
			    "totalTokens": 200
			  },
			  "byProvider": [],
			  "byModel": [
			    {
			      "providerId": "deepseek",
			      "model": "deepseek-v4-pro",
			      "turns": 2,
			      "failedTurns": 1,
			      "promptTokens": 120,
			      "completionTokens": 80,
			      "totalTokens": 200
			    }
			  ],
			  "byTool": [
			    {
			      "toolName": "read_file",
			      "calls": 2,
			      "failures": 0,
			      "avgDurationMs": 300
			    }
			  ],
			  "byStatus": [
			    {
			      "status": "COMPLETED",
			      "turns": 1
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals("7d", result.range)
		assertEquals(2L, result.totals.turns)
		assertEquals(200L, result.totals.totalTokens)
		assertEquals("deepseek-v4-pro", result.byModel.single().model)
		assertEquals(200L, result.byModel.single().totalTokens)
		assertEquals("read_file", result.byTool.single().toolName)
		assertEquals("COMPLETED", result.byStatus.single().status)
	}
}
