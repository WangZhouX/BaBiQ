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
        LongTermMemoryProperties properties = propertiesSupplier.get();
        if (!properties.enabled() || !properties.readEnabled() || !properties.retrievalEnabled()) {
            return LongTermMemoryRetrievalResult.empty();
        }
        List<String> terms = terms(queryText);
        if (terms.isEmpty()) {
            return LongTermMemoryRetrievalResult.empty();
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
        recordEvent(threadId, turnId, snapshotId, queryText, scored.size(), references, tokenEstimate);
        return new LongTermMemoryRetrievalResult(references, tokenEstimate);
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
}
