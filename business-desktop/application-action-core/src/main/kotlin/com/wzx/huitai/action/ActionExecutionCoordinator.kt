package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionFingerprint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 同 execution 的进程内串行边界；持久存储仍负责跨进程唯一所有权。 */
internal interface ActionExecutionLockScope {
    suspend fun <T> withLock(executionId: String, block: suspend () -> T): T
}

/**
 * 固定大小分片锁不创建或删除单 execution entry，因此没有引用计数 ABA，也不会随历史 ID 增长。
 * hash 使用无符号取模，包含 Int.MIN_VALUE 在内的负 hash 都能落到有效分片；碰撞只降低并发度，不破坏互斥。
 */
internal class StripedActionExecutionLockScope(
    val stripeCount: Int = DEFAULT_STRIPE_COUNT,
) : ActionExecutionLockScope {
    private val stripes: Array<Mutex>

    init {
        require(stripeCount > 0) { "分片锁数量必须为正数" }
        stripes = Array(stripeCount) { Mutex() }
    }

    override suspend fun <T> withLock(executionId: String, block: suspend () -> T): T {
        val index = executionId.hashCode().toUInt().mod(stripeCount.toUInt()).toInt()
        return stripes[index].withLock { block() }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
    }
}

/**
 * 在任何动作解码或副作用前取得 execution 的唯一持久化所有权。
 *
 * compare-and-create 仍由持久化端口原子完成；本协调器只负责生成稳定指纹并把存储结果翻译成
 * Bus 可穷举处理的分支，避免进程内先查后写造成 TOCTOU。
 */
class ActionExecutionCoordinator internal constructor(
    private val executionStore: ActionExecutionStore,
    private val clock: ActionClock,
    private val lockScope: ActionExecutionLockScope = sharedLockScope,
) {
    /** 生产装配共享固定分片锁；测试可通过 internal 构造注入隔离锁域模拟跨进程。 */
    constructor(
        executionStore: ActionExecutionStore,
        clock: ActionClock,
    ) : this(executionStore, clock, sharedLockScope)
    /**
     * 原子创建新执行，或返回同 execution 的精确现状。
     *
     * @param command 用户点击或 Agent 调用冻结的动作命令，原始输入只参与本地摘要计算，不写入审计载荷。
     */
    suspend fun begin(command: ActionCommand): ActionExecutionStart {
        return serialized(command.executionId) {
            val now = clock.now()
            val record = ActionExecutionRecord(
                command = command,
                fingerprint = fingerprint(command),
                state = ActionExecutionState.VALIDATING,
                result = null,
                createdAt = now,
                updatedAt = now,
                recordVersion = 1,
            )
            val audit = ActionAuditDraft(
                executionId = command.executionId,
                fromState = ActionExecutionState.RECEIVED,
                toState = ActionExecutionState.VALIDATING,
                type = command.origin.name.lowercase(),
                redactedPayload = JsonObject(emptyMap()),
                actorId = null,
                occurredAt = now,
            )
            when (val created = executionStore.compareAndCreate(record, audit)) {
                is ExecutionCreateResult.Created -> ActionExecutionStart.New(created.record)
                is ExecutionCreateResult.ExistingRunning -> ActionExecutionStart.ExistingRunning(created.record)
                is ExecutionCreateResult.ExistingTerminal -> if (created.record.needsReconciliation) {
                    ActionExecutionStart.NeedsReconciliation(created.record)
                } else {
                    ActionExecutionStart.ExistingTerminal(created.record)
                }
                is ExecutionCreateResult.Conflict -> ActionExecutionStart.Conflict(created.error)
            }
        }
    }

    /**
     * 串行同 execution 的完整协调片段。
     *
     * @param executionId 需要保护的执行标识。
     * @param block 临界区内仍必须调用持久化原子操作或重新读取事实，不能用本锁替代数据库约束。
     */
    suspend fun <T> serialized(executionId: String, block: suspend () -> T): T =
        withExecutionLock(executionId, block)

    /** 基于动作标识和规范 JSON 生成稳定 SHA-256 摘要。 */
    private fun fingerprint(command: ActionCommand): ExecutionFingerprint {
        val canonical = command.input.canonicalJson()
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${command.actionId}\n$canonical".toByteArray(StandardCharsets.UTF_8))
        return ExecutionFingerprint(command.actionId, bytes.joinToString("") { "%02x".format(it) })
    }

    /** 同 execution 的进程内短锁防止薄 fake/adapter 暴露竞争，持久层仍负责最终原子裁决。 */
    private suspend fun <T> withExecutionLock(executionId: String, block: suspend () -> T): T {
        return lockScope.withLock(executionId, block)
    }

    private companion object {
        val sharedLockScope: ActionExecutionLockScope = StripedActionExecutionLockScope()
    }
}

/** execution 幂等协调的完整分支。 */
sealed interface ActionExecutionStart {
    data class New(val record: ActionExecutionRecord) : ActionExecutionStart
    data class ExistingRunning(val record: ActionExecutionRecord) : ActionExecutionStart
    data class ExistingTerminal(val record: ActionExecutionRecord) : ActionExecutionStart
    data class NeedsReconciliation(val record: ActionExecutionRecord) : ActionExecutionStart
    data class Conflict(val error: com.wzx.huitai.action.model.ActionError) : ActionExecutionStart
}

/** 对对象键排序，对数组保序，确保等价 JSON 在不同构造顺序下得到同一摘要。 */
private fun JsonElement.canonicalJson(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> toString()
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        "${JsonPrimitive(it.key)}:${it.value.canonicalJson()}"
    }
}
