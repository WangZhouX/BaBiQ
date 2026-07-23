package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.http.CommonResult
import com.wzx.huitai.integration.http.HuitaiRequest
import com.wzx.huitai.integration.http.HuitaiResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull

class ReadyAuthenticatedHuitaiClientTest {
    @Test
    fun `request keeps the exact permitted identity when login switches before delegate completes`() = runTest {
        val oldIdentity = identity("old-session", 1, "old-user", "old-tenant")
        val registry = BusinessIdentityRegistry().also { check(it.publishReady(oldIdentity, 0)) }
        val entered = CompletableDeferred<BusinessIdentity>()
        val release = CompletableDeferred<Unit>()
        val client = ReadyAuthenticatedHuitaiClient(
            gate = ReadyAuthenticatedHttpGate(ReadyAgentUsageGate(registry), {}, {}),
            sendAuthenticated = { _, identity ->
                entered.complete(identity)
                release.await()
                HuitaiResponse.Success(CommonResult("0", "ok", JsonNull))
            },
            closeDelegate = {},
        )

        val pending = async { client.send(request()) }
        val captured = entered.await()
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        check(registry.publishReady(identity("new-session", 2, "new-user", "new-tenant"), 1))
        release.complete(Unit)

        assertEquals(oldIdentity, captured)
        assertNull(pending.await())
        client.close()
    }

    private fun request() = HuitaiRequest(
        method = "POST",
        relativePath = "/case/create",
        headers = emptyMap(),
        body = "{}".encodeToByteArray(),
        replayPolicy = ActionReplayPolicy.NEVER,
        executionId = null,
        idempotencyHeaderName = null,
        reconciliationPolicy = ReconciliationPolicy.NONE,
    )

    private fun identity(
        authSessionId: String,
        epoch: Long,
        userId: String,
        tenantId: String,
    ) = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = authSessionId,
        identityEpoch = epoch,
        userId = userId,
        tenantId = tenantId,
        platformId = "1",
        roles = setOf("lawyer"),
        permissions = setOf("case:write"),
    )
}
