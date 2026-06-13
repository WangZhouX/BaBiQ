package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals

class FlowNodeCardTest {

	@Test
	fun `failed node style uses failed border and exposes error summary`() {
		val palette = FlowCanvasPalette()
		val node = FlowNode(
			id = "writer",
			title = "writer",
			role = "writer",
			task = "write file",
			status = FlowNodeStatus.Failed,
			errorSummary = "write failed",
		)

		val style = flowNodeCardStyle(node, selected = false, palette)

		assertEquals(palette.failed, style.borderColor)
		assertEquals("ERR", style.statusText)
		assertEquals("write failed", style.errorSummary)
	}
}
