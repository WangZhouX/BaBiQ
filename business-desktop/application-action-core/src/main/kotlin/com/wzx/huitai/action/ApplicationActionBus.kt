package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionConfirmationPort
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ActionRiskPolicy
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionFingerprint
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

private sealed interface InitialRecordResult {
    data class Created(val record: ActionExecutionRecord) : InitialRecordResult
    data class Rejected(val result: ActionBusResult) : InitialRecordResult
}

private sealed interface PreviewAttempt {
    data class Ready(val preview: com.wzx.huitai.action.model.ActionPreview) : PreviewAttempt
    data class Failed(val error: ActionError) : PreviewAttempt
}

private sealed interface StateAdvanceResult {
    data class Advanced(val record: ActionExecutionRecord) : StateAdvanceResult
    data class ExistingTerminal(val result: ActionBusResult) : StateAdvanceResult
    data class Rejected(val result: ActionBusResult.Rejected) : StateAdvanceResult
}

/** Bus 与注册动作之间的模块内部调用边界，生产实现只做直接委派。 */
internal interface RegisteredActionInvoker {
    suspend fun preview(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
    ): ActionInvocationResult

    suspend fun execute(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
    ): ActionInvocationResult
}

private object DirectRegisteredActionInvoker : RegisteredActionInvoker {
    override suspend fun preview(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
    ): ActionInvocationResult = registered.invokePreview(input, context)

    override suspend fun execute(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
    ): ActionInvocationResult = registered.invokeExecute(input, context)
}

