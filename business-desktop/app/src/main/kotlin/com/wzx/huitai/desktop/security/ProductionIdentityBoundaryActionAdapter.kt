package com.wzx.huitai.desktop.security

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.agent.application.ApplicationActionAdmissionRevoker
import com.wzx.huitai.integration.identity.IdentityBoundaryActionPort
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Production identity boundary backed by exact-scope durable action records. */
class ProductionIdentityBoundaryActionAdapter(
    private val executionStore: ActionExecutionStore,
    private val query: ScopedActionExecutionQuery,
    private val admissionRevoker: ApplicationActionAdmissionRevoker = ApplicationActionAdmissionRevoker { _, _ -> },
    private val now: () -> Instant = Instant::now,
    private val maxCancellationAttempts: Int = 4,
) : IdentityBoundaryActionPort {
    init {
        require(maxCancellationAttempts > 0) { "maxCancellationAttempts must be positive" }
    }

    override suspend fun cancelPreExecution(
        identityScope: ActionIdentityScope,
        states: Set<ActionExecutionState>,
    ) {
        admissionRevoker.cancelPreExecutionAdmissions(identityScope, states)
        repeat(maxCancellationAttempts) { attempt ->
            var conflicted = false
            val targets = query.listNonTerminal(identityScope)
                .filter { record ->
                    record.command.identityScope == identityScope &&
                        record.state in states &&
                        record.state != ActionExecutionState.EXECUTING
                }
            if (targets.isEmpty()) return
            targets.forEach { record ->
                val at = now()
                when (executionStore.transition(
                    ExecutionTransition(
                        executionId = record.command.executionId,
                        expectedVersion = record.recordVersion,
                        state = ActionExecutionState.CANCELED,
                        result = ActionResult.Canceled(record.command.executionId, "identity boundary revoked"),
                        updatedAt = at,
                        completedAt = at,
                        audit = ActionAuditDraft(
                            executionId = record.command.executionId,
                            fromState = record.state,
                            toState = ActionExecutionState.CANCELED,
                            type = "identity_revoked",
                            redactedPayload = JsonObject(emptyMap()),
                            actorId = null,
                            occurredAt = at,
                        ),
                    ),
                )) {
                    is ExecutionTransitionResult.Updated,
                    is ExecutionTransitionResult.ExistingTerminal,
                    -> Unit
                    is ExecutionTransitionResult.Conflict -> conflicted = true
                }
            }
            if (!conflicted) return
            if (attempt == maxCancellationAttempts - 1) {
                throw IllegalStateException(
                    "Identity-scoped pre-execution cancellation remained conflicted after " +
                        "$maxCancellationAttempts attempts",
                )
            }
        }
    }

    override suspend fun detachExecutingForReconciliation(identityScope: ActionIdentityScope) {
        // EXECUTING records deliberately remain bound to the complete old scope. Clearing the UI workspace
        // detaches them from the active identity while their durable record remains available for reconciliation.
        query.listNonTerminal(identityScope).filter { it.state == ActionExecutionState.EXECUTING }
    }

    override suspend fun result(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ActionResult<JsonElement>? = query.find(executionId, identityScope)?.result
}
