package com.wzx.babiq.desktop.ui.shell

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.WorkUnitUiState
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppShellRuntimeLayoutTest {

	@Test
	fun `runtime panel width follows horizontal resize handle direction`() {
		assertEquals(440.dp, resizeRuntimePanelWidth(360.dp, (-80).dp))
		assertEquals(320.dp, resizeRuntimePanelWidth(360.dp, 80.dp))
	}

	@Test
	fun `runtime panel width is clamped to usable bounds`() {
		assertEquals(MinRuntimePanelWidth, resizeRuntimePanelWidth(360.dp, 500.dp))
		assertEquals(760.dp, resizeRuntimePanelWidth(360.dp, (-500).dp))
	}

	@Test
	fun `runtime panel resize handle keeps a visible drag target`() {
		assertTrue(RuntimePanelResizeHandleWidth >= 18.dp)
		assertTrue(RuntimePanelResizeRailWidth >= 4.dp)
	}

	@Test
	fun `runtime panel stays hidden after user collapses even when work unit data exists`() {
		val state = AppState(
			runtimeExpanded = false,
			workUnitState = WorkUnitUiState(
				items = listOf(
					ThreadItem.WorkUnit(
						id = "it_workunit_1",
						workUnitId = "wu_1",
						kind = "orchestration",
						name = "html-test",
						status = "waiting_config",
						currentGoal = "修改 html 内容",
						goalCount = 1,
						removed = false,
					),
				),
			),
		)

		assertFalse(shouldShowRuntimePanel(state))
	}
}
