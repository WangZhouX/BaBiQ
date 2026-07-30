package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentChooser
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentFileInspector
import com.wzx.huitai.desktop.ui.agent.NioBusinessAttachmentFileInspector
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class BusinessScheduleAttachmentSelectionException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

class SwingBusinessScheduleAttachmentChooser : BusinessAttachmentChooser {
    override fun chooseFiles(): List<Path> {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择日程附件"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter(
                "日程附件",
                *SCHEDULE_ATTACHMENT_EXTENSIONS.toTypedArray(),
            )
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.map { it.toPath() }
    }
}

/**
 * Mirrors the backend ticket declaration contract so invalid files are rejected before prepare.
 * The backend remains authoritative and revalidates metadata and sniffed content.
 */
class BusinessScheduleAttachmentPicker(
    private val chooser: BusinessAttachmentChooser = SwingBusinessScheduleAttachmentChooser(),
    private val idFactory: BusinessAttachmentIdFactory = BusinessAttachmentIdFactory(),
    private val fileInspector: BusinessAttachmentFileInspector = NioBusinessAttachmentFileInspector,
) {
    fun choose(currentDrafts: List<BusinessAttachmentDraft> = emptyList()): List<BusinessAttachmentDraft> {
        val selected = try {
            chooser.chooseFiles()
        } catch (_: Exception) {
            throw failure("SCHEDULE_ATTACHMENT_PATH_INVALID", "无法读取所选文件")
        }
        if (selected.isEmpty()) return emptyList()
        if (currentDrafts.size + selected.size > MAX_FILE_COUNT) {
            throw failure("SCHEDULE_ATTACHMENT_LIMIT_EXCEEDED", "单次最多选择 50 个附件")
        }

        val currentPaths = currentDrafts.mapTo(hashSetOf()) {
            Path.of(it.localPath).toAbsolutePath().normalize()
        }
        val names = currentDrafts.mapTo(hashSetOf()) { it.name.lowercase() }
        val selectedPaths = hashSetOf<Path>()
        val candidates = selected.map { raw ->
            val path = try {
                raw.toAbsolutePath().normalize()
            } catch (_: Exception) {
                throw failure("SCHEDULE_ATTACHMENT_PATH_INVALID", "附件路径无效")
            }
            val name = path.fileName?.toString()
                ?: throw failure("SCHEDULE_ATTACHMENT_PATH_INVALID", "附件路径无效")
            if (path in currentPaths || !selectedPaths.add(path) || !names.add(name.lowercase())) {
                throw failure("SCHEDULE_ATTACHMENT_DUPLICATE", "同一附件不能重复添加")
            }
            val metadata = try {
                fileInspector.inspect(path)
            } catch (_: Exception) {
                throw failure("SCHEDULE_ATTACHMENT_PATH_INVALID", "无法读取所选文件")
            }
            if (metadata.symbolicLink || !metadata.regularFile || metadata.sizeBytes <= 0) {
                throw failure("SCHEDULE_ATTACHMENT_NOT_REGULAR_FILE", "只能选择非空普通文件")
            }
            if (metadata.sizeBytes >= MAX_SINGLE_BYTES) {
                throw failure("SCHEDULE_ATTACHMENT_FILE_TOO_LARGE", "单个附件必须小于 20,000,000 字节")
            }
            val extension = name.substringAfterLast('.', "").lowercase()
            val displayType = SCHEDULE_ATTACHMENT_DISPLAY_TYPES[extension]
                ?: throw failure("SCHEDULE_ATTACHMENT_TYPE_UNSUPPORTED", "该文件类型不受支持")
            Candidate(path, name, metadata.sizeBytes, displayType)
        }

        val total = candidates.fold(currentDrafts.sumOf { it.sizeBytes }) { sum, candidate ->
            if (candidate.sizeBytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + candidate.sizeBytes
        }
        if (total >= MAX_TOTAL_BYTES) {
            throw failure("SCHEDULE_ATTACHMENT_TOTAL_TOO_LARGE", "附件总大小必须小于 500,000,000 字节")
        }

        val usedIds = currentDrafts.mapTo(hashSetOf()) { it.id }
        val usedDisplayIds = currentDrafts.mapTo(hashSetOf()) { it.displayId }
        return candidates.map { candidate ->
            val identity = idFactory.create(usedIds, usedDisplayIds)
            usedIds += identity.id
            usedDisplayIds += identity.displayId
            BusinessAttachmentDraft(
                id = identity.id,
                displayId = identity.displayId,
                name = candidate.name,
                localPath = candidate.path.toString(),
                sizeBytes = candidate.sizeBytes,
                displayType = candidate.displayType,
            )
        }
    }

    private fun failure(code: String, message: String) =
        BusinessScheduleAttachmentSelectionException(code, message)

    private data class Candidate(
        val path: Path,
        val name: String,
        val sizeBytes: Long,
        val displayType: String,
    )

    private companion object {
        const val MAX_FILE_COUNT = 50
        const val MAX_SINGLE_BYTES = 20_000_000L
        const val MAX_TOTAL_BYTES = 500_000_000L
    }
}

internal val SCHEDULE_ATTACHMENT_DISPLAY_TYPES = mapOf(
    "png" to "图片",
    "jpg" to "图片",
    "jpeg" to "图片",
    "gif" to "图片",
    "pdf" to "PDF",
    "doc" to "Word",
    "docx" to "Word",
    "mp4" to "视频",
    "avi" to "视频",
    "mov" to "视频",
    "mkv" to "视频",
    "webm" to "视频",
)

internal val SCHEDULE_ATTACHMENT_EXTENSIONS = SCHEDULE_ATTACHMENT_DISPLAY_TYPES.keys.sorted()
