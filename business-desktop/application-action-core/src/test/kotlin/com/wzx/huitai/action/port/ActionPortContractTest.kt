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

        val created = assertIs<ExecutionCreateResult.Created>(store.compareAndCreate(running))
        assertSame(running, created.record)
        assertIs<ExecutionCreateResult.ExistingRunning>(store.compareAndCreate(running.copy()))

        val conflict = assertIs<ExecutionCreateResult.Conflict>(
            store.compareAndCreate(
                running.copy(fingerprint = ExecutionFingerprint("other.action", "other-fingerprint")),
            ),
        )
        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, conflict.error.code)

        val terminalResult: ActionResult<JsonElement> = ActionResult.Success(
            executionId = running.command.executionId,
            output = buildJsonObject { put("saved", true) },
        )
        val updated = assertIs<TerminalUpdateResult.Updated>(
            store.updateTerminal(
                TerminalExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    terminalState = ActionExecutionState.SUCCEEDED,
                    result = terminalResult,
                    completedAt = NOW.plusSeconds(2),
                ),
            ),
        )
        assertEquals(terminalResult, updated.record.result)
        assertEquals(ActionExecutionState.SUCCEEDED, updated.record.state)
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running))
        val repeated = assertIs<TerminalUpdateResult.ExistingTerminal>(
            store.updateTerminal(
                TerminalExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = updated.record.recordVersion,
                    terminalState = ActionExecutionState.FAILED,
                    result = failure(running.command.executionId),
                    completedAt = NOW.plusSeconds(3),
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
    fun `terminal and reconciliation updates reject invalid structured payloads`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                terminalState = ActionExecutionState.SUCCEEDED,
                result = failure(),
                completedAt = NOW,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                terminalState = ActionExecutionState.EXECUTING,
                result = success(),
                completedAt = NOW,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                terminalState = ActionExecutionState.SUCCEEDED,
                result = success("other-execution"),
                completedAt = NOW,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                result = canceled(),
                completedAt = NOW,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                result = success("other-execution"),
                completedAt = NOW,
            )
        }
    }

    @Test
    fun `outcome unknown blocks replay and can be reconciled exactly once`() = runTest {
        val store = FakeExecutionStore()
        val running = runningRecord(command())
        store.compareAndCreate(running)
        val unknownResult = outcomeUnknown()
        val unknown = assertIs<TerminalUpdateResult.Updated>(
            store.updateTerminal(
                TerminalExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    terminalState = ActionExecutionState.OUTCOME_UNKNOWN,
                    result = unknownResult,
                    completedAt = NOW.plusSeconds(2),
                ),
            ),
        ).record

        assertTrue(unknown.isTerminal)
        assertFalse(unknown.isFinalTerminal)
        assertTrue(unknown.needsReconciliation)
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running))

        val reconciledResult = success()
        val reconciled = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    result = reconciledResult,
                    completedAt = NOW.plusSeconds(3),
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
                ),
            ),
        )
        assertEquals(reconciled, repeated.record)
    }

    @Test
    fun `reconciliation conflicts on version mismatch or non-unknown state`() = runTest {
        val versionStore = FakeExecutionStore()
        val running = runningRecord(command())
        versionStore.compareAndCreate(running)
        val unknown = assertIs<TerminalUpdateResult.Updated>(
            versionStore.updateTerminal(
                TerminalExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    terminalState = ActionExecutionState.OUTCOME_UNKNOWN,
                    result = outcomeUnknown(),
                    completedAt = NOW.plusSeconds(2),
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
                ),
            ),
        )

        val runningStore = FakeExecutionStore()
        runningStore.compareAndCreate(running)
        assertIs<ReconciliationUpdateResult.Conflict>(
            runningStore.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    result = failure(),
                    completedAt = NOW.plusSeconds(2),
                ),
            ),
        )

        val finalStore = FakeExecutionStore()
        finalStore.compareAndCreate(running)
        val final = assertIs<TerminalUpdateResult.Updated>(
            finalStore.updateTerminal(
                TerminalExecutionUpdate(
                    executionId = running.command.executionId,
                    expectedVersion = running.recordVersion,
                    terminalState = ActionExecutionState.FAILED,
                    result = failure(),
                    completedAt = NOW.plusSeconds(2),
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

        override suspend fun find(executionId: String): ActionExecutionRecord? = records[executionId]

        override suspend fun compareAndCreate(record: ActionExecutionRecord): ExecutionCreateResult {
            val existing = records[record.command.executionId]
            if (existing == null) {
                records[record.command.executionId] = record
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

        override suspend fun updateTerminal(update: TerminalExecutionUpdate): TerminalUpdateResult {
            val existing = records.getValue(update.executionId)
            if (existing.isTerminal) return TerminalUpdateResult.ExistingTerminal(existing)
            if (existing.recordVersion != update.expectedVersion) {
                return TerminalUpdateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "record version conflict",
                    ),
                )
            }
            val updated = existing.copy(
                state = update.terminalState,
                result = update.result,
                completedAt = update.completedAt,
                updatedAt = update.completedAt,
                recordVersion = existing.recordVersion + 1,
            )
            records[update.executionId] = updated
            return TerminalUpdateResult.Updated(updated)
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
            records[update.executionId] = updated
            return ReconciliationUpdateResult.Updated(updated)
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
