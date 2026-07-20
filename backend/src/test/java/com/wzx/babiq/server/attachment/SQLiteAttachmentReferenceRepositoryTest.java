package com.wzx.babiq.server.attachment;

import com.wzx.babiq.server.persistence.mapper.ItemMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SQLiteAttachmentReferenceRepositoryTest {

    private static final Path TEST_DB = Path.of(
            "target", "test-db", "attachment-reference-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", TEST_DB::toString);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ItemMapper itemMapper;

    @Test
    void queryReturnsScopedAndUnscopedUserMessagesWithJoinedArchiveTimestamp() {
        String archivedAt = Instant.parse("2026-06-01T00:00:00Z").toString();
        insertThread("thread-unscoped", null);
        insertThread("thread-scoped", archivedAt);
        insertTurn("turn-unscoped", "thread-unscoped", false);
        insertTurn("turn-scoped", "thread-scoped", true);
        insertItem("user-unscoped", "thread-unscoped", "turn-unscoped",
                "userMessage", "completed", "{\"marker\":\"unscoped\"}");
        insertItem("user-scoped", "thread-scoped", "turn-scoped",
                "userMessage", "completed", "{\"marker\":\"scoped\"}");
        insertItem("assistant", "thread-unscoped", "turn-unscoped",
                "agentMessage", "completed", "{\"marker\":\"non-user\"}");
        insertItem("removed", "thread-unscoped", "turn-unscoped",
                "userMessage", "removed", "{\"marker\":\"removed\"}");

        List<AttachmentReferenceRecord> records =
                new SQLiteAttachmentReferenceRepository(itemMapper).findAll();

        assertThat(records)
                .extracting(AttachmentReferenceRecord::payloadJson)
                .containsExactlyInAnyOrder(
                        "{\"marker\":\"unscoped\"}",
                        "{\"marker\":\"scoped\"}");
        assertThat(records)
                .filteredOn(record -> record.payloadJson().contains("scoped")
                        && !record.payloadJson().contains("unscoped"))
                .singleElement()
                .extracting(AttachmentReferenceRecord::archivedAt)
                .isEqualTo(archivedAt);
        assertThat(records)
                .filteredOn(record -> record.payloadJson().contains("unscoped"))
                .singleElement()
                .extracting(AttachmentReferenceRecord::archivedAt)
                .isNull();
    }

    private void insertThread(String threadId, String archivedAt) {
        jdbcTemplate.update("""
                        INSERT INTO bq_threads (
                            thread_id, title, cwd, provider_id, model, sandbox_mode,
                            approval_policy, status, created_at, updated_at, archived_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                threadId, threadId, "E:\\BaBiQ", "provider", "model",
                "DANGER_FULL_ACCESS", "ON_REQUEST",
                archivedAt == null ? "active" : "archived",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", archivedAt);
    }

    private void insertTurn(String turnId, String threadId, boolean scoped) {
        jdbcTemplate.update("""
                        INSERT INTO bq_turns (
                            turn_id, thread_id, status, input_text, cwd, provider_id, model,
                            sandbox_mode, approval_policy, started_at,
                            desktop_instance_id, desktop_session_id, auth_session_id,
                            identity_epoch, user_id, tenant_id, platform_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                turnId, threadId, "COMPLETED", "input", "E:\\BaBiQ", "provider", "model",
                "DANGER_FULL_ACCESS", "ON_REQUEST", "2026-01-01T00:00:00Z",
                scoped ? "desktop" : null,
                scoped ? "session" : null,
                scoped ? "auth" : null,
                scoped ? 1L : null,
                scoped ? "user" : null,
                scoped ? "tenant" : null,
                scoped ? "platform" : null);
    }

    private void insertItem(
            String itemId,
            String threadId,
            String turnId,
            String type,
            String status,
            String payload
    ) {
        jdbcTemplate.update("""
                        INSERT INTO bq_items (
                            item_id, thread_id, turn_id, type, sequence_no, payload_json,
                            status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                itemId, threadId, turnId, type, itemId.hashCode(), payload, status,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }
}
