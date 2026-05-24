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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
                    "bq_schema_comments",
                    "flyway_schema_history");
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
}
