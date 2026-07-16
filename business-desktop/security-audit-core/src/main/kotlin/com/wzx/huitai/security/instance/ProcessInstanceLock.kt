package com.wzx.huitai.security.instance

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

class ProcessInstanceLock private constructor(
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val lockReleaser: (FileLock) -> Unit,
    private val channelCloser: (FileChannel) -> Unit,
) : AutoCloseable {
    private val closeMonitor = Any()
    private var lockReleased = false
    private var channelClosed = false

    override fun close() = synchronized(closeMonitor) {
        if (lockReleased && channelClosed) return@synchronized
        var firstFailure: Throwable? = null
        if (!lockReleased) {
            if (!fileLock.isValid) {
                lockReleased = true
            } else {
                try {
                    lockReleaser(fileLock)
                    lockReleased = true
                } catch (releaseFailure: Exception) {
                    firstFailure = releaseFailure
                }
            }
        }
        if (!channelClosed) {
            if (!channel.isOpen) {
                channelClosed = true
                lockReleased = true
            } else {
                try {
                    channelCloser(channel)
                    channelClosed = true
                    lockReleased = true
                } catch (closeFailure: Exception) {
                    firstFailure?.addSuppressed(closeFailure) ?: run { firstFailure = closeFailure }
                }
            }
        }
        firstFailure?.let { throw ProcessInstanceLockException("Unable to release process instance lock", it) }
        if (!lockReleased || !channelClosed) {
            throw ProcessInstanceLockException("Unable to release process instance lock")
        }
    }

    override fun toString(): String = "ProcessInstanceLock(path=[REDACTED])"

    companion object {
        fun acquire(path: Path): ProcessInstanceLock = acquire(
            path = path,
            channelOpener = { normalized ->
                FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            },
            tryLocker = FileChannel::tryLock,
            lockReleaser = FileLock::release,
            channelCloser = FileChannel::close,
        )

        internal fun acquire(
            path: Path,
            channelOpener: (Path) -> FileChannel = { normalized ->
                FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            },
            tryLocker: (FileChannel) -> FileLock? = FileChannel::tryLock,
            lockReleaser: (FileLock) -> Unit = FileLock::release,
            channelCloser: (FileChannel) -> Unit = FileChannel::close,
        ): ProcessInstanceLock {
            val normalized = path.toAbsolutePath().normalize()
            require(normalized.fileName != null) { "path must identify a file" }
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                Files.createDirectories(normalized.parent)
                channel = channelOpener(normalized)
                applyBestEffortPermissions(normalized)
                lock = try {
                    tryLocker(channel)
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) throw collision()
                return ProcessInstanceLock(channel, lock, lockReleaser, channelCloser)
            } catch (collision: ProcessInstanceLockException) {
                runCatching { lock?.release() }
                runCatching { channel?.close() }
                throw collision
            } catch (failure: Exception) {
                runCatching { lock?.release() }
                runCatching { channel?.close() }
                throw ProcessInstanceLockException("Unable to acquire process instance lock", failure)
            }
        }

        private fun collision() =
            ProcessInstanceLockException("Process instance lock is already held")

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
    }
}

class ProcessInstanceLockException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
