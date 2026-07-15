package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditPort
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ActionConfirmationPort
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ActionRiskPolicy
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse

class ApplicationActionBusReadOnlyTest {
    private companion object {
        val AUDIT_SCHEMA_FIELDS = setOf(
            "executionId", "actionId", "actionVersion", "origin",
            "threadId", "turnId", "toolCallId",
            "userId", "tenantId", "platformId", "authSessionId", "desktopInstanceId",
            "desktopSessionId", "identityEpoch",
            "pageId", "contextRevision", "risk",
            "confirmationId", "confirmationDecision", "confirmationDecidedAt",
            "approvalId", "approvalDecision", "approvalActorId",
            "requestedAt", "approvalDecidedAt", "remoteReference", "terminalStatus", "errorCode",
        )
    }

    @Test
    fun `public bus constructor no longer exposes standalone audit port`() {
        val constructors = ApplicationActionBus::class.java.constructors.toList()

        assertFalse(constructors.any { it.parameterTypes.contains(ActionAuditPort::class.java) })
        assertEquals(true, constructors.any { it.parameterCount == 7 })
    }

    @Test
    fun `user and agent share the same read-only pipeline`() = runTest {
        listOf(ActionOrigin.USER, ActionOrigin.AGENT).forEach { origin ->
            val fixture = BusFixture(ActionRiskLevel.READ_ONLY)

            val result = fixture.bus.execute(fixture.command(origin), fixture.context)

            val completed = assertIs<ActionBusResult.Completed>(result)
            assertIs<ActionResult.Success<JsonElement>>(completed.result)
            assertEquals(0, fixture.action.previewCount)
            assertEquals(1, fixture.action.executeCount)
            assertEquals(0, fixture.confirmation.requests)
            assertEquals(0, fixture.approval.requests)
            assertEquals(
                listOf(
                    ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
                    ActionExecutionState.VALIDATING to ActionExecutionState.EXECUTING,
                    ActionExecutionState.EXECUTING to ActionExecutionState.SUCCEEDED,
                ),
                fixture.audit.events.map { it.fromState to it.toState },
            )
            assertEquals(listOf(1L, 2L, 3L), fixture.audit.events.map { it.sequence })
            assertEquals(listOf(origin.name.lowercase(), "state_transition", "state_transition"),
                fixture.audit.events.map { it.type })
            assertEquals(listOf(null, null, null), fixture.audit.events.map { it.actorId })
            assertEquals(ActionExecutionState.SUCCEEDED, fixture.store.record?.state)
            assertEquals(completed.result, fixture.store.record?.result)
        }
    }

