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
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** 远程对账硬超时必须严格早于持久 claim 租约，避免 owner 仍运行时被其他进程接管。 */
internal object ReconciliationTimingPolicy {
    val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(15)
    val RECONCILIATION_TIMEOUT: Duration = Duration.ofSeconds(30)
    val CLAIM_LEASE: Duration = Duration.ofSeconds(60)

    init {
        require(!HEARTBEAT_INTERVAL.isZero && !HEARTBEAT_INTERVAL.isNegative) {
            "对账 heartbeat 间隔必须为正时长"
        }
        require(HEARTBEAT_INTERVAL < RECONCILIATION_TIMEOUT) {
            "对账 heartbeat 间隔必须早于远程对账超时"
        }
        require(RECONCILIATION_TIMEOUT < CLAIM_LEASE) { "远程对账超时必须早于 claim 租约" }
        require(CLAIM_LEASE.minus(RECONCILIATION_TIMEOUT) >= Duration.ofSeconds(10)) {
            "远程对账超时与 claim 租约之间必须保留安全余量"
        }
    }
}

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
        val remoteReference: String? = null,
    ) : ActionBusResult {
        override fun toString(): String =
            "ActionBusResult.OutputEncodingFailed(executionId=$executionId, terminalState=$terminalState, " +
                "errorCode=${error.code}, remoteReference=[REDACTED])"
    }

    /** 远程对账确认业务成功，但原动作输出无法恢复。 */
    data class SuccessWithoutOutput(
        val executionId: String,
        val remoteReference: String?,
        val source: String,
    ) : ActionBusResult {
        override fun toString(): String =
            "ActionBusResult.SuccessWithoutOutput(executionId=$executionId, " +
                "remoteReference=[REDACTED], source=$source)"
    }
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

/** 远程调用把普通异常收口为值，避免 async 子任务提前取消 heartbeat 协调 scope。 */
private sealed interface ReconciliationInvocationOutcome {
    data class Completed(val invocation: ActionInvocationResult) : ReconciliationInvocationOutcome
    data class Failed(val failure: Exception) : ReconciliationInvocationOutcome
}

