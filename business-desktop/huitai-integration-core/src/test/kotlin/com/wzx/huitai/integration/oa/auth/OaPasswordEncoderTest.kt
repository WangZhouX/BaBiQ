package com.wzx.huitai.integration.oa.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OaPasswordEncoderTest {
    @Test
    fun `encodes valid password with twice MD5 and clears caller buffer`() {
        val password = "Abcdef12".toCharArray()

        val digest = OaPasswordEncoder.encode(password)

        assertEquals("6d93c260d711cdb51207c420279ae936", digest)
        assertFalse(password.any { it != '\u0000' })
        assertFalse(digest.contains("Abcdef12"))
    }

    @Test
    fun `rejects password without letters or digits and still clears caller buffer`() {
        val password = "12345678".toCharArray()

        assertFailsWith<OaAuthenticationException> { OaPasswordEncoder.encode(password) }

        assertFalse(password.any { it != '\u0000' })
    }
}
