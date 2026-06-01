package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.OrchestrationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchestrationSectionTest {

	@Test
	fun `编排区模型展示拓扑 节点状态和审批冻结状态`() {
		val item = ThreadItem.Orchestration(
			id = "it_orch_1",
			orchestrationId = "orch_1",
			title = "并行检查登录页",
			topology = "parallel",
			status = "running",
			summary = "流程已审批并开始执行",
			approved = true,
			frozen = true,
			nodes = listOf(
				ThreadItem.OrchestrationNode(
					nodeId = "node_scan",
					name = "scan",
					displayName = "扫描",
					status = "completed",
					mode = "READ_ONLY_TOOL",
					task = "读取文件",
					toolCallCount = 2,
				),
				ThreadItem.OrchestrationNode(
					nodeId = "node_write",
					name = "write",
					displayName = "修改",
					status = "running",
					mode = "WORKSPACE_TOOL",
					task = "写入文件",
					model = "deepseek-v4-pro",
				),
			),
		)

		val model = buildOrchestrationSectionModel(OrchestrationUiState(current = item))

		assertTrue(model.visible)
		assertEquals("流程编排 · 并行检查登录页", model.title)
		assertEquals("并行 / 运行中 / 已审批并冻结", model.subtitle)
		assertEquals(2, model.nodes.size)
		assertEquals("●", model.nodes.first().icon)
		assertEquals("◐", model.nodes.last().icon)
		assertEquals("修改", model.nodes.last().title)
		assertEquals("工作区工具 · deepseek-v4-pro", model.nodes.last().meta)
	}

	@Test
	fun `没有编排 item 时隐藏编排区`() {
		assertFalse(buildOrchestrationSectionModel(OrchestrationUiState()).visible)
	}
}
