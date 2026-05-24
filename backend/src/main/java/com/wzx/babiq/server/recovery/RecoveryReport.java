package com.wzx.babiq.server.recovery;

import java.time.Instant;

/**
 * 启动恢复结果。
 *
 * <p>恢复服务每次运行都会生成一份报告，既用于结构化日志，也用于 run/recovery/status
 * 给桌面端展示“上次启动时收口了多少异常状态”。</p>
 *
 * @param lastRecoveredAt 本次恢复完成时间；没有执行过恢复时为空
 * @param interruptedTurns 从 RUNNING/SENDING 收口为 INTERRUPTED 的 turn 数量
 * @param expiredTurns 从 WAITING_APPROVAL 收口为 EXPIRED 的 turn 数量
 * @param expiredApprovals 被标记为 expired 的审批数量
 */
public record RecoveryReport(
        Instant lastRecoveredAt,
        int interruptedTurns,
        int expiredTurns,
        int expiredApprovals
) {

    /**
     * 空报告，表示当前进程还没有发现需要恢复的遗留状态。
     */
    public static RecoveryReport empty() {
        return new RecoveryReport(null, 0, 0, 0);
    }
}