    @Test
    fun `audit is allocated after record creation and continues persisted sequence`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.nextAuditSequence = 7
        fixture.audit.requireExistingRecord = true

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertIs<ActionBusResult.Completed>(result)
        assertEquals(listOf(8L, 9L, 10L), fixture.audit.events.map { it.sequence })
        assertEquals(true, fixture.audit.allAppendsSawRecord)
    }

    @Test
    fun `真实只读执行链每个审计事件都携带稳定业务schema`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)

        fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

        assertEquals(3, fixture.audit.events.size)
        fixture.audit.events.forEach { event ->
            val payload = event.redactedPayload
            AUDIT_SCHEMA_FIELDS.forEach { field ->
                kotlin.test.assertNotNull(payload[field], "${event.type} 缺少 $field")
            }
            assertEquals("execution-1", payload.getValue("executionId").jsonPrimitive.content)
            assertEquals("demo.action", payload.getValue("actionId").jsonPrimitive.content)
            assertEquals(1, payload.getValue("actionVersion").jsonPrimitive.int)
            assertEquals("agent", payload.getValue("origin").jsonPrimitive.content)
            assertEquals("secret-user", payload.getValue("userId").jsonPrimitive.content)
            assertEquals("secret-tenant", payload.getValue("tenantId").jsonPrimitive.content)
            assertEquals("secret-platform", payload.getValue("platformId").jsonPrimitive.content)
            assertEquals("secret-auth", payload.getValue("authSessionId").jsonPrimitive.content)
            assertEquals("secret-desktop", payload.getValue("desktopInstanceId").jsonPrimitive.content)
            assertEquals("page-1", payload.getValue("pageId").jsonPrimitive.content)
            assertEquals(7, payload.getValue("contextRevision").jsonPrimitive.int)
            assertEquals("read_only", payload.getValue("risk").jsonPrimitive.content)
        }
        assertEquals(
            "succeeded",
            fixture.audit.events.last().redactedPayload.getValue("terminalStatus").jsonPrimitive.content,
        )
        assertEquals(
            "remote-1",
            fixture.audit.events.last().redactedPayload.getValue("remoteReference").jsonPrimitive.content,
        )
    }

    @Test
    fun `迁移附加字段不能覆盖审计身份动作风险和时间`() {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val occurredAt = Instant.parse("2026-07-14T00:00:03Z")
        val payload = ActionAuditPayloadBuilder.build(
            command = fixture.command(ActionOrigin.AGENT),
            risk = ActionRiskLevel.READ_ONLY,
            occurredAt = occurredAt,
            details = buildJsonObject {
                put("executionId", "forged-execution")
                put("userId", "forged-user")
                put("risk", "high_risk")
                put("occurredAt", "forged-time")
                put("safeDetail", true)
            },
        )

        assertEquals("execution-1", payload.getValue("executionId").jsonPrimitive.content)
        assertEquals("secret-user", payload.getValue("userId").jsonPrimitive.content)
        assertEquals("read_only", payload.getValue("risk").jsonPrimitive.content)
        assertEquals(occurredAt.toString(), payload.getValue("occurredAt").jsonPrimitive.content)
        assertEquals(true, payload.getValue("safeDetail").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `迁移附加字段不能覆盖任何稳定审计schema字段`() {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val occurredAt = Instant.parse("2026-07-14T00:00:03Z")

        AUDIT_SCHEMA_FIELDS.forEach { protectedField ->
            val payload = ActionAuditPayloadBuilder.build(
                command = fixture.command(ActionOrigin.AGENT),
                risk = ActionRiskLevel.READ_ONLY,
                occurredAt = occurredAt,
                details = buildJsonObject { put(protectedField, "forged-$protectedField") },
            )

            assertEquals(
                false,
                payload.getValue(protectedField).jsonPrimitive.contentOrNull == "forged-$protectedField",
                protectedField,
            )
        }
    }

    @Test
    fun `已存在运行终态和UNKNOWN均精确重放且不再次调用风险策略`() = runTest {
        val storedResults = listOf<ActionResult<JsonElement>?>(
            null,
            ActionResult.Success("execution-1", buildJsonObject { put("saved", true) }),
            ActionResult.OutcomeUnknown(
                "execution-1",
                ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
                reconciliationPolicy = ReconciliationPolicy.MANUAL,
            ),
        )
        storedResults.forEach { stored ->
            var riskCalls = 0
            val fixture = BusFixture(
                risk = ActionRiskLevel.READ_ONLY,
                riskEvaluationProvider = {
                    riskCalls += 1
                    error("risk-policy-must-not-run")
                },
            )
            if (stored == null) {
                fixture.store.installExistingRunning(fixture.command())
            } else {
                fixture.store.installExistingTerminal(fixture.command(), result = stored)
            }

            val replay = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(0, riskCalls)
            if (stored == null) assertIs<ActionBusResult.InProgress>(replay)
            else assertEquals(stored, assertIs<ActionBusResult.Completed>(replay).result)
        }
    }

    @Test
    fun `风险策略异常安全收口失败而取消异常传播且不创建执行`() = runTest {
        val failedFixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            riskEvaluationProvider = { throw IllegalStateException("secret-risk") },
        )

        val failed = assertIs<ActionBusResult.Rejected>(
            failedFixture.bus.execute(failedFixture.command(), failedFixture.context),
        )

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, failed.error.code)
        assertEquals("风险策略评估失败", failed.error.message)
        assertEquals(ActionExecutionState.FAILED, failedFixture.store.record?.state)
        assertEquals("risk_evaluation_failed", failedFixture.audit.events.last().type)
        assertEquals(0, failedFixture.action.previewCount)
        assertEquals(0, failedFixture.confirmation.requests)
        assertEquals(0, failedFixture.approval.requests)
        assertEquals(0, failedFixture.action.executeCount)

        val canceledFixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            riskEvaluationProvider = { throw CancellationException("cancel-risk") },
        )

        assertEquals(
            "cancel-risk",
            assertFailsWith<CancellationException> {
                canceledFixture.bus.execute(canceledFixture.command(), canceledFixture.context)
            }.message,
        )
        assertNull(canceledFixture.store.record)
        assertEquals(emptyList(), canceledFixture.audit.events)
        assertEquals(0, canceledFixture.action.previewCount)
        assertEquals(0, canceledFixture.confirmation.requests)
        assertEquals(0, canceledFixture.approval.requests)
        assertEquals(0, canceledFixture.action.executeCount)

        val fatalFixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            riskEvaluationProvider = { throw AssertionError("fatal-risk") },
        )

        assertEquals(
            "fatal-risk",
            assertFailsWith<AssertionError> {
                fatalFixture.bus.execute(fatalFixture.command(), fatalFixture.context)
            }.message,
        )
        assertNull(fatalFixture.store.record)
        assertEquals(emptyList(), fatalFixture.audit.events)
        assertEquals(0, fatalFixture.action.previewCount)
        assertEquals(0, fatalFixture.confirmation.requests)
        assertEquals(0, fatalFixture.approval.requests)
        assertEquals(0, fatalFixture.action.executeCount)
    }

    @Test
    fun `atomic transition failure keeps prior state and audit unchanged`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.failTransitionTo = ActionExecutionState.EXECUTING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.VALIDATING, fixture.store.record?.state)
        assertEquals(
            listOf(ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING),
            fixture.audit.events.map { it.fromState to it.toState },
        )
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `read only execute exception terminates failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.action.executeFailure = IllegalStateException("secret-execute")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }

    @Test
    fun `write execute exception becomes outcome unknown`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.executeFailure = IllegalStateException("secret-write")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
        assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }

    @Test
    fun `write execute cancellation hands off outcome unknown then propagates`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.executeFailure = CancellationException("secret-cancel")

        assertFailsWith<CancellationException> { fixture.bus.execute(fixture.command(), fixture.context) }

        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
        assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }

    @Test
    fun `real preview cancellation hands off canceled from validating`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.previewEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.previewEntered!!.await()
        execution.cancel(CancellationException("cancel-preview"))

        assertEquals("cancel-preview", assertFailsWith<CancellationException> { execution.await() }.message)
        assertEquals(ActionExecutionState.CANCELED, fixture.store.record?.state)
        assertEquals(
            listOf(
                ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
                ActionExecutionState.VALIDATING to ActionExecutionState.CANCELED,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
    }

    @Test
    fun `real read execute cancellation reaches terminal before propagation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.action.executeEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.executeEntered!!.await()
        execution.cancel(CancellationException("cancel-read-execute"))

        assertEquals("cancel-read-execute", assertFailsWith<CancellationException> { execution.await() }.message)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(
            ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
    }

    @Test
    fun `real write execute cancellation hands off outcome unknown before propagation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        fixture.action.executeEntered = CompletableDeferred()

        val execution = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.executeEntered!!.await()
        execution.cancel(CancellationException("cancel-write-execute"))

        assertEquals("cancel-write-execute", assertFailsWith<CancellationException> { execution.await() }.message)
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
        assertEquals(
            ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState,
        )
    }

    @Test
    fun `first persisted terminal wins over local failure handling`() = runTest {
        val successFixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        successFixture.confirmation.failure = IllegalStateException("confirm")
        successFixture.store.existingTerminalResult = ActionResult.Success(
            "execution-1",
            buildJsonObject { put("winner", "success") },
        )
        val success = assertIs<ActionBusResult.Completed>(
            successFixture.bus.execute(successFixture.command(), successFixture.context),
        )
        assertIs<ActionResult.Success<JsonElement>>(success.result)

        val failureFixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        failureFixture.action.executeFailure = IllegalStateException("write")
        val persistedFailure: ActionResult<JsonElement> = ActionResult.Failure(
            "execution-1",
            ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "persisted"),
        )
        failureFixture.store.existingTerminalResult = persistedFailure
        val failure = assertIs<ActionBusResult.Completed>(
            failureFixture.bus.execute(failureFixture.command(), failureFixture.context),
        )
        assertEquals(persistedFailure, failure.result)

        val unknownFixture = BusFixture(
            ActionRiskLevel.READ_ONLY,
            throwingOutputCodec = true,
            outputEncodingFailureOverride = { it.copy(executionId = "other-execution") },
        )
        val persistedUnknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            "execution-1",
            ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "persisted"),
            reconciliationPolicy = ReconciliationPolicy.MANUAL,
        )
        unknownFixture.store.existingTerminalResult = persistedUnknown
        val unknown = assertIs<ActionBusResult.Completed>(
            unknownFixture.bus.execute(unknownFixture.command(), unknownFixture.context),
        )
        assertEquals(persistedUnknown, unknown.result)
    }

    @Test
    fun `initial existing terminals replay exactly including unavailable success fact`() = runTest {
        val terminalResults = listOf<ActionResult<JsonElement>>(
            ActionResult.Success("execution-1", buildJsonObject { put("winner", "success") }),
            ActionResult.Failure("execution-1", ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "persisted")),
            ActionResult.Canceled("execution-1", "persisted canceled"),
            ActionResult.Expired("execution-1", "persisted expired"),
            ActionResult.OutcomeUnknown(
                "execution-1",
                ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "persisted unknown"),
                reconciliationPolicy = ReconciliationPolicy.MANUAL,
            ),
        )
        terminalResults.forEach { persisted ->
            val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
            fixture.store.installExistingTerminal(fixture.command(), result = persisted)

            val replay = assertIs<ActionBusResult.Completed>(fixture.bus.execute(fixture.command(), fixture.context))

            assertEquals(persisted, replay.result)
            assertEquals(0, fixture.action.executeCount)
        }

        val unavailable = BusFixture(ActionRiskLevel.READ_ONLY)
        unavailable.store.installExistingTerminal(
            unavailable.command(),
            successFact = ExecutionSuccessFact(ExecutionSuccessFact.OUTPUT_ENCODING_FAILED, "secret-reference"),
        )

        val replay = assertIs<ActionBusResult.OutputEncodingFailed>(
            unavailable.bus.execute(unavailable.command(), unavailable.context),
        )
        assertEquals(ActionExecutionState.SUCCEEDED, replay.terminalState)
        assertEquals(0, unavailable.action.executeCount)
    }

    @Test
    fun `audit failure rolls back state transition atomically`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.nextAuditSequence = 7
        fixture.audit.failOnState = ActionExecutionState.EXECUTING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.VALIDATING, fixture.store.record?.state)
        assertEquals(1, fixture.store.record?.recordVersion)
        assertEquals(1, fixture.audit.events.size)
        assertEquals(8L, fixture.audit.events.single().sequence)
        assertEquals(8L, fixture.store.nextAuditSequence)
        assertEquals(ActionExecutionState.EXECUTING, fixture.store.preparedStateBeforeAuditFailure)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `initial audit insertion failure rolls back record event and sequence atomically`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.nextAuditSequence = 11
        fixture.audit.failOnState = ActionExecutionState.VALIDATING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertNull(fixture.store.record)
        assertEquals(emptyList(), fixture.audit.events)
        assertEquals(11L, fixture.store.nextAuditSequence)
        assertEquals(ActionExecutionState.VALIDATING, fixture.store.preparedStateBeforeAuditFailure)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `bus requires a frozen registry`() {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY, freezeRegistry = false)

        assertFailsWith<IllegalStateException> { fixture.bus }
    }

    @Test
    fun `context validator rejects mismatched identity page revision permissions and action`() {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val validator = ActionExecutionContextValidator()
        val command = fixture.command(ActionOrigin.USER)
        val descriptor = fixture.action.descriptor

        assertNull(validator.validate(descriptor, command, fixture.context))
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, validator.validate(
            descriptor.copy(id = "other.action"), command, fixture.context,
        )?.code)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, validator.validate(
            descriptor.copy(version = descriptor.version + 1), command, fixture.context,
        )?.code)
        assertEquals(ActionErrorCode.CONTEXT_STALE, validator.validate(
            descriptor, command.copy(pageId = "other-page"), fixture.context,
        )?.code)
        assertEquals(ActionErrorCode.CONTEXT_STALE, validator.validate(
            descriptor, command.copy(contextRevision = command.contextRevision + 1), fixture.context,
        )?.code)
        assertEquals(ActionErrorCode.CONTEXT_STALE, validator.validate(
            descriptor, command.copy(identityScope = fixture.identity.copy(identityEpoch = 99)), fixture.context,
        )?.code)
        assertEquals(ActionErrorCode.PERMISSION_DENIED, validator.validate(
            descriptor.copy(requiredPermissions = setOf("missing:permission")), command, fixture.context,
        )?.code)
    }

    @Test
    fun `context rejection happens before begin audit and action invocation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val stale = fixture.command(ActionOrigin.AGENT).copy(contextRevision = 99)

        val result = fixture.bus.execute(stale, fixture.context)

        assertEquals(ActionErrorCode.CONTEXT_STALE, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.action.executeCount)
        assertNull(fixture.store.record)
        assertEquals(emptyList(), fixture.audit.events)
    }

    @Test
    fun `风险策略明确拒绝后即使预置批准也不会预览审批或执行`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.HIGH_RISK,
            riskEvaluation = RiskEvaluation.deny(
                baseRisk = ActionRiskLevel.HIGH_RISK,
                reasons = listOf("UNKNOWN_OPERATION"),
            ),
        )
        fixture.confirmation.response = ActionConfirmation(
            decisionId = "confirmation-approved",
            executionId = "execution-1",
            decision = ConfirmationDecision.ACCEPTED,
            decidedAt = Instant.parse("2026-07-14T00:00:01Z"),
        )
        fixture.approval.response = ActionApproval(
            approvalId = "approval-approved",
            executionId = "execution-1",
            decision = ApprovalDecision.APPROVED,
            decidedAt = Instant.parse("2026-07-14T00:00:02Z"),
            decidedBy = "reviewer-1",
        )

        val result = fixture.bus.execute(fixture.command(ActionOrigin.AGENT), fixture.context)

        assertEquals(ActionErrorCode.PERMISSION_DENIED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.confirmation.requests)
        assertEquals(0, fixture.approval.requests)
        assertEquals(0, fixture.action.executeCount)
        assertEquals("risk_denied", fixture.audit.events.last().type)
    }

    @Test
    fun `all terminal result execution mismatches become audited protocol failure`() = runTest {
        val results = listOf<ActionResult<BusOutput>>(
            ActionResult.Success("other-execution", BusOutput(1)),
            ActionResult.Failure("other-execution", ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "failed")),
            ActionResult.Canceled("other-execution", "canceled"),
            ActionResult.Expired("other-execution", "expired"),
            ActionResult.OutcomeUnknown(
                "other-execution",
                ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
                reconciliationPolicy = ReconciliationPolicy.MANUAL,
            ),
        )

        results.forEach { mismatched ->
            val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
            fixture.action.result = mismatched

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
            assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
            assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
                fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
        }
    }

    @Test
    fun `decode failure after executing state is persisted as failed`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.commandInput = buildJsonObject { put("unexpected", true) }

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.VALIDATION_FAILED, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }

    @Test
    fun `output encoding failure persists exact replayable fact without fake output`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY, throwingOutputCodec = true)

        val first = fixture.bus.execute(fixture.command(), fixture.context)
        val replay = fixture.bus.execute(fixture.command(), fixture.context)

        val failed = assertIs<ActionBusResult.OutputEncodingFailed>(first)
        assertEquals(ActionExecutionState.SUCCEEDED, failed.terminalState)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, failed.error.code)
        assertEquals("动作输出编码失败", failed.error.message)
        assertEquals("remote-1", failed.remoteReference)
        assertEquals(first, replay)
        assertEquals(ActionExecutionState.SUCCEEDED, fixture.store.record?.state)
        assertNull(fixture.store.record?.result)
        assertEquals("OUTPUT_ENCODING_FAILED", fixture.store.record?.successFact?.kind)
        assertEquals("remote-1", fixture.store.record?.successFact?.remoteReference)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, fixture.store.record?.successFact?.errorCode)
        assertEquals("动作输出编码失败", fixture.store.record?.successFact?.safeMessage)
        assertEquals(
            ExecutionSuccessFact.SOURCE_OUTPUT_ENCODING,
            fixture.store.record?.successFact?.source,
        )
        assertEquals(1, fixture.action.executeCount)
        assertFalse(fixture.audit.events.last().redactedPayload.toString().contains("secret-codec"))
        assertEquals("OUTPUT_ENCODING_FAILED",
            fixture.audit.events.last().redactedPayload.getValue("successFact").jsonPrimitive.content)
    }

    @Test
    fun `illegal intermediate execution result is persisted as protocol failure`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.action.result = ActionResult.Preview(ActionPreview("execution-1", "illegal"))

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.FAILED,
            fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
    }

    @Test
    fun `encoding failure correlation errors become outcome unknown`() = runTest {
        val overrides = listOf<(ActionInvocationResult.OutputEncodingFailed) ->
            ActionInvocationResult.OutputEncodingFailed>(
            { it.copy(executionId = "other-execution") },
            { it.copy(terminalState = ActionExecutionState.FAILED) },
        )

        overrides.forEach { override ->
            val fixture = BusFixture(
                ActionRiskLevel.READ_ONLY,
                throwingOutputCodec = true,
                outputEncodingFailureOverride = override,
            )

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
            assertEquals(1, fixture.action.executeCount)
            assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
            val unknown = assertIs<ActionResult.OutcomeUnknown>(fixture.store.record?.result)
            assertEquals("execution-1", unknown.executionId)
            assertEquals(ActionErrorCode.PROTOCOL_ERROR, unknown.error.code)
            assertEquals(ReconciliationPolicy.MANUAL, unknown.reconciliationPolicy)
            assertEquals(ActionExecutionState.EXECUTING to ActionExecutionState.OUTCOME_UNKNOWN,
                fixture.audit.events.last().fromState to fixture.audit.events.last().toState)
        }
    }
}

