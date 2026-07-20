package com.wzx.huitai.desktop.ui.agent

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun interface BusinessAttachmentChooser {
    fun chooseFiles(): List<Path>
}

class SwingBusinessAttachmentChooser : BusinessAttachmentChooser {
    override fun chooseFiles(): List<Path> {
        val chooser = configuredChooser()
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.map { it.toPath() }
    }

    internal fun configuredChooser(): JFileChooser = JFileChooser().apply {
        dialogTitle = "选择业务资料"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = true
        isAcceptAllFileFilterUsed = false
        fileFilter = FileNameExtensionFilter("支持的业务资料", *SUPPORTED_EXTENSIONS.toTypedArray())
    }
}

data class BusinessAttachmentPickerLimits(
    val maxAttachments: Int = 8,
    val maxFileBytes: Long = 20L * 1024 * 1024,
    val maxTotalBytes: Long = 50L * 1024 * 1024,
) {
    init {
        require(maxAttachments >= 0) { "maxAttachments must not be negative" }
        require(maxFileBytes >= 0) { "maxFileBytes must not be negative" }
        require(maxTotalBytes >= 0) { "maxTotalBytes must not be negative" }
    }
}

class BusinessAttachmentSelectionException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * 文件选择仅做桌面端友好预检。文件内容、MIME 和指纹仍由本机 Agent 后端权威验证。
 */
class BusinessAttachmentPicker(
    private val chooser: BusinessAttachmentChooser = SwingBusinessAttachmentChooser(),
    private val idFactory: BusinessAttachmentIdFactory = BusinessAttachmentIdFactory(),
    private val limits: BusinessAttachmentPickerLimits = BusinessAttachmentPickerLimits(),
) {
    fun choose(
        currentDrafts: List<BusinessAttachmentDraft> = emptyList(),
        existingIds: Set<String> = emptySet(),
        existingDisplayIds: Set<String> = emptySet(),
    ): List<BusinessAttachmentDraft> {
        val selected = chooser.chooseFiles()
        if (selected.isEmpty()) return emptyList()

        val currentPaths = currentDrafts.mapTo(hashSetOf()) {
            Path.of(it.localPath).toAbsolutePath().normalize()
        }
        val normalized = selected
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .filterNot(currentPaths::contains)
        if (normalized.isEmpty()) return emptyList()
        if (currentDrafts.size + normalized.size > limits.maxAttachments) {
            throw BusinessAttachmentSelectionException(
                "ATTACHMENT_LIMIT_EXCEEDED",
                "单次最多选择 8 个附件",
            )
        }

        val candidates = normalized.map(::readCandidate)
        val currentBytes = currentDrafts.fold(0L) { sum, draft -> safeAdd(sum, draft.sizeBytes) }
        val totalBytes = candidates.fold(currentBytes) { sum, candidate -> safeAdd(sum, candidate.sizeBytes) }
        if (totalBytes > limits.maxTotalBytes) {
            throw BusinessAttachmentSelectionException(
                "ATTACHMENT_TOTAL_TOO_LARGE",
                "附件总大小超过 50 MiB 限制",
            )
        }

        val usedIds = (existingIds + currentDrafts.map { it.id }).toMutableSet()
        val usedDisplayIds = (existingDisplayIds + currentDrafts.map { it.displayId }).toMutableSet()
        return candidates.map { candidate ->
            val identity = idFactory.create(usedIds, usedDisplayIds)
            usedIds += identity.id
            usedDisplayIds += identity.displayId
            BusinessAttachmentDraft(
                id = identity.id,
                displayId = identity.displayId,
                name = candidate.path.fileName.toString(),
                localPath = candidate.path.toString(),
                sizeBytes = candidate.sizeBytes,
                displayType = candidate.displayType,
            )
        }
    }

    private fun readCandidate(path: Path): Candidate {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw BusinessAttachmentSelectionException(
                "ATTACHMENT_NOT_REGULAR_FILE",
                "只能选择普通文件，不能选择目录或链接",
            )
        }
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        val displayType = DISPLAY_TYPES[extension]
            ?: throw BusinessAttachmentSelectionException(
                "ATTACHMENT_TYPE_UNSUPPORTED",
                "该文件类型暂不支持",
            )
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() > limits.maxFileBytes) {
            throw BusinessAttachmentSelectionException(
                "ATTACHMENT_FILE_TOO_LARGE",
                "单个附件超过 20 MiB 限制",
            )
        }
        return Candidate(path, attributes.size(), displayType)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private data class Candidate(
        val path: Path,
        val sizeBytes: Long,
        val displayType: String,
    )
}

private val DISPLAY_TYPES: Map<String, String> = buildMap {
    listOf("png", "jpg", "jpeg", "webp", "gif").forEach { put(it, "图片") }
    put("pdf", "PDF")
    listOf("doc", "docx").forEach { put(it, "Word") }
    listOf("xls", "xlsx").forEach { put(it, "Excel") }
    listOf("ppt", "pptx").forEach { put(it, "PPT") }
    listOf("txt", "md", "csv", "log").forEach { put(it, "文本") }
    listOf(
        "json", "yaml", "yml", "xml", "html", "htm", "css",
        "js", "ts", "jsx", "tsx", "java", "kt", "kts", "groovy",
        "py", "rb", "php", "c", "cpp", "cc", "cxx", "h", "hpp",
        "cs", "go", "rs", "swift", "sql", "sh", "bash", "zsh", "ps1",
        "properties", "ini", "conf",
    ).forEach { put(it, "代码") }
}

internal val SUPPORTED_EXTENSIONS: List<String> = DISPLAY_TYPES.keys.sorted()
