package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.SubAgentUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubAgentSectionTest {

	@Test
	fun `subagent section model summarizes current delegation`() {
		val item = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "running",
			mode = "READ_ONLY_TOOL",
			summary = "正在只读查看目录",
			toolCallCount = 2,
			tokenEstimate = 321,
		)

		val model = buildSubAgentSectionModel(SubAgentUiState(current = item))

		assertTrue(model.visible)
		assertEquals("子 Agent · explorer", model.title)
		assertEquals("babiq_agent -> explorer / 运行中", model.subtitle)
		assertEquals("READ_ONLY_TOOL", model.rows.single { it.label == "模式" }.value)
		assertEquals("2 次", model.rows.single { it.label == "只读工具" }.value)
		assertEquals("321", model.rows.single { it.label == "token 估算" }.value)
	}

	@Test
	fun `subagent section hides without delegation item`() {
		assertFalse(buildSubAgentSectionModel(SubAgentUiState()).visible)
	}
}
