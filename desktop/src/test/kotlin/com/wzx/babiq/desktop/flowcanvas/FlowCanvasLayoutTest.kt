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
	fun `start line insert target prepends before first node`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan"))

		val layout = layoutFlowCanvas(graph)

		assertEquals(FlowInsertTarget.Prepend, layout.insertPoints.single { it.id == "insert_start" }.target)
	}

	@Test
	fun `empty start line insert target appends first real node`() {
		val layout = layoutFlowCanvas(FlowGraph())

		assertEquals(FlowInsertTarget.Append, layout.insertPoints.single { it.id == "insert_start" }.target)
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

	@Test
	fun `layout separates branch insert targets from next step targets`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan"))
			.insertParallel("scan", FlowNode("write", "write", "write", "write"))
			.insertSerial(FlowInsertTarget.AfterGroup("g_scan"), FlowNode("review", "review", "review", "review"))

		val layout = layoutFlowCanvas(graph)
		val branchTargets = layout.insertPoints
			.filter { it.id.contains("fanout") }
			.map { it.target }
			.toSet()
		val nextStepTarget = layout.insertPoints.single { it.id.startsWith("insert_1_") }.target

		assertEquals(setOf(FlowInsertTarget.IntoGroup("g_scan", FlowTopology.Parallel)), branchTargets)
		assertEquals(FlowInsertTarget.AfterGroup("g_scan"), nextStepTarget)
	}
}
