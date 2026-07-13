package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionSuccessFact
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionIdempotencyTest {
    @Test
    fun `固定分片锁在A释放B已引用窗口不会让C进入第二把锁`() = runTest {
        val scope = StripedActionExecutionLockScope(stripeCount = 4)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val aEntered = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val cEntered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val releaseC = CompletableDeferred<Unit>()

        suspend fun enter(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            scope.withLock("execution-aba") {
                val current = active.incrementAndGet()
                maximumActive.accumulateAndGet(current, ::maxOf)
                entered.complete(Unit)
                try {
                    release.await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val a = async { enter(aEntered, releaseA) }
        aEntered.await()
        val b = async { enter(bEntered, releaseB) }
        yield()
        releaseA.complete(Unit)
        bEntered.await()
        val c = async { enter(cEntered, releaseC) }
        yield()

        assertEquals(false, cEntered.isCompleted)
        releaseB.complete(Unit)
        cEntered.await()
        releaseC.complete(Unit)
        a.await()
        b.await()
        c.await()
        assertEquals(1, maximumActive.get())

        repeat(1_000) { index ->
            scope.withLock("execution-$index") { Unit }
        }
        assertEquals(4, scope.stripeCount)
    }

    @Test
    fun `相同执行标识与输入在所有非终态只返回已有运行且不再调用动作`() = runTest {
        val states = listOf(
            ActionExecutionState.VALIDATING,
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
            ActionExecutionState.EXECUTING,
        )

        states.forEach { state ->
            val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
            fixture.store.record = runningRecord(fixture.command(), state)

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            val running = assertIs<ActionBusResult.InProgress>(result, state.name)
            assertEquals(fixture.command().executionId, running.executionId)
            assertEquals(state, running.state)
            assertEquals(0, fixture.action.previewCount)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(0, fixture.confirmation.requests)
            assertEquals(0, fixture.approval.requests)
            assertEquals(emptyList(), fixture.audit.events)
        }
    }

    @Test
    fun `相同执行标识但动作不同优先返回执行冲突`() = runTest {
        val otherAction = BusCountingAction(
            busDescriptor(ActionRiskLevel.READ_ONLY).copy(id = "other.action"),
        )
        val fixture = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            additionalRegisteredActions = listOf(
                RegisteredAction(otherAction, BusInputCodec(), BusOutputCodec()),
            ),
        )
        fixture.store.record = runningRecord(fixture.command(), ActionExecutionState.VALIDATING)
        val conflicting = fixture.command().copy(actionId = "other.action")

        val result = fixture.bus.execute(conflicting, fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `相同执行标识但规范输入指纹不同返回执行冲突`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.record = runningRecord(fixture.command(), ActionExecutionState.VALIDATING)
        val conflicting = fixture.command().copy(input = buildJsonObject { put("value", 2) })

        val result = fixture.bus.execute(conflicting, fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `对象键顺序不同仍识别为同一稳定输入`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val first = fixture.command().copy(input = buildJsonObject { put("a", 1); put("b", 2) })
        val reordered = fixture.command().copy(input = buildJsonObject { put("b", 2); put("a", 1) })
        fixture.store.record = runningRecord(first, ActionExecutionState.VALIDATING)

        val result = fixture.bus.execute(reordered, fixture.context)

        assertIs<ActionBusResult.InProgress>(result)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `已有执行完整绑定任一维度变化都在结果重放和对账前冲突`() = runTest {
        val states = listOf("running", "terminal", "unknown")

        states.forEach { storedState ->
            val version2Action = BusCountingAction(
                busDescriptor(ActionRiskLevel.READ_ONLY, version = 2),
            )
            val fixture = BusFixture(
                risk = ActionRiskLevel.READ_ONLY,
                additionalRegisteredActions = listOf(
                    RegisteredAction(version2Action, BusInputCodec(), BusOutputCodec()),
                ),
            )
            val base = fixture.command()
            when (storedState) {
                "running" -> fixture.store.record = runningRecord(base, ActionExecutionState.VALIDATING)
                "terminal" -> fixture.store.installExistingTerminal(
                    base,
                    result = ActionResult.Success(
                        executionId = base.executionId,
                        output = buildJsonObject { put("secretResult", true) },
                        remoteReference = "secret-remote",
                    ),
                )
                "unknown" -> fixture.store.installExistingTerminal(
                    base,
                    result = ActionResult.OutcomeUnknown(
                        executionId = base.executionId,
                        error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程结果未知"),
                        remoteReference = "secret-remote",
                        reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
                    ),
                )
            }
            val before = fixture.store.record
            fun identityCase(
                transform: (com.wzx.huitai.action.model.ActionIdentityScope) ->
                    com.wzx.huitai.action.model.ActionIdentityScope,
            ): Pair<ActionCommand, ActionContext> {
                val changed = transform(base.identityScope)
                return base.copy(identityScope = changed) to fixture.context.copy(identityScope = changed)
            }
            val cases = listOf(
                "desktopInstance" to identityCase { it.copy(desktopInstanceId = "other-desktop") },
                "desktopSession" to identityCase { it.copy(desktopSessionId = "other-session") },
                "authSession" to identityCase { it.copy(authSessionId = "other-auth") },
                "identityEpoch" to identityCase { it.copy(identityEpoch = it.identityEpoch + 1) },
                "user" to identityCase { it.copy(userId = "other-user") },
                "tenant" to identityCase { it.copy(tenantId = "other-tenant") },
                "platform" to identityCase { it.copy(platformId = "other-platform") },
                "page" to (base.copy(pageId = "other-page") to fixture.context.copy(pageId = "other-page")),
                "revision" to (
                    base.copy(contextRevision = base.contextRevision + 1) to
                        fixture.context.copy(contextRevision = fixture.context.contextRevision + 1)
                ),
                "origin" to (base.copy(origin = ActionOrigin.AGENT) to fixture.context),
                "version" to (base.copy(actionVersion = 2) to fixture.context),
            )

            cases.forEach { (dimension, incoming) ->
                val (command, context) = incoming
                val result = fixture.bus.execute(command, context)

                assertEquals(
                    ActionErrorCode.EXECUTION_CONFLICT,
                    assertIs<ActionBusResult.Rejected>(result, "$storedState/$dimension").error.code,
                )
                assertEquals(before, fixture.store.record, "$storedState/$dimension")
                assertEquals(emptyList(), fixture.audit.events, "$storedState/$dimension")
                assertEquals(0, fixture.action.executeCount, "$storedState/$dimension")
                assertEquals(0, fixture.action.reconcileCount, "$storedState/$dimension")
                assertEquals(0, version2Action.reconcileCount, "$storedState/$dimension")
            }
        }
    }

    @Test
    fun `并发相同执行最多只有一个调用进入真实执行`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.action.executeEntered = CompletableDeferred()

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.executeEntered!!.await()
        val duplicate = async { fixture.bus.execute(fixture.command(), fixture.context) }

        val duplicateResult = duplicate.await()
        assertIs<ActionBusResult.InProgress>(duplicateResult)
        assertEquals(1, fixture.action.executeCount)
        owner.cancel()
    }

    @Test
    fun `独立协调器并发创建仍由持久存储只授予一个所有者`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        val store = RaceWindowStore()
        val clock = ActionClock { NOW }
        val first = async { ActionExecutionCoordinator(store, clock).begin(fixture.command()) }
        val second = async { ActionExecutionCoordinator(store, clock).begin(fixture.command()) }

        val starts = listOf(first.await(), second.await())

        assertEquals(1, starts.count { it is ActionExecutionStart.New })
        assertEquals(1, starts.count { it is ActionExecutionStart.ExistingRunning })
        assertEquals(1, store.createdAudits)
    }

    @Test
    fun `成功失败取消过期终态均精确返回存储结果`() = runTest {
        val results: List<ActionResult<JsonElement>> = listOf(
            ActionResult.Success("execution-1", buildJsonObject { put("saved", true) }, remoteReference = "remote-1"),
            ActionResult.Failure(
                "execution-1",
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "远程明确失败"),
                remoteReference = "remote-2",
            ),
            ActionResult.Canceled("execution-1", "用户取消"),
            ActionResult.Expired("execution-1", "确认过期"),
        )

        results.forEach { stored ->
            val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
            fixture.store.installExistingTerminal(fixture.command(), result = stored)

            val replayed = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(stored, assertIs<ActionBusResult.Completed>(replayed).result)
            assertEquals(0, fixture.action.executeCount)
            assertEquals(emptyList(), fixture.audit.events)
        }
    }

    @Test
    fun `成功事实没有普通结果时精确重放输出编码失败`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.READ_ONLY)
        fixture.store.installExistingTerminal(
            fixture.command(),
            successFact = ExecutionSuccessFact(
                kind = ExecutionSuccessFact.OUTPUT_ENCODING_FAILED,
                remoteReference = "remote-encoding",
            ),
        )

        val replayed = fixture.bus.execute(fixture.command(), fixture.context)

        val failed = assertIs<ActionBusResult.OutputEncodingFailed>(replayed)
        assertEquals("execution-1", failed.executionId)
        assertEquals(ActionExecutionState.SUCCEEDED, failed.terminalState)
        assertEquals(ActionErrorCode.PROTOCOL_ERROR, failed.error.code)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `人工对账的结果未知精确返回存储事实且绝不重新执行`() = runTest {
        val fixture = BusFixture(ActionRiskLevel.REVERSIBLE_WRITE)
        val stored: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程结果待人工确认"),
            remoteReference = "remote-unknown",
            reconciliationPolicy = ReconciliationPolicy.MANUAL,
        )
        fixture.store.installExistingTerminal(fixture.command(), result = stored)

        val replayed = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(stored, assertIs<ActionBusResult.Completed>(replayed).result)
        assertEquals(0, fixture.action.previewCount)
        assertEquals(0, fixture.action.executeCount)
    }

    private fun runningRecord(
        command: ActionCommand,
        state: ActionExecutionState,
    ): ActionExecutionRecord = ActionExecutionRecord(
        command = command,
        binding = ExecutionBinding(
            actionId = command.actionId,
            actionVersion = command.actionVersion,
            inputFingerprint = command.inputFingerprint(),
            origin = command.origin,
            identityScope = command.identityScope,
            pageId = command.pageId,
            contextRevision = command.contextRevision,
        ),
        state = state,
        result = null,
        createdAt = NOW,
        startedAt = NOW.takeIf { state == ActionExecutionState.EXECUTING },
        updatedAt = NOW,
        recordVersion = 1,
    )

    private fun ActionCommand.inputFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.canonicalJson().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun JsonElement.canonicalJson(): String = when (this) {
        JsonNull -> "null"
        is JsonPrimitive -> toString()
        is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
        is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
            "${JsonPrimitive(it.key)}:${it.value.canonicalJson()}"
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }

    /** 暴露适配器提交前竞争窗口，用于证明进程内协调不能只依赖单个 Bus 实例。 */
    private class RaceWindowStore : ActionExecutionStore {
        private var record: ActionExecutionRecord? = null
        var createdAudits: Int = 0
            private set

        override suspend fun find(executionId: String): ActionExecutionRecord? = record

        override suspend fun compareAndCreate(
            record: ActionExecutionRecord,
            audit: ActionAuditDraft,
        ): ExecutionCreateResult {
            val existing = this.record
            return when {
                existing == null -> {
                    yield()
                    this.record = record
                    createdAudits += 1
                    ExecutionCreateResult.Created(record)
                }
                existing.binding != record.binding -> ExecutionCreateResult.Conflict(
                    ActionError(ActionErrorCode.EXECUTION_CONFLICT, "binding conflict"),
                )
                existing.isTerminal -> ExecutionCreateResult.ExistingTerminal(existing)
                else -> ExecutionCreateResult.ExistingRunning(existing)
            }
        }

        override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult =
            error("本测试不进入状态迁移")

        override suspend fun updateReconciliation(
            update: ReconciliationExecutionUpdate,
        ): ReconciliationUpdateResult = error("本测试不进入对账")

        override suspend fun claimReconciliation(
            request: com.wzx.huitai.action.port.ReconciliationClaimRequest,
        ): com.wzx.huitai.action.port.ReconciliationClaimResult = error("本测试不进入对账 claim")

        override suspend fun renewReconciliation(
            request: com.wzx.huitai.action.port.ReconciliationRenewRequest,
        ): com.wzx.huitai.action.port.ReconciliationRenewResult = error("本测试不进入对账 renew")

        override suspend fun releaseReconciliation(
            request: com.wzx.huitai.action.port.ReconciliationReleaseRequest,
        ): com.wzx.huitai.action.port.ReconciliationReleaseResult = error("本测试不进入对账 release")
    }
}
