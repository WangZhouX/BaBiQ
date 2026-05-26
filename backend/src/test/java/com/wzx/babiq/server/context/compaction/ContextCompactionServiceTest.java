package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextCompactionService 编排测试。
 *
 * <p>压缩是辅助治理能力，失败时必须写审计并让主 turn 继续，不能把模型调用链路直接打断。</p>
 */
class ContextCompactionServiceTest {

    @Test
    void compact_if_needed_should_install_success_record_with_audit_fields() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        when(windowRepository.compareAndSwapOrdinal(any(), anyInt(), any())).thenReturn(true);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> new ContextCompactionStrategyResult("旧历史已经压缩成摘要。"));

        ContextCompactionOutcome outcome = service.compactIfNeeded(request(999, false),
                new WindowInstallRequest(new ContextWindowRecord(
                        "thr_1", 1, "pending", 1_000, 89, "ctxsnap_after", Instant.now(), Instant.now()),
                        0, "ctxsnap_before", "ctxsnap_after"));

        ArgumentCaptor<ContextSummaryRecord> summaryCaptor = ArgumentCaptor.forClass(ContextSummaryRecord.class);
        ArgumentCaptor<ContextCompactionRecord> compactionCaptor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(summaryRepository).save(summaryCaptor.capture());
        verify(compactionRepository).save(compactionCaptor.capture());
        verify(windowRepository).compareAndSwapOrdinal(any(), anyInt(), any());
        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(compactionCaptor.getValue().status()).isEqualTo("SUCCESS");
        assertThat(compactionCaptor.getValue().triggerType()).isEqualTo("AUTO_PRE_TURN");
        assertThat(compactionCaptor.getValue().previousWindowOrdinal()).isEqualTo(0);
        assertThat(compactionCaptor.getValue().nextWindowOrdinal()).isEqualTo(1);
        assertThat(compactionCaptor.getValue().inputSnapshotId()).isEqualTo("ctxsnap_before");
        assertThat(compactionCaptor.getValue().replacementSnapshotId()).isEqualTo("ctxsnap_after");
        assertThat(compactionCaptor.getValue().modelContextWindow()).isEqualTo(1_000);
        assertThat(compactionCaptor.getValue().effectiveInputBudget()).isPositive();
        assertThat(compactionCaptor.getValue().autoCompactThreshold()).isPositive();
        assertThat(compactionCaptor.getValue().startedAt()).isNotNull();
        assertThat(compactionCaptor.getValue().completedAt()).isNotNull();
    }

    @Test
    void compact_if_needed_should_return_not_needed_without_writes_when_below_threshold() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> new ContextCompactionStrategyResult("不会被调用"));

        ContextCompactionOutcome outcome = service.compactIfNeeded(request(1, false));

        assertThat(outcome.status()).isEqualTo("NOT_NEEDED");
        verify(summaryRepository, never()).save(any());
        verify(compactionRepository, never()).save(any());
        verify(windowRepository, never()).compareAndSwapOrdinal(any(), anyInt(), any());
    }

    @Test
    void compact_if_needed_should_record_skipped_when_source_is_empty() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> new ContextCompactionStrategyResult("不会被调用"));

        ContextCompactionOutcome outcome = service.compactIfNeeded(new ContextCompactionRequest(
                "thr_1", "turn_1", "deepseek", "deepseek-v4-pro",
                List.of(), null, 999, 1_000, "当前问题"));

        ArgumentCaptor<ContextCompactionRecord> captor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(compactionRepository).save(captor.capture());
        verify(summaryRepository, never()).save(any());
        assertThat(outcome.status()).isEqualTo("SKIPPED");
        assertThat(captor.getValue().triggerType()).isEqualTo("AUTO_PRE_TURN");
    }

    @Test
    void compact_if_needed_should_record_failed_when_strategy_returns_empty_summary() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> new ContextCompactionStrategyResult("  "));

        ContextCompactionOutcome outcome = service.compactIfNeeded(request(999, false));

        ArgumentCaptor<ContextCompactionRecord> captor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(compactionRepository).save(captor.capture());
        verify(summaryRepository, never()).save(any());
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(captor.getValue().errorMessage()).contains("空摘要");
        assertThat(captor.getValue().completedAt()).isNotNull();
    }

    @Test
    void compact_if_needed_should_return_conflict_without_saving_summary_when_window_ordinal_changed() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        when(windowRepository.compareAndSwapOrdinal(any(), anyInt(), any())).thenReturn(false);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> new ContextCompactionStrategyResult("旧历史已经压缩成摘要。"));

        ContextCompactionOutcome outcome = service.compactIfNeeded(request(999, false),
                new WindowInstallRequest(new ContextWindowRecord(
                        "thr_1", 1, "pending", 1_000, 89, "ctxsnap_after", Instant.now(), Instant.now()),
                        0, "ctxsnap_before", "ctxsnap_after"));

        ArgumentCaptor<ContextCompactionRecord> captor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(summaryRepository, never()).save(any());
        verify(compactionRepository).save(captor.capture());
        assertThat(outcome.status()).isEqualTo("CONFLICT");
        assertThat(captor.getValue().status()).isEqualTo("CONFLICT");
        assertThat(captor.getValue().summaryId()).isNull();
    }

    @Test
    void compact_if_needed_should_record_failed_audit_when_strategy_throws() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextCompactionService service = service(summaryRepository, compactionRepository, windowRepository,
                request -> {
                    throw new IllegalStateException("model unavailable");
                });

        ContextCompactionOutcome outcome = service.compactIfNeeded(request(999, false));

        ArgumentCaptor<ContextCompactionRecord> captor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(compactionRepository).save(captor.capture());
        verify(summaryRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().errorMessage()).contains("model unavailable");
        assertThat(captor.getValue().completedAt()).isNotNull();
    }

    private static ContextCompactionService service(ContextSummaryRepository summaryRepository,
                                                   ContextCompactionRepository compactionRepository,
                                                   ContextWindowRepository windowRepository,
                                                   ContextCompactionStrategy strategy) {
        return new ContextCompactionService(
                new ContextBudgetPolicy(new ContextBudgetProperties(1_000, 0.10, 10, 100, 0.05, 0.01)),
                new CompactionSourceSelector(),
                summaryRepository,
                compactionRepository,
                strategy,
                new com.wzx.babiq.server.context.ApproximateContextTokenEstimator(),
                windowRepository,
                null);
    }

    private static ContextCompactionRequest request(int estimatedTokens, boolean force) {
        return new ContextCompactionRequest(
                "thr_1",
                "turn_1",
                "deepseek",
                "deepseek-v4-pro",
                List.of(UserMessageItem.of("it_1", "很长的旧历史")),
                null,
                estimatedTokens,
                1_000,
                "当前问题",
                force);
    }
}
