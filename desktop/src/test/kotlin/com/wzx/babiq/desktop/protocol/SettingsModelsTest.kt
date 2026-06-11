package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString

class SettingsModelsTest {

	@Test
	fun `settings 响应可以解析后端当前设置`() {
		val json = """
			{
			  "activeProviderId": "deepseek-official",
			  "sandboxMode": "DANGER_FULL_ACCESS",
			  "approvalPolicy": "ON_REQUEST",
			  "defaultCwd": "E:\\BaBiQ",
			  "futureField": "ignored"
			}
		""".trimIndent()

		val result = protocolJson.decodeFromString(AppSettingsResult.serializer(), json)

		assertEquals("deepseek-official", result.activeProviderId)
		assertEquals("DANGER_FULL_ACCESS", result.sandboxMode)
		assertEquals("ON_REQUEST", result.approvalPolicy)
		assertEquals("E:\\BaBiQ", result.defaultCwd)
	}

	@Test
	fun `provider 保存参数会编码 apiKey 但响应模型不包含明文字段`() {
		val params = ProviderSaveParams(
			providerId = "custom-openai",
			displayName = "自定义 OpenAI",
			type = "OPENAI_COMPATIBLE",
			authMode = "api_key",
			baseUrl = "https://relay.example.com/v1",
			model = "deepseek-chat",
			apiKey = "sk-secret",
			contextWindow = 128000,
			enabled = true,
		)

		val encoded = protocolJson.encodeToString(params)
		val response = protocolJson.decodeFromString(
			ProviderMutationResult.serializer(),
			"""
				{
				  "id": "custom-openai",
				  "label": "自定义 OpenAI",
				  "displayName": "自定义 OpenAI",
				  "type": "OPENAI_COMPATIBLE",
				  "authMode": "api_key",
				  "baseUrl": "https://relay.example.com/v1",
				  "model": "deepseek-chat",
				  "contextWindow": 128000,
				  "enabled": true,
				  "hasApiKey": true,
				  "active": false,
				  "models": [{ "id": "deepseek-chat", "label": "deepseek-chat", "active": false }]
				}
			""".trimIndent(),
		)

		assertEquals(true, encoded.contains("sk-secret"))
		assertEquals("custom-openai", response.id)
		assertEquals("自定义 OpenAI", response.displayName)
		assertEquals("api_key", response.authMode)
		assertFalse(protocolJson.encodeToString(response).contains("sk-secret"))
	}

	@Test
	fun `Anthropic OAuth Provider 保存参数允许不携带 apiKey`() {
		val params = ProviderSaveParams(
			providerId = "claude-oauth",
			displayName = "Claude OAuth",
			type = "ANTHROPIC",
			authMode = "oauth_cli",
			baseUrl = "https://api.anthropic.com",
			model = "claude-sonnet-4-6",
			apiKey = null,
			contextWindow = 1000000,
			enabled = true,
		)

		val encoded = protocolJson.encodeToString(params)
		val response = protocolJson.decodeFromString(
			ProviderMutationResult.serializer(),
			"""
				{
				  "id": "claude-oauth",
				  "label": "Claude OAuth",
				  "displayName": "Claude OAuth",
				  "type": "ANTHROPIC",
				  "authMode": "oauth_cli",
				  "baseUrl": "https://api.anthropic.com",
				  "model": "claude-sonnet-4-6",
				  "contextWindow": 1000000,
				  "enabled": true,
				  "hasApiKey": false,
				  "active": false
				}
			""".trimIndent(),
		)

		assertEquals(true, encoded.contains("oauth_cli"))
		assertFalse(encoded.contains("apiKey"))
		assertEquals("ANTHROPIC", response.type)
		assertEquals("oauth_cli", response.authMode)
		assertNull(response.apiKey)
	}

	@Test
	fun `Provider OAuth 状态和登录响应可以解析`() {
		val status = protocolJson.decodeFromString(
			ProviderOAuthStatusResult.serializer(),
			"""
				{
				  "providerType": "ANTHROPIC",
				  "authMode": "oauth_cli",
				  "cliInstalled": true,
				  "loggedIn": true,
				  "message": "Claude CLI OAuth 可用"
				}
			""".trimIndent(),
		)
		val login = protocolJson.decodeFromString(
			ProviderOAuthLoginResult.serializer(),
			"""
				{
				  "ok": true,
				  "pid": 12345,
				  "message": "已打开 Claude CLI 登录流程"
				}
			""".trimIndent(),
		)

		assertEquals("ANTHROPIC", status.providerType)
		assertEquals("oauth_cli", status.authMode)
		assertEquals(true, status.cliInstalled)
		assertEquals(true, status.loggedIn)
		assertEquals(true, login.ok)
		assertEquals(12345L, login.pid)
	}
}
