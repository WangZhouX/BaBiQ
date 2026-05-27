package com.wzx.babiq.server.memory.retrieval;

import java.time.Instant;

/**
 * 长期记忆检索审计记录。
 *
 * @param retrievalId 检索事件 id
 * @param threadId 来源 thread id
 * @param turnId 来源 turn id
 * @param snapshotId 上下文快照 id，可为空
 * @param queryText 检索查询
 * @param strategy 检索策略
 * @param candidateCount 初筛候选数量
 * @param selectedReferencesJson 注入引用 id JSON
 * @param tokenEstimate 注入 token 估算
 * @param pollutionFlagsJson 污染标记 JSON
 * @param createdAt 创建时间
 */
public record MemoryRetrievalEventRecord(
        String retrievalId,
        String threadId,
        String turnId,
        String snapshotId,
        String queryText,
        String strategy,
        int candidateCount,
        String selectedReferencesJson,
        int tokenEstimate,
        String pollutionFlagsJson,
        Instant createdAt
) {
}
