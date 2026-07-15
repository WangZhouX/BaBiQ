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
 * @param decisions 初始确认决定，按 executionId 独立消费。
 */
class InMemoryConfirmationPort(decisions: List<ActionConfirmation> = emptyList()) : ActionConfirmationPort {
    private val mutex = Mutex()
    private val decisionsByExecution = decisions.associateByTo(mutableMapOf()) { it.executionId }

    /**
     * 与本 demo 适配器的进程/测试生命周期同长，永久阻止同一 execution 注入第二个确认事实。
     * 生产环境由后续 Compose/SQLite 适配器以持久确认事实替换，不在此处清理 tombstone。
     */
    private val terminalExecutionIds = decisionsByExecution.keys.toMutableSet()

    init {
        require(decisionsByExecution.size == decisions.size) { "同一 execution 不能有多个确认决定" }
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
            require(terminalExecutionIds.add(decision.executionId)) { "同一 execution 不能追加第二个确认决定" }
            decisionsByExecution[decision.executionId] = decision
        }
    }

    /** 按 executionId 原子移除一次性决定；不存在时安全拒绝。 */
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation = mutex.withLock {
        require(preview.executionId == command.executionId) { "确认预览 executionId 不匹配" }
        decisionsByExecution.remove(command.executionId) ?: error("当前 execution 没有待消费确认决定")
    }

    /** 校验单次确认决定具备可审计的非空标识。 */
    private fun validateDecision(decision: ActionConfirmation) {
        require(decision.decisionId.isNotBlank()) { "确认决定 id 不能为空" }
        require(decision.executionId.isNotBlank()) { "确认 executionId 不能为空" }
    }
}