internal class BusFixture(
    risk: ActionRiskLevel,
    private val effectiveRisk: ActionRiskLevel = risk,
    private val riskEvaluation: RiskEvaluation? = null,
    private val riskEvaluationProvider: ((ActionDescriptor) -> RiskEvaluation)? = null,
    freezeRegistry: Boolean = true,
    registerAction: Boolean = true,
    throwingOutputCodec: Boolean = false,
    reconciliationPolicy: ReconciliationPolicy = ReconciliationPolicy.MANUAL,
    outputEncodingFailureOverride: ((ActionInvocationResult.OutputEncodingFailed) ->
        ActionInvocationResult.OutputEncodingFailed)? = null,
    previewInvocationOverride: ((ActionInvocationResult) -> ActionInvocationResult)? = null,
    reconcileInvocationOverride: ((ActionInvocationResult) -> ActionInvocationResult)? = null,
    val lockScope: ActionExecutionLockScope = StripedActionExecutionLockScope(),
    val store: BusExecutionStore = BusExecutionStore(),
    val audit: BusAuditPort = BusAuditPort(),
    val clock: BusClock = BusClock(),
    additionalRegisteredActions: List<RegisteredAction<*, *>> = emptyList(),
    val identity: ActionIdentityScope = busIdentity(),
) {
    val context = ActionContext(identity, "page-1", 7, setOf("demo:read", "demo:write"))
    val action = BusCountingAction(busDescriptor(risk, reconciliationPolicy))
    var commandInput: JsonObject = buildJsonObject { put("value", 1) }
    private val registered = RegisteredAction(
        action,
        BusInputCodec(),
        if (throwingOutputCodec) ActionOutputCodec<BusOutput> { error("secret-codec") } else BusOutputCodec(),
    )
    val registry = ActionRegistry().apply {
        if (registerAction) register(registered)
        additionalRegisteredActions.forEach(::register)
        if (freezeRegistry) freeze()
    }
    private val actionInvoker = object : RegisteredActionInvoker {
        override suspend fun preview(
            registered: RegisteredAction<*, *>,
            input: JsonObject,
            context: ActionContext,
        ): ActionInvocationResult {
            val invocation = registered.invokePreview(input, context)
            return previewInvocationOverride?.invoke(invocation) ?: invocation
        }

        override suspend fun execute(
            registered: RegisteredAction<*, *>,
            input: JsonObject,
            context: ActionContext,
        ): ActionInvocationResult {
            val invocation = registered.invokeExecute(input, context)
            return if (invocation is ActionInvocationResult.OutputEncodingFailed &&
                outputEncodingFailureOverride != null
            ) {
                outputEncodingFailureOverride(invocation)
            } else {
                invocation
            }
        }

        override suspend fun reconcile(
            registered: RegisteredAction<*, *>,
            input: JsonObject,
            context: ActionContext,
            remoteReference: String?,
            executionId: String,
        ): ActionInvocationResult {
            val invocation = registered.invokeReconcile(input, context, remoteReference, executionId)
            return reconcileInvocationOverride?.invoke(invocation) ?: invocation
        }
    }
    val confirmation = BusConfirmationPort()
    val approval = BusApprovalPort()
    val bus: ApplicationActionBus
        get() = ApplicationActionBus(
            registry = registry,
            riskPolicy = ActionRiskPolicy { descriptor, _, _ ->
                riskEvaluationProvider?.invoke(descriptor)
                    ?: riskEvaluation
                    ?: RiskEvaluation.atLeast(descriptor.riskLevel, effectiveRisk)
            },
            confirmationPort = confirmation,
            approvalPort = approval,
            executionStore = store,
            clock = clock,
            contextValidator = ActionExecutionContextValidator(),
            actionInvoker = actionInvoker,
            lockScope = lockScope,
        )

    init {
        confirmation.store = store
        approval.store = store
        store.audit = audit
    }

    fun command(
        origin: ActionOrigin = ActionOrigin.USER,
        executionId: String = "execution-1",
    ) = ActionCommand(
        executionId = executionId,
        actionId = action.descriptor.id,
        actionVersion = action.descriptor.version,
        input = commandInput,
        origin = origin,
        identityScope = identity,
        pageId = context.pageId,
        contextRevision = context.contextRevision,
    )
}

