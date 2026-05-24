package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.ui.runtime.formatDuration
import com.wzx.babiq.desktop.ui.runtime.toSummaryMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnSummaryFormattingTest {

	@Test
	fun `duration formats milliseconds to seconds`() {
		assertEquals("1.5 秒", formatDuration(1500))
		assertEquals("8.2 秒", formatDuration(8200))
	}

	@Test
	fun `summary maps backend protocol fields to ui labels`() {
		val summary = ThreadItem.TurnSummary(
			id = "summary-1",
			status = "completed",
			model = "qwen-plus",
			promptTokens = 1824,
			completionTokens = 386,
			totalTokens = 2210,
			toolCalls = 5,
			estimatedCostUsd = 0.0021,
			durationMs = 8200,
		)

		val metrics = summary.toSummaryMetrics()

		assertEquals("输入 1,824", metrics[0].label)
		assertEquals("输出 386", metrics[1].label)
		assertEquals("总计 2,210", metrics[2].label)
		assertEquals("total tokens", metrics[2].helper)
		assertEquals("8.2 秒", metrics[3].label)
		assertEquals("5 工具", metrics[4].label)
	}
}
