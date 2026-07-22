package com.wzx.huitai.desktop.security

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.security.execution.InMemoryActionExecutionStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProductionIdentityBoundaryActionAdapterTest {
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
