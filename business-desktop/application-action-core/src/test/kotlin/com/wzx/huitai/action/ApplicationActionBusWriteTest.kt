package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ApprovalDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

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
    fun `可逆写确认三种决定都在真实迁移事件中保存完整事实`() = runTest {
        listOf(
            ConfirmationDecision.ACCEPTED to "confirmation_accepted",
            ConfirmationDecision.REJECTED to "confirmation_rejected",
            ConfirmationDecision.EXPIRED to "confirmation_expired",
        ).forEach { (decision, eventType) ->
            val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
            fixture.confirmation.response = fixture.confirmation.response.copy(decision = decision)

            fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

            val event = fixture.audit.events.single { it.type == eventType }
            assertEquals("confirmation-1", event.redactedPayload.getValue("confirmationId").jsonPrimitive.content)
            assertEquals(decision.name, event.redactedPayload.getValue("confirmationDecision").jsonPrimitive.content)
            assertEquals(
                fixture.confirmation.response.decidedAt.toString(),
                event.redactedPayload.getValue("confirmationDecidedAt").jsonPrimitive.content,
            )
        }
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
    fun `effective reversible write exception from read only descriptor becomes outcome unknown`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            effectiveRisk = ActionRiskLevel.REVERSIBLE_WRITE,
        )
        fixture.action.executeFailure = IllegalStateException("secret-upgraded-write")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(
            ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
    }

    @Test
    fun `effective high risk cancellation from read only descriptor becomes outcome unknown`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            effectiveRisk = ActionRiskLevel.HIGH_RISK,
        )
        fixture.action.executeEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.executeEntered!!.await()
        execution.cancel(CancellationException("cancel-upgraded-high-risk"))

        assertEquals(
            "cancel-upgraded-high-risk",
            assertFailsWith<CancellationException> { execution.await() }.message,
        )
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(1, fixture.approval.requests)
        assertEquals(
            ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
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
        assertEquals("APPROVED", approvalEvent.redactedPayload.getValue("approvalDecision").jsonPrimitive.content)
        assertEquals(null, approvalEvent.redactedPayload["reason"])
        assertEquals(ActionExecutionState.WAITING_APPROVAL, fixture.approval.stateAtRequest)
    }

    @Test
    fun `高风险确认审批请求和审批三种决定保存完整真实时间事实`() = runTest {
        ApprovalDecision.entries.forEach { decision ->
            val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
            fixture.approval.response = fixture.approval.response.copy(decision = decision)

            fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

            val requested = fixture.audit.events.single { it.type == "approval_requested" }
            assertEquals(false, requested.redactedPayload.getValue("requestedAt") is JsonNull)
            val requestedAt = requested.redactedPayload.getValue("requestedAt").jsonPrimitive.content
            assertEquals(
                "confirmation-1",
                requested.redactedPayload.getValue("confirmationId").jsonPrimitive.content,
            )
            assertEquals(
                ConfirmationDecision.ACCEPTED.name,
                requested.redactedPayload.getValue("confirmationDecision").jsonPrimitive.content,
            )
            val decided = fixture.audit.events.single { it.type == "approval_${decision.name.lowercase()}" }
            assertEquals("approval-1", decided.redactedPayload.getValue("approvalId").jsonPrimitive.content)
            assertEquals(decision.name, decided.redactedPayload.getValue("approvalDecision").jsonPrimitive.content)
            assertEquals(requestedAt, decided.redactedPayload.getValue("requestedAt").jsonPrimitive.content)
            assertEquals(
                fixture.approval.response.decidedAt.toString(),
                decided.redactedPayload.getValue("approvalDecidedAt").jsonPrimitive.content,
            )
            assertEquals(
                fixture.approval.response.decidedBy,
                decided.redactedPayload.getValue("approvalActorId").jsonPrimitive.content,
            )
        }
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
            assertEquals(decision.name, event.redactedPayload.getValue("approvalDecision").jsonPrimitive.content)
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

    @Test
    fun `preview decode failure terminates validating as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.commandInput = kotlinx.serialization.json.buildJsonObject { put("invalid", true) }

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.VALIDATING)
        assertEquals(ActionErrorCode.VALIDATION_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `preview exception terminates validating as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.previewResultMode = PreviewResultMode.THROW

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.VALIDATING)
        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `illegal preview result terminates validating as failed`() = runTest {
        val fixture = BusFixture(
            ActionRiskLevel.REVERSIBLE_WRITE,
            previewInvocationOverride = {
                if (it is ActionInvocationResult.Previewed) {
                    ActionInvocationResult.Executed(ActionResult.Canceled("execution-1", "illegal"))
                } else {
                    it
                }
            },
        )

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.VALIDATING)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `preview execution mismatch terminates validating as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.previewExecutionId = "other-execution"

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.VALIDATING)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(1, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `confirmation mismatch terminates previewed as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.confirmation.response = fixture.confirmation.response.copy(executionId = "other-execution")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.PREVIEWED)
        assertEquals(1, fixture.confirmation.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `approval mismatch terminates waiting approval as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
        fixture.approval.response = fixture.approval.response.copy(executionId = "other-execution")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertFailedAt(fixture, result, ActionExecutionState.WAITING_APPROVAL)
        assertEquals(1, fixture.approval.requests)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(false, fixture.audit.events.any { it.type in setOf("approval_approved", "approval_denied", "approval_expired") })
    }

    @Test
    fun `确认决定早于预览或晚于接收时间均协议失败且不执行`() = runTest {
        listOf(
            Instant.parse("2026-07-14T00:00:00Z"),
            Instant.parse("2026-07-14T00:01:40Z"),
        ).forEach { decidedAt ->
            val fixture = BusFixture(
                risk = ActionRiskLevel.REVERSIBLE_WRITE,
                clock = BusClock(initialSeconds = 0),
            )
            fixture.confirmation.response = fixture.confirmation.response.copy(decidedAt = decidedAt)

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertFailedAt(fixture, result, ActionExecutionState.PREVIEWED)
            assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(false, fixture.audit.events.any { it.type.startsWith("confirmation_") })
        }
    }

    @Test
    fun `批准人空白或审批时间越界均协议失败且不执行`() = runTest {
        listOf(
            "" to Instant.parse("2026-07-14T00:00:03Z"),
            "   " to Instant.parse("2026-07-14T00:00:03Z"),
            "secret-actor" to Instant.parse("2026-07-14T00:00:01Z"),
            "secret-actor" to Instant.parse("2026-07-14T00:01:40Z"),
        ).forEach { (decidedBy, decidedAt) ->
            val fixture = BusFixture(
                risk = ActionRiskLevel.HIGH_RISK,
                clock = BusClock(initialSeconds = 0),
            )
            fixture.approval.response = fixture.approval.response.copy(
                decision = ApprovalDecision.APPROVED,
                decidedAt = decidedAt,
                decidedBy = decidedBy,
            )

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertFailedAt(fixture, result, ActionExecutionState.WAITING_APPROVAL)
            assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(false, fixture.audit.events.any { it.type == "approval_approved" })
        }
    }

    @Test
    fun `confirmation exception and cancellation terminate before propagation`() = runTest {
        listOf<Throwable>(IllegalStateException("secret-confirm"), CancellationException("cancel-confirm"))
            .forEach { failure ->
                val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
                fixture.confirmation.failure = failure

                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { fixture.bus.execute(fixture.command(), fixture.context) }
                    assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
                } else {
                    assertIs<ActionBusResult.Rejected>(fixture.bus.execute(fixture.command(), fixture.context))
                    assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
                }
                assertEquals(0, fixture.action.executeCount)
            }
    }

    @Test
    fun `approval exception and cancellation terminate before propagation`() = runTest {
        listOf<Throwable>(IllegalStateException("secret-approve"), CancellationException("cancel-approve"))
            .forEach { failure ->
                val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
                fixture.approval.failure = failure

                if (failure is CancellationException) {
                    assertFailsWith<CancellationException> { fixture.bus.execute(fixture.command(), fixture.context) }
                    assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
                } else {
                    assertIs<ActionBusResult.Rejected>(fixture.bus.execute(fixture.command(), fixture.context))
                    assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
                }
                assertEquals(0, fixture.action.executeCount)
            }
    }

    @Test
    fun `real confirmation cancellation hands off canceled before propagation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.confirmation.requestEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.confirmation.requestEntered!!.await()
        execution.cancel(CancellationException("cancel-confirmation"))

        assertEquals("cancel-confirmation", assertFailsWith<CancellationException> { execution.await() }.message)
        assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
        assertEquals(
            ActionExecutionState.PREVIEWED to ActionExecutionState.CANCELED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
    }

    @Test
    fun `real approval cancellation hands off canceled before propagation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
        fixture.approval.requestEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.approval.requestEntered!!.await()
        execution.cancel(CancellationException("cancel-approval"))

        assertEquals("cancel-approval", assertFailsWith<CancellationException> { execution.await() }.message)
        assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
        assertEquals(
            ActionExecutionState.WAITING_APPROVAL to ActionExecutionState.CANCELED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
    }

    @Test
    fun `cancellation handoff failure is suppressed without replacing original cancellation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.confirmation.requestEntered = CompletableDeferred()
        fixture.store.failTransitionTo = ActionExecutionState.CANCELED

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.confirmation.requestEntered!!.await()
        val original = CancellationException("original-cancellation")
        execution.cancel(original)

        val cancellation = assertFailsWith<CancellationException> { execution.await() }
        assertEquals("original-cancellation", cancellation.message)
        assertEquals(1, original.suppressed.size)
        assertEquals(ActionExecutionState.PREVIEWED, fixture.store.record?.state)
    }

    @Test
    fun `preview to execute terminal race replays exact persisted result`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        val persisted: ActionResult<JsonElement> = ActionResult.Canceled("execution-1", "concurrent cancel")
        fixture.store.existingTerminalResult = persisted
        fixture.store.existingTerminalOnTransitionTo = ActionExecutionState.EXECUTING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(persisted, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `waiting approval to execute terminal race replays exact persisted result`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.HIGH_RISK)
        val persisted: ActionResult<JsonElement> = ActionResult.Expired("execution-1", "concurrent expiry")
        fixture.store.existingTerminalResult = persisted
        fixture.store.existingTerminalOnTransitionTo = ActionExecutionState.EXECUTING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(persisted, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(1, fixture.approval.requests)
        assertEquals(0, fixture.action.executeCount)
    }

    private fun assertFailedAt(
        fixture: BusFixture,
        result: ActionBusResult,
        fromState: ActionExecutionState,
    ) {
        assertIs<ActionBusResult.Rejected>(result)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        val failure = assertIs<ActionResult.Failure>(fixture.store.record?.result)
        assertEquals("execution-1", failure.executionId)
        assertEquals(fromState to ActionExecutionState.FAILED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }
}

private fun ActionResult<JsonElement>.state(): ActionExecutionState = when (this) {
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    else -> error("unexpected result")
}
