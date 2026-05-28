package com.wzx.babiq.server.memory.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.model.LongTermMemoryReference;
import com.wzx.babiq.server.memory.LongTermMemoryProperties;
import com.wzx.babiq.server.memory.MemoryStatusService;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 长期记忆检索增强服务。
 *
 * <p>P3-4 read path 只注入最新 memory_summary；P3-5 在此基础上用轻量词法检索补充少量
 * 相关 artifact 片段。它不会修改长期记忆事实源，只生成 reference 和审计事件。</p>
 */
@Service
public class LongTermMemoryRetrievalService {

    /** 默认检索策略名称。 */
    public static final String STRATEGY = "LEXICAL";

    /** 长期记忆产物仓库。 */
    private final MemoryArtifactRepository artifactRepository;
    /** 检索审计仓库。 */
    private final MemoryRetrievalEventRepository eventRepository;
    /** token 预估器。 */
    private final ContextTokenEstimator tokenEstimator;
    /** 当前长期记忆配置供应器，保证设置页切换后检索开关立即生效。 */
    private final Supplier<LongTermMemoryProperties> propertiesSupplier;
    /** JSON mapper，用于审计字段。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建检索服务。
     */
    @Autowired
    public LongTermMemoryRetrievalService(MemoryArtifactRepository artifactRepository,
                                          MemoryRetrievalEventRepository eventRepository,
                                          ContextTokenEstimator tokenEstimator,
                                          MemoryStatusService statusService) {
        this.artifactRepository = artifactRepository;
        this.eventRepository = eventRepository;
        this.tokenEstimator = tokenEstimator;
        this.propertiesSupplier = statusService::properties;
    }

    /**
     * 测试构造器：直接提供固定配置，避免测试依赖完整 Spring 容器。
     */
    public LongTermMemoryRetrievalService(MemoryArtifactRepository artifactRepository,
                                          MemoryRetrievalEventRepository eventRepository,
                                          ContextTokenEstimator tokenEstimator,
                                          LongTermMemoryProperties properties) {
        this.artifactRepository = artifactRepository;
        this.eventRepository = eventRepository;
        this.tokenEstimator = tokenEstimator;
        this.propertiesSupplier = () -> properties;
    }

    /**
     * 根据当前用户输入检索可注入的长期记忆片段。
     */
    public LongTermMemoryRetrievalResult retrieve(String threadId,
                                                  String turnId,
                                                  String snapshotId,
                                                  String queryText,
                                                  int modelContextWindow) {
        RetrievalSelection selection = selectReferences(queryText, modelContextWindow);
        if (selection.auditEligible()) {
            recordEvent(threadId, turnId, snapshotId, queryText,
                    selection.candidateCount(), selection.references(), selection.tokenEstimate());
        }
        return selection.toResult();
    }

    /**
     * 为设置页和调试入口执行只读预览检索。
     *
     * <p>预览检索没有真实 turn/snapshot 上下文，也不代表模型已经看到这些记忆片段，因此不能写入
     * `bq_memory_retrieval_events` 这张“模型注入审计”表。正式 Agent read path 仍然使用
     * {@link #retrieve(String, String, String, String, int)} 写入可追溯审计。</p>
     */
    public LongTermMemoryRetrievalResult retrievePreview(String queryText, int modelContextWindow) {
        return selectReferences(queryText, modelContextWindow).toResult();
    }

    private RetrievalSelection selectReferences(String queryText, int modelContextWindow) {
        LongTermMemoryProperties properties = propertiesSupplier.get();
        if (!properties.enabled() || !properties.readEnabled() || !properties.retrievalEnabled()) {
            return RetrievalSelection.empty(false);
        }
        List<String> terms = terms(queryText);
        if (terms.isEmpty()) {
            return RetrievalSelection.empty(false);
        }
        int budget = Math.max(1, modelContextWindow * properties.retrievalBudgetWindowPercent() / 100);
        List<ScoredArtifact> scored = artifactRepository.listLatest(128).stream()
                .filter(record -> record.summaryText() != null && !record.summaryText().isBlank())
                .map(record -> new ScoredArtifact(record, score(record, terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredArtifact::score).reversed()
                        .thenComparing(candidate -> candidate.record().artifactId()))
                .limit(properties.retrievalMaxReferences())
                .toList();
        List<LongTermMemoryReference> references = selectWithinBudget(scored, budget);
        int tokenEstimate = references.stream().mapToInt(reference -> tokenEstimator.estimate(reference.text())).sum();
        return new RetrievalSelection(references, tokenEstimate, scored.size(), true);
    }

    private List<LongTermMemoryReference> selectWithinBudget(List<ScoredArtifact> scored, int budget) {
        java.util.ArrayList<LongTermMemoryReference> selected = new java.util.ArrayList<>();
        int used = 0;
        for (ScoredArtifact candidate : scored) {
            String text = clip(candidate.record().summaryText(), Math.max(32, budget - used));
            int estimate = tokenEstimator.estimate(text);
            if (used + estimate > budget && !selected.isEmpty()) {
                break;
            }
            selected.add(new LongTermMemoryReference(candidate.record().artifactId(), "medium", text));
            used += estimate;
            if (used >= budget) {
                break;
            }
        }
        return selected;
    }

    private int score(MemoryArtifactRecord record, List<String> terms) {
        String haystack = (record.artifactType() + " " + record.artifactPath() + " " + record.summaryText())
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    private List<String> terms(String query) {
        return java.util.Arrays.stream((query == null ? "" : query).toLowerCase(Locale.ROOT)
                        .split("[^\\p{IsAlphabetic}\\p{IsDigit}_\\-\\.]+"))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private String clip(String text, int budgetTokens) {
        if (tokenEstimator.estimate(text) <= budgetTokens) {
            return text;
        }
        int maxChars = Math.max(1, budgetTokens * 3);
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private void recordEvent(String threadId,
                             String turnId,
                             String snapshotId,
                             String queryText,
                             int candidateCount,
                             List<LongTermMemoryReference> references,
                             int tokenEstimate) {
        eventRepository.save(new MemoryRetrievalEventRecord(
                newRetrievalId(),
                threadId,
                turnId,
                snapshotId,
                queryText,
                STRATEGY,
                candidateCount,
                writeJson(references.stream().map(LongTermMemoryReference::artifactId).toList()),
                tokenEstimate,
                "[]",
                Instant.now()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("长期记忆检索审计 JSON 序列化失败", exception);
        }
    }

    private static String newRetrievalId() {
        return "memret_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 内部评分结构。
     *
     * @param record artifact 记录
     * @param score 词法相关度
     */
    private record ScoredArtifact(MemoryArtifactRecord record, int score) {
    }

    /**
     * 检索中间结果，额外保留候选数量和是否需要审计，避免预览入口误写正式注入审计表。
     *
     * @param references 最终可注入或预览的记忆片段
     * @param tokenEstimate 片段 token 估算
     * @param candidateCount 初筛候选数量
     * @param auditEligible 是否已经完成有效检索、可由正式 read path 写入审计
     */
    private record RetrievalSelection(
            List<LongTermMemoryReference> references,
            int tokenEstimate,
            int candidateCount,
            boolean auditEligible
    ) {
        private static RetrievalSelection empty(boolean auditEligible) {
            return new RetrievalSelection(List.of(), 0, 0, auditEligible);
        }

        private LongTermMemoryRetrievalResult toResult() {
            return new LongTermMemoryRetrievalResult(references, tokenEstimate);
        }
    }
}
