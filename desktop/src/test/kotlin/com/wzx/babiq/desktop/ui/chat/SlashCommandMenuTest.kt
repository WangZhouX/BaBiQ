package com.wzx.babiq.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlashCommandMenuTest {

	@Test
	fun `输入斜杠时展示编排和团队命令`() {
		val suggestions = slashCommandSuggestionsFor("/")

		assertEquals(listOf("/编排", "/团队"), suggestions.map { it.command })
	}

	@Test
	fun `输入命令关键词时只保留匹配项`() {
		val suggestions = slashCommandSuggestionsFor("/编")

		assertEquals(listOf("/编排"), suggestions.map { it.command })
	}

	@Test
	fun `已填写完整命令后不再展示菜单`() {
		assertTrue(slashCommandSuggestionsFor("/团队 登录优化：检查登录页").isEmpty())
	}

	@Test
	fun `命令项提供可直接填入输入框的模板`() {
		val orchestration = slashCommandSuggestionsFor("/").first { it.command == "/编排" }

		assertEquals("/编排 名称：目标", orchestration.template)
	}
}
