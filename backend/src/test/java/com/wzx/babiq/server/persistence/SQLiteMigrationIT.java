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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQLite migration 集成测试。
 *
 * <p>测试使用独立的 target/test-db 数据库文件，避免污染真实用户目录。
 * 它验证 Flyway 建表、SQLite PRAGMA 和 P2 初始表结构是否一起生效。</p>
 */
@SpringBootTest
class SQLiteMigrationIT {

    /** 每次测试类运行都使用独立数据库文件，避免本地旧 migration 结果影响断言。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "sqlite-migration-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** SQLite JDBC URL 使用独立文件，Spring Boot 会用它创建测试 DataSource。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Flyway 自动创建 P2 初始表和 schema 注释表")
    void migration_should_create_p2_tables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            Set<String> tableNames = tableNames(statement);

            assertThat(tableNames).contains(
                    "bq_threads",
                    "bq_turns",
                    "bq_items",
                    "bq_turn_summaries",
                    "bq_approvals",
                    "bq_provider_configs",
                    "bq_app_settings",
                    "bq_business_oa_sessions",
                    "bq_business_auth_events",
                    "bq_business_oa_secret_cleanup",
                    "bq_schema_comments",
                    "flyway_schema_history");
        }
    }

    @Test
    @DisplayName("OA 密钥清理 tombstone 具备状态约束、完整审计字段且不级联 OA 会话")
    void business_oa_secret_cleanup_should_be_durable_without_session_cascade() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(flywayVersions(statement)).contains("28");
            assertThat(columns(statement, "bq_business_oa_secret_cleanup")).containsExactlyInAnyOrder(
                    "secret_ref",
                    "auth_session_id",
                    "state",
                    "reason_code",
                    "operation_id",
                    "attempt_count",
                    "created_at",
                    "updated_at",
                    "last_attempt_at",
                    "last_result_code");
            assertThat(commentFor(statement, "bq_business_oa_secret_cleanup", "secret_ref"))
                    .contains("SecretStore").contains("引用");
            assertThat(tableSql(statement, "bq_business_oa_secret_cleanup"))
                    .doesNotContainIgnoringCase("ON DELETE CASCADE")
                    .doesNotContain("REFERENCES bq_business_oa_sessions");

            statement.executeUpdate("""
                    INSERT INTO bq_business_oa_secret_cleanup(
                        secret_ref, auth_session_id, state, reason_code, attempt_count, created_at, updated_at)
                    VALUES ('keystore://business.oa.test-reserved', 'auth-test', 'RESERVED', 'TEST', 0,
                            '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z')
                    """);
            assertThatThrownBySql(() -> statement.executeUpdate("""
                    INSERT INTO bq_business_oa_secret_cleanup(
                        secret_ref, auth_session_id, state, reason_code, attempt_count, created_at, updated_at)
                    VALUES (NULL, 'auth-test-null-ref', 'RESERVED', 'TEST', 0,
                            '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z')
                    """));
            assertThatThrownBySql(() -> statement.executeUpdate("""
                    INSERT INTO bq_business_oa_secret_cleanup(
                        secret_ref, auth_session_id, state, reason_code, attempt_count, created_at, updated_at)
                    VALUES ('keystore://business.oa.test-invalid', 'auth-test', 'UNKNOWN', 'TEST', 0,
                            '2026-07-28T00:00:00Z', '2026-07-28T00:00:00Z')
                    """));
        }
    }

    @Test
    @DisplayName("SQLite 连接启用外键、WAL 和 busy timeout")
    void datasource_should_apply_sqlite_pragmas() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(singleValue(statement, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(singleValue(statement, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(singleValue(statement, "PRAGMA busy_timeout")).isEqualTo("5000");
        }
    }

    @Test
    @DisplayName("turnSummary 表只保存 token、耗时和工具统计，不再保存价格字段")
    void turn_summary_schema_should_store_tokens_without_cost_column() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            Set<String> columns = columns(statement, "bq_turn_summaries");

            assertThat(columns).contains("prompt_tokens", "completion_tokens", "total_tokens");
            assertThat(columns).doesNotContain("cost_usd");
            assertThat(commentFor(statement, "bq_turn_summaries", "total_tokens"))
                    .contains("总 token");
            assertThat(commentExists(statement, "bq_turn_summaries", "cost_usd")).isFalse();
        }
    }

    @Test
    @DisplayName("P6-4 状态说明通过后续迁移刷新，避免修改已发布 V16")
    void work_unit_status_comment_should_be_refreshed_by_followup_migration() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(flywayVersions(statement)).contains("16", "17");
            assertThat(commentFor(statement, "bq_work_units", "status"))
                    .contains("waiting_config")
                    .contains("待启动");
        }
    }

    private static Set<String> tableNames(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table'")) {
            return resultSetValues(rs).stream().collect(Collectors.toSet());
        }
    }

    private static String singleValue(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static Set<String> resultSetValues(ResultSet rs) throws Exception {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        while (rs.next()) {
            values.add(rs.getString(1));
        }
        return values;
    }

    private static Set<String> columns(Statement statement, String tableName) throws Exception {
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
            while (rs.next()) {
                values.add(rs.getString("name"));
            }
            return values;
        }
    }

    private static Set<String> flywayVersions(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success = 1")) {
            return resultSetValues(rs).stream().collect(Collectors.toSet());
        }
    }

    private static String commentFor(Statement statement, String tableName, String columnName) throws Exception {
        String sql = """
                SELECT comment
                FROM bq_schema_comments
                WHERE table_name = '%s' AND column_name = '%s'
                """.formatted(tableName, columnName);
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean commentExists(Statement statement, String tableName, String columnName) throws Exception {
        String sql = """
                SELECT 1
                FROM bq_schema_comments
                WHERE table_name = '%s' AND column_name = '%s'
                """.formatted(tableName, columnName);
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        }
    }

    @Test
    void business_schedule_create_operations_are_durable_and_recoverable() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(tableNames(statement)).contains("bq_business_schedule_operations");
            assertThat(columns(statement, "bq_business_schedule_operations")).contains(
                    "operation_id", "desktop_instance_id", "desktop_session_id", "auth_session_id",
                    "tenant_id", "identity_generation", "client_operation_id", "actor_user_id",
                    "form_revision", "attachment_batch_id", "request_fingerprint", "state",
                    "result_revision", "created_at", "updated_at");
            assertThat(tableSql(statement, "bq_business_schedule_operations"))
                    .contains("IN_FLIGHT", "COMPLETED", "OUTCOME_UNKNOWN", "FAILED");
            assertThat(commentFor(statement, "bq_business_schedule_operations", "request_fingerprint"))
                    .contains("指纹");
        }
    }

    private static String tableSql(Statement statement, String tableName) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static void assertThatThrownBySql(SqlAction action) {
        assertThatThrownBy(action::run).isInstanceOf(SQLException.class);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
