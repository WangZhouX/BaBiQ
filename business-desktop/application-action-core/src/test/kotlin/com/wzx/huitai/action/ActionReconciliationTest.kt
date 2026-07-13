package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionReconciliationTest {
    @Test
    fun `远程对账硬超时严格早于claim租约并保留安全余量`() {
        assertEquals(Duration.ofSeconds(15), ReconciliationTimingPolicy.HEARTBEAT_INTERVAL)
        assertEquals(Duration.ofSeconds(30), ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT)
        assertEquals(Duration.ofSeconds(60), ReconciliationTimingPolicy.CLAIM_LEASE)
        assertTrue(!ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.isZero)
        assertTrue(!ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.isNegative)
        assertTrue(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL < ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT)
        assertTrue(ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT < ReconciliationTimingPolicy.CLAIM_LEASE)
        assertTrue(
            ReconciliationTimingPolicy.CLAIM_LEASE.minus(ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT) >=
                Duration.ofSeconds(10),
        )
    }

    @Test
    fun `远程查询确认成功后原子收束且第二次只重放最终事实`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "remote-confirmed",
            executionId = "execution-1",
        )

        val first = fixture.bus.execute(fixture.command(), fixture.context)
        val second = fixture.bus.execute(fixture.command(), fixture.context)

        val success = assertIs<ActionBusResult.SuccessWithoutOutput>(first)
        assertEquals("execution-1", success.executionId)
        assertEquals("remote-confirmed", success.remoteReference)
        assertEquals(com.wzx.huitai.action.port.ExecutionSuccessFact.SOURCE_RECONCILIATION, success.source)
        assertEquals(first, second)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.SUCCEEDED, fixture.store.record?.state)
        assertNull(fixture.store.record?.result)
        assertEquals(
            com.wzx.huitai.action.port.ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
            fixture.store.record?.successFact?.kind,
        )
        assertEquals("remote-confirmed", fixture.store.record?.successFact?.remoteReference)
    }

    @Test
    fun `远程查询确认失败后精确保存失败结果且不执行动作`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        val error = ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "远程查询确认失败")
        fixture.action.reconciliationResult = ReconciliationResult.Failed(error, "execution-1")

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        val failure = assertIs<ActionResult.Failure>(assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(error, failure.error)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.FAILED, fixture.store.record?.state)
    }

    @Test
    fun `人工策略保持精确结果未知且不调用对账或执行`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.MANUAL)
        val stored = fixture.store.record!!.result

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(stored, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(0, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
    }

    @Test
    fun `无对账策略返回配置错误且不改变存储或审计`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.NONE)
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(before, fixture.store.record)
        assertEquals(emptyList(), fixture.audit.events)
        assertEquals(0, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `对账不支持时保留结果未知且不盲写失败`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Unsupported("execution-1")
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before.result, assertIs<ActionBusResult.Completed>(result).result)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `待处理未找到和查询错误都保留结果未知`() = runTest {
        val uncertain = listOf(
            ReconciliationResult.Pending("execution-1"),
            ReconciliationResult.NotFound("execution-1"),
            ReconciliationResult.Error(
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "对账查询失败"),
                "execution-1",
            ),
        )

        uncertain.forEach { reconciliation ->
            val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
            fixture.action.reconciliationResult = reconciliation
            val before = fixture.store.record

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
            assertReleasedUnknown(before, fixture.store.record!!)
            assertEquals(1, fixture.action.reconcileCount)
            assertEquals(0, fixture.action.executeCount)
        }
    }

    @Test
    fun `真实动作返回错误执行标识时结构化拒绝并释放claim`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            executionId = "other-execution",
            remoteReference = "remote-confirmed",
        )
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    fun `当前上下文任一维度变化都在begin和claim前拒绝`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        val contexts = listOf(
            fixture.context.copy(identityScope = fixture.identity.copy(identityEpoch = 2)),
            fixture.context.copy(pageId = "page-2"),
            fixture.context.copy(contextRevision = fixture.context.contextRevision + 1),
            fixture.context.copy(permissions = emptySet()),
        )
        val expectedCodes = listOf(
            ActionErrorCode.CONTEXT_STALE,
            ActionErrorCode.CONTEXT_STALE,
            ActionErrorCode.CONTEXT_STALE,
            ActionErrorCode.PERMISSION_DENIED,
        )

        contexts.zip(expectedCodes).forEach { (changedContext, expectedCode) ->
            val isolated = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
            val before = isolated.store.record!!

            val result = isolated.bus.execute(isolated.command(), changedContext)

            assertEquals(expectedCode, assertIs<ActionBusResult.Rejected>(result).error.code)
            assertEquals(before, isolated.store.record)
            assertEquals(0, isolated.action.reconcileCount)
            assertEquals(emptyList(), isolated.audit.events)
        }
    }

    @Test
    fun `动作注册缺失时在begin和claim前拒绝且允许再次检查`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            registerAction = false,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            remoteReference = "remote-original",
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        fixture.store.installExistingTerminal(fixture.command(), result = unknown)
        val before = fixture.store.record!!

        val first = fixture.bus.execute(fixture.command(), fixture.context)
        val second = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.ACTION_NOT_FOUND, assertIs<ActionBusResult.Rejected>(first).error.code)
        assertEquals(first, second)
        assertEquals(before, fixture.store.record)
        assertNull(fixture.store.record?.reconciliationClaim)
        assertEquals(emptyList(), fixture.audit.events)
        assertEquals(0, fixture.action.reconcileCount)
    }

    @Test
    fun `v1结果未知只调用冻结的v1对账而不选择更高版本`() = runTest {
        val version2Action = BusCountingAction(
            busDescriptor(
                risk = ActionRiskLevel.REVERSIBLE_WRITE,
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
                version = 2,
            ),
        )
        val fixture = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            additionalRegisteredActions = listOf(
                RegisteredAction(version2Action, BusInputCodec(), BusOutputCodec()),
            ),
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        fixture.store.installExistingTerminal(fixture.command(), result = unknown)
        fixture.action.reconciliationResult = ReconciliationResult.Pending("execution-1")
        version2Action.reconciliationResult = ReconciliationResult.Failed(
            ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "不应调用v2"),
            "execution-1",
        )

        fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, version2Action.reconcileCount)
    }

    @Test
    fun `v1已移除时不回退v2且不claim未知记录`() = runTest {
        val version2Action = BusCountingAction(
            busDescriptor(
                risk = ActionRiskLevel.REVERSIBLE_WRITE,
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
                version = 2,
            ),
        )
        val fixture = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            registerAction = false,
            additionalRegisteredActions = listOf(
                RegisteredAction(version2Action, BusInputCodec(), BusOutputCodec()),
            ),
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        fixture.store.installExistingTerminal(fixture.command(), result = unknown)
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.ACTION_NOT_FOUND, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(before, fixture.store.record)
        assertEquals(emptyList(), fixture.audit.events)
        assertEquals(0, fixture.action.reconcileCount)
        assertEquals(0, version2Action.reconcileCount)
    }

    @Test
    fun `对账输入解码失败也记录结果事件并保留未知事实`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.commandInput = kotlinx.serialization.json.buildJsonObject {
            put("value", kotlinx.serialization.json.JsonPrimitive("invalid"))
        }
        fixture.store.installExistingTerminal(
            fixture.command(),
            result = ActionResult.OutcomeUnknown(
                executionId = "execution-1",
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            ),
        )
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before.result, assertIs<ActionBusResult.Completed>(result).result)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    fun `非法对账调用结果记录结果事件后返回协议错误`() = runTest {
        val fixture = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            reconcileInvocationOverride = {
                ActionInvocationResult.Executed(
                    ActionResult.Success("execution-1", kotlinx.serialization.json.buildJsonObject { }),
                )
            },
        )
        fixture.store.installExistingTerminal(
            fixture.command(),
            result = ActionResult.OutcomeUnknown(
                executionId = "execution-1",
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            ),
        )
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    fun `并发结果对账最多调用远程一次且第二调用观察持久claim`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "remote-confirmed",
            executionId = "execution-1",
        )

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.reconcileEntered!!.await()
        val duplicate = async { fixture.bus.execute(fixture.command(), fixture.context) }
        yield()
        val duplicateResult = duplicate.await()
        assertIs<ActionBusResult.InProgress>(duplicateResult)
        fixture.action.reconcileRelease!!.complete(Unit)

        assertIs<ActionBusResult.SuccessWithoutOutput>(owner.await())
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `两个隔离进程锁域共享存储时持久claim只允许一个远程对账`() = runTest {
        val first = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
        )
        val second = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            store = first.store,
            audit = first.audit,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        first.store.installExistingTerminal(first.command(), result = unknown)
        first.action.reconcileEntered = CompletableDeferred()
        first.action.reconcileRelease = CompletableDeferred()
        first.action.reconciliationResult = ReconciliationResult.Pending("execution-1")

        val owner = async { first.bus.execute(first.command(), first.context) }
        first.action.reconcileEntered!!.await()
        val duplicate = second.bus.execute(second.command(), second.context)

        assertIs<ActionBusResult.InProgress>(duplicate)
        assertEquals(1, first.action.reconcileCount + second.action.reconcileCount)
        first.action.reconcileRelease!!.complete(Unit)
        owner.await()
    }

    @Test
    fun `同一stripe不同execution在A远程阻塞时B仍可完成`() = runTest {
        val sharedScope = StripedActionExecutionLockScope(1)
        val first = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = sharedScope,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        first.store.installExistingTerminal(first.command(), result = unknown)
        first.action.reconcileEntered = CompletableDeferred()
        first.action.reconcileRelease = CompletableDeferred()
        first.action.reconciliationResult = ReconciliationResult.Pending("execution-1")

        val owner = async { first.bus.execute(first.command(), first.context) }
        first.action.reconcileEntered!!.await()

        val second = BusFixture(
            risk = ActionRiskLevel.READ_ONLY,
            lockScope = sharedScope,
        )
        second.action.result = ActionResult.Success(
            executionId = "execution-2",
            output = BusOutput(2),
        )
        val independent = async {
            second.bus.execute(second.command(executionId = "execution-2"), second.context)
        }
        yield()

        assertTrue(independent.isCompleted, "不同 execution 不应被同 stripe 的远程 I/O 阻塞")
        assertIs<ActionBusResult.Completed>(independent.await())
        assertEquals(1, second.action.executeCount)

        first.action.reconcileRelease!!.complete(Unit)
        owner.await()
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `远程对账超时先释放claim再允许第二Bus重试且不并发`() = runTest {
        val sharedClock = BusClock(autoAdvance = false, initialSeconds = 2)
        val first = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            clock = sharedClock,
        )
        val second = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            store = first.store,
            audit = first.audit,
            clock = sharedClock,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            remoteReference = "remote-original",
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        first.store.installExistingTerminal(first.command(), result = unknown)
        first.action.reconcileEntered = CompletableDeferred()
        first.action.reconcileRelease = CompletableDeferred()

        val owner = async { first.bus.execute(first.command(), first.context) }
        first.action.reconcileEntered!!.await()
        val claimed = first.store.record!!

        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.RECONCILIATION_TIMEOUT.toMillis() - 1)
        testScheduler.runCurrent()
        assertFalse(owner.isCompleted)
        assertIs<ActionBusResult.InProgress>(second.bus.execute(second.command(), second.context))
        assertEquals(0, second.action.reconcileCount)

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertTrue(owner.isCompleted, "硬超时到达后 owner 必须先结束并释放 claim")
        assertEquals(unknown, assertIs<ActionBusResult.Completed>(owner.await()).result)
        assertEquals(0, first.action.activeReconcileCount)
        assertEquals(1, first.action.maximumActiveReconcileCount)
        assertNull(first.store.record?.reconciliationClaim)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_claim_renewed", "reconciliation_result"),
            first.audit.events.map { it.type },
        )

        sharedClock.advanceTo(claimed.reconciliationClaim!!.expiresAt)
        second.action.reconciliationResult = ReconciliationResult.Pending("execution-1")
        val retried = second.bus.execute(second.command(), second.context)

        assertIs<ActionBusResult.Completed>(retried)
        assertEquals(1, second.action.reconcileCount)
        assertEquals(0, first.action.activeReconcileCount)
        assertEquals(0, second.action.activeReconcileCount)
        assertEquals(1, second.action.maximumActiveReconcileCount)
    }

    @Test
    fun `调用方较短外层timeout必须传播且释放claim保留结果未知`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        val before = fixture.store.record!!

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) {
                fixture.bus.execute(fixture.command(), fixture.context)
            }
        }

        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
        assertTrue(
            fixture.audit.events.last().redactedPayload.toString().contains("\"outcome\":\"canceled\""),
        )
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.activeReconcileCount)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `活动owner续租后跨scope在旧expiresAt仍不能接管且远程最多并发一次`() = runTest {
        val sharedClock = BusClock()
        val first = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            clock = sharedClock,
        )
        val second = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            store = first.store,
            audit = first.audit,
            clock = sharedClock,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        first.store.installExistingTerminal(first.command(), result = unknown)
        first.action.reconcileEntered = CompletableDeferred()
        first.action.reconcileRelease = CompletableDeferred()

        val owner = async { first.bus.execute(first.command(), first.context) }
        first.action.reconcileEntered!!.await()
        val originalClaimed = first.store.record!!
        val originalExpiresAt = originalClaimed.reconciliationClaim!!.expiresAt

        sharedClock.advanceTo(
            originalClaimed.reconciliationClaim.claimedAt.plus(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL),
        )
        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis())
        testScheduler.runCurrent()
        val renewed = first.store.record!!

        assertEquals(originalClaimed.recordVersion + 1, renewed.recordVersion)
        assertTrue(renewed.reconciliationClaim!!.expiresAt.isAfter(originalExpiresAt))
        assertEquals(1, first.audit.events.count { it.type == "reconciliation_claim_renewed" })

        sharedClock.advanceTo(originalExpiresAt)
        assertIs<ActionBusResult.InProgress>(second.bus.execute(second.command(), second.context))
        assertEquals(0, second.action.reconcileCount)
        assertEquals(1, first.action.maximumActiveReconcileCount)
        assertEquals(1, first.action.activeReconcileCount)

        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis())
        testScheduler.runCurrent()

        assertTrue(owner.isCompleted)
        assertEquals(unknown, assertIs<ActionBusResult.Completed>(owner.await()).result)
        assertEquals(0, first.action.activeReconcileCount)
        assertEquals(1, first.action.maximumActiveReconcileCount)
        assertNull(first.store.record?.reconciliationClaim)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `远程结果在heartbeat后使用最新record version提交终态`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "remote-confirmed",
            executionId = "execution-1",
        )

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.reconcileEntered!!.await()
        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis())
        testScheduler.runCurrent()
        val renewedVersion = fixture.store.record!!.recordVersion
        fixture.action.reconcileRelease!!.complete(Unit)

        assertIs<ActionBusResult.SuccessWithoutOutput>(owner.await())
        assertEquals(renewedVersion, fixture.store.record?.reconciliation?.sourceRecordVersion)
        assertNull(fixture.store.record?.reconciliationClaim)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_claim_renewed", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `heartbeat发现其他claim时取消本地对账且不覆盖新owner`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        lateinit var takeover: com.wzx.huitai.action.port.ActionExecutionRecord
        fixture.store.renewOverride = { request, current ->
            takeover = current.copy(
                updatedAt = request.now,
                recordVersion = current.recordVersion + 1,
                reconciliationClaim = current.reconciliationClaim!!.copy(
                    claimToken = "replacement-token",
                    ownerId = "replacement-owner",
                    claimedAt = request.now,
                    expiresAt = request.expiresAt,
                ),
            )
            fixture.store.record = takeover
            com.wzx.huitai.action.port.ReconciliationRenewResult.ExistingClaim(takeover)
        }

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.reconcileEntered!!.await()
        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis())
        testScheduler.runCurrent()

        assertIs<ActionBusResult.InProgress>(owner.await())
        assertEquals(takeover, fixture.store.record)
        assertEquals(0, fixture.action.activeReconcileCount)
        assertEquals(listOf("reconciliation_attempt"), fixture.audit.events.map { it.type })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `heartbeat版本冲突时取消本地对账并返回冲突`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        val conflict = ActionError(ActionErrorCode.EXECUTION_CONFLICT, "renew conflict")
        fixture.store.renewOverride = { _, _ ->
            com.wzx.huitai.action.port.ReconciliationRenewResult.Conflict(conflict)
        }

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.reconcileEntered!!.await()
        testScheduler.advanceTimeBy(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis())
        testScheduler.runCurrent()

        assertEquals(conflict, assertIs<ActionBusResult.Rejected>(owner.await()).error)
        assertEquals(0, fixture.action.activeReconcileCount)
        assertEquals(listOf("reconciliation_attempt"), fixture.audit.events.map { it.type })
        assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.store.record?.state)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `heartbeat后外部取消的release失败附加suppressed并保留续租租约`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        val cancellation = CancellationException("external-cancel")
        fixture.action.reconcileFailure = cancellation
        val conflict = ActionError(ActionErrorCode.EXECUTION_CONFLICT, "release conflict")
        fixture.store.releaseOverride = { _, _ ->
            com.wzx.huitai.action.port.ReconciliationReleaseResult.Conflict(conflict)
        }
        lateinit var originalExpiresAt: java.time.Instant
        val releaseAfterHeartbeat = launch {
            fixture.action.reconcileEntered!!.await()
            originalExpiresAt = fixture.store.record!!.reconciliationClaim!!.expiresAt
            delay(ReconciliationTimingPolicy.HEARTBEAT_INTERVAL.toMillis() + 1_000)
            fixture.action.reconcileRelease!!.complete(Unit)
        }

        val thrown = assertFailsWith<CancellationException> {
            fixture.bus.execute(fixture.command(), fixture.context)
        }
        releaseAfterHeartbeat.join()
        val renewedExpiresAt = fixture.store.record!!.reconciliationClaim!!.expiresAt

        assertEquals("external-cancel", thrown.message)
        val cancellationChain = mutableListOf<Throwable>()
        var current: Throwable? = thrown
        while (current != null && cancellationChain.none { it === current }) {
            cancellationChain += current
            current = current.cause
        }
        assertTrue(
            cancellationChain.any { cancellation ->
                cancellation.suppressed.any { it.message?.contains("EXECUTION_CONFLICT") == true }
            },
            "message=${thrown.message}, cause=${thrown.cause?.message}, " +
                "suppressed=${cancellationChain.flatMap { it.suppressed.toList() }.map { it.message }}",
        )
        assertTrue(renewedExpiresAt.isAfter(originalExpiresAt))
        assertEquals(renewedExpiresAt, fixture.store.record?.reconciliationClaim?.expiresAt)
        assertEquals(0, fixture.action.activeReconcileCount)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_claim_renewed"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    fun `claim owner由冻结桌面实例和session派生且日志不泄露`() = runTest {
        suspend fun claimedOwner(sessionId: String): Pair<String, String> {
            val identity = busIdentity(
                desktopInstanceId = "secret-desktop-instance",
                desktopSessionId = sessionId,
            )
            val fixture = BusFixture(
                risk = ActionRiskLevel.REVERSIBLE_WRITE,
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
                identity = identity,
            )
            val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
                executionId = "execution-1",
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            )
            fixture.store.installExistingTerminal(fixture.command(), result = unknown)
            fixture.action.reconcileEntered = CompletableDeferred()
            fixture.action.reconcileRelease = CompletableDeferred()

            val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
            fixture.action.reconcileEntered!!.await()
            val claim = fixture.store.record!!.reconciliationClaim!!
            owner.cancel()

            assertFalse(claim.ownerId.contains(identity.desktopInstanceId))
            assertFalse(claim.ownerId.contains(identity.desktopSessionId))
            assertFalse(claim.toString().contains(identity.desktopInstanceId))
            assertFalse(claim.toString().contains(identity.desktopSessionId))
            return claim.ownerId to claim.toString()
        }

        val first = claimedOwner("secret-session-a")
        val second = claimedOwner("secret-session-b")

        assertTrue(first.first != second.first)
        assertFalse(first.second.contains("secret-session"))
        assertFalse(second.second.contains("secret-session"))
    }

    @Test
    fun `隔离进程在租约到期后懒接管并只对账一次`() = runTest {
        val sharedClock = BusClock()
        val first = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            clock = sharedClock,
        )
        val second = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            lockScope = StripedActionExecutionLockScope(4),
            store = first.store,
            audit = first.audit,
            clock = sharedClock,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            remoteReference = "remote-original",
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        first.store.installExistingTerminal(first.command(), result = unknown)
        sharedClock.advanceTo(first.store.record!!.updatedAt.plusSeconds(1))
        val claimNow = sharedClock.now()
        val oldOwner = assertIs<com.wzx.huitai.action.port.ReconciliationClaimResult.Claimed>(
            first.store.claimReconciliation(
                com.wzx.huitai.action.port.ReconciliationClaimRequest(
                    executionId = "execution-1",
                    expectedVersion = first.store.record!!.recordVersion,
                    claimToken = "crashed-owner-token",
                    ownerId = "crashed-owner",
                    now = claimNow,
                    leaseDuration = Duration.ofSeconds(60),
                    audit = com.wzx.huitai.action.port.ActionAuditDraft(
                        executionId = "execution-1",
                        fromState = ActionExecutionState.OUTCOME_UNKNOWN,
                        toState = ActionExecutionState.OUTCOME_UNKNOWN,
                        type = "reconciliation_attempt",
                        redactedPayload = buildJsonObject { },
                        actorId = null,
                        occurredAt = claimNow,
                    ),
                ),
            ),
        ).record

        val activeLease = second.bus.execute(second.command(), second.context)

        assertIs<ActionBusResult.InProgress>(activeLease)
        assertEquals(0, second.action.reconcileCount)
        assertEquals(oldOwner, first.store.record)

        sharedClock.advanceTo(oldOwner.reconciliationClaim!!.expiresAt)
        second.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "remote-confirmed",
            executionId = "execution-1",
        )

        val recovered = second.bus.execute(second.command(), second.context)

        assertIs<ActionBusResult.SuccessWithoutOutput>(recovered)
        assertEquals(1, second.action.reconcileCount)
        assertEquals(ActionExecutionState.SUCCEEDED, first.store.record?.state)
        assertNull(first.store.record?.reconciliationClaim)
        assertEquals(2, first.audit.events.count { it.type == "reconciliation_attempt" })
        assertTrue(
            first.audit.events.last { it.type == "reconciliation_attempt" }
                .redactedPayload.toString().contains("\"takeover\":true"),
        )
    }

    @Test
    fun `对账普通异常保留结果未知且不追加审计`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileFailure = IllegalStateException("secret-reconcile")
        val before = fixture.store.record!!

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before.result, assertIs<ActionBusResult.Completed>(result).result)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
        assertEquals(
            listOf(
                ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.OUTCOME_UNKNOWN,
                ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.OUTCOME_UNKNOWN,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `对账真实取消传播原取消且保留结果未知`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileFailure = CancellationException("cancel-reconciliation")
        val before = fixture.store.record!!

        val thrown = assertFailsWith<CancellationException> {
            fixture.bus.execute(fixture.command(), fixture.context)
        }

        assertEquals("cancel-reconciliation", thrown.message)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `对账结果审计插入失败时仅保留已提交尝试且状态版本来源不变`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "remote-confirmed",
            executionId = "execution-1",
        )
        fixture.audit.failOnState = ActionExecutionState.SUCCEEDED
        val before = fixture.store.record
        val sequenceBefore = fixture.store.nextAuditSequence

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        val claimed = fixture.store.record!!
        assertEquals(before!!.result, claimed.result)
        assertEquals(before.recordVersion + 1, claimed.recordVersion)
        kotlin.test.assertNotNull(claimed.reconciliationClaim)
        assertNull(claimed.reconciliation)
        assertEquals(listOf("reconciliation_attempt"), fixture.audit.events.map { it.type })
        assertEquals(sequenceBefore + 1, fixture.store.nextAuditSequence)
    }

    @Test
    fun `成功对账只追加独立脱敏事件并记录精确来源`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded(
            remoteReference = "secret-remote-confirmed",
            executionId = "execution-1",
        )
        val sourceVersion = fixture.store.record!!.recordVersion

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        val success = assertIs<ActionBusResult.SuccessWithoutOutput>(result)
        assertEquals("execution-1", success.executionId)
        assertEquals("secret-remote-confirmed", success.remoteReference)
        assertEquals(com.wzx.huitai.action.port.ExecutionSuccessFact.SOURCE_RECONCILIATION, success.source)
        val record = fixture.store.record!!
        assertEquals(sourceVersion + 1, record.reconciliation?.sourceRecordVersion)
        assertEquals(record.completedAt, record.reconciliation?.reconciledAt)
        assertEquals(2, fixture.audit.events.size)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
        assertEquals(
            listOf(
                ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.OUTCOME_UNKNOWN,
                ActionExecutionState.OUTCOME_UNKNOWN to ActionExecutionState.SUCCEEDED,
            ),
            fixture.audit.events.map { it.fromState to it.toState },
        )
        assertEquals(listOf(1L, 2L), fixture.audit.events.map { it.sequence })
        fixture.audit.events.forEach { event ->
            kotlin.test.assertFalse("secret" in event.toString())
        }
    }

    @Test
    fun `未确认结果追加尝试和结果审计但保持版本与来源不变`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Pending("execution-1")
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
        assertReleasedUnknown(before, fixture.store.record!!)
        assertNull(fixture.store.record?.reconciliation)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
        assertEquals(listOf(1L, 2L), fixture.audit.events.map { it.sequence })
    }

    private fun reconciliationFixture(policy: ReconciliationPolicy): BusFixture {
        val fixture = BusFixture(
            risk = ActionRiskLevel.REVERSIBLE_WRITE,
            reconciliationPolicy = policy,
        )
        val unknown: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程响应丢失"),
            remoteReference = "remote-original",
            reconciliationPolicy = policy,
        )
        fixture.store.installExistingTerminal(fixture.command(), result = unknown)
        return fixture
    }

    private fun assertReleasedUnknown(
        before: com.wzx.huitai.action.port.ActionExecutionRecord,
        after: com.wzx.huitai.action.port.ActionExecutionRecord,
    ) {
        assertEquals(before.state, after.state)
        assertEquals(before.result, after.result)
        assertEquals(before.recordVersion + 2, after.recordVersion)
        assertNull(after.reconciliationClaim)
        assertNull(after.reconciliation)
    }
}