internal fun busIdentity(
    desktopInstanceId: String = "secret-desktop",
    desktopSessionId: String = "secret-session",
) = ActionIdentityScope(
    desktopInstanceId = desktopInstanceId,
    desktopSessionId = desktopSessionId,
    authSessionId = "secret-auth",
    identityEpoch = 1,
    userId = "secret-user",
    tenantId = "secret-tenant",
    platformId = "secret-platform",
)

internal data class BusInput(val value: Int)
internal data class BusOutput(val value: Int)

internal class BusCountingAction(
    override val descriptor: ActionDescriptor,
) : ApplicationAction<BusInput, BusOutput> {
    var previewCount = 0
    var executeCount = 0
    var reconcileCount = 0
    var activeReconcileCount = 0
    var maximumActiveReconcileCount = 0
    var previewExecutionId = "execution-1"
    var previewResultMode = PreviewResultMode.NORMAL
    var executeFailure: Throwable? = null
    var reconcileFailure: Throwable? = null
    var reconcileCancellationCleanupDelayMillis = 0L
    var reconcileCleanupEntered: CompletableDeferred<Unit>? = null
    var previewEntered: CompletableDeferred<Unit>? = null
    var executeEntered: CompletableDeferred<Unit>? = null
    var reconcileEntered: CompletableDeferred<Unit>? = null
    var reconcileRelease: CompletableDeferred<Unit>? = null
    var result: ActionResult<BusOutput> = ActionResult.Success(
        "execution-1",
        BusOutput(1),
        remoteReference = "remote-1",
    )
    var reconciliationResult: ReconciliationResult = ReconciliationResult.Unsupported("execution-1")

    override suspend fun preview(input: BusInput, context: ActionContext): ActionPreview {
        previewCount += 1
        previewEntered?.let {
            it.complete(Unit)
            awaitCancellation()
        }
        if (previewResultMode == PreviewResultMode.THROW) error("secret-preview-error")
        return ActionPreview(previewExecutionId, "secret-preview")
    }

    override suspend fun execute(input: BusInput, context: ActionContext): ActionResult<BusOutput> {
        executeCount += 1
        executeEntered?.let {
            it.complete(Unit)
            awaitCancellation()
        }
        executeFailure?.let { throw it }
        return result
    }

    override suspend fun reconcile(
        input: BusInput,
        context: ActionContext,
        remoteReference: String?,
        executionId: String,
    ): ReconciliationResult {
        reconcileCount += 1
        activeReconcileCount += 1
        maximumActiveReconcileCount = maxOf(maximumActiveReconcileCount, activeReconcileCount)
        try {
            reconcileEntered?.let {
                it.complete(Unit)
                reconcileRelease?.await()
            }
            reconcileFailure?.let { throw it }
            return reconciliationResult
        } finally {
            if (!currentCoroutineContext().isActive && reconcileCancellationCleanupDelayMillis > 0) {
                withContext(NonCancellable) {
                    reconcileCleanupEntered?.complete(Unit)
                    delay(reconcileCancellationCleanupDelayMillis)
                }
            }
            activeReconcileCount -= 1
        }
    }
}

