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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库中文注释覆盖测试。
 *
 * <p>SQLite 不支持原生字段 COMMENT，所以 BaBiQ 用 `bq_schema_comments`
 * 保存可查询的中文说明。本测试扫描所有 `bq_*` 表，确保每个字段都没有漏注释。</p>
 */
@SpringBootTest
class SchemaCommentsCoverageTest {

    /** 每次运行使用独立 SQLite 文件，让字段注释覆盖率只取决于当前 migration。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "schema-comments-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 覆盖默认数据库路径，避免测试写入真实用户数据目录。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("所有 bq_* 表和字段都在 bq_schema_comments 中有中文说明")
    void every_babiq_table_and_column_should_have_chinese_comment() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            List<String> missing = new ArrayList<>();
            for (String tableName : babiqTables(statement)) {
                assertChineseComment(statement, tableName, "__table__", missing);
                for (String columnName : columns(statement, tableName)) {
                    assertChineseComment(statement, tableName, columnName, missing);
                }
            }

            assertThat(missing)
                    .as("以下表或字段缺少 bq_schema_comments 中文说明")
                    .isEmpty();
        }
    }

    private static List<String> babiqTables(Statement statement) throws Exception {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'bq_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static List<String> columns(Statement statement, String tableName) throws Exception {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private static void assertChineseComment(
            Statement statement,
            String tableName,
            String columnName,
            List<String> missing) throws Exception {
        String sql = "SELECT comment FROM bq_schema_comments WHERE table_name = '%s' AND column_name = '%s'"
                .formatted(tableName, columnName);
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                missing.add(tableName + "." + columnName + " 缺少说明");
                return;
            }
            String comment = rs.getString(1);
            if (comment == null || comment.isBlank() || !comment.matches(".*[\\u4e00-\\u9fff].*")) {
                missing.add(tableName + "." + columnName + " 说明不是有效中文: " + comment);
            }
        }
    }
}
