package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessIdentityRegistryTest {
    @Test
    fun `identity and generation are published as one atomic snapshot`() {
        val registry = BusinessIdentityRegistry()
        val initial = registry.snapshot.value
        assertNull(initial.identity)
        assertEquals(0L, initial.generation)

        assertTrue(registry.install(identity(), initial.generation))
        assertEquals("user-1", registry.snapshot.value.identity?.userId)
        assertEquals(0L, registry.snapshot.value.generation)

        val removed = registry.invalidate()
        assertEquals("user-1", removed?.userId)
        val revoked = registry.snapshot.value
        assertNull(revoked.identity)
        assertEquals(1L, revoked.generation)
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
