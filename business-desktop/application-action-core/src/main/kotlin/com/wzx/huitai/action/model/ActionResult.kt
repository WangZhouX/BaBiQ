package com.wzx.huitai.action.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 预览中的单项变化。
 *
 * @param path 变化字段路径。
 * @param before 变化前的 JSON 值。
 * @param after 变化后的 JSON 值。
 * @param redacted 当前值是否已脱敏。
 */
@Serializable
data class ActionPreviewChange(
    val path: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val redacted: Boolean = false,
) {
    /** 日志中保留字段路径和脱敏状态，隐藏变化值。 */
    override fun toString(): String =
        "ActionPreviewChange(path=$path, redacted=$redacted, before=[REDACTED], after=[REDACTED])"
}

/**
 * 无副作用的动作预览。
 *
 * @param executionId 对应动作执行标识。
 * @param summary 用户可读摘要。
 * @param redactedInput 已脱敏的动作输入。
 * @param changes 结构化变化列表。
 * @param warnings 执行前警告。
 */
@Serializable
data class ActionPreview(
    val executionId: String,
    val summary: String,
    val redactedInput: JsonObject = JsonObject(emptyMap()),
    val changes: List<ActionPreviewChange> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    /** 日志中保留执行标识和变化计数，隐藏预览文本和值载荷。 */
    override fun toString(): String =
        "ActionPreview(executionId=$executionId, changes=${changes.size}, warnings=${warnings.size}, " +
            "summary=[REDACTED], redactedInput=[REDACTED])"
}

/** 桌面动作可序列化结果。 */
@Serializable
sealed class ActionResult<out T> {
    /** 已生成预览并等待用户确认。 */
    @Serializable
    @SerialName("preview")
    data class Preview(val preview: ActionPreview) : ActionResult<Nothing>() {
        override fun toString(): String = "ActionResult.Preview(executionId=${preview.executionId})"
    }

    /** 高风险动作已进入独立审批。 */
    @Serializable
    @SerialName("approval_required")
    data class ApprovalRequired(
        val executionId: String,
        val approvalId: String,
        val preview: ActionPreview,
        val reason: String,
        val expiresAtEpochMillis: Long,
    ) : ActionResult<Nothing>() {
        override fun toString(): String =
            "ActionResult.ApprovalRequired(executionId=$executionId, approvalId=$approvalId, " +
                "expiresAtEpochMillis=$expiresAtEpochMillis, preview=[REDACTED], reason=[REDACTED])"
    }

    /** 动作已成功完成。 */
    @Serializable
    @SerialName("success")
    data class Success<T>(
        val executionId: String,
        val output: T,
        val redactedOutput: T? = null,
        val remoteReference: String? = null,
    ) : ActionResult<T>() {
        override fun toString(): String =
            "ActionResult.Success(executionId=$executionId, output=[REDACTED], " +
                "redactedOutput=[REDACTED], remoteReference=[REDACTED])"
    }

    /** 动作已明确失败。 */
    @Serializable
    @SerialName("failure")
    data class Failure(
        val executionId: String,
        val error: ActionError,
        val remoteReference: String? = null,
    ) : ActionResult<Nothing>() {
        override fun toString(): String =
            "ActionResult.Failure(executionId=$executionId, errorCode=${error.code}, " +
                "remoteReference=[REDACTED])"
    }

    /** 动作已被用户或系统取消。 */
    @Serializable
    @SerialName("canceled")
    data class Canceled(
        val executionId: String,
        val reason: String,
    ) : ActionResult<Nothing>() {
        override fun toString(): String =
            "ActionResult.Canceled(executionId=$executionId, reason=[REDACTED])"
    }

    /** 动作在进入确定终态前已过期。 */
    @Serializable
    @SerialName("expired")
    data class Expired(
        val executionId: String,
        val reason: String,
    ) : ActionResult<Nothing>() {
        override fun toString(): String =
            "ActionResult.Expired(executionId=$executionId, reason=[REDACTED])"
    }

    /** 远程写入可能已发生，必须按策略对账。 */
    @Serializable
    @SerialName("outcome_unknown")
    data class OutcomeUnknown(
        val executionId: String,
        val error: ActionError,
        val remoteReference: String? = null,
        val reconciliationPolicy: ReconciliationPolicy,
    ) : ActionResult<Nothing>() {
        override fun toString(): String =
            "ActionResult.OutcomeUnknown(executionId=$executionId, errorCode=${error.code}, " +
                "reconciliationPolicy=$reconciliationPolicy, remoteReference=[REDACTED])"
    }
}
