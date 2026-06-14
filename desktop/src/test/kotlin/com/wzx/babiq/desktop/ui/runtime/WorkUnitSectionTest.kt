package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.state.WorkUnitUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkUnitSectionTest {

	@Test
	fun `work unit section lists orchestration and team containers`() {
		val model = buildWorkUnitSectionModel(
			WorkUnitUiState(
				items = listOf(
					ThreadItem.WorkUnit(
						id = "it_workunit_1",
						workUnitId = "wu_1",
						kind = "orchestration",
						name = "login-flow",
						status = "idle",
						currentGoal = "split login page",
						goalCount = 2,
						removed = false,
					),
					ThreadItem.WorkUnit(
						id = "it_workunit_2",
						workUnitId = "wu_2",
						kind = "team",
						name = "review-team",
						status = "running",
						currentGoal = "review docs",
						goalCount = 1,
						removed = false,
					),
				),
			),
		)

		assertTrue(model.visible)
		assertEquals(2, model.rows.size)
		assertEquals("编排", model.rows.first().kindLabel)
		assertEquals("空闲中", model.rows.first().runtimeStateLabel)
		assertEquals("待配置", model.rows.first().statusLabel)
		assertTrue(model.rows.first().removable)
		assertEquals("配置编排", model.rows.first().detailActionLabel)
		assertEquals(null, model.rows.first().startActionLabel)
		assertEquals("团队", model.rows.last().kindLabel)
		assertEquals("运行中", model.rows.last().runtimeStateLabel)
		assertEquals("运行中", model.rows.last().statusLabel)
		assertFalse(model.rows.last().removable)
		assertEquals("运行中不可移除", model.rows.last().removeBlockedLabel)
		assertEquals("查看团队", model.rows.last().detailActionLabel)
		assertEquals(null, model.rows.last().startActionLabel)
	}

	@Test
	fun `work unit section hides when there are no visible containers`() {
		assertFalse(buildWorkUnitSectionModel(WorkUnitUiState()).visible)
	}

	@Test
	fun `work unit section can filter containers by kind for dedicated tabs`() {
		val state = WorkUnitUiState(
			items = listOf(
				ThreadItem.WorkUnit(
					id = "it_flow",
					workUnitId = "wu_flow",
					kind = "orchestration",
					name = "页面编排",
					status = "waiting_config",
					currentGoalId = "goal_flow",
					currentGoal = "配置页面编排",
					goalCount = 1,
				),
				ThreadItem.WorkUnit(
					id = "it_team",
					workUnitId = "wu_team",
					kind = "team",
					name = "复核团队",
					status = "waiting_config",
					currentGoalId = "goal_team",
					currentGoal = "配置复核团队",
					goalCount = 1,
				),
			),
		)

		val flowModel = buildWorkUnitSectionModel(state, kindFilter = "orchestration")
		val teamModel = buildWorkUnitSectionModel(state, kindFilter = "team")

		assertEquals(listOf("页面编排"), flowModel.rows.map { it.name })
		assertEquals(listOf("复核团队"), teamModel.rows.map { it.name })
	}

	@Test
	fun `work unit section does not render inline detail card for selected container`() {
		val state = WorkUnitUiState().replaceAll(
			listOf(
				WorkUnitInfo(
					workUnitId = "wu_1",
					threadId = "thr_1",
					kind = "orchestration",
					name = "html-test",
					status = "waiting_config",
					currentGoalId = "goal_2",
					cwd = "H:\\aaa",
					sandboxMode = "FULL_ACCESS",
					goals = listOf(
						WorkUnitGoalInfo(
							goalId = "goal_1",
							workUnitId = "wu_1",
							goalText = "review login page",
							status = "completed",
							summary = "reviewed",
						),
						WorkUnitGoalInfo(
							goalId = "goal_2",
							workUnitId = "wu_1",
							goalText = "edit html content",
							status = "pending",
						),
					),
				),
			),
		).select("wu_1")

		val model = buildWorkUnitSectionModel(state)

		assertEquals(null, model.selectedDetail)
		assertEquals(1, model.rows.size)
		assertEquals("配置编排", model.rows.single().detailActionLabel)
	}

	@Test
	fun `failed work unit detail can be restarted without editing failed goal`() {
		val detail = workUnitDetailModel(
			WorkUnitInfo(
				workUnitId = "wu_1",
				threadId = "thr_1",
				kind = "orchestration",
				name = "html-test",
				status = "failed",
				currentGoalId = "goal_1",
				cwd = "H:\\aaa",
				sandboxMode = "FULL_ACCESS",
				goals = listOf(
					WorkUnitGoalInfo(
						goalId = "goal_1",
						workUnitId = "wu_1",
						goalText = "edit html content",
						status = "failed",
						errorMessage = "checkpoint failed",
					),
				),
			),
			modelLabel = "deepseek-v4-pro",
		)

		assertEquals("重新执行", detail.startActionLabel)
		assertEquals(null, detail.editableGoalId)
		assertEquals(null, detail.editableGoalText)
	}
}
