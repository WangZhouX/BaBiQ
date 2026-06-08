package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ContextSnapshotInfo
import com.wzx.babiq.desktop.protocol.ContextSnapshotItemInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextSnapshotSectionTest {

	@Test
	fun `上下文快照分区按 included 和 excluded 分组`() {
		val snapshot = ContextSnapshotInfo(
			snapshotId = "ctxsnap_1",
			threadId = "thr_1",
			turnId = "turn_1",
			phase = "pre_model_call",
			modelContextWindow = 1_000_000,
			autoCompactThreshold = 750_000,
			estimatedTokens = 120_000,
			includedItemCount = 2,
			excludedItemCount = 1,
			usageRatio = 0.12,
			createdAt = "2026-05-28T10:00:00",
			items = listOf(
				ContextSnapshotItemInfo("item_1", "current_turn", "P0", true, "CURRENT_TURN", 100),
				ContextSnapshotItemInfo("mem_1", "long_term_memory", "P4", true, "MEMORY_REFERENCE", 50),
				ContextSnapshotItemInfo("item_old", "history", "P8", false, "REPLACED_BY_SUMMARY", 500),
			),
		)

		val model = buildContextSnapshotSectionModel(snapshot)

		assertEquals(2, model.included.size)
		assertEquals(1, model.excluded.size)
		assertEquals("纳入 2 段 · 排除 1 段 · 12% · 120000 token", model.summaryLine)
		assertEquals("12%", model.usageLabel)
		assertEquals("本轮输入", model.included.first().sourceLabel)
		assertEquals("长期记忆", model.included.last().sourceLabel)
		assertEquals("优先级 P0", model.included.first().priorityLabel)
		assertEquals("旧历史已由短期摘要替换", model.excluded.single().reasonLabel)
	}
}
