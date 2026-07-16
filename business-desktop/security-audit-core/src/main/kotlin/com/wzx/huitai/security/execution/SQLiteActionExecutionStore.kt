package com.wzx.huitai.security.execution

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionReplayHydrator
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
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
import com.wzx.huitai.action.port.ReplayHydrationResult
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.security.audit.AuditRedactor
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 动作描述符中需要随执行记录冻结的重放和对账策略。 */
data class ActionExecutionPolicies(
    val replayPolicy: ActionReplayPolicy,
    val reconciliationPolicy: ReconciliationPolicy,
)

/**
 * 在创建持久 execution 时解析描述符策略，避免存储层根据原始输入猜测安全行为。
 *
 * 调用方应从已经注册且校验过的动作描述符提供策略；默认实现选择最保守的禁止重放和人工对账。
 */
fun interface ActionExecutionPolicyResolver {
    fun resolve(record: ActionExecutionRecord): ActionExecutionPolicies
}

/**
 * 基于 SQLite 的业务桌面动作执行存储。
 *
 * 数据库是所有状态、版本、scope 和 claim 租约的唯一事实来源。原始命令输入、未脱敏输出以及
 * claim token/owner 只保留在当前适配器实例的短期缓存中，重启后只能得到可安全重放的脱敏表示。
 */
