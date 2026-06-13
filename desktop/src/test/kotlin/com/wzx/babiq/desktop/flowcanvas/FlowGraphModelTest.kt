package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlowGraphModelTest {

	@Test
	fun `empty graph has no editable start or end nodes`() {
		val graph = FlowGraph()

		assertEquals(emptyList(), graph.nodes)
		assertEquals(emptyList(), graph.flattenNodeIds())
	}

	@Test
	fun `serial insert appends real node between fixed terminals`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan files"))
			.insertSerial("explorer", FlowNode("writer", "writer", "write", "update file"))

		assertEquals(listOf("explorer", "writer"), graph.flattenNodeIds())
		assertEquals("writer", graph.selectedNodeId)
	}

	@Test
	fun `parallel insert wraps anchor in a one level group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan files"))
			.insertParallel("explorer", FlowNode("reviewer", "reviewer", "review", "check result"))

		val group = graph.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("explorer", "reviewer"), graph.flattenNodeIds())
	}

	@Test
	fun `routing insert wraps anchor in a routing group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("router", "router", "route", "choose branch"))
			.insertRouting("router", FlowNode("writer", "writer", "write", "handle selected branch"))

		val group = graph.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Routing, group.topology)
		assertEquals(listOf("router", "writer"), graph.flattenNodeIds())
	}

	@Test
	fun `remove node collapses single child parallel group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan files"))
			.insertParallel("explorer", FlowNode("reviewer", "reviewer", "review", "check result"))
			.removeNode("reviewer")

		assertEquals(listOf(FlowEntry.NodeRef("explorer")), graph.root.children)
		assertEquals(listOf("explorer"), graph.flattenNodeIds())
	}

	@Test
	fun `graph rejects nodes outside structure`() {
		assertFailsWith<IllegalArgumentException> {
			FlowGraph(nodes = listOf(FlowNode("orphan", "orphan", "custom", "unused")))
		}
	}

	@Test
	fun `history supports bounded undo and redo`() {
		val start = FlowGraph()
		val next = start.insertSerial(null, FlowNode("node_1", "node_1", "custom", "task"))
		val history = FlowGraphHistory(start).apply(next)

		assertEquals(listOf("node_1"), history.current.flattenNodeIds())
		assertEquals(emptyList(), history.undo().current.flattenNodeIds())
		assertEquals(listOf("node_1"), history.undo().redo().current.flattenNodeIds())
	}

	@Test
	fun `moveEntry reorders node in root sequence`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan files"))
			.insertSerial("scan", FlowNode("write", "write", "write", "update file"))
			.insertSerial("write", FlowNode("review", "review", "review", "check result"))

		val moved = graph.moveEntry("scan", FlowDropTarget.AfterNode("review"))

		assertEquals(listOf("write", "review", "scan"), moved.flattenNodeIds())
		assertEquals("scan", moved.selectedNodeId)
	}

	@Test
	fun `moveEntry can move root node into existing parallel group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan files"))
			.insertSerial("scan", FlowNode("review", "review", "review", "check result"))
			.insertParallel("scan", FlowNode("write", "write", "write", "update file"))

		val moved = graph.moveEntry("review", FlowDropTarget.IntoGroup("g_scan"))
		val group = moved.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("scan", "write", "review"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
	}

	@Test
	fun `moveEntry can move grouped node back to parent sequence`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan files"))
			.insertSerial("scan", FlowNode("review", "review", "review", "check result"))
			.insertParallel("scan", FlowNode("write", "write", "write", "update file"))

		val moved = graph.moveEntry("write", FlowDropTarget.AfterNode("review"))

		assertEquals(listOf("scan", "review", "write"), moved.flattenNodeIds())
		assertEquals(
			listOf(FlowEntry.NodeRef("scan"), FlowEntry.NodeRef("review"), FlowEntry.NodeRef("write")),
			moved.root.children,
		)
	}
}
