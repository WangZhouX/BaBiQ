package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.desktop.state.BusinessIdentity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

data class BusinessComposerDraftState(
    val text: String = "",
    val attachments: List<BusinessAttachmentDraft> = emptyList(),
)

data class BusinessComposerSendResult(
    val accepted: Boolean,
    val succeeded: Boolean,
    val resultingDraft: BusinessComposerDraftState,
)

data class BusinessComposerIdentityScope(
    val desktopInstanceId: String,
    val desktopSessionId: String,
    val authSessionId: String,
    val identityEpoch: Long,
    val userId: String,
    val tenantId: String,
    val platformId: String,
) {
    override fun toString(): String =
        "BusinessComposerIdentityScope(identityEpoch=$identityEpoch, values=[REDACTED])"
}

data class BusinessComposerSessionState(
    val identityScope: BusinessComposerIdentityScope? = null,
    val draft: BusinessComposerDraftState = BusinessComposerDraftState(),
    val attachmentError: BusinessComposerAttachmentError? = null,
) {
    fun forIdentity(nextIdentityScope: BusinessComposerIdentityScope?): BusinessComposerSessionState =
        if (identityScope == nextIdentityScope) this else BusinessComposerSessionState(nextIdentityScope)
}

fun BusinessIdentity.toComposerIdentityScope(): BusinessComposerIdentityScope =
    BusinessComposerIdentityScope(
        desktopInstanceId = desktopInstanceId,
        desktopSessionId = desktopSessionId,
        authSessionId = authSessionId,
        identityEpoch = identityEpoch,
        userId = userId,
        tenantId = tenantId,
        platformId = platformId,
    )

fun interface BusinessComposerTurnStarter {
    suspend fun startTurn(text: String, attachments: List<BusinessAttachmentDraft>)
}

/**
 * Keeps the send transaction independent from Compose state so a completed request cannot erase
 * text or attachments that the user added while the request was pending.
 */
