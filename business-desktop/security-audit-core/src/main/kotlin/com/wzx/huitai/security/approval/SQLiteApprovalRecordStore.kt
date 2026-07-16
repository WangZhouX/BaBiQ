package com.wzx.huitai.security.approval

import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/** 与单个 execution 及完整身份范围绑定的审批生命周期。 */
data class ApprovalRecord(
    val approvalId: String,
    val executionId: String,
    val identityScope: ActionIdentityScope,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val decision: ApprovalDecision? = null,
    val decidedAt: Instant? = null,
    val decidedBy: String? = null,
    val reasonRedacted: String? = null,
) {
    init {
        require(approvalId.isNotBlank()) { "审批 id 不能为空" }
        require(executionId.isNotBlank()) { "审批 executionId 不能为空" }
        require(!expiresAt.isBefore(requestedAt)) { "审批到期时间不能早于请求时间" }
        if (decision == null) {
            require(decidedAt == null && decidedBy == null && reasonRedacted == null) { "待审批记录不能包含决定字段" }
        } else {
            require(decidedAt != null && !decidedAt.isBefore(requestedAt)) { "审批决定时间不能早于请求时间" }
            if (decision == ApprovalDecision.APPROVED) require(!decidedBy.isNullOrBlank()) { "批准必须包含审批人" }
        }
    }
}

/** 第一次创建待审批记录的穷举结果。 */
sealed interface ApprovalCreateResult {
    data class Created(val record: ApprovalRecord) : ApprovalCreateResult
    data class Existing(val record: ApprovalRecord) : ApprovalCreateResult
    data class Conflict(val message: String) : ApprovalCreateResult
}

/** 第一次决定审批的穷举结果。 */
sealed interface ApprovalDecideResult {
    data class Decided(val record: ApprovalRecord) : ApprovalDecideResult
    data class ExistingDecision(val record: ApprovalRecord) : ApprovalDecideResult
    data class Conflict(val message: String) : ApprovalDecideResult
}

/** 单 execution 审批决定请求，不提供任何会话级授权语义。 */
data class ApprovalDecisionRequest(
    val approvalId: String,
    val executionId: String,
    val identityScope: ActionIdentityScope,
    val decision: ApprovalDecision,
    val decidedAt: Instant,
    val decidedBy: String? = null,
    val reason: String? = null,
) {
    init {
        require(approvalId.isNotBlank()) { "审批 id 不能为空" }
        require(executionId.isNotBlank()) { "审批 executionId 不能为空" }
        if (decision == ApprovalDecision.APPROVED) require(!decidedBy.isNullOrBlank()) { "批准必须包含审批人" }
    }
}

/**
 * 基于 SQLite 的逐 execution 审批记录存储。
 *
 * 审批表缺少 desktopSessionId，因此所有查询和决定都 JOIN execution 表校验完整七维身份范围。
 */
