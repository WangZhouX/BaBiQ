package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.JceksSecretStore
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BusinessAuthSessionMetadataStoreTest {
    @Test
    fun `metadata save or replace reloads only identity fields and clear is idempotent`() {
        val root = Files.createTempDirectory("business-auth-session-metadata")
        val password = "password".toCharArray()
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), password).use { secrets ->
                val store = BusinessAuthSessionMetadataStore(secrets)
                val first = BusinessAuthSessionMetadata("user-1", "tenant-1", "100")
                val replacement = BusinessAuthSessionMetadata("user-2", "tenant-2", "200")

                assertNull(store.load())
                store.saveOrReplace(first)
                assertEquals(first, store.load())
                store.saveOrReplace(replacement)
                assertEquals(replacement, store.load())
                store.clear()
                store.clear()
                assertNull(store.load())
                assertEquals(
                    "BusinessAuthSessionMetadata(userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED])",
                    replacement.toString(),
                )
            }
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun `corrupt metadata reports a redacted stable failure and preserves token entry`() = runBlocking {
        val password = "password".toCharArray()
        try {
            JceksSecretStore(Files.createTempDirectory("business-auth-session-corrupt").resolve("credentials.jceks"), password).use { secrets ->
                val metadata = BusinessAuthSessionMetadataStore(secrets)
                val tokens = JceksAuthCredentialPersistence(secrets)
                tokens.replace(com.wzx.huitai.integration.auth.AuthTokenSet("access-token", "refresh-token"))
                secrets.upsert(BusinessAuthSessionMetadataStore.DEFAULT_ALIAS, hex(ByteBuffer.allocate(12)
                    .putInt(0x48534d44)
                    .putInt(1)
                    .putInt(128 * 1024)
                    .array()))

                val failure = assertFailsWith<SessionMetadataPersistenceException> { metadata.load() }
                assertEquals("Stored authentication session metadata is invalid", failure.message)
                assertFalse("access-token" in failure.toString())
                assertEquals("access-token", tokens.load()?.accessToken)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun `unavailable local keystore is left byte for byte unchanged`() {
        val root = Files.createTempDirectory("business-auth-session-unavailable")
        val path = root.resolve("credentials.jceks")
        val snapshot = byteArrayOf(1, 2, 3, 4, 5, 6)
        Files.write(path, snapshot)
        val password = "wrong-password".toCharArray()
        try {
            JceksSecretStore(path, password).use { secrets ->
                val failure = assertFailsWith<LocalCredentialStoreUnavailableException> {
                    BusinessAuthSessionMetadataStore(secrets).load()
                }
                assertEquals("Local key store is unavailable", failure.message)
                assertFalse("wrong-password" in failure.toString())
                assertContentEquals(snapshot, path.readBytes())
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun hex(bytes: ByteArray): CharArray {
        val digits = "0123456789abcdef"
        return CharArray(bytes.size * 2) { index ->
            val value = bytes[index / 2].toInt() and 0xff
            digits[if (index % 2 == 0) value ushr 4 else value and 0xf]
        }
    }
}
