package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ApprovalDecision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApplicationActionBusWriteTest {
    @Test
    fun `reversible accepted preview executes exactly once`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)

        val result = fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

        assertIs<ActionResult.Success<JsonElement>>(assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(ActionExecutionState.PREVIEWED, fixture.confirmation.stateAtRequest)
        assertEquals(0, fixture.approval.requests)
        assertEquals(1, fixture.action.executeCount)
        assertEquals(
            listOf(
                ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
                ActionExecutionState.VALIDATING to ActionExecutionState.PREVIEWED,
                ActionExecutionState.PREVIEWED to ActionExecutionState.EXECUTING,
                ActionExecutionState.EXECUTING to ActionExecutionState.SUCCEEDED,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
    }

    @Test
    fun `reversible rejection and expiry never execute`() = runTest {
        listOf(
            ConfirmationDecision.REJECTED to ActionExecutionState.CANCELED,
            ConfirmationDecision.EXPIRED to ActionExecutionState.EXPIRED,
        ).forEach { (decision, state) ->
            val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
            fixture.confirmation.response = fixture.confirmation.response.copy(decision = decision)

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            val completed = assertIs<ActionBusResult.Completed>(result)
            assertEquals(state, fixture.store.record?.state)
            assertEquals(state, completed.result.state())
            assertEquals(1, fixture.action.previewCount)
            assertEquals(1, fixture.confirmation.requests)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(0, fixture.approval.requests)
        }
    }

    @Test
    fun `effective risk upgrade routes read-only descriptor through preview`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            effectiveRisk = ActionRiskLevel.REVERSIBLE_WRITE,
        )

        fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(1, fixture.action.previewCount)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(1, fixture.action.executeCount)
    }

    @Test
    fun `preview execution mismatch is rejected before confirmation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.previewExecutionId = "other-execution"

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `high risk confirms before one approval and then executes`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)

        val result = fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

        assertIs<ActionBusResult.Completed>(result)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(1, fixture.approval.requests)
        assertEquals(1, fixture.action.executeCount)
        assertEquals(
            listOf(
                ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
                ActionExecutionState.VALIDATING to ActionExecutionState.PREVIEWED,
                ActionExecutionState.PREVIEWED to ActionExecutionState.WAITING_APPROVAL,
                ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.EXECUTING,
                ActionExecutionState.EXECUTING to ActionExecutionState.SUCCEEDED,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
        val approvalEvent = fixture.audit.events.single { it.type == "approval_approved" }
        assertEquals("secret-actor", approvalEvent.actorId)
        assertEquals("approval-1", approvalEvent.redactedPayload.getValue("approvalId").jsonPrimitive.content)
        assertEquals("APPROVED", approvalEvent.redactedPayload.getValue("decision").jsonPrimitive.content)
        assertEquals(null, approvalEvent.redactedPayload["reason"])
        assertEquals(ActionExecutionState.WAITING_APPROVAL, fixture.approval.stateAtRequest)
    }

    @Test
    fun `high risk confirmation rejection skips approval`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
        fixture.confirmation.response = fixture.confirmation.response.copy(decision = ConfirmationDecision.REJECTED)

        fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(1, fixture.confirmation.requests)
        assertEquals(0, fixture.approval.requests)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
    }

    @Test
    fun `high risk approval denial and expiry never execute`() = runTest {
        listOf(
            ApprovalDecision.DENIED to ActionExecutionState.CANCELED,
            ApprovalDecision.EXPIRED to ActionExecutionState.EXPIRED,
        ).forEach { (decision, state) ->
            val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
            fixture.approval.response = fixture.approval.response.copy(
                decision = decision,
                decidedBy = "secret-actor",
                reason = "secret-reason",
            )

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertIs<ActionBusResult.Completed>(result)
            assertEquals(1, fixture.confirmation.requests)
            assertEquals(1, fixture.approval.requests)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(state, fixture.store.record?.state)
            assertEquals(false, fixture.audit.events.any { "secret" in it.toString() })
            val event = fixture.audit.events.single { it.type == "approval_${decision.name.lowercase()}" }
            assertEquals("secret-actor", event.actorId)
            assertEquals("approval-1", event.redactedPayload.getValue("approvalId").jsonPrimitive.content)
            assertEquals(decision.name, event.redactedPayload.getValue("decision").jsonPrimitive.content)
            assertEquals(null, event.redactedPayload["reason"])
            assertEquals(ActionExecutionState.WAITING_APPROVAL, fixture.approval.stateAtRequest)
        }
    }

    @Test
    fun `high risk approval execution mismatch is rejected before execute`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
        fixture.approval.response = fixture.approval.response.copy(executionId = "other-execution")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(1, fixture.approval.requests)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(false, fixture.audit.events.any { it.type.startsWith("approval_") && it.type != "approval_requested" })
    }
}

private fun ActionResult<JsonElement>.state(): ActionExecutionState = when (this) {
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    else -> error("unexpected result")
}
