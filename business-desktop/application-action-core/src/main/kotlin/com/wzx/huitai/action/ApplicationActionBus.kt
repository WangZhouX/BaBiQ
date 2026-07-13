package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionAuditPort
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionConfirmationPort
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ActionRiskPolicy
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionFingerprint
import com.wzx.huitai.action.port.TerminalExecutionUpdate
import com.wzx.huitai.action.port.TerminalUpdateResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        suspend fun audit(from: ActionExecutionState, to: ActionExecutionState, type: String) {
            auditPort.append(
                ActionAuditEvent(
                    executionId = command.executionId,
                    sequence = ++sequence,
                    fromState = from,
                    toState = to,
                    type = type,
                    redactedPayload = buildJsonObject { },
                    actorId = null,
                    occurredAt = clock.now(),
                ),
            )
        }

        val registered = when (val resolution = registry.resolve(command.actionId)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return ActionBusResult.Rejected(resolution.error)
        }
        audit(ActionExecutionState.RECEIVED, ActionExecutionState.VALIDATING, command.origin.name.lowercase())
        contextValidator.validate(registered.descriptor, command, context)?.let {
            return ActionBusResult.Rejected(it)
        }
        val risk = riskPolicy.evaluate(registered.descriptor, command, context)
        if (risk.baseRisk != registered.descriptor.riskLevel) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "风险评估基础等级不一致"))
        }
        return when (risk.effectiveRisk) {
            ActionRiskLevel.READ_ONLY -> executeReadOnly(registered, command, context, ::audit)
            ActionRiskLevel.REVERSIBLE_WRITE -> executeReversible(registered, command, context, ::audit)
            ActionRiskLevel.HIGH_RISK -> executeHighRisk(registered, command, context, risk, ::audit)
        }
    }

    private suspend fun executeHighRisk(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        risk: com.wzx.huitai.action.port.RiskEvaluation,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
    ): ActionBusResult {
        val preview = when (val invocation = registered.invokePreview(command.input, context)) {
            is ActionInvocationResult.Previewed -> invocation.preview
            is ActionInvocationResult.Failure -> return ActionBusResult.Rejected(invocation.error)
            else -> return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览返回了错误结果类型"))
        }
        if (preview.executionId != command.executionId) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览 executionId 不匹配"))
        }
        audit(ActionExecutionState.VALIDATING, ActionExecutionState.PREVIEWED, "state_transition")
        val confirmation = confirmationPort.request(command, preview, context)
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "确认决定 executionId 不匹配"))
        }
        when (confirmation.decision) {
            ConfirmationDecision.REJECTED -> return finishWithoutExecution(
                command,
                ActionExecutionState.PREVIEWED,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                audit,
            )
            ConfirmationDecision.EXPIRED -> return finishWithoutExecution(
                command,
                ActionExecutionState.PREVIEWED,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                audit,
            )
            ConfirmationDecision.ACCEPTED -> Unit
        }
        audit(ActionExecutionState.PREVIEWED, ActionExecutionState.WAITING_APPROVAL, "approval_requested")
        val approval = approvalPort.request(command, preview, risk, context)
        try {
            approval.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "审批决定 executionId 不匹配"))
        }
        return when (approval.decision) {
            ApprovalDecision.APPROVED -> {
                executeAfterGate(
                    registered,
                    command,
                    context,
                    ActionExecutionState.WAITING_APPROVAL,
                    audit,
                    transitionType = "approval_approved",
                )
            }
            ApprovalDecision.DENIED -> finishWithoutExecution(
                command,
                ActionExecutionState.WAITING_APPROVAL,
                ActionResult.Canceled(command.executionId, "高风险动作审批被拒绝"),
                audit,
                transitionType = "approval_denied",
            )
            ApprovalDecision.EXPIRED -> finishWithoutExecution(
                command,
                ActionExecutionState.WAITING_APPROVAL,
                ActionResult.Expired(command.executionId, "高风险动作审批已过期"),
                audit,
                transitionType = "approval_expired",
            )
        }
    }

    private suspend fun executeReversible(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
    ): ActionBusResult {
        val preview = when (val invocation = registered.invokePreview(command.input, context)) {
            is ActionInvocationResult.Previewed -> invocation.preview
            is ActionInvocationResult.Failure -> return ActionBusResult.Rejected(invocation.error)
            else -> return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览返回了错误结果类型"))
        }
        if (preview.executionId != command.executionId) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览 executionId 不匹配"))
        }
        audit(ActionExecutionState.VALIDATING, ActionExecutionState.PREVIEWED, "state_transition")
        val confirmation = confirmationPort.request(command, preview, context)
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "确认决定 executionId 不匹配"))
        }
        return when (confirmation.decision) {
            ConfirmationDecision.ACCEPTED -> executeAfterGate(
                registered,
                command,
                context,
                ActionExecutionState.PREVIEWED,
                audit,
            )
            ConfirmationDecision.REJECTED -> finishWithoutExecution(
                command,
                ActionExecutionState.PREVIEWED,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                audit,
            )
            ConfirmationDecision.EXPIRED -> finishWithoutExecution(
                command,
                ActionExecutionState.PREVIEWED,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                audit,
            )
        }
    }

    private suspend fun executeReadOnly(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
    ): ActionBusResult {
        return executeAfterGate(registered, command, context, ActionExecutionState.VALIDATING, audit)
    }

    private suspend fun executeAfterGate(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        fromState: ActionExecutionState,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
        transitionType: String = "state_transition",
    ): ActionBusResult {
        val running = createRecord(command, ActionExecutionState.EXECUTING, started = true)
        when (val created = executionStore.compareAndCreate(running)) {
            is ExecutionCreateResult.Created -> Unit
            is ExecutionCreateResult.Conflict -> return ActionBusResult.Rejected(created.error)
            is ExecutionCreateResult.ExistingRunning -> return ActionBusResult.Rejected(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "动作已在执行"),
            )
            is ExecutionCreateResult.ExistingTerminal -> return created.record.result?.let(ActionBusResult::Completed)
                ?: ActionBusResult.Rejected(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "终态记录缺少结果"))
        }
        audit(fromState, ActionExecutionState.EXECUTING, transitionType)
        return when (val invocation = registered.invokeExecute(command.input, context)) {
            is ActionInvocationResult.Executed -> persistTerminal(command, running, invocation.result, audit)
            is ActionInvocationResult.Failure -> ActionBusResult.Rejected(invocation.error)
            is ActionInvocationResult.OutputEncodingFailed -> persistEncodingFailure(command, running, invocation, audit)
            is ActionInvocationResult.Previewed,
            is ActionInvocationResult.Reconciled,
            -> ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "执行返回了非终态结果"))
        }
    }

    private suspend fun finishWithoutExecution(
        command: ActionCommand,
        fromState: ActionExecutionState,
        result: ActionResult<JsonElement>,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
        transitionType: String = "state_transition",
    ): ActionBusResult {
        val terminalState = result.terminalState()!!
        val completedAt = clock.now()
        val record = ActionExecutionRecord(
            command = command,
            fingerprint = fingerprint(command),
            state = terminalState,
            result = result,
            createdAt = completedAt,
            completedAt = completedAt,
            updatedAt = completedAt,
            recordVersion = 1,
        )
        return when (val created = executionStore.compareAndCreate(record)) {
            is ExecutionCreateResult.Created -> {
                audit(fromState, terminalState, transitionType)
                ActionBusResult.Completed(created.record.result!!)
            }
            is ExecutionCreateResult.Conflict -> ActionBusResult.Rejected(created.error)
            is ExecutionCreateResult.ExistingRunning -> ActionBusResult.Rejected(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "动作已在执行"),
            )
            is ExecutionCreateResult.ExistingTerminal -> ActionBusResult.Completed(created.record.result!!)
        }
    }

    private fun createRecord(
        command: ActionCommand,
        state: ActionExecutionState,
        started: Boolean,
    ): ActionExecutionRecord {
        val now = clock.now()
        return ActionExecutionRecord(
            command = command,
            fingerprint = fingerprint(command),
            state = state,
            result = null,
            createdAt = now,
            startedAt = now.takeIf { started },
            updatedAt = now,
            recordVersion = 1,
        )
    }

    private suspend fun persistEncodingFailure(
        command: ActionCommand,
        running: ActionExecutionRecord,
        invocation: ActionInvocationResult.OutputEncodingFailed,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
    ): ActionBusResult {
        if (invocation.executionId != command.executionId || invocation.terminalState != ActionExecutionState.SUCCEEDED) {
            return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "输出编码失败关联错误"))
        }
        val result: ActionResult<JsonElement> = ActionResult.Success(command.executionId, JsonNull)
        val persisted = persistTerminal(command, running, result, audit)
        return if (persisted is ActionBusResult.Completed) {
            ActionBusResult.OutputEncodingFailed(invocation.executionId, invocation.terminalState, invocation.error)
        } else {
            persisted
        }
    }

    private suspend fun persistTerminal(
        command: ActionCommand,
        running: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        audit: suspend (ActionExecutionState, ActionExecutionState, String) -> Unit,
    ): ActionBusResult {
        val state = result.terminalState()
            ?: return ActionBusResult.Rejected(ActionError(ActionErrorCode.PROTOCOL_ERROR, "执行返回了中间结果"))
        val completedAt = clock.now()
        return when (val updated = executionStore.updateTerminal(
            TerminalExecutionUpdate(command.executionId, running.recordVersion, state, result, completedAt),
        )) {
            is TerminalUpdateResult.Updated -> {
                audit(ActionExecutionState.EXECUTING, state, "state_transition")
                ActionBusResult.Completed(updated.record.result!!)
            }
            is TerminalUpdateResult.ExistingTerminal -> ActionBusResult.Completed(updated.record.result!!)
            is TerminalUpdateResult.Conflict -> ActionBusResult.Rejected(updated.error)
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

private fun ActionResult<JsonElement>.terminalState(): ActionExecutionState? = when (this) {
    is ActionResult.Preview, is ActionResult.ApprovalRequired -> null
    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
    is ActionResult.Failure -> ActionExecutionState.FAILED
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
}

private fun JsonElement.canonicalJson(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> toString()
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        "${JsonPrimitive(it.key)}:${it.value.canonicalJson()}"
    }
}