/** heartbeat 循环只产生远程结果、内部超时或 fencing 三类确定出口。 */
private sealed interface ReconciliationLoopResult {
    data class Invocation(val outcome: ReconciliationInvocationOutcome) : ReconciliationLoopResult
    data class Fenced(val result: ActionBusResult) : ReconciliationLoopResult
    data object TimedOut : ReconciliationLoopResult
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
    lockScope: ActionExecutionLockScope = StripedActionExecutionLockScope(),
) {
    private val executionCoordinator = ActionExecutionCoordinator(executionStore, clock, lockScope)

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
        StripedActionExecutionLockScope(),
    )
    init {
        check(registry.isFrozen) { "动作注册表必须在创建 Bus 前冻结" }
    }

    /** 解析、校验并按有效风险执行一个动作命令。 */
    suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
        val registered = when (val resolution = registry.resolve(command.actionId, command.actionVersion)) {
            is ActionResolution.Found -> resolution.action
            is ActionResolution.NotFound -> return ActionBusResult.Rejected(resolution.error)
        }
        contextValidator.validate(registered.descriptor, command, context)?.let {
            return ActionBusResult.Rejected(it)
        }
        val validating = when (val start = executionCoordinator.begin(command)) {
            is ActionExecutionStart.New -> start.record
            is ActionExecutionStart.ExistingRunning -> return ActionBusResult.InProgress(
                executionId = start.record.command.executionId,
                state = start.record.state,
            )
            is ActionExecutionStart.ExistingTerminal -> return existingTerminal(start.record)
            is ActionExecutionStart.NeedsReconciliation -> return reconcile(start.record, context, registered)
            is ActionExecutionStart.Conflict -> return ActionBusResult.Rejected(start.error)
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

    /** 按持久化未知结果携带的策略执行一次有界对账。 */
    private suspend fun reconcile(
        unknown: ActionExecutionRecord,
        context: ActionContext,
        registered: RegisteredAction<*, *>,
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
                registered,
            )
        }
    }

    private suspend fun reconcileRemotely(
        unknown: ActionExecutionRecord,
        stored: ActionResult.OutcomeUnknown,
        context: ActionContext,
        registered: RegisteredAction<*, *>,
    ): ActionBusResult {
        val initialClaim = when (val claim = claimReconciliation(unknown)) {
            is ReconciliationClaimResult.Claimed -> claim.record
            is ReconciliationClaimResult.ExistingClaim -> return ActionBusResult.InProgress(
                claim.record.command.executionId,
                ActionExecutionState.OUTCOME_UNKNOWN,
            )
            is ReconciliationClaimResult.ExistingFinal -> return existingTerminal(claim.record)
            is ReconciliationClaimResult.Conflict -> return ActionBusResult.Rejected(claim.error)
        }
        return coroutineScope {
            val latestClaim = AtomicReference(initialClaim)
            val remote = async {
                try {
                    ReconciliationInvocationOutcome.Completed(
                        actionInvoker.reconcile(
                            registered,
                            initialClaim.command.input,
                            context,
                            stored.remoteReference,
                            initialClaim.command.executionId,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    ReconciliationInvocationOutcome.Failed(failure)
                }
            }
            try {
                val loopResult = withTimeoutOrNull(
                    ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT.toMillis(),
                ) {
                    var remainingMillis = ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT.toMillis()
                    while (true) {
                        val waitMillis = minOf(
                            ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis(),
                            remainingMillis,
                        )
                        val outcome = withTimeoutOrNull(
                            waitMillis,
                        ) {
                            remote.await()
                        }
                        if (outcome != null) {
                            return@withTimeoutOrNull ReconciliationLoopResult.Invocation(outcome)
                        }
                        remainingMillis -= waitMillis
                        if (remainingMillis <= 0) {
                            return@withTimeoutOrNull ReconciliationLoopResult.TimedOut
                        }
                        when (val renewed = renewReconciliation(latestClaim)) {
                            is ReconciliationRenewResult.Renewed -> Unit
                            is ReconciliationRenewResult.ExistingClaim -> {
                                return@withTimeoutOrNull ReconciliationLoopResult.Fenced(
                                    ActionBusResult.InProgress(
                                        renewed.record.command.executionId,
                                        ActionExecutionState.OUTCOME_UNKNOWN,
                                    ),
                                )
                            }
                            is ReconciliationRenewResult.ExistingFinal -> {
                                return@withTimeoutOrNull ReconciliationLoopResult.Fenced(
                                    existingTerminal(renewed.record),
                                )
                            }
                            is ReconciliationRenewResult.Conflict -> {
                                return@withTimeoutOrNull ReconciliationLoopResult.Fenced(
                                    ActionBusResult.Rejected(renewed.error),
                                )
                            }
                        }
                    }
                    error("heartbeat 循环必须通过显式分支退出")
                } ?: ReconciliationLoopResult.TimedOut

                val invocation = when (loopResult) {
                    is ReconciliationLoopResult.Invocation -> when (val outcome = loopResult.outcome) {
                        is ReconciliationInvocationOutcome.Completed -> outcome.invocation
                        is ReconciliationInvocationOutcome.Failed -> {
                            return@coroutineScope releaseReconciliation(
                                latestClaim.get(),
                                "error",
                                existingTerminal(unknown),
                            )
                        }
                    }
                    is ReconciliationLoopResult.Fenced -> {
                        remote.cancelAndJoin()
                        return@coroutineScope loopResult.result
                    }
                    ReconciliationLoopResult.TimedOut -> {
                        remote.cancelAndJoin()
                        return@coroutineScope withContext(NonCancellable) {
                            releaseReconciliation(latestClaim.get(), "timeout", existingTerminal(unknown))
                        }
                    }
                }
                val claimed = latestClaim.get()
                val reconciled = when (invocation) {
                    is ActionInvocationResult.Reconciled -> {
                        if (invocation.executionId != claimed.command.executionId) {
                            return@coroutineScope reconciliationDiagnostic(
                                claimed,
                                outcome = "protocol_error",
                                result = protocolError("对账结果 executionId 不匹配"),
                            )
                        }
                        invocation.result
                    }
                    is ActionInvocationResult.Failure -> return@coroutineScope reconciliationDiagnostic(
                        claimed,
                        outcome = "validation_error",
                        result = existingTerminal(unknown),
                    )
                    else -> return@coroutineScope reconciliationDiagnostic(
                        claimed,
                        outcome = "protocol_error",
                        result = protocolError("对账返回了非法结果类型"),
                    )
                }
                val result: ActionResult<JsonElement>?
                val successFact: ExecutionSuccessFact?
                when (reconciled) {
                    is ReconciliationResult.Succeeded -> {
                        result = null
                        successFact = ExecutionSuccessFact(
                            kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
                            remoteReference = reconciled.remoteReference ?: stored.remoteReference,
                            errorCode = null,
                            safeMessage = null,
                            source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
                        )
                    }
                    is ReconciliationResult.Failed -> {
                        result = ActionResult.Failure(
                            executionId = claimed.command.executionId,
                            error = reconciled.error,
                            remoteReference = stored.remoteReference,
                        )
                        successFact = null
                    }
                    is ReconciliationResult.Unsupported,
                    is ReconciliationResult.Pending,
                    is ReconciliationResult.NotFound,
                    is ReconciliationResult.Error,
                    -> {
                        val outcome = when (reconciled) {
                            is ReconciliationResult.Unsupported -> "unsupported"
                            is ReconciliationResult.Pending -> "pending"
                            is ReconciliationResult.NotFound -> "not_found"
                            is ReconciliationResult.Error -> "error"
                            is ReconciliationResult.Succeeded,
                            is ReconciliationResult.Failed,
                            -> error("确认结果已在前置分支处理")
                        }
                        return@coroutineScope releaseReconciliation(
                            claimed,
                            outcome,
                            existingTerminal(unknown),
                        )
                    }
                }
                val completedAt = clock.now()
                val terminalState = result?.terminalState() ?: ActionExecutionState.SUCCEEDED
                val update = try {
                    ReconciliationExecutionUpdate(
                        executionId = claimed.command.executionId,
                        expectedVersion = claimed.recordVersion,
                        claimToken = claimed.reconciliationClaim!!.claimToken,
                        result = result,
                        successFact = successFact,
                        completedAt = completedAt,
                        audit = auditDraft(
                            executionId = claimed.command.executionId,
                            fromState = ActionExecutionState.OUTCOME_UNKNOWN,
                            toState = terminalState,
                            type = "reconciliation_result",
                            payload = buildJsonObject { put("confirmed", true) },
                            actorId = null,
                            occurredAt = completedAt,
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    return@coroutineScope protocolError("对账结果关联错误")
                }
                when (val updated = executionStore.updateReconciliation(update)) {
                    is ReconciliationUpdateResult.Updated -> existingTerminal(updated.record)
                    is ReconciliationUpdateResult.ExistingFinal -> existingTerminal(updated.record)
                    is ReconciliationUpdateResult.Conflict -> ActionBusResult.Rejected(updated.error)
                }
            } catch (cancellation: CancellationException) {
                remote.cancel()
                releaseReconciliationAfterCancellation(remote, latestClaim.get(), cancellation)
                throw (cancellation.cause as? CancellationException ?: cancellation)
            }
        }
    }

    /** 原子取得跨进程唯一 claim，并将 attempt 审计和版本更新一起持久化。 */
    private suspend fun claimReconciliation(unknown: ActionExecutionRecord): ReconciliationClaimResult =
        executionCoordinator.claimReconciliation(unknown) { current ->
            val now = clock.now()
            val takeover = current.reconciliationClaim?.let { !now.isBefore(it.expiresAt) } == true
            ReconciliationClaimRequest(
                executionId = current.command.executionId,
                expectedVersion = current.recordVersion,
                claimToken = UUID.randomUUID().toString(),
                ownerId = reconciliationOwnerId(current.command),
                now = now,
                leaseDuration = ReconciliationTimingPolicy.CLAIM_LEASE,
                audit = auditDraft(
                    current.command.executionId,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    "reconciliation_attempt",
                    buildJsonObject { if (takeover) put("takeover", true) },
                    null,
                    now,
                ),
            )
        }

    /** 使用当前最新 token/version 原子续租；即使外部取消到达也先取得确定持久化结果。 */
    private suspend fun renewReconciliation(
        latestClaim: AtomicReference<ActionExecutionRecord>,
    ): ReconciliationRenewResult = withContext(NonCancellable) {
        val claimed = latestClaim.get()
        val now = clock.now()
        val token = claimed.reconciliationClaim?.claimToken
            ?: return@withContext ReconciliationRenewResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "对账 renew claim 缺失"),
            )
        val renewed = executionStore.renewReconciliation(
            ReconciliationRenewRequest(
                executionId = claimed.command.executionId,
                expectedVersion = claimed.recordVersion,
                claimToken = token,
                now = now,
                leaseDuration = ReconciliationTimingPolicy.CLAIM_LEASE,
                audit = auditDraft(
                    claimed.command.executionId,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    "reconciliation_claim_renewed",
                    buildJsonObject { },
                    null,
                    now,
                ),
            ),
        )
        if (renewed is ReconciliationRenewResult.Renewed) {
            latestClaim.set(renewed.record)
        }
        renewed
    }

    /** 取消必须传播；远程停止或 release 失败作为 suppressed 保留，续租租约继续承担恢复边界。 */
    private suspend fun releaseReconciliationAfterCancellation(
        remote: kotlinx.coroutines.Deferred<*>,
        claimed: ActionExecutionRecord,
        cancellation: CancellationException,
    ) {
        val handoffFailure = try {
            withContext(NonCancellable) {
                withTimeout(CANCELLATION_HANDOFF_TIMEOUT_MILLIS) {
                    remote.cancelAndJoin()
                    val released = releaseReconciliation(claimed, "canceled", existingTerminal(claimed))
                    if (released is ActionBusResult.Rejected) {
                        throw IllegalStateException("对账取消释放失败: ${released.error.code}")
                    }
                }
            }
            null
        } catch (failure: Throwable) {
            failure
        }
        if (handoffFailure != null && handoffFailure !== cancellation) {
            val visited = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>(),
            )
            var current: Throwable? = cancellation
            while (current != null && visited.add(current)) {
                if (current !== handoffFailure) current.addSuppressed(handoffFailure)
                current = current.cause
            }
        }
    }

    /** 在返回本地诊断前补齐对应的结果审计，审计冲突必须显式暴露。 */
    private suspend fun reconciliationDiagnostic(
        claimed: ActionExecutionRecord,
        outcome: String,
        result: ActionBusResult,
    ): ActionBusResult = releaseReconciliation(claimed, outcome, result)

    /** 原子释放未收束 claim，并在同事务提交 result 审计。 */
    private suspend fun releaseReconciliation(
        claimed: ActionExecutionRecord,
        outcome: String,
        result: ActionBusResult,
    ): ActionBusResult {
        val now = clock.now()
        val token = claimed.reconciliationClaim?.claimToken
            ?: return conflict("对账 claim 缺失")
        return when (val released = executionStore.releaseReconciliation(
            ReconciliationReleaseRequest(
                executionId = claimed.command.executionId,
                expectedVersion = claimed.recordVersion,
                claimToken = token,
                releasedAt = now,
                audit = auditDraft(
                    claimed.command.executionId,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    "reconciliation_result",
                    buildJsonObject { put("outcome", outcome) },
                    null,
                    now,
                ),
            ),
        )) {
            is ReconciliationReleaseResult.Released -> result
            is ReconciliationReleaseResult.ExistingFinal -> existingTerminal(released.record)
            is ReconciliationReleaseResult.Conflict -> ActionBusResult.Rejected(released.error)
        }
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
            errorCode = invocation.error.code,
            safeMessage = invocation.error.message,
            source = ExecutionSuccessFact.SOURCE_OUTPUT_ENCODING,
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
            is ExecutionTransitionResult.Updated -> existingTerminal(updated.record)
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
    record.successFact?.kind == ExecutionSuccessFact.OUTPUT_ENCODING_FAILED -> {
        val fact = record.successFact
        ActionBusResult.OutputEncodingFailed(
            executionId = record.command.executionId,
            terminalState = record.state,
            error = ActionError(
                fact.errorCode ?: ActionErrorCode.PROTOCOL_ERROR,
                fact.safeMessage ?: "动作输出不可用",
            ),
            remoteReference = fact.remoteReference,
        )
    }
    record.successFact?.kind == ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS -> ActionBusResult.SuccessWithoutOutput(
        executionId = record.command.executionId,
        remoteReference = record.successFact.remoteReference,
        source = record.successFact.source,
    )
    else -> conflict("终态记录缺少事实")
}

private fun emptyPayload(): JsonObject = buildJsonObject { }

/** 从冻结桌面实例与 session 派生稳定 owner，持久层不保存原始桌面标识。 */
private fun reconciliationOwnerId(command: ActionCommand): String {
    val identity = command.identityScope
    val bytes = MessageDigest.getInstance("SHA-256").digest(
        "${identity.desktopInstanceId}\n${identity.desktopSessionId}".toByteArray(StandardCharsets.UTF_8),
    )
    return "desktop-session:${bytes.joinToString("") { "%02x".format(it) }.take(32)}"
}

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
