package com.wzx.babiq.server.conversation.repository;

import java.time.Instant;

/**
 * TurnSummary 持久化边界使用的领域记录。
 *
 * <p>该记录和协议 item 的 turnSummary 含义一致，但位于 repository 层，方便后续历史恢复和观测页面
 * 从数据库读取摘要，而不是重新计算。</p>
 *
 * @param turnId 所属 turnId
 * @param promptTokens 输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens 总 token 数
 * @param durationMs 本轮耗时毫秒数
 * @param toolCount 工具调用次数
 * @param createdAt 摘要生成时间
 */
public record TurnSummaryRecord(
        String turnId,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long durationMs,
        int toolCount,
        Instant createdAt
) {

    /**
     * 创建 turn 摘要记录。
     *
     * @return 可直接保存的 turn summary record
     */
    public static TurnSummaryRecord of(
            String turnId,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long durationMs,
            int toolCount,
            Instant createdAt) {
        return new TurnSummaryRecord(turnId, promptTokens, completionTokens, totalTokens,
                durationMs, toolCount, createdAt);
    }
}
