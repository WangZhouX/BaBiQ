package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

fun interface ClipboardImageSource {
    fun readImage(): BufferedImage?

    companion object {
        val SYSTEM = ClipboardImageSource {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                null
            } else {
                (clipboard.getData(DataFlavor.imageFlavor) as? Image)?.toBufferedImage()
            }
        }
    }
}

data class ClipboardAttachmentLimits(
    val maxWidth: Int = 16_384,
    val maxHeight: Int = 16_384,
    val maxPixels: Long = 50_000_000,
    val maxEncodedBytes: Long = 20L * 1024 * 1024,
    val maxControlledBytes: Long = 1024L * 1024 * 1024,
) {
    init {
        require(maxWidth >= 0 && maxHeight >= 0) { "image dimensions must not be negative" }
        require(maxPixels >= 0) { "maxPixels must not be negative" }
        require(maxEncodedBytes >= 0) { "maxEncodedBytes must not be negative" }
        require(maxControlledBytes >= 0) { "maxControlledBytes must not be negative" }
    }

    companion object {
        val DEFAULT = ClipboardAttachmentLimits()
    }
}

class BusinessLocalAttachmentException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

interface ClipboardControlledRootScanner {
    fun displayIds(controlledRoot: Path): Set<String>

    fun regularBytesExcluding(
        controlledRoot: Path,
        excluded: Path,
    ): Long
}

object NioClipboardControlledRootScanner : ClipboardControlledRootScanner {
    override fun displayIds(controlledRoot: Path): Set<String> =
        Files.list(controlledRoot).use { paths ->
            paths.iterator().asSequence()
                .map { path ->
                    path to Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }
                .filter { (_, attributes) -> attributes.isRegularFile }
                .map { (path, _) -> path.fileName.toString() }
                .mapNotNull { name ->
                    CLIPBOARD_FINAL_NAME_PATTERN.matchEntire(name)?.groupValues?.get(1)
                }
                .map { "A-$it" }
                .toSet()
        }

