package com.wzx.babiq.server.context.repository;

import java.time.Instant;

/**
 * thread 级上下文窗口状态记录。
 *
 * @param threadId 所属会话 id，由 ContextWindowRuntime 写入，UI 状态查询读取。
 * @param windowOrdinal 当前窗口序号，P3-2 初始为 0，后续压缩成功后递增。
 * @param activeSummaryId 当前窗口引用的短期摘要 id，P3-2 暂为空。
 * @param modelContextWindow 当前模型上下文窗口 token 数。
 * @param autoCompactThreshold 自动压缩阈值 token 数。
 * @param lastSnapshotId 最近一次上下文快照 id。
 * @param createdAt 记录创建时间。
 * @param updatedAt 记录更新时间。
 */
public record ContextWindowRecord(
        String threadId,
        int windowOrdinal,
        String activeSummaryId,
        int modelContextWindow,
        int autoCompactThreshold,
        String lastSnapshotId,
        Instant createdAt,
        Instant updatedAt
) {
}
