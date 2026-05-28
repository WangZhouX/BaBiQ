package com.wzx.babiq.desktop.ui.settings

import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.protocol.MemoryJobInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

	@Test
	fun `长期记忆设置页模型包含原型指标卡和任务行`() {
		val model = buildMemorySettingsSectionModel(
			status = MemoryStatusResult(
				enabled = true,
				generateEnabled = true,
				readEnabled = true,
				retrievalEnabled = true,
				rootDir = "E:\\BaBiQ\\.babiq\\memories",
				pendingJobs = 4,
				runningJobs = 1,
				cleanCandidateCount = 7,
				secretRiskCandidateCount = 2,
				lastConsolidatedAt = "2026-05-28T10:00:00Z",
				phase2Generation = 6,
			),
			jobs = listOf(MemoryJobInfo("job-1", "PHASE1", "phase1:thr_1", 0, "PENDING", "2026-05-28T09:00:00Z")),
			artifacts = emptyList(),
		)

		assertEquals(listOf("开关", "未归并候选", "SECRET_RISK", "最近归并"), model.metrics.map { it.label })
		assertEquals("7", model.metrics.single { it.label == "未归并候选" }.value)
		assertEquals("2", model.metrics.single { it.label == "SECRET_RISK" }.value)
		val row = model.jobRows.single()
		val expectedCreatedAt = Instant.parse("2026-05-28T09:00:00Z")
			.atZone(ZoneId.systemDefault())
			.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
		assertEquals("候选抽取", row.stage)
		assertEquals("待执行", row.status)
		assertEquals("空闲会话抽取", row.strategy)
		assertEquals(expectedCreatedAt, row.createdAt)
		assertTrue(!row.createdAt.contains("T"))
		assertTrue(!row.createdAt.contains("Z"))
	}
}
