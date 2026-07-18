package com.wzx.huitai.demo.action

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ActionInvocationResult
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionApprovalPort
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ActionConfirmationPort
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationClaim
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationProvenance
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.demo.gateway.FakeGatewayMode
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoDispatchResult
import com.wzx.huitai.demo.model.DemoFormEvent
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DemoActionBusIntegrationTest {
    @Test
    fun `原子事件结果明确标记stale补丁未应用`() {
        val initial = DemoFormState()
        val screen = DemoScreenModel(initial)
        val stalePatch = patchFor(initial, DemoFormState.FIELD_NAME, "过期值")
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "用户更新"))

        val result = screen.dispatchWithResult(DemoFormEvent.ApplyPatch(stalePatch))

        assertFalse(result.stateChanged)
        assertEquals(result.before, result.after)
        assertEquals(2, result.after.revision)
        assertEquals(initial.values.name, result.after.values.name)
    }

    @Test
    fun `表单动作把stale补丁返回为失败而不是成功`() = runTest {
        val initial = DemoFormState()
        val screen = DemoScreenModel(initial)
        val stalePatch = patchFor(initial, DemoFormState.FIELD_NAME, "过期值")
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "用户更新"))
        val registered = DemoActionCatalog(screen, FakeHuitaiGateway()).actions
            .single { it.descriptor.id == "form.apply_patch" }

        val invocation = registered.invokeExecute(
            buildJsonObject {
                put("executionId", "stale-apply")
                put("patch", Json.parseToJsonElement(Json.encodeToString(stalePatch)).jsonObject)
            },
            demoContext(screen.state.value.revision),
        )

        val result = assertIs<ActionInvocationResult.Executed>(invocation).result
        assertIs<ActionResult.Failure>(result)
        assertEquals(initial.values.name, screen.state.value.values.name)
    }

    @Test
    fun `补丁与用户编辑并发时每个typed事件结果保持线性化`() {
        repeat(20) {
            val initial = DemoFormState()
            val screen = DemoScreenModel(initial)
            val patch = patchFor(initial, DemoFormState.FIELD_NAME, "补丁值")
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val patchFuture = executor.submit<DemoDispatchResult> {
                    start.await()
                    screen.dispatchWithResult(DemoFormEvent.ApplyPatch(patch))
                }
                val editFuture = executor.submit<DemoDispatchResult> {
                    start.await()
                    screen.dispatchWithResult(
                        DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "用户更新"),
                    )
                }
                start.countDown()

                val patchResult = patchFuture.get(5, TimeUnit.SECONDS)
                val editResult = editFuture.get(5, TimeUnit.SECONDS)
                val finalState = screen.state.value

                assertTrue(editResult.stateChanged)
                if (patchResult.stateChanged) {
                    assertEquals(1, patchResult.before.revision)
                    assertEquals(2, patchResult.after.revision)
                    assertEquals(3, finalState.revision)
                    assertEquals("补丁值", finalState.values.name)
                } else {
                    assertEquals(2, patchResult.before.revision)
                    assertEquals(patchResult.before, patchResult.after)
                    assertEquals(2, finalState.revision)
                    assertEquals(initial.values.name, finalState.values.name)
                }
                assertEquals("用户更新", finalState.values.status)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `导航事件返回自己的after且不受随后页面事件污染`() {
        val screen = DemoScreenModel()

        val navigation = screen.dispatchWithExpectedContext(
            event = DemoFormEvent.Navigate("/demo/owned"),
            expectedPageId = DemoFormState.PAGE_ID,
            expectedRevision = 1,
        )
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_STATUS, "随后编辑"))

        val owned = requireNotNull(navigation)
        assertEquals("/demo/owned", owned.after.route)
        assertEquals(2, owned.after.revision)
        assertEquals(3, screen.state.value.revision)
    }

    @Test
    fun `用户和Agent应用补丁都经过同一个动作总线完整链路`() = runTest {
        val fixture = DemoActionBusFixture()

        val userResult = fixture.bus.execute(
            fixture.applyPatchCommand("apply-user", ActionOrigin.USER, DemoFormState.FIELD_NAME, "用户值"),
            fixture.context(),
        )
        val agentResult = fixture.bus.execute(
            fixture.applyPatchCommand("apply-agent", ActionOrigin.AGENT, DemoFormState.FIELD_STATUS, "Agent值"),
            fixture.context(),
        )

        assertIs<ActionResult.Success<*>>(assertIs<ActionBusResult.Completed>(userResult).result)
        assertIs<ActionResult.Success<*>>(assertIs<ActionBusResult.Completed>(agentResult).result)
        assertEquals("用户值", fixture.screen.state.value.values.name)
        assertEquals("Agent值", fixture.screen.state.value.values.status)
        assertEquals(3, fixture.screen.state.value.revision)
        assertEquals(listOf(ActionOrigin.USER, ActionOrigin.AGENT), fixture.confirmedOrigins)
        listOf("apply-user", "apply-agent").forEach { executionId ->
            assertEquals(
                listOf(
                    ActionExecutionState.VALIDATING,
                    ActionExecutionState.PREVIEWED,
                    ActionExecutionState.EXECUTING,
                    ActionExecutionState.SUCCEEDED,
                ),
                fixture.auditStates(executionId),
            )
        }
    }

    @Test
    fun `提交响应丢失后只对账且远端写入一次并最终成功`() = runTest {
        val fixture = DemoActionBusFixture(submitMode = FakeGatewayMode.RESPONSE_LOST_AFTER_WRITE)
        val command = fixture.submitCommand("submit-lost", ActionOrigin.AGENT)

        val first = fixture.bus.execute(command, fixture.context())
        val unknown = assertIs<ActionResult.OutcomeUnknown>(assertIs<ActionBusResult.Completed>(first).result)
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.stateOf("submit-lost"))
        assertEquals("submit-lost", unknown.executionId)

        val recovered = fixture.bus.execute(command, fixture.context())

        assertIs<ActionBusResult.SuccessWithoutOutput>(recovered)
        assertEquals(1, fixture.gateway.submissionRequestCount)
        assertEquals(1, fixture.gateway.submissionWriteCount)
        assertEquals(1, fixture.gateway.submissionQueryCount)
        assertEquals(ActionExecutionState.SUCCEEDED, fixture.stateOf("submit-lost"))
    }
}

