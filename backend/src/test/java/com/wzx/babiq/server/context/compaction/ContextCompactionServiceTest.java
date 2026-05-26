package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ContextCompactionService 编排测试。
 *
 * <p>压缩是辅助治理能力，失败时必须写审计并让主 turn 继续，不能把模型调用链路直接打断。</p>
 */
class ContextCompactionServiceTest {

    @Test
    void compact_if_needed_should_record_failed_audit_when_strategy_throws() {
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ContextCompactionService service = new ContextCompactionService(
                new ContextBudgetPolicy(new ContextBudgetProperties(1_000, 0.10, 10, 100, 0.05, 0.01)),
                new CompactionSourceSelector(),
                summaryRepository,
                compactionRepository,
                request -> {
                    throw new IllegalStateException("model unavailable");
                });

        ContextCompactionOutcome outcome = service.compactIfNeeded(new ContextCompactionRequest(
                "thr_1",
                "turn_1",
                "deepseek",
                "deepseek-v4-pro",
                List.of(UserMessageItem.of("it_1", "很长的旧历史")),
                null,
                999,
                1_000,
                "当前问题"));

        ArgumentCaptor<ContextCompactionRecord> captor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(compactionRepository).save(captor.capture());
        verify(summaryRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().errorMessage()).contains("model unavailable");
    }
}