class SQLiteActionExecutionStore(
    private val database: BusinessDesktopDatabase,
    private val redactor: AuditRedactor = AuditRedactor(),
    private val policyResolver: ActionExecutionPolicyResolver = ActionExecutionPolicyResolver {
        ActionExecutionPolicies(ActionReplayPolicy.NEVER, ReconciliationPolicy.MANUAL)
    },
) : ActionExecutionStore, ScopedActionExecutionQuery, ActionExecutionReplayHydrator {
    private val exactRecords = ConcurrentHashMap<String, ActionExecutionRecord>()
    private val localClaims = ConcurrentHashMap<String, LocalClaim>()
    private val persistencePayloadRedactor = AuditRedactor(
        sensitiveFieldIds = setOf("ownerId", "claimOwner", "claimToken", "actorId"),
    )

    /** 按 executionId 读取数据库事实，并仅在版本和 binding 一致时叠加本实例精确值。 */
    override suspend fun find(executionId: String): ActionExecutionRecord? = database.read { connection ->
        selectRecord(connection, FIND_BY_ID, listOf(executionId))?.withLocalExactValues()
    }

    /** SQLite 先比较持久完整 binding，匹配后才缓存并返回候选命令 input。 */
    override suspend fun findAndHydrateReplayCandidate(
        candidateCommand: ActionCommand,
        expectedBinding: ExecutionBinding,
    ): ReplayHydrationResult = database.read { connection ->
        val durable = selectRecord(connection, FIND_BY_ID, listOf(candidateCommand.executionId))
            ?: return@read ReplayHydrationResult.Missing
        if (durable.binding != expectedBinding) {
            ReplayHydrationResult.BindingMismatch(durable.snapshot())
        } else {
            cacheReplayInput(durable, durable.copy(command = candidateCommand))
            ReplayHydrationResult.Matching(durable.withLocalExactValues())
        }
    }

    /** 使用 executionId 与完整七维身份范围做单条查询，不暴露其他会话是否存在。 */
    override suspend fun find(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionExecutionRecord? = database.read { connection ->
        selectRecord(connection, FIND_BY_ID_AND_SCOPE, listOf(executionId) + identityScope.arguments())
            ?.withLocalExactValues()
    }

    /** 按创建时间和 executionId 稳定列出当前完整身份范围内的非终态记录。 */
    override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> =
        database.read { connection ->
            connection.prepareStatement(LIST_NON_TERMINAL_BY_SCOPE).use { statement ->
                bind(statement, identityScope.arguments())
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.toRecord().withLocalExactValues())
                    }
                }
            }
        }

    /** 返回 execution 内只追加事件的有序脱敏快照。 */
    suspend fun events(executionId: String): List<ActionAuditEvent> = database.read { connection ->
        connection.prepareStatement(SELECT_EVENTS).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows ->
                Collections.unmodifiableList(buildList {
                    while (rows.next()) {
                        add(
                            ActionAuditEvent(
                                executionId = rows.getString("execution_id"),
                                sequence = rows.getLong("event_sequence"),
                                fromState = rows.getString("from_status")?.let(ActionExecutionState::valueOf),
                                toState = ActionExecutionState.valueOf(rows.getString("to_status")),
                                type = rows.getString("event_type"),
                                redactedPayload = JSON.parseToJsonElement(rows.getString("payload_json_redacted")).jsonObject,
                                actorId = rows.getString("actor_id"),
                                occurredAt = Instant.parse(rows.getString("occurred_at")),
                            ),
                        )
                    }
                })
            }
        }
    }

    /** 在 `BEGIN IMMEDIATE` 中比较完整 binding，只提交首条 execution 与首条事件。 */
    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult {
        validateCreateAudit(record, audit)
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(record.command.executionId))
            if (existing != null) {
                return@write when {
                    existing.binding != record.binding -> createConflict("execution binding conflict")
                    existing.isTerminal -> ExecutionCreateResult.ExistingTerminal(existing)
                    else -> ExecutionCreateResult.ExistingRunning(existing)
                }
            }
            val policies = policyResolver.resolve(record)
            val safeEnvelope = encodeEnvelope(record, policies.reconciliationPolicy, claim = null)
            val safeAudit = prepareAudit(connection, audit)
            insertRecord(connection, record, policies, safeEnvelope, audit)
            insertEvent(connection, safeAudit)
            ExecutionCreateResult.Created(record)
        }
        return when (result) {
            is ExecutionCreateResult.Created -> {
                cacheExact(result.record)
                ExecutionCreateResult.Created(result.record.snapshot())
            }
            is ExecutionCreateResult.ExistingRunning -> {
                cacheReplayInput(result.record, record)
                ExecutionCreateResult.ExistingRunning(result.record.withLocalExactValues())
            }
            is ExecutionCreateResult.ExistingTerminal -> {
                cacheReplayInput(result.record, record)
                ExecutionCreateResult.ExistingTerminal(result.record.withLocalExactValues())
            }
            is ExecutionCreateResult.Conflict -> result
        }
    }

    /** 乐观迁移记录和审计事件；既有终态仅追加迟到响应证据，不覆盖首个终态。 */
    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
        validateTransitionAudit(update)
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(update.executionId))
                ?: return@write transitionConflict("execution not found")
            if (existing.isTerminal) {
                appendLateTerminalEvent(connection, existing, update)
                return@write ExecutionTransitionResult.ExistingTerminal(existing)
            }
            if (existing.recordVersion != update.expectedVersion || update.audit.fromState != existing.state) {
                return@write transitionConflict("transition state or version conflict")
            }
            val updated = existing.copy(
                state = update.state,
                result = update.result?.snapshot(),
                successFact = update.successFact,
                startedAt = update.startedAt ?: existing.startedAt,
                completedAt = update.completedAt,
                updatedAt = update.updatedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = null,
            )
            val policy = selectPolicy(connection, update.executionId)
            val envelope = encodeEnvelope(updated, policy, claim = null)
            val event = prepareAudit(connection, update.audit)
            updateRecord(connection, updated, envelope, policy, existing.recordVersion)
            insertEvent(connection, event)
            ExecutionTransitionResult.Updated(updated)
        }
        return when (result) {
            is ExecutionTransitionResult.Updated -> {
                val exact = mergeExactMutation(result.record, update.result)
                cacheExact(exact)
                ExecutionTransitionResult.Updated(exact.snapshot())
            }
            is ExecutionTransitionResult.ExistingTerminal ->
                ExecutionTransitionResult.ExistingTerminal(result.record.withLocalExactValues())
            is ExecutionTransitionResult.Conflict -> result
        }
    }

    /** 仅允许本实例当前 claim owner 把 OUTCOME_UNKNOWN 收束为最终成功或失败。 */
    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult {
        validateReconciliationAudit(update)
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(update.executionId))
                ?: return@write reconciliationConflict("execution not found")
            if (existing.isFinalTerminal) return@write ReconciliationUpdateResult.ExistingFinal(existing)
            val claim = readClaimEnvelope(connection, update.executionId)
            if (!existing.needsReconciliation || existing.recordVersion != update.expectedVersion ||
                claim == null || !ownsClaim(update.executionId, update.claimToken, claim)
            ) {
                return@write reconciliationConflict("reconciliation state, version or owner conflict")
            }
            val state = if (update.result is ActionResult.Failure) ActionExecutionState.FAILED else ActionExecutionState.SUCCEEDED
            val updated = existing.copy(
                state = state,
                result = update.result?.snapshot(),
                successFact = update.successFact,
                completedAt = update.completedAt,
                updatedAt = update.completedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliation = ReconciliationProvenance(existing.recordVersion, update.completedAt),
                reconciliationClaim = null,
            )
            val policy = selectPolicy(connection, update.executionId)
            val envelope = encodeEnvelope(updated, policy, claim = null)
            val event = prepareAudit(connection, update.audit)
            updateRecord(connection, updated, envelope, policy, existing.recordVersion, reconciliationSucceeded = true)
            insertEvent(connection, event)
            ReconciliationUpdateResult.Updated(updated)
        }
        return when (result) {
            is ReconciliationUpdateResult.Updated -> {
                localClaims.remove(update.executionId)
                val exact = mergeExactMutation(result.record, update.result)
                cacheExact(exact)
                ReconciliationUpdateResult.Updated(exact.snapshot())
            }
            is ReconciliationUpdateResult.ExistingFinal ->
                ReconciliationUpdateResult.ExistingFinal(result.record.withLocalExactValues())
            is ReconciliationUpdateResult.Conflict -> result
        }
    }

    /** 原子取得或在租约到期边界接管对账权，并持久化摘要而非原始 token/owner。 */
    override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult {
        validateAuditTime(request.audit, request.now, "claim")
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(request.executionId))
                ?: return@write claimConflict("execution not found")
            if (existing.isFinalTerminal) return@write ReconciliationClaimResult.ExistingFinal(existing)
            val activeClaim = readClaimEnvelope(connection, request.executionId)
            if (activeClaim != null && request.now.isBefore(activeClaim.expiresAt)) {
                return@write ReconciliationClaimResult.ExistingClaim(existing.copy(reconciliationClaim = renderClaim(request.executionId, activeClaim)))
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return@write claimConflict("reconciliation claim state or version conflict")
            }
            val claim = ClaimEnvelope(
                tokenDigest = digest(request.executionId, request.claimToken),
                ownerDigest = digest(request.executionId, request.ownerId),
                claimedAt = request.now,
                expiresAt = request.expiresAt,
            )
            val claimed = existing.copy(
                updatedAt = request.now,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = ReconciliationClaim(request.claimToken, request.ownerId, request.now, request.expiresAt),
            )
            val policy = selectPolicy(connection, request.executionId)
            val envelope = encodeEnvelope(claimed, policy, claim)
            val event = prepareAudit(connection, request.audit)
            updateRecord(connection, claimed, envelope, policy, existing.recordVersion, claimMutation = ClaimMutation.CLAIM)
            insertEvent(connection, event)
            ReconciliationClaimResult.Claimed(claimed)
        }
        return when (result) {
            is ReconciliationClaimResult.Claimed -> {
                localClaims[result.record.command.executionId] = LocalClaim(
                    request.claimToken,
                    request.ownerId,
                    digest(request.executionId, request.claimToken),
                    digest(request.executionId, request.ownerId),
                )
                cacheExact(result.record)
                ReconciliationClaimResult.Claimed(result.record.snapshot())
            }
            is ReconciliationClaimResult.ExistingClaim ->
                ReconciliationClaimResult.ExistingClaim(result.record.withLocalExactValues())
            is ReconciliationClaimResult.ExistingFinal ->
                ReconciliationClaimResult.ExistingFinal(result.record.withLocalExactValues())
            is ReconciliationClaimResult.Conflict -> result
        }
    }

    /** 只有当前实例持有的原始 token 可以续租，摘要相等不会跨进程转移权限。 */
    override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult {
        validateAuditTime(request.audit, request.now, "renew")
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(request.executionId))
                ?: return@write renewConflict("execution not found")
            if (existing.isFinalTerminal) return@write ReconciliationRenewResult.ExistingFinal(existing)
            val claim = readClaimEnvelope(connection, request.executionId)
                ?: return@write renewConflict("claim missing")
            if (!ownsClaim(request.executionId, request.claimToken, claim)) {
                return@write ReconciliationRenewResult.ExistingClaim(existing.copy(reconciliationClaim = renderClaim(request.executionId, claim)))
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return@write renewConflict("reconciliation renew state or version conflict")
            }
            val renewedClaim = claim.copy(expiresAt = request.expiresAt)
            val raw = localClaims.getValue(request.executionId)
            val renewed = try {
                existing.copy(
                    updatedAt = request.now,
                    recordVersion = existing.recordVersion + 1,
                    reconciliationClaim = ReconciliationClaim(raw.token, raw.owner, claim.claimedAt, request.expiresAt),
                )
            } catch (_: IllegalArgumentException) {
                return@write renewConflict("reconciliation renew time conflict")
            }
            val policy = selectPolicy(connection, request.executionId)
            val envelope = encodeEnvelope(renewed, policy, renewedClaim)
            val event = prepareAudit(connection, request.audit)
            updateRecord(connection, renewed, envelope, policy, existing.recordVersion, claimMutation = ClaimMutation.RENEW)
            insertEvent(connection, event)
            ReconciliationRenewResult.Renewed(renewed)
        }
        return when (result) {
            is ReconciliationRenewResult.Renewed -> {
                cacheExact(result.record)
                ReconciliationRenewResult.Renewed(result.record.snapshot())
            }
            is ReconciliationRenewResult.ExistingClaim ->
                ReconciliationRenewResult.ExistingClaim(result.record.withLocalExactValues())
            is ReconciliationRenewResult.ExistingFinal ->
                ReconciliationRenewResult.ExistingFinal(result.record.withLocalExactValues())
            is ReconciliationRenewResult.Conflict -> result
        }
    }

    /** 只有当前实例 claim owner 可以释放租约，记录仍保持 OUTCOME_UNKNOWN。 */
    override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult {
        validateAuditTime(request.audit, request.releasedAt, "release")
        val result = database.write { connection ->
            val existing = selectRecord(connection, FIND_BY_ID, listOf(request.executionId))
                ?: return@write releaseConflict("execution not found")
            if (existing.isFinalTerminal) return@write ReconciliationReleaseResult.ExistingFinal(existing)
            val claim = readClaimEnvelope(connection, request.executionId)
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion ||
                claim == null || !ownsClaim(request.executionId, request.claimToken, claim)
            ) {
                return@write releaseConflict("reconciliation release state, version or owner conflict")
            }
            val released = existing.copy(
                updatedAt = request.releasedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = null,
            )
            val policy = selectPolicy(connection, request.executionId)
            val envelope = encodeEnvelope(released, policy, claim = null)
            val event = prepareAudit(connection, request.audit)
            updateRecord(connection, released, envelope, policy, existing.recordVersion, claimMutation = ClaimMutation.RELEASE)
            insertEvent(connection, event)
            ReconciliationReleaseResult.Released(released)
        }
        return when (result) {
            is ReconciliationReleaseResult.Released -> {
                localClaims.remove(request.executionId)
                cacheExact(result.record)
                ReconciliationReleaseResult.Released(result.record.snapshot())
            }
            is ReconciliationReleaseResult.ExistingFinal ->
                ReconciliationReleaseResult.ExistingFinal(result.record.withLocalExactValues())
            is ReconciliationReleaseResult.Conflict -> result
        }
    }

    /** 创建行只保存绑定、策略和脱敏 envelope，命令 input 从不进入 SQL 参数。 */
    private fun insertRecord(
        connection: Connection,
        record: ActionExecutionRecord,
        policies: ActionExecutionPolicies,
        envelope: String?,
        audit: ActionAuditDraft,
    ) {
        val safePayload = redactor.redact(audit.redactedPayload)
        val links = correlationLinks(safePayload)
        connection.prepareStatement(INSERT_RECORD).use { statement ->
            val values = listOf(
                record.command.executionId,
                record.binding.actionId,
                record.binding.actionVersion,
                record.binding.inputFingerprint,
                record.binding.origin.name,
                record.binding.identityScope.desktopInstanceId,
                record.binding.identityScope.desktopSessionId,
                record.binding.identityScope.authSessionId,
                record.binding.identityScope.identityEpoch,
                record.binding.identityScope.userId,
                record.binding.identityScope.tenantId,
                record.binding.identityScope.platformId,
                record.binding.pageId,
                record.binding.contextRevision,
                links.threadId,
                links.turnId,
                links.toolCallId,
                record.riskLevel.name,
                policies.replayPolicy.name,
                policies.reconciliationPolicy.name,
                record.state.name,
                safeRemoteReference(record.command.executionId, record.remoteReference()),
                envelope,
                record.errorCode()?.name,
                record.safeErrorMessage(),
                reconciliationStatus(record.state, policies.reconciliationPolicy, hasClaim = false),
                record.reconciliation?.reconciledAt?.toString(),
                record.createdAt.toString(),
                record.startedAt?.toString(),
                record.completedAt?.toString(),
                record.updatedAt.toString(),
                record.recordVersion,
            )
            bind(statement, values)
            check(statement.executeUpdate() == 1) { "execution insert failed" }
        }
    }

    /** 使用乐观版本谓词更新 execution，失败时让整个事务回滚。 */
    private fun updateRecord(
        connection: Connection,
        record: ActionExecutionRecord,
        envelope: String?,
        reconciliationPolicy: ReconciliationPolicy,
        expectedVersion: Long,
        claimMutation: ClaimMutation? = null,
        reconciliationSucceeded: Boolean = false,
    ) {
        val reconciliationStatus = when {
            reconciliationSucceeded -> "SUCCEEDED"
            claimMutation == ClaimMutation.CLAIM || claimMutation == ClaimMutation.RENEW -> "IN_PROGRESS"
            claimMutation == ClaimMutation.RELEASE -> if (reconciliationPolicy == ReconciliationPolicy.MANUAL) "MANUAL_REQUIRED" else "PENDING"
            else -> reconciliationStatus(record.state, reconciliationPolicy, hasClaim = false)
        }
        val incrementAttempts = if (claimMutation == ClaimMutation.CLAIM) 1 else 0
        connection.prepareStatement(UPDATE_RECORD).use { statement ->
            bind(
                statement,
                listOf(
                    record.state.name,
                    safeRemoteReference(record.command.executionId, record.remoteReference()),
                    envelope,
                    record.errorCode()?.name,
                    record.safeErrorMessage(),
                    reconciliationStatus,
                    incrementAttempts,
                    if (reconciliationSucceeded) record.completedAt?.toString() else record.reconciliation?.reconciledAt?.toString(),
                    record.startedAt?.toString(),
                    record.completedAt?.toString(),
                    record.updatedAt.toString(),
                    record.recordVersion,
                    record.command.executionId,
                    expectedVersion,
                ),
            )
            check(statement.executeUpdate() == 1) { "execution optimistic update failed" }
        }
    }

    /** 事件 sequence 在写事务中读取并分配，保证 execution 内严格连续。 */
    private fun prepareAudit(connection: Connection, draft: ActionAuditDraft): ActionAuditEvent {
        val sequence = connection.prepareStatement(SELECT_NEXT_EVENT_SEQUENCE).use { statement ->
            statement.setString(1, draft.executionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
        return ActionAuditEvent(
            draft.executionId,
            sequence,
            draft.fromState,
            draft.toState,
            draft.type,
            safeAuditPayload(draft.redactedPayload),
            safeActorId(draft.executionId, draft.actorId),
            draft.occurredAt,
        )
    }

    /** 追加预先完成脱敏的不可变事件。 */
    private fun insertEvent(connection: Connection, event: ActionAuditEvent) {
        val encoded = JSON.encodeToString(JsonElement.serializer(), event.redactedPayload)
        connection.prepareStatement(INSERT_EVENT).use { statement ->
            bind(
                statement,
                listOf(
                    UUID.randomUUID().toString(),
                    event.executionId,
                    event.sequence,
                    event.fromState?.name,
                    event.toState.name,
                    event.type,
                    encoded,
                    event.actorId,
                    event.occurredAt.toString(),
                ),
            )
            check(statement.executeUpdate() == 1) { "audit event insert failed" }
        }
    }

    /** 迟到终态事件只记录安全目标状态，行和值版本完全不变。 */
    private fun appendLateTerminalEvent(
        connection: Connection,
        existing: ActionExecutionRecord,
        update: ExecutionTransition,
    ) {
        insertEvent(
            connection,
            prepareAudit(
                connection,
                ActionAuditDraft(
                    executionId = existing.command.executionId,
                    fromState = existing.state,
                    toState = existing.state,
                    type = "late_terminal_response",
                    redactedPayload = buildJsonObject {
                        put("attemptedTargetState", update.state.name)
                        put("storedState", existing.state.name)
                    },
                    actorId = update.audit.actorId,
                    occurredAt = update.updatedAt,
                ),
            ),
        )
    }

    /** 从固定 SELECT 结果构造脱敏持久记录。 */
    private fun ResultSet.toRecord(): ActionExecutionRecord {
        val scope = ActionIdentityScope(
            getString("desktop_instance_id"),
            getString("desktop_session_id"),
            getString("auth_session_id"),
            getLong("identity_epoch"),
            getString("user_id"),
            getString("tenant_id"),
            getString("platform_id"),
        )
        val command = ActionCommand(
            executionId = getString("execution_id"),
            actionId = getString("action_id"),
            actionVersion = getInt("action_version"),
            input = JsonObject(emptyMap()),
            origin = ActionOrigin.valueOf(getString("origin")),
            identityScope = scope,
            pageId = getString("page_id"),
            contextRevision = getLong("context_revision"),
        )
        val state = ActionExecutionState.valueOf(getString("status"))
        val policy = ReconciliationPolicy.valueOf(getString("reconciliation_policy"))
        val envelope = getString("result_json_redacted")?.let(::decodeEnvelope)
        val terminal = envelope?.terminal?.let { decodeTerminal(command.executionId, state, policy, getString("remote_reference"), it) }
        val successFact = envelope?.successFact?.let(::decodeSuccessFact)
        val provenance = envelope?.provenance?.let {
            ReconciliationProvenance(it.sourceVersion, it.reconciledAt)
        }
        val claim = envelope?.claim?.let { renderClaim(command.executionId, it) }
        return ActionExecutionRecord(
            command = command,
            binding = ExecutionBinding(
                getString("action_id"),
                getInt("action_version"),
                getString("input_fingerprint"),
                ActionOrigin.valueOf(getString("origin")),
                scope,
                getString("page_id"),
                getLong("context_revision"),
            ),
            riskLevel = ActionRiskLevel.valueOf(getString("risk_level")),
            state = state,
            result = terminal,
            createdAt = Instant.parse(getString("created_at")),
            startedAt = getString("started_at")?.let(Instant::parse),
            completedAt = getString("completed_at")?.let(Instant::parse),
            updatedAt = Instant.parse(getString("updated_at")),
            recordVersion = getLong("record_version"),
            reconciliation = provenance,
            successFact = successFact,
            reconciliationClaim = claim,
        )
    }

    /** 版本化 envelope 只编码脱敏终态、成功事实、对账来源及 claim 摘要。 */
    private fun encodeEnvelope(
        record: ActionExecutionRecord,
        reconciliationPolicy: ReconciliationPolicy,
        claim: ClaimEnvelope?,
    ): String? {
        if (record.result == null && record.successFact == null && record.reconciliation == null && claim == null) return null
        val root = buildJsonObject {
            put("codecVersion", CODEC_VERSION)
            record.result?.let { put("terminal", encodeTerminal(it)) }
            record.successFact?.let { put("successFact", encodeSuccessFact(record.command.executionId, it)) }
            record.reconciliation?.let {
                put("provenance", buildJsonObject {
                    put("sourceVersion", it.sourceRecordVersion)
                    put("reconciledAt", it.reconciledAt.toString())
                })
            }
            claim?.let {
                put("claim", buildJsonObject {
                    put("claimDigest", it.tokenDigest)
                    put("ownerDigest", it.ownerDigest)
                    put("claimedAt", it.claimedAt.toString())
                    put("expiresAt", it.expiresAt.toString())
                })
            }
            put("reconciliationPolicy", reconciliationPolicy.name)
        }
        return JSON.encodeToString(JsonElement.serializer(), root)
    }

    /** 成功结果永远丢弃 raw output，只编码调用方明确提供的 redactedOutput。 */
    @Suppress("UNCHECKED_CAST")
    private fun encodeTerminal(result: ActionResult<JsonElement>): JsonObject = when (result) {
        is ActionResult.Success<*> -> buildJsonObject {
            put("kind", "success")
            val redactedOutput = result.redactedOutput as JsonElement?
            put("hasRedactedOutput", redactedOutput != null)
            put("output", redactedOutput ?: JsonNull)
        }
        is ActionResult.Failure -> buildJsonObject {
            put("kind", "failure")
            put("code", result.error.code.name)
            put("message", safeMessage(result.error.code))
            result.error.details?.let { put("details", redactor.redact(it)) }
        }
        is ActionResult.Canceled -> buildJsonObject {
            put("kind", "canceled")
            put("message", "动作已取消")
        }
        is ActionResult.Expired -> buildJsonObject {
            put("kind", "expired")
            put("message", "动作已过期")
        }
        is ActionResult.OutcomeUnknown -> buildJsonObject {
            put("kind", "outcome_unknown")
            put("code", result.error.code.name)
            put("message", "动作结果未知")
            result.error.details?.let { put("details", redactor.redact(it)) }
        }
        is ActionResult.Preview,
        is ActionResult.ApprovalRequired,
        -> error("intermediate result cannot be persisted as terminal")
    }

    /** 从安全 terminal JSON 重建可重放结果；Success.output 也是脱敏表示。 */
    private fun decodeTerminal(
        executionId: String,
        state: ActionExecutionState,
        reconciliationPolicy: ReconciliationPolicy,
        remoteReference: String?,
        terminal: JsonObject,
    ): ActionResult<JsonElement> = when (terminal.string("kind")) {
        "success" -> {
            val output = terminal["output"] ?: JsonNull
            ActionResult.Success(
                executionId,
                output,
                if (terminal.boolean("hasRedactedOutput")) output else null,
                remoteReference,
            )
        }
        "failure" -> ActionResult.Failure(
            executionId,
            ActionError(
                terminal.string("code")?.let(ActionErrorCode::valueOf) ?: ActionErrorCode.REMOTE_REQUEST_FAILED,
                terminal.string("message") ?: "动作执行失败",
                terminal["details"] as? JsonObject,
            ),
            remoteReference,
        )
        "canceled" -> ActionResult.Canceled(executionId, terminal.string("message") ?: "动作已取消")
        "expired" -> ActionResult.Expired(executionId, terminal.string("message") ?: "动作已过期")
        "outcome_unknown" -> ActionResult.OutcomeUnknown(
            executionId,
            ActionError(
                terminal.string("code")?.let(ActionErrorCode::valueOf) ?: ActionErrorCode.OUTCOME_UNKNOWN,
                terminal.string("message") ?: "动作结果未知",
                terminal["details"] as? JsonObject,
            ),
            remoteReference,
            reconciliationPolicy,
        )
        else -> error("unsupported terminal envelope for $state")
    }

    private fun encodeSuccessFact(executionId: String, fact: ExecutionSuccessFact) = buildJsonObject {
        put("kind", fact.kind)
        safeRemoteReference(executionId, fact.remoteReference)?.let { put("remoteReference", it) }
        fact.errorCode?.let { put("errorCode", it.name) }
        safeSuccessFactMessage(fact)?.let { put("safeMessage", it) }
        put("source", fact.source)
    }

    private fun decodeSuccessFact(value: JsonObject) = ExecutionSuccessFact(
        kind = value.string("kind")!!,
        remoteReference = value.string("remoteReference"),
        errorCode = value.string("errorCode")?.let(ActionErrorCode::valueOf),
        safeMessage = value.string("safeMessage"),
        source = value.string("source")!!,
    )

    /** 解码时拒绝未知 envelope 版本，避免错误解释持久安全元数据。 */
    private fun decodeEnvelope(encoded: String): DurableEnvelope {
        val root = JSON.parseToJsonElement(encoded).jsonObject
        require(root["codecVersion"]?.jsonPrimitive?.intOrNull == CODEC_VERSION) { "unsupported execution codec" }
        val provenance = (root["provenance"] as? JsonObject)?.let {
            ProvenanceEnvelope(it["sourceVersion"]!!.jsonPrimitive.longOrNull!!, Instant.parse(it.string("reconciledAt")))
        }
        val claim = (root["claim"] as? JsonObject)?.let {
            ClaimEnvelope(
                it.string("claimDigest")!!,
                it.string("ownerDigest")!!,
                Instant.parse(it.string("claimedAt")),
                Instant.parse(it.string("expiresAt")),
            )
        }
        return DurableEnvelope(
            terminal = root["terminal"] as? JsonObject,
            successFact = root["successFact"] as? JsonObject,
            provenance = provenance,
            claim = claim,
        )
    }

    /** 只读取 envelope 中的 claim 摘要；不存在结果 JSON 时视为无 claim。 */
    private fun readClaimEnvelope(connection: Connection, executionId: String): ClaimEnvelope? =
        connection.prepareStatement(SELECT_ENVELOPE).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else rows.getString(1)?.let(::decodeEnvelope)?.claim
            }
        }

    private fun selectPolicy(connection: Connection, executionId: String): ReconciliationPolicy =
        connection.prepareStatement(SELECT_RECONCILIATION_POLICY).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                ReconciliationPolicy.valueOf(rows.getString(1))
            }
        }

    private fun selectRecord(connection: Connection, sql: String, values: List<Any?>): ActionExecutionRecord? =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, values)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
        }

    /** scope 的七个字段始终以固定顺序参与 SQL 谓词。 */
    private fun ActionIdentityScope.arguments(): List<Any?> = listOf(
        desktopInstanceId,
        desktopSessionId,
        authSessionId,
        identityEpoch,
        userId,
        tenantId,
        platformId,
    )

    /** 仅从已经脱敏的 payload 中提取非空对话关联，不读取命令 input。 */
    private fun correlationLinks(payload: JsonObject) = CorrelationLinks(
        threadId = payload.safeString("threadId"),
        turnId = payload.safeString("turnId"),
        toolCallId = payload.safeString("toolCallId"),
    )

    private fun JsonObject.safeString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    /** JDBC 参数绑定只接受固定标量和 null，不拼接任何业务值。 */
    private fun bind(statement: java.sql.PreparedStatement, values: List<Any?>) {
        values.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setObject(position, null)
                is String -> statement.setString(position, value)
                is Int -> statement.setInt(position, value)
                is Long -> statement.setLong(position, value)
                else -> error("unsupported JDBC value type")
            }
        }
    }

    /** 数据库记录匹配当前实例版本和 binding 时才覆盖为精确缓存。 */
    private fun ActionExecutionRecord.withLocalExactValues(): ActionExecutionRecord {
        val exact = exactRecords[command.executionId]
        return if (exact != null && exact.recordVersion == recordVersion && exact.binding == binding) {
            exact.snapshot()
        } else {
            snapshot()
        }
    }

    private fun cacheReplayInput(durable: ActionExecutionRecord, candidate: ActionExecutionRecord) {
        val previous = exactRecords[durable.command.executionId]
            ?.takeIf { it.recordVersion == durable.recordVersion && it.binding == durable.binding }
        val exact = durable.copy(
            command = durable.command.copy(input = candidate.command.input.snapshot() as JsonObject),
            result = previous?.result ?: durable.result,
            reconciliationClaim = previous?.reconciliationClaim ?: durable.reconciliationClaim,
        )
        exactRecords[durable.command.executionId] = exact.snapshot()
    }

    private fun cacheExact(record: ActionExecutionRecord) {
        exactRecords[record.command.executionId] = record.snapshot()
    }

    private fun mergeExactMutation(
        durable: ActionExecutionRecord,
        exactResult: ActionResult<JsonElement>?,
    ): ActionExecutionRecord {
        val previous = exactRecords[durable.command.executionId]
        return durable.copy(
            command = durable.command.copy(input = previous?.command?.input ?: durable.command.input),
            result = exactResult?.snapshot() ?: durable.result,
        )
    }

    /** 只有本实例同时持有原始 token 且摘要与持久 claim 一致时才算 owner。 */
    private fun ownsClaim(executionId: String, token: String, claim: ClaimEnvelope): Boolean {
        val local = localClaims[executionId] ?: return false
        return local.token == token &&
            local.tokenDigest == claim.tokenDigest &&
            local.ownerDigest == claim.ownerDigest &&
            digest(executionId, token) == claim.tokenDigest
    }

    /** 重启后的占位 claim 只表达租约存在，不提供可用于 mutation 的原始凭据。 */
    private fun renderClaim(executionId: String, claim: ClaimEnvelope): ReconciliationClaim {
        val local = localClaims[executionId]
        return if (local != null && local.tokenDigest == claim.tokenDigest && local.ownerDigest == claim.ownerDigest) {
            ReconciliationClaim(local.token, local.owner, claim.claimedAt, claim.expiresAt)
        } else {
            ReconciliationClaim(
                "unavailable-${claim.tokenDigest.take(16)}",
                "unavailable-${claim.ownerDigest.take(16)}",
                claim.claimedAt,
                claim.expiresAt,
            )
        }
    }

    private fun digest(executionId: String, value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((executionId + "\u0000" + value).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun reconciliationStatus(
        state: ActionExecutionState,
        policy: ReconciliationPolicy,
        hasClaim: Boolean,
    ): String = when {
        hasClaim -> "IN_PROGRESS"
        state != ActionExecutionState.OUTCOME_UNKNOWN -> "NOT_REQUIRED"
        policy == ReconciliationPolicy.QUERY_REMOTE -> "PENDING"
        policy == ReconciliationPolicy.MANUAL -> "MANUAL_REQUIRED"
        else -> "NOT_REQUIRED"
    }

    private fun ActionExecutionRecord.remoteReference(): String? = when (val value = result) {
        is ActionResult.Success -> value.remoteReference
        is ActionResult.Failure -> value.remoteReference
        is ActionResult.OutcomeUnknown -> value.remoteReference
        else -> successFact?.remoteReference
    }

    private fun ActionExecutionRecord.errorCode(): ActionErrorCode? = when (val value = result) {
        is ActionResult.Failure -> value.error.code
        is ActionResult.OutcomeUnknown -> value.error.code
        else -> successFact?.errorCode
    }

    private fun ActionExecutionRecord.safeErrorMessage(): String? = when (val value = result) {
        is ActionResult.Failure -> safeMessage(value.error.code)
        is ActionResult.Canceled -> "动作已取消"
        is ActionResult.Expired -> "动作已过期"
        is ActionResult.OutcomeUnknown -> "动作结果未知"
        else -> successFact?.let(::safeSuccessFactMessage)
    }

    /** 成功事实说明由 kind 决定，调用方文本绝不进入持久层。 */
    private fun safeSuccessFactMessage(fact: ExecutionSuccessFact): String? = when (fact.kind) {
        ExecutionSuccessFact.OUTPUT_ENCODING_FAILED -> "动作输出不可用"
        ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS -> null
        else -> null
    }

    /** 审计载荷在通用脱敏后再次移除 claim owner/token 等执行存储专有敏感字段。 */
    private fun safeAuditPayload(payload: JsonObject): JsonObject =
        persistencePayloadRedactor.redact(redactor.redact(payload))

    /**
     * 仅保存短且无凭据特征的 actor 标识；其余值转换为 execution-bound 稳定假名。
     */
    private fun safeActorId(executionId: String, actorId: String?): String? {
        if (actorId == null) return null
        return if (actorId.length <= MAX_ACTOR_ID_LENGTH &&
            SAFE_ACTOR_ID.matches(actorId) &&
            !containsCredentialMaterial(actorId)
        ) {
            actorId
        } else {
            "actor-sha256:${digest(executionId, actorId).take(PSEUDONYM_HEX_LENGTH)}"
        }
    }

    /**
     * 普通业务引用保持可查；凭据特征、JWT、控制字符或超长引用只保存稳定假名。
     */
    private fun safeRemoteReference(executionId: String, reference: String?): String? {
        if (reference == null) return null
        return if (reference.isNotBlank() &&
            reference.length <= MAX_REMOTE_REFERENCE_LENGTH &&
            reference.none(Char::isISOControl) &&
            !containsCredentialMaterial(reference)
        ) {
            reference
        } else {
            "ref-sha256:${digest(executionId, reference).take(PSEUDONYM_HEX_LENGTH)}"
        }
    }

    /** 复合词凭据标记和三段式 JWT 都按敏感材料处理。 */
    private fun containsCredentialMaterial(value: String): Boolean {
        val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return CREDENTIAL_MARKERS.any(normalized::contains) || JWT_PATTERN.containsMatchIn(value)
    }

    private fun safeMessage(code: ActionErrorCode): String = when (code) {
        ActionErrorCode.OUTCOME_UNKNOWN -> "动作结果未知"
        ActionErrorCode.APPROVAL_DENIED -> "动作审批已拒绝"
        ActionErrorCode.APPROVAL_EXPIRED -> "动作审批已过期"
        ActionErrorCode.AUTH_EXPIRED -> "认证已过期"
        ActionErrorCode.MEMBERSHIP_EXPIRED -> "会员已过期"
        else -> "动作执行失败"
    }

    /** 创建记录、binding、首条事件和时间必须表示同一个事实。 */
    private fun validateCreateAudit(record: ActionExecutionRecord, audit: ActionAuditDraft) {
        val command = record.command
        val binding = record.binding
        require(binding.actionId == command.actionId) { "binding actionId must match command" }
        require(binding.actionVersion == command.actionVersion) { "binding actionVersion must match command" }
        require(binding.origin == command.origin) { "binding origin must match command" }
        require(binding.identityScope == command.identityScope) { "binding identityScope must match command" }
        require(binding.pageId == command.pageId) { "binding pageId must match command" }
        require(binding.contextRevision == command.contextRevision) { "binding contextRevision must match command" }
        require(audit.executionId == command.executionId) { "创建审计 executionId 不匹配" }
        require(audit.fromState == ActionExecutionState.RECEIVED) { "创建审计必须源自 RECEIVED" }
        require(audit.toState == record.state) { "创建审计目标状态与记录不匹配" }
        require(record.createdAt == record.updatedAt) { "创建记录的 createdAt 与 updatedAt 必须一致" }
        require(audit.occurredAt == record.createdAt) { "创建审计时间与记录创建时间不匹配" }
    }

    private fun validateTransitionAudit(update: ExecutionTransition) {
        require(update.audit.toState == update.state) { "迁移审计目标状态与更新不匹配" }
        validateAuditTime(update.audit, update.updatedAt, "迁移")
        if (update.state.isPersistedTerminal()) {
            require(update.completedAt == update.updatedAt) { "终态 completedAt 与 updatedAt 必须一致" }
        }
    }

    private fun validateReconciliationAudit(update: ReconciliationExecutionUpdate) {
        val state = if (update.result is ActionResult.Failure) ActionExecutionState.FAILED else ActionExecutionState.SUCCEEDED
        require(update.audit.toState == state) { "最终对账审计目标状态与结果不匹配" }
        validateAuditTime(update.audit, update.completedAt, "最终对账")
    }

    private fun validateAuditTime(audit: ActionAuditDraft, businessTime: Instant, operation: String) {
        require(audit.occurredAt == businessTime) { "$operation 审计时间与业务时间不匹配" }
    }

    /** 复制记录和 JSON 容器，避免缓存暴露调用方可变引用。 */
    private fun ActionExecutionRecord.snapshot(): ActionExecutionRecord = copy(
        command = command.copy(input = command.input.snapshot() as JsonObject),
        result = result?.snapshot(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun ActionResult<JsonElement>.snapshot(): ActionResult<JsonElement> = when (this) {
        is ActionResult.Success<*> -> ActionResult.Success(
            executionId,
            (output as JsonElement).snapshot(),
            (redactedOutput as JsonElement?)?.snapshot(),
            remoteReference,
        )
        is ActionResult.Failure -> copy(error = error.copy(details = error.details?.snapshot() as JsonObject?))
        is ActionResult.Canceled -> copy()
        is ActionResult.Expired -> copy()
        is ActionResult.OutcomeUnknown -> copy(error = error.copy(details = error.details?.snapshot() as JsonObject?))
        is ActionResult.Preview -> copy()
        is ActionResult.ApprovalRequired -> copy()
    }

    private fun JsonElement.snapshot(): JsonElement = when (this) {
        JsonNull -> JsonNull
        is JsonPrimitive -> this
        is JsonObject -> JsonObject(Collections.unmodifiableMap(entries.associateTo(linkedMapOf()) { it.key to it.value.snapshot() }))
        is JsonArray -> JsonArray(Collections.unmodifiableList(map { it.snapshot() }))
    }

    private data class CorrelationLinks(val threadId: String?, val turnId: String?, val toolCallId: String?)
    private data class LocalClaim(val token: String, val owner: String, val tokenDigest: String, val ownerDigest: String)
    private data class ClaimEnvelope(val tokenDigest: String, val ownerDigest: String, val claimedAt: Instant, val expiresAt: Instant)
    private data class ProvenanceEnvelope(val sourceVersion: Long, val reconciledAt: Instant)
    private data class DurableEnvelope(
        val terminal: JsonObject?,
        val successFact: JsonObject?,
        val provenance: ProvenanceEnvelope?,
        val claim: ClaimEnvelope?,
    )
    private enum class ClaimMutation { CLAIM, RENEW, RELEASE }

    private companion object {
        const val CODEC_VERSION = 1
        const val MAX_ACTOR_ID_LENGTH = 128
        const val MAX_REMOTE_REFERENCE_LENGTH = 256
        const val PSEUDONYM_HEX_LENGTH = 32
        val JSON = Json { ignoreUnknownKeys = true }
        val SAFE_ACTOR_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")
        val JWT_PATTERN = Regex("(?i)(?:^|\\s)[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:$|\\s)")
        val CREDENTIAL_MARKERS = setOf(
            "bearer",
            "authorization",
            "accesstoken",
            "refreshtoken",
            "claimtoken",
            "password",
            "clientsecret",
            "apikey",
            "accesskey",
            "secretkey",
            "privatekey",
        )

        val EXECUTION_COLUMNS = """
            execution_id,action_id,action_version,input_fingerprint,origin,
            desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,
            page_id,context_revision,thread_id,turn_id,tool_call_id,risk_level,replay_policy,reconciliation_policy,
            status,remote_reference,result_json_redacted,error_code,error_message_redacted,reconciliation_status,
            reconciliation_attempts,last_reconciled_at,created_at,started_at,completed_at,updated_at,record_version
        """.trimIndent().replace("\n", " ")

        val FIND_BY_ID = "SELECT $EXECUTION_COLUMNS FROM bd_action_executions WHERE execution_id=?"
        val FIND_BY_ID_AND_SCOPE = """
            SELECT $EXECUTION_COLUMNS FROM bd_action_executions
            WHERE execution_id=? AND desktop_instance_id=? AND desktop_session_id=? AND auth_session_id=?
              AND identity_epoch=? AND user_id=? AND tenant_id=? AND platform_id=?
        """.trimIndent()
        val LIST_NON_TERMINAL_BY_SCOPE = """
            SELECT $EXECUTION_COLUMNS FROM bd_action_executions
            WHERE desktop_instance_id=? AND desktop_session_id=? AND auth_session_id=?
              AND identity_epoch=? AND user_id=? AND tenant_id=? AND platform_id=?
              AND status NOT IN ('SUCCEEDED','FAILED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')
            ORDER BY created_at ASC, execution_id ASC
        """.trimIndent()
        val INSERT_RECORD = """
            INSERT INTO bd_action_executions (
                execution_id,action_id,action_version,input_fingerprint,origin,
                desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,
                page_id,context_revision,thread_id,turn_id,tool_call_id,risk_level,replay_policy,reconciliation_policy,
                status,remote_reference,result_json_redacted,error_code,error_message_redacted,reconciliation_status,
                reconciliation_attempts,last_reconciled_at,created_at,started_at,completed_at,updated_at,record_version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
        """.trimIndent()
        val UPDATE_RECORD = """
            UPDATE bd_action_executions SET
                status=?,remote_reference=?,result_json_redacted=?,error_code=?,error_message_redacted=?,
                reconciliation_status=?,reconciliation_attempts=reconciliation_attempts+?,last_reconciled_at=?,
                started_at=?,completed_at=?,updated_at=?,record_version=?
            WHERE execution_id=? AND record_version=?
        """.trimIndent()
        const val SELECT_NEXT_EVENT_SEQUENCE =
            "SELECT COALESCE(MAX(event_sequence),0)+1 FROM bd_action_events WHERE execution_id=?"
        const val INSERT_EVENT = """
            INSERT INTO bd_action_events (
                event_id,execution_id,event_sequence,from_status,to_status,event_type,payload_json_redacted,actor_id,occurred_at
            ) VALUES (?,?,?,?,?,?,?,?,?)
        """
        const val SELECT_EVENTS = """
            SELECT execution_id,event_sequence,from_status,to_status,event_type,payload_json_redacted,actor_id,occurred_at
            FROM bd_action_events WHERE execution_id=? ORDER BY event_sequence ASC
        """
        const val SELECT_ENVELOPE = "SELECT result_json_redacted FROM bd_action_executions WHERE execution_id=?"
        const val SELECT_RECONCILIATION_POLICY =
            "SELECT reconciliation_policy FROM bd_action_executions WHERE execution_id=?"
    }
}

private fun ActionExecutionState.isPersistedTerminal(): Boolean = this in setOf(
    ActionExecutionState.SUCCEEDED,
    ActionExecutionState.FAILED,
    ActionExecutionState.CANCELED,
    ActionExecutionState.EXPIRED,
    ActionExecutionState.OUTCOME_UNKNOWN,
)

private fun executionConflict(message: String) = ActionError(ActionErrorCode.EXECUTION_CONFLICT, message)
private fun createConflict(message: String) = ExecutionCreateResult.Conflict(executionConflict(message))
private fun transitionConflict(message: String) = ExecutionTransitionResult.Conflict(executionConflict(message))
private fun reconciliationConflict(message: String) = ReconciliationUpdateResult.Conflict(executionConflict(message))
private fun claimConflict(message: String) = ReconciliationClaimResult.Conflict(executionConflict(message))
private fun renewConflict(message: String) = ReconciliationRenewResult.Conflict(executionConflict(message))
private fun releaseConflict(message: String) = ReconciliationReleaseResult.Conflict(executionConflict(message))
