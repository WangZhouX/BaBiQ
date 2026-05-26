package com.wzx.babiq.desktop.ui.chat

import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.state.ContextWindowUiState
import com.wzx.babiq.desktop.ui.common.BadgeTone
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

	@Test
	fun `上下文窗口 chip 根据后端状态展示摘要和色调`() {
		assertEquals("上下文 未生成", contextWindowChipLabel(ContextWindowUiState()))
		assertEquals(BadgeTone.Info, contextWindowChipTone(ContextWindowUiState()))

		val status = ContextStatusResult(
			threadId = "thr_1",
			modelContextWindow = 1000,
			lastSnapshotId = "ctxsnap_1",
			lastEstimatedTokens = 850,
			usageRatio = 0.85,
			status = "over_threshold",
		)

		assertEquals("上下文 85%", contextWindowChipLabel(ContextWindowUiState(status = status)))
		assertEquals(BadgeTone.Warning, contextWindowChipTone(ContextWindowUiState(status = status)))
		assertEquals(BadgeTone.Danger, contextWindowChipTone(ContextWindowUiState(error = "boom")))
	}
}
