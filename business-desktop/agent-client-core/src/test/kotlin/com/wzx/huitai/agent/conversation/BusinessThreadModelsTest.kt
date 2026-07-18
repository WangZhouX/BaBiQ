package com.wzx.huitai.agent.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
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
    fun `provider decoding exposes only safe metadata`() {
        val result = BusinessProviderCodec.decodeList(buildJsonObject {
            put("providers", json.parseToJsonElement(
                """[{"id":"provider-1","displayName":"Provider One","authMode":"api_key","hasApiKey":true,"active":true,"apiKey":"secret","baseUrl":"https://private","models":[{"id":"model-1","label":"Model One","active":true}]}]""",
            ))
        })

        val provider = result.single()
        assertEquals("provider-1", provider.id)
        assertEquals("Provider One", provider.displayName)
        assertEquals("api_key", provider.authMode)
        assertEquals(true, provider.hasApiKey)
        assertEquals(BusinessProviderModel("model-1", "Model One", active = true), provider.models.single())
        assertFalse(provider.toString().contains("secret"))
        assertFalse(provider.toString().contains("private"))
        assertNull(provider::class.members.singleOrNull { it.name == "apiKey" })
        assertNull(provider::class.members.singleOrNull { it.name == "baseUrl" })
    }

    private fun decode(value: String): BusinessThreadItem =
        BusinessThreadItemCodec.decode(json.parseToJsonElement(value))
}
