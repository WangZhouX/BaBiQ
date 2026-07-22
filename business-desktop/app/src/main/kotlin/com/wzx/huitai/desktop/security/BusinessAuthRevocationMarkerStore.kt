package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.path.SecureRuntimeFile
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

interface BusinessAuthRevocationMarkerPort {
    fun isRevoked(): Boolean
    fun markRevoked()
    fun clearAfterExplicitLogin()
}

/** Non-sensitive fail-closed marker that survives credential deletion failures and process restarts. */
class FileBusinessAuthRevocationMarkerStore(path: Path) : BusinessAuthRevocationMarkerPort {
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
        try {
            SecureRuntimeFile.openChannel(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(MARKER_BYTES)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        } catch (_: FileAlreadyExistsException) {
            check(isRevoked()) { "authentication revocation marker disappeared" }
        }
        SecureRuntimeFile.capture(path)
    }

    override fun clearAfterExplicitLogin() {
        val identity = SecureRuntimeFile.captureIfExists(path) ?: return
        SecureRuntimeFile.verifyUnchanged(identity)
        Files.deleteIfExists(path)
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "authentication revocation marker still exists" }
    }

    override fun toString(): String = "FileBusinessAuthRevocationMarkerStore(path=[REDACTED])"

    private companion object {
        val MARKER_BYTES = "huitai-auth-revoked-v1\n".toByteArray(Charsets.US_ASCII)
    }
}
