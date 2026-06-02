package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
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
		assertEquals("待配置", model.rows.first().statusLabel)
		assertTrue(model.rows.first().removable)
		assertEquals("团队", model.rows.last().kindLabel)
		assertEquals("运行中", model.rows.last().statusLabel)
		assertFalse(model.rows.last().removable)
	}

	@Test
	fun `work unit section hides when there are no visible containers`() {
		assertFalse(buildWorkUnitSectionModel(WorkUnitUiState()).visible)
	}
}
