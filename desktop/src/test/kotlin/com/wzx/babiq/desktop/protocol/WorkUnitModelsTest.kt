package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkUnitModelsTest {

	@Test
	fun `work unit info decodes persisted orchestration node configuration`() {
		val info = WorkUnitInfo(
			workUnitId = "wu_flow",
			threadId = "thr_1",
			kind = "orchestration",
			name = "html-test",
			status = "waiting_config",
			configJson = """
				{
				  "nodes": [
				    {"id": "start", "task": "修改 html 内容", "model": "goal:current"},
				    {"id": "analyzer", "task": "分析模块依赖", "model": "provider:qwen:qwen-plus"}
				  ]
				}
			""".trimIndent(),
		)

		val config = assertNotNull(info.configuration)

		assertEquals("修改 html 内容", config.nodes.first { it.id == "start" }.task)
		assertEquals("provider:qwen:qwen-plus", config.nodes.first { it.id == "analyzer" }.model)
	}

	@Test
	fun `work unit info decodes persisted team member configuration`() {
		val info = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "验收团队",
			status = "waiting_config",
			configJson = """
				{
				  "members": [
				    {"id": "leader", "name": "leader", "task": "拆解目标", "model": "inherit"},
				    {"id": "frontend", "name": "frontend", "task": "实现 UI", "model": "provider:deepseek:deepseek-v4-pro"}
				  ]
				}
			""".trimIndent(),
		)

		val config = assertNotNull(info.configuration)

		assertEquals("实现 UI", config.members.first { it.id == "frontend" }.task)
		assertEquals("provider:deepseek:deepseek-v4-pro", config.members.first { it.id == "frontend" }.model)
	}
}
