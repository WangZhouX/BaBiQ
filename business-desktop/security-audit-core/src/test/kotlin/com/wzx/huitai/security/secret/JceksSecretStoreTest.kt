package com.wzx.huitai.security.secret

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JceksSecretStoreTest {
    @Test
    fun `save load replace delete persist across instances and create parent directory`() {
        val root = Files.createTempDirectory("jceks-secret-store")
        val path = root.resolve("nested/secrets.jceks")
        val password = "test-password".toCharArray()
        val secret = "known-secret-value"
        val store = JceksSecretStore(path, password)

        val ref = store.save("provider.deepseek", secret.toCharArray())
        assertEquals(secret, store.load(ref)?.concatToString())
        store.replace(ref, "replacement-secret".toCharArray())
        assertEquals("replacement-secret", JceksSecretStore(path, password).use { it.load(ref)?.concatToString() })
        assertTrue(Files.exists(path.parent))
        assertFalse(path.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        store.delete(ref)
        assertNull(store.load(ref))
        store.close()
    }

    @Test
    fun `wrong password corrupt store validation and public surfaces never expose secrets`() {
        val root = Files.createTempDirectory("jceks-secret-errors")
        val path = root.resolve("secrets.jceks")
        val secret = "never-leak-this-secret"
        val ref = JceksSecretStore(path, "right-password".toCharArray()).use {
            it.save("provider.safe", secret.toCharArray())
        }

        val wrong = assertFailsWith<SecretStoreException> {
            JceksSecretStore(path, "wrong-password".toCharArray()).use { it.load(ref) }
        }
        assertFalse(secret in wrong.toString())
        path.writeBytes(byteArrayOf(1, 2, 3, 4))
        val corrupt = assertFailsWith<SecretStoreException> {
            JceksSecretStore(path, "right-password".toCharArray()).use { it.load(ref) }
        }
        assertFalse(secret in corrupt.toString())
        assertFalse(secret in ref.toString())
        assertEquals("SecretRef([REDACTED])", ref.toString())
    }

    @Test
    fun `invalid aliases secrets passwords and references are rejected without leaking input`() {
        val path = Files.createTempDirectory("jceks-secret-validation").resolve("store.jceks")
        assertFailsWith<IllegalArgumentException> { JceksSecretStore(path, charArrayOf()) }
        val store = JceksSecretStore(path, "password".toCharArray())
        listOf("", " ", "../escape", "bad/alias").forEach { alias ->
            assertFailsWith<IllegalArgumentException> { store.save(alias, "secret".toCharArray()) }
        }
        assertFailsWith<IllegalArgumentException> { store.save("valid", charArrayOf()) }
        assertFailsWith<IllegalArgumentException> { SecretRef.parse("bad/reference") }
        store.close()
    }

    @Test
    fun `keystore and lock leaves must remain regular files at their actual open points`() {
        val root = Files.createTempDirectory("jceks-secret-unsafe-leaf")
        val storePath = root.resolve("store.jceks")
        Files.createDirectory(storePath)
        JceksSecretStore(storePath, "password".toCharArray()).use { store ->
            assertFailsWith<SecretStoreException> { store.load(SecretRef.parse("provider.safe")) }
        }

        Files.delete(storePath)
        Files.delete(root.resolve("store.jceks.lock"))
        Files.createDirectory(root.resolve("store.jceks.lock"))
        JceksSecretStore(storePath, "password".toCharArray()).use { store ->
            assertFailsWith<IllegalArgumentException> {
                store.save("provider.safe", "secret".toCharArray())
            }
        }
    }

    @Test
    fun `concurrent operations on one instance are serialized safely`() = runBlocking {
        val path = Files.createTempDirectory("jceks-secret-concurrent").resolve("store.jceks")
        val store = JceksSecretStore(path, "password".toCharArray())
        val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        try {
            val refs = (1..32).map { index ->
                async(dispatcher) { store.save("parallel.$index", "secret-$index".toCharArray()) }
            }.awaitAll()
            refs.forEachIndexed { index, ref ->
                assertEquals("secret-${index + 1}", store.load(ref)?.concatToString())
            }
        } finally {
            dispatcher.close()
            store.close()
        }
    }

    @Test
    fun `two instances serialize read modify write without losing aliases`() = runBlocking {
        val path = Files.createTempDirectory("jceks-secret-multi-instance").resolve("store.jceks")
        val password = "password".toCharArray()
        val first = JceksSecretStore(path, password)
        val second = JceksSecretStore(path, password)
        val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        try {
            val refs = (1..32).map { index ->
                async(dispatcher) {
                    (if (index % 2 == 0) first else second).save("multi.$index", "value-$index".toCharArray())
                }
            }.awaitAll()
            JceksSecretStore(path, password).use { reopened ->
                refs.forEachIndexed { index, ref -> assertEquals("value-${index + 1}", reopened.load(ref)?.concatToString()) }
            }
        } finally {
            dispatcher.close(); first.close(); second.close()
        }
    }

    @Test
    fun `unsupported atomic move preserves old store and removes temporary file`() {
        val root = Files.createTempDirectory("jceks-secret-atomic")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        val normal = JceksSecretStore(path, password)
        val ref = normal.save("atomic.alias", "old-secret".toCharArray())
        normal.close()
        val failing = JceksSecretStore(path, password) { source, target ->
            throw java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported")
        }

        assertFailsWith<SecretStoreException> { failing.replace(ref, "new-secret".toCharArray()) }
        assertEquals("old-secret", JceksSecretStore(path, password).use { it.load(ref)?.concatToString() })
        assertEquals(emptyList(), Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() })
        failing.close()
    }

    @Test
    fun `secret references validate persisted values and redact aliases`() {
        val ref = SecretRef.parse("provider.safe")
        assertEquals("provider.safe", ref.value)
        assertEquals("SecretRef([REDACTED])", ref.toString())
        listOf("", "../bad", "bad/reference").forEach { assertFailsWith<IllegalArgumentException> { SecretRef.parse(it) } }
    }

    @Test
    fun `process lock registry retains shared path until last store closes`() {
        val baseline = JceksSecretStore.processLockRegistrySizeForTest()
        val path = Files.createTempDirectory("jceks-secret-lock-registry").resolve("store.jceks")
        val password = "password".toCharArray()
        val first = JceksSecretStore(path, password)
        val second = JceksSecretStore(path, password)

        assertEquals(baseline + 1, JceksSecretStore.processLockRegistrySizeForTest())
        assertEquals(2, JceksSecretStore.processLockRefCountForTest(path))
        first.close()
        first.close()
        assertEquals(baseline + 1, JceksSecretStore.processLockRegistrySizeForTest())
        assertEquals(1, JceksSecretStore.processLockRefCountForTest(path))
        second.save("shared.alias", "secret".toCharArray())
        second.close()
        assertEquals(baseline, JceksSecretStore.processLockRegistrySizeForTest())
        assertNull(JceksSecretStore.processLockRefCountForTest(path))

        JceksSecretStore(path, password).use { reopened ->
            assertEquals("secret", reopened.load(SecretRef.parse("shared.alias"))?.concatToString())
        }
        assertEquals(baseline, JceksSecretStore.processLockRegistrySizeForTest())
    }

    @Test
    fun `process lock registry does not grow after many paths close`() {
        val baseline = JceksSecretStore.processLockRegistrySizeForTest()
        val root = Files.createTempDirectory("jceks-secret-lock-paths")

        repeat(64) { index ->
            JceksSecretStore(root.resolve("store-$index.jceks"), "password".toCharArray()).close()
        }

        assertEquals(baseline, JceksSecretStore.processLockRegistrySizeForTest())
    }

    @Test
    fun `concurrent upsert of one alias is atomic across instances`() = runBlocking {
        val path = Files.createTempDirectory("jceks-secret-upsert").resolve("store.jceks")
        val password = "password".toCharArray()
        val first = JceksSecretStore(path, password)
        val second = JceksSecretStore(path, password)
        val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val values = (1..32).map { "complete-secret-$it" }
        try {
            values.mapIndexed { index, value ->
                async(dispatcher) {
                    (if (index % 2 == 0) first else second).upsert("shared.upsert", value.toCharArray())
                }
            }.awaitAll()
        } finally {
            dispatcher.close()
            first.close()
            second.close()
        }

        JceksSecretStore(path, password).use { reopened ->
            assertTrue(reopened.load(SecretRef.parse("shared.upsert"))?.concatToString() in values)
        }
    }
}
