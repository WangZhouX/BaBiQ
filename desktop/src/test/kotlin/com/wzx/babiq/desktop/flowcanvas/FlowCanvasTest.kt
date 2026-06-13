package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlowCanvasTest {

	@Test
	fun `node status maps to D14 badge text and colors`() {
		val palette = FlowCanvasPalette()
		val styles = FlowNodeStatus.entries.associateWith { status ->
			flowNodeCardStyle(
				node = FlowNode("node_${status.name}", status.name, "role", "task", status = status),
				selected = false,
				palette = palette,
			)
		}

		assertEquals("", styles.getValue(FlowNodeStatus.Pending).statusText)
		assertEquals(palette.muted, styles.getValue(FlowNodeStatus.Pending).statusColor)
		assertEquals("RUN", styles.getValue(FlowNodeStatus.Running).statusText)
		assertEquals(palette.running, styles.getValue(FlowNodeStatus.Running).statusColor)
		assertEquals("OK", styles.getValue(FlowNodeStatus.Completed).statusText)
		assertEquals(palette.completed, styles.getValue(FlowNodeStatus.Completed).statusColor)
		assertEquals("ERR", styles.getValue(FlowNodeStatus.Failed).statusText)
		assertEquals(palette.failed, styles.getValue(FlowNodeStatus.Failed).statusColor)
		assertEquals(palette.failed, styles.getValue(FlowNodeStatus.Failed).borderColor)
	}

	@Test
	fun `status badge text is empty for pending and unique for visible states`() {
		val palette = FlowCanvasPalette()
		val texts = FlowNodeStatus.entries.map { status ->
			flowNodeCardStyle(
				node = FlowNode("node_${status.name}", status.name, "role", "task", status = status),
				selected = false,
				palette = palette,
			).statusText
		}
		val visibleTexts = texts.filter { it.isNotBlank() }

		assertEquals("", texts.first())
		assertTrue(visibleTexts.isNotEmpty())
		assertEquals(visibleTexts.size, visibleTexts.toSet().size)
	}

	@Test
	fun `node mode maps read only and workspace modes to role dot colors`() {
		val palette = FlowCanvasPalette()
		val readOnly = flowNodeCardStyle(
			node = FlowNode("read", "read", "reader", "scan", mode = FlowNodeMode.ReadOnlyTool),
			selected = false,
			palette = palette,
		)
		val workspace = flowNodeCardStyle(
			node = FlowNode("write", "write", "writer", "edit", mode = FlowNodeMode.WorkspaceTool),
			selected = false,
			palette = palette,
		)

		assertEquals(palette.readOnlyDot, readOnly.roleColor)
		assertEquals(palette.workspaceDot, workspace.roleColor)
		assertNotEquals(readOnly.roleColor, workspace.roleColor)
	}
}
