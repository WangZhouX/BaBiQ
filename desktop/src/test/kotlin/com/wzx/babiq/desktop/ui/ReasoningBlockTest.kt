package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.state.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningBlockTest {

	@Test
	fun `running reasoning defaults to expanded`() {
		val message = ChatMessage.Reasoning("reasoning-1", "正在分析目录结构", completed = false)

		assertEquals("💭 思考过程", titleFor(message))
		assertFalse(shouldCollapseByDefault(message))
		assertEquals("正在分析目录结构", bodyFor(message, expanded = true))
	}

	@Test
	fun `completed reasoning defaults to collapsed but can expand`() {
		val message = ChatMessage.Reasoning("reasoning-1", "已经完成分析", completed = true)

		assertEquals("💭 思考过程 · 已完成", titleFor(message))
		assertTrue(shouldCollapseByDefault(message))
		assertEquals("", bodyFor(message, expanded = false))
		assertEquals("已经完成分析", bodyFor(message, expanded = true))
	}

	private fun titleFor(message: ChatMessage): String {
		val method = Class.forName("com.wzx.babiq.desktop.ui.chat.MessageBubbleKt")
			.getDeclaredMethod("titleFor", ChatMessage::class.java)
		method.isAccessible = true
		return method.invoke(null, message) as String
	}

	private fun bodyFor(message: ChatMessage, expanded: Boolean): String {
		val method = Class.forName("com.wzx.babiq.desktop.ui.chat.MessageBubbleKt")
			.getDeclaredMethod("bodyFor", ChatMessage::class.java, Boolean::class.javaPrimitiveType)
		method.isAccessible = true
		return method.invoke(null, message, expanded) as String
	}

	private fun shouldCollapseByDefault(message: ChatMessage): Boolean {
		val method = Class.forName("com.wzx.babiq.desktop.ui.chat.MessageBubbleKt")
			.getDeclaredMethod("shouldCollapseByDefault", ChatMessage::class.java)
		method.isAccessible = true
		return method.invoke(null, message) as Boolean
	}
}
