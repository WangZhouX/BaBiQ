package com.wzx.huitai.action.port

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionRiskLevel

/**
 * 不低于动作描述符基础风险的评估结果。
 *
 * @param baseRisk 描述符声明的基础风险。
 * @param effectiveRisk 实际执行风险。
 * @param reasons 风险提升或保持原因。
 */
class RiskEvaluation private constructor(
    val baseRisk: ActionRiskLevel,
    val effectiveRisk: ActionRiskLevel,
    val reasons: List<String>,
) {
    init {
        require(effectiveRisk.ordinal >= baseRisk.ordinal) { "有效风险不能低于动作基础风险" }
    }

    /** 日志保留风险等级和原因数量，隐藏具体原因。 */
    override fun toString(): String =
        "RiskEvaluation(baseRisk=$baseRisk, effectiveRisk=$effectiveRisk, reasons=${reasons.size})"

    companion object {
        /** 将策略建议与基础风险取较高值，禁止模型或适配器降低风险。 */
        fun atLeast(
            baseRisk: ActionRiskLevel,
            proposedRisk: ActionRiskLevel,
            reasons: List<String> = emptyList(),
        ): RiskEvaluation = RiskEvaluation(
            baseRisk = baseRisk,
            effectiveRisk = maxOf(baseRisk, proposedRisk, compareBy { it.ordinal }),
            reasons = reasons.toList(),
        )
    }
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
