package com.wzx.babiq.server.memory.repository;

import com.wzx.babiq.server.memory.redaction.MemoryPollutionStatus;

import java.time.Instant;

/**
 * Phase1 产出的长期记忆候选记录。
 *
 * <p>候选是 SQLite 中长期记忆流水线的最小事实单元；Phase2 只选择 CLEAN 且未归并的候选，
 * Markdown 镜像也只从这些记录机械生成。</p>
 */
public record MemoryCandidateRecord(
        /** 候选 id，协议和审计使用。 */
        String candidateId,
        /** 来源会话 id。 */
        String threadId,
        /** 来源 turn id，可为空。 */
        String turnId,
        /** 生成该候选的 Phase1 job id。 */
        String jobId,
        /** 来源工作目录。 */
        String cwd,
        /** 抽取时使用的 Provider id。 */
        String providerId,
        /** 抽取时使用的模型名。 */
        String model,
        /** 脱敏后的原始长期记忆文本。 */
        String rawMemory,
        /** 该会话片段的 rollout 摘要。 */
        String rolloutSummary,
        /** 写入 rollout_summaries 的文件名 slug。 */
        String rolloutSlug,
        /** 来源 item id JSON 数组，用于审计和回放。 */
        String sourceItemIdsJson,
        /** 来源上下文快照 id，可为空。 */
        String sourceSnapshotId,
        /** 污染状态，只有 CLEAN 能参与 Phase2。 */
        MemoryPollutionStatus pollutionStatus,
        /** 脱敏命中次数。 */
        int redactionCount,
        /** 是否已经被某次 Phase2 选中。 */
        boolean selectedForPhase2,
        /** 被选中进入 Phase2 的时间。 */
        Instant selectedAt,
        /** 读取路径引用次数，用于后续排序。 */
        int usageCount,
        /** 最近一次被读取路径引用的时间。 */
        Instant lastUsedAt,
        /** 候选创建时间。 */
        Instant createdAt,
        /** 候选更新时间。 */
        Instant updatedAt
) {
}
