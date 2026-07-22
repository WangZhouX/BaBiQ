package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.JceksSecretStore
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessLoginCredentialStoreTest {
    @Test
    fun `remembered login replaces and returns a caller wipeable password copy`() {
        val password = "key-store-password".toCharArray()
        val loginPassword = "oa-password".toCharArray()
        val root = Files.createTempDirectory("business-remembered-login")
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), password).use { secrets ->
                val store = BusinessLoginCredentialStore(secrets)
                store.saveOrReplace("account-1", loginPassword)
                val first = requireNotNull(store.load())
                assertEquals("account-1", first.account)
                assertEquals("oa-password", first.password.concatToString())
                first.password.fill('x')
                assertEquals("oa-password", requireNotNull(store.load()).password.concatToString())

                store.saveOrReplace("account-2", "replacement".toCharArray())
                assertEquals("account-2", requireNotNull(store.load()).account)
                store.clear()
                store.clear()
                assertNull(store.load())
                assertEquals("RememberedBusinessLogin(account=[REDACTED], password=[REDACTED])", first.toString())
            }
            Files.list(root).use { files ->
                val entries = files.toList()
                assertTrue(entries.all { it.fileName.toString() in setOf("credentials.jceks", "credentials.jceks.lock") })
                entries.forEach { file ->
                    val contents = file.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse("account-1" in contents)
                    assertFalse("oa-password" in contents)
                }
            }
        } finally {
            password.fill('\u0000')
            loginPassword.fill('\u0000')
        }
    }

    @Test
    fun `invalid remembered login removes only its alias and exposes a stable redacted outcome`() = runBlocking {
        val password = "key-store-password".toCharArray()
        try {
            JceksSecretStore(Files.createTempDirectory("business-remembered-login-invalid").resolve("credentials.jceks"), password).use { secrets ->
                val remembered = BusinessLoginCredentialStore(secrets)
                val metadata = BusinessAuthSessionMetadataStore(secrets)
                val tokens = JceksAuthCredentialPersistence(secrets)
                tokens.replace(AuthTokenSet("access-token", "refresh-token"))
                metadata.saveOrReplace(BusinessAuthSessionMetadata("user-1", "tenant-1", "100"))
                secrets.upsert(BusinessLoginCredentialStore.DEFAULT_ALIAS, hex(ByteBuffer.allocate(12)
                    .putInt(0x484c4f47)
                    .putInt(1)
                    .putInt(128 * 1024)
                    .array()))

                val failure = assertFailsWith<RememberedLoginInvalidException> { remembered.load() }
                assertEquals("Remembered login is invalid", failure.message)
                assertFalse("access-token" in failure.toString())
                assertNull(remembered.load())
                assertEquals("access-token", tokens.load()?.accessToken)
                assertEquals("user-1", metadata.load()?.userId)
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
