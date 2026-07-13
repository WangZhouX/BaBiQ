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
import com.wzx.huitai.action.port.ExecutionStateUpdate
import com.wzx.huitai.action.port.ExecutionStateUpdateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.action.port.TerminalExecutionUpdate
import com.wzx.huitai.action.port.TerminalUpdateResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse

class ApplicationActionBusReadOnlyTest {
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
    fun `audit failure rolls back state transition atomically`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.failAuditOnState = ActionExecutionState.EXECUTING

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(ActionExecutionState.VALIDATING, fixture.store.record?.state)
        assertEquals(1, fixture.audit.events.size)
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
    fun `context rejection is persisted after validating audit before action invocation`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val stale = fixture.command(ActionOrigin.AGENT).copy(contextRevision = 99)

        val result = fixture.bus.execute(stale, fixture.context)

        assertEquals(ActionErrorCode.CONTEXT_STALE, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
        assertEquals(
            listOf(
                ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING,
                ActionExecutionState.VALIDATING to ActionExecutionState.FAILED,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
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
    fun `output encoding failure persists unavailable success without fake null output`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY, throwingOutputCodec = true)

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        val failed = assertIs<ActionBusResult.OutputEncodingFailed>(result)
        assertEquals(ActionExecutionState.SUCCEEDED, failed.terminalState)
        assertEquals(ActionExecutionState.SUCCEEDED, fixture.store.record?.state)
        assertNull(fixture.store.record?.result)
        assertEquals("OUTPUT_ENCODING_FAILED", fixture.store.record?.successFact?.kind)
        assertEquals("remote-1", fixture.store.record?.successFact?.remoteReference)
        assertEquals(1, fixture.action.executeCount)
        assertFalse(fixture.audit.events.last().redactedPayload.toString().contains("secret"))
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
    freezeRegistry: Boolean = true,
    throwingOutputCodec: Boolean = false,
    outputEncodingFailureOverride: ((ActionInvocationResult.OutputEncodingFailed) ->
        ActionInvocationResult.OutputEncodingFailed)? = null,
    previewInvocationOverride: ((ActionInvocationResult) -> ActionInvocationResult)? = null,
) {
    val identity = ActionIdentityScope(
        desktopInstanceId = "secret-desktop",
        desktopSessionId = "secret-session",
        authSessionId = "secret-auth",
        identityEpoch = 1,
        userId = "secret-user",
        tenantId = "secret-tenant",
        platformId = "secret-platform",
    )
    val context = ActionContext(identity, "page-1", 7, setOf("demo:read", "demo:write"))
    val action = BusCountingAction(busDescriptor(risk))
    var commandInput: JsonObject = buildJsonObject { put("value", 1) }
    private val registered = RegisteredAction(
        action,
        BusInputCodec(),
        if (throwingOutputCodec) ActionOutputCodec<BusOutput> { error("secret-codec") } else BusOutputCodec(),
    )
    val registry = ActionRegistry().apply {
        register(registered)
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
    }
    val confirmation = BusConfirmationPort()
    val approval = BusApprovalPort()
    val store = BusExecutionStore()
    val audit = BusAuditPort()
    val clock = BusClock()
    val bus: ApplicationActionBus
        get() = ApplicationActionBus(
            registry = registry,
            riskPolicy = ActionRiskPolicy { descriptor, _, _ ->
                RiskEvaluation.atLeast(descriptor.riskLevel, effectiveRisk)
            },
            confirmationPort = confirmation,
            approvalPort = approval,
            executionStore = store,
            clock = clock,
            contextValidator = ActionExecutionContextValidator(),
            actionInvoker = actionInvoker,
        )

    init {
        confirmation.store = store
        approval.store = store
        store.audit = audit
    }

    fun command(origin: ActionOrigin = ActionOrigin.USER) = ActionCommand(
        executionId = "execution-1",
        actionId = action.descriptor.id,
        input = commandInput,
        origin = origin,
        identityScope = identity,
        pageId = context.pageId,
        contextRevision = context.contextRevision,
    )
}

internal data class BusInput(val value: Int)
internal data class BusOutput(val value: Int)

internal class BusCountingAction(
    override val descriptor: ActionDescriptor,
) : ApplicationAction<BusInput, BusOutput> {
    var previewCount = 0
    var executeCount = 0
    var previewExecutionId = "execution-1"
    var previewResultMode = PreviewResultMode.NORMAL
    var executeFailure: Throwable? = null
    var result: ActionResult<BusOutput> = ActionResult.Success(
        "execution-1",
        BusOutput(1),
        remoteReference = "remote-1",
    )

    override suspend fun preview(input: BusInput, context: ActionContext): ActionPreview {
        previewCount += 1
        if (previewResultMode == PreviewResultMode.THROW) error("secret-preview-error")
        return ActionPreview(previewExecutionId, "secret-preview")
    }

    override suspend fun execute(input: BusInput, context: ActionContext): ActionResult<BusOutput> {
        executeCount += 1
        executeFailure?.let { throw it }
        return result
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
        failure?.let { throw it }
        return response
    }
}

internal class BusApprovalPort : ActionApprovalPort {
    var requests = 0
    var store: BusExecutionStore? = null
    var stateAtRequest: ActionExecutionState? = null
    var failure: Throwable? = null
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
        failure?.let { throw it }
        return response
    }
}

internal class BusExecutionStore : ActionExecutionStore {
    var record: ActionExecutionRecord? = null
    var nextAuditSequence = 0L
    var failTransitionTo: ActionExecutionState? = null
    var failAuditOnState: ActionExecutionState? = null
    var existingTerminalResult: ActionResult<JsonElement>? = null
    lateinit var audit: BusAuditPort

