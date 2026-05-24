package com.wzx.babiq.server.observability;

/**
 * Provider 或 Provider/Model 维度的 token 用量聚合。
 *
 * @param providerId Provider 稳定标识，来自 turn 创建时的快照。
 * @param model 模型名；按 Provider 聚合时为空，按模型聚合时为具体模型。
 * @param turns 该维度下的 turn 数。
 * @param failedTurns 该维度下的失败 turn 数。
 * @param promptTokens 输入 token 总数。
 * @param completionTokens 输出 token 总数。
 * @param totalTokens 输入和输出 token 总数。
 */
public record ModelUsageStats(
        String providerId,
        String model,
        long turns,
        long failedTurns,
        long promptTokens,
        long completionTokens,
        long totalTokens) {
}
