package com.wzx.huitai.security.path

import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Runtime-file leaf guard shared by SQLite, JCEKS, process locks, and launch/log open points.
 *
 * Java cannot make a pathname lookup and a third-party library open one native atomic operation,
 * so callers capture immediately before the real open and verify the same leaf immediately after.
 * Direct NIO opens additionally use [LinkOption.NOFOLLOW_LINKS].
 */
object SecureRuntimeFile {
    class Identity internal constructor(
        val path: Path,
        internal val fileKey: Any?,
        internal val creationMillis: Long,
    )

    fun prepare(path: Path): Identity {
        val normalized = normalize(path)
        validateParent(normalized)
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return capture(normalized)
        try {
            FileChannel.open(
                normalized,
                setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ).use { }
        } catch (_: FileAlreadyExistsException) {
            // The existing leaf is validated below without following it.
        }
        return capture(normalized)
    }

    fun validateParent(path: Path) {
        val normalized = normalize(path)
        Files.createDirectories(normalized.parent)
        validateDirectoryChain(normalized.parent)
    }

    fun captureIfExists(path: Path): Identity? {
        val normalized = normalize(path)
        validateParent(normalized)
        return if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) capture(normalized) else null
    }

    fun capture(path: Path): Identity {
        val normalized = normalize(path)
        validateDirectoryChain(normalized.parent)
        val attributes = Files.readAttributes(
            normalized,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        validateRegularAttributes(
            symbolicLink = Files.isSymbolicLink(normalized),
            attributes = attributes,
        )
        return Identity(
            path = normalized,
            fileKey = attributes.fileKey(),
            creationMillis = attributes.creationTime().toMillis(),
        )
    }

    fun verifyUnchanged(expected: Identity) {
        val current = capture(expected.path)
        val sameFileKey = expected.fileKey == null || current.fileKey == null || expected.fileKey == current.fileKey
        require(sameFileKey && expected.creationMillis == current.creationMillis) {
            "runtime file leaf changed during open"
        }
    }

    fun openChannel(path: Path, vararg options: StandardOpenOption): FileChannel {
        val normalized = normalize(path)
        val openOptions = linkedSetOf<OpenOption>().apply {
            addAll(options)
            add(LinkOption.NOFOLLOW_LINKS)
        }
        return FileChannel.open(normalized, openOptions)
    }

    internal fun validateRegularAttributes(
        symbolicLink: Boolean,
        attributes: BasicFileAttributes,
    ) {
        require(!symbolicLink && !attributes.isOther && attributes.isRegularFile) {
            "runtime file leaf must be a regular non-reparse file"
        }
    }

    private fun normalize(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.fileName != null && normalized.parent != null) { "runtime path must identify a file" }
        return normalized
    }

    private fun validateDirectoryChain(directory: Path) {
        var current = directory.root
        directory.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                require(!Files.isSymbolicLink(current) && !attributes.isOther && attributes.isDirectory) {
                    "runtime file parent must not contain a link or reparse point"
                }
            }
        }
    }
}
