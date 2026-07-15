package com.wzx.huitai.security.risk

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionRiskPolicy
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.serialization.json.JsonObject

/**
 * 通用桌面动作的默认风险策略。
 *
 * 该策略无状态，只根据冻结描述符、命令和上下文提升风险；任何未知操作都按高风险处理，
 * 避免新动作在未配置安全语义时直接执行。
 */
class DefaultActionRiskPolicy : ActionRiskPolicy {
    /**
     * 评估动作的实际风险，结果永远不会低于描述符基础风险。
     *
     * @param descriptor 已注册动作描述符。
     * @param command 当前冻结动作命令。
     * @param context 当前冻结执行上下文；默认策略不读取身份和值。
     */
    override fun evaluate(
        descriptor: ActionDescriptor,
        command: ActionCommand,
        context: ActionContext,
    ): RiskEvaluation {
        val operation = descriptor.target.operation.trim()
        val reasons = mutableListOf<String>()
        val proposed = when {
            HIGH_RISK_OPERATION.containsMatchIn(operation) -> {
                reasons += HIGH_RISK_OPERATION_REASON
                ActionRiskLevel.HIGH_RISK
            }
            operation.lowercase() !in KNOWN_OPERATIONS -> {
                reasons += UNKNOWN_OPERATION_REASON
                ActionRiskLevel.HIGH_RISK
            }
            operation.lowercase() in WRITE_OPERATIONS && command.input.containsSensitiveField() -> {
                reasons += SENSITIVE_WRITE_REASON
                ActionRiskLevel.REVERSIBLE_WRITE
            }
            operation.lowercase() in WRITE_OPERATIONS -> ActionRiskLevel.REVERSIBLE_WRITE
            else -> ActionRiskLevel.READ_ONLY
        }
        return RiskEvaluation.atLeast(descriptor.riskLevel, proposed, reasons)
    }

    /** 递归检查输入字段名，避免仅检查顶层时漏掉嵌套敏感写入。 */
    private fun JsonObject.containsSensitiveField(): Boolean = entries.any { (key, value) ->
        SENSITIVE_FIELD.containsMatchIn(key) || (value as? JsonObject)?.containsSensitiveField() == true
    }

    private companion object {
        const val UNKNOWN_OPERATION_REASON = "UNKNOWN_OPERATION"
        const val SENSITIVE_WRITE_REASON = "SENSITIVE_WRITE"
        const val HIGH_RISK_OPERATION_REASON = "HIGH_RISK_OPERATION"

        val READ_OPERATIONS = setOf("get", "list", "query", "search", "view", "load", "preview", "refresh")
        val WRITE_OPERATIONS = setOf("save", "update", "edit", "fill", "patch", "create", "upload")
        val KNOWN_OPERATIONS = READ_OPERATIONS + WRITE_OPERATIONS
        val HIGH_RISK_OPERATION = Regex(
            pattern = "(?:^|[._\\-\\s])(?:submit|send|delete)(?:$|[A-Z0-9._\\-\\s])|提交|发送|删除",
            option = RegexOption.IGNORE_CASE,
        )
        val SENSITIVE_FIELD = Regex(
            "(?:^|[._\\-])(?:password|passcode|token|refresh[_-]?token|secret|api[_-]?key|private[_-]?key)(?:$|[._\\-])",
            RegexOption.IGNORE_CASE,
        )
    }
}
