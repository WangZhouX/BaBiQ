package com.wzx.huitai.desktop.runtime

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardImageAttachmentStoreTest {
    @Test
    fun `materializes clipboard image through a temporary sibling and publishes the named PNG`() {
        val root = Files.createTempDirectory("huitai-clipboard-image")
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val store = store(
            root = root,
            image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB),
            uuid = uuid,
            displayId = "A-7K3M2Q",
        )

        val draft = assertNotNull(store.capture())

        assertEquals("A-7K3M2Q", draft.displayId)
        assertEquals("截图-20260720-123456-7K3M2Q.png", draft.name)
        assertEquals(root.resolve(draft.name), java.nio.file.Path.of(draft.localPath))
        assertEquals("图片", draft.displayType)
        assertTrue(draft.sizeBytes > 0)
        assertEquals(3, ImageIO.read(java.nio.file.Path.of(draft.localPath).toFile()).width)
        val names = Files.list(root).use { paths -> paths.map { it.name }.toList() }
        assertEquals(listOf(draft.name), names)
        assertFalse(names.any { it.startsWith("attachment-$uuid") })
    }

    @Test
    fun `non image clipboard returns null so ordinary text paste remains available`() {
        val root = Files.createTempDirectory("huitai-clipboard-text")
        val store = ClipboardImageAttachmentStore(
            controlledRoot = root,
            imageSource = ClipboardImageSource { null },
        )

        assertNull(store.capture())
        assertEquals(0, Files.list(root).use { it.count() })
    }

    @Test
    fun `rejects image dimensions and removes the temporary file`() {
        val root = Files.createTempDirectory("huitai-clipboard-dimensions")
        val store = store(
            root = root,
            image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
            limits = ClipboardAttachmentLimits(maxWidth = 1, maxHeight = 16_384, maxPixels = 1),
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_IMAGE_TOO_LARGE", failure.code)
        assertEquals(0, Files.list(root).use { it.count() })
        assertEquals(16_384, ClipboardAttachmentLimits.DEFAULT.maxWidth)
        assertEquals(50_000_000, ClipboardAttachmentLimits.DEFAULT.maxPixels)
    }

    @Test
    fun `rejects encoded PNG beyond per file limit and removes the temporary file`() {
        val root = Files.createTempDirectory("huitai-clipboard-file-limit")
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            limits = ClipboardAttachmentLimits(maxEncodedBytes = 0),
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_FILE_TOO_LARGE", failure.code)
        assertEquals(0, Files.list(root).use { it.count() })
        assertEquals(20L * 1024 * 1024, ClipboardAttachmentLimits.DEFAULT.maxEncodedBytes)
    }

    @Test
    fun `rejects controlled root capacity without deleting existing files or leaving temporary files`() {
        val root = Files.createTempDirectory("huitai-clipboard-capacity")
        val existing = root.resolve("existing.png")
        Files.write(existing, ByteArray(8) { 1 })
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            limits = ClipboardAttachmentLimits(maxControlledBytes = 8),
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_LIMIT_EXCEEDED", failure.code)
        assertTrue(Files.exists(existing))
        assertEquals(8, Files.size(existing))
        assertEquals(listOf("existing.png"), Files.list(root).use { paths -> paths.map { it.name }.toList() })
        assertEquals(1024L * 1024 * 1024, ClipboardAttachmentLimits.DEFAULT.maxControlledBytes)
    }

    @Test
    fun `pre existing temporary file collision is neither overwritten nor deleted`() {
        val root = Files.createTempDirectory("huitai-clipboard-temp-collision")
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val temporary = root.resolve("attachment-$uuid.tmp")
        Files.writeString(temporary, "existing-owner-data")
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            uuid = uuid,
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_CLIPBOARD_FAILED", failure.code)
        assertTrue(Files.exists(temporary))
        assertEquals("existing-owner-data", Files.readString(temporary))
        assertEquals(listOf(temporary.fileName.toString()), Files.list(root).use { paths ->
            paths.map { it.fileName.toString() }.toList()
        })
    }

    @Test
    fun `pre existing temporary symlink is not followed or deleted`() {
        val root = Files.createTempDirectory("huitai-clipboard-temp-link")
        val outside = Files.createTempFile("huitai-clipboard-outside", ".txt")
        Files.writeString(outside, "outside-owner-data")
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val temporary = root.resolve("attachment-$uuid.tmp")
        val linkCreated = runCatching { Files.createSymbolicLink(temporary, outside) }.isSuccess
        if (!linkCreated) return
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            uuid = uuid,
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_CLIPBOARD_FAILED", failure.code)
        assertTrue(Files.isSymbolicLink(temporary))
        assertEquals("outside-owner-data", Files.readString(outside))
    }

    @Test
    fun `capacity scan never follows symbolic links to outside files`() {
        val root = Files.createTempDirectory("huitai-clipboard-capacity-link")
        val outside = Files.createTempFile("huitai-clipboard-capacity-outside", ".bin")
        Files.write(outside, ByteArray(4 * 1024) { 7 })
        val linked = root.resolve("outside.png")
        val linkCreated = runCatching { Files.createSymbolicLink(linked, outside) }.isSuccess
        if (!linkCreated) return
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            limits = ClipboardAttachmentLimits(maxControlledBytes = 1024),
        )

        val draft = assertNotNull(store.capture())

        assertTrue(Files.exists(java.nio.file.Path.of(draft.localPath)))
        assertEquals(4L * 1024, Files.size(outside))
        assertTrue(Files.isSymbolicLink(linked))
    }

    private fun store(
        root: java.nio.file.Path,
        image: BufferedImage,
        uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        displayId: String = "A-7K3M2Q",
        limits: ClipboardAttachmentLimits = ClipboardAttachmentLimits.DEFAULT,
    ): ClipboardImageAttachmentStore = ClipboardImageAttachmentStore(
        controlledRoot = root,
        imageSource = ClipboardImageSource { image },
        clock = Clock.fixed(Instant.parse("2026-07-20T04:34:56Z"), ZoneId.of("Asia/Shanghai")),
        idFactory = BusinessAttachmentIdFactory(
            uuidSource = { uuid },
            displayIdEncoder = { displayId },
        ),
        limits = limits,
    )
}
