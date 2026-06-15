package com.wzx.babiq.desktop.flowcanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
	fun `serial insert from start edge prepends before first node`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("explorer", "explorer", "read", "scan files"))
			.insertSerial("explorer", FlowNode("writer", "writer", "write", "update file"))
			.insertSerial(FlowInsertTarget.Prepend, FlowNode("setup", "setup", "custom", "prepare"))

		assertEquals(listOf("setup", "explorer", "writer"), graph.flattenNodeIds())
		assertEquals(
			listOf(
				FlowEntry.NodeRef("setup"),
				FlowEntry.NodeRef("explorer"),
				FlowEntry.NodeRef("writer"),
			),
			graph.root.children,
		)
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
	fun `serial insert after a routing group appends to parent sequence`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("router", "router", "route", "choose branch"))
			.insertRouting("router", FlowNode("writer", "writer", "write", "handle selected branch"))
			.insertSerial(FlowInsertTarget.AfterGroup("g_router"), FlowNode("review", "review", "review", "review result"))

		assertEquals(
			listOf(
				FlowEntry.Group(
					groupId = "g_router",
					topology = FlowTopology.Routing,
					children = listOf(FlowEntry.NodeRef("router"), FlowEntry.NodeRef("writer")),
				),
				FlowEntry.NodeRef("review"),
			),
			graph.root.children,
		)
		assertEquals(listOf("router", "writer", "review"), graph.flattenNodeIds())
	}

	@Test
	fun `parallel branch insert is explicit and does not depend on last node`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan"))
			.insertParallel("scan", FlowNode("write", "write", "write", "write"))
			.insertParallel(
				FlowInsertTarget.IntoGroup("g_scan", FlowTopology.Parallel),
				FlowNode("review", "review", "review", "review"),
			)
		val group = graph.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("scan", "write", "review"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
	}

	@Test
	fun `existing serial node can become parallel with previous node`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("edit", "edit", "write", "edit html"))
			.insertSerial("edit", FlowNode("create", "create", "write", "create html"))

		assertFalse(graph.canParallelizeWithPrevious("edit"))
		assertTrue(graph.canParallelizeWithPrevious("create"))
		val parallel = graph.parallelizeWithPrevious("create")
		val group = parallel.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("edit", "create"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
		assertEquals("create", parallel.selectedNodeId)
	}

	@Test
	fun `existing serial node can become parallel with next node`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("edit", "edit", "write", "edit html"))
			.insertSerial("edit", FlowNode("create", "create", "write", "create html"))

		assertTrue(graph.canParallelizeWithNext("edit"))
		assertFalse(graph.canParallelizeWithNext("create"))
		val parallel = graph.parallelizeWithNext("edit")
		val group = parallel.root.children.single() as FlowEntry.Group

		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("edit", "create"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
		assertEquals("edit", parallel.selectedNodeId)
	}

	@Test
	fun `existing serial node can join previous parallel group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("edit", "edit", "write", "edit html"))
			.insertParallel("edit", FlowNode("create", "create", "write", "create html"))
			.insertSerial(FlowInsertTarget.AfterGroup("g_edit"), FlowNode("review", "review", "review", "review"))

		assertTrue(graph.canParallelizeWithPrevious("review"))
		val parallel = graph.parallelizeWithPrevious("review")
		val group = parallel.root.children.single() as FlowEntry.Group

		assertEquals("g_edit", group.groupId)
		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("edit", "create", "review"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
		assertEquals("review", parallel.selectedNodeId)
	}

	@Test
	fun `existing serial node can join next parallel group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan"))
			.insertSerial("scan", FlowNode("edit", "edit", "write", "edit html"))
			.insertParallel("edit", FlowNode("create", "create", "write", "create html"))

		assertTrue(graph.canParallelizeWithNext("scan"))
		val parallel = graph.parallelizeWithNext("scan")
		val group = parallel.root.children.single() as FlowEntry.Group

		assertEquals("g_edit", group.groupId)
		assertEquals(FlowTopology.Parallel, group.topology)
		assertEquals(listOf("scan", "edit", "create"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
		assertEquals("scan", parallel.selectedNodeId)
	}

	@Test
	fun `parallel insert after an existing group does not mutate that group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan"))
			.insertParallel("scan", FlowNode("write", "write", "write", "write"))
			.insertParallel(FlowInsertTarget.AfterGroup("g_scan"), FlowNode("review", "review", "review", "review"))

		val group = graph.root.children.first() as FlowEntry.Group
		assertEquals(listOf("scan", "write"), group.children.map { (it as FlowEntry.NodeRef).nodeId })
		assertEquals(FlowEntry.NodeRef("review"), graph.root.children.last())
		assertEquals(listOf("scan", "write", "review"), graph.flattenNodeIds())
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

	@Test
	fun `moveEntry after a grouped node moves after the parent group`() {
		val graph = FlowGraph()
			.insertSerial(null, FlowNode("scan", "scan", "read", "scan files"))
			.insertParallel("scan", FlowNode("write", "write", "write", "update file"))
			.insertSerial(FlowInsertTarget.AfterGroup("g_scan"), FlowNode("review", "review", "review", "check result"))

		val moved = graph.moveEntry("review", FlowDropTarget.AfterNode("write"))

		assertEquals(
			listOf(
				FlowEntry.Group(
					groupId = "g_scan",
					topology = FlowTopology.Parallel,
					children = listOf(FlowEntry.NodeRef("scan"), FlowEntry.NodeRef("write")),
				),
				FlowEntry.NodeRef("review"),
			),
			moved.root.children,
		)
		assertEquals(listOf("scan", "write", "review"), moved.flattenNodeIds())
	}
}
