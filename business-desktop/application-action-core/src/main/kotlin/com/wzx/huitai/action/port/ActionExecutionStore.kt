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
) {
    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

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

/** 动作幂等和终态重放存储端口。 */
interface ActionExecutionStore {
    /** 按 executionId 查询当前精确记录；不存在返回 null。 */
    suspend fun find(executionId: String): ActionExecutionRecord?

    /** 原子比较并创建运行记录。 */
    suspend fun compareAndCreate(record: ActionExecutionRecord): ExecutionCreateResult

    /** 以版本保护写入首个结构化终态。 */
    suspend fun updateTerminal(
        executionId: String,
        expectedVersion: Long,
        terminalState: ActionExecutionState,
        result: ActionResult<JsonElement>,
        completedAt: Instant,
    ): TerminalUpdateResult
}
