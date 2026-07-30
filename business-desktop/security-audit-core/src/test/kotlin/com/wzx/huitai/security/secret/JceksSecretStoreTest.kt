package com.wzx.huitai.security.secret

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.DestroyFailedException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.concurrent.thread
import com.wzx.huitai.security.path.SecureRuntimeFile

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
    fun `secret store failures expose one fixed cause free public surface`() {
        val rawMessage = "candidate C:\\private\\secrets.jceks contains alias provider.private"
        val rawCause = IllegalStateException("destroy-private-canary secret-private-canary")

        val failure = SecretStoreException(rawMessage, rawCause)

        assertEquals("Local secret store operation failed", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        val rendered = failure.stackTraceToString()
        listOf(rawMessage, rawCause.message!!, "provider.private", "secrets.jceks").forEach { forbidden ->
            assertFalse(forbidden in rendered)
        }
    }

    @Test
    fun `primary and destroy failures preserve primary and wipe every write buffer`() {
        val root = Files.createTempDirectory("jceks-secret-primary-destroy")
        val primary = IllegalArgumentException("primary-entry-failure")
        val wipedBytes = mutableListOf<ByteArray>()
        val wipedChars = mutableListOf<CharArray>()
        lateinit var protection: PrimaryAndDestroyFailureProtection
        val store = JceksSecretStore(
            root.resolve("store.jceks"),
            "password".toCharArray(),
            operations = JceksSecretOperations(
                passwordProtectionFactory = { password ->
                    PrimaryAndDestroyFailureProtection(password, primary).also { protection = it }
                },
                wipeBytes = { buffer ->
                    wipedBytes += buffer
                    buffer.fill(0)
                },
                wipeChars = { buffer ->
                    wipedChars += buffer
                    buffer.fill('\u0000')
                },
            ),
        )
        try {
            val failure = assertFails {
                store.upsert("provider.primary", "private-secret".toCharArray())
            }

            assertEquals(1, protection.destroyCalls)
            assertTrue(wipedBytes.isNotEmpty())
            assertTrue(wipedChars.isNotEmpty())
            assertTrue(wipedBytes.all { buffer -> buffer.all { it == 0.toByte() } })
            assertTrue(wipedChars.all { buffer -> buffer.all { it == '\u0000' } })
            assertSame(primary, failure)
            assertTrue(failure.suppressed.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `successful reads wipe encoded and decoded buffers`() {
        val root = Files.createTempDirectory("jceks-secret-read-wipe")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        val ref = JceksSecretStore(path, password).use { stable ->
            stable.upsert("provider.read-wipe", "private-secret".toCharArray())
        }
        val wipedBytes = mutableListOf<ByteArray>()
        val wipedChars = mutableListOf<CharArray>()

        JceksSecretStore(
            path,
            password,
            operations = JceksSecretOperations(
                wipeBytes = { buffer ->
                    wipedBytes += buffer
                    buffer.fill(0)
                },
                wipeChars = { buffer ->
                    wipedChars += buffer
                    buffer.fill('\u0000')
                },
            ),
        ).use { store ->
            val loaded = store.load(ref)
            try {
                assertEquals("private-secret", loaded?.concatToString())
            } finally {
                loaded?.fill('\u0000')
            }
        }

        assertTrue(wipedBytes.isNotEmpty())
        assertTrue(wipedChars.isNotEmpty())
        assertTrue(wipedBytes.all { buffer -> buffer.all { it == 0.toByte() } })
        assertTrue(wipedChars.all { buffer -> buffer.all { it == '\u0000' } })
    }

    @Test
    fun `password protection uses the store owned password without an intermediate copy`() {
        val root = Files.createTempDirectory("jceks-secret-password-copy")
        val callerPassword = "store-password".toCharArray()
        var factoryPassword: CharArray? = null
        val store = JceksSecretStore(
            root.resolve("store.jceks"),
            callerPassword,
            operations = JceksSecretOperations(
                passwordProtectionFactory = { password ->
                    factoryPassword = password
                    KeyStore.PasswordProtection(password)
                },
            ),
        )

        store.upsert("provider.password-copy", "private-secret".toCharArray())
        assertTrue(factoryPassword !== callerPassword)
        assertEquals("store-password", factoryPassword?.concatToString())
        store.close()
        assertTrue(factoryPassword?.all { it == '\u0000' } == true)
        assertEquals("store-password", callerPassword.concatToString())

        val source = Files.readString(
            Path.of("src/main/kotlin/com/wzx/huitai/security/secret/JceksSecretStore.kt"),
        )
        val protection = source.substringAfter("private fun protection()")
            .substringBefore("private fun checkOpen()")
        assertTrue(protection.contains("passwordProtectionFactory(password)"))
        assertFalse(protection.contains("password.copyOf()"))
    }

    @Test
    fun `resource close failure is rewrapped after unwind without cause or suppressed details`() {
        val root = Files.createTempDirectory("jceks-secret-close-failure")
        val path = root.resolve("private-store.jceks")
        path.writeBytes(byteArrayOf(1, 2, 3, 4))
        val closeCanary = IOException("close-private-canary $path")
        val releaseCalls = AtomicInteger()
        val store = JceksSecretStore(
            path,
            "password".toCharArray(),
            operations = JceksSecretOperations(
                fileLockFactory = { channel ->
                    object : FileLock(channel, 0L, 1L, false) {
                        private var valid = true

                        override fun isValid(): Boolean = valid

                        override fun release() {
                            valid = false
                            releaseCalls.incrementAndGet()
                            throw closeCanary
                        }
                    }
                },
            ),
        )

        val failure = try {
            assertFailsWith<SecretStoreException> {
                store.load(SecretRef.parse("provider.close-failure"))
            }
        } finally {
            store.close()
        }

        assertEquals(1, releaseCalls.get())
        assertEquals("Local secret store operation failed", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        val rendered = failure.stackTraceToString()
        assertFalse(closeCanary.message!! in rendered)
        assertFalse(path.toString() in rendered)
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
    fun `atomic alias deletion removes exact targets preserves sentinels and is idempotent`() {
        val root = Files.createTempDirectory("jceks-secret-delete-aliases")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        JceksSecretStore(path, password).use { store ->
            LEGACY_ALIASES.forEachIndexed { index, alias ->
                store.upsert(alias, "unknown-envelope-$index".toCharArray())
            }
            store.upsert(VAULT_ALIAS, "vault-secret".toCharArray())
            store.upsert(PROVIDER_ALIAS, "provider-secret".toCharArray())

            assertTrue(store.deleteAliasesAtomically(LEGACY_ALIASES))
            assertFalse(store.deleteAliasesAtomically(LEGACY_ALIASES))
        }

        JceksSecretStore(path, password).use { reopened ->
            LEGACY_ALIASES.forEach { alias -> assertNull(reopened.load(SecretRef.parse(alias))) }
            assertEquals("vault-secret", reopened.load(SecretRef.parse(VAULT_ALIAS))?.concatToString())
            assertEquals("provider-secret", reopened.load(SecretRef.parse(PROVIDER_ALIAS))?.concatToString())
        }
        assertOwnerOnly(path)
    }

    @Test
    fun `atomic alias deletion snapshots a caller set exactly once before traversal`() {
        val root = Files.createTempDirectory("jceks-secret-delete-stateful-set")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        seedAliases(path, password)
        val iteratorCalls = AtomicInteger()
        val statefulAliases = object : AbstractSet<String>() {
            override val size: Int = LEGACY_ALIASES.size

            override fun iterator(): Iterator<String> {
                check(iteratorCalls.incrementAndGet() == 1) { "caller alias set traversed more than once" }
                return LEGACY_ALIASES.iterator()
            }
        }

        JceksSecretStore(path, password).use { store ->
            assertTrue(store.deleteAliasesAtomically(statefulAliases))
        }

        assertEquals(1, iteratorCalls.get())
        JceksSecretStore(path, password).use { reopened ->
            LEGACY_ALIASES.forEach { alias -> assertNull(reopened.load(SecretRef.parse(alias))) }
            assertEquals("vault-secret", reopened.load(SecretRef.parse(VAULT_ALIAS))?.concatToString())
        }
    }

    @Test
    fun `atomic alias deletion move failure preserves original bytes and every target`() {
        val root = Files.createTempDirectory("jceks-secret-delete-move-failure")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        seedAliases(path, password)
        val original = path.readBytes()

        JceksSecretStore(
            path,
            password,
            moveAtomically = { source, target ->
                throw java.nio.file.AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "unsupported",
                )
            },
        ).use { failing ->
            assertFailsWith<SecretStoreException> { failing.deleteAliasesAtomically(LEGACY_ALIASES) }
        }

        assertTrue(original.contentEquals(path.readBytes()))
        assertSeededAliasesRemain(path, password)
        assertNoTemporaryKeyStores(root)
    }

    @Test
    fun `atomic alias deletion candidate reopen failure preserves original bytes and every target`() {
        val root = Files.createTempDirectory("jceks-secret-delete-reopen-failure")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        seedAliases(path, password)
        val original = path.readBytes()

        JceksSecretStore(
            path,
            password,
            beforeCandidateValidation = { candidate ->
                Files.write(candidate, byteArrayOf(1, 2, 3, 4), StandardOpenOption.TRUNCATE_EXISTING)
            },
        ).use { failing ->
            assertFailsWith<SecretStoreException> { failing.deleteAliasesAtomically(LEGACY_ALIASES) }
        }

        assertTrue(original.contentEquals(path.readBytes()))
        assertSeededAliasesRemain(path, password)
        assertNoTemporaryKeyStores(root)
    }

    @Test
    fun `atomic alias deletion rejects a candidate where any target alias remains`() {
        val root = Files.createTempDirectory("jceks-secret-delete-validation-failure")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        seedAliases(path, password)
        val original = path.readBytes()

        JceksSecretStore(
            path,
            password,
            beforeCandidateValidation = { candidate ->
                insertCandidateAlias(candidate, password, LEGACY_ALIASES.first())
            },
        ).use { failing ->
            assertFailsWith<SecretStoreException> { failing.deleteAliasesAtomically(LEGACY_ALIASES) }
        }

        assertTrue(original.contentEquals(path.readBytes()))
        assertSeededAliasesRemain(path, password)
        assertNoTemporaryKeyStores(root)
    }

    @Test
    fun `atomic alias deletion does not rewrite when no target exists`() {
        val root = Files.createTempDirectory("jceks-secret-delete-noop")
        val path = root.resolve("store.jceks")
        val password = "password".toCharArray()
        JceksSecretStore(path, password).use { it.upsert(VAULT_ALIAS, "vault-secret".toCharArray()) }
        val original = path.readBytes()
        val moves = AtomicInteger()

        JceksSecretStore(
            path,
            password,
            moveAtomically = { source, target ->
                moves.incrementAndGet()
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
        ).use { store ->
            assertFalse(store.deleteAliasesAtomically(LEGACY_ALIASES))
        }

        assertEquals(0, moves.get())
        assertTrue(original.contentEquals(path.readBytes()))
        assertNoTemporaryKeyStores(root)
    }

    @Test
    fun `atomic replacement performs every throwing validation before the successful move`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/wzx/huitai/security/secret/JceksSecretStore.kt"),
        )
        val replacement = source.substringAfter("private fun replaceWithCandidate(candidate: Path)")
            .substringBefore("private fun protection()")
        val move = "moveAtomically(candidate, storePath)"
        assertTrue(replacement.contains(move), "atomic replacement must publish the validated candidate")
        val afterMove = replacement.substringAfter(move)

        assertFalse(
            afterMove.contains("SecureRuntimeFile.capture"),
            "a throwable target validation after atomic move can report failure after replacing original bytes",
        )
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

    @Test
    fun `process lock acquisition and close fail within the configured deadline`() {
        val path = Files.createTempDirectory("jceks-process-lock-timeout").resolve("store.jceks")
        val enteredMove = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        val first = JceksSecretStore(
            storePath = path,
            password = "password".toCharArray(),
            moveAtomically = { source, target ->
                enteredMove.countDown()
                releaseMove.await()
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            },
            lockTimeoutMillis = 2_000,
        )
        val second = JceksSecretStore(path, "password".toCharArray(), lockTimeoutMillis = 50)
        var workerFailure: Throwable? = null
        val worker = thread(start = true) {
            runCatching { first.upsert("held.alias", "secret".toCharArray()) }
                .onFailure { workerFailure = it }
        }
        assertTrue(enteredMove.await(2, TimeUnit.SECONDS))
        try {
            assertFailsWith<SecretStoreException> { second.load(SecretRef.parse("held.alias")) }
            assertFailsWith<SecretStoreException> { second.close() }
        } finally {
            releaseMove.countDown()
            worker.join(2_000)
            second.close()
            first.close()
        }
        assertNull(workerFailure)
    }

    @Test
    fun `cross process file lock acquisition fails within the configured deadline`() {
        val path = Files.createTempDirectory("jceks-file-lock-timeout").resolve("store.jceks")
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        val identity = SecureRuntimeFile.prepare(lockPath)
        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            SecureRuntimeFile.verifyUnchanged(identity)
            channel.lock().use {
                JceksSecretStore(path, "password".toCharArray(), lockTimeoutMillis = 50).use { store ->
                    assertFailsWith<SecretStoreException> { store.load(SecretRef.parse("missing.alias")) }
                }
            }
        }
    }

    private fun seedAliases(path: Path, password: CharArray) {
        JceksSecretStore(path, password).use { store ->
            LEGACY_ALIASES.forEachIndexed { index, alias ->
                store.upsert(alias, "opaque-legacy-$index".toCharArray())
            }
            store.upsert(VAULT_ALIAS, "vault-secret".toCharArray())
        }
    }

    private fun assertSeededAliasesRemain(path: Path, password: CharArray) {
        JceksSecretStore(path, password).use { reopened ->
            LEGACY_ALIASES.forEachIndexed { index, alias ->
                assertEquals(
                    "opaque-legacy-$index",
                    reopened.load(SecretRef.parse(alias))?.concatToString(),
                )
            }
            assertEquals("vault-secret", reopened.load(SecretRef.parse(VAULT_ALIAS))?.concatToString())
        }
    }

    private fun insertCandidateAlias(path: Path, password: CharArray, alias: String) {
        val keyStore = KeyStore.getInstance("JCEKS")
        Files.newInputStream(path).use { keyStore.load(it, password) }
        val protection = KeyStore.PasswordProtection(password.copyOf())
        val encoded = "must-be-rejected".toByteArray()
        try {
            keyStore.setEntry(
                alias,
                KeyStore.SecretKeyEntry(SecretKeySpec(encoded, "RAW")),
                protection,
            )
            Files.newOutputStream(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use {
                keyStore.store(it, password)
            }
        } finally {
            encoded.fill(0)
            protection.destroy()
        }
    }

    private fun assertNoTemporaryKeyStores(root: Path) {
        assertEquals(
            emptyList(),
            Files.list(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
            },
        )
    }

    private fun assertOwnerOnly(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
            )
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        assertTrue(acl != null, "secret store requires POSIX permissions or Windows ACL support")
        val owner = Files.getOwner(path)
        assertTrue(acl.acl.isNotEmpty())
        assertTrue(acl.acl.all { entry -> entry.type() == AclEntryType.ALLOW && entry.principal() == owner })
    }

    private class PrimaryAndDestroyFailureProtection(
        password: CharArray,
        private val primaryFailure: IllegalArgumentException,
    ) : KeyStore.PasswordProtection(password) {
        var destroyCalls: Int = 0
            private set

        override fun getPassword(): CharArray = throw primaryFailure

        override fun destroy() {
            destroyCalls += 1
            throw DestroyFailedException("destroy-private-canary")
        }
    }

    private companion object {
        val LEGACY_ALIASES = linkedSetOf(
            "huitai.auth.tokens.v1",
            "huitai.auth.session-metadata.v1",
            "huitai.login.remembered.v1",
        )
        const val VAULT_ALIAS = "huitai.backend.keystore.password.v1"
        const val PROVIDER_ALIAS = "provider.deepseek.v1"
    }
}
