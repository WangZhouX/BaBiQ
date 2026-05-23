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

    /** status -> turn 数量，例如 completed/failed/cancelled，后续观测面板会读取它。 */
    private final ConcurrentHashMap<String, LongAdder> turnsByStatus = new ConcurrentHashMap<>();
    /** 全局 prompt token 累计值，和单 turn 的 TurnObservationContext 不同，它跨 turn 聚合。 */
    private final LongAdder promptTokens = new LongAdder();
    /** 全局 completion token 累计值，用于粗略观察模型输出消耗趋势。 */
    private final LongAdder completionTokens = new LongAdder();
    /** toolName -> 调用次数，用于观察哪些工具被 Agent 高频使用。 */
    private final ConcurrentHashMap<String, LongAdder> toolCallsByName = new ConcurrentHashMap<>();
    /** decision -> 次数，统计 approve/deny/edit 等 HITL 审批决策。 */
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
