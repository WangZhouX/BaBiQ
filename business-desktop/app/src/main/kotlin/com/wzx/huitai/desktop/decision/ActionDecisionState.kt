package com.wzx.huitai.desktop.decision

import com.wzx.huitai.action.model.ActionOrigin

/** Compose 决策弹窗所处的单次动作阶段。 */
enum class ActionDecisionPhase {
    CONFIRMATION,
    HIGH_RISK_APPROVAL,
}

/** 已在进入 Compose 状态前完成脱敏的结构化字段变化。 */
data class ActionDecisionDifference(
    val path: String,
    val before: String,
    val after: String,
    val redacted: Boolean,
)

/** Compose 可以观察的单个 execution 决策弹窗公共状态。 */
sealed interface ActionDecisionDialogState {
    val executionId: String
    val decisionId: String
    val phase: ActionDecisionPhase
    val actionTitle: String
    val origin: ActionOrigin
    val summary: String
    val differences: List<ActionDecisionDifference>
    val warnings: List<String>
    val expiresAtEpochMillis: Long
}

/** 普通可逆写或高风险动作执行前的预览确认状态。 */
data class ConfirmationDecisionDialogState(
    override val executionId: String,
    override val decisionId: String,
    override val actionTitle: String,
    override val origin: ActionOrigin,
    override val summary: String,
    override val differences: List<ActionDecisionDifference>,
    override val warnings: List<String>,
    override val expiresAtEpochMillis: Long,
) : ActionDecisionDialogState {
    override val phase: ActionDecisionPhase = ActionDecisionPhase.CONFIRMATION
}

/** 已通过预览确认、仍需用户单次明确批准的高风险审批状态。 */
data class HighRiskApprovalDialogState(
    override val executionId: String,
    override val decisionId: String,
    override val actionTitle: String,
    override val origin: ActionOrigin,
    override val summary: String,
    override val differences: List<ActionDecisionDifference>,
    override val warnings: List<String>,
    override val expiresAtEpochMillis: Long,
    val riskReasons: List<String>,
    val identitySummary: String,
    val remoteSideEffectWarning: String,
) : ActionDecisionDialogState {
    override val phase: ActionDecisionPhase = ActionDecisionPhase.HIGH_RISK_APPROVAL
}

/**
 * 桌面决策弹窗的不可变队列快照。
 *
 * 队列允许多个 execution 独立等待，但 UI 每次只需展示 [activeDialog]，关闭后自动推进下一项。
 */
data class ActionDecisionState(
    val dialogs: List<ActionDecisionDialogState> = emptyList(),
) {
    val activeDialog: ActionDecisionDialogState?
        get() = dialogs.firstOrNull()
}
