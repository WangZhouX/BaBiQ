package com.wzx.huitai.desktop.integration

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.auth.ReadyAuthenticatedHttpGate
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.http.HuitaiResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BusinessAuthenticationExpiryIT {
    @Test
    fun `HTTP authentication expiry enters the unified revocation callback and closes READY`() = runTest {
        val registry = BusinessIdentityRegistry().also { check(it.publishReady(identity(), 0)) }
        var unifiedRevocations = 0
        val gate = ReadyAuthenticatedHttpGate(
            ReadyAgentUsageGate(registry),
            onAuthenticationExpired = {
                unifiedRevocations += 1
                registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
            },
            onMembershipExpired = {},
        )

        val response = gate.execute { HuitaiResponse.Failure(ActionErrorCode.AUTH_EXPIRED) }

        assertEquals(ActionErrorCode.AUTH_EXPIRED, (response as HuitaiResponse.Failure).errorCode)
        assertEquals(1, unifiedRevocations)
        assertEquals(BusinessAccessGateState.SIGNING_OUT, registry.snapshot.value.gate)
        assertNull(registry.snapshot.value.identity)
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
