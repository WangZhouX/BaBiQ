package com.wzx.huitai.security.approval

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionApproval
import com.wzx.huitai.action.port.ActionConfirmation
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.RiskEvaluation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ApprovalAdapterTest {
    @Test
    fun `确认决定按execution独立消费且不可跨execution复用`() = runTest {
        val port = InMemoryConfirmationPort(
            listOf(
                confirmation("execution-a", ConfirmationDecision.ACCEPTED),
                confirmation("execution-b", ConfirmationDecision.REJECTED),
            ),
        )

        assertEquals(ConfirmationDecision.ACCEPTED, port.request(command("execution-a"), preview("execution-a"), context()).decision)
        assertEquals(ConfirmationDecision.REJECTED, port.request(command("execution-b"), preview("execution-b"), context()).decision)
        assertFailsWith<IllegalStateException> {
            port.request(command("execution-a"), preview("execution-a"), context())
        }
    }

    @Test
    fun `审批拒绝和过期是各execution的终态决定`() = runTest {
        val port = InMemoryApprovalPort(
            listOf(
                approval("execution-denied", ApprovalDecision.DENIED),
                approval("execution-expired", ApprovalDecision.EXPIRED),
            ),
        )

        val denied = port.request(
            command("execution-denied"), preview("execution-denied"), risk(), context(),
        )
        val expired = port.request(
            command("execution-expired"), preview("execution-expired"), risk(), context(),
        )

        assertEquals(ApprovalDecision.DENIED, denied.decision)
        assertEquals(ApprovalDecision.EXPIRED, expired.decision)
        assertFailsWith<IllegalStateException> {
            port.request(command("execution-denied"), preview("execution-denied"), risk(), context())
        }
    }

    @Test
    fun `适配器不暴露会话级审批接口`() {
        val forbidden = setOf("approveSession", "allowAlways", "grantSession", "approveAll")

        assertFalse(InMemoryConfirmationPort::class.java.methods.any { it.name in forbidden })
        assertFalse(InMemoryApprovalPort::class.java.methods.any { it.name in forbidden })
    }

    @Test
    fun `同一execution不能排队或追加第二个终态决定`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            InMemoryConfirmationPort(
                listOf(
                    confirmation("execution-a", ConfirmationDecision.REJECTED),
                    confirmation("execution-a", ConfirmationDecision.ACCEPTED),
                ),
            )
        }
        val approvals = InMemoryApprovalPort(
            listOf(approval("execution-a", ApprovalDecision.DENIED)),
        )

        assertFailsWith<IllegalArgumentException> {
            approvals.enqueue(approval("execution-a", ApprovalDecision.APPROVED))
        }
    }

    @Test
    fun `确认和审批可按execution乱序消费且错误execution不会阻塞或偷取消费`() = runTest {
        val confirmations = InMemoryConfirmationPort(
            listOf(
                confirmation("execution-a", ConfirmationDecision.ACCEPTED),
                confirmation("execution-b", ConfirmationDecision.REJECTED),
            ),
        )
        assertFailsWith<IllegalStateException> {
            confirmations.request(command("execution-missing"), preview("execution-missing"), context())
        }
        assertEquals(
            ConfirmationDecision.REJECTED,
            confirmations.request(command("execution-b"), preview("execution-b"), context()).decision,
        )
        assertEquals(
            ConfirmationDecision.ACCEPTED,
            confirmations.request(command("execution-a"), preview("execution-a"), context()).decision,
        )

        val approvals = InMemoryApprovalPort(
            listOf(
                approval("execution-a", ApprovalDecision.APPROVED),
                approval("execution-b", ApprovalDecision.DENIED),
            ),
        )
        assertEquals(
            ApprovalDecision.DENIED,
            approvals.request(command("execution-b"), preview("execution-b"), risk(), context()).decision,
        )
        assertEquals(
            ApprovalDecision.APPROVED,
            approvals.request(command("execution-a"), preview("execution-a"), risk(), context()).decision,
        )
    }

    @Test
    fun `并发execution各自仅消费一次独立审批决定`() = runTest {
        val approvals = InMemoryApprovalPort(
            (1..32).map { approval("execution-$it", ApprovalDecision.APPROVED) },
        )

        val consumed = (32 downTo 1).map { index ->
            async {
                approvals.request(
                    command("execution-$index"),
                    preview("execution-$index"),
                    risk(),
                    context(),
                ).executionId
            }
        }.awaitAll()

        assertEquals((1..32).map { "execution-$it" }.toSet(), consumed.toSet())
        assertFailsWith<IllegalStateException> {
            approvals.request(command("execution-1"), preview("execution-1"), risk(), context())
        }
    }

    private fun confirmation(executionId: String, decision: ConfirmationDecision) = ActionConfirmation(
        decisionId = "confirmation-$executionId",
        executionId = executionId,
        decision = decision,
        decidedAt = NOW,
    )

    private fun approval(executionId: String, decision: ApprovalDecision) = ActionApproval(
        approvalId = "approval-$executionId",
        executionId = executionId,
        decision = decision,
        decidedAt = NOW,
        decidedBy = "reviewer-1",
    )

    private fun command(executionId: String) = ActionCommand(
        executionId = executionId,
        actionId = "demo.submit",
        actionVersion = 1,
        input = buildJsonObject { },
        origin = ActionOrigin.AGENT,
        identityScope = identity(),
        pageId = "page-1",
        contextRevision = 1,
    )

    private fun preview(executionId: String) = ActionPreview(executionId, "预览")

    private fun context() = ActionContext(identity(), "page-1", 1, emptySet())

    private fun identity() = ActionIdentityScope(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = "auth-1",
        identityEpoch = 1,
        userId = "user-1",
        tenantId = "tenant-1",
        platformId = "platform-1",
    )

    private fun risk() = RiskEvaluation.atLeast(ActionRiskLevel.HIGH_RISK, ActionRiskLevel.HIGH_RISK)

    private companion object {
        val NOW: java.time.Instant = java.time.Instant.parse("2026-07-14T00:00:00Z")
    }
}
