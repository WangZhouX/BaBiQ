package com.wzx.huitai.desktop.decision

import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.action.port.ConfirmationDecision
import com.wzx.huitai.action.port.RiskEvaluation
import com.wzx.huitai.security.audit.AuditRedactor
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeActionDecisionCoordinatorTest {
    @Test
    fun `duplicate phase conflicts while different executions stay isolated and decisions consume once`() = runTest {
        val coordinator = coordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-a"), preview("execution-a"), context())
        }

        val conflict = runCatching {
            coordinator.requestConfirmation(command("execution-a"), preview("execution-a"), context())
        }.exceptionOrNull()
        assertTrue(conflict is IllegalStateException)

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-b"), preview("execution-b"), context())
        }
        assertEquals(
            listOf("execution-a", "execution-b"),
            coordinator.state.value.dialogs.map { it.executionId },
        )

        assertFalse(coordinator.accept("execution-missing"))
        assertTrue(coordinator.accept("execution-a"))
        assertEquals(ConfirmationDecision.ACCEPTED, first.await().decision)
        assertEquals(listOf("execution-b"), coordinator.state.value.dialogs.map { it.executionId })
        assertFalse(coordinator.reject("execution-a"))

        assertTrue(coordinator.reject("execution-b"))
        assertEquals(ConfirmationDecision.REJECTED, second.await().decision)
        assertTrue(coordinator.state.value.dialogs.isEmpty())
    }

    @Test
    fun `confirmation and high risk approval are separate phases for one execution`() = runTest {
        val coordinator = coordinator()
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            ComposeConfirmationPort(coordinator).request(
                command("execution-risk"),
                preview("execution-risk"),
                context(),
            )
        }
        assertTrue(coordinator.state.value.activeDialog is ConfirmationDecisionDialogState)
        assertTrue(coordinator.accept("execution-risk"))
        assertEquals(ConfirmationDecision.ACCEPTED, confirmation.await().decision)

        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            ComposeApprovalPort(coordinator).request(
                command("execution-risk"),
                preview("execution-risk"),
                risk(),
                context(),
            )
        }
        val dialog = coordinator.state.value.activeDialog
        assertTrue(dialog is HighRiskApprovalDialogState)
        assertEquals(ActionDecisionPhase.HIGH_RISK_APPROVAL, dialog?.phase)
        assertTrue(coordinator.approve("execution-risk"))
        val result = approval.await()
        assertEquals(ApprovalDecision.APPROVED, result.decision)
        assertEquals("raw-user-id", result.decidedBy)

        val duplicate = runCatching {
            coordinator.requestApproval(command("execution-risk"), preview("execution-risk"), risk(), context())
        }.exceptionOrNull()
        assertTrue(duplicate is IllegalStateException)
        assertFalse(coordinator.deny("execution-risk"))
    }

    @Test
    fun `timeout returns exact expired decision closes dialog and late resolution cannot affect a new execution`() = runTest {
        val coordinator = coordinator(timeoutMillis = 1_000)
        val expired = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-old"), preview("execution-old"), context())
        }
        assertEquals("execution-old", coordinator.state.value.activeDialog?.executionId)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConfirmationDecision.EXPIRED, expired.await().decision)
        assertTrue(coordinator.state.value.dialogs.isEmpty())
        assertFalse(coordinator.accept("execution-old"))

        val current = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-new"), preview("execution-new"), context())
        }
        assertFalse(coordinator.reject("execution-old"))
        assertEquals("execution-new", coordinator.state.value.activeDialog?.executionId)
        assertTrue(coordinator.accept("execution-new"))
        assertEquals(ConfirmationDecision.ACCEPTED, current.await().decision)
    }

    @Test
    fun `approval timeout returns exact expired approval`() = runTest {
        val coordinator = coordinator(timeoutMillis = 1_000)
        val expired = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestApproval(command("execution-risk"), preview("execution-risk"), risk(), context())
        }

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ApprovalDecision.EXPIRED, expired.await().decision)
        assertTrue(coordinator.state.value.dialogs.isEmpty())
    }

    @Test
    fun `late confirmation clicks expire atomically even when timeout scheduler has not advanced`() = runTest {
        listOf<(ComposeActionDecisionCoordinator, String) -> Boolean>(
            ComposeActionDecisionCoordinator::accept,
            ComposeActionDecisionCoordinator::reject,
        ).forEachIndexed { index, click ->
            var now = Instant.parse("2026-07-18T00:00:00Z")
            val coordinator = coordinator(timeoutMillis = 1_000, clock = { now })
            val executionId = "execution-late-confirmation-$index"
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.requestConfirmation(command(executionId), preview(executionId), context())
            }

            now = Instant.ofEpochMilli(coordinator.state.value.activeDialog!!.expiresAtEpochMillis)

            assertFalse(click(coordinator, executionId))
            assertEquals(ConfirmationDecision.EXPIRED, pending.await().decision)
            assertTrue(coordinator.state.value.dialogs.isEmpty())
        }
    }

    @Test
    fun `late approval clicks expire atomically even when timeout scheduler has not advanced`() = runTest {
        listOf<(ComposeActionDecisionCoordinator, String) -> Boolean>(
            ComposeActionDecisionCoordinator::approve,
            ComposeActionDecisionCoordinator::deny,
        ).forEachIndexed { index, click ->
            var now = Instant.parse("2026-07-18T00:00:00Z")
            val coordinator = coordinator(timeoutMillis = 1_000, clock = { now })
            val executionId = "execution-late-approval-$index"
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.requestApproval(command(executionId), preview(executionId), risk(), context())
            }

            now = Instant.ofEpochMilli(coordinator.state.value.activeDialog!!.expiresAtEpochMillis)

            assertFalse(click(coordinator, executionId))
            assertEquals(ApprovalDecision.EXPIRED, pending.await().decision)
            assertTrue(coordinator.state.value.dialogs.isEmpty())
        }
    }

    @Test
    fun `decision just before deadline wins and later deadline callbacks are ignored`() = runTest {
        var now = Instant.parse("2026-07-18T00:00:00Z")
        val coordinator = coordinator(timeoutMillis = 1_000, clock = { now })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-race"), preview("execution-race"), context())
        }
        val deadline = coordinator.state.value.activeDialog!!.expiresAtEpochMillis

        now = Instant.ofEpochMilli(deadline - 1)
        assertTrue(coordinator.accept("execution-race"))
        now = Instant.ofEpochMilli(deadline)
        assertFalse(coordinator.reject("execution-race"))

        assertEquals(ConfirmationDecision.ACCEPTED, pending.await().decision)
    }

    @Test
    fun `agent disconnect cancels every pre execution phase without cross execution sharing`() = runTest {
        val coordinator = coordinator()
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-confirm"), preview("execution-confirm"), context())
        }
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestApproval(command("execution-approve"), preview("execution-approve"), risk(), context())
        }

        coordinator.onAgentDisconnected()

        assertEquals(ConfirmationDecision.REJECTED, confirmation.await().decision)
        assertEquals(ApprovalDecision.DENIED, approval.await().decision)
        assertTrue(coordinator.state.value.dialogs.isEmpty())
        assertFalse(coordinator.accept("execution-confirm"))
        assertFalse(coordinator.approve("execution-approve"))
    }

    @Test
    fun `disconnect is latched until reconnect so requests cannot enter a stale connection window`() = runTest {
        val coordinator = coordinator()

        coordinator.onAgentDisconnected()

        val disconnected = coordinator.requestConfirmation(
            command("execution-disconnected"),
            preview("execution-disconnected"),
            context(),
        )
        assertEquals(ConfirmationDecision.REJECTED, disconnected.decision)
        assertTrue(coordinator.state.value.dialogs.isEmpty())

        coordinator.onAgentConnected()
        val reusedExecution = runCatching {
            coordinator.requestConfirmation(
                command("execution-disconnected"),
                preview("execution-disconnected"),
                context(),
            )
        }.exceptionOrNull()
        assertTrue(reusedExecution is IllegalStateException)

        val reconnected = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(
                command("execution-reconnected"),
                preview("execution-reconnected"),
                context(),
            )
        }
        assertEquals("execution-reconnected", coordinator.state.value.activeDialog?.executionId)
        assertTrue(coordinator.accept("execution-reconnected"))
        assertEquals(ConfirmationDecision.ACCEPTED, reconnected.await().decision)
    }

    @Test
    fun `shutdown completes every waiter and later requests are canceled immediately`() = runTest {
        val coordinator = coordinator()
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(command("execution-confirm"), preview("execution-confirm"), context())
        }
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestApproval(command("execution-approve"), preview("execution-approve"), risk(), context())
        }

        coordinator.shutdown()

        assertEquals(ConfirmationDecision.REJECTED, confirmation.await().decision)
        assertEquals(ApprovalDecision.DENIED, approval.await().decision)
        assertEquals(
            ConfirmationDecision.REJECTED,
            coordinator.requestConfirmation(
                command("execution-after-shutdown"),
                preview("execution-after-shutdown"),
                context(),
            ).decision,
        )
        assertEquals(
            ApprovalDecision.DENIED,
            coordinator.requestApproval(
                command("execution-risk-after-shutdown"),
                preview("execution-risk-after-shutdown"),
                risk(),
                context(),
            ).decision,
        )
        assertTrue(coordinator.state.value.dialogs.isEmpty())
    }

    @Test
    fun `dialog state is redacted before Compose observes it`() = runTest {
        val coordinator = coordinator()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestApproval(
                command("execution-secret"),
                preview("execution-secret"),
                risk(),
                context(),
            )
        }

        val serialized = coordinator.state.value.toString()
        listOf("raw-secret", "raw-tenant-id", "raw-user-id").forEach {
            assertFalse(serialized.contains(it))
        }
        assertTrue(serialized.contains(AuditRedactor.REDACTED))

        coordinator.deny("execution-secret")
        pending.await()
    }

    @Test
    fun `recent execution tombstones stay bounded while protecting the newest duplicate decisions`() = runTest {
        val coordinator = coordinator(recentTombstoneLimit = 3)
        repeat(20) { index ->
            val executionId = "execution-bounded-$index"
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.requestConfirmation(command(executionId), preview(executionId), context())
            }
            assertTrue(coordinator.accept(executionId))
            assertEquals(ConfirmationDecision.ACCEPTED, pending.await().decision)
        }

        assertEquals(3, coordinator.retainedTombstoneCount)
        val recentDuplicate = runCatching {
            coordinator.requestConfirmation(
                command("execution-bounded-19"),
                preview("execution-bounded-19"),
                context(),
            )
        }.exceptionOrNull()
        assertTrue(recentDuplicate is IllegalStateException)

        val evictedOldExecution = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.requestConfirmation(
                command("execution-bounded-0"),
                preview("execution-bounded-0"),
                context(),
            )
        }
        assertEquals("execution-bounded-0", coordinator.state.value.activeDialog?.executionId)
        assertTrue(coordinator.reject("execution-bounded-0"))
        assertEquals(ConfirmationDecision.REJECTED, evictedOldExecution.await().decision)
        assertEquals(3, coordinator.retainedTombstoneCount)
    }

    private fun coordinator(
        timeoutMillis: Long = 10_000,
        clock: () -> Instant = { Instant.parse("2026-07-18T00:00:00Z") },
        recentTombstoneLimit: Int = 4_096,
    ): ComposeActionDecisionCoordinator =
        ComposeActionDecisionCoordinator(
            decisionTimeoutMillis = timeoutMillis,
            clock = clock,
            recentTombstoneLimit = recentTombstoneLimit,
            actionTitleResolver = { "通用动作 raw-secret" },
            redactor = AuditRedactor(setOf("secretField")),
            sensitiveValues = { setOf("raw-secret", "raw-tenant-id", "raw-user-id") },
            decisionIdFactory = { phase, executionId -> "${phase.name.lowercase()}-$executionId" },
        )

    private fun command(executionId: String): ActionCommand = ActionCommand(
        executionId = executionId,
        actionId = "demo.submit",
        actionVersion = 1,
        input = buildJsonObject {
            put("secretField", "raw-secret")
            put("ordinary", "visible")
        },
        origin = ActionOrigin.AGENT,
        identityScope = identity(),
        pageId = "demo.form",
        contextRevision = 7,
    )

    private fun preview(executionId: String): ActionPreview = ActionPreview(
        executionId = executionId,
        summary = "即将提交 raw-secret",
        redactedInput = buildJsonObject { put("secretField", "raw-secret") },
        changes = listOf(
            ActionPreviewChange(
                path = "secretField",
                before = JsonPrimitive("raw-secret"),
                after = JsonPrimitive("updated-secret"),
            ),
            ActionPreviewChange(
                path = "status",
                before = JsonPrimitive("草稿"),
                after = JsonPrimitive("已提交"),
            ),
        ),
        warnings = listOf("远端写入包含 raw-secret"),
    )

    private fun risk(): RiskEvaluation = RiskEvaluation.atLeast(
        baseRisk = ActionRiskLevel.HIGH_RISK,
        proposedRisk = ActionRiskLevel.HIGH_RISK,
        reasons = listOf("会产生远端副作用 raw-secret"),
    )

    private fun context(): ActionContext = ActionContext(
        identityScope = identity(),
        pageId = "demo.form",
        contextRevision = 7,
        permissions = setOf("demo.submit"),
    )

    private fun identity(): ActionIdentityScope = ActionIdentityScope(
        desktopInstanceId = "desktop-instance",
        desktopSessionId = "desktop-session",
        authSessionId = "auth-session",
        identityEpoch = 3,
        userId = "raw-user-id",
        tenantId = "raw-tenant-id",
        platformId = "platform-id",
    )
}
