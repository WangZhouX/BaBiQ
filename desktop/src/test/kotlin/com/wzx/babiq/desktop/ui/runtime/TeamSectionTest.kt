package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.TeamUiState
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
		assertEquals("leader", model.selectedAgent)
		assertEquals(listOf("leader", "explorer", "writer"), model.memberNames)
		assertEquals("团队切换", model.teamSwitchSectionTitle)
		assertEquals("成员（2）", model.memberSectionTitle)
		assertEquals("团队对话", model.timelineSectionTitle)
		assertEquals("对话对象：Leader", model.composerTargetLabel)
		assertEquals("和这个团队（默认 Leader）说…", model.composerPlaceholder)
		assertEquals(2, model.members.size)
		assertEquals("explorer", model.members.first().title)
		assertEquals("运行中 · 只读工具 · 3 工具 · 512 token", model.members.first().meta)
		assertEquals(1, model.messages.size)
		assertEquals("supervisor -> explorer / 路由 / 第 2 轮", model.messages.single().meta)
		assertTrue(model.messages.single().preview.length <= 80)
		assertEquals(null, model.config)
		assertEquals(null, model.removeActionLabel)
	}

	@Test
	fun `completed team runtime model exposes persistent remove action`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "failed team",
			status = "failed",
			summary = "checkpoint failed",
			members = emptyList(),
		)

		val model = buildTeamSectionModel(TeamUiState(current = team), modelLabel = "deepseek-v4-pro")

		assertTrue(model.visible)
		assertEquals("移除", model.removeActionLabel)
	}

	@Test
	fun `team section model exposes multi team switcher`() {
		val oldTeam = ThreadItem.Team(
			id = "it_team_old",
			teamId = "team_old",
			title = "旧团队",
			status = "completed",
			members = emptyList(),
		)
		val activeTeam = ThreadItem.Team(
			id = "it_team_active",
			teamId = "team_active",
			title = "当前团队",
			status = "running",
			members = emptyList(),
		)

		val model = buildTeamSectionModel(
			TeamUiState()
				.withTeamList(listOf(oldTeam, activeTeam))
				.selectTeam("team_active"),
		)

		assertEquals(listOf("team_old", "team_active"), model.teams.map { it.teamId })
		assertEquals("team_active", model.selectedTeamId)
		assertFalse(model.teams.first { it.teamId == "team_old" }.selected)
		assertTrue(model.teams.first { it.teamId == "team_active" }.selected)
	}

	@Test
	fun `team composer defaults to leader and can preserve selected member`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "review team",
			status = "running",
			currentAgent = "writer",
			members = listOf(
				ThreadItem.TeamMember(
					memberId = "member_writer",
					name = "writer",
					displayName = "Writer",
					status = "running",
					mode = "WORKSPACE_TOOL",
				),
			),
		)

		val defaultModel = buildTeamSectionModel(TeamUiState().withTeam(team))
		val memberModel = buildTeamSectionModel(TeamUiState().withTeam(team).selectAgent("writer"))

		assertEquals("leader", defaultModel.selectedAgent)
		assertEquals(listOf("leader", "writer"), defaultModel.memberNames)
		assertEquals("writer", memberModel.selectedAgent)
	}

	@Test
	fun `dismissed team runtime model stays hidden`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "failed team",
			status = "failed",
			members = emptyList(),
		)

		val model = buildTeamSectionModel(
			TeamUiState(current = team, dismissedTeamId = "team_1"),
		)

		assertFalse(model.visible)
	}

	@Test
	fun `team configuration detail takes precedence over runtime playback and exposes back action`() {
		val team = ThreadItem.Team(
			id = "it_team_1",
			teamId = "team_1",
			title = "runtime team",
			status = "running",
			members = emptyList(),
		)
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "review-team",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			sandboxMode = "WORKSPACE_WRITE",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_team", "review page", "pending")),
		)

		val model = buildTeamSectionModel(
			TeamUiState(current = team, configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertEquals("团队详情 · review-team", model.title)
		assertEquals("返回列表", model.backActionLabel)
		assertEquals("wu_team", model.config?.workUnitId)
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
			configJson = """
				{
				  "members": [
				    {"id": "leader", "name": "leader", "task": "拆解验收任务", "model": "inherit"},
				    {"id": "frontend", "name": "frontend", "task": "检查 Compose UI", "model": "provider:qwen:qwen-plus"}
				  ]
				}
			""".trimIndent(),
		)

		val teamSummary = ThreadItem.Team(
			id = "it_team_summary",
			teamId = "team_team",
			title = "review-team",
			status = "pending",
			summary = "team/list 只返回摘要，不含完整成员",
			members = emptyList(),
		)

		val model = buildTeamSectionModel(
			TeamUiState(current = teamSummary, configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		)

		assertTrue(model.visible)
		assertEquals("团队详情 · review-team", model.title)
		assertEquals("待配置 / 1 个目标 / 等待手动启动", model.subtitle)
		assertEquals("wu_team", model.config?.workUnitId)
		assertEquals("H:\\aaa", model.config?.cwd)
		assertEquals("工作区可写", model.config?.sandboxLabel)
		assertEquals("deepseek-v4-pro", model.config?.modelLabel)
		assertEquals("goal_1", model.config?.editableGoalId)
		assertEquals("review login page", model.config?.editableGoalText)
		assertNull(model.selectedAgent)
		assertEquals(emptyList(), model.memberNames)
		assertEquals("团队切换", model.teamSwitchSectionTitle)
		assertEquals("成员（2）", model.memberSectionTitle)
		assertNull(model.timelineSectionTitle)
		assertEquals(emptyList(), model.messages)
		assertEquals(listOf("leader", "frontend"), model.configMembers.map { it.memberId })
		assertEquals("检查 Compose UI", model.configMembers.first { it.memberId == "frontend" }.task)
		assertEquals("provider:qwen:qwen-plus", model.configMembers.first { it.memberId == "frontend" }.modelValue)
		assertEquals("移除", model.removeActionLabel)
		assertEquals("返回列表", model.backActionLabel)
	}

	@Test
	fun `team config members separate identity role and tool permission labels`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "team-default",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_team", "review login page", "pending")),
			configJson = null,
		)

		val members = buildTeamSectionModel(
			TeamUiState(configuringWorkUnit = workUnit),
			modelLabel = "deepseek-v4-pro",
		).configMembers

		val frontend = members.first { it.memberId == "frontend" }
		assertEquals("成员：frontend", frontend.memberLabel)
		assertEquals("角色：实现", frontend.roleLabel)
		assertEquals("工具权限：工作区工具", frontend.permissionLabel)
		assertEquals("角色：实现 · 工具权限：工作区工具", frontend.listMeta)
		assertEquals("成员详情 · frontend", frontend.detailTitle)
		assertEquals("详情配置", frontend.detailActionLabel)
		assertEquals("删除成员", frontend.removeActionLabel)
		assertEquals(frontend.task, frontend.taskPreview)
		assertFalse(frontend.listMeta.contains(frontend.task))
		assertFalse(frontend.role.contains("工具"))

		val tester = members.first { it.memberId == "tester" }
		assertEquals("角色：验证", tester.roleLabel)
		assertEquals("工具权限：只读工具", tester.permissionLabel)
		assertFalse(tester.role.contains("工具"))
	}

	@Test
	fun `team config add member updates local draft and selects new member before backend refresh`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "review-team",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_team", "review login page", "pending")),
			configJson = """
				{
				  "members": [
				    {"id": "writer", "name": "writer", "role": "writer", "task": "write patch", "model": "inherit", "mode": "WORKSPACE_TOOL"}
				  ]
				}
			""".trimIndent(),
		)
		val detail = workUnitDetailModel(workUnit, "deepseek-v4-pro")
		val members = buildTeamSectionModel(TeamUiState(configuringWorkUnit = workUnit), modelLabel = "deepseek-v4-pro").configMembers

		val draft = addTeamConfigMemberDraft(TeamConfigMembersDraft(members, "writer"), detail)

		assertEquals(listOf("writer", "member_2"), draft.members.map { it.memberId })
		assertEquals("member_2", draft.selectedMemberId)
		assertEquals("member_2", draft.detailMemberId)
		assertEquals("成员详情 · member_2", draft.members.last().detailTitle)
	}

	@Test
	fun `team config draft switches between member list and member detail page`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "review-team",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_team", "review login page", "pending")),
			configJson = """
				{
				  "members": [
				    {"id": "writer", "name": "writer", "role": "writer", "task": "write patch", "model": "inherit", "mode": "WORKSPACE_TOOL"},
				    {"id": "reviewer", "name": "reviewer", "role": "reviewer", "task": "review patch", "model": "inherit", "mode": "READ_ONLY_TOOL"}
				  ]
				}
			""".trimIndent(),
		)
		val members = buildTeamSectionModel(TeamUiState(configuringWorkUnit = workUnit), modelLabel = "deepseek-v4-pro").configMembers
		val listDraft = TeamConfigMembersDraft(members, selectedMemberId = "writer")

		assertNull(listDraft.detailMemberId)

		val detailDraft = openTeamConfigMemberDetail(listDraft, "reviewer")
		assertEquals("reviewer", detailDraft.selectedMemberId)
		assertEquals("reviewer", detailDraft.detailMemberId)

		val backToList = closeTeamConfigMemberDetail(detailDraft)
		assertEquals("reviewer", backToList.selectedMemberId)
		assertNull(backToList.detailMemberId)

		val removed = removeTeamConfigMemberDraft(detailDraft, "reviewer")
		assertEquals(listOf("writer"), removed.members.map { it.memberId })
		assertEquals("writer", removed.selectedMemberId)
		assertNull(removed.detailMemberId)
	}

	@Test
	fun `team config helpers add update and remove members`() {
		val workUnit = WorkUnitInfo(
			workUnitId = "wu_team",
			threadId = "thr_1",
			kind = "team",
			name = "review-team",
			status = "waiting_config",
			currentGoalId = "goal_1",
			cwd = "H:\\aaa",
			goals = listOf(WorkUnitGoalInfo("goal_1", "wu_team", "review login page", "pending")),
			configJson = """
				{
				  "members": [
				    {"id": "writer", "name": "writer", "role": "writer", "task": "write patch", "model": "inherit", "mode": "WORKSPACE_TOOL"}
				  ]
				}
			""".trimIndent(),
		)
		val detail = workUnitDetailModel(workUnit, "deepseek-v4-pro")
		val members = buildTeamSectionModel(TeamUiState(configuringWorkUnit = workUnit), modelLabel = "deepseek-v4-pro").configMembers

		val added = addTeamConfigMember(members, detail)

		assertEquals(listOf("writer", "member_2"), added.map { it.memberId })
		val updated = updateTeamConfigMember(
			members = added,
			memberId = "member_2",
			title = "reviewer",
			role = "reviewer",
			task = "review result",
			modelValue = "provider:qwen:qwen-plus",
			mode = "WORKSPACE_TOOL",
			inheritedModel = "deepseek-v4-pro",
		)
		val updatedConfig = protocolJson.decodeFromString<WorkUnitConfiguration>(buildTeamConfigJson(detail, updated))
		val reviewer = updatedConfig.members.first { it.id == "member_2" }
		assertEquals("reviewer", reviewer.name)
		assertEquals("reviewer", reviewer.role)
		assertEquals("review result", reviewer.task)
		assertEquals("provider:qwen:qwen-plus", reviewer.model)
		assertEquals("WORKSPACE_TOOL", reviewer.mode)
		assertTrue("write_file" in reviewer.toolNames)
		assertTrue("apply_patch" in reviewer.toolNames)
		assertEquals(listOf("H:\\aaa"), reviewer.writeScopes)

		val removed = removeTeamConfigMember(updated, "writer")
		val removedConfig = protocolJson.decodeFromString<WorkUnitConfiguration>(buildTeamConfigJson(detail, removed))
		assertEquals(listOf("member_2"), removedConfig.members.map { it.id })
	}

	@Test
	fun `team section hides without runtime or configuration detail`() {
		assertFalse(buildTeamSectionModel(TeamUiState()).visible)
	}
}
