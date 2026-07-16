package com.wzx.huitai.security.secret

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

class JceksSecretStore(
    storePath: Path,
    password: CharArray,
    private val moveAtomically: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) : SecretStore {
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
            val protection = protection()
            try {
                val entry = keyStore.getEntry(alias, protection) as? KeyStore.SecretKeyEntry
                    ?: return@withStoreLock null
                decodeSecret(entry.secretKey.encoded)
            } finally {
                protection.destroy()
            }
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

    override fun close() {
        val releaseEntry = processLockEntry.lock.withLock {
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

    private fun <T> withStoreLock(block: () -> T): T = processLockEntry.lock.withLock {
        checkOpen()
        try {
            Files.createDirectories(parent)
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                applyBestEffortPermissions(lockPath)
                channel.lock().use { block() }
            }
        } catch (failure: SecretStoreException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            throw storeFailure("Unable to lock local secret store", failure)
        }
    }

    private fun loadStore(): KeyStore = try {
        KeyStore.getInstance(STORE_TYPE).apply {
            if (Files.notExists(storePath)) load(null, password)
            else Files.newInputStream(storePath).use { load(it, password) }
        }
    } catch (failure: Exception) {
        throw storeFailure("Unable to open local secret store", failure)
    }

    private fun setSecret(keyStore: KeyStore, alias: String, secret: CharArray) {
        val chars = secret.copyOf()
        val charBuffer = CharBuffer.wrap(chars)
        var encoded: ByteBuffer? = null
        val protection = protection()
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(charBuffer)
            val key = SecretKeySpec(
                encoded.array(),
                encoded.arrayOffset() + encoded.position(),
                encoded.remaining(),
                SECRET_ALGORITHM,
            )
            keyStore.setEntry(alias, KeyStore.SecretKeyEntry(key), protection)
        } finally {
            protection.destroy()
            encoded?.let { Arrays.fill(it.array(), 0) }
            Arrays.fill(chars, '\u0000')
            charBuffer.clear()
        }
    }

    private fun decodeSecret(encoded: ByteArray): CharArray {
        var decoded: CharBuffer? = null
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded))
            return CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            Arrays.fill(encoded, 0)
            decoded?.let {
                if (it.hasArray()) Arrays.fill(it.array(), '\u0000')
                it.clear()
            }
        }
    }

    private fun persist(keyStore: KeyStore) {
        val temp = Files.createTempFile(parent, ".${storePath.fileName}.", ".tmp")
        try {
            val encodedStore = WipeableByteArrayOutputStream()
            try {
                keyStore.store(encodedStore, password)
                FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    encodedStore.writeTo(channel)
                    channel.force(true)
                }
            } finally {
                encodedStore.wipe()
            }
            applyBestEffortPermissions(temp)
            moveAtomically(temp, storePath)
            runCatching {
                FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
            }
            applyBestEffortPermissions(storePath)
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw storeFailure("Unable to persist local secret store", failure)
        }
    }

    private fun protection() = KeyStore.PasswordProtection(password.copyOf())

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

    private fun storeFailure(message: String, cause: Throwable) = SecretStoreException(message, cause)

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
