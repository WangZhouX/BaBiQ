package com.wzx.babiq.desktop.ui.settings

import com.wzx.babiq.desktop.protocol.ProviderInfo
import com.wzx.babiq.desktop.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsPanelTest {

	@Test
	fun `设置页标题栏提供返回聊天页入口`() {
		val model = buildSettingsHeaderModel()

		assertEquals("设置", model.title)
		assertEquals("← 返回对话", model.backLabel)
		assertEquals(Screen.Chat, model.backTarget)
	}

	@Test
	fun `设置页标签匹配 P3 原型的真实产品分区`() {
		val tabs = settingsTabs()

		assertEquals(
			listOf("Provider", "权限与审批", "本地 MCP", "记忆", "能力"),
			tabs.map { it.label },
		)
		assertEquals(SettingsTab.Provider, tabs.first().tab)
	}

	@Test
	fun `Provider 编辑草稿能从已有 Provider 回填关键字段`() {
		val draft = providerDraftFrom(
			ProviderInfo(
				id = "deepseek-main",
				label = "DeepSeek 主账号",
				displayName = "DeepSeek 主账号",
				type = "OPENAI_COMPATIBLE",
				baseUrl = "https://api.deepseek.com",
				model = "deepseek-v4-pro",
				contextWindow = 32768,
				hasApiKey = true,
			),
		)

		assertEquals("deepseek-main", draft.providerId)
		assertEquals("DeepSeek 主账号", draft.displayName)
		assertEquals("OPENAI_COMPATIBLE", draft.type)
		assertEquals("https://api.deepseek.com", draft.baseUrl)
		assertEquals("deepseek-v4-pro", draft.model)
		assertEquals("32768", draft.contextWindowText)
		assertEquals("", draft.apiKey)
	}

	@Test
	fun `审批策略选项包含原型中的全部询问`() {
		val options = approvalPolicyOptions()

		assertTrue(options.any { it.label == "全部询问" && it.value == "ALWAYS" })
		assertTrue(options.any { it.label == "永不询问" && it.value == "NEVER" })
	}
}
