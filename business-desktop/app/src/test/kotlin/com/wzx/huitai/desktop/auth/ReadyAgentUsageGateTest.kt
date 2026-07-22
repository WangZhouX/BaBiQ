package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReadyAgentUsageGateTest {
    @Test
    fun `captures one full ready identity and invalidates it on generation change`() {
        val registry = BusinessIdentityRegistry()
        val identity = identity()
        check(registry.publishReady(identity, 0))
        val gate = ReadyAgentUsageGate(registry)

        val snapshot = assertNotNull(gate.captureIfReady())

        assertEquals(identity, snapshot.identity)
        assertEquals(0, snapshot.generation)
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        assertFalse(gate.isCurrent(snapshot))
        assertNull(gate.captureIfReady())
    }

    @Test
    fun `commit runs only for the exact ready publication`() {
        val registry = BusinessIdentityRegistry()
        check(registry.publishReady(identity(), 0))
        val gate = ReadyAgentUsageGate(registry)
        val snapshot = gate.requireReady()
        var commits = 0

        check(gate.commitIfCurrent(snapshot) { commits += 1 })
        registry.invalidate(BusinessAccessGateState.SIGNED_OUT)
        assertFalse(gate.commitIfCurrent(snapshot) { commits += 1 })

        assertEquals(1, commits)
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
