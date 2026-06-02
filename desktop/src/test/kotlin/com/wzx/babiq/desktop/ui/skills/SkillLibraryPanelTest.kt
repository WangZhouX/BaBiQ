package com.wzx.babiq.desktop.ui.skills

import com.wzx.babiq.desktop.protocol.CapabilityInfo
import com.wzx.babiq.desktop.protocol.CapabilityStatusResult
import com.wzx.babiq.desktop.protocol.SkillInfo
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.SkillUiState
import com.wzx.babiq.desktop.state.WorkspaceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillLibraryPanelTest {

	@Test
	fun `技能页模型匹配 Figma 原型的标签 分区和统计`() {
		val model = buildSkillLibraryModel(sampleState())

		assertEquals("让 BaBiQ 按你的方式工作", model.title)
		assertEquals(listOf("插件", "技能"), model.tabs.map { it.label })
		assertTrue(model.tabs.single { it.label == "技能" }.selected)
		assertEquals(listOf("推荐", "系统", "项目", "个人"), model.sections.map { it.title })
		assertEquals(listOf("本地 3", "系统 1", "个人 1", "项目 1"), model.chips.map { it.label })
		assertEquals("找不到技能", model.sections.first { it.title == "推荐" }.emptyLabel)
		assertEquals("OpenAI Docs", model.sections.first { it.title == "系统" }.skills.single().title)
		assertEquals("项目规范", model.sections.first { it.title == "项目" }.skills.single().title)
	}

	@Test
	fun `技能页中文搜索无结果时保留创建入口`() {
		val model = buildSkillLibraryModel(sampleState(), query = "不存在的技能")

		assertTrue(model.searchPlaceholder.contains("搜索技能"))
		assertEquals("全部", model.filterLabel)
		assertEquals("找不到技能", model.filteredEmptyLabel)
		assertTrue(model.headerActions.contains("创建"))
		assertTrue(model.headerActions.contains("管理"))
	}

	@Test
	fun `技能详情未加载正文时显示按需注入和查看正文动作`() {
		val model = buildSkillLibraryModel(sampleState(), selectedSkillId = "skill.system.openai-docs")
		val detail = model.detail ?: error("应生成技能详情")

		assertEquals("OpenAI Docs", detail.title)
		assertEquals("system", detail.namespace)
		assertTrue(detail.badges.contains("按需注入"))
		assertTrue(detail.badges.contains("正文未加载"))
		assertEquals("查看正文", detail.primaryAction)
		assertEquals("打开目录", detail.secondaryAction)
		assertTrue(detail.injectionText.contains("只在用户显式请求"))
	}

	@Test
	fun `技能正文加载后显示截断状态和正文操作`() {
		val skill = systemSkill()
		val model = buildSkillLibraryModel(
			sampleState().copy(
				skillState = SkillUiState(
					skills = sampleSkills(),
					selectedSkillId = skill.id,
					selectedSkill = skill,
					selectedContent = "# OpenAI Docs\n\n官方文档检索。",
					selectedContentTruncated = true,
				),
			),
			selectedSkillId = skill.id,
		)
		val detail = model.detail ?: error("应生成技能详情")

		assertTrue(detail.badges.contains("已截断"))
		assertTrue(detail.badges.contains("本轮可注入"))
		assertEquals("刷新正文", detail.primaryAction)
		assertEquals("复制路径", detail.secondaryAction)
		assertTrue(detail.contentPreview.orEmpty().contains("官方文档检索"))
	}

	private fun sampleState(): AppState =
		AppState(
			workspace = WorkspaceContext(projectName = "BaBiQ", cwd = "E:\\BaBiQ"),
			skillState = SkillUiState(skills = sampleSkills()),
			capabilityState = CapabilityUiState(
				status = CapabilityStatusResult(
					totalCount = 3,
					enabledCount = 3,
					deferredCount = 3,
					capabilities = listOf(
						CapabilityInfo(
							capabilityId = "skill.system.openai-docs",
							type = "SKILL",
							namespace = "system",
							name = "openai-docs",
							displayName = "OpenAI Docs",
							description = "Reference OpenAI docs",
							exposureMode = "DEFERRED",
							enabled = true,
						),
					),
				),
			),
		)

	private fun sampleSkills(): List<SkillInfo> =
		listOf(
			systemSkill(),
			SkillInfo(
				id = "skill.project.rules",
				namespace = "project",
				name = "项目规范",
				description = "读取 BaBiQ 项目规则。",
				sourceDirectory = "E:\\BaBiQ\\.codex\\skills\\rules",
				skillFile = "E:\\BaBiQ\\.codex\\skills\\rules\\SKILL.md",
				contentHash = "hash-project",
			),
			SkillInfo(
				id = "skill.personal.cleaner",
				namespace = "personal",
				name = "AI Slop Cleaner",
				description = "Run cleanup and refactor workflow.",
				sourceDirectory = "C:\\Users\\86155\\.codex\\skills\\ai-slop-cleaner",
				skillFile = "C:\\Users\\86155\\.codex\\skills\\ai-slop-cleaner\\SKILL.md",
				contentHash = "hash-personal",
			),
		)

	private fun systemSkill(): SkillInfo =
		SkillInfo(
			id = "skill.system.openai-docs",
			namespace = "system",
			name = "OpenAI Docs",
			description = "Reference OpenAI docs and Codex self knowledge.",
			sourceDirectory = "C:\\Users\\86155\\.codex\\skills\\.system\\openai-docs",
			skillFile = "C:\\Users\\86155\\.codex\\skills\\.system\\openai-docs\\SKILL.md",
			contentHash = "hash-system",
			allowedTools = listOf("web.run"),
		)
}
