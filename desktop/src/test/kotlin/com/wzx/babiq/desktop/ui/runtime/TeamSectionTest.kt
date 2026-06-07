package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.state.TeamUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamSectionTest {

	@Test
	fun `team section model summarizes supervisor team members and messages`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "review team",
			status = "running",
			summary = "coordinating members",
			approved = true,
			frozen = true,
			currentAgent = "explorer",
			round = 2,
			maxRounds = 5,
			members = listOf(
				ThreadItem.TeamMember(
					memberId = "member_explorer",
					name = "explorer",
					displayName = "explorer",
					status = "running",
					mode = "READ_ONLY_TOOL",
					task = "read directory",
					toolCallCount = 3,
					tokenEstimate = 512,
					summary = "reading README",
				),
				ThreadItem.TeamMember(
					memberId = "member_writer",
					name = "writer",
					displayName = "writer",
					status = "pending",
					mode = "WORKSPACE_TOOL",
					task = "edit files after review",
				),
			),
		)
		val message = ThreadItem.TeamMessage(
			id = "it_team_msg_1",
			messageId = "msg_1",
			teamId = "team_1",
			fromAgent = "supervisor",
			toAgent = "explorer",
			messageType = "route",
			content = "read the directory first and return a concise summary",
			round = 2,
			createdAt = "2026-06-01T10:00:00Z",
		)

		val model = buildTeamSectionModel(TeamUiState(current = team, messages = listOf(message)), modelLabel = "deepseek-v4-pro")

		assertTrue(model.visible)
		assertEquals("团队协作 · review team", model.title)
		assertEquals("运行中 / 第 2/5 轮 / 当前 explorer / 已审批并冻结", model.subtitle)
		assertEquals("explorer", model.selectedAgent)
		assertEquals(listOf("explorer", "writer"), model.memberNames)
		assertEquals(2, model.members.size)
		assertEquals("explorer", model.members.first().title)
		assertEquals("运行中 · 只读工具 · 3 工具 · 512 token", model.members.first().meta)
		assertEquals(1, model.messages.size)
		assertEquals("supervisor -> explorer / 路由 / 第 2 轮", model.messages.single().meta)
		assertTrue(model.messages.single().preview.length <= 80)
		assertEquals(null, model.config)
	}

	@Test
	fun `team section model renders work unit configuration detail`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "review-team",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "WORKSPACE_WRITE",
			goals = listOf(
				WorkUnitGoalInfo("goal_1", "wu_team", "review login page", "pending"),
			),
		)

		val model = buildTeamSectionModel(TeamUiState(configuringWorkUnit = workUnit), modelLabel = "deepseek-v4-pro")

		assertTrue(model.visible)
		assertEquals("团队详情 · review-team", model.title)
		assertEquals("待配置 / 1 个目标 / 等待手动启动", model.subtitle)
		assertEquals("wu_team", model.config?.workUnitId)
		assertEquals("H:\\aaa", model.config?.cwd)
		assertEquals("工作区可写", model.config?.sandboxLabel)
		assertEquals("deepseek-v4-pro", model.config?.modelLabel)
		assertEquals("goal_1", model.config?.editableGoalId)
		assertEquals("review login page", model.config?.editableGoalText)
	}

	@Test
	fun `team section hides without runtime or configuration detail`() {
		assertFalse(buildTeamSectionModel(TeamUiState()).visible)
	}
}
