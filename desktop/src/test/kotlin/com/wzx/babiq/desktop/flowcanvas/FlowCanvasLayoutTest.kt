package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowCanvasLayoutTest {

	@Test
	fun `sequential layout exposes arrows and line insert points`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan"))
			.insertSerial("explorer", FlowNode("writer", "writer", "write", "edit"))

		val layout = layoutFlowCanvas(graph)

		assertEquals(2, layout.nodes.size)
		assertTrue(layout.edges.any { it.id.startsWith("edge_1") && it.hasArrow })
		assertTrue(layout.insertPoints.any { it.anchorNodeId == "explorer" })
		assertTrue(layout.size.height > layout.start.rect.height + layout.end.rect.height)
	}

	@Test
	fun `parallel layout keeps branch connectors arrowless`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan"))
			.insertParallel("explorer", FlowNode("reviewer", "reviewer", "review", "check"))

		val layout = layoutFlowCanvas(graph)
		val branchEdges = layout.edges.filter { it.id.contains("fanout") || it.id.contains("join") }

		assertEquals(2, layout.nodes.size)
		assertTrue(branchEdges.isNotEmpty())
		assertTrue(branchEdges.none { it.hasArrow })
		assertFalse(layout.nodes[0].rect.x == layout.nodes[1].rect.x)
	}
}
