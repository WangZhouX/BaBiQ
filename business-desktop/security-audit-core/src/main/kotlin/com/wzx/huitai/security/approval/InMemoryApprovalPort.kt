package com.wzx.huitai.security.approval

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 测试和框架演示使用的一次性高风险审批队列。
 *
 * 每条审批严格绑定一个 execution，拒绝和过期决定消费后不能再次改变。
 *
 * @param decisions 初始审批决定，按入队顺序消费。
 */
class InMemoryApprovalPort(decisions: List<ActionApproval> = emptyList()) : ActionApprovalPort {
    private val mutex = Mutex()
    private val queued = ArrayDeque(decisions)
    private val seenExecutionIds = decisions.map { it.executionId }.toMutableSet()

    init {
        require(seenExecutionIds.size == decisions.size) { "同一 execution 不能有多个审批决定" }
        decisions.forEach(::validateDecision)
    }

    /**
     * 追加一个仅供后续单个 execution 消费的审批决定。
     *
     * @param decision 与具体 execution 绑定的决定。
     */
    suspend fun enqueue(decision: ActionApproval) {
        mutex.withLock {
            validateDecision(decision)
            require(seenExecutionIds.add(decision.executionId)) { "同一 execution 不能追加第二个审批决定" }
            queued.addLast(decision)
        }
    }

    /** 严格消费队首决定；空队列或 execution 不匹配均拒绝。 */
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval = mutex.withLock {
        require(preview.executionId == command.executionId) { "审批预览 executionId 不匹配" }
        val decision = queued.firstOrNull() ?: error("当前 execution 没有待消费审批决定")
        require(decision.executionId == command.executionId) { "审批决定 executionId 不匹配" }
        queued.removeFirst()
    }

    /** 校验单次审批决定具备可审计的非空标识。 */
    private fun validateDecision(decision: ActionApproval) {
        require(decision.approvalId.isNotBlank()) { "审批决定 id 不能为空" }
        require(decision.executionId.isNotBlank()) { "审批 executionId 不能为空" }
    }
}
