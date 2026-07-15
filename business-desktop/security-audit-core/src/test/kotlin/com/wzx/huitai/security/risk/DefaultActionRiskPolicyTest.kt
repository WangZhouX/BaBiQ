package com.wzx.huitai.security.risk

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultActionRiskPolicyTest {
    private val policy = DefaultActionRiskPolicy()

    @Test
    fun `未知操作默认提升为高风险并拒绝自动执行`() {
        val result = policy.evaluate(descriptor(operation = "teleport"), command(), context())

        assertEquals(ActionRiskLevel.HIGH_RISK, result.effectiveRisk)
        assertTrue(result.reasons.contains("UNKNOWN_OPERATION"))
    }

    @Test
    fun `策略只能提升不能降低描述符基础风险`() {
        val result = policy.evaluate(
            descriptor(risk = ActionRiskLevel.HIGH_RISK, operation = "list"),
            command(),
            context(),
        )

        assertEquals(ActionRiskLevel.HIGH_RISK, result.effectiveRisk)
    }

    @Test
    fun `敏感字段写入至少为可逆写风险`() {
        val result = policy.evaluate(
            descriptor(operation = "save"),
            command(inputKey = "password"),
            context(),
        )

        assertEquals(ActionRiskLevel.REVERSIBLE_WRITE, result.effectiveRisk)
        assertTrue(result.reasons.contains("SENSITIVE_WRITE"))
    }

    @Test
    fun `提交发送删除用语一律提升为高风险`() {
        listOf("submit", "sendMessage", "delete_record", "提交资料", "发送通知", "删除条目").forEach { operation ->
            val result = policy.evaluate(descriptor(operation = operation), command(), context())

            assertEquals(ActionRiskLevel.HIGH_RISK, result.effectiveRisk, message = operation)
            assertTrue(result.reasons.contains("HIGH_RISK_OPERATION"), message = operation)
        }
    }

    @Test
    fun `已知只读操作且无风险提升时保持只读`() {
        val result = policy.evaluate(descriptor(operation = "query"), command(), context())

        assertEquals(ActionRiskLevel.READ_ONLY, result.effectiveRisk)
        assertTrue(result.reasons.isEmpty())
    }

    private fun descriptor(
        risk: ActionRiskLevel = ActionRiskLevel.READ_ONLY,
        operation: String,
    ) = ActionDescriptor(
        id = "demo.$operation",
        version = 1,
        title = "演示操作",
        description = "框架风险策略测试",
        inputSchema = buildJsonObject { put("type", "object") },
        riskLevel = risk,
        requiredPermissions = emptySet(),
        target = ActionTarget("generic-form", operation),
        replayPolicy = ActionReplayPolicy.NEVER,
        reconciliationPolicy = ReconciliationPolicy.MANUAL,
    )

    private fun command(inputKey: String = "query") = ActionCommand(
        executionId = "execution-1",
        actionId = "demo.action",
        actionVersion = 1,
        input = buildJsonObject { put(inputKey, "value") },
        origin = ActionOrigin.AGENT,
        identityScope = identity(),
        pageId = "page-1",
        contextRevision = 1,
    )

    private fun context() = ActionContext(
        identityScope = identity(),
        pageId = "page-1",
        contextRevision = 1,
        permissions = emptySet(),
    )

    private fun identity() = ActionIdentityScope(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-1",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "platform-1",
    )
}
