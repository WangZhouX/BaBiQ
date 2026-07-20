package com.wzx.huitai.desktop.ui.agent

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessAttachmentPickerTest {
    @Test
    fun `normalizes selected regular files using metadata only`() {
        val directory = Files.createTempDirectory("huitai-picker")
        val selected = directory.resolve("nested/../report.pdf")
        Files.createDirectories(directory.resolve("nested"))
        Files.write(directory.resolve("report.pdf"), byteArrayOf(1, 2, 3))
        val picker = picker(listOf(selected))

        val drafts = picker.choose()

        assertEquals(1, drafts.size)
        assertEquals("report.pdf", drafts.single().name)
        assertEquals(directory.resolve("report.pdf").toAbsolutePath().normalize().toString(), drafts.single().localPath)
        assertEquals(3, drafts.single().sizeBytes)
        assertEquals("PDF", drafts.single().displayType)
        assertFalse(drafts.single().toString().contains(drafts.single().localPath))
    }

    @Test
    fun `deduplicates normalized paths and respects current draft count`() {
        val directory = Files.createTempDirectory("huitai-picker-deduplicate")
        val selected = directory.resolve("notes.txt")
        Files.writeString(selected, "hello")
        val existing = draft(
            id = "00000000-0000-0000-0000-000000000010",
            displayId = "A-BCDEFG",
            path = selected,
        )
        val picker = picker(listOf(selected, directory.resolve("./notes.txt")))

        val drafts = picker.choose(currentDrafts = listOf(existing))

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `retries identifier collisions against draft and current thread attachment ids`() {
        val directory = Files.createTempDirectory("huitai-picker-collision")
        val selected = directory.resolve("source.kt")
        Files.writeString(selected, "fun main() = Unit")
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val supplied = ArrayDeque(listOf(first, second))
        val factory = BusinessAttachmentIdFactory(
            uuidSource = { supplied.removeFirst() },
            displayIdEncoder = { uuid -> if (uuid == first) "A-BCDEFG" else "A-HJKLMN" },
        )
        val picker = BusinessAttachmentPicker(
            chooser = BusinessAttachmentChooser { listOf(selected) },
            idFactory = factory,
        )

        val drafts = picker.choose(
            existingIds = setOf(first.toString()),
            existingDisplayIds = setOf("A-BCDEFG"),
        )

        assertEquals(second.toString(), drafts.single().id)
        assertEquals("A-HJKLMN", drafts.single().displayId)
    }

    @Test
    fun `rejects unsupported links per file size total size and more than eight attachments`() {
        val directory = Files.createTempDirectory("huitai-picker-limits")
        val unsupported = directory.resolve("payload.exe")
        Files.write(unsupported, byteArrayOf(1))
        assertEquals(
            "ATTACHMENT_TYPE_UNSUPPORTED",
            assertFailsWith<BusinessAttachmentSelectionException> {
                picker(listOf(unsupported)).choose()
            }.code,
        )

        val large = directory.resolve("large.txt")
        Files.write(large, ByteArray(3))
        assertEquals(
            "ATTACHMENT_FILE_TOO_LARGE",
            assertFailsWith<BusinessAttachmentSelectionException> {
                BusinessAttachmentPicker(
                    chooser = BusinessAttachmentChooser { listOf(large) },
                    idFactory = fixedIdFactory(),
                    limits = BusinessAttachmentPickerLimits(maxFileBytes = 2),
                ).choose()
            }.code,
        )

        val first = directory.resolve("first.txt")
        val second = directory.resolve("second.txt")
        Files.write(first, ByteArray(2))
        Files.write(second, ByteArray(2))
        assertEquals(
            "ATTACHMENT_TOTAL_TOO_LARGE",
            assertFailsWith<BusinessAttachmentSelectionException> {
                BusinessAttachmentPicker(
                    chooser = BusinessAttachmentChooser { listOf(first, second) },
                    idFactory = incrementingIdFactory(),
                    limits = BusinessAttachmentPickerLimits(maxTotalBytes = 3),
                ).choose()
            }.code,
        )

        val current = (1..8).map { index ->
            draft(
                id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                displayId = "A-BCDEF${"GHJKLMNP"[index - 1]}",
                path = directory.resolve("existing-$index.txt").toAbsolutePath(),
            )
        }
        assertEquals(
            "ATTACHMENT_LIMIT_EXCEEDED",
            assertFailsWith<BusinessAttachmentSelectionException> {
                picker(listOf(first)).choose(currentDrafts = current)
            }.code,
        )
    }

    @Test
    fun `production chooser is files only multi select and restricted to supported extensions`() {
        val chooser = SwingBusinessAttachmentChooser().configuredChooser()

        assertEquals(JFileChooser.FILES_ONLY, chooser.fileSelectionMode)
        assertTrue(chooser.isMultiSelectionEnabled)
        assertFalse(chooser.isAcceptAllFileFilterUsed)
        val extensionFilter = chooser.fileFilter as FileNameExtensionFilter
        assertTrue("pdf" in extensionFilter.extensions)
        assertTrue("docx" in extensionFilter.extensions)
        assertTrue("xlsx" in extensionFilter.extensions)
        assertTrue("pptx" in extensionFilter.extensions)
        assertTrue("png" in extensionFilter.extensions)
        assertTrue("kt" in extensionFilter.extensions)
    }

    @Test
    fun `deleted selection returns a stable path free error`() {
        val directory = Files.createTempDirectory("huitai-picker-deleted")
        val deleted = directory.resolve("deleted-report.pdf")
        Files.write(deleted, byteArrayOf(1))
        Files.delete(deleted)

        val failure = assertFailsWith<BusinessAttachmentSelectionException> {
            picker(listOf(deleted)).choose()
        }

        assertEquals("ATTACHMENT_NOT_REGULAR_FILE", failure.code)
        assertFalse(failure.toString().contains(deleted.toString()))
    }

    @Test
    fun `unreadable selection normalizes inspector failures without leaking a path`() {
        val directory = Files.createTempDirectory("huitai-picker-unreadable")
        val selected = directory.resolve("private-report.pdf")
        Files.write(selected, byteArrayOf(1))
        val picker = BusinessAttachmentPicker(
            chooser = BusinessAttachmentChooser { listOf(selected) },
            idFactory = fixedIdFactory(),
            fileInspector = BusinessAttachmentFileInspector {
                throw AccessDeniedException(selected.toString())
            },
        )

        val failure = assertFailsWith<BusinessAttachmentSelectionException> {
            picker.choose()
        }

        assertEquals("ATTACHMENT_PATH_INVALID", failure.code)
        assertFalse(failure.toString().contains(selected.toString()))
        assertFalse(failure.toString().contains(directory.toString()))
    }

    @Test
    fun `linked selection is rejected without leaking either path`() {
        val directory = Files.createTempDirectory("huitai-picker-link")
        val target = directory.resolve("private-target.pdf")
        val linked = directory.resolve("selected-link.pdf")
        Files.write(target, byteArrayOf(1))
        val linkCreated = runCatching { Files.createSymbolicLink(linked, target) }.isSuccess
        if (!linkCreated) return

        val failure = assertFailsWith<BusinessAttachmentSelectionException> {
            picker(listOf(linked)).choose()
        }

        assertEquals("ATTACHMENT_NOT_REGULAR_FILE", failure.code)
        assertFalse(failure.toString().contains(linked.toString()))
        assertFalse(failure.toString().contains(target.toString()))
        assertEquals(1, Files.size(target))
    }

    private fun picker(paths: List<Path>): BusinessAttachmentPicker = BusinessAttachmentPicker(
        chooser = BusinessAttachmentChooser { paths },
        idFactory = incrementingIdFactory(),
    )

    private fun fixedIdFactory(): BusinessAttachmentIdFactory = BusinessAttachmentIdFactory(
        uuidSource = { UUID.fromString("00000000-0000-0000-0000-000000000100") },
        displayIdEncoder = { "A-BCDEFG" },
    )

    private fun incrementingIdFactory(): BusinessAttachmentIdFactory {
        var next = 100
        return BusinessAttachmentIdFactory(
            uuidSource = {
                UUID.fromString("00000000-0000-0000-0000-${(next++).toString().padStart(12, '0')}")
            },
        )
    }

    private fun draft(
        id: String,
        displayId: String,
        path: Path,
    ): BusinessAttachmentDraft = BusinessAttachmentDraft(
        id = id,
        displayId = displayId,
        name = path.fileName.toString(),
        localPath = path.toAbsolutePath().normalize().toString(),
        sizeBytes = 0,
        displayType = "文本",
    )
}
