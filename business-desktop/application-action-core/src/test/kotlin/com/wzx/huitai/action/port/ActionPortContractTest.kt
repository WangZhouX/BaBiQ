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
                executionId = running.command.executionId,
                expectedVersion = running.recordVersion,
                terminalState = ActionExecutionState.SUCCEEDED,
                result = terminalResult,
                completedAt = NOW.plusSeconds(2),
            ),
        )
        assertEquals(terminalResult, updated.record.result)
        assertEquals(ActionExecutionState.SUCCEEDED, updated.record.state)
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running))
        val repeated = assertIs<TerminalUpdateResult.ExistingTerminal>(
            store.updateTerminal(
                executionId = running.command.executionId,
                expectedVersion = updated.record.recordVersion,
                terminalState = ActionExecutionState.FAILED,
                result = ActionResult.Failure(
                    running.command.executionId,
                    com.wzx.huitai.action.model.ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "secret-error"),
                ),
                completedAt = NOW.plusSeconds(3),
            ),
        )
        assertEquals(updated.record, repeated.record)
        assertFalse("secret-input" in updated.record.toString())
        assertFalse("fingerprint-1" in running.fingerprint.toString())
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
    fun `risk evaluation can raise but never lower descriptor risk`() {
        val descriptor = descriptor(ActionRiskLevel.HIGH_RISK)
        val loweringPolicy = ActionRiskPolicy { current, _, _ ->
            RiskEvaluation.atLeast(current.riskLevel, ActionRiskLevel.READ_ONLY, listOf("model-proposed-lower"))
        }
        val raised = RiskEvaluation.atLeast(
            ActionRiskLevel.READ_ONLY,
            ActionRiskLevel.REVERSIBLE_WRITE,
            listOf("sensitive-field"),
        )

        val normalized = loweringPolicy.evaluate(descriptor, command(), context())

        assertEquals(ActionRiskLevel.HIGH_RISK, normalized.effectiveRisk)
        assertEquals(ActionRiskLevel.REVERSIBLE_WRITE, raised.effectiveRisk)
        assertFalse("model-proposed-lower" in normalized.toString())
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

        override suspend fun updateTerminal(
            executionId: String,
            expectedVersion: Long,
            terminalState: ActionExecutionState,
            result: ActionResult<JsonElement>,
            completedAt: Instant,
        ): TerminalUpdateResult {
            val existing = records.getValue(executionId)
            if (existing.isTerminal) return TerminalUpdateResult.ExistingTerminal(existing)
            if (existing.recordVersion != expectedVersion) {
                return TerminalUpdateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "record version conflict",
                    ),
                )
            }
            val updated = existing.copy(
                state = terminalState,
                result = result,
                completedAt = completedAt,
                updatedAt = completedAt,
                recordVersion = existing.recordVersion + 1,
            )
            records[executionId] = updated
            return TerminalUpdateResult.Updated(updated)
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
