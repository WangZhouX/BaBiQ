package com.wzx.huitai.security.execution

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationClaim
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationProvenance
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.security.audit.AuditRedactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/**
 * 测试和框架演示使用的原子内存执行存储。
 *
 * 所有记录与审计序号在同一 [Mutex] 中比较和提交，调用方只能得到不可变快照，
 * 不暴露内部可变 Map/List。持久 SQLite 适配器将在后续任务替换本实现。
 */
class InMemoryActionExecutionStore(
    private val redactor: AuditRedactor = AuditRedactor(),
) : ActionExecutionStore {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, ActionExecutionRecord>()
    private val auditEvents = mutableMapOf<String, MutableList<ActionAuditEvent>>()

    /** 查询当前不可变记录快照。 */
    override suspend fun find(executionId: String): ActionExecutionRecord? = mutex.withLock {
        records[executionId]?.snapshot()
    }

    /** 返回指定 execution 的不可修改审计快照，供测试和演示展示。 */
    suspend fun events(executionId: String): List<ActionAuditEvent> = mutex.withLock {
        Collections.unmodifiableList(auditEvents[executionId].orEmpty().toList())
    }

    /** 原子比较完整 binding，并且只允许第一次创建记录和首条审计。 */
    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult = mutex.withLock {
        validateCreateAudit(record, audit)
        val existing = records[record.command.executionId]
        if (existing == null) {
            val frozen = record.freeze()
            val event = prepareAudit(audit)
            commit(frozen, event)
            return@withLock ExecutionCreateResult.Created(frozen.snapshot())
        }
        if (existing.binding != record.binding) return@withLock createConflict("execution binding conflict")
        if (existing.isTerminal) ExecutionCreateResult.ExistingTerminal(existing.snapshot())
        else ExecutionCreateResult.ExistingRunning(existing.snapshot())
    }

    /** 原子乐观迁移；任何既有终态都原样获胜。 */
    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult = mutex.withLock {
        validateTransitionAudit(update)
        val existing = records[update.executionId]
            ?: return@withLock transitionConflict("execution not found")
        if (existing.isTerminal) return@withLock ExecutionTransitionResult.ExistingTerminal(existing.snapshot())
        if (existing.recordVersion != update.expectedVersion || update.audit.fromState != existing.state) {
            return@withLock transitionConflict("transition state or version conflict")
        }
        val updated = existing.copy(
            state = update.state,
            result = update.result?.freeze(),
            successFact = update.successFact,
            startedAt = update.startedAt ?: existing.startedAt,
            completedAt = update.completedAt,
            updatedAt = update.updatedAt,
            recordVersion = existing.recordVersion + 1,
        )
        val event = prepareAudit(update.audit)
        commit(updated, event)
        ExecutionTransitionResult.Updated(updated.snapshot())
    }

    /** 仅由当前 claim token/version 将 OUTCOME_UNKNOWN 原子收束为最终成功或失败。 */
    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult = mutex.withLock {
        validateReconciliationAudit(update)
        val existing = records[update.executionId]
            ?: return@withLock reconciliationConflict("execution not found")
        if (existing.isFinalTerminal) return@withLock ReconciliationUpdateResult.ExistingFinal(existing.snapshot())
        if (!existing.needsReconciliation || existing.recordVersion != update.expectedVersion ||
            existing.reconciliationClaim?.claimToken != update.claimToken
        ) {
            return@withLock reconciliationConflict("reconciliation state, version or owner conflict")
        }
        val state = if (update.result is ActionResult.Failure) {
            ActionExecutionState.FAILED
        } else {
            ActionExecutionState.SUCCEEDED
        }
        val updated = existing.copy(
            state = state,
            result = update.result?.freeze(),
            successFact = update.successFact,
            completedAt = update.completedAt,
            updatedAt = update.completedAt,
            recordVersion = existing.recordVersion + 1,
            reconciliation = ReconciliationProvenance(existing.recordVersion, update.completedAt),
            reconciliationClaim = null,
        )
        val event = prepareAudit(update.audit)
        commit(updated, event)
        ReconciliationUpdateResult.Updated(updated.snapshot())
    }

    /** 原子取得或在到期边界接管对账租约，并一起追加 attempt 审计。 */
    override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult =
        mutex.withLock {
            validateAuditTime(request.audit, request.now, "claim")
            val existing = records[request.executionId]
                ?: return@withLock claimConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationClaimResult.ExistingFinal(existing.snapshot())
            existing.reconciliationClaim?.let { active ->
                if (request.now.isBefore(active.expiresAt)) {
                    return@withLock ReconciliationClaimResult.ExistingClaim(existing.snapshot())
                }
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return@withLock claimConflict("reconciliation claim state or version conflict")
            }
            val claimed = existing.copy(
                updatedAt = request.now,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = ReconciliationClaim(
                    request.claimToken,
                    request.ownerId,
                    request.now,
                    request.expiresAt,
                ),
            )
            val event = prepareAudit(request.audit)
            commit(claimed, event)
            ReconciliationClaimResult.Claimed(claimed.snapshot())
        }

    /** 仅当前 token/version owner 可以原子续租。 */
    override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult =
        mutex.withLock {
            validateAuditTime(request.audit, request.now, "renew")
            val existing = records[request.executionId]
                ?: return@withLock renewConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationRenewResult.ExistingFinal(existing.snapshot())
            val claim = existing.reconciliationClaim ?: return@withLock renewConflict("claim missing")
            if (claim.claimToken != request.claimToken) {
                return@withLock ReconciliationRenewResult.ExistingClaim(existing.snapshot())
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return@withLock renewConflict("reconciliation renew state or version conflict")
            }
            val renewed = try {
                existing.copy(
                    updatedAt = request.now,
                    recordVersion = existing.recordVersion + 1,
                    reconciliationClaim = claim.copy(expiresAt = request.expiresAt),
                )
            } catch (_: IllegalArgumentException) {
                return@withLock renewConflict("reconciliation renew time conflict")
            }
            val event = prepareAudit(request.audit)
            commit(renewed, event)
            ReconciliationRenewResult.Renewed(renewed.snapshot())
        }

    /** 仅当前 token/version owner 可以释放未收束 claim。 */
    override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult =
        mutex.withLock {
            validateAuditTime(request.audit, request.releasedAt, "release")
            val existing = records[request.executionId]
                ?: return@withLock releaseConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationReleaseResult.ExistingFinal(existing.snapshot())
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion ||
                existing.reconciliationClaim?.claimToken != request.claimToken
            ) {
                return@withLock releaseConflict("reconciliation release state, version or owner conflict")
            }
            val released = existing.copy(
                updatedAt = request.releasedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = null,
            )
            val event = prepareAudit(request.audit)
            commit(released, event)
            ReconciliationReleaseResult.Released(released.snapshot())
        }

    /** 在不修改 backing state 的前提下完整构造脱敏审计事件。 */
    private fun prepareAudit(draft: ActionAuditDraft): ActionAuditEvent {
        val events = auditEvents[draft.executionId].orEmpty()
        return ActionAuditEvent(
            executionId = draft.executionId,
            sequence = (events.lastOrNull()?.sequence ?: 0L) + 1,
            fromState = draft.fromState,
            toState = draft.toState,
            type = draft.type,
            redactedPayload = redactor.redact(draft.redactedPayload),
            actorId = draft.actorId,
            occurredAt = draft.occurredAt,
        )
    }

    /** 新记录和已构造事件在同一 Mutex 临界区内一次提交。 */
    private fun commit(record: ActionExecutionRecord, event: ActionAuditEvent) {
        records[record.command.executionId] = record
        auditEvents.getOrPut(event.executionId) { mutableListOf() }.add(event)
    }

    /** 创建记录、状态和首条审计必须描述同一个原子业务事实。 */
    private fun validateCreateAudit(record: ActionExecutionRecord, audit: ActionAuditDraft) {
        require(audit.executionId == record.command.executionId) { "创建审计 executionId 不匹配" }
        require(audit.fromState == ActionExecutionState.RECEIVED) { "创建审计必须源自 RECEIVED" }
        require(audit.toState == record.state) { "创建审计目标状态与记录不匹配" }
        require(record.createdAt == record.updatedAt) { "创建记录的 createdAt 与 updatedAt 必须一致" }
        require(audit.occurredAt == record.createdAt) { "创建审计时间与记录创建时间不匹配" }
    }

    /** 普通迁移的目标状态、事件时间和终态完成时间必须一致。 */
    private fun validateTransitionAudit(update: ExecutionTransition) {
        require(update.audit.toState == update.state) { "迁移审计目标状态与更新不匹配" }
        validateAuditTime(update.audit, update.updatedAt, "迁移")
        if (update.state.isPersistedTerminal()) {
            require(update.completedAt == update.updatedAt) { "终态 completedAt 与 updatedAt 必须一致" }
        }
    }

    /** 最终对账的审计状态和时间必须与最终业务更新完全一致。 */
    private fun validateReconciliationAudit(update: ReconciliationExecutionUpdate) {
        val state = if (update.result is ActionResult.Failure) {
            ActionExecutionState.FAILED
        } else {
            ActionExecutionState.SUCCEEDED
        }
        require(update.audit.toState == state) { "最终对账审计目标状态与结果不匹配" }
        validateAuditTime(update.audit, update.completedAt, "最终对账")
    }

    /** 审计发生时间必须使用同一原子请求携带的业务时间。 */
    private fun validateAuditTime(audit: ActionAuditDraft, businessTime: java.time.Instant, operation: String) {
        require(audit.occurredAt == businessTime) { "$operation 审计时间与业务时间不匹配" }
    }

    /** 对命令、预览和结果中的 JSON 做序列化边界复制，切断调用方可变 Map 引用。 */
    private fun ActionExecutionRecord.freeze(): ActionExecutionRecord = copy(
        command = command.copy(input = freezeJson(command.input) as JsonObject),
        result = result?.freeze(),
    )

    /** 每次越过公开端口边界都重新深冻结，调用方永不持有内部记录对象。 */
    private fun ActionExecutionRecord.snapshot(): ActionExecutionRecord = freeze()

    /** 穷举复制所有结果分支，避免终态输出继续持有外部 JSON 容器。 */
    @Suppress("UNCHECKED_CAST")
    private fun ActionResult<JsonElement>.freeze(): ActionResult<JsonElement> = when (this) {
        is ActionResult.Preview -> copy(preview = preview.freeze())
        is ActionResult.ApprovalRequired -> copy(preview = preview.freeze())
        is ActionResult.Success<*> -> ActionResult.Success(
            executionId = executionId,
            output = freezeJson(output as JsonElement),
            redactedOutput = redactedOutput?.let { freezeJson(it as JsonElement) },
            remoteReference = remoteReference,
        )
        is ActionResult.Failure -> copy(error = error.copy(details = error.details?.let(::freezeJson) as JsonObject?))
        is ActionResult.Canceled -> copy()
        is ActionResult.Expired -> copy()
        is ActionResult.OutcomeUnknown -> copy(error = error.copy(details = error.details?.let(::freezeJson) as JsonObject?))
    }

    /** 复制预览的输入、变化值和警告列表。 */
    private fun com.wzx.huitai.action.model.ActionPreview.freeze() = copy(
        redactedInput = freezeJson(redactedInput) as JsonObject,
        changes = Collections.unmodifiableList(changes.map { change ->
            change.copy(
                before = change.before?.let(::freezeJson),
                after = change.after?.let(::freezeJson),
            )
        }),
        warnings = Collections.unmodifiableList(warnings.toList()),
    )

    /** 递归复制 JSON，并用 JVM 不可修改容器封住 entries/iterator 等旁路。 */
    private fun freezeJson(value: JsonElement): JsonElement = when (value) {
        JsonNull -> JsonNull
        is JsonPrimitive -> value
        is JsonObject -> JsonObject(Collections.unmodifiableMap(
            value.entries.associateTo(linkedMapOf()) { (key, child) -> key to freezeJson(child) },
        ))
        is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::freezeJson)))
    }
}

