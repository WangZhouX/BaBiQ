package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.ModelInfo
import com.wzx.babiq.desktop.protocol.ProviderInfo
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
		assertEquals("流程编排 · parallel login page check", model.title)
		assertEquals("并行 / 运行中 / 已审批并冻结", model.subtitle)
		assertEquals(2, model.nodes.size)
		assertEquals("write", model.nodes.last().title)
		assertEquals("工作区工具 · deepseek-v4-pro", model.nodes.last().meta)
		assertEquals(null, model.config)
	}

	@Test
	fun `orchestration section model renders work unit configuration detail`() {
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
				    {"id": "analyzer", "task": "分析现有登录页结构", "model": "provider:qwen:qwen-plus"}
				  ]
				}
			""".trimIndent(),
		)

		val model = buildOrchestrationSectionModel(
			OrchestrationUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertTrue(model.visible)
		assertEquals("编排详情 · html-test", model.title)
		assertEquals("待配置 / 2 个目标 / 等待手动启动", model.subtitle)
		assertEquals("wu_flow", model.config?.workUnitId)
		assertEquals("H:\\aaa", model.config?.cwd)
		assertEquals("完全访问权限", model.config?.sandboxLabel)
		assertEquals("deepseek-v4-pro", model.config?.modelLabel)
		assertEquals("goal_2", model.config?.editableGoalId)
		assertEquals("edit html content", model.config?.editableGoalText)
		assertEquals(2, model.config?.goals?.size)
		assertEquals(6, model.configNodes.size)
		assertEquals(listOf("start", "explorer", "analyzer", "tester", "router", "end"), model.configNodes.map { it.nodeId })
		assertEquals("START", model.configNodes.first().title)
		assertEquals("END", model.configNodes.last().title)
		assertEquals("编排 · 编辑模式", model.editModeTitle)
		val settings = assertNotNull(model.selectedNodeSettings)
		assertEquals("start", settings.nodeId)
		assertEquals("edit html content", settings.task)
		assertEquals("goal:current", settings.modelValue)
		assertEquals("provider:qwen:qwen-plus", model.configNodes.first { it.nodeId == "analyzer" }.modelValue)
		assertEquals("分析现有登录页结构", model.configNodes.first { it.nodeId == "analyzer" }.task)
		assertEquals("end:main-agent-confirmed", model.configNodes.last().modelValue)
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
		assertEquals("继承主 Agent · deepseek-v4-pro", options.first().label)
		assertTrue(options.any { it.providerId == "deepseek" && it.modelId == "deepseek-v4-flash" })
		assertTrue(options.any { it.providerId == "qwen" && it.modelId == "qwen-plus" })
	}

	@Test
	fun `orchestration section hides without runtime or configuration detail`() {
		assertFalse(buildOrchestrationSectionModel(OrchestrationUiState()).visible)
	}
}
