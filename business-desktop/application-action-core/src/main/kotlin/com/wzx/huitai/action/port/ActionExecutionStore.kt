package com.wzx.huitai.action.port

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/**
 * 用于识别同一 execution 是否仍绑定同一动作、输入、来源、身份和页面上下文。
 *
 * @param actionId 动作标识。
 * @param actionVersion 创建命令时冻结的动作版本。
 * @param inputFingerprint 已脱敏的稳定输入摘要。
 * @param origin 动作发起来源。
 * @param identityScope 创建命令时冻结的完整身份范围。
 * @param pageId 创建命令时的页面标识。
 * @param contextRevision 创建命令时的上下文版本。
 */
data class ExecutionBinding(
    val actionId: String,
    val actionVersion: Int,
    val inputFingerprint: String,
    val origin: ActionOrigin,
    val identityScope: ActionIdentityScope,
    val pageId: String,
    val contextRevision: Long,
) {
    /** 日志只保留非敏感定位元数据，隐藏输入摘要和完整身份。 */
    override fun toString(): String =
        "ExecutionBinding(actionId=$actionId, actionVersion=$actionVersion, origin=$origin, " +
            "pageId=$pageId, contextRevision=$contextRevision, inputFingerprint=[REDACTED], " +
            "identityScope=[REDACTED])"
}

/**
 * 动作执行的精确持久化记录。
 *
 * @param command 创建执行时冻结的命令。
 * @param binding execution 的完整冻结绑定。
 * @param riskLevel 创建 execution 时冻结的有效风险。
 * @param state 当前执行状态。
 * @param result JSON 安全的精确终态结果。
 * @param createdAt 记录创建时间。
 * @param startedAt 副作用执行开始时间。
 * @param completedAt 进入持久化终态的时间。
 * @param updatedAt 最近更新时间。
 * @param recordVersion 乐观并发版本。
 * @param reconciliation 从结果不确定状态收束时的对账来源。
 * @param successFact 业务成功但普通 JSON 结果不可用时的结构化事实。
 * @param reconciliationClaim 当前持有远程对账权的持久 claim；仅 OUTCOME_UNKNOWN 可非空。
 */
data class ActionExecutionRecord(
    val command: ActionCommand,
    val binding: ExecutionBinding,
    val riskLevel: ActionRiskLevel,
    val state: ActionExecutionState,
    val result: ActionResult<JsonElement>?,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val updatedAt: Instant,
    val recordVersion: Long,
    val reconciliation: ReconciliationProvenance? = null,
    val successFact: ExecutionSuccessFact? = null,
    val reconciliationClaim: ReconciliationClaim? = null,
) {
    init {
        validateExecutionState(
            executionId = command.executionId,
            state = state,
            result = result,
            completedAt = completedAt,
            successFact = successFact,
        )
        require(!updatedAt.isBefore(createdAt)) { "updatedAt 不能早于 createdAt" }
        startedAt?.let {
            require(!it.isBefore(createdAt)) { "startedAt 不能早于 createdAt" }
            require(!updatedAt.isBefore(it)) { "updatedAt 不能早于 startedAt" }
        }
        completedAt?.let {
            require(!it.isBefore(createdAt)) { "completedAt 不能早于 createdAt" }
            startedAt?.let { started ->
                require(!it.isBefore(started)) { "completedAt 不能早于 startedAt" }
            }
            require(!updatedAt.isBefore(it)) { "updatedAt 不能早于 completedAt" }
        }
        reconciliation?.let {
            require(isFinalTerminal) { "对账来源只能附着在成功或失败最终态" }
            require(state == ActionExecutionState.SUCCEEDED || state == ActionExecutionState.FAILED) {
                "对账只能收束为 SUCCEEDED 或 FAILED"
            }
            require(it.sourceRecordVersion < recordVersion) { "对账来源版本必须早于当前版本" }
            require(completedAt == it.reconciledAt) { "对账完成时间必须与终态完成时间一致" }
        }
        successFact?.let {
            require(state == ActionExecutionState.SUCCEEDED) { "成功事实只能附着在 SUCCEEDED" }
            require(result == null) { "输出不可用成功事实不能同时伪造普通成功结果" }
        }
        reconciliationClaim?.let {
            require(state == ActionExecutionState.OUTCOME_UNKNOWN) { "对账 claim 只能附着在 OUTCOME_UNKNOWN" }
            require(!it.claimedAt.isBefore(createdAt)) { "对账 claim 时间不能早于记录创建时间" }
            require(!updatedAt.isBefore(it.claimedAt)) { "updatedAt 不能早于对账 claim 时间" }
        }
    }

    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    val isFinalTerminal: Boolean
        get() = state in FINAL_TERMINAL_STATES

    val needsReconciliation: Boolean
        get() = state == ActionExecutionState.OUTCOME_UNKNOWN

    /** 日志保留执行状态和版本，隐藏命令、指纹与结果。 */
    override fun toString(): String =
        "ActionExecutionRecord(executionId=${command.executionId}, actionId=${command.actionId}, " +
            "state=$state, recordVersion=$recordVersion, command=[REDACTED], binding=[REDACTED], " +
            "result=[REDACTED], reconciliationClaim=[REDACTED])"

    private companion object {
        val TERMINAL_STATES = setOf(
            ActionExecutionState.SUCCEEDED,
            ActionExecutionState.FAILED,
            ActionExecutionState.CANCELED,
            ActionExecutionState.EXPIRED,
            ActionExecutionState.OUTCOME_UNKNOWN,
        )

        val FINAL_TERMINAL_STATES = TERMINAL_STATES - ActionExecutionState.OUTCOME_UNKNOWN
    }
}