/** 用户点击和 Agent 调用共用的应用动作编排入口。 */
class ApplicationActionBus internal constructor(
    private val registry: ActionRegistry,
    private val riskPolicy: ActionRiskPolicy,
    private val confirmationPort: ActionConfirmationPort,
    private val approvalPort: ActionApprovalPort,
    private val executionStore: ActionExecutionStore,
    private val clock: ActionClock,
    private val contextValidator: ActionExecutionContextValidator,
    private val actionInvoker: RegisteredActionInvoker,
) {
    /** 生产装配使用的公开构造，只启用直接动作调用实现。 */
    constructor(
        registry: ActionRegistry,
        riskPolicy: ActionRiskPolicy,
        confirmationPort: ActionConfirmationPort,
        approvalPort: ActionApprovalPort,
        executionStore: ActionExecutionStore,
        clock: ActionClock,
        contextValidator: ActionExecutionContextValidator,
    ) : this(
        registry,
        riskPolicy,
        confirmationPort,
        approvalPort,
        executionStore,
        clock,
        contextValidator,
        DirectRegisteredActionInvoker,
    )
    init {
        check(registry.isFrozen) { "动作注册表必须在创建 Bus 前冻结" }
    }

    /** 解析、校验并按有效风险执行一个动作命令。 */
    suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
        val registered = when (val resolution = registry.resolve(command.actionId)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return ActionBusResult.Rejected(resolution.error)
        }
        val validating = when (val initial = createValidatingRecord(command, command.origin.name.lowercase())) {
            is InitialRecordResult.Created -> initial.record
            is InitialRecordResult.Rejected -> return initial.result
        }
        contextValidator.validate(registered.descriptor, command, context)?.let {
            return persistFailure(validating, it)
        }
        val risk = riskPolicy.evaluate(registered.descriptor, command, context)
        if (risk.baseRisk != registered.descriptor.riskLevel) {
            return persistFailure(
                validating,
                ActionError(ActionErrorCode.PROTOCOL_ERROR, "风险评估基础等级不一致"),
            )
        }
        return when (risk.effectiveRisk) {
            ActionRiskLevel.READ_ONLY -> executeAfterGate(
                registered,
                command,
                context,
                validating,
            )
            ActionRiskLevel.REVERSIBLE_WRITE -> executeReversible(
                registered,
                command,
                context,
                validating,
            )
            ActionRiskLevel.HIGH_RISK -> executeHighRisk(
                registered,
                command,
                context,
                risk,
                validating,
            )
        }
    }

    private suspend fun executeReversible(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        validating: ActionExecutionRecord,
    ): ActionBusResult {
        val preview = try {
            when (val attempt = preview(registered, command, context)) {
                is PreviewAttempt.Ready -> attempt.preview
                is PreviewAttempt.Failed -> return persistFailure(validating, attempt.error)
            }
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                persistPreExecutionCancellation(validating, "动作预览已取消")
            }
        }
        val previewed = when (val advanced = advanceState(validating, ActionExecutionState.PREVIEWED)) {
            is StateAdvanceResult.Advanced -> advanced.record
            is StateAdvanceResult.ExistingTerminal -> return advanced.result
            is StateAdvanceResult.Rejected -> return advanced.result
        }
        val confirmation = try {
            confirmationPort.request(command, preview, context)
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                persistPreExecutionCancellation(previewed, "确认等待已取消")
            }
        } catch (_: Exception) {
            return persistFailure(
                previewed,
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "确认请求失败"),
            )
        }
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return persistFailure(
                previewed,
                ActionError(ActionErrorCode.PROTOCOL_ERROR, "确认决定 executionId 不匹配"),
            )
        }
        return when (confirmation.decision) {
            ConfirmationDecision.ACCEPTED -> executeAfterGate(
                registered,
                command,
                context,
                previewed,
            )
            ConfirmationDecision.REJECTED -> finishWithoutExecution(
                previewed,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                "state_transition",
                emptyPayload(),
                null,
            )
            ConfirmationDecision.EXPIRED -> finishWithoutExecution(
                previewed,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                "state_transition",
                emptyPayload(),
                null,
            )
        }
    }

    private suspend fun executeHighRisk(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        risk: RiskEvaluation,
        validating: ActionExecutionRecord,
    ): ActionBusResult {
        val preview = try {
            when (val attempt = preview(registered, command, context)) {
                is PreviewAttempt.Ready -> attempt.preview
                is PreviewAttempt.Failed -> return persistFailure(validating, attempt.error)
            }
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                persistPreExecutionCancellation(validating, "动作预览已取消")
            }
        }
        val previewed = when (val advanced = advanceState(validating, ActionExecutionState.PREVIEWED)) {
            is StateAdvanceResult.Advanced -> advanced.record
            is StateAdvanceResult.ExistingTerminal -> return advanced.result
            is StateAdvanceResult.Rejected -> return advanced.result
        }
        val confirmation = try {
            confirmationPort.request(command, preview, context)
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                persistPreExecutionCancellation(previewed, "确认等待已取消")
            }
        } catch (_: Exception) {
            return persistFailure(
                previewed,
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "确认请求失败"),
            )
        }
        try {
            confirmation.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return persistFailure(
                previewed,
                ActionError(ActionErrorCode.PROTOCOL_ERROR, "确认决定 executionId 不匹配"),
            )
        }
        when (confirmation.decision) {
            ConfirmationDecision.REJECTED -> return finishWithoutExecution(
                previewed,
                ActionResult.Canceled(command.executionId, "用户拒绝动作预览"),
                "state_transition",
                emptyPayload(),
                null,
            )
            ConfirmationDecision.EXPIRED -> return finishWithoutExecution(
                previewed,
                ActionResult.Expired(command.executionId, "动作预览确认已过期"),
                "state_transition",
                emptyPayload(),
                null,
            )
            ConfirmationDecision.ACCEPTED -> Unit
        }
        val waiting = when (val advanced = advanceState(
            previewed,
            ActionExecutionState.WAITING_APPROVAL,
            transitionType = "approval_requested",
        )) {
            is StateAdvanceResult.Advanced -> advanced.record
            is StateAdvanceResult.ExistingTerminal -> return advanced.result
            is StateAdvanceResult.Rejected -> return advanced.result
        }
        val approval = try {
            approvalPort.request(command, preview, risk, context)
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                persistPreExecutionCancellation(waiting, "审批等待已取消")
            }
        } catch (_: Exception) {
            return persistFailure(
                waiting,
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "审批请求失败"),
            )
        }
        try {
            approval.requireExecution(command.executionId)
        } catch (_: IllegalArgumentException) {
            return persistFailure(
                waiting,
                ActionError(ActionErrorCode.PROTOCOL_ERROR, "审批决定 executionId 不匹配"),
            )
        }
        val payload = approvalPayload(approval)
        return when (approval.decision) {
            ApprovalDecision.APPROVED -> executeAfterGate(
                registered,
                command,
                context,
                waiting,
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
            )
            ApprovalDecision.EXPIRED -> finishWithoutExecution(
                waiting,
                ActionResult.Expired(command.executionId, "高风险动作审批已过期"),
                "approval_expired",
                payload,
                approval.decidedBy,
            )
        }
    }

    private suspend fun preview(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
    ): PreviewAttempt = try {
        when (val invocation = actionInvoker.preview(registered, command.input, context)) {
            is ActionInvocationResult.Previewed -> if (invocation.preview.executionId == command.executionId) {
                PreviewAttempt.Ready(invocation.preview)
            } else {
                PreviewAttempt.Failed(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览 executionId 不匹配"))
            }
            is ActionInvocationResult.Failure -> PreviewAttempt.Failed(invocation.error)
            else -> PreviewAttempt.Failed(ActionError(ActionErrorCode.PROTOCOL_ERROR, "预览返回了非法结果类型"))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        PreviewAttempt.Failed(ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "动作预览失败"))
    }

    private suspend fun executeAfterGate(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        current: ActionExecutionRecord,
        transitionType: String = "state_transition",
        transitionPayload: JsonObject = emptyPayload(),
        actorId: String? = null,
    ): ActionBusResult {
        val running = when (val advanced = advanceState(
            current,
            ActionExecutionState.EXECUTING,
            started = true,
            transitionType = transitionType,
            transitionPayload = transitionPayload,
            actorId = actorId,
        )) {
            is StateAdvanceResult.Advanced -> advanced.record
            is StateAdvanceResult.ExistingTerminal -> return advanced.result
            is StateAdvanceResult.Rejected -> return advanced.result
        }
        val invocation = try {
            actionInvoker.execute(registered, command.input, context)
        } catch (cancellation: CancellationException) {
            handoffCancellation(cancellation) {
                if (registered.descriptor.riskLevel == ActionRiskLevel.READ_ONLY) {
                    persistFailure(
                        running,
                        ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "只读动作执行已取消"),
                    )
                } else {
                    persistExecutionUnknown(
                        running,
                        ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "写动作执行已取消"),
                        registered.descriptor.reconciliationPolicy,
                    )
                }
            }
        } catch (_: Exception) {
            val error = ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "动作执行失败")
            return if (registered.descriptor.riskLevel == ActionRiskLevel.READ_ONLY) {
                persistFailure(running, error)
            } else {
                persistExecutionUnknown(running, error, registered.descriptor.reconciliationPolicy)
            }
        }
        return when (invocation) {
            is ActionInvocationResult.Executed -> persistTerminal(running, invocation.result)
            is ActionInvocationResult.Failure -> persistFailure(running, invocation.error)
            is ActionInvocationResult.OutputEncodingFailed -> persistEncodingFailure(
                running,
                invocation,
                registered.descriptor.reconciliationPolicy,
            )
            is ActionInvocationResult.Previewed,
            is ActionInvocationResult.Reconciled,
            -> persistProtocolFailure(running, "执行返回了非终态结果")
        }
    }

    /** 副作用前取消必须先持久化明确 CANCELED 终态。 */
    private suspend fun persistPreExecutionCancellation(
        current: ActionExecutionRecord,
        reason: String,
    ): ActionBusResult = persistTerminal(
            current,
            ActionResult.Canceled(current.command.executionId, reason),
        )

    /** 在已取消 Job 外完成一次快速持久化交接，并始终保留原始取消为主异常。 */
    private suspend fun handoffCancellation(
        cancellation: CancellationException,
        handoff: suspend () -> ActionBusResult,
    ): Nothing {
        try {
            withContext(NonCancellable) {
                withTimeout(CANCELLATION_HANDOFF_TIMEOUT_MILLIS) {
                    when (val result = handoff()) {
                        is ActionBusResult.Rejected -> throw IllegalStateException(
                            "取消交接失败：${result.error.code}",
                        )
                        else -> Unit
                    }
                }
            }
        } catch (handoffFailure: Throwable) {
            var observableCancellation: Throwable? = cancellation
            val visited = mutableSetOf<Throwable>()
            while (observableCancellation != null && visited.add(observableCancellation)) {
                if (observableCancellation !== handoffFailure) {
                    observableCancellation.addSuppressed(handoffFailure)
                }
                observableCancellation = observableCancellation.cause
            }
        }
        throw cancellation
    }

    /** 写动作开始后的异常只能安全交接为 OUTCOME_UNKNOWN。 */
    private suspend fun persistExecutionUnknown(
        current: ActionExecutionRecord,
        error: ActionError,
        reconciliationPolicy: com.wzx.huitai.action.model.ReconciliationPolicy,
    ): ActionBusResult {
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = current.command.executionId,
            error = error,
            reconciliationPolicy = reconciliationPolicy,
        )
        return persistLocalErrorTerminal(current, unknown, error)
    }

    private suspend fun finishWithoutExecution(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        transitionType: String,
        transitionPayload: JsonObject,
        actorId: String?,
    ): ActionBusResult = persistTerminal(
        current,
        result,
        transitionType,
        transitionPayload,
        actorId,
    )

    private suspend fun persistEncodingFailure(
        running: ActionExecutionRecord,
        invocation: ActionInvocationResult.OutputEncodingFailed,
        reconciliationPolicy: com.wzx.huitai.action.model.ReconciliationPolicy,
    ): ActionBusResult {
        if (invocation.executionId != running.command.executionId ||
            invocation.terminalState != ActionExecutionState.SUCCEEDED
        ) {
            val error = ActionError(ActionErrorCode.PROTOCOL_ERROR, "输出编码失败关联错误")
            val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
                executionId = running.command.executionId,
                error = error,
                remoteReference = invocation.remoteReference,
                reconciliationPolicy = reconciliationPolicy,
            )
            return persistLocalErrorTerminal(running, unknown, error)
        }
        val fact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.OUTPUT_ENCODING_FAILED,
            remoteReference = invocation.remoteReference,
        )
        val completedAt = clock.now()
        return when (val updated = executionStore.transition(
            ExecutionTransition(
                executionId = running.command.executionId,
                expectedVersion = running.recordVersion,
                state = ActionExecutionState.SUCCEEDED,
                result = null,
                successFact = fact,
                updatedAt = completedAt,
                completedAt = completedAt,
                audit = auditDraft(
                    running.command.executionId,
                    ActionExecutionState.EXECUTING,
                    ActionExecutionState.SUCCEEDED,
                    "output_encoding_failed",
                    buildJsonObject { put("successFact", fact.kind) },
                    null,
                    completedAt,
                ),
            ),
        )) {
            is ExecutionTransitionResult.Updated -> ActionBusResult.OutputEncodingFailed(
                invocation.executionId,
                invocation.terminalState,
                invocation.error,
            )
            is ExecutionTransitionResult.ExistingTerminal -> existingTerminal(updated.record)
            is ExecutionTransitionResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    private suspend fun persistTerminal(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        transitionType: String = "state_transition",
        transitionPayload: JsonObject = emptyPayload(),
        actorId: String? = null,
    ): ActionBusResult {
        val terminalState = result.terminalState()
            ?: return persistProtocolFailure(current, "执行返回了中间结果")
        if (result.executionId() != current.command.executionId) {
            return persistProtocolFailure(current, "执行结果 executionId 不匹配")
        }
        val completedAt = clock.now()
        return when (val updated = executionStore.transition(
            ExecutionTransition(
                executionId = current.command.executionId,
                expectedVersion = current.recordVersion,
                state = terminalState,
                result = result,
                updatedAt = completedAt,
                completedAt = completedAt,
                audit = auditDraft(
                    current.command.executionId,
                    current.state,
                    terminalState,
                    transitionType,
                    transitionPayload,
                    actorId,
                    completedAt,
                ),
            ),
        )) {
            is ExecutionTransitionResult.Updated -> updated.record.result?.let(ActionBusResult::Completed)
                ?: conflict("终态记录缺少普通结果")
            is ExecutionTransitionResult.ExistingTerminal -> existingTerminal(updated.record)
            is ExecutionTransitionResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    /** 输入解码等执行前错误在已落 EXECUTING 后必须收束为 FAILED。 */
    private suspend fun persistFailure(
        current: ActionExecutionRecord,
        error: ActionError,
    ): ActionBusResult {
        val result: ActionResult<JsonElement> = ActionResult.Failure(current.command.executionId, error)
        return persistLocalErrorTerminal(current, result, error)
    }

    /** 仅当本地终态成功写入时返回局部错误；已有终态必须原样获胜。 */
    private suspend fun persistLocalErrorTerminal(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        localError: ActionError,
    ): ActionBusResult {
        val terminalState = result.terminalState() ?: return protocolError("局部错误终态无效")
        val completedAt = clock.now()
        return when (val updated = executionStore.transition(
            ExecutionTransition(
                executionId = current.command.executionId,
                expectedVersion = current.recordVersion,
                state = terminalState,
                result = result,
                updatedAt = completedAt,
                completedAt = completedAt,
                audit = auditDraft(
                    current.command.executionId,
                    current.state,
                    terminalState,
                    "state_transition",
                    emptyPayload(),
                    null,
                    completedAt,
                ),
            ),
        )) {
            is ExecutionTransitionResult.Updated -> ActionBusResult.Rejected(localError)
            is ExecutionTransitionResult.ExistingTerminal -> existingTerminal(updated.record)
            is ExecutionTransitionResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    /** 协议关联错误隐藏原始异常，并以明确失败终态保存执行事实。 */
    private suspend fun persistProtocolFailure(
        current: ActionExecutionRecord,
        message: String,
    ): ActionBusResult {
        val error = ActionError(ActionErrorCode.PROTOCOL_ERROR, message)
        return persistFailure(current, error)
    }

    private suspend fun createValidatingRecord(
        command: ActionCommand,
        eventType: String,
    ): InitialRecordResult {
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
        return when (val created = executionStore.compareAndCreate(
            record,
            auditDraft(
                command.executionId,
                ActionExecutionState.RECEIVED,
                ActionExecutionState.VALIDATING,
                eventType,
                emptyPayload(),
                null,
                now,
            ),
        )) {
            is ExecutionCreateResult.Created -> InitialRecordResult.Created(created.record)
            is ExecutionCreateResult.Conflict -> InitialRecordResult.Rejected(ActionBusResult.Rejected(created.error))
            is ExecutionCreateResult.ExistingRunning -> InitialRecordResult.Rejected(conflict("动作已在执行"))
            is ExecutionCreateResult.ExistingTerminal -> InitialRecordResult.Rejected(
                existingTerminal(created.record),
            )
        }
    }

    private suspend fun advanceState(
        current: ActionExecutionRecord,
        state: ActionExecutionState,
        started: Boolean = false,
        transitionType: String = "state_transition",
        transitionPayload: JsonObject = emptyPayload(),
        actorId: String? = null,
    ): StateAdvanceResult {
        val now = clock.now()
        return when (val updated = executionStore.transition(
            ExecutionTransition(
                executionId = current.command.executionId,
                expectedVersion = current.recordVersion,
                state = state,
                updatedAt = now,
                startedAt = now.takeIf { started },
                audit = auditDraft(
                    current.command.executionId,
                    current.state,
                    state,
                    transitionType,
                    transitionPayload,
                    actorId,
                    now,
                ),
            ),
        )) {
            is ExecutionTransitionResult.Updated -> StateAdvanceResult.Advanced(updated.record)
            is ExecutionTransitionResult.ExistingTerminal -> StateAdvanceResult.ExistingTerminal(
                existingTerminal(updated.record),
            )
            is ExecutionTransitionResult.Conflict -> StateAdvanceResult.Rejected(
                ActionBusResult.Rejected(updated.error),
            )
        }
    }

    /** 基于稳定规范 JSON 和动作标识生成 SHA-256 指纹。 */
    private fun fingerprint(command: ActionCommand): ExecutionFingerprint {
        val canonical = command.input.canonicalJson()
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${command.actionId}\n$canonical".toByteArray(StandardCharsets.UTF_8))
        return ExecutionFingerprint(command.actionId, bytes.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val CANCELLATION_HANDOFF_TIMEOUT_MILLIS = 5_000L
    }
}

private fun approvalPayload(approval: ActionApproval): JsonObject = buildJsonObject {
    put("approvalId", approval.approvalId)
    put("decision", approval.decision.name)
}

private fun auditDraft(
    executionId: String,
    fromState: ActionExecutionState,
    toState: ActionExecutionState,
    type: String,
    payload: JsonObject,
    actorId: String?,
    occurredAt: java.time.Instant,
): ActionAuditDraft = ActionAuditDraft(
    executionId = executionId,
    fromState = fromState,
    toState = toState,
    type = type,
    redactedPayload = payload,
    actorId = actorId,
    occurredAt = occurredAt,
)

private fun existingTerminal(record: ActionExecutionRecord): ActionBusResult = when {
    record.result != null -> ActionBusResult.Completed(record.result)
    record.successFact != null -> ActionBusResult.OutputEncodingFailed(
        executionId = record.command.executionId,
        terminalState = record.state,
        error = ActionError(ActionErrorCode.PROTOCOL_ERROR, "动作输出不可用"),
    )
    else -> conflict("终态记录缺少事实")
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
