package com.wzx.babiq.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerContextBarTest {

	@Test
	fun `对话页权限菜单暴露后端支持的沙箱模式`() {
		assertEquals(
			listOf("READ_ONLY", "WORKSPACE_WRITE", "DANGER_FULL_ACCESS"),
			sandboxModeMenuOptions.map { it.mode },
		)
		assertEquals(
			listOf("只读权限", "工作区可写", "完全访问权限"),
			sandboxModeMenuOptions.map { it.label },
		)
	}

	@Test
	fun `对话页权限菜单只有设置可写且存在保存回调时可点击`() {
		assertTrue(canOpenSandboxModeMenu(canEditSettings = true, onChangeSandboxMode = {}))
		assertFalse(canOpenSandboxModeMenu(canEditSettings = false, onChangeSandboxMode = {}))
		assertFalse(canOpenSandboxModeMenu(canEditSettings = true, onChangeSandboxMode = null))
	}
}
