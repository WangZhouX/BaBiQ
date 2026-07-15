package com.wzx.huitai.security.audit

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.port.ActionAuditEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class InMemoryActionAuditPortTest {
    @Test
    fun `按execution保留只追加顺序和完整审计schema`() = runTest {
        val port = InMemoryActionAuditPort()
        port.append(event(sequence = 1, to = ActionExecutionState.EXECUTING))
        port.append(event(sequence = 2, from = ActionExecutionState.EXECUTING, to = ActionExecutionState.SUCCEEDED))

        val events = port.events("execution-1")
        val payload = events.last().redactedPayload

        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertEquals("demo.submit", payload["actionId"]!!.jsonPrimitive.content)
        assertEquals("1", payload["actionVersion"]!!.jsonPrimitive.content)
        REQUIRED_PAYLOAD_FIELDS.forEach { field -> kotlin.test.assertNotNull(payload[field], field) }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (events as MutableList<ActionAuditEvent>).clear()
        }
        assertEquals(2, port.events("execution-1").size)
    }

    @Test
    fun `追加时重新脱敏并冻结调用方payload`() = runTest {
        val secret = "raw-token-value"
        val port = InMemoryActionAuditPort()
        val source = event(
            sequence = 1,
            to = ActionExecutionState.EXECUTING,
            payload = auditPayload(secret),
        )

        port.append(source)

        val stored = port.events("execution-1").single()
        assertFalse(secret in stored.redactedPayload.toString())
        assertEquals(AuditRedactor.REDACTED, stored.redactedPayload["accessToken"]!!.jsonPrimitive.content)
        assertFalse(stored === source)
    }

    @Test
    fun `重复倒序和跳号事件均fail closed且不改变历史`() = runTest {
        val port = InMemoryActionAuditPort()
        port.append(event(sequence = 1, to = ActionExecutionState.EXECUTING))

        listOf(1L, 0L, 3L).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                port.append(event(sequence = invalid, from = ActionExecutionState.EXECUTING, to = ActionExecutionState.SUCCEEDED))
            }
        }

        assertEquals(listOf(1L), port.events("execution-1").map { it.sequence })
    }

    private fun event(
        sequence: Long,
        from: ActionExecutionState? = null,
        to: ActionExecutionState,
        payload: kotlinx.serialization.json.JsonObject = auditPayload("token-secret"),
    ) = ActionAuditEvent(
        executionId = "execution-1",
        sequence = sequence,
        fromState = from,
        toState = to,
        type = "state_transition",
        redactedPayload = payload,
        actorId = "reviewer-1",
        occurredAt = Instant.parse("2026-07-14T00:00:0${sequence}Z"),
    )

    private fun auditPayload(token: String) = buildJsonObject {
        put("actionId", "demo.submit")
        put("actionVersion", 1)
        put("origin", "agent")
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("toolCallId", "tool-call-1")
        put("userId", "user-1")
        put("tenantId", "tenant-1")
        put("platformId", "platform-1")
        put("authSessionId", "auth-1")
        put("desktopInstanceId", "desktop-1")
        put("pageId", "page-1")
        put("contextRevision", 7)
        put("risk", "high_risk")
        put("approvalId", "approval-1")
        put("approvalDecision", "approved")
        put("requestedAt", "2026-07-14T00:00:00Z")
        put("decidedAt", "2026-07-14T00:00:01Z")
        put("remoteReference", "remote-1")
        put("terminalStatus", "succeeded")
        put("errorCode", "")
        put("accessToken", token)
    }

    private companion object {
        val REQUIRED_PAYLOAD_FIELDS = setOf(
            "actionId", "actionVersion", "origin", "threadId", "turnId", "toolCallId",
            "userId", "tenantId", "platformId", "authSessionId", "desktopInstanceId",
            "pageId", "contextRevision", "risk", "approvalId", "approvalDecision",
            "requestedAt", "decidedAt", "remoteReference", "terminalStatus", "errorCode",
        )
    }
}
