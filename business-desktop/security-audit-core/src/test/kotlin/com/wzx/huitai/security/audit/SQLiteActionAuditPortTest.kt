package com.wzx.huitai.security.audit

import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionAuditEvent
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteActionAuditPortTest {
    @Test
    fun `caller sequence is continuous and restart reads immutable ordered events`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1")
            val port = SQLiteActionAuditPort(database)
            port.append(event(1, "accepted"))
            port.append(event(2, "previewed", ActionExecutionState.EXECUTING, ActionExecutionState.PREVIEWED))

            assertFailsWith<IllegalArgumentException> { port.append(event(4, "running")) }
            assertEquals(listOf(1L, 2L), SQLiteActionAuditPort(database).events("execution-1").map { it.sequence })
            val immutable = port.events("execution-1")
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (immutable as MutableList<ActionAuditEvent>).clear()
            }
        }
    }

    @Test
    fun `concurrent drafts from two adapters allocate every sequence exactly once`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1")
            val first = SQLiteActionAuditPort(database)
            val second = SQLiteActionAuditPort(database)

            val appended = (1..32).map { index ->
                async(Dispatchers.IO) {
                    (if (index % 2 == 0) first else second).append(draft("execution-1", "running", NOW.plusMillis(index.toLong())))
                }
            }.awaitAll()

            assertEquals((1L..32L).toList(), appended.map { it.sequence }.sorted())
            assertEquals((1L..32L).toList(), first.events("execution-1").map { it.sequence })
        }
    }

    @Test
    fun `event vocabulary persists and global order is deterministic`() = runTest {
        fixture().use { database ->
            val types = listOf(
                "accepted", "previewed", "rejected", "approval_requested", "approval_approved",
                "approval_denied", "approval_expired", "running", "completed", "failed", "timeout",
                "cancel_race", "late_terminal_response", "outcome_unknown", "reconciliation_attempt",
                "reconciliation_result",
            )
            types.forEachIndexed { index, type ->
                val executionId = "execution-${index % 3}"
                insertParent(database, executionId)
            }
            val port = SQLiteActionAuditPort(database)
            val sequences = mutableMapOf<String, Long>()
            types.reversed().forEachIndexed { index, type ->
                val executionId = "execution-${index % 3}"
                val sequence = sequences.getOrDefault(executionId, 0L) + 1
                sequences[executionId] = sequence
                port.append(event(sequence, type, executionId = executionId, at = NOW.plusSeconds((index % 4).toLong())))
            }

            val global = port.events()
            assertEquals(global.sortedWith(compareBy<ActionAuditEvent> { it.occurredAt }.thenBy { it.executionId }.thenBy { it.sequence }), global)
            assertEquals(types.toSet(), global.map { it.type }.toSet())
        }
    }

    @Test
    fun `global order compares parsed instants instead of variable fraction text`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-whole")
            insertParent(database, "execution-fractional")
            val port = SQLiteActionAuditPort(database)
            val earlier = Instant.parse("2026-07-14T00:00:00Z")
            val later = Instant.parse("2026-07-14T00:00:00.500Z")

            port.append(event(1, "later", executionId = "execution-fractional", at = later))
            port.append(event(1, "earlier", executionId = "execution-whole", at = earlier))

            assertEquals(listOf("earlier", "later"), port.events().map { it.type })
        }
    }

    @Test
    fun `payload and unsafe actor are redacted and append only triggers reject mutation`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1")
            val secret = "audit-token-super-secret"
            val owner = "claim-owner-super-secret"
            val port = SQLiteActionAuditPort(database)
            val stored = port.append(
                draft("execution-1", "reconciliation_attempt", NOW).copy(
                    actorId = "Bearer $secret\nactor",
                    redactedPayload = buildJsonObject {
                        put("accessToken", secret)
                        put("claimToken", secret)
                        put("ownerId", owner)
                        put("fileContent", "full-file-$secret")
                        put("reasoning", "model-reasoning-$secret")
                    },
                ),
            )

            assertFalse(secret in stored.redactedPayload.toString())
            assertFalse(owner in stored.redactedPayload.toString())
            assertTrue(stored.actorId?.startsWith("actor-sha256:") == true)
            val raw = rawEvents(database)
            listOf(secret, owner, "full-file-$secret", "model-reasoning-$secret").forEach { assertFalse(it in raw) }
            database.read { connection ->
                assertFailsWith<SQLException> { connection.createStatement().use { it.executeUpdate("UPDATE bd_action_events SET event_type='changed'") } }
                assertFailsWith<SQLException> { connection.createStatement().use { it.executeUpdate("DELETE FROM bd_action_events") } }
            }
        }
    }

    @Test
    fun `punctuation prefixed jwt like values never persist in actor or payload`() = runTest {
        fixture().use { database ->
            insertParent(database, "execution-1")
            val jwt = "abcdefgh.abcdefgh.abcdefgh"
            val stored = SQLiteActionAuditPort(database).append(
                draft("execution-1", "running", NOW).copy(
                    actorId = "user:$jwt",
                    redactedPayload = buildJsonObject { put("note", "ref=$jwt") },
                ),
            )

            assertTrue(stored.actorId?.startsWith("actor-sha256:") == true)
            assertFalse(jwt in stored.redactedPayload.toString())
            assertFalse(jwt in rawEvents(database))
        }
    }

    private fun event(
        sequence: Long,
        type: String,
        from: ActionExecutionState? = null,
        to: ActionExecutionState = ActionExecutionState.EXECUTING,
        executionId: String = "execution-1",
        at: Instant = NOW.plusSeconds(sequence),
    ) = ActionAuditEvent(executionId, sequence, from, to, type, buildJsonObject { put("safe", true) }, "reviewer-1", at)

    private fun draft(executionId: String, type: String, at: Instant) =
        ActionAuditDraft(executionId, ActionExecutionState.EXECUTING, ActionExecutionState.EXECUTING, type, buildJsonObject { }, "reviewer-1", at)

    private fun fixture() = BusinessDesktopDatabase(Files.createTempDirectory("sqlite-audit-port").resolve("business.db"))

    private fun insertParent(database: BusinessDesktopDatabase, executionId: String) {
        database.write { connection ->
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO bd_action_executions (
                    execution_id,action_id,action_version,input_fingerprint,origin,desktop_instance_id,
                    desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,page_id,
                    context_revision,risk_level,replay_policy,reconciliation_policy,status,reconciliation_status,
                    reconciliation_attempts,created_at,updated_at,record_version
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                val values = listOf(executionId,"demo",1,"fingerprint-$executionId","USER","desktop","session","auth",1,"user","tenant","platform","page",1,"READ_ONLY","SAFE","NONE","EXECUTING","NOT_REQUIRED",0,NOW.toString(),NOW.toString(),1)
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun rawEvents(database: BusinessDesktopDatabase): String = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT payload_json_redacted,actor_id FROM bd_action_events").use { rows ->
                buildString { while (rows.next()) append(rows.getString(1)).append('|').append(rows.getString(2)) }
            }
        }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z") }
}
