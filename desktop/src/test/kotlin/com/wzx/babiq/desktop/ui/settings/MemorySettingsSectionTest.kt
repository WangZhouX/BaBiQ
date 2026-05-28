package com.wzx.babiq.desktop.ui.settings

import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemorySettingsSectionTest {

	@Test
	fun `长期记忆设置分区展示流水线状态`() {
		val model = buildMemorySettingsSectionModel(
			status = MemoryStatusResult(
				enabled = true,
				generateEnabled = true,
				readEnabled = true,
				retrievalEnabled = true,
				rootDir = "E:\\BaBiQ\\.babiq\\memories",
				pendingJobs = 1,
				runningJobs = 0,
				cleanCandidateCount = 5,
				phase2Generation = 4,
			),
			jobs = emptyList(),
			artifacts = emptyList(),
		)

		assertEquals("已启用", model.enabledLabel)
		assertTrue(model.pipelineLabel.contains("待执行 1"))
		assertTrue(model.pipelineLabel.contains("CLEAN 5"))
		assertTrue(model.pipelineLabel.contains("G4"))
	}
}
