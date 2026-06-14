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

	@Test
	fun `node mode labels use orchestration permission language`() {
		assertEquals("只读", flowNodeModeLabel(FlowNodeMode.ReadOnlyTool))
		assertEquals("全工具", flowNodeModeLabel(FlowNodeMode.WorkspaceTool))
	}

	@Test
	fun `insert menu labels are localized for orchestration canvas`() {
		assertEquals("串行节点", flowInsertKindLabel(FlowInsertKind.Serial))
		assertEquals("并行节点", flowInsertKindLabel(FlowInsertKind.Parallel))
	}

	@Test
	fun `insert anchor stays compact and does not cover connector lines`() {
		assertTrue(FlowInsertButtonDiameterDp <= 18f)
	}

	@Test
	fun `canvas viewport fills frame and centers small graphs`() {
		val content = FlowSize(width = 96f, height = 140f)

		val viewport = flowCanvasViewportSize(content, minWidth = 280f, minHeight = 320f)
		val centerOffset = flowCanvasCenterOffset(viewport, content)

		assertEquals(FlowSize(width = 280f, height = 320f), viewport)
		assertEquals(FlowPoint(x = 92f, y = 90f), centerOffset)
	}

	@Test
	fun `canvas viewport keeps larger graph size`() {
		val content = FlowSize(width = 360f, height = 400f)

		val viewport = flowCanvasViewportSize(content, minWidth = 280f, minHeight = 320f)

		assertEquals(content, viewport)
	}

	@Test
	fun `screen position converts back to layout position with camera and centered viewport`() {
		val camera = FlowCanvasCamera(scale = 1.5f, offsetX = 18f, offsetY = -12f)
		val centerOffset = FlowPoint(x = 20f, y = 30f)

		val point = flowCanvasScreenToLayoutPoint(
			screenX = 18f + 20f * 2f + 90f * 1.5f * 2f,
			screenY = -12f + 30f * 2f + 48f * 1.5f * 2f,
			camera = camera,
			centerOffset = centerOffset,
			density = 2f,
		)

		assertEquals(90f, point.x, absoluteTolerance = 0.001f)
		assertEquals(48f, point.y, absoluteTolerance = 0.001f)
	}

	@Test
	fun `pan hit test only treats blank canvas as pannable`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan"))
		val layout = layoutFlowCanvas(graph)
		val node = layout.nodes.single().rect

		assertEquals(
			FlowCanvasHitTarget.Node,
			flowCanvasHitTarget(layout, FlowPoint(node.center.x, node.center.y)),
		)
		assertEquals(
			FlowCanvasHitTarget.Background,
			flowCanvasHitTarget(layout, FlowPoint(node.rightCenter.x + 80f, node.rightCenter.y + 80f)),
		)
	}
}
