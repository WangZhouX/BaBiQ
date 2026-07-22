package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-5 本地可观测统计服务测试。
 *
 * <p>这些测试刻意从 SQLite 持久化仓库写入数据，再通过 LocalObservabilityService 读取统计。
 * 这样可以证明统计来自历史记录，而不是 P1 的内存计数器或 UI 临时状态。</p>
 */
@SpringBootTest
class LocalObservabilityServiceTest {

    /** 每次测试运行使用独立 SQLite 文件，避免和人工验收数据库互相污染。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "local-observability-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 覆盖默认数据库路径，让 Spring Boot 测试上下文连接独立数据库。 */
    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private TurnPersistenceService turnPersistenceService;
    @Autowired
    private ToolCallPersistenceService toolCallPersistenceService;
    @Autowired
    private LocalObservabilityService observabilityService;

    @Test
    @DisplayName("统计快照按 range 和 cwd 聚合 turn、token、模型、状态和工具")
    void snapshot_should_aggregate_persisted_run_records_by_range_and_cwd() {
        Instant now = Instant.now();
        seedTurn("turn_recent_ok", "thr_recent_ok", "E:\\BaBiQ", "deepseek", "deepseek-v4-pro",
                "COMPLETED", now.minus(Duration.ofHours(2)), 100, 40, 1,
                "read_file", "completed", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(2)).plusMillis(2000));
        seedTurn("turn_recent_failed", "thr_recent_failed", "E:\\BaBiQ", "deepseek", "deepseek-v4-pro",
                "FAILED", now.minus(Duration.ofHours(1)), 50, 10, 1,
                "exec_shell", "failed", now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)).plusMillis(1000));
        seedTurn("turn_old", "thr_old", "E:\\BaBiQ", "dashscope", "qwen-plus",
                "COMPLETED", now.minus(Duration.ofDays(40)), 999, 999, 0,
                null, null, null, null);
        seedTurn("turn_other_cwd", "thr_other_cwd", "H:\\Other", "deepseek", "deepseek-v4-pro",
                "COMPLETED", now.minus(Duration.ofMinutes(30)), 500, 500, 0,
                null, null, null, null);

        LocalObservabilitySnapshot snapshot = observabilityService.snapshot("7d", "E:\\BaBiQ");

        assertThat(snapshot.range()).isEqualTo("7d");
        assertThat(snapshot.totals().turns()).isEqualTo(2);
        assertThat(snapshot.totals().failedTurns()).isEqualTo(1);
        assertThat(snapshot.totals().promptTokens()).isEqualTo(150);
        assertThat(snapshot.totals().completionTokens()).isEqualTo(50);
        assertThat(snapshot.totals().totalTokens()).isEqualTo(200);
        assertThat(snapshot.byModel()).extracting(ModelUsageStats::model).containsExactly("deepseek-v4-pro");
        assertThat(snapshot.byStatus()).extracting(StatusStats::status).containsExactlyInAnyOrder("COMPLETED", "FAILED");
        assertThat(snapshot.byTool()).extracting(ToolStats::toolName).containsExactlyInAnyOrder("read_file", "exec_shell");
        assertThat(snapshot.byTool())
                .filteredOn(tool -> tool.toolName().equals("exec_shell"))
                .singleElement()
                .extracting(ToolStats::failures)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("空数据库返回 0 totals 和空聚合列表")
    void snapshot_should_return_zero_totals_for_empty_database() {
        LocalObservabilitySnapshot snapshot = observabilityService.snapshot("30d", "E:\\NoData");

        assertThat(snapshot.totals().turns()).isZero();
        assertThat(snapshot.totals().failedTurns()).isZero();
        assertThat(snapshot.totals().totalTokens()).isZero();
        assertThat(snapshot.byProvider()).isEmpty();
        assertThat(snapshot.byModel()).isEmpty();
        assertThat(snapshot.byTool()).isEmpty();
        assertThat(snapshot.byStatus()).isEmpty();
    }

    @Test
    @DisplayName("all range 不做时间裁剪")
    void snapshot_should_include_old_turns_when_range_is_all() {
        Instant oldTime = Instant.now().minus(Duration.ofDays(60));
        seedTurn("turn_all_old", "thr_all_old", "E:\\All", "dashscope", "qwen-plus",
                "COMPLETED", oldTime, 10, 5, 0,
                null, null, null, null);

        LocalObservabilitySnapshot snapshot = observabilityService.snapshot("all", "E:\\All");

        assertThat(snapshot.totals().turns()).isEqualTo(1);
        assertThat(snapshot.byProvider()).extracting(ModelUsageStats::providerId).containsExactly("dashscope");
    }

    private void seedTurn(
            String turnId,
            String threadId,
            String cwd,
            String providerId,
            String model,
            String status,
            Instant startedAt,
            long promptTokens,
            long completionTokens,
            int toolCount,
            String toolName,
            String toolStatus,
            Instant toolStartedAt,
            Instant toolCompletedAt) {
        conversationRepository.createThread(threadId, "统计测试", cwd,
                providerId, model, "DANGER_FULL_ACCESS", "ON_REQUEST", startedAt);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, status, "测试输入", cwd, providerId, model,
                "DANGER_FULL_ACCESS", "ON_REQUEST", startedAt));
        turnPersistenceService.updateTurnStatus(turnId, status, status.equals("FAILED") ? "测试失败" : null);
        conversationRepository.saveTurnSummary(TurnSummaryRecord.of(
                turnId, promptTokens, completionTokens, promptTokens + completionTokens,
                1200, toolCount, startedAt.plusMillis(1200)));
        if (toolName != null) {
            toolCallPersistenceService.recordStarted("call_" + turnId, threadId, turnId,
                    toolName, "{\"demo\":true}", toolStartedAt);
            toolCallPersistenceService.recordFinished(turnId, "call_" + turnId, toolStatus,
                    toolStatus.equals("failed") ? null : "ok",
                    toolStatus.equals("failed") ? "工具失败" : null,
                    toolCompletedAt);
        }
    }
}
