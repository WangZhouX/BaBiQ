package com.wzx.babiq.desktop.ui.chat

import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.ContextStatusResult
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.ContextWindowUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import kotlin.test.Test
import kotlin.test.assertTrue

class ContextStatusPopoverTest {

	@Test
	fun `上下文状态弹层把 P3 状态转成可读条目`() {
		val entries = buildContextStatusPopoverEntries(
			context = ContextWindowUiState(
				status = ContextStatusResult(
					threadId = "thr_1",
					modelContextWindow = 1_000_000,
					autoCompactThreshold = 750_000,
					lastSnapshotId = "ctxsnap_1",
					lastEstimatedTokens = 120_000,
					usageRatio = 0.12,
					activeSummaryId = "ctxsum_1",
					compactionCount = 2,
				),
			),
			memory = MemoryUiState(
				status = MemoryStatusResult(
					enabled = true,
					generateEnabled = true,
					readEnabled = true,
					retrievalEnabled = true,
					rootDir = "E:\\BaBiQ\\.babiq\\memories",
					phase2Generation = 3,
				),
			),
			capability = CapabilityUiState(
				status = CapabilityStatusResult(
					totalCount = 12,
					visibleCount = 6,
					deferredCount = 5,
					disabledCount = 1,
				),
			),
		)

		assertTrue(entries.any { it.title == "上下文窗口" && "12%" in it.detail })
		assertTrue(entries.any { it.title == "短期压缩" && "2 次" in it.detail })
		assertTrue(entries.any { it.title == "长期记忆" && "G3" in it.detail })
		assertTrue(entries.any { it.title == "能力装配" && "按需 5" in it.detail })
	}
}
