package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.JceksSecretStore
import com.wzx.huitai.security.secret.SecretRef
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Modifier
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyOaCredentialAliasCleanupTest {
    @Test
    fun `cleanup deletes only the three fixed aliases without decoding legacy values`() {
        val root = Files.createTempDirectory("legacy-oa-alias-cleanup")
        val path = root.resolve("desktop.jceks")
        val password = "desktop-password".toCharArray()
        JceksSecretStore(path, password).use { secrets ->
            LEGACY_ALIASES.forEachIndexed { index, alias ->
                secrets.upsert(alias, "broken-or-unknown-version-$index".toCharArray())
            }
            secrets.upsert(VAULT_ALIAS, "vault-password".toCharArray())
            secrets.upsert(PROVIDER_ALIAS, "provider-secret".toCharArray())

            LegacyOaCredentialAliasCleanup(secrets).cleanup()
            LegacyOaCredentialAliasCleanup(secrets).cleanup()
        }

        JceksSecretStore(path, password).use { reopened ->
            LEGACY_ALIASES.forEach { alias -> assertNull(reopened.load(SecretRef.parse(alias))) }
            assertEquals("vault-password", reopened.load(SecretRef.parse(VAULT_ALIAS))?.concatToString())
            assertEquals("provider-secret", reopened.load(SecretRef.parse(PROVIDER_ALIAS))?.concatToString())
        }
    }

    @Test
    fun `cleanup exposes one no-argument operation and owns the exact alias allowlist`() {
        val cleanupMethods = LegacyOaCredentialAliasCleanup::class.java.declaredMethods
            .filter { method -> method.name == "cleanup" }
        assertEquals(1, cleanupMethods.size)
        assertEquals(0, cleanupMethods.single().parameterCount)

        val ownerTypes = listOf(LegacyOaCredentialAliasCleanup::class.java) +
            LegacyOaCredentialAliasCleanup::class.java.declaredClasses
        val publicAliasCollections = ownerTypes.flatMap { type -> type.declaredMethods.toList() }
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    Set::class.java.isAssignableFrom(method.returnType)
            }
        assertTrue(
            publicAliasCollections.isEmpty(),
            "the fixed legacy alias allowlist must not be exposed as a mutable public collection",
        )
    }

    @Test
    fun `wrong password failure preserves bytes and exposes only a fixed safe error`() {
        val root = Files.createTempDirectory("legacy-oa-alias-wrong-password")
        val path = root.resolve("desktop.jceks")
        JceksSecretStore(path, "right-password".toCharArray()).use { secrets ->
            secrets.upsert(LEGACY_ALIASES.first(), SECRET_CANARY.toCharArray())
        }
        val original = path.readBytes()

        val failure = JceksSecretStore(path, "wrong-password".toCharArray()).use { secrets ->
            assertFailsWith<LegacyOaCredentialCleanupException> {
                LegacyOaCredentialAliasCleanup(secrets).cleanup()
            }
        }

        assertSafeFailure(failure, path)
        assertTrue(original.contentEquals(path.readBytes()))
    }

    @Test
    fun `corrupt key store failure preserves bytes and exposes only a fixed safe error`() {
        val root = Files.createTempDirectory("legacy-oa-alias-corrupt-store")
        val path = root.resolve("desktop.jceks")
        path.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val original = path.readBytes()

        val failure = JceksSecretStore(path, "right-password".toCharArray()).use { secrets ->
            assertFailsWith<LegacyOaCredentialCleanupException> {
                LegacyOaCredentialAliasCleanup(secrets).cleanup()
            }
        }

        assertSafeFailure(failure, path)
        assertTrue(original.contentEquals(path.readBytes()))
    }

    @Test
    fun `legacy alias literals exist only in desktop cleanup production source and never in backend`() {
        val appSource = Path.of("src/main/kotlin").toAbsolutePath().normalize()
        val cleanupSource = appSource.resolve(
            "com/wzx/huitai/desktop/security/LegacyOaCredentialAliasCleanup.kt",
        )
        LEGACY_ALIASES.forEach { alias ->
            val owners = sourceFilesContaining(appSource, alias)
            assertEquals(setOf(cleanupSource), owners, "unexpected desktop production owner for $alias")
        }

        val backendSource = Path.of("../../backend/src/main/java").toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(backendSource), "backend production source root must be discoverable")
        LEGACY_ALIASES.forEach { alias ->
            assertEquals(emptySet(), sourceFilesContaining(backendSource, alias))
        }
    }

    private fun assertSafeFailure(failure: LegacyOaCredentialCleanupException, path: Path) {
        assertEquals("Legacy OA credential cleanup failed", failure.message)
        assertNull(failure.cause)
        val exposed = failure.toString()
        assertFalse(exposed.contains(path.toString()))
        assertFalse(exposed.contains(SECRET_CANARY))
        LEGACY_ALIASES.forEach { alias -> assertFalse(exposed.contains(alias)) }
    }

    private fun sourceFilesContaining(root: Path, text: String): Set<Path> =
        Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
                .filter { path -> Files.readString(path).contains(text) }
                .map { path -> path.toAbsolutePath().normalize() }
                .toList()
                .toSet()
        }

    private companion object {
        val LEGACY_ALIASES = linkedSetOf(
            "huitai.auth.tokens.v1",
            "huitai.auth.session-metadata.v1",
            "huitai.login.remembered.v1",
        )
        const val VAULT_ALIAS = "huitai.backend.keystore.password.v1"
        const val PROVIDER_ALIAS = "provider.deepseek.v1"
        const val SECRET_CANARY = "legacy-secret-canary-never-expose"
    }
}