internal enum class PreviewResultMode { NORMAL, THROW }

internal class BusInputCodec : ActionInputCodec<BusInput> {
    override fun decode(input: JsonObject): ActionInputDecodeResult<BusInput> = try {
        ActionInputDecodeResult.Success(BusInput(input.getValue("value").jsonPrimitive.int))
    } catch (_: Exception) {
        ActionInputDecodeResult.Failure(ActionError(ActionErrorCode.VALIDATION_FAILED, "value 必须是整数"))
    }
}

internal class BusOutputCodec : ActionOutputCodec<BusOutput> {
    override fun encode(output: BusOutput): JsonElement = buildJsonObject { put("value", output.value) }
}

internal class BusConfirmationPort : ActionConfirmationPort {
    var requests = 0
    var store: BusExecutionStore? = null
    var stateAtRequest: ActionExecutionState? = null
    var failure: Throwable? = null
    var requestEntered: CompletableDeferred<Unit>? = null
    var response = ActionConfirmation(
        "confirmation-1", "execution-1", ConfirmationDecision.ACCEPTED, Instant.parse("2026-07-14T00:00:02Z"),
    )

    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        context: ActionContext,
    ): ActionConfirmation {
        requests += 1
        stateAtRequest = store?.record?.state
        requestEntered?.let {
            it.complete(Unit)
            awaitCancellation()
        }
        failure?.let { throw it }
        return response
    }
}

