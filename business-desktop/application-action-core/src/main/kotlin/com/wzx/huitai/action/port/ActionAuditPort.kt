package com.wzx.huitai.action.port

import com.wzx.huitai.action.model.ActionExecutionState
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * 只追加的不可变动作迁移事件。
 *
 * @param executionId 动作执行标识。
 * @param sequence execution 内单调递增序号。
 * @param fromState 迁移前状态。
 * @param toState 迁移后状态。
 * @param type 事件类型。
 * @param redactedPayload 已脱敏事件载荷。
 * @param actorId 事件操作者标识。
 * @param occurredAt 事件发生时间。
 */
data class ActionAuditEvent(
    val executionId: String,
    val sequence: Long,
    val fromState: ActionExecutionState?,
    val toState: ActionExecutionState,
    val type: String,
    val redactedPayload: JsonObject,
    val actorId: String?,
    val occurredAt: Instant,
) {
    /** 日志保留状态迁移，隐藏载荷和操作者。 */
    override fun toString(): String =
        "ActionAuditEvent(executionId=$executionId, sequence=$sequence, fromState=$fromState, " +
            "toState=$toState, type=$type, redactedPayload=[REDACTED], actorId=[REDACTED], " +
            "occurredAt=$occurredAt)"
}

/**
 * 等待持久化边界分配 sequence 的审计草稿。
 *
 * @param executionId 动作执行标识。
 * @param fromState 迁移前状态。
 * @param toState 迁移后状态。
 * @param type 事件类型。
 * @param redactedPayload 已脱敏载荷。
 * @param actorId 操作者标识，由持久化适配器安全保存。
 * @param occurredAt 业务事件发生时间。
 */
data class ActionAuditDraft(
    val executionId: String,
    val fromState: ActionExecutionState?,
    val toState: ActionExecutionState,
    val type: String,
    val redactedPayload: JsonObject,
    val actorId: String?,
    val occurredAt: Instant,
)

/** 仅允许追加动作审计事件。 */
fun interface ActionAuditPort {
    /** 追加一个不可变事件，不提供修改或删除能力。 */
    suspend fun append(event: ActionAuditEvent)
}
