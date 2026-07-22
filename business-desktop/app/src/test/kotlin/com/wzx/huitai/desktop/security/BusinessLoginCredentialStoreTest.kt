package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.JceksSecretStore
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
        val replacementPassword = "replacement".toCharArray()
        val root = Files.createTempDirectory("business-remembered-login")
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), password).use { secrets ->
                val store = BusinessLoginCredentialStore(secrets)
                store.saveOrReplace("account-1", loginPassword)
                val first = requireNotNull(store.load())
                first.use {
                    assertEquals("account-1", it.account)
                    val firstCopy = it.copyPassword()
                    try {
                        assertEquals("oa-password", firstCopy.concatToString())
                        firstCopy.fill('x')
                        val secondCopy = it.copyPassword()
                        try {
                            assertEquals("oa-password", secondCopy.concatToString())
                        } finally {
                            secondCopy.fill('\u0000')
                        }
                    } finally {
                        firstCopy.fill('\u0000')
                    }
                }

                store.saveOrReplace("account-2", replacementPassword)
                requireNotNull(store.load()).use { assertEquals("account-2", it.account) }
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
            replacementPassword.fill('\u0000')
        }
    }

    @Test
    fun `remembered login owns a defensive password copy and clears it idempotently`() {
        val source = "owned-password".toCharArray()
        val remembered = RememberedBusinessLogin("account-1", source)
        source.fill('x')

        val firstCopy = remembered.copyPassword()
        try {
            assertEquals("owned-password", firstCopy.concatToString())
            firstCopy.fill('y')
            val secondCopy = remembered.copyPassword()
            try {
                assertEquals("owned-password", secondCopy.concatToString())
            } finally {
                secondCopy.fill('\u0000')
            }
        } finally {
            firstCopy.fill('\u0000')
            remembered.clear()
            remembered.clear()
            source.fill('\u0000')
        }
        val failure = assertFailsWith<IllegalStateException> { remembered.copyPassword() }
        assertFalse("owned-password" in failure.toString())
    }

    @Test
    fun `all malformed remembered login forms remove only remembered alias`() = runBlocking {
        val password = "key-store-password".toCharArray()
        try {
            JceksSecretStore(Files.createTempDirectory("business-remembered-login-invalid").resolve("credentials.jceks"), password).use { secrets ->
                val remembered = BusinessLoginCredentialStore(secrets)
                val metadata = BusinessAuthSessionMetadataStore(secrets)
                val tokens = JceksAuthCredentialPersistence(secrets)
                tokens.replace(AuthTokenSet("access-token", "refresh-token"))
                metadata.saveOrReplace(BusinessAuthSessionMetadata("user-1", "tenant-1", "100"))
                secrets.upsert(BusinessAuthSessionMetadataStoreTest.PROVIDER_ALIAS, "provider-secret".toCharArray())

                BusinessAuthSessionMetadataStoreTest.malformedEntries(
                    BusinessAuthSessionMetadataStoreTest.REMEMBERED_MAGIC,
                    2,
                ).forEach { (caseName, corrupt) ->
                    secrets.upsert(BusinessLoginCredentialStore.DEFAULT_ALIAS, hex(corrupt))
                    val failure = assertFailsWith<RememberedLoginInvalidException>(caseName) { remembered.load() }
                    assertEquals("Remembered login is invalid", failure.message, caseName)
                    assertFalse("access-token" in failure.toString(), caseName)
                    assertNull(remembered.load(), caseName)
                    assertEquals("access-token", tokens.load()?.accessToken, caseName)
                    assertEquals("user-1", metadata.load()?.userId, caseName)
                    BusinessAuthSessionMetadataStoreTest.assertSecretEquals(
                        secrets,
                        BusinessAuthSessionMetadataStoreTest.PROVIDER_ALIAS,
                        "provider-secret",
                        caseName,
                    )
                }
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