class BusinessComposerSendCoordinator(
    private val turnStarter: BusinessComposerTurnStarter,
) {
    private val submissionInFlight = AtomicBoolean(false)

    suspend fun submit(captured: BusinessComposerDraftState): BusinessComposerSendResult {
        require(captured.text.isNotBlank() || captured.attachments.isNotEmpty()) {
            "composer text and attachments must not both be blank"
        }
        if (!submissionInFlight.compareAndSet(false, true)) {
            return BusinessComposerSendResult(
                accepted = false,
                succeeded = false,
                resultingDraft = captured,
            )
        }
        return try {
            turnStarter.startTurn(captured.text.trim(), captured.attachments.toList())
            BusinessComposerSendResult(
                accepted = true,
                succeeded = true,
                resultingDraft = BusinessComposerDraftState(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BusinessComposerSendResult(
                accepted = true,
                succeeded = false,
                resultingDraft = captured,
            )
        } finally {
            submissionInFlight.set(false)
        }
    }

    fun reconcile(
        current: BusinessComposerDraftState,
        captured: BusinessComposerDraftState,
        result: BusinessComposerSendResult,
    ): BusinessComposerDraftState {
        if (!result.succeeded) return current
        val submittedAttachmentIds = captured.attachments.mapTo(hashSetOf()) { it.id }
        return BusinessComposerDraftState(
            text = if (current.text == captured.text) "" else current.text,
            attachments = current.attachments.filterNot { it.id in submittedAttachmentIds },
        )
    }
}

/**
 * The Compose key event calls only this cheap availability gate. At most one scheduled capture may
 * encode/publish an image; callers invoke the supplied completion after their IO work finishes.
 */
class BusinessClipboardPasteCoordinator(
    private val hasImage: () -> Boolean,
) {
    private val captureInFlight = AtomicBoolean(false)

    fun request(scheduleCapture: ((() -> Unit) -> Unit)): Boolean {
        if (captureInFlight.get()) return true
        if (!runCatching(hasImage).getOrDefault(false)) return false
        if (!captureInFlight.compareAndSet(false, true)) return true

        val completionCalled = AtomicBoolean(false)
        val complete = {
            if (completionCalled.compareAndSet(false, true)) {
                captureInFlight.set(false)
            }
        }
        return try {
            scheduleCapture(complete)
            true
        } catch (_: Exception) {
            complete()
            true
        }
    }
}

class BusinessComposerAttachmentException(
    val code: String,
    message: String,
) : IllegalArgumentException(message) {
    override fun toString(): String = "BusinessComposerAttachmentException(code=$code, message=$message)"
}

data class BusinessComposerAttachmentError(
    val code: String,
    val message: String,
)

fun mergeBusinessComposerAttachments(
    current: List<BusinessAttachmentDraft>,
    additions: List<BusinessAttachmentDraft>,
    maxAttachments: Int = 8,
    maxTotalBytes: Long = 50L * 1024 * 1024,
): List<BusinessAttachmentDraft> {
    val merged = current + additions
    if (merged.size > maxAttachments) {
        throw BusinessComposerAttachmentException(
            "ATTACHMENT_LIMIT_EXCEEDED",
            "单次最多选择 8 个附件",
        )
    }
    val normalizedPaths = hashSetOf<String>()
    merged.forEach { attachment ->
        val normalized = try {
            Path.of(attachment.localPath).toAbsolutePath().normalize().toString()
        } catch (_: Exception) {
            throw BusinessComposerAttachmentException(
                "ATTACHMENT_PATH_INVALID",
                "附件路径无效，请重新选择",
            )
        }
        if (!normalizedPaths.add(normalized.lowercase())) {
            throw BusinessComposerAttachmentException(
                "ATTACHMENT_DUPLICATE",
                "同一文件不能重复添加",
            )
        }
    }
    val totalBytes = merged.fold(0L) { total, attachment ->
        if (attachment.sizeBytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + attachment.sizeBytes
    }
    if (totalBytes > maxTotalBytes) {
        throw BusinessComposerAttachmentException(
            "ATTACHMENT_TOTAL_TOO_LARGE",
            "附件总大小超过 50 MiB 限制",
        )
    }
    return merged
}

fun safeComposerAttachmentError(failure: Throwable): BusinessComposerAttachmentError =
    when (failure) {
        is BusinessComposerAttachmentException ->
            safeComposerAttachmentError(failure.code, failure.message)
        else ->
            BusinessComposerAttachmentError("ATTACHMENT_LOCAL_FAILED", "附件处理失败，请重试")
    }

fun safeComposerAttachmentError(
    code: String,
    message: String?,
): BusinessComposerAttachmentError {
    val safeCode = code.takeIf { SAFE_ATTACHMENT_CODE.matches(it) } ?: "ATTACHMENT_LOCAL_FAILED"
    val safeMessage = SAFE_ATTACHMENT_MESSAGES[safeCode] ?: "附件处理失败，请重试"
    return BusinessComposerAttachmentError(safeCode, safeMessage)
}

private val SAFE_ATTACHMENT_CODE = Regex("^ATTACHMENT_[A-Z_]+$")
private val SAFE_ATTACHMENT_MESSAGES = mapOf(
    "ATTACHMENT_LIMIT_EXCEEDED" to "单次最多选择 8 个附件",
    "ATTACHMENT_FILE_TOO_LARGE" to "单个附件超过 20 MiB 限制",
    "ATTACHMENT_TOTAL_TOO_LARGE" to "附件总大小超过 50 MiB 限制",
    "ATTACHMENT_PATH_INVALID" to "无法读取所选文件，请确认文件仍存在且可访问",
    "ATTACHMENT_NOT_REGULAR_FILE" to "只能选择普通文件，不能选择目录或链接",
    "ATTACHMENT_TYPE_UNSUPPORTED" to "该文件类型暂不支持",
    "ATTACHMENT_DUPLICATE" to "同一文件不能重复添加",
    "ATTACHMENT_CLIPBOARD_FAILED" to "无法读取或保存剪贴板图片，请重试",
    "ATTACHMENT_IMAGE_TOO_LARGE" to "剪贴板图片尺寸超过安全限制",
    "ATTACHMENT_LOCAL_FAILED" to "附件处理失败，请重试",
)
