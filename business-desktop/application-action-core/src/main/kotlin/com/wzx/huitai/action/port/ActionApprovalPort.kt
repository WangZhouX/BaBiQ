package com.wzx.huitai.action.port

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import java.time.Instant

/** 单次高风险动作审批结果。 */
enum class ApprovalDecision {
    APPROVED,
    DENIED,
    EXPIRED,
}

/**
 * 与单个 execution 绑定的高风险审批决定。
 *
 * @param approvalId 单次审批标识。
 * @param executionId 对应动作执行标识。
 * @param decision 审批结果。
 * @param decidedAt 决定时间。
 * @param decidedBy 审批人标识。
 * @param reason 已由适配器保存或脱敏的原因。
 */
data class ActionApproval(
    val approvalId: String,
    val executionId: String,
    val decision: ApprovalDecision,
    val decidedAt: Instant,
    val decidedBy: String? = null,
    val reason: String? = null,
) {
    /** 日志保留关联和决定，隐藏审批人及原因。 */
    override fun toString(): String =
        "ActionApproval(approvalId=$approvalId, executionId=$executionId, decision=$decision, " +
            "decidedAt=$decidedAt, decidedBy=[REDACTED], reason=[REDACTED])"
}

/** 为已确认的单次高风险动作请求独立审批。 */
fun interface ActionApprovalPort {
    /**
     * 请求当前 execution 的一次性高风险审批，不代表任何会话级授权。
     *
     * @param command 已完成预览确认的高风险动作命令。
     * @param preview 已被用户接受的动作预览。
     * @param riskEvaluation 不低于描述符基础风险的评估结果。
     * @param context 动作执行上下文。
     */
    suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval
}
