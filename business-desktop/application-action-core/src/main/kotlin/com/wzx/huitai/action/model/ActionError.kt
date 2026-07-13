package com.wzx.huitai.action.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 错误发生后提供给上层的恢复提示。
 *
 * `RETRYABLE` 只表示上层可在独立安全判断后尝试恢复，不能单独授权自动 execute。
 * 自动重放必须同时依据后续传输结果和 [ActionReplayPolicy]，本枚举不提供重放决策。
 */
@Serializable
enum class ErrorDisposition {
    @SerialName("user_fixable")
    USER_FIXABLE,

    @SerialName("relogin_required")
    RELOGIN_REQUIRED,

    @SerialName("retryable")
    RETRYABLE,

    @SerialName("non_retryable")
    NON_RETRYABLE,

    @SerialName("manual_reconciliation")
    MANUAL_RECONCILIATION,
}

/**
 * 动作执行错误码。
 *
 * @param disposition 此错误对应的上层恢复提示，不是自动重放授权。
 * 自动重放必须由传输结果和 [ActionReplayPolicy] 共同决定。
 */
@Serializable
enum class ActionErrorCode(val disposition: ErrorDisposition) {
    @SerialName("action_not_found")
    ACTION_NOT_FOUND(ErrorDisposition.NON_RETRYABLE),

    @SerialName("action_disabled")
    ACTION_DISABLED(ErrorDisposition.NON_RETRYABLE),

    @SerialName("permission_denied")
    PERMISSION_DENIED(ErrorDisposition.NON_RETRYABLE),

    @SerialName("validation_failed")
    VALIDATION_FAILED(ErrorDisposition.USER_FIXABLE),

    @SerialName("context_stale")
    CONTEXT_STALE(ErrorDisposition.USER_FIXABLE),

    @SerialName("approval_denied")
    APPROVAL_DENIED(ErrorDisposition.NON_RETRYABLE),

    @SerialName("approval_expired")
    APPROVAL_EXPIRED(ErrorDisposition.RETRYABLE),

    @SerialName("execution_conflict")
    EXECUTION_CONFLICT(ErrorDisposition.RETRYABLE),

    @SerialName("execution_timeout")
    EXECUTION_TIMEOUT(ErrorDisposition.RETRYABLE),

    @SerialName("desktop_disconnected")
    DESKTOP_DISCONNECTED(ErrorDisposition.RETRYABLE),

    @SerialName("agent_disconnected")
    AGENT_DISCONNECTED(ErrorDisposition.RETRYABLE),

    @SerialName("auth_expired")
    AUTH_EXPIRED(ErrorDisposition.RELOGIN_REQUIRED),

    @SerialName("membership_expired")
    MEMBERSHIP_EXPIRED(ErrorDisposition.RELOGIN_REQUIRED),

    @SerialName("remote_request_failed")
    REMOTE_REQUEST_FAILED(ErrorDisposition.RETRYABLE),

    @SerialName("outcome_unknown")
    OUTCOME_UNKNOWN(ErrorDisposition.MANUAL_RECONCILIATION),

    @SerialName("protocol_error")
    PROTOCOL_ERROR(ErrorDisposition.NON_RETRYABLE),
}

/**
 * 动作执行错误。
 *
 * @param code 稳定的机器可读错误码。
 * @param message 面向调用方的错误说明。
 * @param details 已脱敏的结构化错误上下文。
 */
@Serializable
data class ActionError(
    val code: ActionErrorCode,
    val message: String,
    val details: JsonObject? = null,
) {
    /** 日志中只保留结构化错误类型，隐藏说明和上下文。 */
    override fun toString(): String =
        "ActionError(code=$code, disposition=${code.disposition}, message=[REDACTED], details=[REDACTED])"
}