/**
 * 结果未知记录的持久对账所有权。
 *
 * @param claimToken 单次 claim 的不可猜测标识，仅后续 release/final mutation 使用。
 * @param ownerId 发起本次对账的本地 owner 标识，用于审计归属，不作为授权主体。
 * @param claimedAt claim 与 attempt 审计原子提交的时间。
 * @param expiresAt claim 租约到期时间；到期后新 owner 可在调用时原子接管。
 */
data class ReconciliationClaim(
    val claimToken: String,
    val ownerId: String,
    val claimedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(claimToken.isNotBlank()) { "对账 claim token 不能为空" }
        require(ownerId.isNotBlank()) { "对账 owner 不能为空" }
        require(!expiresAt.isBefore(claimedAt)) { "对账 claim 到期时间不能早于取得时间" }
    }

    /** 日志只表明 claim 存在，不暴露 token、owner 或租约业务时间。 */
    override fun toString(): String =
        "ReconciliationClaim(claimToken=[REDACTED], ownerId=[REDACTED], " +
            "claimedAt=[REDACTED], expiresAt=[REDACTED])"
}

/**
 * 业务副作用已成功但 JSON 输出不可用的持久化事实。
 *
 * @param kind 稳定事实类型。
 * @param remoteReference 可用于人工查询的远程引用。
 * @param errorCode 输出不可用时持久化的稳定错误码；远程对账成功时为空。
 * @param safeMessage 输出不可用时可安全重放的固定说明；不得包含远程原文或敏感值。
 * @param source 成功事实的稳定来源，供 Bus 区分编码失败和远程对账确认。
 */
