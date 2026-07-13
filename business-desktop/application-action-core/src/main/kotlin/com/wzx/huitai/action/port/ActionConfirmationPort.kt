package com.wzx.huitai.action.port

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionPreview
import java.time.Instant

/** 单次动作预览确认结果。 */
enum class ConfirmationDecision {
    ACCEPTED,
    REJECTED,
    EXPIRED,
}

/**
 * 与单个 execution 绑定的确认决定。
 *
 * @param decisionId 单次确认决定标识。
 * @param executionId 对应动作执行标识。
 * @param decision 确认结果。
 * @param decidedAt 决定时间。
 * @param reason 已由适配器保存或脱敏的原因。
 */
data class ActionConfirmation(
    val decisionId: String,
    val executionId: String,
    val decision: ConfirmationDecision,
    val decidedAt: Instant,
    val reason: String? = null,
) {
    /** 日志保留关联和决定，隐藏原因。 */
    override fun toString(): String =
        "ActionConfirmation(decisionId=$decisionId, executionId=$executionId, decision=$decision, " +
            "decidedAt=$decidedAt, reason=[REDACTED])"
}

/** 为每次可逆写或高风险动作请求独立预览确认。 */
fun interface ActionConfirmationPort {
    /**
     * 请求当前 execution 的一次性确认。
     *
     * @param command 已冻结身份和页面版本的动作命令。
     * @param preview 无副作用动作预览。
     * @param context 动作执行上下文。
     */
    suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation
}
