package com.wzx.babiq.desktop.ui.search

import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.MemoryReferenceInfo
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.protocol.SkillInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.state.SkillUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchPanelTest {

	@Test
	fun `搜索工作台必须是独立产品页模块`() {
		val module = runCatching {
			Class.forName("com.wzx.babiq.desktop.ui.search.SearchPanelKt")
		}.getOrNull()

		assertNotNull(module)
	}

	@Test
	fun `搜索工作台摘要来自长期记忆 能力目录和 Skill 状态`() {
		val model = buildSearchWorkbenchModel(
			AppState(
				memoryState = MemoryUiState(
					status = MemoryStatusResult(
						enabled = true,
						generateEnabled = true,
						readEnabled = true,
						rootDir = "E:\\BaBiQ\\.babiq\\memories",
						cleanCandidateCount = 7,
						phase2Generation = 3,
					),
					searchStrategy = "LUCENE",
					searchResults = listOf(MemoryReferenceInfo("memart_1", "HIGH", "长期记忆引用", 24)),
					searchTokenEstimate = 24,
				),
				capabilityState = CapabilityUiState(
					status = CapabilityStatusResult(
						totalCount = 12,
						visibleCount = 4,
						deferredCount = 8,
						capabilities = listOf(
							CapabilityInfo("local.read_file", "LOCAL_TOOL", "local", "read_file", "读取文件", "Read", "VISIBLE", true),
						),
					),
				),
				skillState = SkillUiState(
					skills = listOf(
						SkillInfo("skill.plan", "local", "plan", "计划", "E:\\skills", "E:\\skills\\SKILL.md", "hash"),
					),
				),
			),
		)

		assertTrue(model.memoryLabel.contains("CLEAN 7"))
		assertEquals("记忆检索: 1 条 · LUCENE · 24 token", model.memoryResultLabel)
		assertEquals("能力目录: 12 个 · 常驻 4 · 按需 8", model.capabilityLabel)
		assertEquals("Skill: 1 个 metadata", model.skillLabel)
	}
}
