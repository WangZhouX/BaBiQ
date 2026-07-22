package com.wzx.huitai.desktop.security

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessAuthRevocationMarkerStoreTest {
    @Test
    fun `revocation marker survives reopening and only explicit clear removes it`() {
        val path = Files.createTempDirectory("business-auth-revocation").resolve("auth-revoked-v1")
        val first = FileBusinessAuthRevocationMarkerStore(path)

        assertFalse(first.isRevoked())
        first.markRevoked()
        assertTrue(FileBusinessAuthRevocationMarkerStore(path).isRevoked())

        FileBusinessAuthRevocationMarkerStore(path).clearAfterExplicitLogin()
        assertFalse(FileBusinessAuthRevocationMarkerStore(path).isRevoked())
    }

    @Test
    fun `redundant marker treats either durable copy as revoked and attempts both writes`() {
        val primary = FakeMarker(failMark = true)
        val fallback = FakeMarker()
        val redundant = RedundantBusinessAuthRevocationMarkerStore(primary, fallback)

        redundant.markRevoked()

        assertEquals(1, primary.markCalls)
        assertTrue(fallback.revoked)
        assertTrue(redundant.isRevoked())
    }

    @Test
    fun `redundant marker fails closed on unreadable copies and clears both explicitly`() {
        val primary = FakeMarker(failRead = true, failClear = true)
        val fallback = FakeMarker()
        val redundant = RedundantBusinessAuthRevocationMarkerStore(primary, fallback)

        assertFails { redundant.isRevoked() }
        assertFails { redundant.clearAfterExplicitLogin() }
        assertEquals(1, primary.clearCalls)
        assertEquals(1, fallback.clearCalls)
    }

    @Test
    fun `file marker rejects directory and symbolic link leaves`() {
        val root = Files.createTempDirectory("business-auth-marker-leaf")
        val directoryLeaf = root.resolve("directory-marker")
        Files.createDirectory(directoryLeaf)
        assertFails { FileBusinessAuthRevocationMarkerStore(directoryLeaf).markRevoked() }

        val target = root.resolve("target-marker")
        Files.writeString(target, "target")
        val link = root.resolve("linked-marker")
        if (runCatching { Files.createSymbolicLink(link, target); true }.getOrDefault(false)) {
            assertFails { FileBusinessAuthRevocationMarkerStore(link).markRevoked() }
        }
    }

    @Test
    fun `file marker rejects a replaced symbolic link parent`() {
        val root = Files.createTempDirectory("business-auth-marker-parent")
        val probeTarget = root.resolve("probe-target")
        val probeLink = root.resolve("probe-link")
        Files.createDirectory(probeTarget)
        if (!runCatching { Files.createSymbolicLink(probeLink, probeTarget); true }.getOrDefault(false)) return
        Files.delete(probeLink)
        Files.delete(probeTarget)
        val parent = root.resolve("controlled")
        val movedParent = root.resolve("moved")
        val attackerParent = root.resolve("attacker")
        Files.createDirectory(parent)
        Files.createDirectory(attackerParent)
        val store = FileBusinessAuthRevocationMarkerStore(parent.resolve("marker")) {
            Files.move(parent, movedParent)
            Files.createSymbolicLink(parent, attackerParent)
        }
        assertFails { store.markRevoked() }
        assertFalse(Files.exists(attackerParent.resolve("marker"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `file marker narrows posix permissions for leaf and immediate parent`() {
        val path = Files.createTempDirectory("business-auth-marker-permissions").resolve("private/marker")
        val store = FileBusinessAuthRevocationMarkerStore(path)
        store.markRevoked()

        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(path.parent),
            )
        } else {
            listOf(path.parent, path).forEach { securedPath ->
                val view = Files.getFileAttributeView(securedPath, AclFileAttributeView::class.java)
                    ?: return@forEach
                val owner = Files.getOwner(securedPath)
                assertEquals(1, view.acl.size)
                assertEquals(owner, view.acl.single().principal())
                assertEquals(AclEntryType.ALLOW, view.acl.single().type())
            }
        }
        assertTrue(FileBusinessAuthRevocationMarkerStore(path).isRevoked())
    }

    private class FakeMarker(
        var revoked: Boolean = false,
        private val failRead: Boolean = false,
        private val failMark: Boolean = false,
        private val failClear: Boolean = false,
    ) : BusinessAuthRevocationMarkerPort {
        var markCalls = 0
        var clearCalls = 0
        override fun isRevoked(): Boolean {
            if (failRead) error("read failed")
            return revoked
        }
        override fun markRevoked() {
            markCalls += 1
            if (failMark) error("mark failed")
            revoked = true
        }
        override fun clearAfterExplicitLogin() {
            clearCalls += 1
            if (failClear) error("clear failed")
            revoked = false
        }
    }
}
