package com.wzx.babiq.server.observability;

import java.math.BigDecimal;

/**
 * 本地可观测总量统计。
 *
 * @param turns 统计窗口内的 turn 总数，由 `bq_turns` 聚合得到。
 * @param failedTurns 统计窗口内失败 turn 数，当前按 `FAILED` 状态计算。
 * @param promptTokens 输入 token 总数，来自 `bq_turn_summaries.prompt_tokens`。
 * @param completionTokens 输出 token 总数，来自 `bq_turn_summaries.completion_tokens`。
 * @param estimatedCostUsd 美元成本估算总和；没有 summary 时按 0 处理。
 */
public record ObservabilityTotals(
        long turns,
        long failedTurns,
        long promptTokens,
        long completionTokens,
        BigDecimal estimatedCostUsd) {
}
