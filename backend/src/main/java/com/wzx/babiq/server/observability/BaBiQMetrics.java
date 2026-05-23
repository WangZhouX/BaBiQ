package com.wzx.babiq.server.observability;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * P1 阶段的轻量内存指标聚合器。
 */
@Component
public class BaBiQMetrics {

    private final ConcurrentHashMap<String, LongAdder> turnsByStatus = new ConcurrentHashMap<>();
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> toolCallsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> approvalDecisionsByDecision = new ConcurrentHashMap<>();

    public void recordTurn(String status) {
        increment(turnsByStatus, status);
    }

    public void recordTokens(long prompt, long completion) {
        promptTokens.add(Math.max(0L, prompt));
        completionTokens.add(Math.max(0L, completion));
    }

    public void recordToolCall(String toolName) {
        increment(toolCallsByName, toolName);
    }

    public void recordApprovalDecision(String decision) {
        increment(approvalDecisionsByDecision, decision);
    }

    public BaBiQMetricsSnapshot snapshot() {
        return new BaBiQMetricsSnapshot(
                snapshot(turnsByStatus),
                promptTokens.sum(),
                completionTokens.sum(),
                snapshot(toolCallsByName),
                snapshot(approvalDecisionsByDecision));
    }

    private void increment(ConcurrentHashMap<String, LongAdder> counters, String key) {
        String normalized = key == null || key.isBlank() ? "_unknown" : key;
        counters.computeIfAbsent(normalized, ignored -> new LongAdder()).increment();
    }

    private Map<String, Long> snapshot(ConcurrentHashMap<String, LongAdder> counters) {
        Map<String, Long> values = new LinkedHashMap<>();
        counters.forEach((name, count) -> values.put(name, count.sum()));
        return Map.copyOf(values);
    }
}
