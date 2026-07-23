package com.wzx.huitai.desktop.security

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.agent.application.ApplicationActionAdmissionRevoker
import com.wzx.huitai.security.execution.InMemoryActionExecutionStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProductionIdentityBoundaryActionAdapterTest {
    @Test
    fun `revocation drains runtime admissions before scanning durable actions`() = runTest {
        val store = InMemoryActionExecutionStore()
        val oldScope = scope("old-auth", 1)
        val calls = mutableListOf<String>()
        val revoker = ApplicationActionAdmissionRevoker { identityScope, states ->
            assertEquals(oldScope, identityScope)
            assertTrue(ActionExecutionState.RECEIVED in states)
            calls += "runtime"
        }
        val query = object : ScopedActionExecutionQuery by store {
            override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> {
                calls += "durable"
                return store.listNonTerminal(identityScope)
            }
        }
        val adapter = ProductionIdentityBoundaryActionAdapter(
            executionStore = store,
            query = query,
            admissionRevoker = revoker,
        )

        adapter.cancelPreExecution(oldScope, setOf(ActionExecutionState.RECEIVED))

        assertEquals(listOf("runtime", "durable"), calls)
    }

    @Test
    fun `revocation cancels only requested pre execution states in the exact old identity scope`() = runTest {
        val store = InMemoryActionExecutionStore()
        val oldScope = scope("old-auth", 1)
        val newScope = scope("new-auth", 2)
        val waiting = record("waiting", oldScope, ActionExecutionState.WAITING_APPROVAL)
        val executing = record("executing", oldScope, ActionExecutionState.EXECUTING)
        val newer = record("newer", newScope, ActionExecutionState.WAITING_APPROVAL)
        listOf(waiting, executing, newer).forEach { store.compareAndCreate(it, createAudit(it)) }
        val adapter = ProductionIdentityBoundaryActionAdapter(store, store, now = { NOW.plusSeconds(1) })

        adapter.cancelPreExecution(oldScope, setOf(ActionExecutionState.WAITING_APPROVAL))
        adapter.detachExecutingForReconciliation(oldScope)

        val canceled = requireNotNull(store.find("waiting", oldScope))
        assertEquals(ActionExecutionState.CANCELED, canceled.state)
        assertIs<ActionResult.Canceled>(canceled.result)
        assertEquals(ActionExecutionState.EXECUTING, requireNotNull(store.find("executing", oldScope)).state)
        assertEquals(ActionExecutionState.WAITING_APPROVAL, requireNotNull(store.find("newer", newScope)).state)
        assertNull(adapter.result("newer", oldScope))
    }

    @Test
    fun `revocation rereads the complete old scope after a cas conflict and cancels the advanced record`() = runTest {
        val backing = InMemoryActionExecutionStore()
        val oldScope = scope("old-auth", 1)
        val newScope = scope("new-auth", 2)
        val stale = record("stale", oldScope, ActionExecutionState.RECEIVED)
        val newer = record("newer", newScope, ActionExecutionState.RECEIVED)
        listOf(stale, newer).forEach { backing.compareAndCreate(it, createAudit(it)) }
        var firstTransition = true
        val store = object : ActionExecutionStore by backing {
            override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
                if (firstTransition) {
                    firstTransition = false
                    val advancedAt = NOW.plusMillis(500)
                    backing.transition(
                        ExecutionTransition(
                            executionId = update.executionId,
                            expectedVersion = update.expectedVersion,
                            state = ActionExecutionState.VALIDATING,
                            updatedAt = advancedAt,
                            audit = ActionAuditDraft(
                                executionId = update.executionId,
                                fromState = ActionExecutionState.RECEIVED,
                                toState = ActionExecutionState.VALIDATING,
                                type = "validation_started",
                                redactedPayload = JsonObject(emptyMap()),
                                actorId = null,
                                occurredAt = advancedAt,
                            ),
                        ),
                    )
                }
                return backing.transition(update)
            }
        }
        val queriedScopes = mutableListOf<ActionIdentityScope>()
        val query = object : ScopedActionExecutionQuery {
            override suspend fun find(
                executionId: String,
                identityScope: ActionIdentityScope,
            ): ActionExecutionRecord? = backing.find(executionId, identityScope)

            override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> {
                queriedScopes += identityScope
                return backing.listNonTerminal(identityScope) + listOfNotNull(backing.find("newer", newScope))
            }
        }
        val adapter = ProductionIdentityBoundaryActionAdapter(store, query, now = { NOW.plusSeconds(1) })

        adapter.cancelPreExecution(
            oldScope,
            setOf(ActionExecutionState.RECEIVED, ActionExecutionState.VALIDATING),
        )

        assertEquals(listOf(oldScope, oldScope), queriedScopes)
        assertEquals(ActionExecutionState.CANCELED, requireNotNull(backing.find("stale", oldScope)).state)
        assertEquals(ActionExecutionState.RECEIVED, requireNotNull(backing.find("newer", newScope)).state)
    }

    @Test
    fun `revocation stops after a finite number of conflicts and fails closed`() = runTest {
        val backing = InMemoryActionExecutionStore()
        val oldScope = scope("old-auth", 1)
        val stale = record("stale", oldScope, ActionExecutionState.RECEIVED)
        backing.compareAndCreate(stale, createAudit(stale))
        var transitionCalls = 0
        val store = object : ActionExecutionStore by backing {
            override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
                transitionCalls += 1
                return ExecutionTransitionResult.Conflict(
                    ActionError(ActionErrorCode.EXECUTION_CONFLICT, "forced conflict"),
                )
            }
        }
        val adapter = ProductionIdentityBoundaryActionAdapter(
            executionStore = store,
            query = backing,
            maxCancellationAttempts = 3,
        )

        val failure = assertFailsWith<IllegalStateException> {
            adapter.cancelPreExecution(oldScope, setOf(ActionExecutionState.RECEIVED))
        }

        assertEquals(3, transitionCalls)
        assertTrue(failure.message.orEmpty().contains("3 attempts"))
        assertEquals(ActionExecutionState.RECEIVED, requireNotNull(backing.find("stale", oldScope)).state)
    }

    @Test
    fun `revocation does not swallow scoped query failures`() = runTest {
        val store = InMemoryActionExecutionStore()
        val oldScope = scope("old-auth", 1)
        val query = object : ScopedActionExecutionQuery {
            override suspend fun find(
                executionId: String,
                identityScope: ActionIdentityScope,
            ): ActionExecutionRecord? = null

            override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> {
                throw IllegalStateException("query unavailable")
            }
        }
        val adapter = ProductionIdentityBoundaryActionAdapter(store, query)

        val failure = assertFailsWith<IllegalStateException> {
            adapter.cancelPreExecution(oldScope, setOf(ActionExecutionState.RECEIVED))
        }

        assertEquals("query unavailable", failure.message)
    }

    private fun record(
        executionId: String,
        identityScope: ActionIdentityScope,
        state: ActionExecutionState,
    ): ActionExecutionRecord {
        val command = ActionCommand(
            executionId = executionId,
            actionId = "demo.save",
            actionVersion = 1,
            input = buildJsonObject { put("safe", true) },
            origin = ActionOrigin.AGENT,
            identityScope = identityScope,
            pageId = "page-1",
            contextRevision = 1,
        )
        return ActionExecutionRecord(
            command = command,
            binding = ExecutionBinding(
                actionId = command.actionId,
                actionVersion = command.actionVersion,
                inputFingerprint = "fingerprint-$executionId",
                origin = command.origin,
                identityScope = command.identityScope,
                pageId = command.pageId,
                contextRevision = command.contextRevision,
            ),
            riskLevel = ActionRiskLevel.REVERSIBLE_WRITE,
            state = state,
            result = null,
            createdAt = NOW,
            startedAt = NOW.takeIf { state == ActionExecutionState.EXECUTING },
            updatedAt = NOW,
            recordVersion = 1,
        )
    }

    private fun createAudit(record: ActionExecutionRecord) = ActionAuditDraft(
        executionId = record.command.executionId,
        fromState = ActionExecutionState.RECEIVED,
        toState = record.state,
        type = "created",
        redactedPayload = JsonObject(emptyMap()),
        actorId = null,
        occurredAt = NOW,
    )

    private fun scope(authSessionId: String, epoch: Long) = ActionIdentityScope(
        desktopInstanceId = "desktop",
        desktopSessionId = "session",
        authSessionId = authSessionId,
        identityEpoch = epoch,
        userId = "user-$epoch",
        tenantId = "tenant-$epoch",
        platformId = "platform",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }
}
