package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.flowcanvas.FlowEntry
import com.wzx.babiq.desktop.flowcanvas.FlowGraph
import com.wzx.babiq.desktop.flowcanvas.FlowTopology
import com.wzx.babiq.desktop.protocol.FlowStructureDto
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FlowStructureAdapterTest {

	@Test
	fun `work unit graph filters old start and end pseudo nodes`() {
		val detail = workUnitDetailModel(
			WorkUnitInfo(
				workUnitId = "wu_1",
				threadId = "thr_1",
				kind = "orchestration",
				name = "html-test",
				status = "waiting_config",
				configJson = """
					{
					  "topology": "sequential",
					  "nodes": [
					    {"id": "start", "task": "goal"},
					    {"id": "writer", "task": "update index", "mode": "WORKSPACE_TOOL"},
					    {"id": "end", "task": "finish"}
					  ]
					}
				""".trimIndent(),
			),
			modelLabel = "deepseek-v4-pro",
		)

		val graph = flowGraphFromWorkUnitDetail(detail)
		val configJson = buildFlowConfigJson(detail, graph)

		assertEquals(listOf("writer"), graph.flattenNodeIds())
		assertFalse(configJson.contains("\"id\":\"start\""))
		assertFalse(configJson.contains("\"id\":\"end\""))
	}

	@Test
	fun `work unit graph restores explicit nested structure json`() {
		val detail = workUnitDetailModel(
			WorkUnitInfo(
				workUnitId = "wu_1",
				threadId = "thr_1",
				kind = "orchestration",
				name = "html-test",
				status = "waiting_config",
				configJson = """
					{
					  "nodes": [
					    {"id": "explorer", "task": "read"},
					    {"id": "tester", "task": "test"},
					    {"id": "reviewer", "task": "review"}
					  ]
					}
				""".trimIndent(),
				structureJson = """
					{
					  "root": {
					    "groupId": "g_root",
					    "topology": "SEQUENTIAL",
					    "children": [
					      {"nodeId": "explorer"},
					      {
					        "groupId": "g_verify",
					        "topology": "PARALLEL",
					        "children": [
					          {"nodeId": "tester"},
					          {"nodeId": "reviewer"}
					        ]
					      }
					    ]
					  }
					}
				""".trimIndent(),
			),
			modelLabel = "deepseek-v4-pro",
		)

		val graph = flowGraphFromWorkUnitDetail(detail)
		val nested = assertIs<FlowEntry.Group>(graph.root.children[1])

		assertEquals(FlowTopology.Parallel, nested.topology)
		assertEquals(listOf("explorer", "tester", "reviewer"), graph.flattenNodeIds())
	}

	@Test
	fun `orchestration runtime item uses structure json for playback graph`() {
		val item = ThreadItem.Orchestration(
			id = "it_orch",
			orchestrationId = "orch_1",
			title = "runtime",
			topology = "sequential",
			status = "running",
			structureJson = """
				{
				  "root": {
				    "groupId": "g_root",
				    "topology": "PARALLEL",
				    "children": [
				      {"nodeId": "explorer"},
				      {"nodeId": "reviewer"}
				    ]
				  }
				}
			""".trimIndent(),
			nodes = listOf(
				ThreadItem.OrchestrationNode("explorer", "explorer", status = "completed", mode = "READ_ONLY_TOOL"),
				ThreadItem.OrchestrationNode("reviewer", "reviewer", status = "running", mode = "READ_ONLY_TOOL"),
			),
		)

		val graph = flowGraphFromOrchestrationItem(item)

		assertEquals(FlowTopology.Parallel, graph.root.topology)
		assertEquals("reviewer", graph.selectedNodeId)
	}

	@Test
	fun `graph can encode structure json for backend persistence`() {
		val detail = workUnitDetailModel(
			WorkUnitInfo(
				workUnitId = "wu_1",
				threadId = "thr_1",
				kind = "orchestration",
				name = "seeded",
				status = "waiting_config",
				currentGoalId = "goal_1",
				goals = listOf(
					WorkUnitGoalInfo(
						goalId = "goal_1",
						workUnitId = "wu_1",
						goalText = "1. explorer node read\n2. writer node write",
						status = "pending",
					),
				),
			),
			modelLabel = "deepseek-v4-pro",
		)

		val graph = flowGraphFromWorkUnitDetail(detail)
		val json = buildFlowStructureJson(graph)
		val structure = protocolJson.decodeFromString<FlowStructureDto>(json)

		assertEquals(listOf("explorer", "writer"), graph.flattenNodeIds())
		assertNotNull(structure.root.children.first().nodeId)
		assertFalse(json.contains("\"children\":[]"))
	}

	@Test
	fun `new draft flow node uses localized default labels`() {
		val node = newFlowNodeForGraph(FlowGraph(), inheritedModelLabel = "deepseek-v4-pro")

		assertEquals("自定义节点", node.role)
		assertEquals("补充这个节点的任务", node.task)
		assertEquals("继承主 Agent / deepseek-v4-pro", node.modelLabel)
	}
}
