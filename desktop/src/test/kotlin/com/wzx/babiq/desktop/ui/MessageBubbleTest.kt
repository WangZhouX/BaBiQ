package com.wzx.babiq.desktop.ui

import com.wzx.babiq.desktop.state.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageBubbleTest {
	@Test
	fun `用户和助手气泡不显示发送者标题`() {
		assertEquals("", titleFor(ChatMessage.User("user-1", "你好")))
		assertEquals("", titleFor(ChatMessage.Agent("agent-1", "你好，我是 BaBiQ")))
		assertEquals("", titleFor(ChatMessage.Agent("agent-2", "正在输入", streaming = true)))
	}

	@Test
	fun `工具和文件气泡仍保留语义标题`() {
		assertEquals("工具 · completed", titleFor(ChatMessage.Tool("tool-1", "read_file", "completed", "README.md")))
		assertEquals("文件 · patched", titleFor(ChatMessage.FileChange("file-1", "patch", "README.md", "patched", null)))
	}

	/**
	 * titleFor 是渲染文件的私有辅助函数；测试通过反射读取它，避免为了测试把 UI 内部细节暴露成 public API。
	 */
	private fun titleFor(message: ChatMessage): String {
		val method = Class.forName("com.wzx.babiq.desktop.ui.chat.MessageBubbleKt")
			.getDeclaredMethod("titleFor", ChatMessage::class.java)
		method.isAccessible = true
		return method.invoke(null, message) as String
	}
}