class SQLiteApprovalRecordStore(
    private val database: BusinessDesktopDatabase,
) {
    /** 比较完整审批绑定，只允许每个 execution 创建一个待审批生命周期。 */
    suspend fun compareAndCreatePending(record: ApprovalRecord): ApprovalCreateResult {
        require(record.decision == null) { "只能创建待审批记录" }
        return database.write { connection ->
            val parent = selectParentScope(connection, record.executionId)
                ?: return@write ApprovalCreateResult.Conflict("execution 不存在")
            if (parent != record.identityScope) return@write ApprovalCreateResult.Conflict("execution 身份范围冲突")
            selectByExecutionRaw(connection, record.executionId)?.let { existing ->
                if (!existing.approvalMatchesExecution) {
                    return@write ApprovalCreateResult.Conflict("审批行身份范围损坏")
                }
                return@write if (existing.record == record) ApprovalCreateResult.Existing(existing.record)
                else ApprovalCreateResult.Conflict("审批绑定冲突")
            }
            if (selectByApprovalId(connection, record.approvalId) != null) {
                return@write ApprovalCreateResult.Conflict("approvalId 已绑定其他 execution")
            }
            connection.prepareStatement(INSERT_PENDING).use { statement ->
                val scope = record.identityScope
                val values = listOf(
                    record.approvalId, record.executionId, scope.desktopInstanceId, scope.authSessionId,
                    scope.identityEpoch, scope.userId, scope.tenantId, scope.platformId,
                    record.requestedAt.toString(), record.expiresAt.toString(),
                )
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                check(statement.executeUpdate() == 1) { "审批记录创建失败" }
            }
            ApprovalCreateResult.Created(record)
        }
    }

    /** 完整 scope 匹配时返回审批；旧 desktop session 与不存在采用相同 null 结果。 */
    suspend fun find(executionId: String, identityScope: ActionIdentityScope): ApprovalRecord? =
        database.read { connection -> selectScoped(connection, executionId, identityScope) }

    /** 在写事务中执行 first-decision-wins，并对审批人及原因做安全标量处理。 */
    suspend fun decide(request: ApprovalDecisionRequest): ApprovalDecideResult = database.write { connection ->
        val existing = selectScoped(connection, request.executionId, request.identityScope)
            ?: return@write ApprovalDecideResult.Conflict("审批不存在或身份范围冲突")
        if (existing.approvalId != request.approvalId) return@write ApprovalDecideResult.Conflict("审批绑定冲突")
        existing.decision?.let { return@write ApprovalDecideResult.ExistingDecision(existing) }
        if (request.decidedAt.isBefore(existing.requestedAt)) {
            return@write ApprovalDecideResult.Conflict("审批决定时间无效")
        }
        if (request.decision == ApprovalDecision.EXPIRED) {
            if (request.decidedAt.isBefore(existing.expiresAt)) {
                return@write ApprovalDecideResult.Conflict("审批尚未到期")
            }
        } else if (!request.decidedAt.isBefore(existing.expiresAt)) {
            return@write ApprovalDecideResult.Conflict("审批已过期")
        }
        val decidedBy = safeActor(request.executionId, request.decidedBy)
        if (request.decision == ApprovalDecision.APPROVED && decidedBy.isNullOrBlank()) {
            return@write ApprovalDecideResult.Conflict("批准必须包含审批人")
        }
        val reason = safeReason(request.reason)
        connection.prepareStatement(DECIDE).use { statement ->
            val values = listOf(
                request.decision.name, request.decidedAt.toString(), decidedBy, reason,
                request.approvalId, request.executionId,
            )
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            if (statement.executeUpdate() != 1) {
                return@write selectByExecutionRaw(connection, request.executionId)?.record?.let(ApprovalDecideResult::ExistingDecision)
                    ?: ApprovalDecideResult.Conflict("审批并发决定冲突")
            }
        }
        ApprovalDecideResult.Decided(
            existing.copy(
                decision = request.decision,
                decidedAt = request.decidedAt,
                decidedBy = decidedBy,
                reasonRedacted = reason,
            ),
        )
    }

    /** approval 与 execution 表的其他六维身份也必须相互一致。 */
    private fun selectScoped(
        connection: Connection,
        executionId: String,
        scope: ActionIdentityScope,
    ): ApprovalRecord? = connection.prepareStatement(SELECT_SCOPED).use { statement ->
        val values = listOf(
            executionId, scope.desktopInstanceId, scope.desktopSessionId, scope.authSessionId,
            scope.identityEpoch, scope.userId, scope.tenantId, scope.platformId,
        )
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
    }

    private fun selectByExecutionRaw(connection: Connection, executionId: String): ApprovalRow? =
        connection.prepareStatement(SELECT_BY_EXECUTION).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toApprovalRow() else null }
        }

    private fun selectByApprovalId(connection: Connection, approvalId: String): ApprovalRecord? =
        connection.prepareStatement(SELECT_BY_APPROVAL).use { statement ->
            statement.setString(1, approvalId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
        }

    private fun selectParentScope(connection: Connection, executionId: String): ActionIdentityScope? =
        connection.prepareStatement(SELECT_PARENT_SCOPE).use { statement ->
            statement.setString(1, executionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toScope() else null }
        }

    private fun ResultSet.toRecord() = ApprovalRecord(
        approvalId = getString("approval_id"),
        executionId = getString("execution_id"),
        identityScope = toScope(),
        requestedAt = Instant.parse(getString("requested_at")),
        expiresAt = Instant.parse(getString("expires_at")),
        decision = getString("decision")?.let(ApprovalDecision::valueOf),
        decidedAt = getString("decided_at")?.let(Instant::parse),
        decidedBy = getString("decided_by"),
        reasonRedacted = getString("reason_redacted"),
    )

    /** 同时保留 approval 行和 execution 行的身份，避免 JOIN 重建掩盖审批行漂移。 */
    private fun ResultSet.toApprovalRow(): ApprovalRow {
        val executionScope = toScope()
        val approvalScope = ActionIdentityScope(
            getString("approval_desktop_instance_id"),
            executionScope.desktopSessionId,
            getString("approval_auth_session_id"),
            getLong("approval_identity_epoch"),
            getString("approval_user_id"),
            getString("approval_tenant_id"),
            getString("approval_platform_id"),
        )
        return ApprovalRow(toRecord(), approvalScope == executionScope)
    }

    private fun ResultSet.toScope() = ActionIdentityScope(
        getString("desktop_instance_id"), getString("desktop_session_id"), getString("auth_session_id"),
        getLong("identity_epoch"), getString("user_id"), getString("tenant_id"), getString("platform_id"),
    )

    private fun safeActor(executionId: String, actor: String?): String? {
        if (actor == null) return null
        return if (actor.length <= MAX_ACTOR_LENGTH && SAFE_ACTOR.matches(actor) && !containsSecret(actor)) actor
        else "actor-sha256:${digest(executionId, actor).take(PSEUDONYM_LENGTH)}"
    }

    /** 原因只保留短、无控制符和凭据标记的普通说明，否则固定脱敏。 */
    private fun safeReason(reason: String?): String? {
        if (reason == null) return null
        return if (reason.length <= MAX_REASON_LENGTH && reason.none(Char::isISOControl) && !containsSecret(reason)) reason
        else REDACTED
    }

    private fun containsSecret(value: String): Boolean {
        val compact = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return SECRET_MARKERS.any(compact::contains) || JWT_PATTERN.containsMatchIn(value)
    }

    private fun digest(executionId: String, value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((executionId + "\u0000" + value).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ApprovalRow(
        val record: ApprovalRecord,
        val approvalMatchesExecution: Boolean,
    )

    private companion object {
        const val REDACTED = "[REDACTED]"
        const val MAX_ACTOR_LENGTH = 128
        const val MAX_REASON_LENGTH = 512
        const val PSEUDONYM_LENGTH = 32
        val SAFE_ACTOR = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")
        val JWT_PATTERN = Regex("(?i)[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
        val SECRET_MARKERS = setOf("bearer", "authorization", "accesstoken", "refreshtoken", "claimtoken", "password", "clientsecret", "apikey", "secretkey", "privatekey")
        const val INSERT_PENDING = """
            INSERT INTO bd_action_approvals (approval_id,execution_id,desktop_instance_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,requested_at,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
        """
        const val DECIDE = """
            UPDATE bd_action_approvals SET decision=?,decided_at=?,decided_by=?,reason_redacted=?
            WHERE approval_id=? AND execution_id=? AND decision IS NULL
        """
        const val SELECT_COLUMNS = """
            a.approval_id,a.execution_id,a.requested_at,a.expires_at,a.decision,a.decided_at,a.decided_by,a.reason_redacted,
            a.desktop_instance_id AS approval_desktop_instance_id,a.auth_session_id AS approval_auth_session_id,
            a.identity_epoch AS approval_identity_epoch,a.user_id AS approval_user_id,a.tenant_id AS approval_tenant_id,
            a.platform_id AS approval_platform_id,
            e.desktop_instance_id,e.desktop_session_id,e.auth_session_id,e.identity_epoch,e.user_id,e.tenant_id,e.platform_id
        """
        val SELECT_SCOPED = """
            SELECT $SELECT_COLUMNS FROM bd_action_approvals a JOIN bd_action_executions e ON e.execution_id=a.execution_id
            WHERE a.execution_id=? AND e.desktop_instance_id=? AND e.desktop_session_id=? AND e.auth_session_id=?
              AND e.identity_epoch=? AND e.user_id=? AND e.tenant_id=? AND e.platform_id=?
              AND a.desktop_instance_id=e.desktop_instance_id AND a.auth_session_id=e.auth_session_id
              AND a.identity_epoch=e.identity_epoch AND a.user_id=e.user_id AND a.tenant_id=e.tenant_id AND a.platform_id=e.platform_id
        """.trimIndent()
        val SELECT_BY_EXECUTION = "SELECT $SELECT_COLUMNS FROM bd_action_approvals a JOIN bd_action_executions e ON e.execution_id=a.execution_id WHERE a.execution_id=?"
        val SELECT_BY_APPROVAL = "SELECT $SELECT_COLUMNS FROM bd_action_approvals a JOIN bd_action_executions e ON e.execution_id=a.execution_id WHERE a.approval_id=?"
        const val SELECT_PARENT_SCOPE = """
            SELECT desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id
            FROM bd_action_executions WHERE execution_id=?
        """
    }
}
