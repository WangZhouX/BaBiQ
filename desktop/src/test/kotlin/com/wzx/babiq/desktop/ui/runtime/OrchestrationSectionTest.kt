package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ModelInfo
import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.state.ProviderSelection
import com.wzx.babiq.desktop.state.ProviderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestrationSectionTest {

	@Test
	fun `orchestration section model summarizes topology nodes and frozen approval state`() {
		val item = ThreadItem.Orchestration(
			id = "it_orch_1",
			orchestrationId = "orch_1",
			title = "parallel login page check",
			topology = "parallel",
			status = "running",
			summary = "flow approved and started",
			approved = true,
			frozen = true,
			nodes = listOf(
				ThreadItem.OrchestrationNode(
					nodeId = "node_scan",
					name = "scan",
					displayName = "scan",
					status = "completed",
					mode = "READ_ONLY_TOOL",
					task = "read files",
					toolCallCount = 2,
				),
				ThreadItem.OrchestrationNode(
					nodeId = "node_write",
					name = "write",
					displayName = "write",
					status = "running",
					mode = "WORKSPACE_TOOL",
					task = "write file",
					model = "deepseek-v4-pro",
				),
			),
		)

		val model = buildOrchestrationSectionModel(OrchestrationUiState(current = item), modelLabel = "deepseek-v4-pro")

		assertTrue(model.visible)
		assertTrue(model.title.contains("parallel login page check"))
		assertEquals(2, model.nodes.size)
		assertEquals("write", model.nodes.last().title)
		assertTrue(model.nodes.last().active)
		assertEquals(null, model.config)
		assertEquals(null, model.removeActionLabel)
	}

	@Test
	fun `completed orchestration runtime model exposes dismiss action`() {
		val item = ThreadItem.Orchestration(
			id = "it_orch_1",
			orchestrationId = "orch_1",
			title = "failed smoke flow",
			topology = "sequential",
			status = "failed",
			summary = "checkpoint failed",
			nodes = emptyList(),
		)

		val model = buildOrchestrationSectionModel(OrchestrationUiState(current = item))

		assertTrue(model.visible)
		assertNotNull(model.removeActionLabel)
	}

	@Test
	fun `orchestration section model renders saved work unit configuration nodes only`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_flow",
			threadId = "thr_1",
			kind = "orchestration",
			name = "html-test",
			status = "waiting_config",
			currentGoalId = "goal_2",
			cwd = "H:\\aaa",
			sandboxMode = "FULL_ACCESS",
			goals = listOf(
				WorkUnitGoalInfo("goal_1", "wu_flow", "inspect page", "completed", summary = "done"),
				WorkUnitGoalInfo("goal_2", "wu_flow", "edit html content", "pending"),
			),
			configJson = """
				{
				  "nodes": [
				    {"id": "start", "task": "edit html content", "model": "goal:current"},
				    {"id": "analyzer", "task": "analyze login page structure", "model": "provider:qwen:qwen-plus"}
				  ]
				}
			""".trimIndent(),
		)

		val model = buildOrchestrationSectionModel(
			OrchestrationUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertTrue(model.visible)
		assertEquals("wu_flow", model.config?.workUnitId)
		assertEquals("H:\\aaa", model.config?.cwd)
		assertEquals("goal_2", model.config?.editableGoalId)
		assertEquals("edit html content", model.config?.editableGoalText)
		assertEquals(2, model.config?.goals?.size)
		assertEquals("sequential", model.configTopology)
		assertEquals(listOf("start", "analyzer", "end"), model.configNodes.map { it.nodeId })
		assertEquals("START", model.configNodes.first().title)
		assertEquals("END", model.configNodes.last().title)
		val settings = assertNotNull(model.selectedNodeSettings)
		assertEquals("analyzer", settings.nodeId)
		assertEquals("analyze login page structure", settings.task)
		assertEquals("provider:qwen:qwen-plus", settings.modelValue)
		assertEquals("provider:qwen:qwen-plus", model.configNodes.first { it.nodeId == "analyzer" }.modelValue)
		assertEquals("analyze login page structure", model.configNodes.first { it.nodeId == "analyzer" }.task)
		assertEquals("end:main-agent-confirmed", model.configNodes.last().modelValue)
		assertNotNull(model.removeActionLabel)
	}

	@Test
	fun `orchestration section model preserves parallel topology from saved configuration`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_flow",
			threadId = "thr_1",
			kind = "orchestration",
			name = "parallel-flow",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "FULL_ACCESS",
			goals = listOf(
				WorkUnitGoalInfo("goal_1", "wu_flow", "check page in parallel", "pending"),
			),
			configJson = """
				{
				  "topology": "parallel",
				  "nodes": [
				    {"id": "start", "task": "check page in parallel", "model": "goal:current"},
				    {"id": "scan", "task": "read page", "model": "inherit"},
				    {"id": "review", "task": "review result", "model": "inherit"}
				  ]
				}
			""".trimIndent(),
		)

		val model = buildOrchestrationSectionModel(
			OrchestrationUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertEquals("parallel", model.configTopology)
		assertEquals(listOf("serial node", "parallel node", "routing branch"), model.addNodeActions.map { it.label })
	}

	@Test
	fun `orchestration work unit without explicit nodes starts with terminals only`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_flow",
			threadId = "thr_1",
			kind = "orchestration",
			name = "empty-flow",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "FULL_ACCESS",
			goals = listOf(
				WorkUnitGoalInfo("goal_1", "wu_flow", "update the index page", "pending"),
			),
		)

		val model = buildOrchestrationSectionModel(
			OrchestrationUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertEquals(listOf("start", "end"), model.configNodes.map { it.nodeId })
	}

	@Test
	fun `orchestration work unit derives explicitly requested nodes from goal text`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_flow",
			threadId = "thr_1",
			kind = "orchestration",
			name = "p6-smoke-test",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "FULL_ACCESS",
			goals = listOf(
				WorkUnitGoalInfo(
					"goal_1",
					"wu_flow",
					"1. explorer \u8282\u70b9 reads current index.html\n" +
						"2. designer \u8282\u70b9 designs the small page enhancement\n" +
						"3. writer \u8282\u70b9 updates index.html\n" +
						"4. reviewer \u8282\u70b9 verifies the result",
					"pending",
				),
			),
		)

		val model = buildOrchestrationSectionModel(
			OrchestrationUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertEquals(
			listOf("start", "explorer", "designer", "writer", "reviewer", "end"),
			model.configNodes.map { it.nodeId },
		)
		assertEquals("updates index.html", model.configNodes.first { it.nodeId == "writer" }.task)
		assertEquals("WORKSPACE_TOOL", model.configNodes.first { it.nodeId == "writer" }.modeValue)
	}

	@Test
	fun `add orchestration draft serial node inserts before end node`() {
		val nodes = listOf(
			OrchestrationConfigNodeRow(
				nodeId = "start",
				title = "START",
				role = "entry",
				task = "goal",
				modelLabel = "goal",
				modelValue = "goal:current",
				modeLabel = "goal",
				selected = true,
				removable = false,
			),
			OrchestrationConfigNodeRow(
				nodeId = "end",
				title = "END",
				role = "exit",
				task = "finish",
				modelLabel = "main",
				modelValue = "end:main-agent-confirmed",
				modeLabel = "end",
				selected = false,
				removable = false,
			),
		)

		val updated = addOrchestrationDraftNode(nodes, inheritedModelLabel = "deepseek-v4-pro")

		assertEquals(listOf("start", "node_1", "end"), updated.map { it.nodeId })
		assertTrue(updated.first { it.nodeId == "node_1" }.removable)
	}

	@Test
	fun `add orchestration draft parallel node switches topology to parallel`() {
		val nodes = listOf(
			OrchestrationConfigNodeRow(
				nodeId = "start",
				title = "START",
				role = "entry",
				task = "goal",
				modelLabel = "goal",
				modelValue = "goal:current",
				modeLabel = "goal",
				selected = true,
				removable = false,
			),
			OrchestrationConfigNodeRow(
				nodeId = "scan",
				title = "scan",
				role = "reader",
				task = "read",
				modelLabel = "main",
				modelValue = "inherit",
				modeLabel = "read only",
				selected = false,
				removable = true,
			),
			OrchestrationConfigNodeRow(
				nodeId = "end",
				title = "END",
				role = "exit",
				task = "finish",
				modelLabel = "main",
				modelValue = "end:main-agent-confirmed",
				modeLabel = "end",
				selected = false,
				removable = false,
			),
		)

		val update = addOrchestrationDraftNodeWithTopology(
			nodes = nodes,
			inheritedModelLabel = "deepseek-v4-pro",
			currentTopology = "sequential",
			mode = OrchestrationDraftAddMode.Parallel,
		)

		assertEquals("parallel", update.topology)
		assertEquals(listOf("start", "scan", "node_1", "end"), update.nodes.map { it.nodeId })
	}

	@Test
	fun `orchestration node model options mirror composer provider models`() {
		val providerState = ProviderState(
			providers = listOf(
				ProviderInfo(
					id = "deepseek",
					label = "DeepSeek",
					models = listOf(
						ModelInfo(id = "deepseek-v4-pro", label = "deepseek-v4-pro", active = true),
						ModelInfo(id = "deepseek-v4-flash", label = "deepseek-v4-flash"),
					),
				),
				ProviderInfo(
					id = "qwen",
					label = "Qwen",
					models = listOf(ModelInfo(id = "qwen-plus", label = "qwen-plus")),
				),
			),
			active = ProviderSelection(providerId = "deepseek", modelId = "deepseek-v4-pro", label = "deepseek-v4-pro"),
		)

		val options = nodeModelOptions(providerState, inheritedModelLabel = "deepseek-v4-pro")

		assertEquals("inherit", options.first().modelValue)
		assertTrue(options.first().label.contains("deepseek-v4-pro"))
		assertTrue(options.any { it.providerId == "deepseek" && it.modelId == "deepseek-v4-flash" })
		assertTrue(options.any { it.providerId == "qwen" && it.modelId == "qwen-plus" })
	}

	@Test
	fun `orchestration section hides without runtime or configuration detail`() {
		assertFalse(buildOrchestrationSectionModel(OrchestrationUiState()).visible)
	}
}
