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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    override suspend fun find(executionId: String): ActionExecutionRecord? = mutex.withLock { records[executionId] }

    /** 返回指定 execution 的不可修改审计快照，供测试和演示展示。 */
    suspend fun events(executionId: String): List<ActionAuditEvent> = mutex.withLock {
        Collections.unmodifiableList(auditEvents[executionId].orEmpty().toList())
    }

    /** 原子比较完整 binding，并且只允许第一次创建记录和首条审计。 */
    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult = mutex.withLock {
        require(audit.executionId == record.command.executionId) { "创建审计 executionId 不匹配" }
        val existing = records[record.command.executionId]
        if (existing == null) {
            val frozen = record.freeze()
            records[record.command.executionId] = frozen
            appendAudit(audit)
            return@withLock ExecutionCreateResult.Created(frozen)
        }
        if (existing.binding != record.binding) return@withLock createConflict("execution binding conflict")
        if (existing.isTerminal) ExecutionCreateResult.ExistingTerminal(existing)
        else ExecutionCreateResult.ExistingRunning(existing)
    }

    /** 原子乐观迁移；任何既有终态都原样获胜。 */
    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult = mutex.withLock {
        val existing = records[update.executionId]
            ?: return@withLock transitionConflict("execution not found")
        if (existing.isTerminal) return@withLock ExecutionTransitionResult.ExistingTerminal(existing)
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
        records[update.executionId] = updated
        appendAudit(update.audit)
        ExecutionTransitionResult.Updated(updated)
    }

    /** 仅由当前 claim token/version 将 OUTCOME_UNKNOWN 原子收束为最终成功或失败。 */
    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult = mutex.withLock {
        val existing = records[update.executionId]
            ?: return@withLock reconciliationConflict("execution not found")
        if (existing.isFinalTerminal) return@withLock ReconciliationUpdateResult.ExistingFinal(existing)
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
        records[update.executionId] = updated
        appendAudit(update.audit)
        ReconciliationUpdateResult.Updated(updated)
    }

    /** 原子取得或在到期边界接管对账租约，并一起追加 attempt 审计。 */
    override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult =
        mutex.withLock {
            val existing = records[request.executionId]
                ?: return@withLock claimConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationClaimResult.ExistingFinal(existing)
            existing.reconciliationClaim?.let { active ->
                if (request.now.isBefore(active.expiresAt)) {
                    return@withLock ReconciliationClaimResult.ExistingClaim(existing)
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
            records[request.executionId] = claimed
            appendAudit(request.audit)
            ReconciliationClaimResult.Claimed(claimed)
        }

    /** 仅当前 token/version owner 可以原子续租。 */
    override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult =
        mutex.withLock {
            val existing = records[request.executionId]
                ?: return@withLock renewConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationRenewResult.ExistingFinal(existing)
            val claim = existing.reconciliationClaim ?: return@withLock renewConflict("claim missing")
            if (claim.claimToken != request.claimToken) return@withLock ReconciliationRenewResult.ExistingClaim(existing)
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
            records[request.executionId] = renewed
            appendAudit(request.audit)
            ReconciliationRenewResult.Renewed(renewed)
        }

    /** 仅当前 token/version owner 可以释放未收束 claim。 */
    override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult =
        mutex.withLock {
            val existing = records[request.executionId]
                ?: return@withLock releaseConflict("execution not found")
            if (existing.isFinalTerminal) return@withLock ReconciliationReleaseResult.ExistingFinal(existing)
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
            records[request.executionId] = released
            appendAudit(request.audit)
            ReconciliationReleaseResult.Released(released)
        }

    /** 在锁内分配 execution 内严格递增序号并追加不可变事件。 */
    private fun appendAudit(draft: ActionAuditDraft) {
        val events = auditEvents.getOrPut(draft.executionId) { mutableListOf() }
        events += ActionAuditEvent(
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

    /** 对命令、预览和结果中的 JSON 做序列化边界复制，切断调用方可变 Map 引用。 */
    private fun ActionExecutionRecord.freeze(): ActionExecutionRecord = copy(
        command = command.copy(input = freezeJson(command.input) as JsonObject),
        result = result?.freeze(),
    )

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
        changes = changes.map { change ->
            change.copy(
                before = change.before?.let(::freezeJson),
                after = change.after?.let(::freezeJson),
            )
        },
        warnings = warnings.toList(),
    )

    /** 通过 JSON 编解码创建独立树，避免暴露输入树的任何可变容器。 */
    private fun freezeJson(value: JsonElement): JsonElement = JSON.parseToJsonElement(
        JSON.encodeToString(JsonElement.serializer(), value),
    )

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}

private fun error(message: String) = ActionError(ActionErrorCode.EXECUTION_CONFLICT, message)
private fun createConflict(message: String) = ExecutionCreateResult.Conflict(error(message))
private fun transitionConflict(message: String) = ExecutionTransitionResult.Conflict(error(message))
private fun reconciliationConflict(message: String) = ReconciliationUpdateResult.Conflict(error(message))
private fun claimConflict(message: String) = ReconciliationClaimResult.Conflict(error(message))
private fun renewConflict(message: String) = ReconciliationRenewResult.Conflict(error(message))
private fun releaseConflict(message: String) = ReconciliationReleaseResult.Conflict(error(message))