    override fun regularBytesExcluding(
        controlledRoot: Path,
        excluded: Path,
    ): Long = Files.walk(controlledRoot).use { paths ->
        paths.iterator().asSequence()
            .filter { path -> path != excluded }
            .map { path ->
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
            .filter(BasicFileAttributes::isRegularFile)
            .fold(0L) { total, attributes ->
                val size = attributes.size()
                if (size > Long.MAX_VALUE - total) Long.MAX_VALUE else total + size
            }
    }
}

fun interface ClipboardTemporaryFileCleaner {
    fun delete(path: Path): Boolean
}

object NioClipboardTemporaryFileCleaner : ClipboardTemporaryFileCleaner {
    override fun delete(path: Path): Boolean = Files.deleteIfExists(path)
}

/**
 * 把剪贴板图像写入 Agent 隔离根。WebSocket 只会收到返回草稿中的本机路径，不承载图片字节。
 */
class ClipboardImageAttachmentStore(
    controlledRoot: Path,
    private val imageSource: ClipboardImageSource = ClipboardImageSource.SYSTEM,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idFactory: BusinessAttachmentIdFactory = BusinessAttachmentIdFactory(),
    private val limits: ClipboardAttachmentLimits = ClipboardAttachmentLimits.DEFAULT,
    private val rootScanner: ClipboardControlledRootScanner = NioClipboardControlledRootScanner,
    private val temporaryFileCleaner: ClipboardTemporaryFileCleaner = NioClipboardTemporaryFileCleaner,
) {
    val controlledRoot: Path = controlledRoot.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.controlledRoot)
        require(!Files.isSymbolicLink(this.controlledRoot)) {
            "clipboard attachment root must not be a symbolic link"
        }
        this.controlledRoot.toRealPath()
    }

    fun capture(
        existingIds: Set<String> = emptySet(),
        existingDisplayIds: Set<String> = emptySet(),
    ): BusinessAttachmentDraft? {
        val image = try {
            imageSource.readImage()
        } catch (_: Exception) {
            throw BusinessLocalAttachmentException(
                "ATTACHMENT_CLIPBOARD_FAILED",
                "无法读取剪贴板图片，请重新复制后再试",
            )
        } ?: return null

        validateImage(image)
        val rejectedDisplayIds = mutableSetOf<String>()
        repeat(PUBLICATION_ATTEMPTS) {
            val rootDisplayIds = scanDisplayIds()
            val identity = idFactory.create(
                existingIds,
                existingDisplayIds + rootDisplayIds + rejectedDisplayIds,
            )
            captureWithIdentity(image, identity)?.let { return it }
            rejectedDisplayIds += identity.displayId
        }
        throw BusinessLocalAttachmentException(
            "ATTACHMENT_CLIPBOARD_FAILED",
            "附件名称发生冲突，请重试",
        )
    }

    private fun captureWithIdentity(
        image: BufferedImage,
        identity: BusinessAttachmentIdentity,
    ): BusinessAttachmentDraft? {
        val temporary = controlledRoot.resolve("attachment-${identity.id}.tmp")
        val suffix = identity.displayId.removePrefix("A-")
        val timestamp = FILE_TIMESTAMP.format(clock.instant().atZone(clock.zone))
        val filename = "截图-$timestamp-$suffix.png"
        val published = controlledRoot.resolve(filename)
        var completed = false
        var temporaryOwned = false
        try {
            val encodedResult = runCatching {
                FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    temporaryOwned = true
                    val output = Channels.newOutputStream(channel)
                    val encoded = ImageIO.write(image, "png", output)
                    output.flush()
                    EncodedPng(encoded, channel.size())
                }
            }
                .getOrElse {
                    throw BusinessLocalAttachmentException(
                        "ATTACHMENT_CLIPBOARD_FAILED",
                        "保存剪贴板图片失败，请重试",
                    )
                }
            if (!encodedResult.encoded) {
                throw BusinessLocalAttachmentException(
                    "ATTACHMENT_CLIPBOARD_FAILED",
                    "当前环境无法保存 PNG 图片",
                )
            }
            val encodedBytes = encodedResult.sizeBytes
            if (encodedBytes > limits.maxEncodedBytes) {
                throw BusinessLocalAttachmentException(
                    "ATTACHMENT_FILE_TOO_LARGE",
                    "剪贴板图片超过 20 MiB 限制",
                )
            }
            val existingBytes = scanControlledBytes(temporary)
            if (existingBytes > limits.maxControlledBytes - encodedBytes) {
                throw BusinessLocalAttachmentException(
                    "ATTACHMENT_LIMIT_EXCEEDED",
                    "剪贴板附件目录容量已满，请清理过期附件后重试",
                )
            }
            try {
                publishWithoutReplacement(temporary, published)
            } catch (_: FileAlreadyExistsException) {
                return null
            }
            applyOwnerOnlyFilePermissions(published)
            completed = true
            return BusinessAttachmentDraft(
                id = identity.id,
                displayId = identity.displayId,
                name = filename,
                localPath = published.toString(),
                sizeBytes = encodedBytes,
                displayType = "图片",
            )
        } catch (failure: BusinessLocalAttachmentException) {
            throw failure
        } catch (_: Exception) {
            throw BusinessLocalAttachmentException(
                "ATTACHMENT_CLIPBOARD_FAILED",
                "保存剪贴板图片失败，请重试",
            )
        } finally {
            if (!completed && temporaryOwned) runCatching { temporaryFileCleaner.delete(temporary) }
        }
    }

    private fun validateImage(image: BufferedImage) {
        val width = image.width
        val height = image.height
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 ||
            width > limits.maxWidth || height > limits.maxHeight || pixels > limits.maxPixels
        ) {
            throw BusinessLocalAttachmentException(
                "ATTACHMENT_IMAGE_TOO_LARGE",
                "剪贴板图片尺寸超过安全限制",
            )
        }
    }

    private fun scanDisplayIds(): Set<String> =
        try {
            rootScanner.displayIds(controlledRoot)
        } catch (_: Exception) {
            throw BusinessLocalAttachmentException(
                "ATTACHMENT_CLIPBOARD_FAILED",
                "无法检查剪贴板附件目录，请重试",
            )
        }

    private fun scanControlledBytes(excluded: Path): Long =
        try {
            rootScanner.regularBytesExcluding(controlledRoot, excluded)
        } catch (_: Exception) {
            throw BusinessLocalAttachmentException(
                "ATTACHMENT_CLIPBOARD_FAILED",
                "无法检查剪贴板附件目录容量，请重试",
            )
        }

    private fun publishWithoutReplacement(temporary: Path, published: Path) {
        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException("clipboard attachment target already exists")
        }
        try {
            // A sibling hard-link publishes the fully encoded inode atomically and CREATE_NEW-like:
            // createLink fails when any file or link already owns the final name.
            Files.createLink(published, temporary)
            try {
                check(temporaryFileCleaner.delete(temporary)) {
                    "temporary clipboard attachment was not removed"
                }
            } catch (_: Exception) {
                runCatching { Files.deleteIfExists(published) }
                throw BusinessLocalAttachmentException(
                    "ATTACHMENT_CLIPBOARD_FAILED",
                    "剪贴板附件发布清理失败，请重试",
                )
            }
        } catch (_: UnsupportedOperationException) {
            Files.move(temporary, published)
        }
    }

    private fun applyOwnerOnlyFilePermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object {
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        const val PUBLICATION_ATTEMPTS = 8
    }

    private data class EncodedPng(
        val encoded: Boolean,
        val sizeBytes: Long,
    )
}

private val CLIPBOARD_FINAL_NAME_PATTERN =
    Regex("^截图-\\d{8}-\\d{6}-([A-HJ-NP-Z2-9]{6})\\.png$")

private fun Image.toBufferedImage(): BufferedImage {
    if (this is BufferedImage) return this
    val width = getWidth(null)
    val height = getHeight(null)
    if (width <= 0 || height <= 0) {
        throw IllegalArgumentException("clipboard image has invalid dimensions")
    }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { buffered ->
        val graphics: Graphics2D = buffered.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}
