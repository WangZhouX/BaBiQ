package com.wzx.babiq.server.memory.repository;

import java.time.Instant;

/**
 * 长期记忆后台任务记录。
 *
 * <p>Phase1 和 Phase2 都走同一张任务表。Phase2 使用 generation 递增的 job_key，
 * 这样每次归并都有独立审计记录，不复用 singleton 行。</p>
 */
public record MemoryJobRecord(
        /** 任务 id。 */
        String jobId,
        /** 任务类型，PHASE1 或 PHASE2。 */
        String jobType,
        /** 去重键，Phase2 形如 phase2:{generation}。 */
        String jobKey,
        /** Phase2 generation，Phase1 可为 0。 */
        int generation,
        /** 来源 thread id，Phase2 全局任务为空。 */
        String threadId,
        /** 来源 turn id，通常为空。 */
        String turnId,
        /** 任务状态。 */
        String status,
        /** 当前 worker id，用于租约。 */
        String workerId,
        /** 租约过期时间。 */
        Instant leaseUntil,
        /** 当前重试次数。 */
        int retryCount,
        /** 最大重试次数。 */
        int maxRetries,
        /** 输入水位线，例如 thread updatedAt。 */
        String inputWatermark,
        /** 失败原因。 */
        String errorMessage,
        /** 任务创建时间。 */
        Instant createdAt,
        /** 任务开始时间。 */
        Instant startedAt,
        /** 任务完成时间。 */
        Instant completedAt,
        /** 任务更新时间。 */
        Instant updatedAt
) {

    /**
     * 创建待执行 Phase2 任务。
     */
    public static MemoryJobRecord phase2Pending(
            String jobId,
            String jobKey,
            int generation,
            Instant createdAt,
            Instant updatedAt) {
        return new MemoryJobRecord(jobId, "PHASE2", jobKey, generation, null, null, "PENDING",
                null, null, 0, 3, null, null, createdAt, null, null, updatedAt);
    }

    /**
     * 创建待执行 Phase1 任务。
     */
    public static MemoryJobRecord phase1Pending(String jobId,
                                                String jobKey,
                                                String threadId,
                                                String inputWatermark,
                                                Instant createdAt,
                                                Instant updatedAt) {
        return new MemoryJobRecord(jobId, "PHASE1", jobKey, 0, threadId, null, "PENDING",
                null, null, 0, 3, inputWatermark, null, createdAt, null, null, updatedAt);
    }

    /**
     * 返回同一任务的新状态副本。
     */
    public MemoryJobRecord withStatus(String status, Instant updatedAt) {
        Instant completed = isTerminalStatus(status) ? updatedAt : completedAt;
        Instant started = "RUNNING".equals(status) && startedAt == null ? updatedAt : startedAt;
        return new MemoryJobRecord(jobId, jobType, jobKey, generation, threadId, turnId, status, workerId,
                leaseUntil, retryCount, maxRetries, inputWatermark, errorMessage, createdAt, started, completed,
                updatedAt);
    }

    private static boolean isTerminalStatus(String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "NO_OUTPUT".equals(status)
                || "SKIPPED_POLLUTED".equals(status)
                || "CANCELLED".equals(status);
    }
}
