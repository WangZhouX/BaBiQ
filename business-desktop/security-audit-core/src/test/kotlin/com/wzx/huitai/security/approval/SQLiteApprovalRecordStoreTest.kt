package com.wzx.huitai.security.approval

import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.port.ApprovalDecision
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteApprovalRecordStoreTest {
    @Test
    fun `pending create is exact replay and mismatched binding conflicts`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1", scope())
            val store = SQLiteApprovalRecordStore(database)
            val pending = pending()

            assertIs<ApprovalCreateResult.Created>(store.compareAndCreatePending(pending))
            assertIs<ApprovalCreateResult.Existing>(SQLiteApprovalRecordStore(database).compareAndCreatePending(pending))
            assertIs<ApprovalCreateResult.Conflict>(store.compareAndCreatePending(pending.copy(approvalId = "other")))
            assertIs<ApprovalCreateResult.Conflict>(store.compareAndCreatePending(pending.copy(expiresAt = pending.expiresAt.plusSeconds(1))))
            assertEquals(pending, store.find("execution-1", scope()))
        }
    }

    @Test
    fun `parent identity and all seven scope dimensions isolate find and decide`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1", scope())
            val store = SQLiteApprovalRecordStore(database)
            store.compareAndCreatePending(pending())
            val mismatches = listOf(
                scope().copy(desktopInstanceId = "other"), scope().copy(desktopSessionId = "other"),
                scope().copy(authSessionId = "other"), scope().copy(identityEpoch = 2),
                scope().copy(userId = "other"), scope().copy(tenantId = "other"), scope().copy(platformId = "other"),
            )
            mismatches.forEach { mismatch ->
                assertNull(store.find("execution-1", mismatch))
                assertIs<ApprovalDecideResult.Conflict>(store.decide(decideRequest(identityScope = mismatch)))
            }
            assertIs<ApprovalCreateResult.Conflict>(
                store.compareAndCreatePending(pending().copy(identityScope = scope().copy(desktopSessionId = "prior-session"))),
            )
        }
    }

    @Test
    fun `approval row identity drift fails closed for replay find and decide`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1", scope())
            val store = SQLiteApprovalRecordStore(database)
            val pending = pending()
            store.compareAndCreatePending(pending)
            database.write { connection ->
                connection.createStatement().use {
                    it.executeUpdate("UPDATE bd_action_approvals SET tenant_id='tampered-tenant' WHERE execution_id='execution-1'")
                }
            }

            assertNull(store.find("execution-1", scope()))
            assertIs<ApprovalCreateResult.Conflict>(store.compareAndCreatePending(pending))
            assertIs<ApprovalDecideResult.Conflict>(store.decide(decideRequest()))
        }
    }

    @Test
    fun `first decision wins across adapters and restart persists every decision`() = runTest {
        fixture().use { database ->
            val decisions = listOf(ApprovalDecision.APPROVED, ApprovalDecision.DENIED, ApprovalDecision.EXPIRED)
            decisions.forEachIndexed { index, decision ->
                val executionId = "execution-$index"
                insertParent(database, executionId, scope())
                SQLiteApprovalRecordStore(database).compareAndCreatePending(pending(executionId))
                val stores = listOf(SQLiteApprovalRecordStore(database), SQLiteApprovalRecordStore(database))
                val results = stores.mapIndexed { contender, store ->
                    async(Dispatchers.IO) {
                        store.decide(
                            decideRequest(
                                executionId,
                                decision,
                                decidedAt = if (decision == ApprovalDecision.EXPIRED) NOW.plusSeconds(60) else NOW.plusSeconds(10),
                                decidedBy = "reviewer-$contender",
                            ),
                        )
                    }
                }.awaitAll()
                assertEquals(1, results.count { it is ApprovalDecideResult.Decided })
                assertEquals(1, results.count { it is ApprovalDecideResult.ExistingDecision })
                assertEquals(decision, SQLiteApprovalRecordStore(database).find(executionId, scope())?.decision)
            }
        }
    }

    @Test
    fun `timestamps approver and reason are validated and unsafe values never persist raw`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1", scope())
            val store = SQLiteApprovalRecordStore(database)
            assertFailsWith<IllegalArgumentException> { pending().copy(expiresAt = NOW.minusSeconds(1)) }
            store.compareAndCreatePending(pending())
            assertIs<ApprovalDecideResult.Conflict>(
                store.decide(decideRequest(decidedAt = NOW.minusSeconds(1))),
            )
            assertIs<ApprovalDecideResult.Conflict>(
                store.decide(decideRequest(decidedAt = NOW.plusSeconds(61))),
            )
            assertIs<ApprovalDecideResult.Conflict>(
                store.decide(
                    decideRequest(
                        decision = ApprovalDecision.EXPIRED,
                        decidedAt = NOW.plusSeconds(59),
                        decidedBy = null,
                    ),
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                decideRequest(decision = ApprovalDecision.APPROVED, decidedBy = "")
            }
            val token = "approval-token-super-secret"
            val decided = assertIs<ApprovalDecideResult.Decided>(
                store.decide(decideRequest(decidedBy = "Bearer $token\nowner", reason = "password=$token")),
            ).record
            assertTrue(decided.decidedBy?.startsWith("actor-sha256:") == true)
            assertEquals("[REDACTED]", decided.reasonRedacted)
            val raw = database.read { connection -> connection.createStatement().use { it.executeQuery("SELECT decided_by,reason_redacted FROM bd_action_approvals").use { rows -> rows.next(); rows.getString(1) + rows.getString(2) } } }
            assertTrue(token !in raw)
            assertTrue(SQLiteApprovalRecordStore::class.java.methods.none { it.name in setOf("approveSession", "allowAlways", "approveAll") })
        }
    }

    @Test
    fun `expiry instant only accepts expired decision`() = runTest {
        fixture().use { database ->
            val store = SQLiteApprovalRecordStore(database)
            listOf(ApprovalDecision.APPROVED, ApprovalDecision.DENIED, ApprovalDecision.EXPIRED).forEachIndexed { index, decision ->
                val executionId = "execution-boundary-$index"
                insertParent(database, executionId, scope())
                val pending = pending(executionId)
                store.compareAndCreatePending(pending)
                val result = store.decide(
                    decideRequest(
                        executionId = executionId,
                        decision = decision,
                        decidedAt = pending.expiresAt,
                        decidedBy = if (decision == ApprovalDecision.APPROVED) "reviewer" else null,
                    ),
                )
                if (decision == ApprovalDecision.EXPIRED) assertIs<ApprovalDecideResult.Decided>(result)
                else assertIs<ApprovalDecideResult.Conflict>(result)
            }
        }
    }

    @Test
    fun `punctuation prefixed jwt like values never persist in approver or reason`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1", scope())
            val store = SQLiteApprovalRecordStore(database)
            store.compareAndCreatePending(pending())
            val jwt = "abcdefgh.abcdefgh.abcdefgh"

            val decided = assertIs<ApprovalDecideResult.Decided>(
                store.decide(decideRequest(decidedBy = "user:$jwt", reason = "ref=$jwt")),
            ).record

            assertTrue(decided.decidedBy?.startsWith("actor-sha256:") == true)
            assertEquals("[REDACTED]", decided.reasonRedacted)
            val raw = database.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT decided_by,reason_redacted FROM bd_action_approvals").use { rows ->
                        rows.next()
                        rows.getString(1) + rows.getString(2)
                    }
                }
            }
            assertTrue(jwt !in raw)
        }
    }

    private fun pending(executionId: String = "execution-1") = ApprovalRecord(
        "approval-$executionId", executionId, scope(), NOW, NOW.plusSeconds(60),
    )

    private fun decideRequest(
        executionId: String = "execution-1",
        decision: ApprovalDecision = ApprovalDecision.APPROVED,
        decidedAt: Instant = NOW.plusSeconds(10),
        decidedBy: String? = "reviewer-1",
        reason: String? = "safe reason",
        identityScope: ActionIdentityScope = scope(),
    ) = ApprovalDecisionRequest("approval-$executionId", executionId, identityScope, decision, decidedAt, decidedBy, reason)

    private fun scope() = ActionIdentityScope("desktop", "session", "auth", 1, "user", "tenant", "platform")
    private fun fixture() = BusinessDesktopDatabase(Files.createTempDirectory("sqlite-approval-store").resolve("business.db"))

    private fun insertParent(database: BusinessDesktopDatabase, executionId: String, scope: ActionIdentityScope) {
        database.write { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bd_action_executions (execution_id,action_id,action_version,input_fingerprint,origin,
                desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,
                page_id,context_revision,risk_level,replay_policy,reconciliation_policy,status,reconciliation_status,
                reconciliation_attempts,created_at,updated_at,record_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                val values = listOf(executionId,"demo",1,"fingerprint-$executionId","USER",scope.desktopInstanceId,scope.desktopSessionId,scope.authSessionId,scope.identityEpoch,scope.userId,scope.tenantId,scope.platformId,"page",1,"HIGH_RISK","NEVER","MANUAL","WAITING_APPROVAL","NOT_REQUIRED",0,NOW.toString(),NOW.toString(),1)
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z") }
}
