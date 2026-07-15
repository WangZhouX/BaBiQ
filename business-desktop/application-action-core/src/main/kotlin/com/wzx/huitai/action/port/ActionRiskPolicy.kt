package com.wzx.huitai.action.port

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionRiskLevel

/** 风险策略对当前 execution 的明确执行决定。 */
enum class RiskDecision {
    ALLOW,
    DENY,
}

/**
 * 不低于动作描述符基础风险的评估结果。
 *
 * @param baseRisk 描述符声明的基础风险。
 * @param effectiveRisk 实际执行风险。
 * @param decision 是否允许进入预览、审批或执行链。
 * @param reasons 风险提升或保持原因。
 */
class RiskEvaluation private constructor(
    val baseRisk: ActionRiskLevel,
    val effectiveRisk: ActionRiskLevel,
    val decision: RiskDecision,
    val reasons: List<String>,
) {
    init {
        require(effectiveRisk.severity() >= baseRisk.severity()) { "有效风险不能低于动作基础风险" }
    }

    val isAllowed: Boolean
        get() = decision == RiskDecision.ALLOW

    val isDenied: Boolean
        get() = decision == RiskDecision.DENY

    /** 日志保留风险等级和原因数量，隐藏具体原因。 */
    override fun toString(): String =
        "RiskEvaluation(baseRisk=$baseRisk, effectiveRisk=$effectiveRisk, decision=$decision, " +
            "reasons=${reasons.size})"

    companion object {
        /** 将策略建议与基础风险取较高值，禁止模型或适配器降低风险。 */
        fun atLeast(
            baseRisk: ActionRiskLevel,
            proposedRisk: ActionRiskLevel,
            reasons: List<String> = emptyList(),
        ): RiskEvaluation = RiskEvaluation(
            baseRisk = baseRisk,
            effectiveRisk = if (proposedRisk.severity() >= baseRisk.severity()) proposedRisk else baseRisk,
            decision = RiskDecision.ALLOW,
            reasons = reasons.toList(),
        )

        /** 明确拒绝当前 execution，同时保持不可降级的风险等级。 */
        fun deny(
            baseRisk: ActionRiskLevel,
            proposedRisk: ActionRiskLevel = ActionRiskLevel.HIGH_RISK,
            reasons: List<String>,
        ): RiskEvaluation = RiskEvaluation(
            baseRisk = baseRisk,
            effectiveRisk = if (proposedRisk.severity() >= baseRisk.severity()) proposedRisk else baseRisk,
            decision = RiskDecision.DENY,
            reasons = reasons.toList(),
        )
    }
}

/** 使用稳定业务顺序比较风险，避免枚举声明顺序改变语义。 */
private fun ActionRiskLevel.severity(): Int = when (this) {
    ActionRiskLevel.READ_ONLY -> 0
    ActionRiskLevel.REVERSIBLE_WRITE -> 1
    ActionRiskLevel.HIGH_RISK -> 2
}

/** 桌面动作风险评估策略。 */
fun interface ActionRiskPolicy {
    /**
     * 评估当前动作风险；返回值必须通过 [RiskEvaluation.atLeast] 保证不降级。
     *
     * @param descriptor 动作基础风险和元数据。
     * @param command 当前动作命令。
     * @param context 冻结的执行上下文。
     */
    fun evaluate(
        descriptor: ActionDescriptor,
        command: ActionCommand,
        context: ActionContext,
    ): RiskEvaluation
}
