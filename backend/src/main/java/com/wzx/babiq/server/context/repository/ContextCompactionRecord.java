package com.wzx.babiq.server.context.repository;

import java.time.Instant;

/**
 * 短期上下文压缩审计记录。
 *
 * <p>每一次自动压缩尝试都会写入该记录，成功、跳过和失败都可追踪，避免压缩成为不可解释的隐式行为。</p>
 *
 * @param compactionId 压缩尝试 id，以 ctxcmp_ 开头
 * @param threadId 所属会话 id
 * @param turnId 触发压缩的 turn id
 * @param status 压缩状态：SUCCESS、SKIPPED、FAILED
 * @param summaryId 成功时生成的摘要 id
 * @param sourceItemRange 本次压缩覆盖的 item 范围
 * @param sourceStartItemId 本次压缩起点 item id
 * @param sourceEndItemId 本次压缩终点 item id
 * @param estimatedTokensBefore 压缩前本轮上下文预估 token
 * @param estimatedTokensAfter 摘要正文预估 token，失败或跳过时为 0
 * @param errorMessage 失败或跳过原因
 * @param createdAt 创建时间
 * @param triggerType 压缩触发类型，区分自动、手动或极限保护触发
 * @param previousWindowOrdinal 压缩前窗口序号，用于乐观锁和审计回放
 * @param nextWindowOrdinal 压缩安装后的窗口序号，失败或跳过时可为空
 * @param inputSnapshotId 触发压缩时的输入快照 id
 * @param replacementSnapshotId 压缩成功后替换窗口指向的快照 id
 * @param modelContextWindow 本次压缩判断采用的模型上下文窗口
 * @param effectiveInputBudget 本次压缩判断采用的有效输入预算
 * @param autoCompactThreshold 本次压缩判断采用的自动压缩阈值
 * @param startedAt 压缩尝试开始时间
 * @param completedAt 压缩尝试结束时间
 */
public record ContextCompactionRecord(
        String compactionId,
        String threadId,
        String turnId,
        String status,
        String summaryId,
        String sourceItemRange,
        String sourceStartItemId,
        String sourceEndItemId,
        int estimatedTokensBefore,
        int estimatedTokensAfter,
        String errorMessage,
        Instant createdAt,
        String triggerType,
        Integer previousWindowOrdinal,
        Integer nextWindowOrdinal,
        String inputSnapshotId,
        String replacementSnapshotId,
        Integer modelContextWindow,
        Integer effectiveInputBudget,
        Integer autoCompactThreshold,
        Instant startedAt,
        Instant completedAt
) {

    /**
     * P3-3 兼容构造器。
     *
     * <p>旧测试和旧调用方只知道 V8 字段；新审计字段默认留空，由 P3-3a 的压缩安装链路填充。</p>
     */
    public ContextCompactionRecord(String compactionId,
                                   String threadId,
                                   String turnId,
                                   String status,
                                   String summaryId,
                                   String sourceItemRange,
                                   String sourceStartItemId,
                                   String sourceEndItemId,
                                   int estimatedTokensBefore,
                                   int estimatedTokensAfter,
                                   String errorMessage,
                                   Instant createdAt) {
        this(compactionId, threadId, turnId, status, summaryId, sourceItemRange,
                sourceStartItemId, sourceEndItemId, estimatedTokensBefore, estimatedTokensAfter,
                errorMessage, createdAt, null, null, null, null, null,
                null, null, null, null, null);
    }
}