/** 创建绑定给定状态 revision 的单字段补丁。 */
private fun patchFor(
    state: DemoFormState,
    fieldId: String,
    value: String,
): FormPatch = FormPatch(
    pageId = DemoFormState.PAGE_ID,
    baseRevision = state.revision,
    changes = listOf(
        FieldChange(
            fieldId = fieldId,
            previousValue = JsonPrimitive(state.values.valueOf(fieldId)),
            newValue = JsonPrimitive(value),
            reason = "并发边界验证",
            confidence = 1.0,
        ),
    ),
)

/** 创建演示动作直接调用所需的冻结上下文。 */
private fun demoContext(revision: Long): ActionContext = ActionContext(
    identityScope = ActionIdentityScope(
        desktopInstanceId = "desktop-direct",
        desktopSessionId = "session-direct",
        authSessionId = "auth-direct",
        identityEpoch = 1,
        userId = "user-direct",
        tenantId = "tenant-direct",
        platformId = "platform-direct",
    ),
    pageId = DemoFormState.PAGE_ID,
    contextRevision = revision,
    permissions = setOf("demo.write"),
)

/** 为 framework-demo 提供真实 ApplicationActionBus 的最小内存端口。 */
private class DemoActionBusFixture(
    submitMode: FakeGatewayMode = FakeGatewayMode.CONFIRMED,
) {
    val screen = DemoScreenModel()
    val gateway = FakeHuitaiGateway(submitMode = submitMode)
    private val store = DemoExecutionStore()
    private val clock = DemoClock()
    private val identity = ActionIdentityScope(
        desktopInstanceId = "desktop-demo",
        desktopSessionId = "session-demo",
        authSessionId = "auth-demo",
        identityEpoch = 1,
        userId = "user-demo",
        tenantId = "tenant-demo",
        platformId = "platform-demo",
    )
    val confirmedOrigins = mutableListOf<ActionOrigin>()
    val bus = ApplicationActionBus(
        registry = DemoActionCatalog(screen, gateway).createRegistry(),
        riskPolicy = { descriptor, _, _ ->
            RiskEvaluation.atLeast(descriptor.riskLevel, descriptor.riskLevel)
        },
        confirmationPort = ActionConfirmationPort { command, _, _ ->
            confirmedOrigins += command.origin
            ActionConfirmation(
                decisionId = "confirmation-${command.executionId}",
                executionId = command.executionId,
                decision = ConfirmationDecision.ACCEPTED,
                decidedAt = clock.now(),
            )
        },
        approvalPort = ActionApprovalPort { command, _, _, _ ->
            ActionApproval(
                approvalId = "approval-${command.executionId}",
                executionId = command.executionId,
                decision = ApprovalDecision.APPROVED,
                decidedAt = clock.now(),
                decidedBy = "demo-approver",
            )
        },
        executionStore = store,
        clock = clock,
        contextValidator = ActionExecutionContextValidator(),
    )

    /** 创建与当前页面 revision 一致的执行上下文。 */
    fun context(): ActionContext = ActionContext(
        identityScope = identity,
        pageId = DemoFormState.PAGE_ID,
        contextRevision = screen.state.value.revision,
        permissions = setOf("demo.write", "demo.submit"),
    )

    /** 创建绑定当前 revision 的单字段补丁命令。 */
    fun applyPatchCommand(
        executionId: String,
        origin: ActionOrigin,
        fieldId: String,
        value: String,
    ): ActionCommand {
        val state = screen.state.value
        val patch = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = state.revision,
            changes = listOf(
                FieldChange(
                    fieldId = fieldId,
                    previousValue = JsonPrimitive(state.values.valueOf(fieldId)),
                    newValue = JsonPrimitive(value),
                    reason = "集成演示",
                    confidence = 1.0,
                ),
            ),
        )
        return command(
            executionId = executionId,
            actionId = "form.apply_patch",
            origin = origin,
            input = buildJsonObject {
                put("executionId", executionId)
                put("patch", Json.parseToJsonElement(Json.encodeToString(patch)).jsonObject)
            },
        )
    }

    /** 创建提交命令。 */
    fun submitCommand(executionId: String, origin: ActionOrigin): ActionCommand = command(
        executionId = executionId,
        actionId = "demo.submit",
        origin = origin,
        input = buildJsonObject { put("executionId", executionId) },
    )

    /** 返回指定 execution 的审计目标状态序列。 */
    fun auditStates(executionId: String): List<ActionExecutionState> =
        store.audits.filter { it.executionId == executionId }.map(ActionAuditEvent::toState)

    /** 返回指定 execution 当前状态。 */
    fun stateOf(executionId: String): ActionExecutionState? = store.records[executionId]?.state

    private fun command(
        executionId: String,
        actionId: String,
        origin: ActionOrigin,
        input: JsonObject,
    ): ActionCommand = ActionCommand(
        executionId = executionId,
        actionId = actionId,
        actionVersion = 1,
        input = input,
        origin = origin,
        identityScope = identity,
        pageId = DemoFormState.PAGE_ID,
        contextRevision = screen.state.value.revision,
    )
}

