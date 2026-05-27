package com.wzx.babiq.server.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * BaBiQ 自有词法能力搜索实现。
 *
 * <p>它不依赖额外 Spring AI Community 版本，因此不会影响当前 Spring AI 1.1.6 主线。
 * 算法刻意简单：按查询词在能力 id、名称、说明和 searchText 中的命中次数评分，足够支撑
 * P3-5 的按需能力发现；后续可以在同一接口下替换为 Lucene/BM25。</p>
 */
@Service
public class FallbackLexicalCapabilitySearchService implements CapabilitySearchService {

    /** 当前搜索策略名称，写入审计表。 */
    public static final String STRATEGY = "FALLBACK_LEXICAL";

    /** 能力事实源。 */
    private final CapabilityRepository repository;
    /** 本地 JSON mapper，只用于审计 id 数组。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建词法能力搜索服务。
     */
    public FallbackLexicalCapabilitySearchService(CapabilityRepository repository) {
        this.repository = repository;
    }

    @Override
    public CapabilitySearchResult search(CapabilitySearchRequest request) {
        String query = request == null ? "" : safe(request.queryText()).trim();
        int limit = request == null || request.limit() <= 0 ? 8 : request.limit();
        List<String> terms = terms(query);
        List<ScoredCapability> scored = repository.listEnabled().stream()
                .filter(descriptor -> descriptor.exposureMode() != CapabilityExposureMode.DISABLED)
                .map(descriptor -> new ScoredCapability(descriptor, score(descriptor, terms)))
                .filter(scoredCapability -> scoredCapability.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCapability::score).reversed()
                        .thenComparing(scoredCapability -> scoredCapability.descriptor().capabilityId()))
                .limit(limit)
                .toList();
        List<CapabilityDescriptor> results = scored.stream().map(ScoredCapability::descriptor).toList();
        if (request != null && request.recordEvent()) {
            recordEvent(request, results);
        }
        return new CapabilitySearchResult(STRATEGY, results);
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

    private int score(CapabilityDescriptor descriptor, List<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        String haystack = (descriptor.capabilityId() + " " + descriptor.name() + " "
                + descriptor.displayName() + " " + descriptor.description() + " "
                + descriptor.searchText()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score += descriptor.exposureMode() == CapabilityExposureMode.DEFERRED ? 3 : 2;
            }
            if (descriptor.name().toLowerCase(Locale.ROOT).contains(term)) {
                score += 2;
            }
        }
        return score;
    }

    private List<String> terms(String query) {
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_\\-\\.]+"))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
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

    /**
     * 内部评分结果。
     *
     * @param descriptor 能力描述
     * @param score 搜索相关度分数
     */
    private record ScoredCapability(CapabilityDescriptor descriptor, int score) {
    }
}
