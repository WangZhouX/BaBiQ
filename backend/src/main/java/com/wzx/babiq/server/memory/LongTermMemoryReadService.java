package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.model.LongTermMemoryReference;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryReferenceRecord;
import com.wzx.babiq.server.memory.repository.MemoryReferenceRepository;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalResult;
import com.wzx.babiq.server.memory.retrieval.LongTermMemoryRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 长期记忆读取路径。
 *
 * <p>该服务只读取最新 MEMORY_SUMMARY，并按 token 预算裁剪后交给 ContextAssembler。
 * 这和 Spring AI ChatMemory 的“消息窗口缓存”不同：BaBiQ 的长期记忆事实源仍是 SQLite artifact。</p>
 */
@Service
public class LongTermMemoryReadService {

    /** 长期记忆产物仓库。 */
    private final MemoryArtifactRepository artifactRepository;
    /** 读取引用仓库。 */
    private final MemoryReferenceRepository referenceRepository;
    /** token 预估器，用于预算裁剪。 */
    private final ContextTokenEstimator tokenEstimator;
    /** 长期记忆配置供应器，设置页修改后读取最新开关。 */
    private final Supplier<LongTermMemoryProperties> propertiesSupplier;
    /** P3-5 长期记忆检索增强服务；为空时保持 P3-4 summary-only 行为。 */
    private final LongTermMemoryRetrievalService retrievalService;

    /**
     * 创建长期记忆读取服务。
     */
    @Autowired
    public LongTermMemoryReadService(MemoryArtifactRepository artifactRepository,
                                     MemoryReferenceRepository referenceRepository,
                                     ContextTokenEstimator tokenEstimator,
                                     MemoryStatusService statusService,
                                     ObjectProvider<LongTermMemoryRetrievalService> retrievalServiceProvider) {
        this.artifactRepository = artifactRepository;
        this.referenceRepository = referenceRepository;
        this.tokenEstimator = tokenEstimator;
        this.propertiesSupplier = statusService::properties;
        this.retrievalService = retrievalServiceProvider.getIfAvailable();
    }

    /**
     * 测试构造器：直接提供固定配置。
     */
    public LongTermMemoryReadService(MemoryArtifactRepository artifactRepository,
                                     MemoryReferenceRepository referenceRepository,
                                     ContextTokenEstimator tokenEstimator,
                                     LongTermMemoryProperties properties) {
        this.artifactRepository = artifactRepository;
        this.referenceRepository = referenceRepository;
        this.tokenEstimator = tokenEstimator;
        this.propertiesSupplier = () -> properties;
        this.retrievalService = null;
    }

    /**
     * 为当前 turn 读取可注入的长期记忆。
     */
    public LongTermMemoryReadResult readForTurn(String threadId, String turnId, String snapshotId) {
        return readForTurn(threadId, turnId, snapshotId, "", 32_768);
    }

    /**
     * 为当前 turn 读取可注入的长期记忆，并按本轮用户输入补充少量检索命中。
     *
     * <p>summary 仍然是 read path 的主干，检索引用只作为低优先级补充。这样可以避免把长期记忆
     * 变成无边界的历史回放，也便于后续在 SQLite 中审计每次注入了哪些 artifact。</p>
     */
    public LongTermMemoryReadResult readForTurn(String threadId,
                                                String turnId,
                                                String snapshotId,
                                                String queryText,
                                                int modelContextWindow) {
        LongTermMemoryProperties properties = propertiesSupplier.get();
        if (!properties.enabled() || !properties.readEnabled()) {
            return LongTermMemoryReadResult.empty();
        }
        List<LongTermMemoryReference> references = new ArrayList<>();
        int tokenEstimate = 0;
        LongTermMemoryReadResult summaryResult = artifactRepository.findLatestByType("MEMORY_SUMMARY")
                .map(artifact -> toReadResult(artifact, properties.readBudgetTokens()))
                .orElseGet(LongTermMemoryReadResult::empty);
        references.addAll(summaryResult.references());
        tokenEstimate += summaryResult.tokenEstimate();
        if (retrievalService != null) {
            LongTermMemoryRetrievalResult retrievalResult = retrievalService.retrieve(
                    threadId, turnId, snapshotId, queryText, modelContextWindow);
            for (LongTermMemoryReference reference : retrievalResult.references()) {
                if (references.stream().noneMatch(existing -> existing.artifactId().equals(reference.artifactId()))) {
                    references.add(reference);
                    tokenEstimate += tokenEstimator.estimate(reference.text());
                }
            }
        }
        return references.isEmpty() ? LongTermMemoryReadResult.empty()
                : new LongTermMemoryReadResult(List.copyOf(references), tokenEstimate);
    }

    /**
     * 保存本轮上下文窗口实际注入的长期记忆引用。
     */
    public void recordReferences(String threadId,
                                 String turnId,
                                 String snapshotId,
                                 List<LongTermMemoryReference> references) {
        if (snapshotId == null || snapshotId.isBlank() || references == null || references.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (LongTermMemoryReference reference : references) {
            referenceRepository.save(new MemoryReferenceRecord(
                    newReferenceId(),
                    threadId,
                    turnId,
                    snapshotId,
                    reference.artifactId(),
                    null,
                    "SUMMARY",
                    tokenEstimator.estimate(reference.text()),
                    now));
        }
    }

    private LongTermMemoryReadResult toReadResult(MemoryArtifactRecord artifact, int budgetTokens) {
        String summary = artifact.summaryText() == null ? "" : artifact.summaryText();
        String clipped = clipByParagraph(summary, budgetTokens);
        if (clipped.isBlank()) {
            return LongTermMemoryReadResult.empty();
        }
        int tokenEstimate = tokenEstimator.estimate(clipped);
        return new LongTermMemoryReadResult(
                List.of(new LongTermMemoryReference(artifact.artifactId(), "medium", clipped)),
                tokenEstimate);
    }

    private String clipByParagraph(String text, int budgetTokens) {
        if (tokenEstimator.estimate(text) <= budgetTokens) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (String paragraph : text.split("\\R\\s*\\R")) {
            String next = builder.isEmpty() ? paragraph : builder + "\n\n" + paragraph;
            if (tokenEstimator.estimate(next) > budgetTokens) {
                break;
            }
            builder.setLength(0);
            builder.append(next);
        }
        if (!builder.isEmpty()) {
            return builder.toString();
        }
        int maxChars = Math.max(1, budgetTokens * 3);
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static String newReferenceId() {
        return "memref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
