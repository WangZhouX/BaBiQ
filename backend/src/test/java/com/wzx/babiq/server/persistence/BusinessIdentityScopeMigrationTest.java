package com.wzx.babiq.server.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BusinessIdentityScopeMigrationTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "business-scope-migration-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final List<String> SCOPE_COLUMNS = List.of(
            "desktop_instance_id", "desktop_session_id", "auth_session_id", "identity_epoch",
            "user_id", "tenant_id", "platform_id");

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V23 为历史运行记录追加可空业务身份字段")
    void addsNullableIdentityScopeToLegacyTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            for (String table : List.of("bq_threads", "bq_turns", "bq_context_windows",
                    "bq_context_snapshots", "bq_tool_calls")) {
                for (String column : SCOPE_COLUMNS) {
                    assertThat(columnExists(connection, table, column)).as(table + "." + column).isTrue();
                    assertThat(columnNotNull(connection, table, column)).as(table + "." + column).isFalse();
                }
            }
            assertThat(columnExists(connection, "bq_tool_calls", "execution_id")).isTrue();
            assertThat(columnNotNull(connection, "bq_tool_calls", "execution_id")).isFalse();
        }
    }

    @Test
    @DisplayName("V23 创建动作当前态和不可变事件审计结构")
    void createsApplicationActionCurrentAndAppendOnlyEventTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(columns(connection, "bq_application_actions")).containsExactly(
                    "execution_id", "action_id", "action_version", "request_fingerprint",
                    "thread_id", "turn_id", "tool_call_id", "desktop_instance_id",
                    "desktop_session_id", "auth_session_id", "identity_epoch", "user_id",
                    "tenant_id", "platform_id", "status", "result_summary_redacted", "error_code",
                    "error_message_redacted", "created_at", "updated_at", "terminal_at");
            assertThat(columns(connection, "bq_application_action_events")).containsExactly(
                    "event_id", "execution_id", "event_sequence", "event_type", "from_status",
                    "to_status", "payload_summary_redacted", "late_result", "occurred_at");

            String actionsSql = schemaSql(statement, "table", "bq_application_actions");
            String eventsSql = schemaSql(statement, "table", "bq_application_action_events");
            assertThat(actionsSql).containsIgnoringCase("PRIMARY KEY")
                    .containsIgnoringCase("CHECK")
                    .containsIgnoringCase("FOREIGN KEY");
            assertThat(eventsSql).containsIgnoringCase("PRIMARY KEY")
                    .containsIgnoringCase("FOREIGN KEY")
                    .containsIgnoringCase("CHECK")
                    .containsIgnoringCase("UNIQUE (execution_id, event_sequence)");

            assertThat(indexes(statement, "bq_application_actions"))
                    .contains("ux_bq_application_actions_tool_call_id", "idx_bq_application_actions_scope_status");
            assertThat(indexes(statement, "bq_application_action_events"))
                    .contains("idx_bq_application_action_events_execution_sequence");
            assertThat(schemaSql(statement, "trigger", "trg_bq_application_action_events_no_update"))
                    .containsIgnoringCase("RAISE(ABORT");
            assertThat(schemaSql(statement, "trigger", "trg_bq_application_action_events_no_delete"))
                    .containsIgnoringCase("RAISE(ABORT");
        }
    }

    @Test
    @DisplayName("动作事件拒绝更新删除且 toolCall 只关联一个 execution")
    void enforcesAppendOnlyEventsAndUniqueToolCallCorrelation() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bq_threads(thread_id,title,cwd,provider_id,model,sandbox_mode,approval_policy,status,created_at,updated_at)
                    VALUES('thread-migration','t','C:/tmp','p','m','workspace_write','on_request','active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bq_turns(turn_id,thread_id,status,input_text,cwd,provider_id,model,sandbox_mode,approval_policy,started_at)
                    VALUES('turn-migration','thread-migration','RUNNING','x','C:/tmp','p','m','workspace_write','on_request',CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bq_tool_calls(tool_call_id,thread_id,turn_id,tool_name,args_json,status,started_at)
                    VALUES('tool-migration','thread-migration','turn-migration','application_action','{}','running',CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate(actionInsert("execution-1", "tool-migration"));
            assertThatThrownBySql(() -> statement.executeUpdate(actionInsert("execution-2", "tool-migration")))
                    .hasMessageContaining("UNIQUE constraint failed");
            statement.executeUpdate("""
                    INSERT INTO bq_application_action_events(event_id,execution_id,event_sequence,event_type,to_status,late_result,occurred_at)
                    VALUES('event-1','execution-1',1,'registered','REQUESTED',0,CURRENT_TIMESTAMP)
                    """);
            assertThatThrownBySql(() -> statement.executeUpdate(
                    "UPDATE bq_application_action_events SET event_type='changed' WHERE event_id='event-1'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBySql(() -> statement.executeUpdate(
                    "DELETE FROM bq_application_action_events WHERE event_id='event-1'"))
                    .hasMessageContaining("append-only");
        }
    }

    private static String actionInsert(String executionId, String toolCallId) {
        return """
                INSERT INTO bq_application_actions(
                    execution_id,action_id,action_version,request_fingerprint,thread_id,turn_id,tool_call_id,
                    desktop_instance_id,desktop_session_id,auth_session_id,identity_epoch,user_id,tenant_id,platform_id,
                    status,created_at,updated_at)
                VALUES('%s','case.update',1,'fingerprint','thread-migration','turn-migration','%s',
                    'desktop','session','auth',1,'user','tenant','platform','REQUESTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """.formatted(executionId, toolCallId);
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        return columns(connection, table).contains(column);
    }

    private static boolean columnNotNull(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return result.getInt("notnull") == 1;
                }
            }
        }
        throw new IllegalArgumentException("Unknown column " + table + "." + column);
    }

    private static List<String> columns(Connection connection, String table) throws Exception {
        var columns = new java.util.ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private static List<String> indexes(Statement statement, String table) throws Exception {
        var names = new java.util.ArrayList<String>();
        try (ResultSet result = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (result.next()) {
                names.add(result.getString("name"));
            }
        }
        return names;
    }

    private static String schemaSql(Statement statement, String type, String name) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='" + type + "' AND name='" + name + "'")) {
            assertThat(result.next()).as(type + " " + name + " exists").isTrue();
            return result.getString(1);
        }
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownBySql(
            SqlOperation operation) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(operation::run);
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws Exception;
    }
}