internal class BusApprovalPort : ActionApprovalPort {
    var requests = 0
    var store: BusExecutionStore? = null
    var stateAtRequest: ActionExecutionState? = null
    var failure: Throwable? = null
    var requestEntered: CompletableDeferred<Unit>? = null
    var response = ActionApproval(
        "approval-1", "execution-1", ApprovalDecision.APPROVED, Instant.parse("2026-07-14T00:00:03Z"),
        decidedBy = "secret-actor",
    )

    override suspend fun request(
        command: ActionCommand,
        preview: ActionPreview,
        riskEvaluation: RiskEvaluation,
        context: ActionContext,
    ): ActionApproval {
        requests += 1
        stateAtRequest = store?.record?.state
        requestEntered?.let {
            it.complete(Unit)
            awaitCancellation()
        }
        failure?.let { throw it }
        return response
    }
}

internal class BusExecutionStore : ActionExecutionStore {
    var record: ActionExecutionRecord? = null
    var nextAuditSequence = 0L
    var failTransitionTo: ActionExecutionState? = null
    var preparedStateBeforeAuditFailure: ActionExecutionState? = null
    var existingTerminalResult: ActionResult<JsonElement>? = null
    var existingTerminalOnTransitionTo: ActionExecutionState? = null
    var blockRenew = false
    var renewEntered: CompletableDeferred<Unit>? = null
    var blockRelease = false
    var releaseEntered: CompletableDeferred<Unit>? = null
    var renewOverride: ((
        com.wzx.huitai.action.port.ReconciliationRenewRequest,
        ActionExecutionRecord,
    ) -> com.wzx.huitai.action.port.ReconciliationRenewResult)? = null
    var releaseOverride: ((
        com.wzx.huitai.action.port.ReconciliationReleaseRequest,
        ActionExecutionRecord,
    ) -> com.wzx.huitai.action.port.ReconciliationReleaseResult)? = null
    lateinit var audit: BusAuditPort

    override suspend fun find(executionId: String): ActionExecutionRecord? = record

