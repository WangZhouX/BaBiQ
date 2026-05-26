package com.wzx.babiq.server.memory.repository;

import java.time.Instant;

/**
 * 长期记忆读取引用记录。
 *
 * <p>每次上下文窗口注入 memory_summary 时都会写入引用记录，便于追踪哪一轮模型看到了哪份长期记忆。</p>
 */
public record MemoryReferenceRecord(
        /** 引用记录 id。 */
        String referenceId,
        /** 当前会话 id。 */
        String threadId,
        /** 当前 turn id。 */
        String turnId,
        /** 当前上下文快照 id。 */
        String snapshotId,
        /** 被注入的记忆产物 id。 */
        String artifactId,
        /** 候选 id；summary 级引用通常为空。 */
        String candidateId,
        /** 引用类型，例如 SUMMARY。 */
        String referenceType,
        /** 本次注入 token 估算。 */
        int tokenEstimate,
        /** 引用创建时间。 */
        Instant createdAt
) {
}
