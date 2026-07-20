package com.wzx.babiq.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.model.ContextPriority;
import com.wzx.babiq.server.context.model.ContextSnapshotItem;
import com.wzx.babiq.server.context.model.ContextSourceType;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3-2 上下文窗口快照持久化测试。
 *
 * <p>Agent 运行期只能依赖领域仓库接口，SQLite/MyBatis-Plus 细节被限制在 persistence adapter 内。
 * 这个测试覆盖 window upsert、snapshot 保存和真实 prompt token 回填三条关键写路径。</p>
 */
@SpringBootTest
class ContextSnapshotPersistenceTest {

    @Test
    @DisplayName("attachment snapshot round-trip 只保存安全元数据不保存正文或路径")
    void attachment_snapshot_should_persist_metadata_only() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thr_attachment_" + suffix;
        String turnId = "turn_attachment_" + suffix;
        String snapshotId = "ctxsnap_attachment_" + suffix;
        String bodyMarker = "EXTRACTED_ATTACHMENT_BODY_MARKER";
        String forbiddenPath = "C:\\Users\\secret\\合同.txt";
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "attachment", "E:\\BaBiQ",
                "provider", "model", "READ_ONLY", "NEVER", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "input", "E:\\BaBiQ", "provider", "model",
                "READ_ONLY", "NEVER", now));
        ObjectMapper snakeCase = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String itemsJson = snakeCase.writeValueAsString(List.of(new ContextSnapshotItem(
                "A-7K3M2Q", ContextSourceType.ATTACHMENT, ContextPriority.AUTHORITATIVE,
                true, "ATTACHMENT_INCLUDED", 12, "合同.txt", "text/plain",
                20, 20, 0)));
        snapshotRepository.save(new ContextSnapshotRecord(
                snapshotId, threadId, turnId, "pre_model_call", "provider", "model", "E:\\BaBiQ",
                0, 1000, 750, 22, null, 1, 0, "{\"current_turn\":{}}", itemsJson,
                "{}", null, 0, "input", now));

        ContextSnapshotRecord stored = snapshotRepository.findBySnapshotId(snapshotId).orElseThrow();

        assertThat(stored.itemsJson())
                .contains("A-7K3M2Q", "合同.txt", "text/plain")
                .doesNotContain(bodyMarker, forbiddenPath);
        assertThat(stored.envelopeJson()).doesNotContain(bodyMarker, forbiddenPath);
        assertThat(stored.inputPreview()).doesNotContain(bodyMarker, forbiddenPath);
    }

    private static final BusinessIdentityScope SCOPE_A = BusinessIdentityScope.scoped(
            "desktop", "session-a", "auth-a", 1, "user-a", "tenant-a", "platform");
    private static final BusinessIdentityScope SCOPE_B = BusinessIdentityScope.scoped(
            "desktop", "session-b", "auth-b", 2, "user-b", "tenant-b", "platform");

    /** 每次测试使用独立 SQLite 文件，避免上下文快照受到本机真实历史污染。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "context-window-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 覆盖持久化路径，让 Flyway 在测试库里创建 P3-2 新表。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ContextWindowRepository windowRepository;
    @Autowired
    private ContextSnapshotRepository snapshotRepository;

    @Test
    @DisplayName("window 和 snapshot 可以按 thread、turn、snapshot id 回读")
    void context_window_and_snapshot_should_round_trip() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_ctx", "上下文测试", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "WORKSPACE_WRITE", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                "turn_ctx", "thr_ctx", "COMPLETED", "请总结", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "WORKSPACE_WRITE", "ON_REQUEST", now));
        windowRepository.upsert(new ContextWindowRecord(
                "thr_ctx",
                0,
                null,
                128_000,
                89_600,
                "ctxsnap_1",
                now,
                now));
        snapshotRepository.save(new ContextSnapshotRecord(
                "ctxsnap_1",
                "thr_ctx",
                "turn_ctx",
                "pre_model_call",
                "deepseek",
                "deepseek-v4-pro",
                "E:\\BaBiQ",
                0,
                128_000,
                89_600,
                1200,
                null,
                3,
                1,
                "{\"current_turn\":{}}",
                "[]",
                "{\"tools\":[]}",
                "请总结",
                now));

        snapshotRepository.updateActualPromptTokens("ctxsnap_1", 1300L);

        assertThat(windowRepository.findByThreadId("thr_ctx"))
                .get()
                .satisfies(window -> {
                    assertThat(window.windowOrdinal()).isZero();
                    assertThat(window.lastSnapshotId()).isEqualTo("ctxsnap_1");
                });
        assertThat(snapshotRepository.findLatestByTurnId("turn_ctx"))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.snapshotId()).isEqualTo("ctxsnap_1");
                    assertThat(snapshot.actualPromptTokens()).isEqualTo(1300L);
                    assertThat(snapshot.includedItemCount()).isEqualTo(3);
                });
        assertThat(snapshotRepository.findBySnapshotId("ctxsnap_1")).isPresent();
    }

    @Test
    @DisplayName("window ordinal 乐观锁只允许匹配旧序号的安装成功")
    void context_window_should_compare_and_swap_by_window_ordinal() {
        Instant now = Instant.now();
        conversationRepository.createThread("thr_cas", "上下文 CAS 测试", "E:\\BaBiQ",
                "deepseek", "deepseek-v4-pro", "WORKSPACE_WRITE", "ON_REQUEST", now);
        windowRepository.upsert(new ContextWindowRecord(
                "thr_cas",
                0,
                null,
                128_000,
                89_600,
                "ctxsnap_0",
                now,
                now));

        boolean firstInstalled = windowRepository.compareAndSwapOrdinal("thr_cas", 0, new ContextWindowRecord(
                "thr_cas",
                1,
                "ctxsum_1",
                128_000,
                89_600,
                "ctxsnap_1",
                now,
                now.plusSeconds(1)));
        boolean secondInstalled = windowRepository.compareAndSwapOrdinal("thr_cas", 0, new ContextWindowRecord(
                "thr_cas",
                1,
                "ctxsum_2",
                128_000,
                89_600,
                "ctxsnap_2",
                now,
                now.plusSeconds(2)));

        assertThat(firstInstalled).isTrue();
        assertThat(secondInstalled).isFalse();
        assertThat(windowRepository.findByThreadId("thr_cas"))
                .get()
                .satisfies(window -> {
                    assertThat(window.windowOrdinal()).isEqualTo(1);
                    assertThat(window.activeSummaryId()).isEqualTo("ctxsum_1");
                    assertThat(window.lastSnapshotId()).isEqualTo("ctxsnap_1");
                });
    }

    @Test
    @DisplayName("scoped context repositories query exact seven-field identity in SQL")
    void scoped_context_queries_should_not_expose_other_identity() {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thr_scope_" + suffix;
        String turnId = "turn_scope_" + suffix;
        String snapshotId = "ctxsnap_scope_" + suffix;
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "scope", "E:\\BaBiQ", "provider", "model",
                "WORKSPACE_WRITE", "ON_REQUEST", now, SCOPE_A);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "COMPLETED", "input", "E:\\BaBiQ", "provider", "model",
                "WORKSPACE_WRITE", "ON_REQUEST", now, SCOPE_A));
        windowRepository.upsert(new ContextWindowRecord(
                threadId, 0, null, 1000, 750, snapshotId, now, now, SCOPE_A));
        snapshotRepository.save(new ContextSnapshotRecord(
                snapshotId, threadId, turnId, "pre_model_call", "provider", "model", "E:\\BaBiQ",
                0, 1000, 750, 10, null, 1, 0, "{}", "[]", "{}", null, 0,
                "preview", now, SCOPE_A));

        assertThat(windowRepository.findByThreadId(threadId, SCOPE_A)).isPresent();
        assertThat(windowRepository.findByThreadId(threadId, SCOPE_B)).isEmpty();
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_A)).isPresent();
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_B)).isEmpty();
        assertThat(snapshotRepository.findLatestByTurnId(turnId, SCOPE_A)).isPresent();
        assertThat(snapshotRepository.findLatestByTurnId(turnId, SCOPE_B)).isEmpty();
        assertThat(snapshotRepository.findLatestByThreadId(threadId, SCOPE_A)).isPresent();
        assertThat(snapshotRepository.findLatestByThreadId(threadId, SCOPE_B)).isEmpty();
    }

    @Test
    @DisplayName("context IDs cannot be rebound across identity scopes")
    void context_ids_should_preserve_immutable_scope_and_correlation() {
        String suffix = UUID.randomUUID().toString();
        String threadId = "thr_context_owner_" + suffix;
        String turnId = "turn_context_owner_" + suffix;
        String snapshotId = "ctxsnap_context_owner_" + suffix;
        Instant now = Instant.parse("2026-07-17T02:00:00Z");
        conversationRepository.createThread(threadId, "scope", "E:\\BaBiQ", "provider", "model",
                "WORKSPACE_WRITE", "ON_REQUEST", now, SCOPE_A);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "input", "E:\\BaBiQ", "provider", "model",
                "WORKSPACE_WRITE", "ON_REQUEST", now, SCOPE_A));
        ContextWindowRecord windowA = new ContextWindowRecord(
                threadId, 0, null, 1000, 750, snapshotId, now, now, SCOPE_A);
        ContextSnapshotRecord snapshotA = new ContextSnapshotRecord(
                snapshotId, threadId, turnId, "pre_model_call", "provider", "model", "E:\\BaBiQ",
                0, 1000, 750, 10, null, 1, 0, "{}", "[]", "{}", null, 0,
                "preview", now, SCOPE_A);
        windowRepository.upsert(windowA);
        snapshotRepository.save(snapshotA);

        snapshotRepository.updateActualPromptTokens(snapshotId, 99L);
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_A)).get()
                .extracting(ContextSnapshotRecord::actualPromptTokens).isNull();
        snapshotRepository.updateActualPromptTokens(snapshotId, 99L, SCOPE_A);
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_A)).get()
                .extracting(ContextSnapshotRecord::actualPromptTokens).isEqualTo(99L);

        windowRepository.upsert(windowA);
        snapshotRepository.save(snapshotA);
        assertThatThrownBy(() -> windowRepository.upsert(new ContextWindowRecord(
                threadId, 9, "stolen", 2000, 1500, "stolen", now, now.plusSeconds(1), SCOPE_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("context window immutable scope conflict");
        assertThatThrownBy(() -> windowRepository.compareAndSwapOrdinal(
                threadId, 0, new ContextWindowRecord(
                        threadId, 1, "stolen", 2000, 1500, "stolen", now, now.plusSeconds(1), SCOPE_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("context window immutable scope conflict");
        assertThatThrownBy(() -> snapshotRepository.save(new ContextSnapshotRecord(
                snapshotId, threadId, turnId, "pre_model_call", "other", "other", "C:/other",
                9, 2000, 1500, 99, 99L, 9, 9, "{\"stolen\":true}", "[]", "{}", null, 0,
                "stolen", now.plusSeconds(1), SCOPE_B)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("context snapshot immutable metadata conflict");

        assertThat(windowRepository.findByThreadId(threadId, SCOPE_A)).get()
                .satisfies(stored -> {
                    assertThat(stored.windowOrdinal()).isZero();
                    assertThat(stored.lastSnapshotId()).isEqualTo(snapshotId);
                });
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_A)).get()
                .satisfies(stored -> {
                    assertThat(stored.providerId()).isEqualTo("provider");
                    assertThat(stored.inputPreview()).isEqualTo("preview");
                });
        assertThat(windowRepository.findByThreadId(threadId, SCOPE_B)).isEmpty();
        assertThat(snapshotRepository.findBySnapshotId(snapshotId, SCOPE_B)).isEmpty();
    }
}
