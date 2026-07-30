package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.JceksSecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BusinessLoginAccountOnlyContractTest {
    @Test
    fun `remembered login persists only the account`() {
        val keyStorePassword = "key-store-password".toCharArray()
        val root = Files.createTempDirectory("business-remembered-account-only")
        try {
            JceksSecretStore(root.resolve("credentials.jceks"), keyStorePassword).use { secrets ->
                val store = BusinessLoginCredentialStore(secrets)
                store.saveOrReplace("lawyer@example.com")

                assertEquals("lawyer@example.com", store.load()?.account)
                store.clear()
                assertNull(store.load())
            }
        } finally {
            keyStorePassword.fill('\u0000')
        }
    }
}
