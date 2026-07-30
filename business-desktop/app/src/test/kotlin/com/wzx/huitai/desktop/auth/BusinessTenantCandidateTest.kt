package com.wzx.huitai.desktop.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BusinessTenantCandidateTest {
    @Test
    fun `candidate equality is stable for the opaque selection ticket`() {
        val first = BusinessTenantCandidate(
            candidateId = "ticket-1",
            name = "律所一",
            platformId = 101,
            tenantEnterStatus = 0,
        )

        assertEquals(first, first.copy())
        assertEquals(first.hashCode(), first.copy().hashCode())
    }

    @Test
    fun `candidate requires a non blank opaque id`() {
        assertFailsWith<IllegalArgumentException> {
            BusinessTenantCandidate(candidateId = " ", name = null, platformId = 0, tenantEnterStatus = 0)
        }
    }
}
