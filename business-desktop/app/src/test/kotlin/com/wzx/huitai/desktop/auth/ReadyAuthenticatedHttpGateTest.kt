package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.http.HuitaiResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ReadyAuthenticatedHttpGateTest {
    @Test
    fun `non ready state rejects before HTTP send`() = runTest {
        val registry = BusinessIdentityRegistry().apply { transitionTo(BusinessAccessGateState.RESTORING) }
        var sends = 0
        val gate = ReadyAuthenticatedHttpGate(ReadyAgentUsageGate(registry), {}, {})

        assertFailsWith<AgentAuthenticationRequiredException> {
            gate.execute {
                sends += 1
                success()
            }
        }

        assertEquals(0, sends)
    }

    @Test
    fun `decoded 401 and 499 authentication failures use the one orchestrator callback`() = runTest {
        listOf(401, 499).forEach { status ->
            val registry = readyRegistry()
            var authenticationExpiredCalls = 0
            val gate = ReadyAuthenticatedHttpGate(
                ReadyAgentUsageGate(registry),
                onAuthenticationExpired = { authenticationExpiredCalls += 1 },
                onMembershipExpired = {},
            )

            val response = gate.execute { HuitaiResponse.Failure(ActionErrorCode.AUTH_EXPIRED, status.toString()) }

            assertNull(response)
            assertEquals(1, authenticationExpiredCalls, "HTTP $status")
        }
    }

    @Test
    fun `singleflight refresh failure is routed to authentication expiry callback`() = runTest {
        val registry = readyRegistry()
        var authenticationExpiredCalls = 0
        val gate = ReadyAuthenticatedHttpGate(
            ReadyAgentUsageGate(registry),
            onAuthenticationExpired = { authenticationExpiredCalls += 1 },
            onMembershipExpired = {},
        )

        val response = gate.execute {
            HuitaiResponse.Failure(ActionErrorCode.AUTH_EXPIRED, remoteCode = "refresh_failed")
        }

        assertNull(response)
        assertEquals(1, authenticationExpiredCalls)
    }

    @Test
    fun `membership expiry callback also consumes the stale HTTP response`() = runTest {
        val registry = readyRegistry()
        var membershipExpiredCalls = 0
        val gate = ReadyAuthenticatedHttpGate(
            ReadyAgentUsageGate(registry),
            onAuthenticationExpired = {},
            onMembershipExpired = { membershipExpiredCalls += 1 },
        )

        val response = gate.execute {
            HuitaiResponse.Failure(ActionErrorCode.MEMBERSHIP_EXPIRED, remoteCode = "membership_expired")
        }

        assertNull(response)
        assertEquals(1, membershipExpiredCalls)
    }

    @Test
    fun `late HTTP success after logout is discarded before response commit`() = runTest {
        val registry = readyRegistry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = ReadyAuthenticatedHttpGate(ReadyAgentUsageGate(registry), {}, {})

        val pending = async {
            gate.execute {
                entered.complete(Unit)
                release.await()
                success()
            }
        }
        entered.await()
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        release.complete(Unit)

        assertNull(pending.await())
    }

    private fun success() = HuitaiResponse.Success(
        com.wzx.huitai.integration.http.CommonResult("0", "ok", kotlinx.serialization.json.JsonNull),
    )

    private fun readyRegistry(): BusinessIdentityRegistry = BusinessIdentityRegistry().also {
        check(it.publishReady(identity(), 0))
    }

    private fun identity() = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-session-1",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "1",
        roles = setOf("lawyer"),
        permissions = setOf("case:read"),
    )
}
