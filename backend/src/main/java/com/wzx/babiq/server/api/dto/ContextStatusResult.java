package com.wzx.babiq.server.api.dto;

/**
 * thread 级上下文窗口状态 DTO。
 *
 * @param threadId 当前会话 id。
 * @param windowOrdinal 当前窗口序号。
 * @param modelContextWindow 模型上下文窗口 token 数。
 * @param autoCompactThreshold 自动压缩阈值 token 数。
 * @param lastSnapshotId 最近一次上下文快照 id。
 * @param lastEstimatedTokens 最近快照的预估 token。
 * @param lastActualPromptTokens 最近快照回填的真实 prompt token。
 * @param usageRatio 最近 token 使用率。
 * @param status 状态标签，例如 empty、ok、over_threshold。
 */
public record ContextStatusResult(
        String threadId,
        int windowOrdinal,
        int modelContextWindow,
        int autoCompactThreshold,
        String lastSnapshotId,
        int lastEstimatedTokens,
        Long lastActualPromptTokens,
        double usageRatio,
        String status
) {
}
