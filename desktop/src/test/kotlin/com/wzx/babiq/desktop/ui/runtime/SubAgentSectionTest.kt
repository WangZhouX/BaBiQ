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
	fun `subagent section keeps long markdown summary as compact preview only`() {
		val longSummary = """
			## 目录结构：`H:\aaa`

			```
			H:\aaa\
			└── index.html（唯一的 HTML 文件）
			```

			---

			## `index.html` 完整内容

			这是一个登录页面，包含用户名输入框、密码输入框、按钮和很多视觉说明。
			后续还有非常长的 Markdown 表格和代码块。
		""".trimIndent()
		val item = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "running",
			mode = "READ_ONLY_TOOL",
			summary = longSummary,
			toolCallCount = 3,
			tokenEstimate = 0,
		)

		val model = buildSubAgentSectionModel(SubAgentUiState(current = item))

		assertTrue(model.visible)
		assertFalse(model.rows.any { it.label == "摘要" })
		assertTrue(model.summaryPreview.orEmpty().contains("目录结构"))
		assertFalse(model.summaryPreview.orEmpty().contains("完整内容"))
		assertTrue(model.summaryPreview.orEmpty().length <= 120)
	}

	@Test
	fun `subagent section keeps completed delegation until user dismisses it`() {
		val item = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "completed",
			mode = "READ_ONLY_TOOL",
			summary = "已经查看完目录",
			toolCallCount = 3,
			tokenEstimate = 0,
		)

		val model = buildSubAgentSectionModel(SubAgentUiState(current = item))

		assertTrue(model.visible)
		assertEquals("babiq_agent -> explorer / 已完成", model.subtitle)
		assertEquals("已经查看完目录", model.summaryPreview)
	}

	@Test
	fun `subagent section hides dismissed delegation item`() {
		val item = ThreadItem.AgentDelegation(
			id = "it-agent-1",
			delegationId = "delegation-1",
			parentAgent = "babiq_agent",
			childAgent = "explorer",
			status = "completed",
			mode = "READ_ONLY_TOOL",
			summary = "已经查看完目录",
			toolCallCount = 3,
			tokenEstimate = 0,
		)

		val model = buildSubAgentSectionModel(
			SubAgentUiState(current = item, dismissedDelegationId = "delegation-1"),
		)

		assertFalse(model.visible)
	}

	@Test
	fun `subagent section hides without delegation item`() {
		assertFalse(buildSubAgentSectionModel(SubAgentUiState()).visible)
	}
}
