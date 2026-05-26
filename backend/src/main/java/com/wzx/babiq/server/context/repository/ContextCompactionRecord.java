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
        Instant createdAt
) {
}
