package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusinessLoginCredentialStoreTest {
    @Test
    fun `remembered login replaces account and never accepts a password`() {
        val keyStorePassword = "key-store-password".toCharArray()
        val root = Files.createTempDirectory("business-remembered-login")
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), keyStorePassword).use { secrets ->
                val store = BusinessLoginCredentialStore(secrets)
                store.saveOrReplace("account-1")
                assertEquals("account-1", store.load()?.account)

                store.saveOrReplace("account-2")
                assertEquals("account-2", store.load()?.account)

                store.clear()
                store.clear()
                assertNull(store.load())
            }
        } finally {
            keyStorePassword.fill('\u0000')
        }
    }

    @Test
    fun `remembered account rejects blank values and clear is idempotent`() {
        val keyStorePassword = "key-store-password".toCharArray()
        try {
            JceksSecretStore(Files.createTempDirectory("business-remembered-login-invalid").resolve("credentials.jceks"), keyStorePassword).use { secrets ->
                val store = BusinessLoginCredentialStore(secrets)
                assertFailsWith<IllegalArgumentException> { store.saveOrReplace(" ") }
                assertFailsWith<IllegalArgumentException> { RememberedBusinessLogin(" ") }
                store.clear()
            }
        } finally {
            keyStorePassword.fill('\u0000')
        }
    }

    @Test
    fun `remembered account string representation redacts account`() {
        val remembered = RememberedBusinessLogin("account-1")
        assertFalse(remembered.toString().contains("account-1"))
        remembered.close()
        assertEquals("RememberedBusinessLogin(account=[REDACTED])", remembered.toString())
    }

    @Test
    fun `account only persistence uses a non legacy alias and survives restart`() {
        assertEquals("huitai.login.account.v2", BusinessLoginCredentialStore.DEFAULT_ALIAS)
        assertTrue(BusinessLoginCredentialStore.DEFAULT_ALIAS !in LEGACY_ALIASES)
        val keyStorePassword = "key-store-password".toCharArray()
        val path = Files.createTempDirectory("business-remembered-account-v2").resolve("credentials.jceks")
        try {
            JceksSecretStore(path, keyStorePassword).use { secrets ->
                BusinessLoginCredentialStore(secrets).saveOrReplace("restart-account")
                assertNotNull(secrets.load(SecretRef.parse(BusinessLoginCredentialStore.DEFAULT_ALIAS)))
                LEGACY_ALIASES.forEach { alias -> assertNull(secrets.load(SecretRef.parse(alias))) }
            }

            JceksSecretStore(path, keyStorePassword).use { reopened ->
                assertEquals("restart-account", BusinessLoginCredentialStore(reopened).load()?.account)
                LEGACY_ALIASES.forEach { alias -> assertNull(reopened.load(SecretRef.parse(alias))) }
            }
        } finally {
            keyStorePassword.fill('\u0000')
        }
    }

    @Test
    fun `account persistence API cannot accept a password`() {
        val methods = BusinessLoginCredentialStore::class.java.declaredMethods
            .filter { method -> method.name == "saveOrReplace" }
        assertEquals(1, methods.size)
        assertEquals(listOf(String::class.java), methods.single().parameterTypes.toList())
    }

    @Test
    fun `account persistence constructors cannot inject an arbitrary secret alias`() {
        val aliasAwareConstructors = BusinessLoginCredentialStore::class.java.declaredConstructors
            .filter { constructor -> SecretRef::class.java in constructor.parameterTypes }

        assertTrue(
            aliasAwareConstructors.isEmpty(),
            "remembered-account storage must be fixed to ${BusinessLoginCredentialStore.DEFAULT_ALIAS}",
        )
    }

    private companion object {
        val LEGACY_ALIASES = setOf(
            "huitai.auth.tokens.v1",
            "huitai.auth.session-metadata.v1",
            "huitai.login.remembered.v1",
        )
    }
}
