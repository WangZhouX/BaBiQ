package com.wzx.huitai.security.execution

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class InMemoryActionExecutionStoreTest {
    @Test
    fun `并发compareAndCreate仅创建一次其余返回同一运行记录`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()

        val results = (1..32).map { async { store.compareAndCreate(record, audit()) } }.awaitAll()

        assertEquals(1, results.count { it is ExecutionCreateResult.Created })
        assertEquals(31, results.count { it is ExecutionCreateResult.ExistingRunning })
        assertEquals(record, store.find("execution-1"))
        assertEquals(listOf(1L), store.events("execution-1").map { it.sequence })
    }

    @Test
    fun `相同execution的动作和指纹冲突且不改变原记录`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()
        store.compareAndCreate(record, audit())

        val conflict = store.compareAndCreate(
            record.copy(binding = record.binding.copy(actionId = "other", inputFingerprint = "other")),
            audit(),
        )

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ExecutionCreateResult.Conflict>(conflict).error.code)
        assertEquals(record, store.find("execution-1"))
    }

    @Test
    fun `首个终态原样重放且迟到终态不能覆盖`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()
        store.compareAndCreate(record, audit())
        val success: ActionResult<JsonElement> = ActionResult.Success(
            executionId = "execution-1",
            output = buildJsonObject { put("saved", true) },
        )
        val terminal = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(record, ActionExecutionState.SUCCEEDED, success)),
        ).record

        val late = store.transition(
            transition(
                terminal,
                ActionExecutionState.FAILED,
                ActionResult.Failure("execution-1", ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "late")),
            ),
        )

        assertSame(terminal.result, assertIs<ExecutionTransitionResult.ExistingTerminal>(late).record.result)
        assertEquals(terminal, store.find("execution-1"))
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(record, audit()))
    }

    @Test
    fun `OUTCOME_UNKNOWN持久化后只能由有效租约token收束`() = runTest {
        val store = InMemoryActionExecutionStore()
        val running = runningRecord()
        store.compareAndCreate(running, audit())
        val unknownResult: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        val unknown = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(running, ActionExecutionState.OUTCOME_UNKNOWN, unknownResult)),
        ).record

        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running, audit()))
        val claimed = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
        ).record
        assertIs<ReconciliationUpdateResult.Conflict>(
            store.updateReconciliation(finalUpdate(claimed, "wrong-token")),
        )
        val final = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(finalUpdate(claimed, "token-1")),
        ).record

        assertEquals(ActionExecutionState.SUCCEEDED, final.state)
        assertNull(final.reconciliationClaim)
        assertEquals(claimed.recordVersion, final.reconciliation?.sourceRecordVersion)
    }

    @Test
    fun `claim可在到期边界接管旧token不能续租或释放`() = runTest {
        val store = InMemoryActionExecutionStore()
        val unknown = installUnknown(store)
        val first = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
        ).record
        assertIs<ReconciliationClaimResult.ExistingClaim>(
            store.claimReconciliation(claimRequest(first, "token-2", NOW.plusSeconds(10))),
        )
        val takeover = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(first, "token-2", first.reconciliationClaim!!.expiresAt)),
        ).record

        assertEquals("token-2", takeover.reconciliationClaim?.claimToken)
        assertIs<ReconciliationRenewResult.ExistingClaim>(
            store.renewReconciliation(renewRequest(takeover, "token-1")),
        )
        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(releaseRequest(takeover, "token-1")),
        )
        val renewed = assertIs<ReconciliationRenewResult.Renewed>(
            store.renewReconciliation(renewRequest(takeover, "token-2")),
        ).record
        assertIs<ReconciliationReleaseResult.Released>(
            store.releaseReconciliation(releaseRequest(renewed, "token-2")),
        )
    }

    @Test
    fun `存储边界重新脱敏审计载荷且快照不可修改`() = runTest {
        val store = InMemoryActionExecutionStore()
        val rawToken = "raw-token-must-not-survive"
        val draft = audit().copy(
            redactedPayload = buildJsonObject { put("accessToken", rawToken) },
        )

        store.compareAndCreate(runningRecord(), draft)

        val events = store.events("execution-1")
        assertEquals("[REDACTED]", events.single().redactedPayload["accessToken"].toString().trim('"'))
        kotlin.test.assertFalse(rawToken in events.toString())
        kotlin.test.assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (events as MutableList<com.wzx.huitai.action.port.ActionAuditEvent>).clear()
        }
    }

    @Test
    fun `输入JsonObject的外部可变map不能改变已存储记录`() = runTest {
        val values = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("before"))
        val original = runningRecord()
        val command = original.command.copy(input = JsonObject(values))
        val record = original.copy(command = command, binding = original.binding)
        val store = InMemoryActionExecutionStore()

        store.compareAndCreate(record, audit())
        values["value"] = JsonPrimitive("after")

        assertEquals("before", store.find("execution-1")!!.command.input["value"].toString().trim('"'))
    }

    private suspend fun installUnknown(store: InMemoryActionExecutionStore): ActionExecutionRecord {
        val running = runningRecord()
        store.compareAndCreate(running, audit())
        val result: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        return assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(running, ActionExecutionState.OUTCOME_UNKNOWN, result)),
        ).record
    }

    private fun runningRecord(): ActionExecutionRecord {
        val command = command()
        return ActionExecutionRecord(
            command = command,
            binding = binding(command),
            state = ActionExecutionState.EXECUTING,
            result = null,
            createdAt = NOW,
            startedAt = NOW,
            updatedAt = NOW,
            recordVersion = 1,
        )
    }

    private fun transition(
        record: ActionExecutionRecord,
        state: ActionExecutionState,
        result: ActionResult<JsonElement>,
    ) = ExecutionTransition(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        state = state,
        result = result,
        updatedAt = record.updatedAt.plusSeconds(1),
        completedAt = record.updatedAt.plusSeconds(1),
        audit = audit(record.state, state, record.updatedAt.plusSeconds(1)),
    )

    private fun claimRequest(record: ActionExecutionRecord, token: String, now: Instant) = ReconciliationClaimRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        ownerId = "owner-$token",
        now = now,
        leaseDuration = Duration.ofSeconds(60),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, now)
            .copy(type = "reconciliation_attempt"),
    )

    private fun renewRequest(record: ActionExecutionRecord, token: String) = ReconciliationRenewRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        now = record.updatedAt.plusSeconds(1),
        leaseDuration = Duration.ofSeconds(60),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_claim_renewed"),
    )

    private fun releaseRequest(record: ActionExecutionRecord, token: String) = ReconciliationReleaseRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        releasedAt = record.updatedAt.plusSeconds(1),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_result"),
    )

    private fun finalUpdate(record: ActionExecutionRecord, token: String) = ReconciliationExecutionUpdate(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        result = null,
        successFact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
            errorCode = null,
            safeMessage = null,
            source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
        ),
        completedAt = record.updatedAt.plusSeconds(1),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_result"),
    )

    private fun command() = ActionCommand(
        executionId = "execution-1",
        actionId = "demo.save",
        actionVersion = 1,
        input = buildJsonObject { put("value", "secret") },
        origin = ActionOrigin.AGENT,
        identityScope = ActionIdentityScope("desktop", "session", "auth", 1, "user", "tenant", "platform"),
        pageId = "page-1",
        contextRevision = 1,
    )

    private fun binding(command: ActionCommand) = ExecutionBinding(
        actionId = command.actionId,
        actionVersion = command.actionVersion,
        inputFingerprint = "fingerprint",
        origin = command.origin,
        identityScope = command.identityScope,
        pageId = command.pageId,
        contextRevision = command.contextRevision,
    )

    private fun audit(
        from: ActionExecutionState? = null,
        to: ActionExecutionState = ActionExecutionState.EXECUTING,
        at: Instant = NOW,
    ) = ActionAuditDraft(
        executionId = "execution-1",
        fromState = from,
        toState = to,
        type = "state_transition",
        redactedPayload = buildJsonObject { },
        actorId = null,
        occurredAt = at,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
