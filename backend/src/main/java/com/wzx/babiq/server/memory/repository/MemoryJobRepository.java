package com.wzx.babiq.server.memory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆任务仓库端口。
 *
 * <p>Agent/Memory 服务只依赖这个端口，SQLite/MyBatis-Plus 细节留在 persistence adapter。</p>
 */
public interface MemoryJobRepository {

    /** 返回下一次 Phase2 generation。 */
    default int nextPhase2Generation() {
        return 1;
    }

    /** 查询最近已完成 Phase2，用于最小间隔防抖。 */
    default Optional<MemoryJobRecord> findLatestCompletedPhase2() {
        return Optional.empty();
    }

    /** 查询正在等待或运行的 Phase2，避免并发归并。 */
    default Optional<MemoryJobRecord> findActivePhase2() {
        return Optional.empty();
    }

    /** 保存或更新任务。 */
    void save(MemoryJobRecord record);

    /** 最近任务列表。 */
    List<MemoryJobRecord> listLatest(int limit);

    /** 按状态计数。 */
    default long countByStatus(String status) {
        return 0;
    }

    /** 查询可执行 Phase2。 */
    default Optional<MemoryJobRecord> findPendingPhase2() {
        return Optional.empty();
    }

    /** 查询可执行 Phase1。 */
    default Optional<MemoryJobRecord> findPendingPhase1() {
        return Optional.empty();
    }

    /** 将任务更新为运行中。 */
    default MemoryJobRecord markRunning(MemoryJobRecord record, String workerId, Instant leaseUntil, Instant now) {
        MemoryJobRecord running = new MemoryJobRecord(record.jobId(), record.jobType(), record.jobKey(),
                record.generation(), record.threadId(), record.turnId(), "RUNNING", workerId, leaseUntil,
                record.retryCount(), record.maxRetries(), record.inputWatermark(), null, record.createdAt(),
                now, null, now);
        save(running);
        return running;
    }
}
