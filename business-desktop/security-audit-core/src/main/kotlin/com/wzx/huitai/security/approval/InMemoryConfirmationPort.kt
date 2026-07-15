package com.wzx.huitai.security.approval

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ActionConfirmationPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 测试和框架演示使用的一次性确认队列。
 *
 * 决定只能由对应 execution 消费，不提供会话级或永久放行能力。
 *
 * @param decisions 初始确认决定，按入队顺序消费。
 */
class InMemoryConfirmationPort(decisions: List<ActionConfirmation> = emptyList()) : ActionConfirmationPort {
    private val mutex = Mutex()
    private val queued = ArrayDeque(decisions)
    private val seenExecutionIds = decisions.map { it.executionId }.toMutableSet()

    init {
        require(seenExecutionIds.size == decisions.size) { "同一 execution 不能有多个确认决定" }
        decisions.forEach(::validateDecision)
    }

    /**
     * 追加一个仅供后续单个 execution 消费的确认决定。
     *
     * @param decision 与具体 execution 绑定的决定。
     */
    suspend fun enqueue(decision: ActionConfirmation) {
        mutex.withLock {
            validateDecision(decision)
            require(seenExecutionIds.add(decision.executionId)) { "同一 execution 不能追加第二个确认决定" }
            queued.addLast(decision)
        }
    }

    /** 严格消费队首决定；空队列或 execution 不匹配均拒绝。 */
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation = mutex.withLock {
        require(preview.executionId == command.executionId) { "确认预览 executionId 不匹配" }
        val decision = queued.firstOrNull() ?: error("当前 execution 没有待消费确认决定")
        require(decision.executionId == command.executionId) { "确认决定 executionId 不匹配" }
        queued.removeFirst()
    }

    /** 校验单次确认决定具备可审计的非空标识。 */
    private fun validateDecision(decision: ActionConfirmation) {
        require(decision.decisionId.isNotBlank()) { "确认决定 id 不能为空" }
        require(decision.executionId.isNotBlank()) { "确认 executionId 不能为空" }
    }
}
