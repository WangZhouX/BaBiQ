package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryModelsTest {

	@Test
	fun `memory status 可以解析长期记忆状态`() {
		val json = """
			{
			  "enabled": true,
			  "generateEnabled": true,
			  "readEnabled": false,
			  "rootDir": "E:\\BaBiQ\\.babiq\\memories",
			  "pendingJobs": 1,
			  "runningJobs": 0,
			  "cleanCandidateCount": 5,
			  "lastSummaryArtifactId": "memart_1",
			  "lastConsolidatedAt": "2026-05-27T00:00:00Z",
			  "phase2Generation": 2
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(MemoryStatusResult.serializer(), json)

		assertEquals(true, result.enabled)
		assertEquals(false, result.readEnabled)
		assertEquals(5, result.cleanCandidateCount)
		assertEquals("memart_1", result.lastSummaryArtifactId)
	}

	@Test
	fun `memory job 和 artifact 列表可以解析`() {
		val jobs = protocolJson.decodeFromString(
			MemoryJobsListResult.serializer(),
			"""{"jobs":[{"jobId":"memjob_1","jobType":"PHASE2","jobKey":"phase2:1","generation":1,"status":"PENDING","createdAt":"2026-05-27T00:00:00Z"}]}""",
		)
		val artifacts = protocolJson.decodeFromString(
			MemoryArtifactsListResult.serializer(),
			"""{"artifacts":[{"artifactId":"memart_1","artifactType":"MEMORY_SUMMARY","artifactPath":"memory_summary.md","version":1,"tokenEstimate":100,"createdAt":"2026-05-27T00:00:00Z"}]}""",
		)

		assertEquals("phase2:1", jobs.jobs.single().jobKey)
		assertEquals("memory_summary.md", artifacts.artifacts.single().artifactPath)
	}
}
