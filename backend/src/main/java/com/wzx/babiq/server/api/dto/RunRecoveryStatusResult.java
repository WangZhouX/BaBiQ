package com.wzx.babiq.server.api.dto;

/**
 * run/recovery/status 响应。
 *
 * @param lastRecoveredAt 最近一次启动恢复时间
 * @param interruptedTurns 被恢复为 INTERRUPTED 的 turn 数量
 * @param expiredTurns 被恢复为 EXPIRED 的 turn 数量
 * @param expiredApprovals 被过期的审批数量
 */
public record RunRecoveryStatusResult(
        String lastRecoveredAt,
        int interruptedTurns,
        int expiredTurns,
        int expiredApprovals
) {
}
