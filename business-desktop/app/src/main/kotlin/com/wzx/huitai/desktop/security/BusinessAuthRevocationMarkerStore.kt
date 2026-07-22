package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.path.SecureRuntimeFile
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
        var failure: Throwable? = null
        val primaryRevoked = try {
            primary.isRevoked()
        } catch (caught: Throwable) {
            failure = caught
            false
        }
        val fallbackRevoked = try {
            fallback.isRevoked()
        } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
            false
        }
        if (primaryRevoked || fallbackRevoked) return true
        failure?.let { throw IllegalStateException("authentication revocation marker is unreadable", it) }
        return false
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

/** Non-sensitive fail-closed marker that survives credential deletion failures and process restarts. */
class FileBusinessAuthRevocationMarkerStore(
    path: Path,
    private val permissionApplier: (Path, Boolean) -> Unit = ::applyVerifiedOwnerOnlyPermissions,
    private val beforeLeafCreate: () -> Unit = {},
) : BusinessAuthRevocationMarkerPort {
    private val path = path.toAbsolutePath().normalize()

    override fun isRevoked(): Boolean {
        val identity = SecureRuntimeFile.captureIfExists(path) ?: return false
        SecureRuntimeFile.openChannel(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(MARKER_BYTES.size + 1)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            SecureRuntimeFile.verifyUnchanged(identity)
            return true
        }
    }

    override fun markRevoked() {
        SecureRuntimeFile.validateParent(path)
        permissionApplier(path.parent, true)
        val parentIdentity = captureParentIdentity()
        try {
            beforeLeafCreate()
            verifyParentIdentity(parentIdentity)
            SecureRuntimeFile.openChannel(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                verifyParentIdentity(parentIdentity)
                val buffer = ByteBuffer.wrap(MARKER_BYTES)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (_: FileAlreadyExistsException) {
            check(isRevoked()) { "authentication revocation marker disappeared" }
        }
        permissionApplier(path, false)
        SecureRuntimeFile.capture(path)
        forceParentDirectory()
    }

    override fun clearAfterExplicitLogin() {
        val identity = SecureRuntimeFile.captureIfExists(path) ?: return
        SecureRuntimeFile.verifyUnchanged(identity)
        Files.deleteIfExists(path)
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "authentication revocation marker still exists" }
        forceParentDirectory()
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
        val MARKER_BYTES = "huitai-auth-revoked-v1\n".toByteArray(Charsets.US_ASCII)
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
