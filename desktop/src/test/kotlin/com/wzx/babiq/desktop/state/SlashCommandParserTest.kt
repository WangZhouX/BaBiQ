package com.wzx.babiq.desktop.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SlashCommandParserTest {

	@Test
	fun `parse orchestration command with Chinese colon`() {
		val command = SlashCommandParser.parse("/编排 登录页重构：把登录页拆成设计检查和代码修改两个步骤")

		val orchestration = assertIs<SlashCommand.WorkUnit>(command)
		assertEquals(WorkUnitKind.Orchestration, orchestration.kind)
		assertEquals("登录页重构", orchestration.name)
		assertEquals("把登录页拆成设计检查和代码修改两个步骤", orchestration.goal)
	}

	@Test
	fun `parse team command with ASCII colon and extra spaces`() {
		val command = SlashCommandParser.parse("  /团队  登录优化小组 : 让研究员和实现者协作修复登录页  ")

		val team = assertIs<SlashCommand.WorkUnit>(command)
		assertEquals(WorkUnitKind.Team, team.kind)
		assertEquals("登录优化小组", team.name)
		assertEquals("让研究员和实现者协作修复登录页", team.goal)
	}

	@Test
	fun `normal message and incomplete command are not slash commands`() {
		assertNull(SlashCommandParser.parse("查看当前目录"))
		assertNull(SlashCommandParser.parse("/编排 只有名称"))
		assertNull(SlashCommandParser.parse("/团队 ：缺少名称"))
		assertNull(SlashCommandParser.parse("/编排 名称："))
	}
}
