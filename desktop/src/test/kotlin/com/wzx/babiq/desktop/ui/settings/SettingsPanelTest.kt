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
				authMode = "api_key",
				baseUrl = "https://api.deepseek.com",
				model = "deepseek-v4-pro",
				contextWindow = 32768,
				hasApiKey = true,
			),
		)

		assertEquals("deepseek-main", draft.providerId)
		assertEquals("DeepSeek 主账号", draft.displayName)
		assertEquals("OPENAI_COMPATIBLE", draft.type)
		assertEquals("api_key", draft.authMode)
		assertEquals("https://api.deepseek.com", draft.baseUrl)
		assertEquals("deepseek-v4-pro", draft.model)
		assertEquals("32768", draft.contextWindowText)
		assertEquals("", draft.apiKey)
	}

	@Test
	fun `Provider 编辑草稿保留 Anthropic OAuth CLI 认证模式`() {
		val draft = providerDraftFrom(
			ProviderInfo(
				id = "claude-oauth",
				label = "Claude OAuth",
				displayName = "Claude OAuth",
				type = "ANTHROPIC",
				authMode = "oauth_cli",
				baseUrl = "https://api.anthropic.com",
				model = "claude-sonnet-4-6",
				contextWindow = 1000000,
				hasApiKey = false,
			),
		)

		assertEquals("ANTHROPIC", draft.type)
		assertEquals("oauth_cli", draft.authMode)
		assertEquals("https://api.anthropic.com", draft.baseUrl)
		assertEquals("claude-sonnet-4-6", draft.model)
		assertEquals("1000000", draft.contextWindowText)
		assertEquals("", draft.apiKey)
	}

	@Test
	fun `Anthropic OAuth 预设填入官方端点和 Claude 4 模型`() {
		val draft = anthropicOAuthProviderPreset()

		assertEquals("claude-oauth", draft.providerId)
		assertEquals("ANTHROPIC", draft.type)
		assertEquals("oauth_cli", draft.authMode)
		assertEquals("https://api.anthropic.com", draft.baseUrl)
		assertEquals("claude-sonnet-4-6", draft.model)
		assertEquals("1000000", draft.contextWindowText)
		assertEquals("", draft.apiKey)
	}

	@Test
	fun `Provider 预设包含 P7 五类入口`() {
		val presets = providerPresets()

		assertEquals(
			listOf("Claude 官方 API Key", "Claude 官方 OAuth", "DeepSeek 官方", "阿里百炼", "OpenAI 兼容中转"),
			presets.map { it.label },
		)
		assertEquals("api_key", presets.first { it.label == "Claude 官方 API Key" }.draft.authMode)
		assertEquals("oauth_cli", presets.first { it.label == "Claude 官方 OAuth" }.draft.authMode)
		assertEquals("ANTHROPIC", presets.first { it.label == "Claude 官方 API Key" }.draft.type)
		assertEquals("OPENAI_COMPATIBLE", presets.first { it.label == "DeepSeek 官方" }.draft.type)
		assertEquals("DASHSCOPE", presets.first { it.label == "阿里百炼" }.draft.type)
	}

	@Test
	fun `复制 Provider 会保留非敏感字段并清空 API Key`() {
		val draft = copyProviderDraftFrom(
			ProviderInfo(
				id = "claude-oauth",
				label = "Claude OAuth",
				displayName = "Claude OAuth",
				type = "ANTHROPIC",
				authMode = "oauth_cli",
				baseUrl = "https://api.anthropic.com",
				model = "claude-sonnet-4-6",
				contextWindow = 1000000,
				hasApiKey = false,
			),
		)

		assertEquals("claude-oauth-copy", draft.providerId)
		assertEquals("Claude OAuth 副本", draft.displayName)
		assertEquals("ANTHROPIC", draft.type)
		assertEquals("oauth_cli", draft.authMode)
		assertEquals("https://api.anthropic.com", draft.baseUrl)
		assertEquals("", draft.apiKey)
	}

	@Test
	fun `审批策略选项包含原型中的全部询问`() {
		val options = approvalPolicyOptions()

		assertTrue(options.any { it.label == "全部询问" && it.value == "ALWAYS" })
		assertTrue(options.any { it.label == "永不询问" && it.value == "NEVER" })
	}
}
