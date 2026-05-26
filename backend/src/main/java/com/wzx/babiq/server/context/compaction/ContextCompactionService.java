package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 短期上下文自动压缩服务。
 *
 * <p>它负责“是否压缩、压缩哪些历史、摘要如何落库、失败如何降级”的编排。
 * 真实摘要生成委托给 ContextCompactionStrategy，这样 Spring AI、Spring AI Alibaba 或测试 fake 都能复用同一条审计链路。</p>
 */
@Service
public class ContextCompactionService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionService.class);

    /** 预算策略，决定是否达到自动压缩阈值。 */
    private final ContextBudgetPolicy budgetPolicy;
    /** 来源选择器，保证只压缩模型可见历史。 */
    private final CompactionSourceSelector sourceSelector;
    /** 短期摘要仓库。 */
    private final ContextSummaryRepository summaryRepository;
    /** 压缩审计仓库。 */
    private final ContextCompactionRepository compactionRepository;
    /** 摘要生成策略。 */
    private final ContextCompactionStrategy strategy;
    /** 摘要正文 token 预估器。 */
    private final ContextTokenEstimator tokenEstimator;

    /**
     * 创建生产用压缩服务。
     */
    @Autowired
    public ContextCompactionService(ContextBudgetPolicy budgetPolicy,
                                    CompactionSourceSelector sourceSelector,
                                    ContextSummaryRepository summaryRepository,
                                    ContextCompactionRepository compactionRepository,
                                    ContextCompactionStrategy strategy) {
        this(budgetPolicy, sourceSelector, summaryRepository, compactionRepository,
                strategy, new ApproximateContextTokenEstimator());
    }

    /**
     * 创建可测试的压缩服务。
     */
    public ContextCompactionService(ContextBudgetPolicy budgetPolicy,
                                    CompactionSourceSelector sourceSelector,
                                    ContextSummaryRepository summaryRepository,
                                    ContextCompactionRepository compactionRepository,
                                    ContextCompactionStrategy strategy,
                                    ContextTokenEstimator tokenEstimator) {
        this.budgetPolicy = budgetPolicy == null ? new ContextBudgetPolicy() : budgetPolicy;
        this.sourceSelector = sourceSelector == null ? new CompactionSourceSelector() : sourceSelector;
        this.summaryRepository = summaryRepository;
        this.compactionRepository = compactionRepository;
        this.strategy = strategy;
        this.tokenEstimator = tokenEstimator == null ? new ApproximateContextTokenEstimator() : tokenEstimator;
    }

    /**
     * 如果当前上下文超过阈值，则执行一次 pre-turn 压缩。
     *
     * @param request 压缩请求
     * @return 压缩结果；失败时不会抛出到 Agent 主链路
     */
    public ContextCompactionOutcome compactIfNeeded(ContextCompactionRequest request) {
        ContextBudget budget = budgetFor(request.modelContextWindow());
        if (!request.force() && !budget.shouldCompact(request.estimatedTokensBefore())) {
            return ContextCompactionOutcome.notNeeded();
        }
        Instant now = Instant.now();
        CompactionSource source = sourceSelector.select(request.historyItems(), request.activeSummary());
        if (source.isEmpty()) {
            ContextCompactionRecord record = compactionRecord(request, "SKIPPED", null, source,
                    request.estimatedTokensBefore(), 0, "没有可压缩的模型可见历史", now);
            saveCompaction(record);
            return ContextCompactionOutcome.skipped(record);
        }
        try {
            ContextCompactionStrategyResult result = strategy.summarize(new ContextCompactionStrategyRequest(
                    request.threadId(),
                    request.turnId(),
                    request.providerId(),
                    request.model(),
                    source,
                    request.activeSummary(),
                    request.currentUserMessage()));
            if (result == null || result.summary() == null || result.summary().isBlank()) {
                ContextCompactionRecord record = compactionRecord(request, "FAILED", null, source,
                        request.estimatedTokensBefore(), 0, "压缩策略返回空摘要", now);
                saveCompaction(record);
                return ContextCompactionOutcome.failed(record);
            }
            ContextSummaryRecord summary = summaryRecord(request, source, result.summary(), now);
            saveSummary(summary);
            ContextCompactionRecord record = compactionRecord(request, "SUCCESS", summary.summaryId(), source,
                    request.estimatedTokensBefore(), summary.estimatedTokens(), null, now);
            saveCompaction(record);
            return ContextCompactionOutcome.success(summary, record);
        } catch (RuntimeException exception) {
            log.warn("短期上下文压缩失败，主流程继续使用未压缩上下文: threadId={}, turnId={}, reason={}: {}",
                    request.threadId(), request.turnId(), exception.getClass().getSimpleName(), exception.getMessage());
            ContextCompactionRecord record = compactionRecord(request, "FAILED", null, source,
                    request.estimatedTokensBefore(), 0, exception.getMessage(), now);
            saveCompaction(record);
            return ContextCompactionOutcome.failed(record);
        }
    }

    /**
     * 暴露给 ContextWindowRuntime 写入窗口状态的预算结果。
     *
     * @param modelContextWindow 模型上下文窗口
     * @return 当前策略下的预算
     */
    public ContextBudget budgetFor(int modelContextWindow) {
        return budgetPolicy.calculate(modelContextWindow);
    }

    private ContextSummaryRecord summaryRecord(ContextCompactionRequest request,
                                               CompactionSource source,
                                               String summary,
                                               Instant now) {
        return new ContextSummaryRecord(
                "ctxsum_" + randomSuffix(),
                request.threadId(),
                source.sourceItemRange(),
                source.sourceStartItemId(),
                source.sourceEndItemId(),
                summary,
                request.providerId(),
                request.model(),
                tokenEstimator.estimate(summary),
                now);
    }

    private ContextCompactionRecord compactionRecord(ContextCompactionRequest request,
                                                     String status,
                                                     String summaryId,
                                                     CompactionSource source,
                                                     int tokensBefore,
                                                     int tokensAfter,
                                                     String errorMessage,
                                                     Instant now) {
        return new ContextCompactionRecord(
                "ctxcmp_" + randomSuffix(),
                request.threadId(),
                request.turnId(),
                status,
                summaryId,
                source.sourceItemRange(),
                source.sourceStartItemId(),
                source.sourceEndItemId(),
                tokensBefore,
                tokensAfter,
                errorMessage,
                now);
    }

    private void saveSummary(ContextSummaryRecord summary) {
        if (summaryRepository != null) {
            summaryRepository.save(summary);
        }
    }

    private void saveCompaction(ContextCompactionRecord record) {
        if (compactionRepository != null) {
            compactionRepository.save(record);
        }
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