/** Store 防御校验使用的持久终态集合。 */
private fun ActionExecutionState.isPersistedTerminal(): Boolean = this in setOf(
    ActionExecutionState.SUCCEEDED,
    ActionExecutionState.FAILED,
    ActionExecutionState.CANCELED,
    ActionExecutionState.EXPIRED,
    ActionExecutionState.OUTCOME_UNKNOWN,
)

/** 构造不包含底层异常或业务值的稳定执行冲突。 */
private fun error(message: String) = ActionError(ActionErrorCode.EXECUTION_CONFLICT, message)

/** 将稳定冲突映射为创建结果。 */
private fun createConflict(message: String) = ExecutionCreateResult.Conflict(error(message))

/** 将稳定冲突映射为状态迁移结果。 */
private fun transitionConflict(message: String) = ExecutionTransitionResult.Conflict(error(message))

/** 将稳定冲突映射为最终对账结果。 */
private fun reconciliationConflict(message: String) = ReconciliationUpdateResult.Conflict(error(message))

/** 将稳定冲突映射为 claim 结果。 */
private fun claimConflict(message: String) = ReconciliationClaimResult.Conflict(error(message))

/** 将稳定冲突映射为续租结果。 */
private fun renewConflict(message: String) = ReconciliationRenewResult.Conflict(error(message))

/** 将稳定冲突映射为释放结果。 */
private fun releaseConflict(message: String) = ReconciliationReleaseResult.Conflict(error(message))