data class ExecutionSuccessFact(
    val kind: String,
    val remoteReference: String? = null,
    val errorCode: com.wzx.huitai.action.model.ActionErrorCode? =
        com.wzx.huitai.action.model.ActionErrorCode.PROTOCOL_ERROR,
    val safeMessage: String? = "动作输出不可用",
    val source: String = SOURCE_OUTPUT_ENCODING,
) {
    init {
        require(kind in setOf(OUTPUT_ENCODING_FAILED, RECONCILED_REMOTE_SUCCESS)) { "不支持的成功事实类型" }
        when (kind) {
            OUTPUT_ENCODING_FAILED -> {
                require(errorCode != null) { "输出编码失败事实必须保存错误码" }
                require(!safeMessage.isNullOrBlank()) { "输出编码失败事实必须保存安全说明" }
                require(source == SOURCE_OUTPUT_ENCODING) { "输出编码失败来源不匹配" }
            }
            RECONCILED_REMOTE_SUCCESS -> {
                require(errorCode == null && safeMessage == null) { "远程对账成功不能伪造错误" }
                require(source == SOURCE_RECONCILIATION) { "远程对账成功来源不匹配" }
            }
        }
    }

    /** 日志保留事实类型并隐藏远程引用。 */
    override fun toString(): String =
        "ExecutionSuccessFact(kind=$kind, remoteReference=[REDACTED], errorCode=$errorCode, " +
            "safeMessage=[REDACTED], source=$source)"

    companion object {
        const val OUTPUT_ENCODING_FAILED = "OUTPUT_ENCODING_FAILED"
        const val RECONCILED_REMOTE_SUCCESS = "RECONCILED_REMOTE_SUCCESS"
        const val SOURCE_OUTPUT_ENCODING = "output_encoding"
        const val SOURCE_RECONCILIATION = "reconciliation"
    }
}

/**
 * 已完成对账的结构化来源，用于区分普通最终态和重复对账。
 *
 * @param sourceRecordVersion 被对账的 OUTCOME_UNKNOWN 记录版本。
 * @param reconciledAt 对账完成并写入最终态的时间。
 */
data class ReconciliationProvenance(
    val sourceRecordVersion: Long,
    val reconciledAt: Instant,
) {
    init {
        require(sourceRecordVersion >= 0) { "对账来源版本不能为负数" }
    }
}

/**
 * 将结果不确定状态收束为成功或失败的对账更新。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 期望的结果不确定记录版本。
 * @param claimToken 必须匹配当前持久 claim 的 token。
 * @param result 对账确认的精确失败结果；成功无业务输出时为空。
 * @param successFact 远程对账确认成功但没有业务输出时的持久事实。
 * @param completedAt 对账完成时间。
 * @param audit 与最终态和对账来源原子提交的独立对账审计。
 */
data class ReconciliationExecutionUpdate(
    val executionId: String,
    val expectedVersion: Long,
    val claimToken: String,
    val result: ActionResult<JsonElement>?,
    val successFact: ExecutionSuccessFact? = null,
    val completedAt: Instant,
    val audit: ActionAuditDraft,
) {
    init {
        require(audit.executionId == executionId) { "对账审计 executionId 必须一致" }
        require(claimToken.isNotBlank()) { "最终对账 claim token 不能为空" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "对账审计必须源自 OUTCOME_UNKNOWN" }
        val terminalState = result?.terminalStateOrNull()
        require(terminalState == ActionExecutionState.FAILED || successFact != null) {
            "对账最终态只能是精确 Failure 或远程成功事实"
        }
        successFact?.let {
            require(result == null) { "成功事实不能同时携带普通结果" }
            require(it.kind == ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS) {
                "最终对账成功只能使用 RECONCILED_REMOTE_SUCCESS"
            }
        }
        val expectedState = terminalState ?: ActionExecutionState.SUCCEEDED
        require(audit.toState == expectedState) { "对账审计目标状态必须与结果一致" }
        result?.let {
            require(it.executionId() == executionId) {
                "对账结果 executionId 不匹配：expected=$executionId, actual=${it.executionId()}"
            }
        }
    }
}

/**
 * 原子取得 OUTCOME_UNKNOWN 对账所有权并追加 attempt 审计。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 期望的未 claim 记录版本。
 * @param claimToken 本次 claim token。
 * @param ownerId 本次对账 owner。
 * @param now 本次 claim 原子判断使用的当前时间，同时作为新 claim 的 claimedAt。
 * @param leaseDuration 有界租约时长，必须为正数。
 * @param audit 与 claim 原子提交的 reconciliation_attempt 审计。
 */
