package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentChooser
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentFileInspector
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentFileMetadata
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BusinessScheduleAttachmentPickerTest {
    @Test
    fun `accepts the exact server extension contract including videos up to fifty files`() {
        val extensions = listOf(
            "png", "jpg", "jpeg", "gif", "pdf", "doc", "docx",
            "mp4", "avi", "mov", "mkv", "webm",
        )
        val paths = (0 until 50).map { index ->
            Path.of("E:/schedule/file-$index.${extensions[index % extensions.size]}")
        }
        val picker = picker(paths, sizeBytes = 1)

        val drafts = picker.choose()

        assertEquals(50, drafts.size)
        assertTrue(drafts.any { it.name.endsWith(".mp4") })
        assertTrue(drafts.any { it.name.endsWith(".webm") })
    }

    @Test
    fun `rejects count single and total boundaries exactly like the server`() {
        assertCode(
            "SCHEDULE_ATTACHMENT_LIMIT_EXCEEDED",
            picker((0..50).map { Path.of("E:/schedule/$it.pdf") }, 1),
        )
        assertCode(
            "SCHEDULE_ATTACHMENT_FILE_TOO_LARGE",
            picker(listOf(Path.of("E:/schedule/large.pdf")), 20_000_000),
        )
        assertCode(
            "SCHEDULE_ATTACHMENT_TOTAL_TOO_LARGE",
            picker((0 until 50).map { Path.of("E:/schedule/$it.pdf") }, 10_000_000),
        )
    }

    @Test
    fun `rejects extensions outside the server whitelist`() {
        listOf("webp", "txt", "xlsx").forEach { extension ->
            assertCode(
                "SCHEDULE_ATTACHMENT_TYPE_UNSUPPORTED",
                picker(listOf(Path.of("E:/schedule/file.$extension")), 1),
            )
        }
    }

    @Test
    fun `desktop limits and allowlist stay locked to the backend ticket contract`() {
        val sourcePath = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map {
                it.resolve(
                    "backend/src/main/java/com/wzx/babiq/server/business/upload/" +
                        "BusinessAttachmentTicketService.java",
                )
            }
            .firstOrNull(Files::isRegularFile)
            ?: error("backend attachment ticket source not found")
        val source = Files.readString(sourcePath)
        assertTrue(source.contains("MAX_SINGLE_BYTES = 20_000_000L"))
        assertTrue(source.contains("MAX_TOTAL_BYTES = 500_000_000L"))
        assertTrue(source.contains("MAX_FILE_COUNT = 50"))
        val allowlist = source.substringAfter("EXTENSION_MEDIA_TYPES = Map.ofEntries(").substringBefore(");")
        val backendExtensions = Regex("""Map\.entry\("([^"]+)"""")
            .findAll(allowlist)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(SCHEDULE_ATTACHMENT_EXTENSIONS.toSet(), backendExtensions)
    }

    private fun picker(paths: List<Path>, sizeBytes: Long): BusinessScheduleAttachmentPicker =
        BusinessScheduleAttachmentPicker(
            chooser = BusinessAttachmentChooser { paths },
            fileInspector = BusinessAttachmentFileInspector {
                BusinessAttachmentFileMetadata(
                    regularFile = true,
                    symbolicLink = false,
                    sizeBytes = sizeBytes,
                )
            },
        )

    private fun assertCode(expected: String, picker: BusinessScheduleAttachmentPicker) {
        val failure = assertFailsWith<BusinessScheduleAttachmentSelectionException> { picker.choose() }
        assertEquals(expected, failure.code)
    }
}
