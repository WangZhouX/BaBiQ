package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

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
    /** 当前窗口仓库；成功压缩必须通过它以 CAS 方式安装 active summary。 */
    private final ContextWindowRepository windowRepository;
    /** 事务模板；模型调用在事务外完成，摘要、窗口和审计安装在事务内完成。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建生产用压缩服务。
     */
    @Autowired
    public ContextCompactionService(ContextBudgetPolicy budgetPolicy,
                                    CompactionSourceSelector sourceSelector,
                                    ContextSummaryRepository summaryRepository,
                                    ContextCompactionRepository compactionRepository,
                                    ContextCompactionStrategy strategy,
                                    ContextWindowRepository windowRepository,
                                    PlatformTransactionManager transactionManager) {
        this(budgetPolicy, sourceSelector, summaryRepository, compactionRepository,
                strategy, new ApproximateContextTokenEstimator(), windowRepository, transactionManager);
    }

    /**
     * 创建兼容 P3-3 测试的压缩服务。
     */
    public ContextCompactionService(ContextBudgetPolicy budgetPolicy,
                                    CompactionSourceSelector sourceSelector,
                                    ContextSummaryRepository summaryRepository,
                                    ContextCompactionRepository compactionRepository,
                                    ContextCompactionStrategy strategy) {
        this(budgetPolicy, sourceSelector, summaryRepository, compactionRepository,
                strategy, new ApproximateContextTokenEstimator(), null, null);
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
        this(budgetPolicy, sourceSelector, summaryRepository, compactionRepository,
                strategy, tokenEstimator, null, null);
    }

    /**
     * 创建可测试且可注入窗口仓库的压缩服务。
     */
    public ContextCompactionService(ContextBudgetPolicy budgetPolicy,
                                    CompactionSourceSelector sourceSelector,
                                    ContextSummaryRepository summaryRepository,
                                    ContextCompactionRepository compactionRepository,
                                    ContextCompactionStrategy strategy,
                                    ContextTokenEstimator tokenEstimator,
                                    ContextWindowRepository windowRepository,
                                    PlatformTransactionManager transactionManager) {
        this.budgetPolicy = budgetPolicy == null ? new ContextBudgetPolicy() : budgetPolicy;
        this.sourceSelector = sourceSelector == null ? new CompactionSourceSelector() : sourceSelector;
        this.summaryRepository = summaryRepository;
        this.compactionRepository = compactionRepository;
        this.strategy = strategy;
        this.tokenEstimator = tokenEstimator == null ? new ApproximateContextTokenEstimator() : tokenEstimator;
        this.windowRepository = windowRepository;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /**
     * 如果当前上下文超过阈值，则执行一次 pre-turn 压缩。
     *
     * @param request 压缩请求
     * @return 压缩结果；失败时不会抛出到 Agent 主链路
     */
    public ContextCompactionOutcome compactIfNeeded(ContextCompactionRequest request) {
        return compactIfNeeded(request, null);
    }

    /**
     * 如果需要压缩，则生成摘要并可选地把摘要安装到 active window。
     *
     * <p>模型摘要生成一定在事务外执行，避免长事务占住 SQLite 写锁；安装阶段再把 summary、window 和
     * compaction audit 一起提交。若窗口序号已变化，服务返回 CONFLICT，调用方继续使用未压缩上下文。</p>
     *
     * @param request 压缩请求
     * @param installRequest 成功摘要的窗口安装请求；为空时只保存摘要和审计，兼容旧调用方
     * @return 压缩结果；失败时不会抛出到 Agent 主链路
     */
    public ContextCompactionOutcome compactIfNeeded(ContextCompactionRequest request,
                                                    WindowInstallRequest installRequest) {
        ContextBudget budget = budgetFor(request.modelContextWindow());
        if (!request.force() && !budget.shouldCompact(request.estimatedTokensBefore())) {
            return ContextCompactionOutcome.notNeeded();
        }
        CompactionAttempt attempt = createAttempt(request, budget, installRequest);
        return installAttempt(attempt, installRequest);
    }

    private CompactionAttempt createAttempt(ContextCompactionRequest request,
                                            ContextBudget budget,
                                            WindowInstallRequest installRequest) {
        Instant startedAt = Instant.now();
        CompactionSource source = sourceSelector.select(request.historyItems(), request.activeSummary());
        if (source.isEmpty()) {
            ContextCompactionRecord record = compactionRecord(request, "SKIPPED", null, source,
                    request.estimatedTokensBefore(), 0, "没有可压缩的模型可见历史",
                    budget, installRequest, startedAt, Instant.now());
            return new CompactionAttempt("SKIPPED", null, record);
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
                        request.estimatedTokensBefore(), 0, "压缩策略返回空摘要",
                        budget, installRequest, startedAt, Instant.now());
                return new CompactionAttempt("FAILED", null, record);
            }
            ContextSummaryRecord summary = summaryRecord(request, source, result.summary(), Instant.now());
            ContextCompactionRecord record = compactionRecord(request, "SUCCESS", summary.summaryId(), source,
                    request.estimatedTokensBefore(), summary.estimatedTokens(), null,
                    budget, installRequest, startedAt, Instant.now());
            return new CompactionAttempt("SUCCESS", summary, record);
        } catch (RuntimeException exception) {
            log.warn("短期上下文压缩失败，主流程继续使用未压缩上下文: threadId={}, turnId={}, reason={}: {}",
                    request.threadId(), request.turnId(), exception.getClass().getSimpleName(), exception.getMessage());
            ContextCompactionRecord record = compactionRecord(request, "FAILED", null, source,
                    request.estimatedTokensBefore(), 0, exception.getMessage(),
                    budget, installRequest, startedAt, Instant.now());
            return new CompactionAttempt("FAILED", null, record);
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
                                                     ContextBudget budget,
                                                     WindowInstallRequest installRequest,
                                                     Instant startedAt,
                                                     Instant completedAt) {
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
                completedAt == null ? Instant.now() : completedAt,
                request.triggerType(),
                installRequest == null ? null : installRequest.previousWindowOrdinal(),
                installRequest == null || installRequest.nextWindow() == null
                        ? null
                        : installRequest.nextWindow().windowOrdinal(),
                installRequest == null || installRequest.inputSnapshotId() == null
                        ? request.inputSnapshotId()
                        : installRequest.inputSnapshotId(),
                installRequest == null ? null : installRequest.replacementSnapshotId(),
                budget == null ? request.modelContextWindow() : budget.effectiveModelContextWindow(),
                budget == null ? null : budget.inputBudgetTokens(),
                budget == null ? null : budget.autoCompactThresholdTokens(),
                startedAt,
                completedAt);
    }

    private ContextCompactionOutcome installAttempt(CompactionAttempt attempt,
                                                    WindowInstallRequest installRequest) {
        if (!"SUCCESS".equals(attempt.status())) {
            saveCompaction(attempt.compactionRecord());
            if ("SKIPPED".equals(attempt.status())) {
                return ContextCompactionOutcome.skipped(attempt.compactionRecord());
            }
            return ContextCompactionOutcome.failed(attempt.compactionRecord());
        }
        if (installRequest == null) {
            inTransaction(() -> {
                saveSummary(attempt.summaryRecord());
                saveCompaction(attempt.compactionRecord());
                return null;
            });
            return ContextCompactionOutcome.success(attempt.summaryRecord(), attempt.compactionRecord());
        }
        try {
            inTransaction(() -> {
                ContextWindowRecord nextWindow = windowWithSummary(
                        installRequest.nextWindow(),
                        attempt.summaryRecord().summaryId(),
                        installRequest.replacementSnapshotId());
                boolean installed = windowRepository != null
                        && windowRepository.compareAndSwapOrdinal(
                        nextWindow.threadId(), installRequest.previousWindowOrdinal(), nextWindow);
                if (!installed) {
                    throw new WindowOrdinalConflictException();
                }
                saveSummary(attempt.summaryRecord());
                saveCompaction(attempt.compactionRecord());
                return null;
            });
            return ContextCompactionOutcome.success(attempt.summaryRecord(), attempt.compactionRecord());
        } catch (WindowOrdinalConflictException exception) {
            ContextCompactionRecord conflict = copyWithStatus(
                    attempt.compactionRecord(), "CONFLICT", null,
                    "window_ordinal 被并发 turn 抢先更新");
            saveCompaction(conflict);
            return ContextCompactionOutcome.conflict(conflict);
        } catch (RuntimeException exception) {
            log.warn("短期上下文压缩安装失败，主流程继续使用未压缩上下文: compactionId={}, reason={}: {}",
                    attempt.compactionRecord().compactionId(),
                    exception.getClass().getSimpleName(), exception.getMessage());
            ContextCompactionRecord failed = copyWithStatus(
                    attempt.compactionRecord(), "FAILED", null, exception.getMessage());
            saveCompaction(failed);
            return ContextCompactionOutcome.failed(failed);
        }
    }

    private ContextWindowRecord windowWithSummary(ContextWindowRecord source,
                                                  String summaryId,
                                                  String replacementSnapshotId) {
        Instant now = Instant.now();
        return new ContextWindowRecord(
                source.threadId(),
                source.windowOrdinal(),
                summaryId,
                source.modelContextWindow(),
                source.autoCompactThreshold(),
                replacementSnapshotId == null ? source.lastSnapshotId() : replacementSnapshotId,
                source.createdAt(),
                now);
    }

    private ContextCompactionRecord copyWithStatus(ContextCompactionRecord source,
                                                  String status,
                                                  String summaryId,
                                                  String errorMessage) {
        Instant completedAt = Instant.now();
        return new ContextCompactionRecord(
                source.compactionId(),
                source.threadId(),
                source.turnId(),
                status,
                summaryId,
                source.sourceItemRange(),
                source.sourceStartItemId(),
                source.sourceEndItemId(),
                source.estimatedTokensBefore(),
                "CONFLICT".equals(status) ? 0 : source.estimatedTokensAfter(),
                errorMessage,
                completedAt,
                source.triggerType(),
                source.previousWindowOrdinal(),
                source.nextWindowOrdinal(),
                source.inputSnapshotId(),
                source.replacementSnapshotId(),
                source.modelContextWindow(),
                source.effectiveInputBudget(),
                source.autoCompactThreshold(),
                source.startedAt(),
                completedAt);
    }

    private <T> T inTransaction(Supplier<T> supplier) {
        if (transactionTemplate == null) {
            return supplier.get();
        }
        return transactionTemplate.execute(status -> supplier.get());
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

    /**
     * 窗口安装 CAS 失败的内部信号。
     *
     * <p>它只用于从事务 lambda 中跳出，外层会把它转换成 CONFLICT 审计记录，不会向 AgentLoop 抛出。</p>
     */
    private static final class WindowOrdinalConflictException extends RuntimeException {
    }
}