/** 单调递增的确定性测试时钟。 */
private class DemoClock : ActionClock {
    private var seconds = 0L

    /** 每次读取推进一秒。 */
    override fun now(): Instant = Instant.parse("2026-07-18T00:00:00Z").plusSeconds(seconds++)
}

/** 支持多 execution、状态迁移与远端对账的内存执行存储。 */
private class DemoExecutionStore : ActionExecutionStore {
    val records = linkedMapOf<String, ActionExecutionRecord>()
    val audits = mutableListOf<ActionAuditEvent>()
    private val sequences = mutableMapOf<String, Long>()

    override suspend fun find(executionId: String): ActionExecutionRecord? = records[executionId]

    override suspend fun compareAndCreate(
        record: ActionExecutionRecord,
        audit: ActionAuditDraft,
    ): ExecutionCreateResult {
        records[record.command.executionId]?.let { existing ->
            return if (existing.binding != record.binding) {
                ExecutionCreateResult.Conflict(conflict())
            } else if (existing.isTerminal) {
                ExecutionCreateResult.ExistingTerminal(existing)
            } else {
                ExecutionCreateResult.ExistingRunning(existing)
            }
        }
        records[record.command.executionId] = record
        append(audit)
        return ExecutionCreateResult.Created(record)
    }

    override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult {
        val current = records[update.executionId]
            ?: return ExecutionTransitionResult.Conflict(conflict())
        if (current.isTerminal) return ExecutionTransitionResult.ExistingTerminal(current)
        if (current.recordVersion != update.expectedVersion) {
            return ExecutionTransitionResult.Conflict(conflict())
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
        records[update.executionId] = updated
        append(update.audit)
        return ExecutionTransitionResult.Updated(updated)
    }

    override suspend fun updateReconciliation(
        update: ReconciliationExecutionUpdate,
    ): ReconciliationUpdateResult {
        val current = records[update.executionId]
            ?: return ReconciliationUpdateResult.Conflict(conflict())
        if (current.isFinalTerminal) return ReconciliationUpdateResult.ExistingFinal(current)
        if (current.recordVersion != update.expectedVersion ||
            current.reconciliationClaim?.claimToken != update.claimToken
        ) {
            return ReconciliationUpdateResult.Conflict(conflict())
        }
        val updated = current.copy(
            state = update.result?.terminalState() ?: ActionExecutionState.SUCCEEDED,
            result = update.result,
            successFact = update.successFact,
            completedAt = update.completedAt,
            updatedAt = update.completedAt,
            recordVersion = current.recordVersion + 1,
            reconciliation = ReconciliationProvenance(current.recordVersion, update.completedAt),
            reconciliationClaim = null,
        )
        records[update.executionId] = updated
        append(update.audit)
        return ReconciliationUpdateResult.Updated(updated)
    }

    override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult {
        val current = records[request.executionId]
            ?: return ReconciliationClaimResult.Conflict(conflict())
        if (current.isFinalTerminal) return ReconciliationClaimResult.ExistingFinal(current)
        current.reconciliationClaim?.let { return ReconciliationClaimResult.ExistingClaim(current) }
        if (!current.needsReconciliation || current.recordVersion != request.expectedVersion) {
            return ReconciliationClaimResult.Conflict(conflict())
        }
        val claimed = current.copy(
            updatedAt = request.now,
            recordVersion = current.recordVersion + 1,
            reconciliationClaim = ReconciliationClaim(
                claimToken = request.claimToken,
                ownerId = request.ownerId,
                claimedAt = request.now,
                expiresAt = request.expiresAt,
            ),
        )
        records[request.executionId] = claimed
        append(request.audit)
        return ReconciliationClaimResult.Claimed(claimed)
    }

    override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult {
        val current = records[request.executionId]
            ?: return ReconciliationRenewResult.Conflict(conflict())
        if (current.isFinalTerminal) return ReconciliationRenewResult.ExistingFinal(current)
        val claim = current.reconciliationClaim ?: return ReconciliationRenewResult.Conflict(conflict())
        if (claim.claimToken != request.claimToken) return ReconciliationRenewResult.ExistingClaim(current)
        if (current.recordVersion != request.expectedVersion) return ReconciliationRenewResult.Conflict(conflict())
        val renewed = current.copy(
            updatedAt = request.now,
            recordVersion = current.recordVersion + 1,
            reconciliationClaim = claim.copy(expiresAt = request.expiresAt),
        )
        records[request.executionId] = renewed
        append(request.audit)
        return ReconciliationRenewResult.Renewed(renewed)
    }

    override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult {
        val current = records[request.executionId]
            ?: return ReconciliationReleaseResult.Conflict(conflict())
        if (current.isFinalTerminal) return ReconciliationReleaseResult.ExistingFinal(current)
        if (current.recordVersion != request.expectedVersion ||
            current.reconciliationClaim?.claimToken != request.claimToken
        ) {
            return ReconciliationReleaseResult.Conflict(conflict())
        }
        val released = current.copy(
            updatedAt = request.releasedAt,
            recordVersion = current.recordVersion + 1,
            reconciliationClaim = null,
        )
        records[request.executionId] = released
        append(request.audit)
        return ReconciliationReleaseResult.Released(released)
    }

    private fun append(draft: ActionAuditDraft) {
        val sequence = (sequences[draft.executionId] ?: 0) + 1
        sequences[draft.executionId] = sequence
        audits += ActionAuditEvent(
            executionId = draft.executionId,
            sequence = sequence,
            fromState = draft.fromState,
            toState = draft.toState,
            type = draft.type,
            redactedPayload = draft.redactedPayload,
            actorId = draft.actorId,
            occurredAt = draft.occurredAt,
        )
    }

    private fun conflict(): ActionError = ActionError(
        ActionErrorCode.EXECUTION_CONFLICT,
        "内存执行记录冲突",
    )
}

/** 将持久化 JSON 结果映射到唯一终态。 */
private fun ActionResult<JsonElement>.terminalState(): ActionExecutionState = when (this) {
    is ActionResult.Success -> ActionExecutionState.SUCCEEDED
    is ActionResult.Failure -> ActionExecutionState.FAILED
    is ActionResult.Canceled -> ActionExecutionState.CANCELED
    is ActionResult.Expired -> ActionExecutionState.EXPIRED
    is ActionResult.OutcomeUnknown -> ActionExecutionState.OUTCOME_UNKNOWN
    is ActionResult.Preview, is ActionResult.ApprovalRequired -> error("中间结果不能持久化为终态")
}
