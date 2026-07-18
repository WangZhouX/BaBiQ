package com.wzx.huitai.security.path

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureRuntimeFileTest {
    @Test
    fun `prepare creates a regular leaf and detects replacement before consumer commit`() {
        val path = Files.createTempDirectory("secure-runtime-file").resolve("nested/runtime.db")
        val identity = SecureRuntimeFile.prepare(path)

        assertTrue(Files.isRegularFile(path))
        Files.delete(path)
        Files.createDirectory(path)

        assertFailsWith<IllegalArgumentException> { SecureRuntimeFile.verifyUnchanged(identity) }
    }

    @Test
    fun `windows reparse-like other attributes are rejected without relying on symlink privileges`() {
        assertFailsWith<IllegalArgumentException> {
            SecureRuntimeFile.validateRegularAttributes(
                symbolicLink = false,
                attributes = attributes(regular = false, other = true),
            )
        }
    }

    private fun attributes(regular: Boolean, other: Boolean) = object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(1)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(1)
        override fun creationTime(): FileTime = FileTime.fromMillis(1)
        override fun isRegularFile(): Boolean = regular
        override fun isDirectory(): Boolean = false
        override fun isSymbolicLink(): Boolean = false
        override fun isOther(): Boolean = other
        override fun size(): Long = 0
        override fun fileKey(): Any = "key"
    }
}
