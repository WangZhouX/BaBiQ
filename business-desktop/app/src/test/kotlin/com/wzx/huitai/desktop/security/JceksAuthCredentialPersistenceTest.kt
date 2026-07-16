package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.Executors
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JceksAuthCredentialPersistenceTest {
    @Test
    fun `credentials persist replace and clear without plaintext artifacts`() = runBlocking {
        val root = Files.createTempDirectory("jceks-auth-persistence")
        val path = root.resolve("credentials.jceks")
        val password = "password".toCharArray()
        val old = AuthTokenSet("old-access-token", "old-refresh-token")
        val refreshed = AuthTokenSet("new-access-token", "new-refresh-token")
        val switched = AuthTokenSet("tenant-access-token", "tenant-refresh-token")

        JceksSecretStore(path, password).use { store ->
            val persistence = JceksAuthCredentialPersistence(store)
            assertNull(persistence.load())
            persistence.replace(old)
            assertEquals(old, persistence.load())
            persistence.replace(refreshed)
            assertEquals(refreshed, persistence.load())
            persistence.replace(switched)
            assertEquals(switched, persistence.load())
            assertFalse(old.accessToken in persistence.toString())
            assertFalse(switched.accessToken in persistence.toString())
        }

        JceksSecretStore(path, password).use { store ->
            val reopened = JceksAuthCredentialPersistence(store)
            assertEquals(switched, reopened.load())
            reopened.clear()
            reopened.clear()
            assertNull(reopened.load())
        }

        val forbidden = listOf(old, refreshed, switched).flatMap { listOf(it.accessToken, it.refreshToken) }
        Files.list(root).use { stream ->
            val files = stream.toList()
            assertTrue(files.all { it.fileName.toString() in setOf("credentials.jceks", "credentials.jceks.lock") })
            files.forEach { file ->
                val text = file.readBytes().toString(Charsets.ISO_8859_1)
                forbidden.forEach { token -> assertFalse(token in text) }
            }
        }
    }

    @Test
    fun `corrupt stored credentials are rejected with stable redacted error`() = runBlocking {
        val path = Files.createTempDirectory("jceks-auth-corrupt").resolve("credentials.jceks")
        val password = "password".toCharArray()
        val tokenText = "must-not-leak"
        JceksSecretStore(path, password).use { store ->
            val ref = SecretRef.parse(JceksAuthCredentialPersistence.DEFAULT_ALIAS)
            val persistence = JceksAuthCredentialPersistence(store, ref)
            val corruptValues = listOf(
                charArrayOf('0'),
                hex(byteArrayOf(1, 2, 3, 4)),
                hex(header(accessLength = 65 * 1024)),
                hex(validPayload("a", "b") + byteArrayOf(9)),
                hex(invalidUtf8Payload()),
            )

            corruptValues.forEachIndexed { index, corrupt ->
                if (index == 0) store.save(ref.value, corrupt) else store.replace(ref, corrupt)
                val failure = assertFailsWith<CredentialPersistenceException> { persistence.load() }
                assertEquals("Stored authentication credentials are invalid", failure.message)
                assertFalse(tokenText in failure.toString())
                assertFalse(corrupt.concatToString() in failure.toString())
            }
            assertEquals("SecretRef([REDACTED])", ref.toString())
            assertFalse(ref.value in ref.toString())
            assertEquals(
                "AuthTokenSet(accessToken=[REDACTED], refreshToken=[REDACTED])",
                AuthTokenSet(tokenText, "$tokenText-refresh").toString(),
            )
        }
    }

    @Test
    fun `auth session manager persists login and clears logout in real store`() = runBlocking {
        val path = Files.createTempDirectory("jceks-auth-session").resolve("credentials.jceks")
        val password = "password".toCharArray()
        val tokens = AuthTokenSet("manager-access", "manager-refresh")
        JceksSecretStore(path, password).use { store ->
            val persistence = JceksAuthCredentialPersistence(store)
            val manager = AuthSessionManager(persistence)
            manager.login(
                userId = "user-1",
                tenantId = "tenant-1",
                platformId = "desktop",
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
                authenticatedAt = Instant.parse("2026-07-16T00:00:00Z"),
                tokens = tokens,
            )
            assertEquals(tokens, persistence.load())
            manager.logout()
            assertNull(persistence.load())
        }
    }

    @Test
    fun `concurrent replace and clear preserve complete credentials`() = runBlocking {
        val path = Files.createTempDirectory("jceks-auth-concurrent").resolve("credentials.jceks")
        val password = "password".toCharArray()
        val values = (1..32).map { AuthTokenSet("access-$it", "refresh-$it") }
        JceksSecretStore(path, password).use { store ->
            val persistence = JceksAuthCredentialPersistence(store)
            val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
            try {
                values.map { tokens -> async(dispatcher) { persistence.replace(tokens) } }.awaitAll()
                assertTrue(persistence.load() in values)

                (1..64).map { index ->
                    async(dispatcher) {
                        if (index % 3 == 0) persistence.clear() else persistence.replace(values[index % values.size])
                    }
                }.awaitAll()
                persistence.load()?.let { assertTrue(it in values) }
                persistence.replace(values.first())
                assertEquals(values.first(), persistence.load())
            } finally {
                dispatcher.close()
            }
        }
    }

    private fun validPayload(access: String, refresh: String): ByteArray {
        val accessBytes = access.toByteArray()
        val refreshBytes = refresh.toByteArray()
        return ByteBuffer.allocate(16 + accessBytes.size + refreshBytes.size)
            .putInt(0x48544352)
            .putInt(1)
            .putInt(accessBytes.size)
            .put(accessBytes)
            .putInt(refreshBytes.size)
            .put(refreshBytes)
            .array()
    }

    private fun header(accessLength: Int): ByteArray = ByteBuffer.allocate(12)
        .putInt(0x48544352)
        .putInt(1)
        .putInt(accessLength)
        .array()

    private fun invalidUtf8Payload(): ByteArray = ByteBuffer.allocate(18)
        .putInt(0x48544352)
        .putInt(1)
        .putInt(1)
        .put(0x80.toByte())
        .putInt(1)
        .put('b'.code.toByte())
        .array()

    private fun hex(bytes: ByteArray): CharArray {
        val digits = "0123456789abcdef"
        return CharArray(bytes.size * 2) { index ->
            val value = bytes[index / 2].toInt() and 0xff
            digits[if (index % 2 == 0) value ushr 4 else value and 0xf]
        }
    }
}