data class ReconciliationClaimRequest(
    val executionId: String,
    val expectedVersion: Long,
    val claimToken: String,
    val ownerId: String,
    val now: Instant,
    val leaseDuration: Duration,
    val audit: ActionAuditDraft,
) {
    val expiresAt: Instant = now.plus(leaseDuration)

    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "对账 claim 租约必须为正时长" }
        ReconciliationClaim(claimToken, ownerId, now, expiresAt)
        require(audit.executionId == executionId) { "claim 审计 executionId 必须一致" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "claim 审计必须源自 OUTCOME_UNKNOWN" }
        require(audit.toState == ActionExecutionState.OUTCOME_UNKNOWN) { "claim 不能改变执行状态" }
        require(audit.type == "reconciliation_attempt") { "claim 必须提交 reconciliation_attempt 审计" }
    }

    /** 日志隐藏 claim token、owner 和审计载荷。 */
    override fun toString(): String =
        "ReconciliationClaimRequest(executionId=$executionId, expectedVersion=$expectedVersion, " +
            "claimToken=[REDACTED], ownerId=[REDACTED], now=[REDACTED], " +
            "leaseDuration=[REDACTED], audit=[REDACTED])"
}

/**
 * 原子续租当前 OUTCOME_UNKNOWN 对账 claim 并追加脱敏 heartbeat 审计。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 当前 claim 的精确记录版本。
 * @param claimToken 必须匹配持久 claim 的 token。
 * @param now 续租原子判断使用的当前时间。
 * @param leaseDuration 从 now 开始计算的新租约时长。
 * @param audit 与续租原子提交的 reconciliation_claim_renewed 审计。
 */
data class ReconciliationRenewRequest(
    val executionId: String,
    val expectedVersion: Long,
    val claimToken: String,
    val now: Instant,
    val leaseDuration: Duration,
    val audit: ActionAuditDraft,
) {
    val expiresAt: Instant = now.plus(leaseDuration)

    init {
        require(claimToken.isNotBlank()) { "renew claim token 不能为空" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "对账 renew 租约必须为正时长" }
        require(audit.executionId == executionId) { "renew 审计 executionId 必须一致" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "renew 审计必须源自 OUTCOME_UNKNOWN" }
        require(audit.toState == ActionExecutionState.OUTCOME_UNKNOWN) { "renew 不能改变执行状态" }
        require(audit.type == "reconciliation_claim_renewed") {
            "renew 必须提交 reconciliation_claim_renewed 审计"
        }
    }

    /** 日志隐藏 claim token、租约业务时间和审计载荷。 */
    override fun toString(): String =
        "ReconciliationRenewRequest(executionId=$executionId, expectedVersion=$expectedVersion, " +
            "claimToken=[REDACTED], now=[REDACTED], leaseDuration=[REDACTED], audit=[REDACTED])"
}

/**
 * 原子释放未收束对账 claim 并追加 result 审计。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 当前已 claim 记录版本。
 * @param claimToken 必须匹配持久 claim 的 token。
 * @param releasedAt 释放时间。
 * @param audit 与 release 原子提交的 reconciliation_result 审计。
 */
data class ReconciliationReleaseRequest(
    val executionId: String,
    val expectedVersion: Long,
    val claimToken: String,
    val releasedAt: Instant,
    val audit: ActionAuditDraft,
) {
    init {
        require(claimToken.isNotBlank()) { "release claim token 不能为空" }
        require(audit.executionId == executionId) { "release 审计 executionId 必须一致" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "release 审计必须源自 OUTCOME_UNKNOWN" }
        require(audit.toState == ActionExecutionState.OUTCOME_UNKNOWN) { "release 不能改变执行状态" }
        require(audit.type == "reconciliation_result") { "release 必须提交 reconciliation_result 审计" }
    }

    /** 日志隐藏 claim token 和审计载荷。 */
    override fun toString(): String =
        "ReconciliationReleaseRequest(executionId=$executionId, expectedVersion=$expectedVersion, " +
            "claimToken=[REDACTED], releasedAt=$releasedAt, audit=[REDACTED])"
}

