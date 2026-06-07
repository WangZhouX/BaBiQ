package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.state.SubAgentUiState
import com.wzx.babiq.desktop.state.TeamUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDetailsPanelTest {

	@Test
	fun `runtime tabs expose orchestration separately when a flow is being configured`() {
		val state = AppState(
			orchestrationState = OrchestrationUiState(configuringWorkUnit = workUnit(kind = "orchestration")),
		)

		val tabs = runtimePanelTabs(state, RuntimePanelTab.Run)

		assertEquals(listOf(RuntimePanelTab.Run, RuntimePanelTab.Orchestration), tabs.map { it.tab })
		assertTrue(tabs.first { it.tab == RuntimePanelTab.Run }.visible)
		assertTrue(tabs.first { it.tab == RuntimePanelTab.Orchestration }.visible)
		assertEquals(RuntimePanelTab.Orchestration, preferredRuntimePanelTab(state, RuntimePanelTab.Run))
	}

	@Test
	fun `runtime tab content does not include dedicated work unit editors`() {
		val runContent = runtimePanelContent(RuntimePanelTab.Run)

		assertTrue(RuntimePanelContent.WorkUnits in runContent)
		assertFalse(RuntimePanelContent.Orchestration in runContent)
		assertFalse(RuntimePanelContent.Team in runContent)
		assertFalse(RuntimePanelContent.SubAgent in runContent)
		assertEquals(setOf(RuntimePanelContent.Orchestration), runtimePanelContent(RuntimePanelTab.Orchestration))
		assertEquals(setOf(RuntimePanelContent.Team), runtimePanelContent(RuntimePanelTab.Team))
		assertEquals(setOf(RuntimePanelContent.SubAgent), runtimePanelContent(RuntimePanelTab.SubAgent))
	}

	@Test
	fun `invisible requested tab falls back to run tab`() {
		assertEquals(RuntimePanelTab.Run, resolveRuntimePanelTab(AppState(), RuntimePanelTab.Orchestration))
	}

	@Test
	fun `team and sub agent tabs only appear when their state is visible`() {
		val teamState = AppState(teamState = TeamUiState(configuringWorkUnit = workUnit(kind = "team")))
		val subAgentState = AppState(subAgentState = SubAgentUiState())

		assertEquals(listOf(RuntimePanelTab.Run, RuntimePanelTab.Team), runtimePanelTabs(teamState, RuntimePanelTab.Run).map { it.tab })
		assertEquals(listOf(RuntimePanelTab.Run), runtimePanelTabs(subAgentState, RuntimePanelTab.Run).map { it.tab })
	}

	private fun workUnit(kind: String): WorkUnitInfo =
		WorkUnitInfo(
			workUnitId = "wu_1",
			threadId = "thr_1",
			kind = kind,
			name = "html-test",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "FULL_ACCESS",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_1", "edit html", "pending")),
		)
}
