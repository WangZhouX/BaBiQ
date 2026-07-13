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
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.action.port.TerminalExecutionUpdate
import com.wzx.huitai.action.port.TerminalUpdateResult
import kotlinx.coroutines.test.runTest
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
    fun `context rejection happens before preview execute or persistence`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val stale = fixture.command(ActionOrigin.AGENT).copy(contextRevision = 99)

        val result = fixture.bus.execute(stale, fixture.context)

        assertEquals(ActionErrorCode.CONTEXT_STALE, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.action.executeCount)
        assertNull(fixture.store.record)
        assertEquals(
            listOf(ActionExecutionState.RECEIVED to ActionExecutionState.VALIDATING),
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
    val registry = ActionRegistry().apply {
        register(RegisteredAction(
            action,
            BusInputCodec(),
            if (throwingOutputCodec) ActionOutputCodec<BusOutput> { error("secret-codec") } else BusOutputCodec(),
            invocationOverride = { invocation ->
                when {
                    invocation is ActionInvocationResult.OutputEncodingFailed && outputEncodingFailureOverride != null ->
                        outputEncodingFailureOverride(invocation)
                    previewInvocationOverride != null -> previewInvocationOverride(invocation)
                    else -> invocation
                }
            },
        ))
        if (freezeRegistry) freeze()
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
            auditPort = audit,
            clock = clock,
            contextValidator = ActionExecutionContextValidator(),
        )

    init {
        confirmation.store = store
        approval.store = store
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
        return response
    }
}

internal class BusApprovalPort : ActionApprovalPort {
    var requests = 0
    var store: BusExecutionStore? = null
    var stateAtRequest: ActionExecutionState? = null
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
        return response
    }
}

internal class BusExecutionStore : ActionExecutionStore {
    var record: ActionExecutionRecord? = null

    override suspend fun find(executionId: String): ActionExecutionRecord? = record

    override suspend fun compareAndCreate(record: ActionExecutionRecord): ExecutionCreateResult {
        if (this.record != null) {
            return ExecutionCreateResult.Conflict(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "duplicate"))
        }
        this.record = record
        return ExecutionCreateResult.Created(record)
    }

    override suspend fun updateState(update: ExecutionStateUpdate): ExecutionStateUpdateResult {
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

    override suspend fun updateTerminal(update: TerminalExecutionUpdate): TerminalUpdateResult {
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
    override suspend fun append(event: ActionAuditEvent) {
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