/**
 * 原子提交执行状态和同一迁移审计的乐观命令。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 期望的当前记录版本。
 * @param state 目标状态。
 * @param result 普通终态结果，非终态必须为空。
 * @param successFact 输出不可用的成功事实。
 * @param updatedAt 状态更新时间。
 * @param startedAt 首次进入执行状态的时间。
 * @param completedAt 进入终态的时间。
 * @param audit 与状态变更原子提交的审计草稿。
 */
data class ExecutionTransition(
    val executionId: String,
    val expectedVersion: Long,
    val state: ActionExecutionState,
    val result: ActionResult<JsonElement>? = null,
    val successFact: ExecutionSuccessFact? = null,
    val updatedAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val audit: ActionAuditDraft,
) {
    init {
        require(audit.executionId == executionId) { "迁移审计 executionId 必须一致" }
        require(audit.toState == state) { "迁移审计目标状态必须一致" }
        validateExecutionState(executionId, state, result, completedAt, successFact)
        require(startedAt == null || state == ActionExecutionState.EXECUTING) {
            "startedAt 只能在 EXECUTING 状态写入"
        }
    }

    /** 日志隐藏结果、成功事实和审计草稿，只保留并发与状态元数据。 */
    override fun toString(): String =
        "ExecutionTransition(executionId=$executionId, expectedVersion=$expectedVersion, state=$state, " +
            "result=[REDACTED], successFact=[REDACTED], updatedAt=$updatedAt, startedAt=$startedAt, " +
            "completedAt=$completedAt, audit=[REDACTED])"
}

