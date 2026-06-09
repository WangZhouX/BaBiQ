package com.wzx.babiq.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerChromeTest {

	@Test
	fun `空草稿不允许发送但保留输入提示`() {
		val chrome = composerChromeFor(text = "   ", canSend = true)

		assertEquals("描述任务或提出问题", chrome.placeholder)
		assertEquals("发送", chrome.sendLabel)
		assertFalse(chrome.sendEnabled)
		assertEquals(ComposerSendTone.Disabled, chrome.sendTone)
	}

	@Test
	fun `可发送草稿使用强调态`() {
		val chrome = composerChromeFor(text = "修复输入框样式", canSend = true)

		assertTrue(chrome.sendEnabled)
		assertEquals(ComposerSendTone.Accent, chrome.sendTone)
	}

	@Test
	fun `运行中的 turn 禁用发送`() {
		val chrome = composerChromeFor(text = "继续执行", canSend = false)

		assertFalse(chrome.sendEnabled)
		assertEquals(ComposerSendTone.Disabled, chrome.sendTone)
	}
}
