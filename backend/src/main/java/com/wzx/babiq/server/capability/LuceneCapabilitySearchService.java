package com.wzx.babiq.server.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springaicommunity.tool.search.ToolReference;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.search.ToolSearchResponse;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Spring AI Community LuceneToolSearcher 的能力搜索实现。
 *
 * <p>BaBiQ 的能力事实源仍然是 `bq_capabilities`，Lucene 只作为内存索引派生层。
 * 这样上游 `ToolSearchTool`、`CapabilityExposurePlanner` 和 JSON-RPC handler 仍依赖
 * `CapabilitySearchService` 端口，而底层评分从自实现子串匹配切换为 Lucene/BM25。</p>
 */
@Service
public class LuceneCapabilitySearchService implements CapabilitySearchService {

    /** 当前搜索策略名称，写入 `bq_capability_search_events.strategy` 供后续审计区分历史版本。 */
    public static final String STRATEGY = "LUCENE";
    /** Spring AI Community 搜索器按 session 隔离索引；BaBiQ 当前是本地单进程，使用固定 session 即可。 */
    private static final String SESSION_ID = "babiq";
    /** 最低相关度阈值。工具数量较少，阈值保守放低，避免中文短 query 被过度过滤。 */
    private static final float MIN_SCORE_THRESHOLD = 0.1f;

    /** 能力目录事实源。 */
    private final CapabilityRepository repository;
    /** 官方 Lucene 搜索器，内部使用 StandardAnalyzer + Lucene 打分。 */
    private final LuceneToolSearcher searcher;
    /** 本地 JSON mapper，只用于审计字段序列化。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 Lucene 能力搜索服务。
     *
     * @param repository 能力目录事实源仓库
     */
    public LuceneCapabilitySearchService(CapabilityRepository repository) {
        this.repository = repository;
        this.searcher = new LuceneToolSearcher(MIN_SCORE_THRESHOLD);
    }

    /**
     * 启动后从 SQLite 事实源重建内存索引。
     *
     * <p>当前能力数量是几十级，全量重建比维护复杂增量索引更稳定；未来能力数量上升后再优化为真正增量。</p>
     */
    @PostConstruct
    synchronized void rebuildIndex() {
        searcher.clearIndex(SESSION_ID);
        for (CapabilityDescriptor descriptor : repository.listEnabled()) {
            if (descriptor.exposureMode() == CapabilityExposureMode.DISABLED) {
                continue;
            }
            searcher.indexTool(SESSION_ID, toToolReference(descriptor));
        }
        searcher.commit(SESSION_ID);
    }

    /**
     * 能力目录变化后重建 Lucene 索引。
     *
     * @param event 能力目录变化事件，内容本身不参与计算，只作为解耦通知
     */
    @EventListener
    void onCatalogChanged(CapabilityCatalogChangedEvent event) {
        rebuildIndex();
    }

    @Override
    public CapabilitySearchResult search(CapabilitySearchRequest request) {
        String query = request == null ? "" : safe(request.queryText()).trim();
        int limit = request == null || request.limit() <= 0 ? 8 : request.limit();
        List<CapabilityDescriptor> results = query.isBlank()
                ? List.of()
                : searchWithLucene(query, limit);
        if (request != null && request.recordEvent()) {
            recordEvent(request, results);
        }
        return new CapabilitySearchResult(STRATEGY, results);
    }

    /**
     * 关闭 Lucene 内存索引，避免测试和桌面进程退出时泄漏 reader/writer。
     */
    @PreDestroy
    void close() {
        try {
            searcher.close();
        } catch (Exception exception) {
            throw new IllegalStateException("关闭 Lucene 能力搜索索引失败", exception);
        }
    }

    private List<CapabilityDescriptor> searchWithLucene(String query, int limit) {
        ToolSearchResponse response = searcher.search(new ToolSearchRequest(SESSION_ID, query, limit, null));
        List<ToolReference> references = response == null || response.toolReferences() == null
                ? List.of()
                : response.toolReferences();
        return references.stream()
                .map(reference -> repository.findById(reference.toolName()).orElse(null))
                .filter(descriptor -> descriptor != null
                        && descriptor.enabled()
                        && descriptor.exposureMode() != CapabilityExposureMode.DISABLED)
                .limit(limit)
                .toList();
    }

    private ToolReference toToolReference(CapabilityDescriptor descriptor) {
        return ToolReference.builder()
                .toolName(descriptor.capabilityId())
                .summary(searchSummary(descriptor))
                .build();
    }

    private String searchSummary(CapabilityDescriptor descriptor) {
        return String.join(" ",
                safe(descriptor.capabilityId()),
                safe(descriptor.name()),
                safe(descriptor.displayName()),
                safe(descriptor.description()),
                safe(descriptor.searchText()));
    }

    private void recordEvent(CapabilitySearchRequest request, List<CapabilityDescriptor> results) {
        Set<String> selected = new LinkedHashSet<>(results.stream()
                .map(CapabilityDescriptor::capabilityId)
                .toList());
        List<String> rejected = repository.listAll().stream()
                .filter(descriptor -> !descriptor.enabled() || descriptor.exposureMode() == CapabilityExposureMode.DISABLED)
                .map(CapabilityDescriptor::capabilityId)
                .limit(64)
                .toList();
        repository.recordSearchEvent(new CapabilitySearchEventRecord(
                newEventId(),
                request.threadId(),
                request.turnId(),
                request.queryText(),
                STRATEGY,
                results.size(),
                writeJson(selected),
                writeJson(rejected),
                Instant.now()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("能力搜索审计 JSON 序列化失败", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String newEventId() {
        return "capev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
