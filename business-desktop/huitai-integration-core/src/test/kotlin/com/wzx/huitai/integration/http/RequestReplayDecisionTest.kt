package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RequestReplayDecisionTest {
    @Test
    fun `request carries exact transport replay and reconciliation metadata`() {
        val body = "payload".encodeToByteArray()
        val request = HuitaiRequest(
            method = "POST",
            relativePath = "/framework/example",
            headers = mapOf("X-Execution-Id" to "execution-1"),
            body = body,
            replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
            executionId = "execution-1",
            idempotencyHeaderName = "X-Execution-Id",
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )

        assertEquals("POST", request.method)
        assertEquals("/framework/example", request.relativePath)
        assertEquals(mapOf("X-Execution-Id" to "execution-1"), request.headers)
        assertContentEquals(body, request.body)
        assertEquals(ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED, request.replayPolicy)
        assertEquals("execution-1", request.executionId)
        assertEquals("X-Execution-Id", request.idempotencyHeaderName)
        assertEquals(ReconciliationPolicy.QUERY_REMOTE, request.reconciliationPolicy)
    }

    @Test
    fun `request owns headers and body and never renders their secret values`() {
        val mutableHeaders = linkedMapOf("Authorization" to "Bearer top-secret-token")
        val mutableBody = "top-secret-body".encodeToByteArray()
        val request = HuitaiRequest(
            method = "POST",
            relativePath = "/framework/example?access_token=top-secret-query",
            headers = mutableHeaders,
            body = mutableBody,
            replayPolicy = ActionReplayPolicy.NEVER,
            executionId = "top-secret-execution",
            idempotencyHeaderName = "X-Secret-Header",
            reconciliationPolicy = ReconciliationPolicy.MANUAL,
        )

        mutableHeaders["Authorization"] = "changed"
        mutableBody[0] = 0x00
        request.body[1] = 0x00

        assertEquals("Bearer top-secret-token", request.headers["Authorization"])
        assertContentEquals("top-secret-body".encodeToByteArray(), request.body)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (request.headers as MutableMap<String, String>)["Authorization"] = "changed-again"
        }
        val rendered = request.toString()
        listOf(
            "top-secret-token",
            "top-secret-body",
            "top-secret-query",
            "top-secret-execution",
            "X-Secret-Header",
        ).forEach { secret -> assertFalse(secret in rendered, rendered) }
    }

    @Test
    fun `request rejects unsafe relative paths`() {
        listOf(
            "//untrusted.example/path",
            "https://untrusted.example/path",
            "/framework/example#fragment",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                request(replayPolicy = ActionReplayPolicy.NEVER, relativePath = path)
            }
        }
    }

    @Test
    fun `request rejects whitespace control and non-token HTTP methods`() {
        listOf(" ", "PO ST", "POST\r\n", "POST/").forEach { method ->
            assertFailsWith<IllegalArgumentException>(method) {
                request(replayPolicy = ActionReplayPolicy.NEVER, method = method)
            }
        }
    }

    @Test
    fun `request rejects invalid HTTP header names`() {
        listOf("", " ", "Bad Header", "Bad:Header", "Bad\r\nHeader", "非ASCII").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) {
                request(
                    replayPolicy = ActionReplayPolicy.NEVER,
                    headers = mapOf(name to "value"),
                )
            }
        }
    }

    @Test
    fun `request rejects CRLF in HTTP header values`() {
        listOf("value\rspoofed", "value\nspoofed", "value\r\nInjected: true").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                request(
                    replayPolicy = ActionReplayPolicy.NEVER,
                    headers = mapOf("X-Custom-Header" to value),
                )
            }
        }
    }

    @Test
    fun `request preserves valid custom HTTP headers`() {
        val headers = mapOf(
            "X-Custom-Header" to "custom-value",
            "traceparent" to "00-trace-parent-01",
        )

        val request = request(
            replayPolicy = ActionReplayPolicy.NEVER,
            headers = headers,
        )

        assertEquals(headers, request.headers)
    }

    @Test
    fun `response received refresh marker defaults false and preserves explicit true`() {
        assertFalse(HuitaiTransportOutcome.ResponseReceived(httpStatus = 401).authenticationRefreshCompleted)
        assertEquals(
            true,
            HuitaiTransportOutcome.ResponseReceived(
                httpStatus = 401,
                authenticationRefreshCompleted = true,
            ).authenticationRefreshCompleted,
        )
    }

    @Test
    fun `NotSent retries without reconciliation for every replay policy`() {
        ActionReplayPolicy.entries.forEach { replayPolicy ->
            val decision = RequestReplayDecision.decide(
                request = request(replayPolicy = replayPolicy),
                outcome = HuitaiTransportOutcome.NotSent,
            )

            assertIs<RequestReplayDecision.RetryWithoutReconciliation>(
                decision,
                replayPolicy.name,
            )
        }
    }

    @Test
    fun `ambiguous SAFE request may replay`() {
        val decision = RequestReplayDecision.decide(
            request = request(replayPolicy = ActionReplayPolicy.SAFE),
            outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
        )

        assertIs<RequestReplayDecision.Replay>(decision)
    }

    @Test
    fun `ambiguous keyed request replays only with attached matching execution id`() {
        val decision = RequestReplayDecision.decide(
            request = request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
            outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
        )

        assertIs<RequestReplayDecision.Replay>(decision)
    }

    @Test
    fun `idempotency header names match case-insensitively`() {
        val request = request(
            replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
            headers = mapOf("x-execution-id" to EXECUTION_ID),
            executionId = EXECUTION_ID,
            idempotencyHeaderName = "X-Execution-Id",
        )

        assertIs<RequestReplayDecision.Replay>(
            RequestReplayDecision.decide(
                request = request,
                outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
            ),
        )
        assertIs<RequestReplayDecision.Replay>(
            RequestReplayDecision.decide(
                request = request,
                outcome = HuitaiTransportOutcome.ResponseReceived(
                    httpStatus = 401,
                    authenticationRefreshCompleted = true,
                ),
            ),
        )
    }

    @Test
    fun `conflicting case variants of idempotency header fail closed`() {
        val request = request(
            replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
            headers = linkedMapOf(
                "X-Execution-Id" to EXECUTION_ID,
                "x-execution-id" to "different-execution",
            ),
            executionId = EXECUTION_ID,
            idempotencyHeaderName = "X-Execution-Id",
        )

        assertIs<RequestReplayDecision.OutcomeUnknown>(
            RequestReplayDecision.decide(
                request = request,
                outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
            ),
        )
        assertIs<RequestReplayDecision.AuthExpiredNoReplay>(
            RequestReplayDecision.decide(
                request = request,
                outcome = HuitaiTransportOutcome.ResponseReceived(
                    httpStatus = 499,
                    authenticationRefreshCompleted = true,
                ),
            ),
        )
    }

    @Test
    fun `ambiguous keyed request without exact idempotency attachment becomes outcome unknown`() {
        val invalidRequests = listOf(
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = null,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = "",
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = null,
            ),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = emptyMap(),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to "different-execution"),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
        )

        invalidRequests.forEachIndexed { index, request ->
            val decision = RequestReplayDecision.decide(
                request = request,
                outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
            )

            val unknown = assertIs<RequestReplayDecision.OutcomeUnknown>(decision, "case $index")
            assertEquals(ReconciliationPolicy.QUERY_REMOTE, unknown.reconciliationPolicy)
        }
    }

    @Test
    fun `ambiguous NEVER request becomes outcome unknown`() {
        val decision = RequestReplayDecision.decide(
            request = request(
                replayPolicy = ActionReplayPolicy.NEVER,
                reconciliationPolicy = ReconciliationPolicy.MANUAL,
            ),
            outcome = HuitaiTransportOutcome.AmbiguousAfterSend,
        )

        val unknown = assertIs<RequestReplayDecision.OutcomeUnknown>(decision)
        assertEquals(ReconciliationPolicy.MANUAL, unknown.reconciliationPolicy)
    }

    @Test
    fun `received auth expiry replays SAFE and correctly keyed requests only after refresh`() {
        listOf(401, 499).forEach { statusCode ->
            val safe = request(replayPolicy = ActionReplayPolicy.SAFE)
            val keyed = request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            )

            listOf(safe, keyed).forEach { request ->
                assertIs<RequestReplayDecision.NoReplay>(
                    RequestReplayDecision.decide(
                        request = request,
                        outcome = HuitaiTransportOutcome.ResponseReceived(
                            httpStatus = statusCode,
                            authenticationRefreshCompleted = false,
                        ),
                    ),
                )
                assertIs<RequestReplayDecision.Replay>(
                    RequestReplayDecision.decide(
                        request = request,
                        outcome = HuitaiTransportOutcome.ResponseReceived(
                            httpStatus = statusCode,
                            authenticationRefreshCompleted = true,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `received auth expiry never replays unsafe requests`() {
        val unsafeRequests = listOf(
            request(replayPolicy = ActionReplayPolicy.NEVER),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = emptyMap(),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
        )

        listOf(401, 499).forEach { statusCode ->
            unsafeRequests.forEach { request ->
                val decision = RequestReplayDecision.decide(
                    request = request,
                    outcome = HuitaiTransportOutcome.ResponseReceived(
                        httpStatus = statusCode,
                        authenticationRefreshCompleted = true,
                    ),
                )

                assertIs<RequestReplayDecision.AuthExpiredNoReplay>(decision)
            }
        }
    }

    @Test
    fun `ordinary received response does not retry or require reconciliation`() {
        ActionReplayPolicy.entries.forEach { replayPolicy ->
            val decision = RequestReplayDecision.decide(
                request = request(replayPolicy = replayPolicy),
                outcome = HuitaiTransportOutcome.ResponseReceived(httpStatus = 200),
            )

            assertIs<RequestReplayDecision.NoReplay>(decision, replayPolicy.name)
        }
    }

    private fun request(
        replayPolicy: ActionReplayPolicy,
        method: String = "POST",
        relativePath: String = "/framework/example",
        headers: Map<String, String> = emptyMap(),
        executionId: String? = null,
        idempotencyHeaderName: String? = null,
        reconciliationPolicy: ReconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
    ) = HuitaiRequest(
        method = method,
        relativePath = relativePath,
        headers = headers,
        body = "{}".encodeToByteArray(),
        replayPolicy = replayPolicy,
        executionId = executionId,
        idempotencyHeaderName = idempotencyHeaderName,
        reconciliationPolicy = reconciliationPolicy,
    )

    private companion object {
        const val EXECUTION_ID = "execution-1"
        const val IDEMPOTENCY_HEADER = "X-Execution-Id"
    }
}
