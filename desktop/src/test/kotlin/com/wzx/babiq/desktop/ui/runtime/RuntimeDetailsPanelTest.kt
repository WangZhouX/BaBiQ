package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.RunToolCallInfo
import com.wzx.babiq.desktop.protocol.RunTurnDetailResult
import com.wzx.babiq.desktop.protocol.RunTurnSummaryInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.state.RunRecordState
import com.wzx.babiq.desktop.state.SubAgentUiState
import com.wzx.babiq.desktop.state.TeamUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDetailsPanelTest {

	@Test
	fun `runtime tabs expose orchestration but never add team navigation tab`() {
		val state = AppState(
			orchestrationState = OrchestrationUiState(configuringWorkUnit = workUnit(kind = "orchestration")),
			teamState = TeamUiState(configuringWorkUnit = workUnit(kind = "team")),
		)

		val tabs = runtimePanelTabs(state, RuntimePanelTab.Run)

		assertEquals(listOf(RuntimePanelTab.Run, RuntimePanelTab.Orchestration), tabs.map { it.tab })
		assertTrue(tabs.first { it.tab == RuntimePanelTab.Run }.visible)
		assertTrue(tabs.first { it.tab == RuntimePanelTab.Orchestration }.visible)
		assertFalse(tabs.any { it.tab == RuntimePanelTab.Team })
		assertEquals(RuntimePanelTab.Orchestration, preferredRuntimePanelTab(state, RuntimePanelTab.Run))
	}

	@Test
	fun `runtime tab content does not include dedicated work unit editors`() {
		val runContent = runtimePanelContent(RuntimePanelTab.Run)

		assertFalse(RuntimePanelContent.WorkUnits in runContent)
		assertFalse(RuntimePanelContent.Orchestration in runContent)
		assertFalse(RuntimePanelContent.Team in runContent)
		assertFalse(RuntimePanelContent.SubAgent in runContent)
		assertEquals(setOf(RuntimePanelContent.WorkUnits), runtimePanelContent(RuntimePanelTab.Orchestration))
		assertEquals(setOf(RuntimePanelContent.SubAgent), runtimePanelContent(RuntimePanelTab.SubAgent))
	}

	@Test
	fun `work unit tabs switch from list to detail only while configuring a container`() {
		val runtimeOnly = AppState(
			orchestrationState = OrchestrationUiState(
				current = ThreadItem.Orchestration(
					id = "it_orch",
					orchestrationId = "orch_1",
					title = "runtime flow",
					topology = "sequential",
					status = "running",
				),
			),
			teamState = TeamUiState(
				current = ThreadItem.Team(
					id = "it_team",
					teamId = "team_1",
					title = "runtime team",
					status = "running",
				),
			),
		)
		val configuring = AppState(
			orchestrationState = OrchestrationUiState(configuringWorkUnit = workUnit(kind = "orchestration")),
			teamState = TeamUiState(configuringWorkUnit = workUnit(kind = "team")),
		)

		assertEquals(setOf(RuntimePanelContent.WorkUnits), runtimePanelContent(runtimeOnly, RuntimePanelTab.Orchestration))
		assertEquals(setOf(RuntimePanelContent.Orchestration), runtimePanelContent(configuring, RuntimePanelTab.Orchestration))
	}

	@Test
	fun `team tab requests fall back to run tab without active runtime state`() {
		assertEquals(RuntimePanelTab.Orchestration, resolveRuntimePanelTab(AppState(), RuntimePanelTab.Orchestration))
		assertEquals(RuntimePanelTab.Run, resolveRuntimePanelTab(AppState(), RuntimePanelTab.Team))
	}

	@Test
	fun `sub agent tab only appears when visible and team never appears as tab`() {
		val teamState = AppState(teamState = TeamUiState(configuringWorkUnit = workUnit(kind = "team")))
		val subAgentState = AppState(subAgentState = SubAgentUiState())

		assertEquals(listOf(RuntimePanelTab.Run, RuntimePanelTab.Orchestration), runtimePanelTabs(teamState, RuntimePanelTab.Run).map { it.tab })
		assertEquals(listOf(RuntimePanelTab.Run, RuntimePanelTab.Orchestration), runtimePanelTabs(subAgentState, RuntimePanelTab.Run).map { it.tab })
	}

	@Test
	fun `tool call summary hides spotlight tags and uses chinese labels`() {
		val call = RunToolCallInfo(
			toolCallId = "call-1",
			toolName = "orchestrate_flow",
			argsJson = "{}",
			status = "completed",
			resultPreview = """<untrusted-data source="tool:orchestrate_flow">"Flow failed: Resume request without a valid checkpoint!"</untrusted-data>""",
			agentName = "babiq_agent",
			startedAt = "2026-06-08T10:00:00",
		)

		val line = call.readableToolCallLine()

		assertEquals("[babiq_agent] 编排执行 · 已完成 · Flow failed: Resume request without a valid checkpoint!", line)
		assertFalse(line.contains("<untrusted-data"))
		assertFalse(line.contains("</untrusted-data>"))
	}

	@Test
	fun `selected run row shows loading placeholder while detail is pending`() {
		val state = RunRecordState(
			loading = true,
			selectedTurnId = "turn-2",
			selectedDetail = RunTurnDetailResult(turn = runTurn("turn-1")),
		)

		assertTrue(state.isDetailLoadingForTurn("turn-2"))
		assertEquals(null, state.detailForTurn("turn-2"))
	}

	@Test
	fun `selected run row only renders detail that belongs to the selected turn`() {
		val detail = RunTurnDetailResult(turn = runTurn("turn-2"))
		val state = RunRecordState(
			selectedTurnId = "turn-2",
			selectedDetail = detail,
		)

		assertFalse(state.isDetailLoadingForTurn("turn-2"))
		assertEquals(detail, state.detailForTurn("turn-2"))
		assertEquals(null, state.detailForTurn("turn-1"))
	}

	@Test
	fun `selected run row does not keep redundant view action after detail is expanded`() {
		val detail = RunTurnDetailResult(turn = runTurn("turn-2"))
		val expanded = RunRecordState(
			selectedTurnId = "turn-2",
			selectedDetail = detail,
		)
		val loading = RunRecordState(
			loading = true,
			selectedTurnId = "turn-2",
			selectedDetail = null,
		)

		assertEquals(null, expanded.actionForTurn("turn-2"))
		assertEquals(RunTurnAction.ReadLoading, loading.actionForTurn("turn-2"))
		assertEquals(RunTurnAction.View, expanded.actionForTurn("turn-1"))
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

	private fun runTurn(turnId: String): RunTurnSummaryInfo =
		RunTurnSummaryInfo(
			turnId = turnId,
			threadId = "thr_1",
			status = "COMPLETED",
			inputText = "查看运行记录",
			cwd = "H:\\aaa",
			startedAt = "2026-06-08T10:00:00",
		)
}