    override suspend fun find(executionId: String): ActionExecutionRecord? = record

    override suspend fun compareAndCreate(record: ActionExecutionRecord): ExecutionCreateResult {
        if (this.record != null) {
            return ExecutionCreateResult.Conflict(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "duplicate"))
        }
        this.record = record
        return ExecutionCreateResult.Created(record)
    }

    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult {
        if (this.record != null) {
            return ExecutionCreateResult.Conflict(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "duplicate"))
        }
        this.record = record
        appendAudit(audit)
        return ExecutionCreateResult.Created(record)
    }

    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
        val current = record ?: return ExecutionTransitionResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        if (current.isTerminal) return ExecutionTransitionResult.ExistingTerminal(current)
        if (current.recordVersion != update.expectedVersion ||
            failTransitionTo == update.state ||
            failAuditOnState == update.state
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
                val state = when (result) {
                    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
                    is ActionResult.Failure -> ActionExecutionState.FAILED
                    is ActionResult.Canceled -> ActionExecutionState.CANCELED
                    is ActionResult.Expired -> ActionExecutionState.EXPIRED
                    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
                    else -> error("terminal result required")
                }
                val terminal = current.copy(
                    state = state,
                    result = result,
                    completedAt = update.updatedAt,
                    updatedAt = update.updatedAt,
                    recordVersion = current.recordVersion + 1,
                )
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
        record = updated
        appendAudit(update.audit)
        return ExecutionTransitionResult.Updated(updated)
    }

    private suspend fun appendAudit(draft: ActionAuditDraft) {
        audit.append(
            ActionAuditEvent(
                executionId = draft.executionId,
                sequence = ++nextAuditSequence,
                fromState = draft.fromState,
                toState = draft.toState,
                type = draft.type,
                redactedPayload = draft.redactedPayload,
                actorId = draft.actorId,
                occurredAt = draft.occurredAt,
            ),
        )
    }

    suspend fun updateState(update: ExecutionStateUpdate): ExecutionStateUpdateResult {
        val current = record ?: return ExecutionStateUpdateResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        if (current.recordVersion != update.expectedVersion || current.isTerminal) {
            return ExecutionStateUpdateResult.Conflict(
                ActionError(ActionErrorCode.EXECUTION_CONFLICT, "state conflict"),
            )
        }
        val updated = current.copy(
            state = update.state,
            startedAt = update.startedAt ?: current.startedAt,
            updatedAt = update.updatedAt,
            recordVersion = current.recordVersion + 1,
        )
        record = updated
        return ExecutionStateUpdateResult.Updated(updated)
    }

    suspend fun updateTerminal(update: TerminalExecutionUpdate): TerminalUpdateResult {
        val current = record ?: return TerminalUpdateResult.Conflict(
            ActionError(ActionErrorCode.EXECUTION_CONFLICT, "missing"),
        )
        val updated = current.copy(
            state = update.terminalState,
            result = update.result,
            successFact = update.successFact,
            completedAt = update.completedAt,
            updatedAt = update.completedAt,
            recordVersion = current.recordVersion + 1,
        )
        record = updated
        return TerminalUpdateResult.Updated(updated)
    }

    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult = ReconciliationUpdateResult.Conflict(
        ActionError(ActionErrorCode.EXECUTION_CONFLICT, "not used"),
    )
}

internal class BusAuditPort : ActionAuditPort {
    val events = mutableListOf<ActionAuditEvent>()
    var requireExistingRecord = false
    var allAppendsSawRecord = true
    override suspend fun append(event: ActionAuditEvent) {
        if (requireExistingRecord) {
            allAppendsSawRecord = allAppendsSawRecord && true
        }
        events += event
    }
}

internal class BusClock : ActionClock {
    private var seconds = 0L
    override fun now(): Instant = Instant.parse("2026-07-14T00:00:00Z").plusSeconds(seconds++)
}

internal fun busDescriptor(risk: ActionRiskLevel) = ActionDescriptor(
    id = "demo.action",
    version = 1,
    title = "演示动作",
    description = "Bus 测试动作",
    inputSchema = buildJsonObject { put("type", "object") },
    riskLevel = risk,
    requiredPermissions = setOf("demo:read"),
    target = ActionTarget("generic-form", "submit"),
    replayPolicy = ActionReplayPolicy.NEVER,
    reconciliationPolicy = ReconciliationPolicy.MANUAL,
)
