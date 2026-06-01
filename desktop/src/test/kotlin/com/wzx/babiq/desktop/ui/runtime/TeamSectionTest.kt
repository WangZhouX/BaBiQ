package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
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
			title = "团队协作",
			status = "running",
			summary = "正在协调成员",
			approved = true,
			frozen = true,
			currentAgent = "explorer",
			round = 2,
			maxRounds = 5,
			members = listOf(
				ThreadItem.TeamMember(
					memberId = "member_explorer",
					name = "explorer",
					displayName = "探索成员",
					status = "running",
					mode = "READ_ONLY_TOOL",
					task = "读取目录",
					toolCallCount = 3,
					tokenEstimate = 512,
					summary = "正在读取 README",
				),
				ThreadItem.TeamMember(
					memberId = "member_writer",
					name = "writer",
					displayName = "修改成员",
					status = "pending",
					mode = "WORKSPACE_TOOL",
					task = "按结论修改文件",
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
			content = "先读取目录，再返回摘要。这个内容很长时应该被压成短预览。",
			round = 2,
			createdAt = "2026-06-01T10:00:00Z",
		)

		val model = buildTeamSectionModel(TeamUiState(current = team, messages = listOf(message)))

		assertTrue(model.visible)
		assertEquals("团队协作 · 团队协作", model.title)
		assertEquals("运行中 / 第 2/5 轮 / 当前 explorer / 已审批并冻结", model.subtitle)
		assertEquals("explorer", model.selectedAgent)
		assertEquals(listOf("explorer", "writer"), model.memberNames)
		assertEquals(2, model.members.size)
		assertEquals("探索成员", model.members.first().title)
		assertEquals("运行中 · 只读工具 · 3 工具 · 512 token", model.members.first().meta)
		assertEquals(1, model.messages.size)
		assertEquals("supervisor -> explorer / 路由 / 第 2 轮", model.messages.single().meta)
		assertTrue(model.messages.single().preview.length <= 80)
	}

	@Test
	fun `team section hides without current team`() {
		assertFalse(buildTeamSectionModel(TeamUiState()).visible)
	}
}
