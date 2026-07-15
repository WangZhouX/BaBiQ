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
 * @param decisions 初始审批决定，按 executionId 独立消费。
 */
class InMemoryApprovalPort(decisions: List<ActionApproval> = emptyList()) : ActionApprovalPort {
    private val mutex = Mutex()
    private val decisionsByExecution = decisions.associateByTo(mutableMapOf()) { it.executionId }

    /**
     * 与本 demo 适配器的进程/测试生命周期同长，永久阻止同一 execution 注入第二个审批事实。
     * 生产环境由后续 Compose/SQLite 适配器以持久授权事实替换，不在此处清理 tombstone。
     */
    private val terminalExecutionIds = decisionsByExecution.keys.toMutableSet()

    init {
        require(decisionsByExecution.size == decisions.size) { "同一 execution 不能有多个审批决定" }
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
            require(terminalExecutionIds.add(decision.executionId)) { "同一 execution 不能追加第二个审批决定" }
            decisionsByExecution[decision.executionId] = decision
        }
    }

    /** 按 executionId 原子移除一次性决定；不存在时安全拒绝。 */
    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval = mutex.withLock {
        require(preview.executionId == command.executionId) { "审批预览 executionId 不匹配" }
        decisionsByExecution.remove(command.executionId) ?: error("当前 execution 没有待消费审批决定")
    }

    /** 校验单次审批决定具备可审计的非空标识。 */
    private fun validateDecision(decision: ActionApproval) {
        require(decision.approvalId.isNotBlank()) { "审批决定 id 不能为空" }
        require(decision.executionId.isNotBlank()) { "审批 executionId 不能为空" }
    }
}
