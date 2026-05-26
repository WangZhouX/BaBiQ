package com.wzx.babiq.server.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.context.CapabilityCatalogAssembler;
import com.wzx.babiq.server.context.ContextAssembler;
import com.wzx.babiq.server.context.compaction.CompactionSourceSelector;
import com.wzx.babiq.server.context.compaction.ContextBudgetPolicy;
import com.wzx.babiq.server.context.compaction.ContextBudgetProperties;
import com.wzx.babiq.server.context.compaction.ContextCompactionService;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyRequest;
import com.wzx.babiq.server.context.compaction.ContextCompactionStrategyResult;
import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.ContextCompactionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextWindowRuntime 自动压缩协作测试。
 *
 * <p>这里不调用真实大模型，只用 fake strategy 固定摘要输出，验证 runtime 是否按照 Codex 类似的
 * “pre-turn compact -> install active summary -> 再装配模型输入”流程推进。</p>
 */
class ContextWindowRuntimeCompactionTest {

    @Test
    @DisplayName("prepare 超过阈值时会生成摘要、递增窗口并用摘要替换旧历史")
    void prepare_should_install_summary_when_estimated_tokens_cross_threshold() throws Exception {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        when(windowRepository.compareAndSwapOrdinal(any(), anyInt(), any())).thenReturn(true);
        ContextCompactionService compactionService = new ContextCompactionService(
                new ContextBudgetPolicy(new ContextBudgetProperties(1_000, 0.10, 10, 100, 0.05, 0.01)),
                new CompactionSourceSelector(),
                summaryRepository,
                compactionRepository,
                (ContextCompactionStrategyRequest request) ->
                        new ContextCompactionStrategyResult("旧问题和旧回答已经压缩为摘要。"),
                new ApproximateContextTokenEstimator(),
                windowRepository,
                null);
        ContextWindowRuntime runtime = new ContextWindowRuntime(
                conversationRepository,
                new ContextAssembler(new ObjectMapper(), new ApproximateContextTokenEstimator()),
                new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(),
                windowRepository,
                snapshotRepository,
                new ObjectMapper(),
                compactionService);
        Instant now = Instant.now();
        when(conversationRepository.listItems("thr_ctx", 200)).thenReturn(List.of(
                ItemRecord.of("it_1", "thr_ctx", "turn_old", "userMessage", 1,
                        "{\"id\":\"it_1\",\"type\":\"userMessage\",\"text\":\"旧问题\"}", "completed", now),
                ItemRecord.of("it_2", "thr_ctx", "turn_old", "agentMessage", 2,
                        "{\"id\":\"it_2\",\"type\":\"agentMessage\",\"text\":\"旧回答\"}", "completed", now)));
        when(windowRepository.findByThreadId("thr_ctx")).thenReturn(Optional.empty());
        when(windowRepository.upsert(any(ContextWindowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContextWindowRuntimeResult result = runtime.prepare(new ContextWindowRuntimeInput(
                "thr_ctx",
                "turn_current",
                "当前问题",
                "deepseek",
                "deepseek-v4-pro",
                "E:\\BaBiQ",
                "BaBiQ",
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST),
                1_000,
                new org.springframework.ai.tool.ToolCallback[0],
                emitter));

        ArgumentCaptor<ContextSummaryRecord> summaryCaptor = ArgumentCaptor.forClass(ContextSummaryRecord.class);
        ArgumentCaptor<ContextWindowRecord> windowCaptor = ArgumentCaptor.forClass(ContextWindowRecord.class);
        ArgumentCaptor<ContextCompactionRecord> compactionCaptor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        verify(summaryRepository).save(summaryCaptor.capture());
        verify(compactionRepository).save(compactionCaptor.capture());
        verify(windowRepository).compareAndSwapOrdinal(any(), anyInt(), windowCaptor.capture());
        verify(snapshotRepository).save(any(ContextSnapshotRecord.class));
        verify(emitter).emitItemAdded(argThat(ContextWindowRuntimeCompactionTest::isSuccessfulCompactionItem));

        assertThat(summaryCaptor.getValue().sourceItemRange()).isEqualTo("it_1..it_2");
        assertThat(summaryCaptor.getValue().summary()).contains("旧问题和旧回答");
        assertThat(compactionCaptor.getValue().status()).isEqualTo("SUCCESS");
        assertThat(windowCaptor.getValue().windowOrdinal()).isEqualTo(1);
        assertThat(windowCaptor.getValue().activeSummaryId()).isEqualTo(summaryCaptor.getValue().summaryId());
        assertThat(result.assemblyResult().envelope().shortTermSummary().summary()).contains("旧问题和旧回答");
        assertThat(result.assemblyResult().envelope().recentHistory().items()).isEmpty();
    }

    @Test
    @DisplayName("压缩策略失败时 prepare 继续使用未压缩快照")
    void prepare_should_continue_with_uncompacted_snapshot_when_compaction_fails() throws Exception {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        ContextCompactionService compactionService = new ContextCompactionService(
                new ContextBudgetPolicy(new ContextBudgetProperties(1_000, 0.10, 10, 100, 0.05, 0.01)),
                new CompactionSourceSelector(),
                summaryRepository,
                compactionRepository,
                request -> {
                    throw new IllegalStateException("summary model down");
                },
                new ApproximateContextTokenEstimator(),
                windowRepository,
                null);
        ContextWindowRuntime runtime = runtime(conversationRepository, windowRepository, snapshotRepository,
                compactionService);
        Instant now = Instant.now();
        when(conversationRepository.listItems("thr_ctx", 200)).thenReturn(history(now));
        when(windowRepository.findByThreadId("thr_ctx")).thenReturn(Optional.empty());
        when(windowRepository.upsert(any(ContextWindowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContextWindowRuntimeResult result = runtime.prepare(input(emitter));

        ArgumentCaptor<ContextWindowRecord> windowCaptor = ArgumentCaptor.forClass(ContextWindowRecord.class);
        verify(summaryRepository, never()).save(any());
        verify(windowRepository, never()).compareAndSwapOrdinal(any(), anyInt(), any());
        verify(windowRepository).upsert(windowCaptor.capture());
        verify(emitter, never()).emitItemAdded(any());
        assertThat(windowCaptor.getValue().windowOrdinal()).isZero();
        assertThat(windowCaptor.getValue().activeSummaryId()).isNull();
        assertThat(result.assemblyResult().envelope().shortTermSummary()).isNull();
        assertThat(result.assemblyResult().envelope().recentHistory().items()).hasSize(2);
    }

    @Test
    @DisplayName("window ordinal 冲突时 prepare 不安装摘要并继续旧窗口")
    void prepare_should_continue_uncompacted_when_window_cas_conflicts() throws Exception {
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ContextWindowRepository windowRepository = mock(ContextWindowRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextSummaryRepository summaryRepository = mock(ContextSummaryRepository.class);
        ContextCompactionRepository compactionRepository = mock(ContextCompactionRepository.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        when(windowRepository.compareAndSwapOrdinal(any(), anyInt(), any())).thenReturn(false);
        ContextCompactionService compactionService = new ContextCompactionService(
                new ContextBudgetPolicy(new ContextBudgetProperties(1_000, 0.10, 10, 100, 0.05, 0.01)),
                new CompactionSourceSelector(),
                summaryRepository,
                compactionRepository,
                request -> new ContextCompactionStrategyResult("旧问题和旧回答已经压缩为摘要。"),
                new ApproximateContextTokenEstimator(),
                windowRepository,
                null);
        ContextWindowRuntime runtime = runtime(conversationRepository, windowRepository, snapshotRepository,
                compactionService);
        Instant now = Instant.now();
        when(conversationRepository.listItems("thr_ctx", 200)).thenReturn(history(now));
        when(windowRepository.findByThreadId("thr_ctx")).thenReturn(Optional.empty());
        when(windowRepository.upsert(any(ContextWindowRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContextWindowRuntimeResult result = runtime.prepare(input(emitter));

        ArgumentCaptor<ContextCompactionRecord> compactionCaptor = ArgumentCaptor.forClass(ContextCompactionRecord.class);
        ArgumentCaptor<ContextWindowRecord> windowCaptor = ArgumentCaptor.forClass(ContextWindowRecord.class);
        verify(summaryRepository, never()).save(any());
        verify(compactionRepository).save(compactionCaptor.capture());
        verify(windowRepository).upsert(windowCaptor.capture());
        verify(emitter, never()).emitItemAdded(any());
        assertThat(compactionCaptor.getValue().status()).isEqualTo("CONFLICT");
        assertThat(windowCaptor.getValue().windowOrdinal()).isZero();
        assertThat(result.assemblyResult().envelope().recentHistory().items()).hasSize(2);
    }

    private static boolean isSuccessfulCompactionItem(ThreadItem item) {
        return item instanceof ContextCompactionItem compactionItem
                && "SUCCESS".equals(compactionItem.status())
                && compactionItem.windowOrdinal() == 1
                && compactionItem.estimatedTokensAfter() > 0;
    }

    private static ContextWindowRuntime runtime(ConversationRepository conversationRepository,
                                                ContextWindowRepository windowRepository,
                                                ContextSnapshotRepository snapshotRepository,
                                                ContextCompactionService compactionService) {
        return new ContextWindowRuntime(
                conversationRepository,
                new ContextAssembler(new ObjectMapper(), new ApproximateContextTokenEstimator()),
                new CapabilityCatalogAssembler(),
                new ContextualPromptRenderer(),
                windowRepository,
                snapshotRepository,
                new ObjectMapper(),
                compactionService);
    }

    private static List<ItemRecord> history(Instant now) {
        return List.of(
                ItemRecord.of("it_1", "thr_ctx", "turn_old", "userMessage", 1,
                        "{\"id\":\"it_1\",\"type\":\"userMessage\",\"text\":\"旧问题\"}", "completed", now),
                ItemRecord.of("it_2", "thr_ctx", "turn_old", "agentMessage", 2,
                        "{\"id\":\"it_2\",\"type\":\"agentMessage\",\"text\":\"旧回答\"}", "completed", now));
    }

    private static ContextWindowRuntimeInput input(ItemEmitter emitter) {
        return new ContextWindowRuntimeInput(
                "thr_ctx",
                "turn_current",
                "当前问题",
                "deepseek",
                "deepseek-v4-pro",
                "E:\\BaBiQ",
                "BaBiQ",
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST),
                1_000,
                new org.springframework.ai.tool.ToolCallback[0],
                emitter);
    }
}
