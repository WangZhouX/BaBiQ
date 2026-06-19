package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamModelsTest {

	@Test
	fun `team list info maps to runtime team item`() {
		val info = TeamInfo(
			teamId = "team_1",
			threadId = "thread_1",
			turnId = "turn_1",
			title = "review team",
			goal = "review generated html",
			status = "running",
			approved = true,
			frozen = true,
			maxRounds = 5,
			currentRound = 2,
			currentAgent = "writer",
			summary = "writer is editing files",
			memberCount = 2,
		)

		val item = info.toThreadItem()

		assertEquals("it_team_1", item.id)
		assertEquals("team_1", item.teamId)
		assertEquals("review team", item.title)
		assertEquals("running", item.status)
		assertEquals("writer is editing files", item.summary)
		assertEquals(true, item.approved)
		assertEquals(true, item.frozen)
		assertEquals("writer", item.currentAgent)
		assertEquals(2, item.round)
		assertEquals(5, item.maxRounds)
		assertEquals(emptyList(), item.members)
	}

	@Test
	fun `team detail maps members and timeline messages`() {
		val detail = TeamGetResult(
			team = TeamInfo(
				teamId = "team_1",
				threadId = "thread_1",
				turnId = "turn_1",
				title = "review team",
				status = "completed",
				currentRound = 0,
				maxRounds = 0,
			),
			members = listOf(
				TeamMemberInfo(
					teamId = "team_1",
					memberId = "member_writer",
					name = "writer",
					displayName = "Writer",
					role = "write files",
					mode = "WORKSPACE_TOOL",
					status = "completed",
					toolCallCount = 3,
					tokenEstimate = 1200,
					summary = "updated index.html",
				),
			),
			messages = listOf(
				TeamMessageInfo(
					teamId = "team_1",
					messageId = "msg_1",
					threadId = "thread_1",
					turnId = "turn_1",
					fromAgent = "supervisor",
					toAgent = "writer",
					messageType = "route",
					content = "write the file",
					round = 1,
				),
			),
		)

		val team = detail.toThreadTeam()
		val member = team.members.single()
		val message = detail.toThreadMessages().single()

		assertEquals("completed", team.status)
		assertNull(team.round)
		assertNull(team.maxRounds)
		assertEquals("member_writer", member.memberId)
		assertEquals("writer", member.name)
		assertEquals("Writer", member.displayName)
		assertEquals("WORKSPACE_TOOL", member.mode)
		assertEquals("write files", member.task)
		assertEquals(3, member.toolCallCount)
		assertEquals(1200, member.tokenEstimate)
		assertEquals("updated index.html", member.summary)
		assertEquals("it_team_msg_msg_1", message.id)
		assertEquals("msg_1", message.messageId)
		assertEquals("supervisor", message.fromAgent)
		assertEquals("writer", message.toAgent)
		assertEquals("route", message.messageType)
		assertEquals("write the file", message.content)
		assertEquals(1, message.round)
	}

	@Test
	fun `team detail accepts timeline messages without thread ids`() {
		val detail = protocolJson.decodeFromString(
			TeamGetResult.serializer(),
			"""
			{
			  "team": {
			    "teamId": "team_1",
			    "threadId": "thread_1",
			    "turnId": "turn_1",
			    "title": "review team",
			    "status": "completed"
			  },
			  "messages": [
			    {
			      "teamId": "team_1",
			      "messageId": "msg_route",
			      "threadId": null,
			      "turnId": null,
			      "fromAgent": "supervisor",
			      "toAgent": "leader",
			      "messageType": "route",
			      "content": "next step",
			      "round": 1
			    }
			  ]
			}
			""".trimIndent(),
		)

		val message = detail.toThreadMessages().single()

		assertEquals("msg_route", message.messageId)
		assertEquals("leader", message.toAgent)
		assertEquals("next step", message.content)
	}
}
