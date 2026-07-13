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
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActionReconciliationTest {
    @Test
    fun `远程查询确认成功后原子收束且第二次只重放最终事实`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded("remote-confirmed")

        val first = fixture.bus.execute(fixture.command(), fixture.context)
        val second = fixture.bus.execute(fixture.command(), fixture.context)

        val success = assertIs<ActionResult.Success<JsonElement>>(assertIs<ActionBusResult.Completed>(first).result)
        assertEquals("execution-1", success.executionId)
        assertEquals("remote-confirmed", success.remoteReference)
        assertEquals(first, second)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(ActionExecutionState.SUCCEEDED, fixture.store.record?.state)
    }

    @Test
    fun `远程查询确认失败后精确保存失败结果且不执行动作`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        val error = ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "远程查询确认失败")
        fixture.action.reconciliationResult = ReconciliationResult.Failed(error)

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
        val before = fixture.store.record

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
        fixture.action.reconciliationResult = ReconciliationResult.Unsupported
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(before, fixture.store.record)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `待处理未找到和查询错误都保留结果未知`() = runTest {
        val uncertain = listOf(
            ReconciliationResult.Pending,
            ReconciliationResult.NotFound,
            ReconciliationResult.Error(
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "对账查询失败"),
            ),
        )

        uncertain.forEach { reconciliation ->
            val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
            fixture.action.reconciliationResult = reconciliation
            val before = fixture.store.record

            val result = fixture.bus.execute(fixture.command(), fixture.context)

            assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
            assertEquals(before, fixture.store.record)
            assertEquals(1, fixture.action.reconcileCount)
            assertEquals(0, fixture.action.executeCount)
        }
    }

    @Test
    fun `对账结果执行标识错配返回协议错误且保留结果未知`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.reconcileExecutionIdOverride = "other-execution"
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded("remote-confirmed")
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(before, fixture.store.record)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
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
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(before, fixture.store.record)
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
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.PROTOCOL_ERROR, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(before, fixture.store.record)
        assertEquals(
            listOf("reconciliation_attempt", "reconciliation_result"),
            fixture.audit.events.map { it.type },
        )
    }

    @Test
    fun `并发结果对账最多调用远程一次且第二调用返回最终事实`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileEntered = CompletableDeferred()
        fixture.action.reconcileRelease = CompletableDeferred()
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded("remote-confirmed")

        val owner = async { fixture.bus.execute(fixture.command(), fixture.context) }
        fixture.action.reconcileEntered!!.await()
        val duplicate = async { fixture.bus.execute(fixture.command(), fixture.context) }
        yield()
        fixture.action.reconcileRelease!!.complete(Unit)

        val ownerResult = owner.await()
        val duplicateResult = duplicate.await()
        assertEquals(ownerResult, duplicateResult)
        assertEquals(1, fixture.action.reconcileCount)
        assertEquals(0, fixture.action.executeCount)
    }

    @Test
    fun `对账普通异常保留结果未知且不追加审计`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconcileFailure = IllegalStateException("secret-reconcile")
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(before, fixture.store.record)
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
        val before = fixture.store.record

        val thrown = assertFailsWith<CancellationException> {
            fixture.bus.execute(fixture.command(), fixture.context)
        }

        assertEquals("cancel-reconciliation", thrown.message)
        assertEquals(before, fixture.store.record)
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
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded("remote-confirmed")
        fixture.audit.failOnState = ActionExecutionState.SUCCEEDED
        val before = fixture.store.record
        val sequenceBefore = fixture.store.nextAuditSequence

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ActionBusResult.Rejected>(result).error.code)
        assertEquals(before, fixture.store.record)
        assertNull(fixture.store.record?.reconciliation)
        assertEquals(listOf("reconciliation_attempt"), fixture.audit.events.map { it.type })
        assertEquals(sequenceBefore + 1, fixture.store.nextAuditSequence)
    }

    @Test
    fun `成功对账只追加独立脱敏事件并记录精确来源`() = runTest {
        val fixture = reconciliationFixture(ReconciliationPolicy.QUERY_REMOTE)
        fixture.action.reconciliationResult = ReconciliationResult.Succeeded("secret-remote-confirmed")
        val sourceVersion = fixture.store.record!!.recordVersion

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertIs<ActionBusResult.Completed>(result)
        val record = fixture.store.record!!
        assertEquals(sourceVersion, record.reconciliation?.sourceRecordVersion)
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
        fixture.action.reconciliationResult = ReconciliationResult.Pending
        val before = fixture.store.record

        val result = fixture.bus.execute(fixture.command(), fixture.context)

        assertEquals(before!!.result, assertIs<ActionBusResult.Completed>(result).result)
        assertEquals(before, fixture.store.record)
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
}
