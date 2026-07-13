package com.wzx.huitai.action.port

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActionPortContractTest {
    @Test
    fun `execution store exposes only audited create and transition mutations`() {
        val createMethods = ActionExecutionStore::class.java.methods.filter { it.name == "compareAndCreate" }

        assertEquals(1, createMethods.size)
        assertTrue(createMethods.single().parameterTypes.contains(ActionAuditDraft::class.java))
        val reconciliationMethods = ActionExecutionStore::class.java.methods
            .filter { it.name in setOf("updateReconciliation", "appendReconciliationAudit") }
        assertEquals(2, reconciliationMethods.size)
        assertTrue(
            reconciliationMethods.all { method ->
                method.parameterTypes
                    .first { it != kotlin.coroutines.Continuation::class.java }
                    .declaredFields.any { it.type == ActionAuditDraft::class.java }
            },
        )
        listOf(
            "com.wzx.huitai.action.port.ExecutionStateUpdate",
            "com.wzx.huitai.action.port.ExecutionStateUpdateResult",
            "com.wzx.huitai.action.port.TerminalExecutionUpdate",
            "com.wzx.huitai.action.port.TerminalUpdateResult",
        ).forEach { removedType ->
            assertFailsWith<ClassNotFoundException> { Class.forName(removedType) }
        }
    }

    @Test
    fun `confirmation and approval use distinct per-execution vocabularies`() = runTest {
        val confirmation = QueueConfirmationPort(
            ActionConfirmation(
                decisionId = "confirmation-1",
                executionId = "execution-1",
                decision = ConfirmationDecision.ACCEPTED,
                decidedAt = NOW,
                reason = "secret-confirmation-reason",
            ),
        )
        val approval = QueueApprovalPort(
            ActionApproval(
                approvalId = "approval-1",
                executionId = "execution-1",
                decision = ApprovalDecision.APPROVED,
                decidedAt = NOW,
                decidedBy = "secret-actor",
                reason = "secret-approval-reason",
            ),
        )
        val command = command()
        val preview = preview()
        val context = context()
        val risk = RiskEvaluation.atLeast(ActionRiskLevel.HIGH_RISK, ActionRiskLevel.HIGH_RISK, listOf("submit"))

        val confirmationResult = confirmation.request(command, preview, context)
        val approvalResult = approval.request(command, preview, risk, context)

        confirmationResult.requireExecution(command.executionId)
        approvalResult.requireExecution(command.executionId)
        assertEquals(setOf("ACCEPTED", "REJECTED", "EXPIRED"), ConfirmationDecision.entries.map { it.name }.toSet())
        assertEquals(setOf("APPROVED", "DENIED", "EXPIRED"), ApprovalDecision.entries.map { it.name }.toSet())
        assertFalse((ConfirmationDecision.entries + ApprovalDecision.entries).any {
            "always" in it.name.lowercase() || "session" in it.name.lowercase()
        })
        assertEquals("confirmation-1", confirmationResult.decisionId)
        assertEquals(command.executionId, confirmationResult.executionId)
        assertEquals("approval-1", approvalResult.approvalId)
        assertEquals(command.executionId, approvalResult.executionId)
        assertEquals(1, confirmation.requests)
        assertEquals(1, approval.requests)
        assertFalse("secret" in confirmationResult.toString())
        assertFalse("secret" in approvalResult.toString())
    }

    @Test
    fun `confirmation and approval decisions verify the expected execution on consumption`() {
        val confirmation = ActionConfirmation(
            decisionId = "confirmation-1",
            executionId = "other-execution",
            decision = ConfirmationDecision.ACCEPTED,
            decidedAt = NOW,
        )
        val approval = ActionApproval(
            approvalId = "approval-1",
            executionId = "other-execution",
            decision = ApprovalDecision.APPROVED,
            decidedAt = NOW,
        )

        val confirmationError = assertFailsWith<IllegalArgumentException> {
            confirmation.requireExecution("execution-1")
        }
        val approvalError = assertFailsWith<IllegalArgumentException> {
            approval.requireExecution("execution-1")
        }

        assertTrue("execution-1" in confirmationError.message.orEmpty())
        assertTrue("other-execution" in confirmationError.message.orEmpty())
        assertTrue("execution-1" in approvalError.message.orEmpty())
        assertTrue("other-execution" in approvalError.message.orEmpty())
    }

    @Test
    fun `execution store distinguishes absent running terminal and conflict`() = runTest {
        val store = FakeExecutionStore()
        assertNull(store.find("execution-1"))
        val running = runningRecord(command())

        val created = assertIs<ExecutionCreateResult.Created>(store.compareAndCreate(running, auditDraft()))
        assertSame(running, created.record)
        assertEquals(listOf(1L), store.auditEvents.map { it.sequence })
        assertIs<ExecutionCreateResult.ExistingRunning>(store.compareAndCreate(running.copy(), auditDraft()))

        val conflict = assertIs<ExecutionCreateResult.Conflict>(
            store.compareAndCreate(
                running.copy(fingerprint = ExecutionFingerprint("other.action", "other-fingerprint")),
                auditDraft(),
            ),
        )
        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, conflict.error.code)

        val terminalResult: ActionResult<JsonElement> = ActionResult.Success(
            executionId = running.command.executionId,
            output = buildJsonObject { put("saved", true) },
        )
        val updated = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    state = ActionExecutionState.SUCCEEDED,
                    result = terminalResult,
                    updatedAt = NOW.plusSeconds(2),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.SUCCEEDED),
                ),
            ),
        )
        assertEquals(terminalResult, updated.record.result)
        assertEquals(ActionExecutionState.SUCCEEDED, updated.record.state)
        assertEquals(listOf(1L, 2L), store.auditEvents.map { it.sequence })
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running, auditDraft()))
        val repeated = assertIs<ExecutionTransitionResult.ExistingTerminal>(
            store.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = updated.record.recordVersion,
                    state = ActionExecutionState.FAILED,
                    result = failure(running.command.executionId),
                    updatedAt = NOW.plusSeconds(3),
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.SUCCEEDED, ActionExecutionState.FAILED),
                ),
            ),
        )
        assertEquals(updated.record, repeated.record)
        assertFalse("secret-input" in updated.record.toString())
        assertFalse("fingerprint-1" in running.fingerprint.toString())
    }

    @Test
    fun `execution records enforce result state correlation and timestamp ordering`() {
        val running = runningRecord(command())
        val terminal = terminalRecord(ActionExecutionState.SUCCEEDED, success())

        assertFailsWith<IllegalArgumentException> {
            running.copy(
                state = ActionExecutionState.SUCCEEDED,
                result = failure(),
                completedAt = NOW.plusSeconds(2),
                updatedAt = NOW.plusSeconds(2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            running.copy(
                state = ActionExecutionState.SUCCEEDED,
                result = null,
                completedAt = NOW.plusSeconds(2),
                updatedAt = NOW.plusSeconds(2),
            )
        }
        assertFailsWith<IllegalArgumentException> { running.copy(result = success()) }
        assertFailsWith<IllegalArgumentException> {
            running.copy(
                state = ActionExecutionState.SUCCEEDED,
                result = success("other-execution"),
                completedAt = NOW.plusSeconds(2),
                updatedAt = NOW.plusSeconds(2),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            running.copy(state = ActionExecutionState.SUCCEEDED, result = success())
        }
        assertFailsWith<IllegalArgumentException> { running.copy(completedAt = NOW.plusSeconds(2)) }
        assertFailsWith<IllegalArgumentException> { running.copy(startedAt = NOW.minusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { running.copy(updatedAt = NOW) }
        assertFailsWith<IllegalArgumentException> { terminal.copy(completedAt = NOW) }
        assertFailsWith<IllegalArgumentException> { terminal.copy(updatedAt = NOW.plusSeconds(1)) }
    }

    @Test
    fun `execution records use an exhaustive terminal result mapping`() {
        val validMappings = listOf(
            ActionExecutionState.SUCCEEDED to success(),
            ActionExecutionState.FAILED to failure(),
            ActionExecutionState.CANCELED to canceled(),
            ActionExecutionState.EXPIRED to expired(),
            ActionExecutionState.OUTCOME_UNKNOWN to outcomeUnknown(),
        )

        validMappings.forEach { (state, result) ->
            val record = terminalRecord(state, result)
            assertTrue(record.isTerminal)
            assertEquals(state != ActionExecutionState.OUTCOME_UNKNOWN, record.isFinalTerminal)
            assertEquals(state == ActionExecutionState.OUTCOME_UNKNOWN, record.needsReconciliation)
        }
        validMappings.forEach { (expectedState, result) ->
            validMappings.map { it.first }.filterNot { it == expectedState }.forEach { wrongState ->
                assertFailsWith<IllegalArgumentException> { terminalRecord(wrongState, result) }
            }
        }

        val nonTerminalResults = listOf<ActionResult<JsonElement>>(
            ActionResult.Preview(preview()),
            ActionResult.ApprovalRequired(
                executionId = "execution-1",
                approvalId = "approval-1",
                preview = preview(),
                reason = "secret-reason",
                expiresAtEpochMillis = NOW.plusSeconds(30).toEpochMilli(),
            ),
        )
        nonTerminalResults.forEach { result ->
            validMappings.map { it.first }.forEach { terminalState ->
                assertFailsWith<IllegalArgumentException> { terminalRecord(terminalState, result) }
            }
        }
    }

    @Test
    fun `transition and reconciliation updates reject invalid structured payloads`() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionTransition(
                executionId = "execution-1",
                expectedVersion = 1,
                state = ActionExecutionState.SUCCEEDED,
                result = failure(),
                updatedAt = NOW,
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.SUCCEEDED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionTransition(
                executionId = "execution-1",
                expectedVersion = 1,
                state = ActionExecutionState.EXECUTING,
                result = success(),
                updatedAt = NOW,
                audit = auditDraft(ActionExecutionState.VALIDATING, ActionExecutionState.EXECUTING),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionTransition(
                executionId = "execution-1",
                expectedVersion = 1,
                state = ActionExecutionState.SUCCEEDED,
                result = success("other-execution"),
                updatedAt = NOW,
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.SUCCEEDED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                result = canceled(),
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.CANCELED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                result = success("other-execution"),
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
            )
        }
    }

    @Test
    fun `output unavailable success fact redacts remote reference in logs`() {
        val fact = ExecutionSuccessFact(
            ExecutionSuccessFact.OUTPUT_ENCODING_FAILED,
            "secret-remote-reference",
        )
        val update = ExecutionTransition(
            executionId = "execution-1",
            expectedVersion = 1,
            state = ActionExecutionState.SUCCEEDED,
            result = null,
            updatedAt = NOW,
            completedAt = NOW,
            successFact = fact,
            audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.SUCCEEDED),
        )

        assertFalse("secret-remote-reference" in fact.toString())
        assertFalse("secret-remote-reference" in update.toString())
    }

    @Test
    fun `outcome unknown blocks replay and can be reconciled exactly once`() = runTest {
        val store = FakeExecutionStore()
        val running = runningRecord(command())
        store.compareAndCreate(running, auditDraft())
        val unknownResult = outcomeUnknown()
        val unknown = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    state = ActionExecutionState.OUTCOME_UNKNOWN,
                    result = unknownResult,
                    updatedAt = NOW.plusSeconds(2),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.OUTCOME_UNKNOWN),
                ),
            ),
        ).record

        assertTrue(unknown.isTerminal)
        assertFalse(unknown.isFinalTerminal)
        assertTrue(unknown.needsReconciliation)
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running, auditDraft()))

        val reconciledResult = success()
        val reconciled = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    result = reconciledResult,
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
                ),
            ),
        ).record
        assertEquals(ActionExecutionState.SUCCEEDED, reconciled.state)
        assertEquals(reconciledResult, reconciled.result)
        assertTrue(reconciled.isFinalTerminal)
        assertFalse(reconciled.needsReconciliation)
        assertEquals(unknown.recordVersion, reconciled.reconciliation?.sourceRecordVersion)
        assertEquals(NOW.plusSeconds(3), reconciled.reconciliation?.reconciledAt)

        val repeated = assertIs<ReconciliationUpdateResult.ExistingFinal>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    result = failure(),
                    completedAt = NOW.plusSeconds(4),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
                ),
            ),
        )
        assertEquals(reconciled, repeated.record)
    }

    @Test
    fun `final reconciliation audit failure rolls back state version provenance events and sequence`() = runTest {
        val store = FakeExecutionStore()
        val running = runningRecord(command())
        store.compareAndCreate(running, auditDraft())
        val unknown = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    state = ActionExecutionState.OUTCOME_UNKNOWN,
                    result = outcomeUnknown(),
                    updatedAt = NOW.plusSeconds(2),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.OUTCOME_UNKNOWN),
                ),
            ),
        ).record
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence
        store.failNextAudit = true

        val result = store.updateReconciliation(
            ReconciliationExecutionUpdate(
                executionId = unknown.command.executionId,
                expectedVersion = unknown.recordVersion,
                result = success(),
                completedAt = NOW.plusSeconds(3),
                audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
            ),
        )

        assertIs<ReconciliationUpdateResult.Conflict>(result)
        assertEquals(unknown, store.find(unknown.command.executionId))
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, store.find(unknown.command.executionId)?.state)
        assertEquals(unknown.recordVersion, store.find(unknown.command.executionId)?.recordVersion)
        assertNull(store.find(unknown.command.executionId)?.reconciliation)
        assertEquals(eventsBefore, store.auditEvents)
        assertEquals(sequenceBefore, store.auditSequence)
    }

    @Test
    fun `reconciliation conflicts on version mismatch or non-unknown state`() = runTest {
        val versionStore = FakeExecutionStore()
        val running = runningRecord(command())
        versionStore.compareAndCreate(running, auditDraft())
        val unknown = assertIs<ExecutionTransitionResult.Updated>(
            versionStore.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    state = ActionExecutionState.OUTCOME_UNKNOWN,
                    result = outcomeUnknown(),
                    updatedAt = NOW.plusSeconds(2),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.OUTCOME_UNKNOWN),
                ),
            ),
        ).record
        assertIs<ReconciliationUpdateResult.Conflict>(
            versionStore.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = unknown.recordVersion + 1,
                    result = success(),
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
                ),
            ),
        )

        val runningStore = FakeExecutionStore()
        runningStore.compareAndCreate(running, auditDraft())
        assertIs<ReconciliationUpdateResult.Conflict>(
            runningStore.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    result = failure(),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
                ),
            ),
        )

        val finalStore = FakeExecutionStore()
        finalStore.compareAndCreate(running, auditDraft())
        val final = assertIs<ExecutionTransitionResult.Updated>(
            finalStore.transition(
                ExecutionTransition(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    state = ActionExecutionState.FAILED,
                    result = failure(),
                    updatedAt = NOW.plusSeconds(2),
                    completedAt = NOW.plusSeconds(2),
                    audit = auditDraft(ActionExecutionState.EXECUTING, ActionExecutionState.FAILED),
                ),
            ),
        ).record
        assertIs<ReconciliationUpdateResult.Conflict>(
            finalStore.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = final.recordVersion,
                    result = success(),
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
                ),
            ),
        )
    }

    @Test
    fun `execution records require reconciliation provenance only on final results`() {
        val provenance = ReconciliationProvenance(
            sourceRecordVersion = 2,
            reconciledAt = NOW.plusSeconds(3),
        )

        assertFailsWith<IllegalArgumentException> {
            runningRecord(command()).copy(reconciliation = provenance)
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRecord(ActionExecutionState.OUTCOME_UNKNOWN, outcomeUnknown()).copy(
                reconciliation = provenance,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            terminalRecord(ActionExecutionState.SUCCEEDED, success()).copy(
                reconciliation = provenance,
                completedAt = NOW.plusSeconds(2),
                updatedAt = NOW.plusSeconds(2),
            )
        }

        val reconciled = terminalRecord(ActionExecutionState.FAILED, failure()).copy(
            completedAt = NOW.plusSeconds(3),
            updatedAt = NOW.plusSeconds(3),
            recordVersion = 3,
            reconciliation = provenance,
        )
        assertEquals(provenance, reconciled.reconciliation)
    }

    @Test
    fun `audit port surface is append only and event logs are redacted`() = runTest {
        val port = RecordingAuditPort()
        val event = ActionAuditEvent(
            executionId = "execution-1",
            sequence = 1,
            fromState = ActionExecutionState.RECEIVED,
            toState = ActionExecutionState.VALIDATING,
            type = "state_transition",
            redactedPayload = buildJsonObject { put("token", "secret-payload") },
            actorId = "secret-actor",
            occurredAt = NOW,
        )

        port.append(event)

        assertEquals(listOf(event), port.events)
        assertEquals(listOf("append"), ActionAuditPort::class.java.declaredMethods.map { it.name }.distinct())
        assertFalse("secret" in event.toString())
    }

    @Test
    fun `audit draft and transition logs redact nested secrets`() {
        val draft = ActionAuditDraft(
            executionId = "execution-1",
            fromState = ActionExecutionState.EXECUTING,
            toState = ActionExecutionState.SUCCEEDED,
            type = "secret-event-type",
            redactedPayload = buildJsonObject { put("token", "secret-payload") },
            actorId = "secret-actor",
            occurredAt = NOW,
        )
        val transition = ExecutionTransition(
            executionId = "execution-1",
            expectedVersion = 1,
            state = ActionExecutionState.SUCCEEDED,
            result = ActionResult.Success(
                "execution-1",
                buildJsonObject { put("token", "secret-result") },
                remoteReference = "secret-remote-reference",
            ),
            updatedAt = NOW,
            completedAt = NOW,
            audit = draft,
        )

        listOf(draft.toString(), transition.toString()).forEach { logged ->
            assertFalse("secret-payload" in logged)
            assertFalse("secret-actor" in logged)
            assertFalse("secret-result" in logged)
            assertFalse("secret-remote-reference" in logged)
        }
    }

    @Test
    fun `risk evaluation uses the explicit business severity table`() {
        val expected = mapOf(
            ActionRiskLevel.READ_ONLY to mapOf(
                ActionRiskLevel.READ_ONLY to ActionRiskLevel.READ_ONLY,
                ActionRiskLevel.REVERSIBLE_WRITE to ActionRiskLevel.REVERSIBLE_WRITE,
                ActionRiskLevel.HIGH_RISK to ActionRiskLevel.HIGH_RISK,
            ),
            ActionRiskLevel.REVERSIBLE_WRITE to mapOf(
                ActionRiskLevel.READ_ONLY to ActionRiskLevel.REVERSIBLE_WRITE,
                ActionRiskLevel.REVERSIBLE_WRITE to ActionRiskLevel.REVERSIBLE_WRITE,
                ActionRiskLevel.HIGH_RISK to ActionRiskLevel.HIGH_RISK,
            ),
            ActionRiskLevel.HIGH_RISK to mapOf(
                ActionRiskLevel.READ_ONLY to ActionRiskLevel.HIGH_RISK,
                ActionRiskLevel.REVERSIBLE_WRITE to ActionRiskLevel.HIGH_RISK,
                ActionRiskLevel.HIGH_RISK to ActionRiskLevel.HIGH_RISK,
            ),
        )

        expected.forEach { (baseRisk, proposedMappings) ->
            proposedMappings.forEach { (proposedRisk, effectiveRisk) ->
                val evaluation = RiskEvaluation.atLeast(baseRisk, proposedRisk, listOf("secret-reason"))
                assertEquals(effectiveRisk, evaluation.effectiveRisk, "$baseRisk + $proposedRisk")
                assertFalse("secret-reason" in evaluation.toString())
            }
        }
    }

    @Test
    fun `clock returns the injected instant`() {
        val clock = ActionClock { NOW }

        assertEquals(NOW, clock.now())
    }

    private fun command() = ActionCommand(
        executionId = "execution-1",
        actionId = "demo.action",
        input = buildJsonObject { put("token", "secret-input") },
        origin = ActionOrigin.AGENT,
        identityScope = identity(),
        pageId = "page-1",
        contextRevision = 3,
    )

    private fun context() = ActionContext(identity(), "page-1", 3, setOf("demo:write"))

    private fun identity() = ActionIdentityScope(
        desktopInstanceId = "secret-desktop",
        desktopSessionId = "secret-session",
        authSessionId = "secret-auth",
        identityEpoch = 4,
        userId = "secret-user",
        tenantId = "secret-tenant",
        platformId = "secret-platform",
    )

    private fun preview() = ActionPreview(
        executionId = "execution-1",
        summary = "secret-preview",
        redactedInput = buildJsonObject { put("token", "secret-preview-input") },
    )

    private fun descriptor(risk: ActionRiskLevel) = ActionDescriptor(
        id = "demo.action",
        version = 1,
        title = "演示动作",
        description = "端口测试动作",
        inputSchema = buildJsonObject { put("type", "object") },
        riskLevel = risk,
        requiredPermissions = setOf("demo:write"),
        target = ActionTarget("generic-form", "submit"),
        replayPolicy = ActionReplayPolicy.NEVER,
        reconciliationPolicy = ReconciliationPolicy.MANUAL,
    )

    private fun runningRecord(command: ActionCommand) = ActionExecutionRecord(
        command = command,
        fingerprint = ExecutionFingerprint(command.actionId, "fingerprint-1"),
        state = ActionExecutionState.EXECUTING,
        result = null,
        createdAt = NOW,
        startedAt = NOW.plusSeconds(1),
        completedAt = null,
        updatedAt = NOW.plusSeconds(1),
        recordVersion = 1,
    )

    private fun auditDraft(
        fromState: ActionExecutionState = ActionExecutionState.RECEIVED,
        toState: ActionExecutionState = ActionExecutionState.EXECUTING,
    ) = ActionAuditDraft(
        executionId = "execution-1",
        fromState = fromState,
        toState = toState,
        type = "state_transition",
        redactedPayload = buildJsonObject { },
        actorId = null,
        occurredAt = NOW,
    )

    private fun terminalRecord(
        state: ActionExecutionState,
        result: ActionResult<JsonElement>,
    ) = ActionExecutionRecord(
        command = command(),
        fingerprint = ExecutionFingerprint("demo.action", "fingerprint-1"),
        state = state,
        result = result,
        createdAt = NOW,
        startedAt = NOW.plusSeconds(1),
        completedAt = NOW.plusSeconds(2),
        updatedAt = NOW.plusSeconds(2),
        recordVersion = 2,
    )

    private fun success(executionId: String = "execution-1"): ActionResult<JsonElement> =
        ActionResult.Success(
            executionId = executionId,
            output = buildJsonObject { put("saved", true) },
        )

    private fun failure(executionId: String = "execution-1"): ActionResult<JsonElement> =
        ActionResult.Failure(
            executionId = executionId,
            error = com.wzx.huitai.action.model.ActionError(
                ActionErrorCode.REMOTE_REQUEST_FAILED,
                "secret-error",
            ),
        )

    private fun canceled(executionId: String = "execution-1"): ActionResult<JsonElement> =
        ActionResult.Canceled(executionId, "secret-cancel-reason")

    private fun expired(executionId: String = "execution-1"): ActionResult<JsonElement> =
        ActionResult.Expired(executionId, "secret-expiry-reason")

    private fun outcomeUnknown(executionId: String = "execution-1"): ActionResult<JsonElement> =
        ActionResult.OutcomeUnknown(
            executionId = executionId,
            error = com.wzx.huitai.action.model.ActionError(
                ActionErrorCode.OUTCOME_UNKNOWN,
                "secret-unknown-error",
            ),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )

    private class QueueConfirmationPort(private val decision: ActionConfirmation) : ActionConfirmationPort {
        var requests = 0
        override suspend fun request(
            command: ActionCommand,
            preview: ActionPreview,
            context: ActionContext,
        ): ActionConfirmation {
            requests += 1
            return decision
        }
    }

    private class QueueApprovalPort(private val decision: ActionApproval) : ActionApprovalPort {
        var requests = 0
        override suspend fun request(
            command: ActionCommand,
            preview: ActionPreview,
            riskEvaluation: RiskEvaluation,
            context: ActionContext,
        ): ActionApproval {
            requests += 1
            return decision
        }
    }

    private class FakeExecutionStore : ActionExecutionStore {
        private val records = mutableMapOf<String, ActionExecutionRecord>()
        val auditEvents = mutableListOf<ActionAuditEvent>()
        private var nextAuditSequence = 0L
        var failNextAudit = false
        val auditSequence: Long
            get() = nextAuditSequence

        override suspend fun find(executionId: String): ActionExecutionRecord? = records[executionId]

        override suspend fun compareAndCreate(
            record: ActionExecutionRecord,
            audit: ActionAuditDraft,
        ): ExecutionCreateResult {
            val existing = records[record.command.executionId]
            if (existing == null) {
                records[record.command.executionId] = record
                appendAudit(audit)
                return ExecutionCreateResult.Created(record)
            }
            if (existing.fingerprint != record.fingerprint) {
                return ExecutionCreateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "execution fingerprint conflict",
                    ),
                )
            }
            return if (existing.isTerminal) {
                ExecutionCreateResult.ExistingTerminal(existing)
            } else {
                ExecutionCreateResult.ExistingRunning(existing)
            }
        }

        override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
            val existing = records.getValue(update.executionId)
            if (existing.isTerminal) return ExecutionTransitionResult.ExistingTerminal(existing)
            if (existing.recordVersion != update.expectedVersion) {
                return ExecutionTransitionResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "transition conflict",
                    ),
                )
            }
            val updated = existing.copy(
                state = update.state,
                result = update.result,
                successFact = update.successFact,
                startedAt = update.startedAt ?: existing.startedAt,
                completedAt = update.completedAt,
                updatedAt = update.updatedAt,
                recordVersion = existing.recordVersion + 1,
            )
            records[update.executionId] = updated
            appendAudit(update.audit)
            return ExecutionTransitionResult.Updated(updated)
        }

        private fun prepareAudit(draft: ActionAuditDraft): ActionAuditEvent {
            if (failNextAudit) {
                failNextAudit = false
                error("audit insertion failed")
            }
            return ActionAuditEvent(
                executionId = draft.executionId,
                sequence = nextAuditSequence + 1,
                fromState = draft.fromState,
                toState = draft.toState,
                type = draft.type,
                redactedPayload = draft.redactedPayload,
                actorId = draft.actorId,
                occurredAt = draft.occurredAt,
            )
        }

        private fun appendAudit(draft: ActionAuditDraft) {
            val event = prepareAudit(draft)
            auditEvents += event
            nextAuditSequence = event.sequence
        }

        override suspend fun updateReconciliation(
            update: ReconciliationExecutionUpdate,
        ): ReconciliationUpdateResult {
            val existing = records.getValue(update.executionId)
            if (existing.reconciliation?.sourceRecordVersion == update.expectedVersion) {
                return ReconciliationUpdateResult.ExistingFinal(existing)
            }
            if (!existing.needsReconciliation || existing.recordVersion != update.expectedVersion) {
                return ReconciliationUpdateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation state or version conflict",
                    ),
                )
            }
            val terminalState = when (update.result) {
                is ActionResult.Success<*> -> ActionExecutionState.SUCCEEDED
                is ActionResult.Failure -> ActionExecutionState.FAILED
                else -> error("ReconciliationExecutionUpdate 已限制结果类型")
            }
            val updated = existing.copy(
                state = terminalState,
                result = update.result,
                completedAt = update.completedAt,
                updatedAt = update.completedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliation = ReconciliationProvenance(
                    sourceRecordVersion = existing.recordVersion,
                    reconciledAt = update.completedAt,
                ),
            )
            val audit = try {
                prepareAudit(update.audit)
            } catch (_: Exception) {
                return ReconciliationUpdateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation audit rolled back",
                    ),
                )
            }
            records[update.executionId] = updated
            auditEvents += audit
            nextAuditSequence = audit.sequence
            return ReconciliationUpdateResult.Updated(updated)
        }

        override suspend fun appendReconciliationAudit(
            append: ReconciliationAuditAppend,
        ): ReconciliationAuditAppendResult {
            val existing = records.getValue(append.executionId)
            if (existing.isFinalTerminal) return ReconciliationAuditAppendResult.ExistingFinal(existing)
            if (!existing.needsReconciliation || existing.recordVersion != append.expectedVersion) {
                return ReconciliationAuditAppendResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation audit conflict",
                    ),
                )
            }
            appendAudit(append.audit)
            return ReconciliationAuditAppendResult.Appended(existing)
        }
    }

    private class RecordingAuditPort : ActionAuditPort {
        val events = mutableListOf<ActionAuditEvent>()
        override suspend fun append(event: ActionAuditEvent) {
            events += event
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
