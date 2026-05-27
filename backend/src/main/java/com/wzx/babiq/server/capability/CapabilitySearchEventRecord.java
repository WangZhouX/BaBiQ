package com.wzx.babiq.server.capability;

import java.time.Instant;

/**
 * 能力搜索审计记录。
 *
 * <p>`tool_search`、设置页搜索和 Planner 自动选择都会写入该模型，后续排查
 * “为什么某轮模型看到了这个工具”时可以从 SQLite 复现。</p>
 *
 * @param eventId 搜索事件 id
 * @param threadId 来源 thread，可为空
 * @param turnId 来源 turn，可为空
 * @param queryText 搜索词
 * @param strategy 搜索策略
 * @param resultCount 返回数量
 * @param selectedCapabilityIdsJson 返回或装配的能力 id JSON
 * @param rejectedCapabilityIdsJson 被过滤能力 id JSON
 * @param createdAt 创建时间
 */
public record CapabilitySearchEventRecord(
        String eventId,
        String threadId,
        String turnId,
        String queryText,
        String strategy,
        int resultCount,
        String selectedCapabilityIdsJson,
        String rejectedCapabilityIdsJson,
        Instant createdAt
) {
}
