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
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationAuditAppend
import com.wzx.huitai.action.port.ReconciliationAuditAppendResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 应用动作在 JSON 安全边界上的执行结果。 */
sealed interface ActionBusResult {
    data class Completed(val result: ActionResult<JsonElement>) : ActionBusResult
    data class Rejected(val error: ActionError) : ActionBusResult

    /** 相同 execution 已由另一调用持有，调用方只能观察现状，不能把它伪装成终态。 */
    data class InProgress(
        val executionId: String,
        val state: ActionExecutionState,
    ) : ActionBusResult

    /** 动作已成功落终态，但输出无法编码到展示边界。 */
    data class OutputEncodingFailed(
        val executionId: String,
        val terminalState: ActionExecutionState,
        val error: ActionError,
    ) : ActionBusResult
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

    /** 只调用动作对账入口，绝不回退 execute。 */
    suspend fun reconcile(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
        remoteReference: String?,
        executionId: String,
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

    override suspend fun reconcile(
        registered: RegisteredAction<*, *>,
        input: JsonObject,
        context: ActionContext,
        remoteReference: String?,
        executionId: String,
    ): ActionInvocationResult = registered.invokeReconcile(input, context, remoteReference, executionId)
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
    private val executionCoordinator = ActionExecutionCoordinator(executionStore, clock)

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
        val validating = when (val start = executionCoordinator.begin(command)) {
            is ActionExecutionStart.New -> start.record
            is ActionExecutionStart.ExistingRunning -> return ActionBusResult.InProgress(
                executionId = start.record.command.executionId,
                state = start.record.state,
            )
            is ActionExecutionStart.ExistingTerminal -> return existingTerminal(start.record)
            is ActionExecutionStart.NeedsReconciliation -> return reconcileSerialized(start.record, context)
            is ActionExecutionStart.Conflict -> return ActionBusResult.Rejected(start.error)
        }
        val registered = when (val resolution = registry.resolve(command.actionId)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return persistFailure(validating, resolution.error)
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
                risk.effectiveRisk,
            )
            ActionRiskLevel.REVERSIBLE_WRITE -> executeReversible(
                registered,
                command,
                context,
                validating,
                risk.effectiveRisk,
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

    /** 等待同 execution 的已有对账后重新读取事实，确保远程 reconcile 最多一个在途。 */
    private suspend fun reconcileSerialized(
        observed: ActionExecutionRecord,
        context: ActionContext,
    ): ActionBusResult = executionCoordinator.serialized(observed.command.executionId) {
        val current = executionStore.find(observed.command.executionId)
            ?: return@serialized conflict("对账执行记录不存在")
        when {
            current.fingerprint != observed.fingerprint -> conflict("对账执行指纹冲突")
            current.needsReconciliation -> reconcile(current, context)
            current.isFinalTerminal -> existingTerminal(current)
            else -> ActionBusResult.InProgress(current.command.executionId, current.state)
        }
    }

    /** 按持久化未知结果携带的策略执行一次有界对账。 */
    private suspend fun reconcile(
        unknown: ActionExecutionRecord,
        context: ActionContext,
    ): ActionBusResult {
        val stored = unknown.result as? ActionResult.OutcomeUnknown
            ?: return protocolError("结果未知记录缺少 OutcomeUnknown 事实")
        return when (stored.reconciliationPolicy) {
            com.wzx.huitai.action.model.ReconciliationPolicy.MANUAL -> existingTerminal(unknown)
            com.wzx.huitai.action.model.ReconciliationPolicy.NONE -> protocolError("结果未知动作未配置对账策略")
            com.wzx.huitai.action.model.ReconciliationPolicy.QUERY_REMOTE -> reconcileRemotely(
                unknown,
                stored,
                context,
            )
        }
    }

    private suspend fun reconcileRemotely(
        unknown: ActionExecutionRecord,
        stored: ActionResult.OutcomeUnknown,
        context: ActionContext,
    ): ActionBusResult {
        when (val attempt = appendReconciliationAudit(unknown, "reconciliation_attempt")) {
            is ReconciliationAuditAppendResult.Appended -> Unit
            is ReconciliationAuditAppendResult.ExistingFinal -> return existingTerminal(attempt.record)
            is ReconciliationAuditAppendResult.Conflict -> return ActionBusResult.Rejected(attempt.error)
        }
        val registered = when (val resolution = registry.resolve(unknown.command.actionId)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return ActionBusResult.Rejected(resolution.error)
        }
        val invocation = try {
            actionInvoker.reconcile(
                registered,
                unknown.command.input,
                context,
                stored.remoteReference,
                unknown.command.executionId,
            )
        } catch (cancellation: CancellationException) {
            appendReconciliationResultBestEffort(unknown, "canceled")
            throw cancellation
        } catch (_: Exception) {
            when (val result = appendReconciliationAudit(unknown, "reconciliation_result", "error")) {
                is ReconciliationAuditAppendResult.Conflict -> return ActionBusResult.Rejected(result.error)
                is ReconciliationAuditAppendResult.ExistingFinal -> return existingTerminal(result.record)
                is ReconciliationAuditAppendResult.Appended -> Unit
            }
            return existingTerminal(unknown)
        }
        val reconciled = when (invocation) {
            is ActionInvocationResult.Reconciled -> {
                if (invocation.executionId != unknown.command.executionId) {
                    return reconciliationDiagnostic(
                        unknown,
                        outcome = "protocol_error",
                        result = protocolError("对账结果 executionId 不匹配"),
                    )
                }
                invocation.result
            }
            is ActionInvocationResult.Failure -> return reconciliationDiagnostic(
                unknown,
                outcome = "validation_error",
                result = existingTerminal(unknown),
            )
            else -> return reconciliationDiagnostic(
                unknown,
                outcome = "protocol_error",
                result = protocolError("对账返回了非法结果类型"),
            )
        }
        val result: ActionResult<JsonElement> = when (reconciled) {
            is ReconciliationResult.Succeeded -> ActionResult.Success(
                executionId = unknown.command.executionId,
                output = emptyPayload(),
                remoteReference = reconciled.remoteReference ?: stored.remoteReference,
            )
            is ReconciliationResult.Failed -> ActionResult.Failure(
                executionId = unknown.command.executionId,
                error = reconciled.error,
                remoteReference = stored.remoteReference,
            )
            ReconciliationResult.Unsupported,
            ReconciliationResult.Pending,
            ReconciliationResult.NotFound,
            is ReconciliationResult.Error,
            -> {
                val outcome = when (reconciled) {
                    ReconciliationResult.Unsupported -> "unsupported"
                    ReconciliationResult.Pending -> "pending"
                    ReconciliationResult.NotFound -> "not_found"
                    is ReconciliationResult.Error -> "error"
                    is ReconciliationResult.Succeeded,
                    is ReconciliationResult.Failed,
                    -> error("确认结果已在前置分支处理")
                }
                return when (val appended = appendReconciliationAudit(
                    unknown,
                    "reconciliation_result",
                    outcome,
                )) {
                    is ReconciliationAuditAppendResult.Appended -> existingTerminal(unknown)
                    is ReconciliationAuditAppendResult.ExistingFinal -> existingTerminal(appended.record)
                    is ReconciliationAuditAppendResult.Conflict -> ActionBusResult.Rejected(appended.error)
                }
            }
        }
        val completedAt = clock.now()
        val terminalState = result.terminalState() ?: return protocolError("对账结果不是最终态")
        val update = try {
            ReconciliationExecutionUpdate(
                executionId = unknown.command.executionId,
                expectedVersion = unknown.recordVersion,
                result = result,
                completedAt = completedAt,
                audit = auditDraft(
                    executionId = unknown.command.executionId,
                    fromState = ActionExecutionState.OUTCOME_UNKNOWN,
                    toState = terminalState,
                    type = "reconciliation_result",
                    payload = buildJsonObject { put("confirmed", true) },
                    actorId = null,
                    occurredAt = completedAt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return protocolError("对账结果关联错误")
        }
        return when (val updated = executionStore.updateReconciliation(update)) {
            is ReconciliationUpdateResult.Updated -> existingTerminal(updated.record)
            is ReconciliationUpdateResult.ExistingFinal -> existingTerminal(updated.record)
            is ReconciliationUpdateResult.Conflict -> ActionBusResult.Rejected(updated.error)
        }
    }

    /** 同版本未知记录上的审计追加由 store 原子校验，payload 只含稳定枚举。 */
    private suspend fun appendReconciliationAudit(
        unknown: ActionExecutionRecord,
        type: String,
        outcome: String? = null,
    ): ReconciliationAuditAppendResult {
        val now = clock.now()
        return executionStore.appendReconciliationAudit(
            ReconciliationAuditAppend(
                executionId = unknown.command.executionId,
                expectedVersion = unknown.recordVersion,
                audit = auditDraft(
                    executionId = unknown.command.executionId,
                    fromState = ActionExecutionState.OUTCOME_UNKNOWN,
                    toState = ActionExecutionState.OUTCOME_UNKNOWN,
                    type = type,
                    payload = buildJsonObject { outcome?.let { put("outcome", it) } },
                    actorId = null,
                    occurredAt = now,
                ),
            ),
        )
    }

    /** 真实取消以取消本身为主异常，审计故障不能覆盖取消语义。 */
    private suspend fun appendReconciliationResultBestEffort(
        unknown: ActionExecutionRecord,
        outcome: String,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeout(CANCELLATION_HANDOFF_TIMEOUT_MILLIS) {
                    appendReconciliationAudit(unknown, "reconciliation_result", outcome)
                }
            }
        } catch (_: Throwable) {
            // 调用方继续传播原 CancellationException，审计失败不伪装成业务结果。
        }
    }

    /** 在返回本地诊断前补齐对应的结果审计，审计冲突必须显式暴露。 */
    private suspend fun reconciliationDiagnostic(
        unknown: ActionExecutionRecord,
        outcome: String,
        result: ActionBusResult,
    ): ActionBusResult = when (val appended = appendReconciliationAudit(
        unknown,
        "reconciliation_result",
        outcome,
    )) {
        is ReconciliationAuditAppendResult.Appended -> result
        is ReconciliationAuditAppendResult.ExistingFinal -> existingTerminal(appended.record)
        is ReconciliationAuditAppendResult.Conflict -> ActionBusResult.Rejected(appended.error)
    }

    private suspend fun executeReversible(
        registered: RegisteredAction<*, *>,
        command: ActionCommand,
        context: ActionContext,
        validating: ActionExecutionRecord,
        effectiveRisk: ActionRiskLevel,
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
                effectiveRisk,
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
                risk.effectiveRisk,
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
        effectiveRisk: ActionRiskLevel,
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
                if (effectiveRisk == ActionRiskLevel.READ_ONLY) {
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
            return if (effectiveRisk == ActionRiskLevel.READ_ONLY) {
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
