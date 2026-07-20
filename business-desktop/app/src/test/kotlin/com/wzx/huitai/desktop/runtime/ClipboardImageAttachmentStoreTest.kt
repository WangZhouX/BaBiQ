package com.wzx.huitai.desktop.runtime

import java.awt.image.BufferedImage
import java.nio.file.AccessDeniedException
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
    fun `image availability check never reads or encodes clipboard pixels`() {
        var imageReads = 0
        val source = object : ClipboardImageSource {
            override fun hasImage(): Boolean = true
            override fun readImage(): BufferedImage? {
                imageReads++
                return BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            }
        }
        val store = ClipboardImageAttachmentStore(
            controlledRoot = Files.createTempDirectory("huitai-clipboard-availability"),
            imageSource = source,
        )

        assertTrue(store.hasImage())
        assertEquals(0, imageReads)
        assertTrue(Files.list(store.controlledRoot).use { it.findAny().isEmpty })
    }

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

    @Test
    fun `rejects invalid image dimensions before touching a colliding temporary path`() {
        val root = Files.createTempDirectory("huitai-clipboard-dimension-before-temp")
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val temporary = root.resolve("attachment-$uuid.tmp")
        Files.writeString(temporary, "existing-owner-data")
        val store = store(
            root = root,
            image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB),
            uuid = uuid,
            limits = ClipboardAttachmentLimits(maxWidth = 1),
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_IMAGE_TOO_LARGE", failure.code)
        assertEquals("existing-owner-data", Files.readString(temporary))
    }

    @Test
    fun `final publication collision retries identity without replacing the existing reference`() {
        val root = Files.createTempDirectory("huitai-clipboard-final-collision")
        val outside = Files.createTempFile("huitai-clipboard-final-outside", ".txt")
        Files.writeString(outside, "outside-owner-data")
        val firstUuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val secondUuid = UUID.fromString("00000000-0000-0000-0000-000000000124")
        val firstFinal = root.resolve("截图-20260720-123456-7K3M2Q.png")
        val linkCreated = runCatching { Files.createSymbolicLink(firstFinal, outside) }.isSuccess
        if (!linkCreated) return
        val supplied = ArrayDeque(listOf(firstUuid, secondUuid))
        val displayIds = mapOf(firstUuid to "A-7K3M2Q", secondUuid to "A-HJKLMN")
        val store = ClipboardImageAttachmentStore(
            controlledRoot = root,
            imageSource = ClipboardImageSource {
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            },
            clock = Clock.fixed(Instant.parse("2026-07-20T04:34:56Z"), ZoneId.of("Asia/Shanghai")),
            idFactory = BusinessAttachmentIdFactory(
                uuidSource = { supplied.removeFirst() },
                displayIdEncoder = displayIds::getValue,
            ),
        )

        val draft = assertNotNull(store.capture())

        assertEquals(secondUuid.toString(), draft.id)
        assertEquals("A-HJKLMN", draft.displayId)
        assertEquals("截图-20260720-123456-HJKLMN.png", draft.name)
        assertTrue(Files.isSymbolicLink(firstFinal))
        assertEquals("outside-owner-data", Files.readString(outside))
        assertTrue(Files.exists(java.nio.file.Path.of(draft.localPath)))
    }

    @Test
    fun `controlled display id scan failure is path free`() {
        val root = Files.createTempDirectory("huitai-clipboard-display-scan")
        val secret = root.resolve("private-display-entry.png")
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            rootScanner = object : ClipboardControlledRootScanner {
                override fun displayIds(controlledRoot: java.nio.file.Path): Set<String> {
                    throw AccessDeniedException(secret.toString())
                }

                override fun regularBytesExcluding(
                    controlledRoot: java.nio.file.Path,
                    excluded: java.nio.file.Path,
                ): Long = 0
            },
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_CLIPBOARD_FAILED", failure.code)
        assertFalse(failure.toString().contains(secret.toString()))
    }

    @Test
    fun `controlled capacity scan failure is path free and cleans owned temporary file`() {
        val root = Files.createTempDirectory("huitai-clipboard-capacity-scan")
        val secret = root.resolve("private-capacity-entry.png")
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            rootScanner = object : ClipboardControlledRootScanner {
                override fun displayIds(controlledRoot: java.nio.file.Path): Set<String> = emptySet()

                override fun regularBytesExcluding(
                    controlledRoot: java.nio.file.Path,
                    excluded: java.nio.file.Path,
                ): Long {
                    throw AccessDeniedException(secret.toString())
                }
            },
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_CLIPBOARD_FAILED", failure.code)
        assertFalse(failure.toString().contains(secret.toString()))
        assertEquals(0, Files.list(root).use { it.count() })
    }

    @Test
    fun `temporary cleanup failure rolls back published link and returns a path free error`() {
        val root = Files.createTempDirectory("huitai-clipboard-cleanup-failure")
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val temporary = root.resolve("attachment-$uuid.tmp")
        val published = root.resolve("截图-20260720-123456-7K3M2Q.png")
        val store = store(
            root = root,
            image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            uuid = uuid,
            temporaryFileCleaner = ClipboardTemporaryFileCleaner { path ->
                throw AccessDeniedException(path.toString())
            },
        )

        val failure = assertFailsWith<BusinessLocalAttachmentException> { store.capture() }

        assertEquals("ATTACHMENT_CLIPBOARD_FAILED", failure.code)
        assertFalse(failure.toString().contains(root.toString()))
        assertFalse(Files.exists(published, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporary, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    private fun store(
        root: java.nio.file.Path,
        image: BufferedImage,
        uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        displayId: String = "A-7K3M2Q",
        limits: ClipboardAttachmentLimits = ClipboardAttachmentLimits.DEFAULT,
        rootScanner: ClipboardControlledRootScanner = NioClipboardControlledRootScanner,
        temporaryFileCleaner: ClipboardTemporaryFileCleaner = NioClipboardTemporaryFileCleaner,
    ): ClipboardImageAttachmentStore = ClipboardImageAttachmentStore(
        controlledRoot = root,
        imageSource = ClipboardImageSource { image },
        clock = Clock.fixed(Instant.parse("2026-07-20T04:34:56Z"), ZoneId.of("Asia/Shanghai")),
        idFactory = BusinessAttachmentIdFactory(
            uuidSource = { uuid },
            displayIdEncoder = { displayId },
        ),
        limits = limits,
        rootScanner = rootScanner,
        temporaryFileCleaner = temporaryFileCleaner,
    )
}
