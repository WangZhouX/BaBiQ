package com.wzx.huitai.security.database

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class BusinessDesktopMigrationTest {
    @Test
    fun `connection initialization failure closes connection and suppresses close failure`() {
        var closeCalled = false
        val closeFailure = IllegalStateException("close failed")
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "close" -> {
                    closeCalled = true
                    throw closeFailure
                }
                "isClosed" -> closeCalled
                "toString" -> "RecordingConnection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection
        val initializationFailure = IllegalStateException("pragma failed")
        val path = Files.createTempDirectory("business-connection-failure").resolve("business.db")

        val failure = assertFailsWith<IllegalStateException> {
            BusinessDesktopDatabase(
                path = path,
                connectionFactory = { connection },
                connectionInitializer = { throw initializationFailure },
            )
        }

        assertTrue(closeCalled)
        assertTrue(failure === initializationFailure)
        assertEquals(listOf(closeFailure), failure.suppressed.toList())
    }

    @Test
    fun `database leaf replacement between validation and jdbc open fails closed`() {
        val path = Files.createTempDirectory("business-database-leaf-swap").resolve("business.db")
        var closeCalled = false
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "close" -> { closeCalled = true; Unit }
                "isClosed" -> closeCalled
                "toString" -> "ReplacementRaceConnection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection

        assertFailsWith<IllegalArgumentException> {
            BusinessDesktopDatabase(
                path = path,
                connectionFactory = {
                    Files.delete(path)
                    Files.createDirectory(path)
                    connection
                },
            )
        }

        assertTrue(closeCalled)
    }

    @Test
    fun `bootstrap creates exact action audit schema constraints indexes and pragmas`() {
        val path = Files.createTempDirectory("business-desktop-db").resolve("nested/business.db")
        BusinessDesktopDatabase(path).use { database ->
            database.read { connection ->
                assertEquals(setOf("bd_action_executions", "bd_action_events", "bd_action_approvals", "bd_schema_comments"), businessTables(connection))
                assertEquals(EXECUTION_COLUMNS, columns(connection, "bd_action_executions"))
                assertEquals(EVENT_COLUMNS, columns(connection, "bd_action_events"))
                assertEquals(APPROVAL_COLUMNS, columns(connection, "bd_action_approvals"))
                assertEquals(COMMENT_COLUMNS, columns(connection, "bd_schema_comments"))
                assertEquals("wal", pragmaText(connection, "journal_mode").lowercase())
                assertEquals(1, pragmaInt(connection, "foreign_keys"))
                assertEquals(5000, pragmaInt(connection, "busy_timeout"))
                assertEquals("execution_id", primaryKey(connection, "bd_action_executions"))
                assertEquals("event_id", primaryKey(connection, "bd_action_events"))
                assertEquals("approval_id", primaryKey(connection, "bd_action_approvals"))
                assertEquals("bd_action_executions", foreignTable(connection, "bd_action_events"))
                assertEquals("bd_action_executions", foreignTable(connection, "bd_action_approvals"))

                val executionSql = schemaSql(connection, "table", "bd_action_executions")
                listOf("USER", "AGENT", "READ_ONLY", "REVERSIBLE_WRITE", "HIGH_RISK", "SAFE", "IDEMPOTENCY_KEY_REQUIRED", "NEVER", "QUERY_REMOTE", "OUTCOME_UNKNOWN").forEach {
                    assertTrue(it in executionSql)
                }
                val approvalSql = schemaSql(connection, "table", "bd_action_approvals")
                listOf("APPROVED", "DENIED", "EXPIRED").forEach { assertTrue(it in approvalSql) }

                val indexes = indexes(connection)
                assertTrue("bd_action_executions_tool_call_id_unique" !in indexes)
                val turnScopedToolIndex = indexes["bd_action_executions_turn_tool_call_id_unique"].orEmpty()
                assertTrue(turnScopedToolIndex.contains("turn_id, tool_call_id", ignoreCase = true))
                assertTrue(turnScopedToolIndex.contains("turn_id IS NOT NULL", ignoreCase = true))
                assertTrue(turnScopedToolIndex.contains("tool_call_id IS NOT NULL", ignoreCase = true))
                assertTrue("bd_action_executions_identity_scope_status_idx" in indexes)
                assertTrue("bd_action_executions_correlation_idx" in indexes)
                assertTrue("bd_action_approvals_identity_scope_idx" in indexes)

                connection.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO bd_action_executions (execution_id,action_id,action_version,input_fingerprint,origin,desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,page_id,context_revision,risk_level,replay_policy,reconciliation_policy,status,reconciliation_status,reconciliation_attempts,created_at,updated_at,record_version) VALUES ('e','a',1,'f','USER','di','ds','as',1,'u','t','p','page',1,'READ_ONLY','SAFE','NONE','RECEIVED','NOT_REQUIRED',0,'now','now',0)")
                    statement.executeUpdate("INSERT INTO bd_action_events (event_id,execution_id,event_sequence,to_status,event_type,payload_json_redacted,occurred_at) VALUES ('ev','e',1,'RECEIVED','received','{}','now')")
                    assertFailsWith<java.sql.SQLException> { statement.executeUpdate("UPDATE bd_action_events SET event_type='changed' WHERE event_id='ev'") }
                    assertFailsWith<java.sql.SQLException> { statement.executeUpdate("DELETE FROM bd_action_events WHERE event_id='ev'") }
                }
                insertExecution(connection, "turn-a-execution", "turn-a", "application_action_0")
                insertExecution(connection, "turn-b-execution", "turn-b", "application_action_0")
                assertFailsWith<java.sql.SQLException> {
                    insertExecution(connection, "turn-b-duplicate", "turn-b", "application_action_0")
                }
                assertTrue("RAISE(ABORT" in schemaSql(connection, "trigger", "bd_action_events_reject_update").uppercase())
                assertTrue("RAISE(ABORT" in schemaSql(connection, "trigger", "bd_action_events_reject_delete").uppercase())
            }
        }
        BusinessDesktopDatabase(path).use { it.read { connection -> assertEquals(EXECUTION_COLUMNS, columns(connection, "bd_action_executions")) } }
    }

    @Test
    fun `V1 audit history upgrades to turn scoped identity without data loss`() {
        val path = Files.createTempDirectory("business-desktop-v1-upgrade").resolve("business.db")
        Flyway.configure()
            .dataSource("jdbc:sqlite:${path.toAbsolutePath().normalize()}", null, null)
            .locations("classpath:db/migration")
            .target("1")
            .load()
            .migrate()

        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}").use { connection ->
            insertExecution(connection, "legacy-execution", "legacy-turn", "application_action_0")
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO bd_action_events (
                        event_id,execution_id,event_sequence,to_status,event_type,
                        payload_json_redacted,occurred_at
                    ) VALUES ('legacy-event','legacy-execution',1,'RECEIVED','received','{}','now')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO bd_action_approvals (
                        approval_id,execution_id,desktop_instance_id,auth_session_id,identity_epoch,
                        user_id,tenant_id,platform_id,requested_at,expires_at
                    ) VALUES (
                        'legacy-approval','legacy-execution','desktop','auth',1,
                        'user','tenant','platform','now','later'
                    )
                    """.trimIndent(),
                )
            }
            assertEquals(1, rowCount(connection, "bd_action_executions"))
            assertEquals(1, rowCount(connection, "bd_action_events"))
            assertEquals(1, rowCount(connection, "bd_action_approvals"))
        }

        BusinessDesktopDatabase(path).use { database ->
            database.read { connection ->
                assertEquals("2", installedVersion(connection))
                assertEquals(1, rowCount(connection, "bd_action_executions"))
                assertEquals(1, rowCount(connection, "bd_action_events"))
                assertEquals(1, rowCount(connection, "bd_action_approvals"))
                insertExecution(connection, "current-execution", "current-turn", "application_action_0")
                assertFailsWith<java.sql.SQLException> {
                    insertExecution(connection, "current-duplicate", "current-turn", "application_action_0")
                }
            }
        }
    }

    private fun businessTables(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'bd_%'").use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
    }
    private fun columns(connection: Connection, table: String) = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { result -> buildList { while (result.next()) add(result.getString("name")) } }
    }
    private fun primaryKey(connection: Connection, table: String) = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { result -> generateSequence { if (result.next()) result else null }.first { it.getInt("pk") == 1 }.getString("name") }
    }
    private fun foreignTable(connection: Connection, table: String) = connection.createStatement().use { statement -> statement.executeQuery("PRAGMA foreign_key_list($table)").use { it.next(); it.getString("table") } }
    private fun pragmaText(connection: Connection, name: String) = connection.createStatement().use { it.executeQuery("PRAGMA $name").use { result -> result.next(); result.getString(1) } }
    private fun pragmaInt(connection: Connection, name: String) = pragmaText(connection, name).toInt()
    private fun schemaSql(connection: Connection, type: String, name: String) = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type=? AND name=?").use { statement -> statement.setString(1, type); statement.setString(2, name); statement.executeQuery().use { it.next(); it.getString(1) } }
    private fun indexes(connection: Connection) = connection.createStatement().use { statement -> statement.executeQuery("SELECT name,sql FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'").use { result -> buildMap { while (result.next()) put(result.getString(1), result.getString(2) ?: "") } } }
    private fun rowCount(connection: Connection, table: String) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { result -> result.next(); result.getInt(1) }
    }
    private fun installedVersion(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
        ).use { result -> result.next(); result.getString(1) }
    }
    private fun insertExecution(
        connection: Connection,
        executionId: String,
        turnId: String,
        toolCallId: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO bd_action_executions (
                execution_id,action_id,action_version,input_fingerprint,origin,
                desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,
                user_id,tenant_id,platform_id,page_id,context_revision,
                thread_id,turn_id,tool_call_id,risk_level,replay_policy,reconciliation_policy,
                status,reconciliation_status,reconciliation_attempts,created_at,updated_at,record_version
            ) VALUES (?,?,1,'fingerprint','AGENT','desktop','session','auth',1,'user','tenant','platform',
                'page',1,'thread',?,?,'READ_ONLY','SAFE','NONE','RECEIVED','NOT_REQUIRED',0,'now','now',0)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, executionId)
            statement.setString(2, "action")
            statement.setString(3, turnId)
            statement.setString(4, toolCallId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    companion object {
        val EXECUTION_COLUMNS = listOf("execution_id","action_id","action_version","input_fingerprint","origin","desktop_instance_id","desktop_session_id","auth_session_id","identity_epoch","user_id","tenant_id","platform_id","page_id","context_revision","thread_id","turn_id","tool_call_id","risk_level","replay_policy","reconciliation_policy","status","remote_reference","result_json_redacted","error_code","error_message_redacted","reconciliation_status","reconciliation_attempts","last_reconciled_at","created_at","started_at","completed_at","updated_at","record_version")
        val EVENT_COLUMNS = listOf("event_id","execution_id","event_sequence","from_status","to_status","event_type","payload_json_redacted","actor_id","occurred_at")
        val APPROVAL_COLUMNS = listOf("approval_id","execution_id","desktop_instance_id","auth_session_id","identity_epoch","user_id","tenant_id","platform_id","requested_at","expires_at","decided_at","decision","decided_by","reason_redacted")
        val COMMENT_COLUMNS = listOf("object_type","object_name","column_name","comment_text")
    }
}
