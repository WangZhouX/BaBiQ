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
import java.time.Duration
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
            .filter {
                it.name in setOf(
                    "updateReconciliation",
                    "claimReconciliation",
                    "renewReconciliation",
                    "releaseReconciliation",
                )
            }
        assertEquals(4, reconciliationMethods.size)
        assertTrue(
            reconciliationMethods.all { method ->
                method.parameterTypes
                    .first { it != kotlin.coroutines.Continuation::class.java }
                    .declaredFields.any { it.type == ActionAuditDraft::class.java }
            },
        )
        assertTrue(ActionExecutionStore::class.java.methods.none { it.name == "appendReconciliationAudit" })
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
                running.copy(
                    binding = running.binding.copy(
                        actionId = "other.action",
                        inputFingerprint = "other-fingerprint",
                    ),
                ),
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
        assertFalse("fingerprint-1" in running.binding.toString())
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
                claimToken = "claim-token",
                result = canceled(),
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.CANCELED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                claimToken = "claim-token",
                result = success(),
                completedAt = NOW,
                audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReconciliationExecutionUpdate(
                executionId = "execution-1",
                expectedVersion = 1,
                claimToken = "claim-token",
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
        val claimed = claimUnknown(store)

        assertTrue(claimed.isTerminal)
        assertFalse(claimed.isFinalTerminal)
        assertTrue(claimed.needsReconciliation)
        assertIs<ExecutionCreateResult.ExistingTerminal>(
            store.compareAndCreate(runningRecord(claimed.command), auditDraft()),
        )

        val claimToken = claimed.reconciliationClaim!!.claimToken
        val reconciled = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = claimed.command.executionId,
                    expectedVersion = claimed.recordVersion,
                    claimToken = claimToken,
                    result = failure(),
                    completedAt = NOW.plusSeconds(4),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
                ),
            ),
        ).record
        assertEquals(ActionExecutionState.FAILED, reconciled.state)
        assertEquals(failure(), reconciled.result)
        assertTrue(reconciled.isFinalTerminal)
        assertFalse(reconciled.needsReconciliation)
        assertEquals(claimed.recordVersion, reconciled.reconciliation?.sourceRecordVersion)
        assertEquals(NOW.plusSeconds(4), reconciled.reconciliation?.reconciledAt)
        assertNull(reconciled.reconciliationClaim)

        val repeated = assertIs<ReconciliationUpdateResult.ExistingFinal>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = claimed.command.executionId,
                    expectedVersion = claimed.recordVersion,
                    claimToken = claimToken,
                    result = null,
                    successFact = ExecutionSuccessFact(
                        kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
                        errorCode = null,
                        safeMessage = null,
                        source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
                    ),
                    completedAt = NOW.plusSeconds(5),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED),
                ),
            ),
        )
        assertEquals(reconciled, repeated.record)
    }

    @Test
    fun `final reconciliation success fact validates claim and commits atomically`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val fact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
            remoteReference = "secret-remote-reference",
            errorCode = null,
            safeMessage = null,
            source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
        )
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence

        val wrongOwner = store.updateReconciliation(
            finalSuccessFactUpdate(claimed, fact, "wrong-token"),
        )

        assertIs<ReconciliationUpdateResult.Conflict>(wrongOwner)
        assertEquals(claimed, store.find(claimed.command.executionId))
        assertEquals(eventsBefore, store.auditEvents)
        assertEquals(sequenceBefore, store.auditSequence)

        val updated = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(
                finalSuccessFactUpdate(claimed, fact, claimed.reconciliationClaim!!.claimToken),
            ),
        ).record

        assertEquals(ActionExecutionState.SUCCEEDED, updated.state)
        assertNull(updated.result)
        assertEquals(fact, updated.successFact)
        assertNull(updated.reconciliationClaim)
        assertEquals(claimed.recordVersion, updated.reconciliation?.sourceRecordVersion)
        assertEquals(NOW.plusSeconds(4), updated.reconciliation?.reconciledAt)
        assertEquals(eventsBefore.size + 1, store.auditEvents.size)
        assertEquals(sequenceBefore + 1, store.auditSequence)
    }

    @Test
    fun `reconciliation claim grants one persistent owner and audits atomically`() = runTest {
        val store = FakeExecutionStore()
        val unknown = installUnknown(store)
        val claimAudit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN)

        val claimed = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(
                ReconciliationClaimRequest(
                    executionId = unknown.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    claimToken = "secret-claim-token-1",
                    ownerId = "secret-owner-1",
                    now = NOW.plusSeconds(3),
                    leaseDuration = Duration.ofSeconds(60),
                    audit = claimAudit.copy(type = "reconciliation_attempt"),
                ),
            ),
        ).record
        val duplicate = assertIs<ReconciliationClaimResult.ExistingClaim>(
            store.claimReconciliation(
                ReconciliationClaimRequest(
                    executionId = unknown.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    claimToken = "secret-claim-token-2",
                    ownerId = "secret-owner-2",
                    now = NOW.plusSeconds(4),
                    leaseDuration = Duration.ofSeconds(60),
                    audit = claimAudit.copy(type = "reconciliation_attempt"),
                ),
            ),
        )

        assertEquals(unknown.recordVersion + 1, claimed.recordVersion)
        assertEquals("secret-claim-token-1", claimed.reconciliationClaim?.claimToken)
        assertEquals(claimed, duplicate.record)
        assertEquals(1, store.auditEvents.count { it.type == "reconciliation_attempt" })
        assertFalse("secret-claim-token" in claimed.toString())
        assertFalse("secret-owner" in claimed.reconciliationClaim.toString())
    }

    @Test
    fun `reconciliation claim lease requires positive duration and redacts ownership metadata`() {
        val claim = ReconciliationClaim(
            claimToken = "secret-claim-token",
            ownerId = "secret-owner",
            claimedAt = NOW,
            expiresAt = NOW.plusSeconds(60),
        )
        val request = ReconciliationClaimRequest(
            executionId = "execution-1",
            expectedVersion = 1,
            claimToken = "secret-claim-token",
            ownerId = "secret-owner",
            now = NOW,
            leaseDuration = Duration.ofSeconds(60),
            audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN)
                .copy(type = "reconciliation_attempt"),
        )

        assertEquals(NOW.plusSeconds(60), claim.expiresAt)
        assertEquals(NOW.plusSeconds(60), request.expiresAt)
        assertFalse("secret-claim-token" in claim.toString())
        assertFalse("secret-owner" in claim.toString())
        assertFalse(NOW.toString() in claim.toString())
        assertFailsWith<IllegalArgumentException> {
            claim.copy(expiresAt = NOW.minusSeconds(1))
        }
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { invalidDuration ->
            assertFailsWith<IllegalArgumentException> {
                request.copy(leaseDuration = invalidDuration)
            }
        }
    }

    @Test
    fun `expired reconciliation claim takeover is atomic and stale owner cannot mutate`() = runTest {
        val store = FakeExecutionStore()
        val firstOwner = claimUnknown(store)
        val takeoverAudit = auditDraft(
            ActionExecutionState.OUTCOME_UNKNOWN,
            ActionExecutionState.OUTCOME_UNKNOWN,
        ).copy(
            type = "reconciliation_attempt",
            redactedPayload = buildJsonObject { put("takeover", true) },
            occurredAt = firstOwner.reconciliationClaim!!.expiresAt,
        )

        val secondOwner = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(
                ReconciliationClaimRequest(
                    executionId = firstOwner.command.executionId,
                    expectedVersion = firstOwner.recordVersion,
                    claimToken = "second-claim-token",
                    ownerId = "second-owner",
                    now = firstOwner.reconciliationClaim.expiresAt,
                    leaseDuration = Duration.ofSeconds(60),
                    audit = takeoverAudit,
                ),
            ),
        ).record

        assertEquals(firstOwner.recordVersion + 1, secondOwner.recordVersion)
        assertEquals("second-claim-token", secondOwner.reconciliationClaim?.claimToken)
        assertEquals(firstOwner.reconciliationClaim.expiresAt, secondOwner.reconciliationClaim?.claimedAt)
        assertEquals(firstOwner.reconciliationClaim.expiresAt.plusSeconds(60), secondOwner.reconciliationClaim?.expiresAt)
        assertEquals(2, store.auditEvents.count { it.type == "reconciliation_attempt" })
        assertTrue(store.auditEvents.last().redactedPayload.toString().contains("\"takeover\":true"))

        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(
                releaseRequest(firstOwner, firstOwner.reconciliationClaim.claimToken),
            ),
        )
        assertIs<ReconciliationUpdateResult.Conflict>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = firstOwner.command.executionId,
                    expectedVersion = firstOwner.recordVersion,
                    claimToken = firstOwner.reconciliationClaim.claimToken,
                    result = failure(),
                    completedAt = firstOwner.reconciliationClaim.expiresAt.plusSeconds(1),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
                ),
            ),
        )

        val finalized = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(
                ReconciliationExecutionUpdate(
                    executionId = secondOwner.command.executionId,
                    expectedVersion = secondOwner.recordVersion,
                    claimToken = secondOwner.reconciliationClaim!!.claimToken,
                    result = failure(),
                    completedAt = secondOwner.reconciliationClaim.claimedAt.plusSeconds(1),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
                ),
            ),
        ).record
        assertEquals(ActionExecutionState.FAILED, finalized.state)
        assertNull(finalized.reconciliationClaim)
    }

    @Test
    fun `expired reconciliation takeover audit failure rolls back claim version events and sequence`() = runTest {
        val store = FakeExecutionStore()
        val firstOwner = claimUnknown(store)
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence
        store.failNextAudit = true

        val result = store.claimReconciliation(
            ReconciliationClaimRequest(
                executionId = firstOwner.command.executionId,
                expectedVersion = firstOwner.recordVersion,
                claimToken = "second-claim-token",
                ownerId = "second-owner",
                now = firstOwner.reconciliationClaim!!.expiresAt,
                leaseDuration = Duration.ofSeconds(60),
                audit = auditDraft(
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                ).copy(
                    type = "reconciliation_attempt",
                    redactedPayload = buildJsonObject { put("takeover", true) },
                ),
            ),
        )

        assertIs<ReconciliationClaimResult.Conflict>(result)
        assertEquals(firstOwner, store.find(firstOwner.command.executionId))
        assertEquals(eventsBefore, store.auditEvents)
        assertEquals(sequenceBefore, store.auditSequence)
    }

    @Test
    fun `reconciliation release requires claim token and rolls back on audit failure`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence

        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(
                releaseRequest(claimed, "wrong-token"),
            ),
        )
        store.failNextAudit = true
        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(
                releaseRequest(claimed, claimed.reconciliationClaim!!.claimToken),
            ),
        )

        assertEquals(claimed, store.find(claimed.command.executionId))
        assertEquals(eventsBefore, store.auditEvents)
        assertEquals(sequenceBefore, store.auditSequence)
    }

    @Test
    fun `reconciliation renew atomically extends lease and preserves unknown facts`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val claim = claimed.reconciliationClaim!!
        val renewedAt = claim.claimedAt.plusSeconds(15)

        val renewed = assertIs<ReconciliationRenewResult.Renewed>(
            store.renewReconciliation(renewRequest(claimed, claim.claimToken, renewedAt)),
        ).record

        assertEquals(claimed.recordVersion + 1, renewed.recordVersion)
        assertEquals(claimed.state, renewed.state)
        assertEquals(claimed.result, renewed.result)
        assertEquals(claimed.successFact, renewed.successFact)
        assertEquals(claimed.reconciliation, renewed.reconciliation)
        assertEquals(claim.claimToken, renewed.reconciliationClaim?.claimToken)
        assertEquals(claim.ownerId, renewed.reconciliationClaim?.ownerId)
        assertEquals(claim.claimedAt, renewed.reconciliationClaim?.claimedAt)
        assertEquals(renewedAt.plusSeconds(60), renewed.reconciliationClaim?.expiresAt)
        assertEquals(renewedAt, renewed.updatedAt)
        assertEquals("reconciliation_claim_renewed", store.auditEvents.last().type)
        assertIs<ReconciliationUpdateResult.Conflict>(
            store.updateReconciliation(finalSuccessFactUpdate(claimed, reconciledSuccessFact(), claim.claimToken)),
        )
        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(releaseRequest(claimed, claim.claimToken)),
        )
    }

    @Test
    fun `reconciliation renew fences wrong token and version without mutation`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val beforeEvents = store.auditEvents.toList()
        val beforeSequence = store.auditSequence
        val now = claimed.reconciliationClaim!!.claimedAt.plusSeconds(15)

        assertIs<ReconciliationRenewResult.ExistingClaim>(
            store.renewReconciliation(renewRequest(claimed, "wrong-token", now)),
        )
        assertIs<ReconciliationRenewResult.Conflict>(
            store.renewReconciliation(
                renewRequest(claimed, claimed.reconciliationClaim.claimToken, now)
                    .copy(expectedVersion = claimed.recordVersion - 1),
            ),
        )

        assertEquals(claimed, store.find(claimed.command.executionId))
        assertEquals(beforeEvents, store.auditEvents)
        assertEquals(beforeSequence, store.auditSequence)
    }

    @Test
    fun `reconciliation renew audit failure rolls back record version lease event and sequence`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence
        store.failNextAudit = true

        val result = store.renewReconciliation(
            renewRequest(
                claimed,
                claimed.reconciliationClaim!!.claimToken,
                claimed.reconciliationClaim.claimedAt.plusSeconds(15),
            ),
        )

        assertIs<ReconciliationRenewResult.Conflict>(result)
        assertEquals(claimed, store.find(claimed.command.executionId))
        assertEquals(eventsBefore, store.auditEvents)
        assertEquals(sequenceBefore, store.auditSequence)
    }

    @Test
    fun `reconciliation renew request validates audit and redacts lease secrets`() {
        val request = ReconciliationRenewRequest(
            executionId = "execution-1",
            expectedVersion = 3,
            claimToken = "secret-renew-token",
            now = NOW.plusSeconds(18),
            leaseDuration = Duration.ofSeconds(60),
            audit = auditDraft(
                ActionExecutionState.OUTCOME_UNKNOWN,
                ActionExecutionState.OUTCOME_UNKNOWN,
            ).copy(
                type = "reconciliation_claim_renewed",
                occurredAt = NOW.plusSeconds(18),
            ),
        )

        assertFalse(request.toString().contains("secret-renew-token"))
        assertFalse(request.toString().contains("2026-07-14T00:00:18Z"))
        assertFailsWith<IllegalArgumentException> {
            request.copy(leaseDuration = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(audit = request.audit.copy(type = "reconciliation_attempt"))
        }
    }

    private suspend fun installUnknown(store: FakeExecutionStore): ActionExecutionRecord {
        val running = runningRecord(command())
        store.compareAndCreate(running, auditDraft())
        return assertIs<ExecutionTransitionResult.Updated>(
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
    }

    private suspend fun claimUnknown(store: FakeExecutionStore): ActionExecutionRecord {
        val unknown = installUnknown(store)
        return assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(
                ReconciliationClaimRequest(
                    executionId = unknown.command.executionId,
                    expectedVersion = unknown.recordVersion,
                    claimToken = "secret-claim-token",
                    ownerId = "secret-owner",
                    now = NOW.plusSeconds(3),
                    leaseDuration = Duration.ofSeconds(60),
                    audit = auditDraft(
                        ActionExecutionState.OUTCOME_UNKNOWN,
                        ActionExecutionState.OUTCOME_UNKNOWN,
                    ).copy(type = "reconciliation_attempt"),
                ),
            ),
        ).record
    }

    private fun releaseRequest(claimed: ActionExecutionRecord, token: String) = ReconciliationReleaseRequest(
        executionId = claimed.command.executionId,
        expectedVersion = claimed.recordVersion,
        claimToken = token,
        releasedAt = NOW.plusSeconds(4),
        audit = auditDraft(
            ActionExecutionState.OUTCOME_UNKNOWN,
            ActionExecutionState.OUTCOME_UNKNOWN,
        ).copy(type = "reconciliation_result"),
    )

    private fun renewRequest(
        claimed: ActionExecutionRecord,
        token: String,
        now: Instant,
    ) = ReconciliationRenewRequest(
        executionId = claimed.command.executionId,
        expectedVersion = claimed.recordVersion,
        claimToken = token,
        now = now,
        leaseDuration = Duration.ofSeconds(60),
        audit = auditDraft(
            ActionExecutionState.OUTCOME_UNKNOWN,
            ActionExecutionState.OUTCOME_UNKNOWN,
        ).copy(
            type = "reconciliation_claim_renewed",
            occurredAt = now,
        ),
    )

    private fun reconciledSuccessFact() = ExecutionSuccessFact(
        kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
        remoteReference = "remote-reference",
        errorCode = null,
        safeMessage = null,
        source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
    )

    private fun finalSuccessFactUpdate(
        claimed: ActionExecutionRecord,
        fact: ExecutionSuccessFact,
        token: String,
    ) = ReconciliationExecutionUpdate(
        executionId = claimed.command.executionId,
        expectedVersion = claimed.recordVersion,
        claimToken = token,
        result = null,
        successFact = fact,
        completedAt = NOW.plusSeconds(4),
        audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED)
            .copy(type = "reconciliation_result"),
    )

    @Test
    fun `final reconciliation audit failure rolls back state version provenance events and sequence`() = runTest {
        val store = FakeExecutionStore()
        val claimed = claimUnknown(store)
        val fact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
            remoteReference = "remote-reference",
            errorCode = null,
            safeMessage = null,
            source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
        )
        val eventsBefore = store.auditEvents.toList()
        val sequenceBefore = store.auditSequence
        store.failNextAudit = true

        val result = store.updateReconciliation(
            finalSuccessFactUpdate(claimed, fact, claimed.reconciliationClaim!!.claimToken),
        )

        assertIs<ReconciliationUpdateResult.Conflict>(result)
        assertEquals(claimed, store.find(claimed.command.executionId))
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, store.find(claimed.command.executionId)?.state)
        assertEquals(claimed.recordVersion, store.find(claimed.command.executionId)?.recordVersion)
        assertNull(store.find(claimed.command.executionId)?.successFact)
        assertNull(store.find(claimed.command.executionId)?.reconciliation)
        assertEquals(claimed.reconciliationClaim, store.find(claimed.command.executionId)?.reconciliationClaim)
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
                    claimToken = "claim-token",
                    result = failure(),
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
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
                    claimToken = "claim-token",
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
                    claimToken = "claim-token",
                    result = failure(),
                    completedAt = NOW.plusSeconds(3),
                    audit = auditDraft(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.FAILED),
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
        actionVersion = 1,
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
        binding = binding(command),
        riskLevel = ActionRiskLevel.HIGH_RISK,
        state = ActionExecutionState.EXECUTING,
        result = null,
        createdAt = NOW,
        startedAt = NOW.plusSeconds(1),
        completedAt = null,
        updatedAt = NOW.plusSeconds(1),
        recordVersion = 1,
    )

    private fun binding(command: ActionCommand) = ExecutionBinding(
        actionId = command.actionId,
        actionVersion = command.actionVersion,
        inputFingerprint = "fingerprint-1",
        origin = command.origin,
        identityScope = command.identityScope,
        pageId = command.pageId,
        contextRevision = command.contextRevision,
        correlation = command.correlation,
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
        binding = binding(command()),
        riskLevel = ActionRiskLevel.HIGH_RISK,
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
            if (existing.binding != record.binding) {
                return ExecutionCreateResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "execution binding conflict",
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
            if (!existing.needsReconciliation ||
                existing.recordVersion != update.expectedVersion ||
                existing.reconciliationClaim?.claimToken != update.claimToken
            ) {
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
                null -> ActionExecutionState.SUCCEEDED
                else -> error("ReconciliationExecutionUpdate 已限制结果类型")
            }
            val updated = existing.copy(
                state = terminalState,
                result = update.result,
                successFact = update.successFact,
                completedAt = update.completedAt,
                updatedAt = update.completedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliation = ReconciliationProvenance(
                    sourceRecordVersion = existing.recordVersion,
                    reconciledAt = update.completedAt,
                ),
                reconciliationClaim = null,
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

        override suspend fun claimReconciliation(
            request: ReconciliationClaimRequest,
        ): ReconciliationClaimResult {
            val existing = records.getValue(request.executionId)
            if (existing.isFinalTerminal) return ReconciliationClaimResult.ExistingFinal(existing)
            existing.reconciliationClaim?.let { existingClaim ->
                if (request.now.isBefore(existingClaim.expiresAt)) {
                    return ReconciliationClaimResult.ExistingClaim(existing)
                }
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return ReconciliationClaimResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation claim conflict",
                    ),
                )
            }
            val audit = try {
                prepareAudit(request.audit)
            } catch (_: Exception) {
                return ReconciliationClaimResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation claim audit rolled back",
                    ),
                )
            }
            val claimed = existing.copy(
                updatedAt = request.now,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = ReconciliationClaim(
                    request.claimToken,
                    request.ownerId,
                    request.now,
                    request.expiresAt,
                ),
            )
            records[request.executionId] = claimed
            auditEvents += audit
            nextAuditSequence = audit.sequence
            return ReconciliationClaimResult.Claimed(claimed)
        }

        override suspend fun renewReconciliation(
            request: ReconciliationRenewRequest,
        ): ReconciliationRenewResult {
            val existing = records.getValue(request.executionId)
            if (existing.isFinalTerminal) return ReconciliationRenewResult.ExistingFinal(existing)
            val claim = existing.reconciliationClaim ?: return ReconciliationRenewResult.Conflict(
                com.wzx.huitai.action.model.ActionError(
                    ActionErrorCode.EXECUTION_CONFLICT,
                    "reconciliation renew claim missing",
                ),
            )
            if (claim.claimToken != request.claimToken) {
                return ReconciliationRenewResult.ExistingClaim(existing)
            }
            if (!existing.needsReconciliation || existing.recordVersion != request.expectedVersion) {
                return ReconciliationRenewResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation renew conflict",
                    ),
                )
            }
            val renewed = try {
                existing.copy(
                    updatedAt = request.now,
                    recordVersion = existing.recordVersion + 1,
                    reconciliationClaim = claim.copy(expiresAt = request.expiresAt),
                )
            } catch (_: IllegalArgumentException) {
                return ReconciliationRenewResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation renew time conflict",
                    ),
                )
            }
            val audit = try {
                prepareAudit(request.audit)
            } catch (_: Exception) {
                return ReconciliationRenewResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation renew audit rolled back",
                    ),
                )
            }
            records[request.executionId] = renewed
            auditEvents += audit
            nextAuditSequence = audit.sequence
            return ReconciliationRenewResult.Renewed(renewed)
        }

        override suspend fun releaseReconciliation(
            request: ReconciliationReleaseRequest,
        ): ReconciliationReleaseResult {
            val existing = records.getValue(request.executionId)
            if (existing.isFinalTerminal) return ReconciliationReleaseResult.ExistingFinal(existing)
            if (!existing.needsReconciliation ||
                existing.recordVersion != request.expectedVersion ||
                existing.reconciliationClaim?.claimToken != request.claimToken
            ) {
                return ReconciliationReleaseResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation release conflict",
                    ),
                )
            }
            val audit = try {
                prepareAudit(request.audit)
            } catch (_: Exception) {
                return ReconciliationReleaseResult.Conflict(
                    com.wzx.huitai.action.model.ActionError(
                        ActionErrorCode.EXECUTION_CONFLICT,
                        "reconciliation release audit rolled back",
                    ),
                )
            }
            val released = existing.copy(
                updatedAt = request.releasedAt,
                recordVersion = existing.recordVersion + 1,
                reconciliationClaim = null,
            )
            records[request.executionId] = released
            auditEvents += audit
            nextAuditSequence = audit.sequence
            return ReconciliationReleaseResult.Released(released)
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
