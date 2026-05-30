package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.PlanUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanSectionTest {

	@Test
	fun `计划区模型展示三态图标和进行时文案`() {
		val model = buildPlanSectionModel(
			PlanUiState(
				current = ThreadItem.Plan(
					id = "it-plan",
					steps = listOf(
						ThreadItem.PlanStep(1, "阅读计划", "completed", null),
						ThreadItem.PlanStep(2, "实现工具", "in_progress", "正在实现工具"),
						ThreadItem.PlanStep(3, "运行测试", "pending", null),
					),
				),
			),
		)

		assertEquals("进度 1/3", model.title)
		assertEquals("●", model.rows[0].icon)
		assertEquals("◐", model.rows[1].icon)
		assertEquals("○", model.rows[2].icon)
		assertEquals("正在实现工具", model.rows[1].text)
		assertTrue(model.rows[1].active)
	}

	@Test
	fun `收起提醒胶囊只在未完成计划存在时显示`() {
		val running = PlanUiState(
			current = ThreadItem.Plan(
				id = "it-plan",
				steps = listOf(
					ThreadItem.PlanStep(1, "阅读计划", "completed", null),
					ThreadItem.PlanStep(2, "实现工具", "in_progress", "正在实现工具"),
				),
			),
			collapsed = true,
		)

		assertEquals("◐ 计划进行中 · 1/2 展开", buildPlanReminderPill(running))
		assertFalse(buildPlanSectionModel(PlanUiState()).visible)
	}
}