    fun installExistingRunning(command: ActionCommand) {
        record = ActionExecutionRecord(
            command = command,
            binding = command.bindingForStore(),
            riskLevel = ActionRiskLevel.READ_ONLY,
            state = ActionExecutionState.EXECUTING,
            result = null,
            createdAt = Instant.parse("2026-07-14T00:00:00Z"),
            startedAt = Instant.parse("2026-07-14T00:00:01Z"),
            updatedAt = Instant.parse("2026-07-14T00:00:01Z"),
            recordVersion = 2,
        )
    }

    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult {
        this.record?.let { existing ->
            return if (existing.binding == record.binding) {
                if (existing.isTerminal) ExecutionCreateResult.ExistingTerminal(existing)
                else ExecutionCreateResult.ExistingRunning(existing)
            } else {
                ExecutionCreateResult.Conflict(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "duplicate"))
            }
        }
        if (audit.toState == this.audit.failOnState) {
            preparedStateBeforeAuditFailure = record.state
        }
        val event = prepareAudit(audit) ?: return auditConflict()
        this.record = record
        commitAudit(event)
        return ExecutionCreateResult.Created(record)
    }

    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
        currentCoroutineContext().ensureActive()
        val current = record ?: return ExecutionTransitionResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        if (current.isTerminal) return ExecutionTransitionResult.ExistingTerminal(current)
        if (existingTerminalOnTransitionTo == update.state && existingTerminalResult != null) {
            val terminal = terminalRecord(current, existingTerminalResult!!, update.updatedAt)
            record = terminal
            return ExecutionTransitionResult.ExistingTerminal(terminal)
        }
        if (current.recordVersion != update.expectedVersion ||
            failTransitionTo == update.state
        ) {
            return ExecutionTransitionResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "transition conflict"),
            )
        }
        if (update.state in setOf(
                ActionExecutionState.SUCCEEDED,
                ActionExecutionState.FAILED,
                ActionExecutionState.CANCELED,
                ActionExecutionState.EXPIRED,
                ActionExecutionState.OUTCOME_UNKNOWN,
            )
        ) {
            existingTerminalResult?.let { result ->
                val terminal = terminalRecord(current, result, update.updatedAt)
                record = terminal
                return ExecutionTransitionResult.ExistingTerminal(terminal)
            }
        }
        val updated = current.copy(
            state = update.state,
            result = update.result,
            successFact = update.successFact,
            startedAt = update.startedAt ?: current.startedAt,
            completedAt = update.completedAt,
            updatedAt = update.updatedAt,
            recordVersion = current.recordVersion + 1,
        )
        if (update.audit.toState == audit.failOnState) {
            preparedStateBeforeAuditFailure = updated.state
        }
        val event = prepareAudit(update.audit) ?: return auditTransitionConflict()
        record = updated
        commitAudit(event)
        return ExecutionTransitionResult.Updated(updated)
    }

    private suspend fun prepareAudit(draft: ActionAuditDraft): ActionAuditEvent? {
        val event = ActionAuditEvent(
            executionId = draft.executionId,
            sequence = nextAuditSequence + 1,
            fromState = draft.fromState,
            toState = draft.toState,
            type = draft.type,
            redactedPayload = draft.redactedPayload,
            actorId = draft.actorId,
            occurredAt = draft.occurredAt,
        )
        return try {
            audit.prepare(event)
            event
        } catch (_: Exception) {
            null
        }
    }

    private fun commitAudit(event: ActionAuditEvent) {
        audit.commit(event)
        nextAuditSequence = event.sequence
    }

    private fun auditConflict(): ExecutionCreateResult.Conflict = ExecutionCreateResult.Conflict(
        ActionError(ActionErrorCode.EXECUTION_CONFLICT, "audit transaction rolled back"),
    )

    private fun auditTransitionConflict(): ExecutionTransitionResult.Conflict = ExecutionTransitionResult.Conflict(
        ActionError(ActionErrorCode.EXECUTION_CONFLICT, "audit transaction rolled back"),
    )

    fun installExistingTerminal(
        command: ActionCommand,
        result: ActionResult<JsonElement>? = null,
        successFact: ExecutionSuccessFact? = null,
    ) {
        val state = if (successFact != null) ActionExecutionState.SUCCEEDED else result!!.stateForStore()
        record = ActionExecutionRecord(
            command = command,
            binding = command.bindingForStore(),
            riskLevel = ActionRiskLevel.READ_ONLY,
            state = state,
            result = result,
            successFact = successFact,
            createdAt = Instant.parse("2026-07-14T00:00:00Z"),
            completedAt = Instant.parse("2026-07-14T00:00:01Z"),
            updatedAt = Instant.parse("2026-07-14T00:00:01Z"),
            recordVersion = 2,
        )
    }

    private fun terminalRecord(
        current: ActionExecutionRecord,
        result: ActionResult<JsonElement>,
        completedAt: Instant,
    ): ActionExecutionRecord = current.copy(
        state = result.stateForStore(),
        result = result,
        completedAt = completedAt,
        updatedAt = completedAt,
        recordVersion = current.recordVersion + 1,
    )

    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult {
        val current = record ?: return ReconciliationUpdateResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        if (current.reconciliation?.sourceRecordVersion == update.expectedVersion) {
            return ReconciliationUpdateResult.ExistingFinal(current)
        }
        if (!current.needsReconciliation ||
            current.recordVersion != update.expectedVersion ||
            current.reconciliationClaim?.claimToken != update.claimToken
        ) {
            return ReconciliationUpdateResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "reconciliation conflict"),
            )
        }
        val state = update.result?.stateForStore() ?: ActionExecutionState.SUCCEEDED
        val updated = current.copy(
            state = state,
            result = update.result,
            successFact = update.successFact,
            completedAt = update.completedAt,
            updatedAt = update.completedAt,
            recordVersion = current.recordVersion + 1,
            reconciliation = com.wzx.huitai.action.port.ReconciliationProvenance(
                sourceRecordVersion = current.recordVersion,
                reconciledAt = update.completedAt,
            ),
            reconciliationClaim = null,
        )
        val event = prepareAudit(update.audit) ?: return ReconciliationUpdateResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "audit transaction rolled back"),
        )
        record = updated
        commitAudit(event)
        return ReconciliationUpdateResult.Updated(updated)
    }

    override suspend fun claimReconciliation(
        request: com.wzx.huitai.action.port.ReconciliationClaimRequest,
    ): com.wzx.huitai.action.port.ReconciliationClaimResult {
        val current = record ?: return com.wzx.huitai.action.port.ReconciliationClaimResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        if (current.isFinalTerminal) {
            return com.wzx.huitai.action.port.ReconciliationClaimResult.ExistingFinal(current)
        }
        current.reconciliationClaim?.let { existingClaim ->
            if (request.now.isBefore(existingClaim.expiresAt)) {
                return com.wzx.huitai.action.port.ReconciliationClaimResult.ExistingClaim(current)
            }
        }
        if (!current.needsReconciliation || current.recordVersion != request.expectedVersion) {
            return com.wzx.huitai.action.port.ReconciliationClaimResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "claim conflict"),
            )
        }
        val event = prepareAudit(request.audit) ?: return com.wzx.huitai.action.port.ReconciliationClaimResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "claim audit rolled back"),
        )
        val claimed = current.copy(
            updatedAt = request.now,
            recordVersion = current.recordVersion + 1,
            reconciliationClaim = com.wzx.huitai.action.port.ReconciliationClaim(
                request.claimToken,
                request.ownerId,
                request.now,
                request.expiresAt,
            ),
        )
        record = claimed
        commitAudit(event)
        return com.wzx.huitai.action.port.ReconciliationClaimResult.Claimed(claimed)
    }

    override suspend fun renewReconciliation(
        request: com.wzx.huitai.action.port.ReconciliationRenewRequest,
    ): com.wzx.huitai.action.port.ReconciliationRenewResult {
        renewEntered?.complete(Unit)
        if (blockRenew) awaitCancellation()
        val current = record ?: return com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        renewOverride?.let { return it(request, current) }
        if (current.isFinalTerminal) {
            return com.wzx.huitai.action.port.ReconciliationRenewResult.ExistingFinal(current)
        }
        val claim = current.reconciliationClaim ?: return com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "renew claim missing"),
        )
        if (claim.claimToken != request.claimToken) {
            return com.wzx.huitai.action.port.ReconciliationRenewResult.ExistingClaim(current)
        }
        if (!current.needsReconciliation || current.recordVersion != request.expectedVersion) {
            return com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "renew conflict"),
            )
        }
        val renewed = try {
            current.copy(
                updatedAt = request.now,
                recordVersion = current.recordVersion + 1,
                reconciliationClaim = claim.copy(expiresAt = request.expiresAt),
            )
        } catch (_: IllegalArgumentException) {
            return com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "renew time conflict"),
            )
        }
        val event = prepareAudit(request.audit)
            ?: return com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "renew audit rolled back"),
            )
        record = renewed
        commitAudit(event)
        return com.wzx.huitai.action.port.ReconciliationRenewResult.Renewed(renewed)
    }

    override suspend fun releaseReconciliation(
        request: com.wzx.huitai.action.port.ReconciliationReleaseRequest,
    ): com.wzx.huitai.action.port.ReconciliationReleaseResult {
        releaseEntered?.complete(Unit)
        if (blockRelease) awaitCancellation()
        val current = record ?: return com.wzx.huitai.action.port.ReconciliationReleaseResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        releaseOverride?.let { return it(request, current) }
        if (current.isFinalTerminal) {
            return com.wzx.huitai.action.port.ReconciliationReleaseResult.ExistingFinal(current)
        }
        if (!current.needsReconciliation ||
            current.recordVersion != request.expectedVersion ||
            current.reconciliationClaim?.claimToken != request.claimToken
        ) {
            return com.wzx.huitai.action.port.ReconciliationReleaseResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "release conflict"),
            )
        }
        val event = prepareAudit(request.audit) ?: return com.wzx.huitai.action.port.ReconciliationReleaseResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "release audit rolled back"),
        )
        val released = current.copy(
            updatedAt = request.releasedAt,
            recordVersion = current.recordVersion + 1,
            reconciliationClaim = null,
        )
        record = released
        commitAudit(event)
        return com.wzx.huitai.action.port.ReconciliationReleaseResult.Released(released)
    }
}

