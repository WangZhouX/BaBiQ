package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionExecutionState.CANCELED
import com.wzx.huitai.action.model.ActionExecutionState.EXECUTING
import com.wzx.huitai.action.model.ActionExecutionState.EXPIRED
import com.wzx.huitai.action.model.ActionExecutionState.FAILED
import com.wzx.huitai.action.model.ActionExecutionState.OUTCOME_UNKNOWN
import com.wzx.huitai.action.model.ActionExecutionState.PREVIEWED
import com.wzx.huitai.action.model.ActionExecutionState.RECEIVED
import com.wzx.huitai.action.model.ActionExecutionState.SUCCEEDED
import com.wzx.huitai.action.model.ActionExecutionState.VALIDATING
import com.wzx.huitai.action.model.ActionExecutionState.WAITING_APPROVAL
import com.wzx.huitai.action.model.ActionRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionStateMachineTest {
    private val common = setOf(
        RECEIVED to VALIDATING,
        RECEIVED to CANCELED,
        RECEIVED to EXPIRED,
        VALIDATING to FAILED,
        VALIDATING to CANCELED,
        VALIDATING to EXPIRED,
        PREVIEWED to FAILED,
        PREVIEWED to CANCELED,
        PREVIEWED to EXPIRED,
        WAITING_APPROVAL to FAILED,
        WAITING_APPROVAL to CANCELED,
        WAITING_APPROVAL to EXPIRED,
        EXECUTING to SUCCEEDED,
        EXECUTING to FAILED,
        EXECUTING to CANCELED,
        EXECUTING to EXPIRED,
        EXECUTING to OUTCOME_UNKNOWN,
        OUTCOME_UNKNOWN to SUCCEEDED,
        OUTCOME_UNKNOWN to FAILED,
    )

    private val riskSpecific = mapOf(
        ActionRiskLevel.READ_ONLY to setOf(VALIDATING to EXECUTING),
        ActionRiskLevel.REVERSIBLE_WRITE to setOf(
            VALIDATING to PREVIEWED,
            PREVIEWED to EXECUTING,
        ),
        ActionRiskLevel.HIGH_RISK to setOf(
            VALIDATING to PREVIEWED,
            PREVIEWED to WAITING_APPROVAL,
            WAITING_APPROVAL to EXECUTING,
        ),
    )

    @Test
    fun `transition matrix allows exactly the common and risk-specific pairs`() {
        ActionRiskLevel.entries.forEach { riskLevel ->
            ActionExecutionState.entries.forEach { from ->
                ActionExecutionState.entries.forEach { to ->
                    val result = ActionStateMachine.transition(from, to, riskLevel)
                    if ((from to to) in common || (from to to) in riskSpecific.getValue(riskLevel)) {
                        assertIs<ActionTransitionResult.Allowed>(result, "$riskLevel: $from -> $to")
                    } else {
                        val rejected = assertIs<ActionTransitionResult.Rejected>(result, "$riskLevel: $from -> $to")
                        assertEquals(ActionErrorCode.PROTOCOL_ERROR, rejected.error.code)
                    }
                }
            }
        }
    }

    @Test
    fun `terminal states reject every transition`() {
        val terminalStates = setOf(SUCCEEDED, FAILED, CANCELED, EXPIRED)

        terminalStates.forEach { from ->
            ActionRiskLevel.entries.forEach { riskLevel ->
                ActionExecutionState.entries.forEach { to ->
                    assertIs<ActionTransitionResult.Rejected>(
                        ActionStateMachine.transition(from, to, riskLevel),
                        "$riskLevel: $from -> $to",
                    )
                }
            }
        }
    }
}
