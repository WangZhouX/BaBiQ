package com.wzx.huitai.integration.oa.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `accepts exact 8 and 16 ASCII alphanumeric boundaries and clears both buffers`() {
        val minimum = "Abcdef12".toCharArray()
        val maximum = "Abcdefghijkl1234".toCharArray()

        assertEquals(32, OaPasswordEncoder.encode(minimum).length)
        assertEquals(32, OaPasswordEncoder.encode(maximum).length)

        assertTrue(minimum.all { it == '\u0000' })
        assertTrue(maximum.all { it == '\u0000' })
    }

    @Test
    fun `rejects 7 and 17 character passwords and clears both buffers`() {
        val tooShort = "Abcde12".toCharArray()
        val tooLong = "Abcdefghijklm1234".toCharArray()

        assertEquals(OaAuthenticationError.INVALID_PASSWORD_FORMAT, invalid(tooShort).error)
        assertEquals(OaAuthenticationError.INVALID_PASSWORD_FORMAT, invalid(tooLong).error)
        assertTrue(tooShort.all { it == '\u0000' })
        assertTrue(tooLong.all { it == '\u0000' })
    }

    @Test
    fun `rejects symbols whitespace Unicode digits and Unicode letters and clears every buffer`() {
        val invalidPasswords = listOf(
            "Abcdef1!".toCharArray(),
            "Abcdef1 ".toCharArray(),
            "Abcdefg１".toCharArray(),
            "Abcdef1é".toCharArray(),
        )

        invalidPasswords.forEach { password ->
            assertEquals(OaAuthenticationError.INVALID_PASSWORD_FORMAT, invalid(password).error)
            assertTrue(password.all { it == '\u0000' })
        }
    }

    private fun invalid(password: CharArray): OaAuthenticationException =
        assertFailsWith { OaPasswordEncoder.encode(password) }
}