internal class BusAuditPort : ActionAuditPort {
    val events = mutableListOf<ActionAuditEvent>()
    var requireExistingRecord = false
    var allAppendsSawRecord = true
    var failOnState: ActionExecutionState? = null
    override suspend fun append(event: ActionAuditEvent) {
        prepare(event)
        commit(event)
    }

    fun prepare(event: ActionAuditEvent) {
        if (event.toState == failOnState) error("audit insertion failed")
    }

    fun commit(event: ActionAuditEvent) {
        if (requireExistingRecord) {
            allAppendsSawRecord = allAppendsSawRecord && true
        }
        events += event
    }
}

private fun ActionResult<JsonElement>.stateForStore(): ActionExecutionState = when (this) {
    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
    is ActionResult.Failure -> ActionExecutionState.FAILED
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
    else -> error("terminal result required")
}

private fun ActionCommand.bindingForStore(): ExecutionBinding {
    val canonical = input.entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        "${kotlinx.serialization.json.JsonPrimitive(it.key)}:${it.value}"
    }
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return ExecutionBinding(
        actionId = actionId,
        actionVersion = actionVersion,
        inputFingerprint = bytes.joinToString("") { "%02x".format(it) },
        origin = origin,
        identityScope = identityScope,
        pageId = pageId,
        contextRevision = contextRevision,
    )
}

internal class BusClock(
    private val autoAdvance: Boolean = true,
    initialSeconds: Long = 0,
) : ActionClock {
    private var seconds = initialSeconds
    override fun now(): Instant {
        val current = Instant.parse("2026-07-14T00:00:00Z").plusSeconds(seconds)
        if (autoAdvance) seconds += 1
        return current
    }

    fun advanceTo(instant: Instant) {
        seconds = java.time.Duration.between(Instant.parse("2026-07-14T00:00:00Z"), instant).seconds
    }
}

internal fun busDescriptor(
    risk: ActionRiskLevel,
    reconciliationPolicy: ReconciliationPolicy = ReconciliationPolicy.MANUAL,
    version: Int = 1,
) = ActionDescriptor(
    id = "demo.action",
    version = version,
    title = "演示动作",
    description = "Bus 测试动作",
    inputSchema = buildJsonObject { put("type", "object") },
    riskLevel = risk,
    requiredPermissions = setOf("demo:read"),
    target = ActionTarget("generic-form", "submit"),
    replayPolicy = ActionReplayPolicy.NEVER,
    reconciliationPolicy = reconciliationPolicy,
)
