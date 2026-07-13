package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionAuditPort
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionConfirmationPort
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ActionRiskPolicy
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionFingerprint
import com.wzx.huitai.action.port.ExecutionStateUpdate
import com.wzx.huitai.action.port.ExecutionStateUpdateResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.action.port.TerminalExecutionUpdate
import com.wzx.huitai.action.port.TerminalUpdateResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 应用动作在 JSON 安全边界上的执行结果。 */
sealed interface ActionBusResult {
    data class Completed(val result: ActionResult<JsonElement>) : ActionBusResult
    data class Rejected(val error: ActionError) : ActionBusResult

    /** 动作已成功落终态，但输出无法编码到展示边界。 */
    data class OutputEncodingFailed(
        val executionId: String,
        val terminalState: ActionExecutionState,
        val error: ActionError,
    ) : ActionBusResult
}

private typealias AuditAppender = suspend (
    ActionExecutionState,
    ActionExecutionState,
    String,
    JsonObject,
    String?,
) -> Unit

private sealed interface InitialRecordResult {
    data class Created(val record: ActionExecutionRecord) : InitialRecordResult
    data class Rejected(val result: ActionBusResult) : InitialRecordResult
}

/** 用户点击和 Agent 调用共用的应用动作编排入口。 */
class ApplicationActionBus(
    private val registry: ActionRegistry,
    private val riskPolicy: ActionRiskPolicy,
    private val confirmationPort: ActionConfirmationPort,
    private val approvalPort: ActionApprovalPort,
    private val executionStore: ActionExecutionStore,
    private val auditPort: ActionAuditPort,
    private val clock: ActionClock,
    private val contextValidator: ActionExecutionContextValidator,
) {
    init {
        check(registry.isFrozen) { "动作注册表必须在创建 Bus 前冻结" }
    }

    /** 解析、校验并按有效风险执行一个动作命令。 */
    suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
        var sequence = 0L
        suspend fun audit(
            from: ActionExecutionState,
            to: ActionExecutionState,
            type: String,
            payload: JsonObject,
            actorId: String?,
        ) {
            auditPort.append(
                ActionAuditEvent(
                    executionId = command.executionId,
                    sequence = ++sequence,
                    fromState = from,
                    toState = to,
                    type = type,
                    redactedPayload = payload,
                    actorId = actorId,
                    occurredAt = clock.now(),
                ),
            )
        }

        val registered = when (val resolution = registry.resolve(command.actionId)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return ActionBusResult.Rejected(resolution.error)
        }
        audit(
            ActionExecutionState.RECEIVED,
            ActionExecutionState.VALIDATING,
            command.origin.name.lowercase(),
            emptyPayload(),
            null,
        )
        contextValidator.validate(registered.descriptor, command, context)?.let {
            return ActionBusResult.Rejected(it)
        }
        val risk = riskPolicy.evaluate(registered.descriptor, command, context)
        if (risk.baseRisk != registered.descriptor.riskLevel) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "风险评估基础等级不一致"))
        }
        val validating = when (val initial = createValidatingRecord(command)) {
            is InitialRecordResult.Created -> initial.record
            is InitialRecordResult.Rejected -> return initial.result
        }
        return when (risk.effectiveRisk) {
            ActionRiskLevel.READ_ONLY -> executeAfterGate(
                registered,
                command,
                context,
                validating,
                ActionExecutionState.VALIDATING,
                ::audit,
            )
            ActionRiskLevel.REVERSIBLE_WRITE -> executeReversible(
                registered,
                command,
                context,
                validating,
                ::audit,
            )
            ActionRiskLevel.HIGH_RISK -> executeHighRisk(
                registered,
                command,
                context,
                risk,
                validating,
                ::audit,
            )
        }
    }

    private suspend fun executeReversible(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        validating: ActionExecutionRecord,
        audit: AuditAppender,
    ): ActionBusResult {
        val preview = preview(registered, command, context) ?: return protocolError("动作预览失败或关联错误")
        val previewed = advanceState(validating, ActionExecutionState.PREVIEWED)
            ?: return conflict("无法持久化预览状态")
        audit(
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            "state_transition",
            emptyPayload(),
            null,
        )
        val confirmation = confirmationPort.request(command, preview, context)
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return protocolError("确认决定 executionId 不匹配")
        }
        return when (confirmation.decision) {
            ConfirmationDecision.ACCEPTED -> executeAfterGate(
                registered,
                command,
                context,
                previewed,
                ActionExecutionState.PREVIEWED,
                audit,
            )
            ConfirmationDecision.REJECTED -> finishWithoutExecution(
                previewed,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                "state_transition",
                emptyPayload(),
                null,
                audit,
            )
            ConfirmationDecision.EXPIRED -> finishWithoutExecution(
                previewed,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                "state_transition",
                emptyPayload(),
                null,
                audit,
            )
        }
    }

    private suspend fun executeHighRisk(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        risk: RiskEvaluation,
        validating: ActionExecutionRecord,
        audit: AuditAppender,
    ): ActionBusResult {
        val preview = preview(registered, command, context) ?: return protocolError("动作预览失败或关联错误")
        val previewed = advanceState(validating, ActionExecutionState.PREVIEWED)
            ?: return conflict("无法持久化预览状态")
        audit(
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            "state_transition",
            emptyPayload(),
            null,
        )
        val confirmation = confirmationPort.request(command, preview, context)
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return protocolError("确认决定 executionId 不匹配")
        }
        when (confirmation.decision) {
            ConfirmationDecision.REJECTED -> return finishWithoutExecution(
                previewed,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                "state_transition",
                emptyPayload(),
                null,
                audit,
            )
            ConfirmationDecision.EXPIRED -> return finishWithoutExecution(
                previewed,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                "state_transition",
                emptyPayload(),
                null,
                audit,
            )
            ConfirmationDecision.ACCEPTED -> Unit
        }
        val waiting = advanceState(previewed, ActionExecutionState.WAITING_APPROVAL)
            ?: return conflict("无法持久化审批等待状态")
        audit(
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
            "approval_requested",
            emptyPayload(),
            null,
        )
        val approval = approvalPort.request(command, preview, risk, context)
        try {
            approval.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return protocolError("审批决定 executionId 不匹配")
        }
        val payload = approvalPayload(approval)
        return when (approval.decision) {
            ApprovalDecision.APPROVED -> executeAfterGate(
                registered,
                command,
                context,
                waiting,
                ActionExecutionState.WAITING_APPROVAL,
                audit,
                "approval_approved",
                payload,
                approval.decidedBy,
            )
            ApprovalDecision.DENIED -> finishWithoutExecution(
                waiting,
                ActionResult.Canceled(command.executionId, "高风险动作审批被拒绝"),
                "approval_denied",
                payload,
                approval.decidedBy,
                audit,
            )
            ApprovalDecision.EXPIRED -> finishWithoutExecution(
                waiting,
                ActionResult.Expired(command.executionId, "高风险动作审批已过期"),
                "approval_expired",
                payload,
                approval.decidedBy,
                audit,
            )
        }
    }

    private suspend fun preview(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
    ) = when (val invocation = registered.invokePreview(command.input, context)) {
        is ActionInvocationResult.Previewed -> invocation.preview.takeIf { it.executionId == command.executionId }
        else -> null
    }

    private suspend fun executeAfterGate(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        current: ActionExecutionRecord,
        fromState: ActionExecutionState,
        audit: AuditAppender,
        transitionType: String = "state_transition",
        transitionPayload: JsonObject = emptyPayload(),
        actorId: String? = null,
    ): ActionBusResult {
        val running = advanceState(current, ActionExecutionState.EXECUTING, started = true)
            ?: return conflict("无法持久化执行状态")
        audit(
            fromState,
            ActionExecutionState.EXECUTING,
            transitionType,
            transitionPayload,
            actorId,
        )
        return when (val invocation = registered.invokeExecute(command.input, context)) {
            is ActionInvocationResult.Executed -> persistTerminal(running, invocation.result, audit)
            is ActionInvocationResult.Failure -> persistFailureAfterExecutionStart(running, invocation.error, audit)
            is ActionInvocationResult.OutputEncodingFailed -> persistEncodingFailure(running, invocation, audit)
            is ActionInvocationResult.Previewed,
            is ActionInvocationResult.Reconciled,
            -> persistProtocolFailure(running, "执行返回了非终态结果", audit)
        }
    }

    private suspend fun finishWithoutExecution(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        transitionType: String,
        transitionPayload: JsonObject,
        actorId: String?,
        audit: AuditAppender,
    ): ActionBusResult = persistTerminal(
        current,
        result,
        audit,
        transitionType,
        transitionPayload,
        actorId,
    )

    private suspend fun persistEncodingFailure(
        running: ActionExecutionRecord,
        invocation: ActionInvocationResult.OutputEncodingFailed,
        audit: AuditAppender,
    ): ActionBusResult {
        if (invocation.executionId != running.command.executionId ||
            invocation.terminalState != ActionExecutionState.SUCCEEDED
        ) {
            return protocolError("输出编码失败关联错误")
        }
        val fact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.OUTPUT_ENCODING_FAILED,
            remoteReference = invocation.remoteReference,
        )
        val completedAt = clock.now()
        return when (val updated = executionStore.updateTerminal(
            TerminalExecutionUpdate(
                executionId = running.command.executionId,
                expectedVersion = running.recordVersion,
                terminalState = ActionExecutionState.SUCCEEDED,
                result = null,
                completedAt = completedAt,
                successFact = fact,
            ),
        )) {
            is TerminalUpdateResult.Updated -> {
                audit(
                    ActionExecutionState.EXECUTING,
                    ActionExecutionState.SUCCEEDED,
                    "output_encoding_failed",
                    buildJsonObject { put("successFact", fact.kind) },
                    null,
                )
                ActionBusResult.OutputEncodingFailed(
                    invocation.executionId,
                    invocation.terminalState,
                    invocation.error,
                )
            }
            is TerminalUpdateResult.ExistingTerminal -> conflict("终态已存在")
            is TerminalUpdateResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    private suspend fun persistTerminal(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        audit: AuditAppender,
        transitionType: String = "state_transition",
        transitionPayload: JsonObject = emptyPayload(),
        actorId: String? = null,
    ): ActionBusResult {
        val terminalState = result.terminalState()
            ?: return persistProtocolFailure(current, "执行返回了中间结果", audit)
        if (result.executionId() != current.command.executionId) {
            return persistProtocolFailure(current, "执行结果 executionId 不匹配", audit)
        }
        val completedAt = clock.now()
        return when (val updated = executionStore.updateTerminal(
            TerminalExecutionUpdate(
                current.command.executionId,
                current.recordVersion,
                terminalState,
                result,
                completedAt,
            ),
        )) {
            is TerminalUpdateResult.Updated -> {
                audit(
                    current.state,
                    terminalState,
                    transitionType,
                    transitionPayload,
                    actorId,
                )
                updated.record.result?.let(ActionBusResult::Completed)
                    ?: conflict("终态记录缺少普通结果")
            }
            is TerminalUpdateResult.ExistingTerminal -> updated.record.result?.let(ActionBusResult::Completed)
                ?: conflict("终态记录缺少普通结果")
            is TerminalUpdateResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    /** 输入解码等执行前错误在已落 EXECUTING 后必须收束为 FAILED。 */
    private suspend fun persistFailureAfterExecutionStart(
        current: ActionExecutionRecord,
        error: ActionError,
        audit: AuditAppender,
    ): ActionBusResult {
        val result: ActionResult<JsonElement> = ActionResult.Failure(current.command.executionId, error)
        val persisted = persistTerminal(current, result, audit)
        return if (persisted is ActionBusResult.Completed) ActionBusResult.Rejected(error) else persisted
    }

    /** 协议关联错误隐藏原始异常，并以明确失败终态保存执行事实。 */
    private suspend fun persistProtocolFailure(
        current: ActionExecutionRecord,
        message: String,
        audit: AuditAppender,
    ): ActionBusResult {
        val error = ActionError(ActionErrorCode.PROTOCOL_ERROR, message)
        return persistFailureAfterExecutionStart(current, error, audit)
    }

    private suspend fun createValidatingRecord(command: ActionCommand): InitialRecordResult {
        val now = clock.now()
        val record = ActionExecutionRecord(
            command = command,
            fingerprint = fingerprint(command),
            state = ActionExecutionState.VALIDATING,
            result = null,
            createdAt = now,
            updatedAt = now,
            recordVersion = 1,
        )
        return when (val created = executionStore.compareAndCreate(record)) {
            is ExecutionCreateResult.Created -> InitialRecordResult.Created(created.record)
            is ExecutionCreateResult.Conflict -> InitialRecordResult.Rejected(ActionBusResult.Rejected(created.error))
            is ExecutionCreateResult.ExistingRunning -> InitialRecordResult.Rejected(conflict("动作已在执行"))
            is ExecutionCreateResult.ExistingTerminal -> InitialRecordResult.Rejected(
                created.record.result?.let(ActionBusResult::Completed)
                    ?: conflict("终态记录缺少结果"),
            )
        }
    }

    private suspend fun advanceState(
        current: ActionExecutionRecord,
        state: ActionExecutionState,
        started: Boolean = false,
    ): ActionExecutionRecord? {
        val now = clock.now()
        return when (val updated = executionStore.updateState(
            ExecutionStateUpdate(
                executionId = current.command.executionId,
                expectedVersion = current.recordVersion,
                state = state,
                updatedAt = now,
                startedAt = now.takeIf { started },
            ),
        )) {
            is ExecutionStateUpdateResult.Updated -> updated.record
            is ExecutionStateUpdateResult.Conflict -> null
        }
    }

    /** 基于稳定规范 JSON 和动作标识生成 SHA-256 指纹。 */
    private fun fingerprint(command: ActionCommand): ExecutionFingerprint {
        val canonical = command.input.canonicalJson()
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${command.actionId}\n$canonical".toByteArray(StandardCharsets.UTF_8))
        return ExecutionFingerprint(command.actionId, bytes.joinToString("") { "%02x".format(it) })
    }
}

private fun approvalPayload(approval: ActionApproval): JsonObject = buildJsonObject {
    put("approvalId", approval.approvalId)
    put("decision", approval.decision.name)
}

private fun emptyPayload(): JsonObject = buildJsonObject { }

private fun protocolError(message: String): ActionBusResult.Rejected =
    ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, message))

private fun conflict(message: String): ActionBusResult.Rejected =
    ActionBusResult.Rejected(ActionError(ActionErrorCode.EXECUTION_CONFLICT, message))

private fun ActionResult<JsonElement>.terminalState(): ActionExecutionState? = when (this) {
    is ActionResult.Preview, is ActionResult.ApprovalRequired -> null
    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
    is ActionResult.Failure -> ActionExecutionState.FAILED
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
}

private fun ActionResult<JsonElement>.executionId(): String = when (this) {
    is ActionResult.Preview -> preview.executionId
    is ActionResult.ApprovalRequired -> executionId
    is ActionResult.Success -> executionId
    is ActionResult.Failure -> executionId
    is ActionResult.Canceled -> executionId
    is ActionResult.Expired -> executionId
    is ActionResult.OutcomeUnknown -> executionId
}

private fun JsonElement.canonicalJson(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> toString()
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        "${JsonPrimitive(it.key)}:${it.value.canonicalJson()}"
    }
}
