package com.wzx.babiq.server.memory.repository;

import java.time.Instant;

/**
 * 长期记忆产物记录。
 *
 * <p>产物既包括模型生成的 memory_summary/MEMORY，也包括 Java 机械生成的 raw_memories
 * 和 rollout_summaries。数据库记录是事实源，Markdown 文件只是可读镜像。</p>
 */
public record MemoryArtifactRecord(
        /** 产物 id。 */
        String artifactId,
        /** 产物类型，例如 MEMORY_SUMMARY、MEMORY_HANDBOOK。 */
        String artifactType,
        /** Markdown 镜像相对路径或绝对路径。 */
        String artifactPath,
        /** 文件内容 hash，用于后续判断镜像是否漂移。 */
        String contentHash,
        /** 产物版本，Phase2 generation 通常对应一个版本。 */
        int version,
        /** 来源 Phase2 job id。 */
        String sourceJobId,
        /** 被选中候选 id JSON 数组。 */
        String candidateIdsJson,
        /** 摘要文本；对 raw/rollout 产物可为空或存预览。 */
        String summaryText,
        /** 文本 token 估算，用于 read path 控制预算。 */
        int tokenEstimate,
        /** 产物创建时间。 */
        Instant createdAt,
        /** 产物更新时间。 */
        Instant updatedAt
) {
}