/** 原子创建执行记录的结果。 */
sealed interface ExecutionCreateResult {
    data class Created(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class ExistingRunning(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class ExistingTerminal(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class Conflict(val error: ActionError) : ExecutionCreateResult
}

/** 原子状态与审计迁移结果。 */
sealed interface ExecutionTransitionResult {
    data class Updated(val record: ActionExecutionRecord) : ExecutionTransitionResult
    data class ExistingTerminal(val record: ActionExecutionRecord) : ExecutionTransitionResult
    data class Conflict(val error: ActionError) : ExecutionTransitionResult
}

/** 结果不确定记录的对账更新结果。 */
sealed interface ReconciliationUpdateResult {
    data class Updated(val record: ActionExecutionRecord) : ReconciliationUpdateResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationUpdateResult
    data class Conflict(val error: ActionError) : ReconciliationUpdateResult
}

/** 持久对账 claim 的原子结果。 */
sealed interface ReconciliationClaimResult {
    data class Claimed(val record: ActionExecutionRecord) : ReconciliationClaimResult
    data class ExistingClaim(val record: ActionExecutionRecord) : ReconciliationClaimResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationClaimResult
    data class Conflict(val error: ActionError) : ReconciliationClaimResult
}

/** 当前对账 claim 原子续租的结果。 */
sealed interface ReconciliationRenewResult {
    data class Renewed(val record: ActionExecutionRecord) : ReconciliationRenewResult
    data class ExistingClaim(val record: ActionExecutionRecord) : ReconciliationRenewResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationRenewResult
    data class Conflict(val error: ActionError) : ReconciliationRenewResult
}

/** 未收束对账 release 的原子结果。 */
sealed interface ReconciliationReleaseResult {
    data class Released(val record: ActionExecutionRecord) : ReconciliationReleaseResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationReleaseResult
    data class Conflict(val error: ActionError) : ReconciliationReleaseResult
}

/** 动作幂等和终态重放存储端口。 */
/**
 * Executes identity-scoped reads without exposing an unscoped existence probe to protocol adapters.
 * Implementations must match the execution identifier and the complete identity scope atomically.
 */
interface ScopedActionExecutionQuery {
    suspend fun find(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionExecutionRecord?

    suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord>
}

/**
 * 为重启后的精确重放提供候选命令 hydration，而不要求所有执行存储都实现持久缓存。
 *
 * 实现必须先在自己的原子事实源中比较 executionId 与完整 [ExecutionBinding]；只有完全匹配时
 * 才能把 [candidateCommand] 的原始输入叠加到返回快照，冲突时返回未 hydration 的持久记录。
 */
interface ActionExecutionReplayHydrator {
    suspend fun findAndHydrateReplayCandidate(
        candidateCommand: ActionCommand,
        expectedBinding: ExecutionBinding,
    ): ReplayHydrationResult
}

/** 候选重放 hydration 的穷举结果，Coordinator 仍会独立复核 binding。 */
sealed interface ReplayHydrationResult {
    data class Matching(val record: ActionExecutionRecord) : ReplayHydrationResult
    data class BindingMismatch(val record: ActionExecutionRecord) : ReplayHydrationResult
    data object Missing : ReplayHydrationResult
}

interface ActionExecutionStore {
    /** 按 executionId 查询当前精确记录；不存在返回 null。 */
    suspend fun find(executionId: String): ActionExecutionRecord?

    /** 原子创建首条执行记录并追加由适配器分配 sequence 的首个审计。 */
    suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult

    /** 原子提交乐观状态变化和同一迁移审计。 */
    suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult

    /** 仅将当前 OUTCOME_UNKNOWN 记录和独立对账审计原子收束为成功或失败。 */
    suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult

    /** 原子取得跨进程唯一对账所有权并提交 attempt 审计。 */
    suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult

    /** 仅由当前 token/version owner 原子续租 claim 并提交 heartbeat 审计。 */
    suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult

    /** 原子释放未收束 claim 并提交 result 审计。 */
    suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult
}

/** 校验记录或更新中的状态、结果、执行标识和完成时间必须一致。 */
private fun validateExecutionState(
    executionId: String,
    state: ActionExecutionState,
    result: ActionResult<JsonElement>?,
    completedAt: Instant?,
    successFact: ExecutionSuccessFact?,
) {
    val terminalState = result?.terminalStateOrNull()
    if (state in TERMINAL_STATES) {
        if (successFact == null) {
            require(result != null) { "终态记录必须包含结果或成功事实" }
            require(terminalState != null) { "Preview 或 ApprovalRequired 不能作为持久化终态结果" }
            require(state == terminalState) { "终态与结果类型不匹配：state=$state, resultState=$terminalState" }
            require(result.executionId() == executionId) {
                "结果 executionId 不匹配：expected=$executionId, actual=${result.executionId()}"
            }
        } else {
            require(state == ActionExecutionState.SUCCEEDED) { "成功事实只能对应 SUCCEEDED" }
            require(result == null) { "成功事实不能与普通结果同时存在" }
        }
        require(completedAt != null) { "终态记录必须包含 completedAt" }
    } else {
        require(result == null) { "非终态记录不能包含结果" }
        require(successFact == null) { "非终态记录不能包含成功事实" }
        require(completedAt == null) { "非终态记录不能包含 completedAt" }
    }
}

/** 穷举结果类型并返回其唯一允许的终态；中间结果没有持久化终态。 */
private fun ActionResult<JsonElement>.terminalStateOrNull(): ActionExecutionState? = when (this) {
    is ActionResult.Preview -> null
    is ActionResult.ApprovalRequired -> null
    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
    is ActionResult.Failure -> ActionExecutionState.FAILED
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
}

/** 穷举提取各结果携带的 executionId。 */
private fun ActionResult<JsonElement>.executionId(): String = when (this) {
    is ActionResult.Preview -> preview.executionId
    is ActionResult.ApprovalRequired -> executionId
    is ActionResult.Success -> executionId
    is ActionResult.Failure -> executionId
    is ActionResult.Canceled -> executionId
    is ActionResult.Expired -> executionId
    is ActionResult.OutcomeUnknown -> executionId
}

private val TERMINAL_STATES = setOf(
    ActionExecutionState.SUCCEEDED,
    ActionExecutionState.FAILED,
    ActionExecutionState.CANCELED,
    ActionExecutionState.EXPIRED,
    ActionExecutionState.OUTCOME_UNKNOWN,
)
