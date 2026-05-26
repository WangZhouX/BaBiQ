package com.wzx.babiq.desktop.ui.chat

import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.state.ContextWindowUiState
import com.wzx.babiq.desktop.state.MemoryUiState
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

	@Test
	fun `上下文窗口 chip 优先展示已压缩次数`() {
		val status = ContextStatusResult(
			threadId = "thr_1",
			modelContextWindow = 1000,
			lastSnapshotId = "ctxsnap_1",
			lastEstimatedTokens = 400,
			usageRatio = 0.4,
			status = "ok",
			activeSummaryId = "ctxsum_1",
			compactionCount = 2,
			lastCompactionStatus = "SUCCESS",
		)

		assertEquals("已压缩 2 次", contextWindowChipLabel(ContextWindowUiState(status = status)))
		assertEquals(BadgeTone.Success, contextWindowChipTone(ContextWindowUiState(status = status)))
	}

	@Test
	fun `长期记忆 chip 根据后端状态展示注入语义`() {
		assertEquals("长期记忆 未加载", memoryChipLabel(MemoryUiState()))
		assertEquals(BadgeTone.Info, memoryChipTone(MemoryUiState()))

		val withSummary = MemoryUiState(
			status = MemoryStatusResult(
				enabled = true,
				generateEnabled = true,
				readEnabled = true,
				rootDir = "E:\\BaBiQ\\.babiq\\memories",
				cleanCandidateCount = 0,
				lastSummaryArtifactId = "memart_1",
				phase2Generation = 2,
			),
		)
		assertEquals("长期记忆 G2", memoryChipLabel(withSummary))
		assertEquals(BadgeTone.Success, memoryChipTone(withSummary))

		val readDisabled = MemoryUiState(
			status = MemoryStatusResult(
				enabled = true,
				generateEnabled = true,
				readEnabled = false,
				rootDir = "E:\\BaBiQ\\.babiq\\memories",
			),
		)
		assertEquals("长期记忆 不注入", memoryChipLabel(readDisabled))
		assertEquals(BadgeTone.Warning, memoryChipTone(readDisabled))
		assertEquals(BadgeTone.Danger, memoryChipTone(MemoryUiState(error = "boom")))
	}
}
