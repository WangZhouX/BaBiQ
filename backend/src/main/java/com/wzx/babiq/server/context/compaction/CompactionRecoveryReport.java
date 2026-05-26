package com.wzx.babiq.server.context.compaction;

import java.time.Instant;

/**
 * 启动时短期压缩恢复扫描报告。
 *
 * <p>报告只描述本地 SQLite 中压缩审计的清理结果，供日志、运行详情或后续状态接口读取。
 * 它不参与模型上下文装配，因此不会污染对话历史。</p>
 *
 * @param recoveredAt 本次扫描完成时间
 * @param scannedCompactions 扫描到的候选压缩记录数量
 * @param interruptedCompactions 被标记为 INTERRUPTED 的半完成记录数量
 * @param orphanedCompactions 被标记为 ORPHANED 的孤儿成功记录数量
 * @param installedCompactions 已确认 active window 正确安装的成功记录数量
 */
public record CompactionRecoveryReport(
        Instant recoveredAt,
        int scannedCompactions,
        int interruptedCompactions,
        int orphanedCompactions,
        int installedCompactions
) {

    /** 空数据库或仓库不可用时的安全报告。 */
    public static CompactionRecoveryReport empty() {
        return new CompactionRecoveryReport(Instant.now(), 0, 0, 0, 0);
    }
}
