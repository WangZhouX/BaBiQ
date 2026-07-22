package com.wzx.huitai.desktop.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessAuthRevocationMarkerStoreTest {
    @Test
    fun `revocation marker survives reopening and only explicit clear removes it`() {
        val path = Files.createTempDirectory("business-auth-revocation").resolve("auth-revoked-v1")
        val first = FileBusinessAuthRevocationMarkerStore(path)

        assertFalse(first.isRevoked())
        first.markRevoked()
        assertTrue(FileBusinessAuthRevocationMarkerStore(path).isRevoked())

        FileBusinessAuthRevocationMarkerStore(path).clearAfterExplicitLogin()
        assertFalse(FileBusinessAuthRevocationMarkerStore(path).isRevoked())
    }
}
