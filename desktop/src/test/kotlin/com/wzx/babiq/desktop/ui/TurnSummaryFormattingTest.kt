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
	fun `summary main feedback shows total usage without prompt completion split`() {
		val summary = ThreadItem.TurnSummary(
			id = "summary-1",
			status = "completed",
			model = "qwen-plus",
			promptTokens = 1824,
			completionTokens = 386,
			totalTokens = 2210,
			toolCalls = 5,
			durationMs = 8200,
		)

		val metrics = summary.toSummaryMetrics()

		assertEquals(3, metrics.size)
		assertEquals("总用量 2,210", metrics[0].label)
		assertEquals("tokens", metrics[0].helper)
		assertEquals("8.2 秒", metrics[1].label)
		assertEquals("5 工具", metrics[2].label)
	}
}
