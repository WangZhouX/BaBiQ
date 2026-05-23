package com.wzx.babiq.server.observability;

import java.util.Map;

/**
 * 内存指标快照。
 */
public record BaBiQMetricsSnapshot(
        Map<String, Long> turnsByStatus,
        long promptTokens,
        long completionTokens,
        Map<String, Long> toolCallsByName,
        Map<String, Long> approvalDecisionsByDecision
) {
}
