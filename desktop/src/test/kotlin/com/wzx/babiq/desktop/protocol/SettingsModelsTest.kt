package com.wzx.babiq.desktop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
		assertFalse(protocolJson.encodeToString(response).contains("sk-secret"))
	}
}
