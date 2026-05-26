package com.wzx.babiq.server.persistence;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-2 上下文窗口快照持久化测试。
 *
 * <p>Agent 运行期只能依赖领域仓库接口，SQLite/MyBatis-Plus 细节被限制在 persistence adapter 内。
 * 这个测试覆盖 window upsert、snapshot 保存和真实 prompt token 回填三条关键写路径。</p>
 */
@SpringBootTest
class ContextSnapshotPersistenceTest {

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
}
