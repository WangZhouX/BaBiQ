package com.wzx.huitai.agent.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BusinessThreadModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes the six business desktop item families`() {
        assertEquals(
            BusinessThreadItem.UserMessage("user-1", "hello"),
            decode("""{"id":"user-1","type":"userMessage","text":"hello"}"""),
        )
        assertEquals(
            BusinessThreadItem.AgentMessage("agent-1", text = null, textDelta = "partial"),
            decode("""{"id":"agent-1","type":"agentMessage","textDelta":"partial"}"""),
        )
        assertEquals(
            BusinessThreadItem.Reasoning("reason-1", "checking"),
            decode("""{"id":"reason-1","type":"reasoning","text":"checking"}"""),
        )

        val plan = assertIs<BusinessThreadItem.Plan>(decode(
            """{"id":"plan-1","type":"plan","goal":"finish","steps":[{"order":1,"description":"inspect","status":"in_progress","activeForm":"inspecting"}]}""",
        ))
        assertEquals("finish", plan.goal)
        assertEquals(BusinessPlanStep(1, "inspect", "in_progress", "inspecting"), plan.steps.single())

        val action = assertIs<BusinessThreadItem.ApplicationAction>(decode(
            """{"id":"action-1","type":"applicationAction","executionId":"exec-1","actionId":"demo.save_draft","title":"save","risk":"SAFE","status":"executing","previewSummary":"draft","durationMs":12}""",
        ))
        assertEquals("exec-1", action.executionId)
        assertEquals("executing", action.status)

        val summary = assertIs<BusinessThreadItem.TurnSummary>(decode(
            """{"id":"summary-1","type":"turnSummary","status":"completed","model":"model-a","promptTokens":10,"completionTokens":4,"totalTokens":14,"toolCalls":2,"durationMs":30}""",
        ))
        assertEquals(14, summary.totalTokens)
        assertEquals(2, summary.toolCalls)
    }

    @Test
    fun `decodes stable attachment metadata and accepts attachment only user messages`() {
        val localPath = "C:\\private\\contracts\\contract.pdf"

        val item = assertIs<BusinessThreadItem.UserMessage>(decode(
            """
                {
                  "id":"user-attachment-1",
                  "type":"userMessage",
                  "text":"",
                  "attachments":[{
                    "id":"5e4d4e7a-7dd6-4c6e-bec4-bd6f92ec9123",
                    "displayId":"A-7K3M2Q",
                    "name":"contract.pdf",
                    "mediaType":"application/pdf",
                    "sizeBytes":1024,
                    "sha256":"${"a".repeat(64)}",
                    "source":"SELECTED_FILE",
                    "localPath":"C:\\private\\contracts\\contract.pdf"
                  }]
                }
            """.trimIndent(),
        ))

        assertEquals("", item.text)
        assertEquals(
            BusinessMessageAttachment(
                id = "5e4d4e7a-7dd6-4c6e-bec4-bd6f92ec9123",
                displayId = "A-7K3M2Q",
                name = "contract.pdf",
                mediaType = "application/pdf",
                sizeBytes = 1_024,
                sha256 = "a".repeat(64),
                source = "SELECTED_FILE",
                localPath = localPath,
            ),
            item.attachments.single(),
        )
        assertFalse(item.toString().contains(localPath))
        assertFalse(item.attachments.single().toString().contains(localPath))
    }

    @Test
    fun `historical user messages missing attachments normalize to empty list`() {
        val item = assertIs<BusinessThreadItem.UserMessage>(
            decode("""{"id":"user-legacy","type":"userMessage","text":"hello"}"""),
        )

        assertEquals(emptyList(), item.attachments)
    }

    @Test
    fun `unknown item is forward compatible without retaining raw secret payload`() {
        val unknown = assertIs<BusinessThreadItem.Unknown>(decode(
            """{"id":"future-1","type":"futureItem","apiKey":"secret","nested":{"token":"secret"}}""",
        ))

        assertEquals("future-1", unknown.id)
        assertEquals("futureItem", unknown.type)
        assertFalse(unknown.toString().contains("secret"))

        val withoutId = assertIs<BusinessThreadItem.Unknown>(decode(
            """{"type":"anotherFutureItem","token":"secret"}""",
        ))
        assertEquals("unknown:anotherFutureItem", withoutId.id)
    }

    @Test
    fun `provider decoding exposes complete safe metadata and projects the configured model`() {
        val result = BusinessProviderCodec.decodeList(buildJsonObject {
            put("providers", json.parseToJsonElement(
                """[{"id":"provider-1","displayName":"Provider One","type":"OPENAI_COMPATIBLE","authMode":"api_key","baseUrl":"https://relay.example.com/v1","model":"kimi-k3","contextWindow":131072,"enabled":true,"hasApiKey":true,"active":true,"apiKey":"sk-fake-sensitive-marker"}]""",
            ))
        })

        val provider = result.single()
        assertEquals("provider-1", provider.id)
        assertEquals("Provider One", provider.displayName)
        assertEquals("OPENAI_COMPATIBLE", provider.type)
        assertEquals("api_key", provider.authMode)
        assertEquals("https://relay.example.com/v1", provider.baseUrl)
        assertEquals("kimi-k3", provider.model)
        assertEquals(131072, provider.contextWindow)
        assertEquals(true, provider.enabled)
        assertEquals(true, provider.hasApiKey)
        assertEquals(true, provider.active)
        assertEquals(BusinessProviderModel("kimi-k3", "kimi-k3", active = true), provider.models.single())
        assertFalse(provider.toString().contains("sk-fake-sensitive-marker"))
        assertNull(provider::class.members.singleOrNull { it.name == "apiKey" })
    }

    @Test
    fun `provider decoding keeps safe defaults but requires id and model`() {
        val defaulted = BusinessProviderCodec.decodeList(buildJsonObject {
            put("providers", json.parseToJsonElement(
                """[{"id":"minimal","model":"custom-model","active":false,"hasApiKey":false,"enabled":true}]""",
            ))
        }).single()

        assertEquals("minimal", defaulted.displayName)
        assertEquals("UNKNOWN", defaulted.type)
        assertEquals("api_key", defaulted.authMode)
        assertEquals("", defaulted.baseUrl)
        assertEquals(0, defaulted.contextWindow)
        assertEquals(true, defaulted.enabled)
        assertFalse(defaulted.hasApiKey)
        assertFalse(defaulted.active)
        assertEquals(BusinessProviderModel("custom-model", "custom-model"), defaulted.models.single())

        assertFailsWith<SerializationException> {
            BusinessProviderCodec.decodeList(buildJsonObject {
                put("providers", json.parseToJsonElement("""[{"model":"custom-model"}]"""))
            })
        }
        assertFailsWith<SerializationException> {
            BusinessProviderCodec.decodeList(buildJsonObject {
                put("providers", json.parseToJsonElement("""[{"id":"missing-model"}]"""))
            })
        }
    }

    @Test
    fun `provider status fields require genuine JSON booleans`() {
        val provider = buildJsonObject {
            put("id", "relay")
            put("model", "kimi-k3")
            put("active", false)
            put("hasApiKey", true)
            put("enabled", true)
        }
        listOf("active", "hasApiKey", "enabled").forEach { field ->
            assertRejectsMissingAndMalformedBoolean(provider, field, BusinessProviderCodec::decodeProvider)
        }

        val deleted = buildJsonObject {
            put("ok", true)
            put("providerId", "relay")
            put("activeProviderId", "fallback")
        }
        assertRejectsMissingAndMalformedBoolean(deleted, "ok", BusinessProviderCodec::decodeDeleteResult)

        val tested = buildJsonObject {
            put("ok", true)
            put("providerId", "relay")
            put("message", "Provider 配置可用")
        }
        assertRejectsMissingAndMalformedBoolean(tested, "ok", BusinessProviderCodec::decodeTestResult)

        val oauthStatus = buildJsonObject {
            put("providerType", "ANTHROPIC")
            put("authMode", "oauth_cli")
            put("cliInstalled", true)
            put("loggedIn", false)
            put("message", "未登录")
        }
        listOf("cliInstalled", "loggedIn").forEach { field ->
            assertRejectsMissingAndMalformedBoolean(oauthStatus, field, BusinessProviderCodec::decodeOAuthStatus)
        }

        val oauthLogin = buildJsonObject {
            put("ok", true)
            put("pid", 12345L)
            put("message", "登录已启动")
        }
        assertRejectsMissingAndMalformedBoolean(oauthLogin, "ok", BusinessProviderCodec::decodeOAuthLoginResult)
    }

    @Test
    fun `provider operation result codecs require and preserve backend safe messages`() {
        val tested = BusinessProviderCodec.decodeTestResult(buildJsonObject {
            put("ok", true)
            put("providerId", "relay")
            put("message", "Provider 配置可用")
        })
        val oauthStatus = BusinessProviderCodec.decodeOAuthStatus(buildJsonObject {
            put("providerType", "ANTHROPIC")
            put("authMode", "oauth_cli")
            put("cliInstalled", true)
            put("loggedIn", false)
            put("message", "未登录")
        })
        val oauthLogin = BusinessProviderCodec.decodeOAuthLoginResult(buildJsonObject {
            put("ok", true)
            put("pid", 12345L)
            put("message", "登录已启动")
        })

        assertEquals("Provider 配置可用", tested.message)
        assertEquals("未登录", oauthStatus.message)
        assertEquals("登录已启动", oauthLogin.message)

        assertFailsWith<SerializationException> {
            BusinessProviderCodec.decodeTestResult(buildJsonObject {
                put("ok", true)
                put("providerId", "relay")
            })
        }
        assertFailsWith<SerializationException> {
            BusinessProviderCodec.decodeOAuthStatus(buildJsonObject {
                put("loggedIn", false)
            })
        }
        assertFailsWith<SerializationException> {
            BusinessProviderCodec.decodeOAuthLoginResult(buildJsonObject {
                put("ok", true)
            })
        }
    }

    private fun decode(value: String): BusinessThreadItem =
        BusinessThreadItemCodec.decode(json.parseToJsonElement(value))

    private fun assertRejectsMissingAndMalformedBoolean(
        payload: JsonObject,
        field: String,
        decode: (JsonObject) -> Any,
    ) {
        val invalidPayloads = listOf(
            JsonObject(payload - field),
            JsonObject(payload + (field to JsonPrimitive("true"))),
            JsonObject(payload + (field to JsonPrimitive(1))),
        )
        invalidPayloads.forEach { invalid ->
            assertFailsWith<SerializationException>("field=$field payload=$invalid") {
                decode(invalid)
            }
        }
    }
}
