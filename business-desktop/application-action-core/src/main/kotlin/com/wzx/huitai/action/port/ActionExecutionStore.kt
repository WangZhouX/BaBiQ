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
) {
    init {
        validateExecutionState(
            executionId = command.executionId,
            state = state,
            result = result,
            completedAt = completedAt,
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
 * 首次写入终态所需的完整结构化更新。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 期望的当前记录版本。
 * @param terminalState 待写入的终态。
 * @param result 与终态严格对应的精确结果。
 * @param completedAt 终态完成时间。
 */
data class TerminalExecutionUpdate(
    val executionId: String,
    val expectedVersion: Long,
    val terminalState: ActionExecutionState,
    val result: ActionResult<JsonElement>,
    val completedAt: Instant,
) {
    init {
        validateExecutionState(executionId, terminalState, result, completedAt)
    }
}

/**
 * 将结果不确定状态收束为成功或失败的对账更新。
 *
 * @param executionId 动作执行标识。
 * @param expectedVersion 期望的结果不确定记录版本。
 * @param result 对账确认的精确成功或失败结果。
 * @param completedAt 对账完成时间。
 */
data class ReconciliationExecutionUpdate(
    val executionId: String,
    val expectedVersion: Long,
    val result: ActionResult<JsonElement>,
    val completedAt: Instant,
) {
    init {
        val terminalState = result.terminalStateOrNull()
        require(terminalState == ActionExecutionState.SUCCEEDED || terminalState == ActionExecutionState.FAILED) {
            "对账结果只能是 Success 或 Failure"
        }
        require(result.executionId() == executionId) {
            "对账结果 executionId 不匹配：expected=$executionId, actual=${result.executionId()}"
        }
    }
}

/** 原子创建执行记录的结果。 */
sealed interface ExecutionCreateResult {
    data class Created(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class ExistingRunning(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class ExistingTerminal(val record: ActionExecutionRecord) : ExecutionCreateResult
    data class Conflict(val error: ActionError) : ExecutionCreateResult
}

/** 首个终态更新结果。 */
sealed interface TerminalUpdateResult {
    data class Updated(val record: ActionExecutionRecord) : TerminalUpdateResult
    data class ExistingTerminal(val record: ActionExecutionRecord) : TerminalUpdateResult
    data class Conflict(val error: ActionError) : TerminalUpdateResult
}

/** 结果不确定记录的对账更新结果。 */
sealed interface ReconciliationUpdateResult {
    data class Updated(val record: ActionExecutionRecord) : ReconciliationUpdateResult
    data class ExistingFinal(val record: ActionExecutionRecord) : ReconciliationUpdateResult
    data class Conflict(val error: ActionError) : ReconciliationUpdateResult
}

/** 动作幂等和终态重放存储端口。 */
interface ActionExecutionStore {
    /** 按 executionId 查询当前精确记录；不存在返回 null。 */
    suspend fun find(executionId: String): ActionExecutionRecord?

    /** 原子比较并创建运行记录。 */
    suspend fun compareAndCreate(record: ActionExecutionRecord): ExecutionCreateResult

    /** 以版本保护写入首个结构化终态。 */
    suspend fun updateTerminal(update: TerminalExecutionUpdate): TerminalUpdateResult

    /** 仅将当前 OUTCOME_UNKNOWN 记录原子收束为成功或失败。 */
    suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult
}

/** 校验记录或更新中的状态、结果、执行标识和完成时间必须一致。 */
private fun validateExecutionState(
    executionId: String,
    state: ActionExecutionState,
    result: ActionResult<JsonElement>?,
    completedAt: Instant?,
) {
    val terminalState = result?.terminalStateOrNull()
    if (state in TERMINAL_STATES) {
        require(result != null) { "终态记录必须包含结果" }
        require(terminalState != null) { "Preview 或 ApprovalRequired 不能作为持久化终态结果" }
        require(state == terminalState) { "终态与结果类型不匹配：state=$state, resultState=$terminalState" }
        require(result.executionId() == executionId) {
            "结果 executionId 不匹配：expected=$executionId, actual=${result.executionId()}"
        }
        require(completedAt != null) { "终态记录必须包含 completedAt" }
    } else {
        require(result == null) { "非终态记录不能包含结果" }
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
