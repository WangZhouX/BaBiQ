package com.wzx.huitai.action.port

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * 用于识别同一 execution 是否仍是同一动作输入。
 *
 * @param actionId 动作标识。
 * @param inputFingerprint 已脱敏的稳定输入摘要。
 */
data class ExecutionFingerprint(
    val actionId: String,
    val inputFingerprint: String,
) {
    /** 日志只保留动作标识，隐藏输入摘要。 */
    override fun toString(): String =
        "ExecutionFingerprint(actionId=$actionId, inputFingerprint=[REDACTED])"
}

/**
 * 动作执行的精确持久化记录。
 *
 * @param command 创建执行时冻结的命令。
 * @param fingerprint 动作和输入指纹。
 * @param state 当前执行状态。
 * @param result JSON 安全的精确终态结果。
 * @param createdAt 记录创建时间。
 * @param startedAt 副作用执行开始时间。
 * @param completedAt 进入持久化终态的时间。
 * @param updatedAt 最近更新时间。
 * @param recordVersion 乐观并发版本。
 * @param reconciliation 从结果不确定状态收束时的对账来源。
 * @param successFact 业务成功但普通 JSON 结果不可用时的结构化事实。
 */
data class ActionExecutionRecord(
    val command: ActionCommand,
    val fingerprint: ExecutionFingerprint,
    val state: ActionExecutionState,
    val result: ActionResult<JsonElement>?,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val updatedAt: Instant,
    val recordVersion: Long,
    val reconciliation: ReconciliationProvenance? = null,
    val successFact: ExecutionSuccessFact? = null,
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
            "state=$state, recordVersion=$recordVersion, command=[REDACTED], fingerprint=[REDACTED], " +
            "result=[REDACTED])"

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
 * 业务副作用已成功但 JSON 输出不可用的持久化事实。
 *
 * @param kind 稳定事实类型，目前固定为 OUTPUT_ENCODING_FAILED。
 * @param remoteReference 可用于人工查询的远程引用。
 */
data class ExecutionSuccessFact(
    val kind: String,
    val remoteReference: String? = null,
) {
    init {
        require(kind == OUTPUT_ENCODING_FAILED) { "不支持的成功事实类型" }
    }

    /** 日志保留事实类型并隐藏远程引用。 */
    override fun toString(): String =
        "ExecutionSuccessFact(kind=$kind, remoteReference=[REDACTED])"

    companion object {
        const val OUTPUT_ENCODING_FAILED = "OUTPUT_ENCODING_FAILED"
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
 * @param result 对账确认的精确成功或失败结果。
 * @param completedAt 对账完成时间。
 * @param audit 与最终态和对账来源原子提交的独立对账审计。
 */
data class ReconciliationExecutionUpdate(
    val executionId: String,
    val expectedVersion: Long,
    val result: ActionResult<JsonElement>,
    val completedAt: Instant,
    val audit: ActionAuditDraft,
) {
    init {
        require(audit.executionId == executionId) { "对账审计 executionId 必须一致" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "对账审计必须源自 OUTCOME_UNKNOWN" }
        val terminalState = result.terminalStateOrNull()
        require(terminalState == ActionExecutionState.SUCCEEDED || terminalState == ActionExecutionState.FAILED) {
            "对账结果只能是 Success 或 Failure"
        }
        require(audit.toState == terminalState) { "对账审计目标状态必须与结果一致" }
        require(result.executionId() == executionId) {
            "对账结果 executionId 不匹配：expected=$executionId, actual=${result.executionId()}"
        }
    }
}

/**
 * 对账期间不改变执行状态的原子审计追加。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 仍应处于 OUTCOME_UNKNOWN 的记录版本。
 * @param audit 同状态的对账尝试或未确认结果事件。
 */
data class ReconciliationAuditAppend(
    val executionId: String,
    val expectedVersion: Long,
    val audit: ActionAuditDraft,
) {
    init {
        require(audit.executionId == executionId) { "对账审计 executionId 必须一致" }
        require(audit.fromState == ActionExecutionState.OUTCOME_UNKNOWN) { "对账审计必须源自 OUTCOME_UNKNOWN" }
        require(audit.toState == ActionExecutionState.OUTCOME_UNKNOWN) { "纯对账审计不能改变执行状态" }
    }
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

/** 保持 OUTCOME_UNKNOWN 的原子审计追加结果。 */
sealed interface ReconciliationAuditAppendResult {
    data class Appended(val record: ActionExecutionRecord) : ReconciliationAuditAppendResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationAuditAppendResult
    data class Conflict(val error: ActionError) : ReconciliationAuditAppendResult
}

/** 动作幂等和终态重放存储端口。 */
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

    /** 仅在匹配版本的 OUTCOME_UNKNOWN 上原子追加一次审计，不改变记录版本或结果。 */
    suspend fun appendReconciliationAudit(
        append: ReconciliationAuditAppend,
    ): ReconciliationAuditAppendResult
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
