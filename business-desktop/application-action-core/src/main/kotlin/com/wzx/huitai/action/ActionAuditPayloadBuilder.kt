package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionConfirmation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** 统一构造真实动作执行链的稳定审计业务 schema。 */
internal object ActionAuditPayloadBuilder {
    /**
     * 合并冻结命令、风险、审批和终态事实；不可用关联字段用 JSON null 保持稳定语义。
     *
     * @param command 当前 execution 的冻结动作命令。
     * @param risk 当前有效风险。
     * @param occurredAt 当前业务事件时间。
     * @param details 当前迁移额外的已脱敏字段。
     * @param confirmation 当前迁移绑定的真实确认决定。
     * @param approval 当前迁移绑定的真实审批决定。
     * @param requestedAt 审批请求的真实业务时间。
     */
    fun build(
        command: ActionCommand,
        risk: ActionRiskLevel,
        occurredAt: Instant,
        details: JsonObject = JsonObject(emptyMap()),
        confirmation: ActionConfirmation? = null,
        approval: ActionApproval? = null,
        requestedAt: Instant? = null,
        result: ActionResult<JsonElement>? = null,
        terminalState: ActionExecutionState? = null,
        error: ActionError? = null,
    ): JsonObject {
        val values = linkedMapOf<String, JsonElement>(
            "executionId" to JsonPrimitive(command.executionId),
            "actionId" to JsonPrimitive(command.actionId),
            "actionVersion" to JsonPrimitive(command.actionVersion),
            "origin" to JsonPrimitive(command.origin.name.lowercase()),
            "threadId" to command.correlation?.threadId.jsonOrNull(),
            "turnId" to command.correlation?.turnId.jsonOrNull(),
            "toolCallId" to command.correlation?.toolCallId.jsonOrNull(),
            "userId" to JsonPrimitive(command.identityScope.userId),
            "tenantId" to JsonPrimitive(command.identityScope.tenantId),
            "platformId" to JsonPrimitive(command.identityScope.platformId),
            "authSessionId" to JsonPrimitive(command.identityScope.authSessionId),
            "desktopInstanceId" to JsonPrimitive(command.identityScope.desktopInstanceId),
            "desktopSessionId" to JsonPrimitive(command.identityScope.desktopSessionId),
            "identityEpoch" to JsonPrimitive(command.identityScope.identityEpoch),
            "pageId" to JsonPrimitive(command.pageId),
            "contextRevision" to JsonPrimitive(command.contextRevision),
            "risk" to JsonPrimitive(risk.name.lowercase()),
            "confirmationId" to confirmation?.decisionId.jsonOrNull(),
            "confirmationDecision" to confirmation?.decision?.name.jsonOrNull(),
            "confirmationDecidedAt" to confirmation?.decidedAt?.toString().jsonOrNull(),
            "approvalId" to approval?.approvalId.jsonOrNull(),
            "approvalDecision" to approval?.decision?.name.jsonOrNull(),
            "approvalActorId" to approval?.decidedBy.jsonOrNull(),
            "requestedAt" to requestedAt?.toString().jsonOrNull(),
            "approvalDecidedAt" to approval?.decidedAt?.toString().jsonOrNull(),
            "remoteReference" to result.remoteReference().jsonOrNull(),
            "terminalStatus" to terminalState?.name?.lowercase().jsonOrNull(),
            "errorCode" to (error ?: result.errorOrNull())?.code?.name?.lowercase().jsonOrNull(),
            "occurredAt" to JsonPrimitive(occurredAt.toString()),
        )
        details.forEach { (key, value) ->
            if (key !in PROTECTED_FIELDS) values[key] = value
        }
        return JsonObject(values)
    }

    private val PROTECTED_FIELDS = setOf(
        "executionId", "actionId", "actionVersion", "origin",
        "threadId", "turnId", "toolCallId",
        "userId", "tenantId", "platformId", "authSessionId",
        "desktopInstanceId", "desktopSessionId", "identityEpoch",
        "pageId", "contextRevision", "risk",
        "confirmationId", "confirmationDecision", "confirmationDecidedAt",
        "approvalId", "approvalDecision", "approvalActorId", "requestedAt", "approvalDecidedAt",
        "remoteReference", "terminalStatus", "errorCode", "occurredAt",
    )
}

private fun String?.jsonOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

private fun ActionResult<JsonElement>?.remoteReference(): String? = when (this) {
    is ActionResult.Success -> remoteReference
    is ActionResult.Failure -> remoteReference
    is ActionResult.OutcomeUnknown -> remoteReference
    else -> null
}

private fun ActionResult<JsonElement>?.errorOrNull(): ActionError? = when (this) {
    is ActionResult.Failure -> error
    is ActionResult.OutcomeUnknown -> error
    else -> null
}
