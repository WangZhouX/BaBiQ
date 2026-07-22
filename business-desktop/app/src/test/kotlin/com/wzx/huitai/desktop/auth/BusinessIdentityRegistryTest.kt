package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessIdentityRegistryTest {
    @Test
    fun `identity and generation are published as one atomic snapshot`() {
        val registry = BusinessIdentityRegistry()
        val initial = registry.snapshot.value
        assertNull(initial.identity)
        assertEquals(BusinessAccessGateState.STARTING, initial.gate)
        assertEquals(0L, initial.generation)

        registry.transitionTo(BusinessAccessGateState.REGISTERING_AGENT)
        assertTrue(registry.publishReady(identity(), initial.generation))
        assertEquals("user-1", registry.snapshot.value.identity?.userId)
        assertEquals(BusinessAccessGateState.READY, registry.snapshot.value.gate)
        assertEquals(0L, registry.snapshot.value.generation)

        val removed = registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        assertEquals("user-1", removed?.userId)
        val revoked = registry.snapshot.value
        assertNull(revoked.identity)
        assertEquals(BusinessAccessGateState.SIGNING_OUT, revoked.gate)
        assertEquals(1L, revoked.generation)
    }

    @Test
    fun `every concurrently observed snapshot keeps ready and identity inseparable`() = runTest {
        val registry = BusinessIdentityRegistry()
        val observed = mutableListOf<BusinessIdentityRegistrySnapshot>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            registry.snapshot.collect { observed += it }
        }

        repeat(500) {
            registry.transitionTo(BusinessAccessGateState.REGISTERING_AGENT)
            assertTrue(registry.publishReady(identity(), registry.currentGeneration()))
            registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
            registry.transitionTo(BusinessAccessGateState.SIGNED_OUT)
        }
        runCurrent()
        collector.cancel()

        assertTrue(observed.isNotEmpty())
        assertTrue(
            observed.all { snapshot ->
                (snapshot.gate == BusinessAccessGateState.READY) == (snapshot.identity != null)
            },
        )
    }

    @Test
    fun `non ready transition always removes identity while stale ready publication is rejected`() {
        val registry = BusinessIdentityRegistry()
        assertTrue(registry.publishReady(identity(), expectedGeneration = 0))
        val invalidated = registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        assertEquals("user-1", invalidated?.userId)

        registry.transitionTo(BusinessAccessGateState.SIGNED_OUT)

        assertNull(registry.snapshot.value.identity)
        assertEquals(BusinessAccessGateState.SIGNED_OUT, registry.snapshot.value.gate)
        assertTrue(!registry.publishReady(identity(), expectedGeneration = 0))
    }

    private fun identity() = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "session-1",
        authSessionId = "auth-1",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "100",
        roles = setOf("lawyer"),
        permissions = setOf("case:read"),
    )
}
