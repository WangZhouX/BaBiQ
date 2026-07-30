package com.wzx.huitai.security.secret

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.util.Arrays
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.spec.SecretKeySpec
import com.wzx.huitai.security.path.SecureRuntimeFile

internal class JceksSecretOperations(
    val passwordProtectionFactory: (CharArray) -> KeyStore.PasswordProtection = { password ->
        KeyStore.PasswordProtection(password)
    },
    val wipeBytes: (ByteArray) -> Unit = { buffer -> Arrays.fill(buffer, 0) },
    val wipeChars: (CharArray) -> Unit = { buffer -> Arrays.fill(buffer, '\u0000') },
    val fileLockFactory: ((FileChannel) -> FileLock)? = null,
)

private fun moveSecretStoreAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Synchronous JCEKS storage with deadline-bounded in-process and cross-process lock acquisition.
 *
 * The JDK does not provide portable cancellation for ordinary filesystem reads, writes, or fsync. Those operations
 * therefore stay synchronous on the calling thread (never continue as late background deletion); every explicit lock
 * wait surrounding them has the configured finite deadline.
 */
class JceksSecretStore private constructor(
    storePath: Path,
    password: CharArray,
    private val lockTimeoutMillis: Long,
    private val beforeCandidateValidation: (Path) -> Unit,
    private val operations: JceksSecretOperations,
    private val moveAtomically: (Path, Path) -> Unit,
) : SecretStore {
    constructor(
        storePath: Path,
        password: CharArray,
        lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
        beforeCandidateValidation: (Path) -> Unit = {},
        moveAtomically: (Path, Path) -> Unit = ::moveSecretStoreAtomically,
    ) : this(
        storePath,
        password,
        lockTimeoutMillis,
        beforeCandidateValidation,
        JceksSecretOperations(),
        moveAtomically,
    )

    internal constructor(
        storePath: Path,
        password: CharArray,
        operations: JceksSecretOperations,
        lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
        beforeCandidateValidation: (Path) -> Unit = {},
        moveAtomically: (Path, Path) -> Unit = ::moveSecretStoreAtomically,
    ) : this(
        storePath,
        password,
        lockTimeoutMillis,
        beforeCandidateValidation,
        operations,
        moveAtomically,
    )

    init {
        require(lockTimeoutMillis > 0) { "lockTimeoutMillis must be positive" }
    }

    private val storePath = normalizeStorePath(storePath)
    private val lockPath = this.storePath.resolveSibling("${this.storePath.fileName}.lock")
    private val parent = this.storePath.parent
    private val password = validatedPasswordCopy(password)
    private val processLockEntry = acquireProcessLock(this.storePath)
    private var closed = false

    override fun save(alias: String, secret: CharArray): SecretRef = withStoreLock {
        validateAlias(alias)
        validateSecret(secret)
        val keyStore = loadStore()
        require(!keyStore.containsAlias(alias)) { "secret alias already exists" }
        setSecret(keyStore, alias, secret)
        persist(keyStore)
        SecretRef.parse(alias)
    }

    override fun upsert(alias: String, secret: CharArray): SecretRef = withStoreLock {
        validateAlias(alias)
        validateSecret(secret)
        val keyStore = loadStore()
        setSecret(keyStore, alias, secret)
        persist(keyStore)
        SecretRef.parse(alias)
    }

    override fun load(ref: SecretRef): CharArray? = withStoreLock {
        val alias = validateRef(ref)
        try {
            val keyStore = loadStore()
            if (!keyStore.containsAlias(alias)) return@withStoreLock null
            val entry = withPasswordProtection { protection ->
                keyStore.getEntry(alias, protection)
            } as? KeyStore.SecretKeyEntry ?: return@withStoreLock null
            decodeSecret(entry.secretKey.encoded)
        } catch (failure: SecretStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw storeFailure("Unable to load local secret store", failure)
        }
    }

    override fun replace(ref: SecretRef, secret: CharArray) = withStoreLock {
        val alias = validateRef(ref)
        validateSecret(secret)
        val keyStore = loadStore()
        require(keyStore.containsAlias(alias)) { "secret alias does not exist" }
        setSecret(keyStore, alias, secret)
        persist(keyStore)
    }

    override fun delete(ref: SecretRef): Boolean = withStoreLock {
        val alias = validateRef(ref)
        val keyStore = loadStore()
        if (!keyStore.containsAlias(alias)) return@withStoreLock false
        keyStore.deleteEntry(alias)
        persist(keyStore)
        true
    }

    fun deleteAliasesAtomically(aliases: Set<String>): Boolean {
        val aliasesSnapshot = aliases.toSet()
        return withStoreLock {
            aliasesSnapshot.forEach(::validateAlias)
            if (aliasesSnapshot.isEmpty()) return@withStoreLock false
            val keyStore = loadStore()
            val originalAliases = keyStore.aliases().asSequence().toSet()
            val deletedAliases = aliasesSnapshot.filterTo(linkedSetOf(), keyStore::containsAlias)
            if (deletedAliases.isEmpty()) return@withStoreLock false
            deletedAliases.forEach(keyStore::deleteEntry)
            persistValidatedDeletionCandidate(
                keyStore = keyStore,
                expectedAliases = originalAliases - deletedAliases,
                deletedAliases = deletedAliases,
            )
            true
        }
    }

    override fun close() {
        val releaseEntry = withProcessLock {
            if (closed) {
                false
            } else {
                Arrays.fill(password, '\u0000')
                closed = true
                true
            }
        }
        if (releaseEntry) releaseProcessLock(storePath, processLockEntry)
    }

    override fun toString(): String = "JceksSecretStore(path=[REDACTED], password=[REDACTED])"

    private fun <T> withStoreLock(block: () -> T): T = withProcessLock {
        try {
            checkOpen()
            val lockIdentity = SecureRuntimeFile.prepare(lockPath)
            SecureRuntimeFile.openChannel(lockPath, StandardOpenOption.WRITE).use { channel ->
                SecureRuntimeFile.verifyUnchanged(lockIdentity)
                applyBestEffortPermissions(lockPath)
                acquireFileLock(channel).use { block() }
            }
        } catch (_: SecretStoreException) {
            // Resource close failures may have been attached as suppressed while nested use blocks unwound.
            throw SecretStoreException()
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            throw storeFailure("Unable to lock local secret store", failure)
        }
    }

    private fun <T> withProcessLock(block: () -> T): T {
        var acquired = false
        try {
            acquired = processLockEntry.lock.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!acquired) throw storeFailure("Timed out waiting for local secret store process lock", null)
            return block()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw storeFailure("Interrupted while waiting for local secret store process lock", interrupted)
        } finally {
            if (acquired) processLockEntry.lock.unlock()
        }
    }

    private fun acquireFileLock(channel: FileChannel): FileLock {
        operations.fileLockFactory?.let { factory -> return factory(channel) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis)
        while (true) {
            val acquired = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquired != null) return acquired
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw storeFailure("Timed out waiting for local secret store file lock", null)
            }
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, FILE_LOCK_RETRY_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw storeFailure("Interrupted while waiting for local secret store file lock", interrupted)
            }
        }
    }

    private fun loadStore(): KeyStore = try {
        KeyStore.getInstance(STORE_TYPE).apply {
            val storeIdentity = SecureRuntimeFile.captureIfExists(storePath)
            if (storeIdentity == null) {
                load(null, password)
            } else {
                Files.newInputStream(storePath, LinkOption.NOFOLLOW_LINKS).use { load(it, password) }
                SecureRuntimeFile.verifyUnchanged(storeIdentity)
            }
        }
    } catch (failure: Exception) {
        throw storeFailure("Unable to open local secret store", failure)
    }

    private fun setSecret(keyStore: KeyStore, alias: String, secret: CharArray) {
        val chars = secret.copyOf()
        val charBuffer = CharBuffer.wrap(chars)
        var encoded: ByteBuffer? = null
        withBestEffortCleanup(
            cleanupSteps = listOf(
                { encoded?.let { operations.wipeBytes(it.array()) } },
                { operations.wipeChars(chars) },
                { charBuffer.clear() },
            ),
        ) {
            val encodedBuffer = StandardCharsets.UTF_8.newEncoder().encode(charBuffer)
            encoded = encodedBuffer
            val key = SecretKeySpec(
                encodedBuffer.array(),
                encodedBuffer.arrayOffset() + encodedBuffer.position(),
                encodedBuffer.remaining(),
                SECRET_ALGORITHM,
            )
            withPasswordProtection { protection ->
                keyStore.setEntry(alias, KeyStore.SecretKeyEntry(key), protection)
            }
        }
    }

    private fun decodeSecret(encoded: ByteArray): CharArray {
        var decoded: CharBuffer? = null
        return withBestEffortCleanup(
            cleanupSteps = listOf(
                { operations.wipeBytes(encoded) },
                {
                    decoded?.let { buffer ->
                        if (buffer.hasArray()) operations.wipeChars(buffer.array())
                    }
                },
                { decoded?.clear() },
            ),
        ) {
            val decodedBuffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded))
            decoded = decodedBuffer
            CharArray(decodedBuffer.remaining()).also(decodedBuffer::get)
        }
    }

    private fun persist(keyStore: KeyStore) {
        SecureRuntimeFile.validateParent(storePath)
        val temp = Files.createTempFile(parent, ".${storePath.fileName}.", ".tmp")
        try {
            writeCandidate(keyStore, temp)
            applyBestEffortPermissions(temp)
            replaceWithCandidate(temp)
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw storeFailure("Unable to persist local secret store", failure)
        }
    }

    private fun persistValidatedDeletionCandidate(
        keyStore: KeyStore,
        expectedAliases: Set<String>,
        deletedAliases: Set<String>,
    ) {
        SecureRuntimeFile.validateParent(storePath)
        val temp = Files.createTempFile(parent, ".${storePath.fileName}.", ".tmp")
        try {
            writeCandidate(keyStore, temp)
            applyBestEffortPermissions(temp)
            beforeCandidateValidation(temp)
            val candidateAliases = loadCandidateAliases(temp)
            check(candidateAliases == expectedAliases && deletedAliases.none(candidateAliases::contains)) {
                "candidate secret store alias validation failed"
            }
            replaceWithCandidate(temp)
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw storeFailure("Unable to persist local secret store", failure)
        }
    }

    private fun writeCandidate(keyStore: KeyStore, candidate: Path) {
        val candidateIdentity = SecureRuntimeFile.capture(candidate)
        val encodedStore = WipeableByteArrayOutputStream()
        try {
            keyStore.store(encodedStore, password)
            SecureRuntimeFile.openChannel(
                candidate,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                encodedStore.writeTo(channel)
                channel.force(true)
            }
            SecureRuntimeFile.verifyUnchanged(candidateIdentity)
        } finally {
            encodedStore.wipe()
        }
    }

    private fun loadCandidateAliases(candidate: Path): Set<String> = try {
        val candidateIdentity = SecureRuntimeFile.capture(candidate)
        val keyStore = KeyStore.getInstance(STORE_TYPE)
        Files.newInputStream(candidate, LinkOption.NOFOLLOW_LINKS).use { keyStore.load(it, password) }
        SecureRuntimeFile.verifyUnchanged(candidateIdentity)
        keyStore.aliases().asSequence().toSet()
    } catch (failure: Exception) {
        throw storeFailure("Unable to validate local secret store candidate", failure)
    }

    private fun replaceWithCandidate(candidate: Path) {
        SecureRuntimeFile.captureIfExists(storePath)
        moveAtomically(candidate, storePath)
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        }
        applyBestEffortPermissions(storePath)
    }

    private fun <T> withPasswordProtection(operation: (KeyStore.PasswordProtection) -> T): T {
        val protection = protection()
        return withBestEffortCleanup(
            cleanupSteps = listOf({ protection.destroy() }),
        ) {
            operation(protection)
        }
    }

    private fun <T> withBestEffortCleanup(
        cleanupSteps: List<() -> Unit>,
        operation: () -> T,
    ): T {
        var primaryFailure: Throwable? = null
        try {
            return operation()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var firstCleanupFailure: Throwable? = null
            cleanupSteps.forEach { cleanup ->
                try {
                    cleanup()
                } catch (cleanupFailure: Throwable) {
                    if (primaryFailure == null && firstCleanupFailure == null) {
                        firstCleanupFailure = cleanupFailure
                    }
                }
            }
            if (primaryFailure == null) firstCleanupFailure?.let { throw it }
        }
    }

    private fun protection() = operations.passwordProtectionFactory(password)

    private fun checkOpen() = check(!closed) { "secret store is closed" }
    private fun validateRef(ref: SecretRef): String = ref.alias.also(::validateAlias)
    private fun validateAlias(alias: String) {
        require(SecretRef.isValidAlias(alias)) { "invalid secret alias" }
    }

    private fun validateSecret(secret: CharArray) {
        require(secret.isNotEmpty()) { "secret must not be empty" }
    }

    private fun applyBestEffortPermissions(path: Path) {
        val posixApplied = runCatching {
            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return@runCatching false
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            true
        }.getOrDefault(false)
        if (posixApplied) return

        val aclApplied = runCatching {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return@runCatching false
            val owner = Files.getOwner(path)
            view.acl = listOf(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                    .build(),
            )
            true
        }.getOrDefault(false)
        if (!aclApplied) runCatching { Files.setAttribute(path, "dos:hidden", true) }
    }

    private fun storeFailure(
        @Suppress("UNUSED_PARAMETER") message: String,
        @Suppress("UNUSED_PARAMETER") cause: Throwable?,
    ) = SecretStoreException()

    private class WipeableByteArrayOutputStream : ByteArrayOutputStream() {
        fun writeTo(channel: FileChannel) {
            val data = ByteBuffer.wrap(buf, 0, count)
            while (data.hasRemaining()) channel.write(data)
        }

        fun wipe() {
            Arrays.fill(buf, 0)
            reset()
        }
    }

    internal companion object {
        private const val STORE_TYPE = "JCEKS"
        private const val SECRET_ALGORITHM = "RAW"
        private const val DEFAULT_LOCK_TIMEOUT_MILLIS = 5_000L
        private val FILE_LOCK_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(10)
        private val PROCESS_LOCKS = ConcurrentHashMap<Path, ProcessLockEntry>()

        private fun normalizeStorePath(path: Path): Path {
            require(path.fileName != null) { "storePath must identify a file" }
            return path.toAbsolutePath().normalize()
        }

        private fun validatedPasswordCopy(password: CharArray): CharArray {
            require(password.isNotEmpty()) { "password must not be empty" }
            return password.copyOf()
        }

        private fun acquireProcessLock(path: Path): ProcessLockEntry =
            PROCESS_LOCKS.compute(path) { _, current -> current?.retain() ?: ProcessLockEntry(refs = 1) }!!

        private fun releaseProcessLock(path: Path, entry: ProcessLockEntry) {
            PROCESS_LOCKS.compute(path) { _, current ->
                require(current === entry) { "process lock registry entry changed unexpectedly" }
                if (entry.release() == 0) null else entry
            }
        }

        internal fun processLockRegistrySizeForTest(): Int = PROCESS_LOCKS.size

        internal fun processLockRefCountForTest(path: Path): Int? {
            var refs: Int? = null
            PROCESS_LOCKS.compute(normalizeStorePath(path)) { _, current ->
                refs = current?.refs
                current
            }
            return refs
        }
    }

    private class ProcessLockEntry(
        val lock: ReentrantLock = ReentrantLock(),
        var refs: Int,
    ) {
        fun retain(): ProcessLockEntry {
            refs += 1
            return this
        }

        fun release(): Int {
            refs -= 1
            return refs
        }
    }
}
