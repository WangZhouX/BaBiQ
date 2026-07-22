package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.path.SecureRuntimeFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

interface BusinessAuthRevocationMarkerPort {
    fun isRevoked(): Boolean
    fun markRevoked()
    fun clearAfterExplicitLogin()
}

/** Two-location marker: either surviving copy keeps restore fail-closed. */
class RedundantBusinessAuthRevocationMarkerStore(
    private val primary: BusinessAuthRevocationMarkerPort,
    private val fallback: BusinessAuthRevocationMarkerPort,
) : BusinessAuthRevocationMarkerPort {
    override fun isRevoked(): Boolean {
        val primaryAuthorized = try {
            !primary.isRevoked()
        } catch (_: Throwable) {
            false
        }
        val fallbackAuthorized = try {
            !fallback.isRevoked()
        } catch (_: Throwable) {
            false
        }
        return !(primaryAuthorized && fallbackAuthorized)
    }

    override fun markRevoked() {
        runBoth("persist") { it.markRevoked() }
    }

    override fun clearAfterExplicitLogin() {
        runBoth("clear") { it.clearAfterExplicitLogin() }
    }

    private fun runBoth(action: String, block: (BusinessAuthRevocationMarkerPort) -> Unit) {
        var successes = 0
        var failure: Throwable? = null
        listOf(primary, fallback).forEach { store ->
            try {
                block(store)
                successes += 1
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        val requiredSuccesses = if (action == "clear") 2 else 1
        if (successes < requiredSuccesses) {
            throw IllegalStateException("unable to $action authentication revocation markers", failure)
        }
    }
}

/** Non-sensitive durable authorization state that survives credential deletion failures and process restarts. */
class FileBusinessAuthRevocationMarkerStore(
    path: Path,
    private val permissionApplier: (Path, Boolean) -> Unit = ::applyVerifiedOwnerOnlyPermissions,
    private val beforeLeafCreate: () -> Unit = {},
) : BusinessAuthRevocationMarkerPort {
    private val path = path.toAbsolutePath().normalize()

    override fun isRevoked(): Boolean {
        val identity = SecureRuntimeFile.captureIfExists(path) ?: return true
        SecureRuntimeFile.openChannel(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(MAX_STATE_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            SecureRuntimeFile.verifyUnchanged(identity)
            buffer.flip()
            val content = ByteArray(buffer.remaining())
            buffer.get(content)
            return !content.contentEquals(AUTHORIZED_BYTES)
        }
    }

    override fun markRevoked() = writeState(REVOKED_BYTES)

    override fun clearAfterExplicitLogin() = writeState(AUTHORIZED_BYTES)

    private fun writeState(state: ByteArray) {
        SecureRuntimeFile.validateParent(path)
        permissionApplier(path.parent, true)
        val parentIdentity = captureParentIdentity()
        SecureRuntimeFile.captureIfExists(path)
        beforeLeafCreate()
        verifyParentIdentity(parentIdentity)
        val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            val tempIdentity = SecureRuntimeFile.capture(temp)
            permissionApplier(temp, false)
            verifyParentIdentity(parentIdentity)
            SecureRuntimeFile.openChannel(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                verifyParentIdentity(parentIdentity)
                val buffer = ByteBuffer.wrap(state)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            SecureRuntimeFile.verifyUnchanged(tempIdentity)
            permissionApplier(temp, false)
            verifyParentIdentity(parentIdentity)
            Files.move(
                temp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            SecureRuntimeFile.capture(path)
            permissionApplier(path, false)
            forceParentDirectory()
        } catch (failure: Throwable) {
            runCatching {
                if (!Files.isSymbolicLink(temp)) Files.deleteIfExists(temp)
            }
            throw failure
        }
    }

    override fun toString(): String = "FileBusinessAuthRevocationMarkerStore(path=[REDACTED])"

    private fun forceParentDirectory() {
        if (Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java) != null) {
            SecureRuntimeFile.openChannel(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } else {
            // The JDK does not expose directory fsync on non-POSIX providers such as the Windows default provider.
        }
    }

    private fun captureParentIdentity(): ParentIdentity {
        val attributes = Files.readAttributes(
            path.parent,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(!Files.isSymbolicLink(path.parent) && !attributes.isOther && attributes.isDirectory) {
            "authentication revocation marker parent must be a real directory"
        }
        return ParentIdentity(attributes.fileKey(), attributes.creationTime().toMillis())
    }

    private fun verifyParentIdentity(expected: ParentIdentity) {
        val current = captureParentIdentity()
        val sameFileKey = expected.fileKey == null || current.fileKey == null || expected.fileKey == current.fileKey
        require(sameFileKey && expected.creationMillis == current.creationMillis) {
            "authentication revocation marker parent changed during creation"
        }
    }

    private companion object {
        val REVOKED_BYTES = "REVOKED v1\n".toByteArray(Charsets.US_ASCII)
        val AUTHORIZED_BYTES = "AUTHORIZED v1\n".toByteArray(Charsets.US_ASCII)
        val MAX_STATE_BYTES = maxOf(REVOKED_BYTES.size, AUTHORIZED_BYTES.size)
    }

    private data class ParentIdentity(val fileKey: Any?, val creationMillis: Long)
}

private fun applyVerifiedOwnerOnlyPermissions(target: Path, directory: Boolean) {
    val posixView = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
    if (posixView != null) {
        val expected = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (directory) expected += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(target, expected)
        require(posixView.readAttributes().permissions() == expected) {
            "authentication revocation marker permissions were not applied"
        }
        return
    }

    val aclView = Files.getFileAttributeView(target, AclFileAttributeView::class.java)
    if (aclView != null) {
        val owner = Files.getOwner(target)
        val permissions = EnumSet.allOf(AclEntryPermission::class.java)
        aclView.acl = listOf(
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(permissions)
                .build(),
        )
        val applied = aclView.acl
        require(
            applied.size == 1 &&
                applied.single().type() == AclEntryType.ALLOW &&
                applied.single().principal() == owner &&
                applied.single().permissions() == permissions,
        ) { "authentication revocation marker ACL was not applied" }
        return
    }

    // A provider that explicitly exposes neither POSIX permissions nor ACLs has no portable hardening API.
}
