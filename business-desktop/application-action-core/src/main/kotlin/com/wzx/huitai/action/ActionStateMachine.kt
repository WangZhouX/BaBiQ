package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionRiskLevel

/** 状态迁移判断结果。 */
sealed interface ActionTransitionResult {
    /** 当前迁移属于显式允许矩阵。 */
    data object Allowed : ActionTransitionResult

    /** 当前迁移不属于允许矩阵。 */
    data class Rejected(val error: ActionError) : ActionTransitionResult
}

/** 桌面动作生命周期状态机。 */
object ActionStateMachine {
    private val commonTransitions = setOf(
        ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
        ActionExecutionState.RECEIVED to ActionExecutionState.CANCELED,
        ActionExecutionState.RECEIVED to ActionExecutionState.EXPIRED,
        ActionExecutionState.VALIDATING to ActionExecutionState.FAILED,
        ActionExecutionState.VALIDATING to ActionExecutionState.CANCELED,
        ActionExecutionState.VALIDATING to ActionExecutionState.EXPIRED,
        ActionExecutionState.PREVIEWED to ActionExecutionState.FAILED,
        ActionExecutionState.PREVIEWED to ActionExecutionState.CANCELED,
        ActionExecutionState.PREVIEWED to ActionExecutionState.EXPIRED,
        ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.FAILED,
        ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.CANCELED,
        ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.EXPIRED,
        ActionExecutionState.EXECUTING to ActionExecutionState.SUCCEEDED,
        ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
        ActionExecutionState.EXECUTING to ActionExecutionState.CANCELED,
        ActionExecutionState.EXECUTING to ActionExecutionState.EXPIRED,
        ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
        ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.SUCCEEDED,
        ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.FAILED,
    )

    private val riskTransitions = mapOf(
        ActionRiskLevel.READ_ONLY to setOf(
            ActionExecutionState.VALIDATING to ActionExecutionState.EXECUTING,
        ),
        ActionRiskLevel.REVERSIBLE_WRITE to setOf(
            ActionExecutionState.VALIDATING to ActionExecutionState.PREVIEWED,
            ActionExecutionState.PREVIEWED to ActionExecutionState.EXECUTING,
        ),
        ActionRiskLevel.HIGH_RISK to setOf(
            ActionExecutionState.VALIDATING to ActionExecutionState.PREVIEWED,
            ActionExecutionState.PREVIEWED to ActionExecutionState.WAITING_APPROVAL,
            ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.EXECUTING,
        ),
    )

    /**
     * 判断指定风险等级下的状态迁移是否合法。
     *
     * @param from 当前状态。
     * @param to 目标状态。
     * @param riskLevel 动作风险等级。
     */
    fun transition(
        from: ActionExecutionState,
        to: ActionExecutionState,
        riskLevel: ActionRiskLevel,
    ): ActionTransitionResult {
        val transition = from to to
        if (transition in commonTransitions || transition in riskTransitions.getValue(riskLevel)) {
            return ActionTransitionResult.Allowed
        }
        return ActionTransitionResult.Rejected(
            ActionError(
                code = ActionErrorCode.PROTOCOL_ERROR,
                message = "不允许的动作状态迁移: $riskLevel $from -> $to",
            ),
        )
    }
}
