package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase2 归并触发服务。
 *
 * <p>该服务只负责“是否应该创建任务”，不执行模型调用。触发规则参考 Codex：
 * 保留 generation 历史、防抖、避免已有 active Phase2 时重复入队。</p>
 */
@Service
public class MemoryPhase2TriggerService {

    /** 任务仓库。 */
    private final MemoryJobRepository jobRepository;
    /** 候选仓库。 */
    private final MemoryCandidateRepository candidateRepository;
    /** 长期记忆配置。 */
    private final LongTermMemoryProperties properties;
    /** 时间源，测试可固定。 */
    private final Clock clock;

    /**
     * 创建 Phase2 触发服务。
     */
    @Autowired
    public MemoryPhase2TriggerService(MemoryJobRepository jobRepository,
                                      MemoryCandidateRepository candidateRepository,
                                      LongTermMemoryProperties properties) {
        this(jobRepository, candidateRepository, properties, Clock.systemUTC());
    }

    /**
     * 创建可注入时间源的 Phase2 触发服务。
     */
    public MemoryPhase2TriggerService(MemoryJobRepository jobRepository,
                                      MemoryCandidateRepository candidateRepository,
                                      LongTermMemoryProperties properties,
                                      Clock clock) {
        this.jobRepository = jobRepository;
        this.candidateRepository = candidateRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 条件满足时创建新的 Phase2 generation job。
     *
     * @param force true 表示手动触发，可绕过最小间隔和候选数量阈值
     * @return 新创建的任务；条件不满足时为空
     */
    public Optional<MemoryJobRecord> enqueueIfNeeded(boolean force) {
        if (!properties.enabled() || !properties.generateEnabled()) {
            return Optional.empty();
        }
        if (jobRepository.findActivePhase2().isPresent()) {
            return Optional.empty();
        }
        if (!force && candidateRepository.countUnmergedCleanCandidates() < properties.phase2TriggerOnCandidateCount()) {
            return Optional.empty();
        }
        if (!force && !minIntervalSatisfied()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        int generation = jobRepository.nextPhase2Generation();
        MemoryJobRecord job = MemoryJobRecord.phase2Pending(newJobId(), "phase2:" + generation, generation, now, now);
        jobRepository.save(job);
        return Optional.of(job);
    }

    private boolean minIntervalSatisfied() {
        return jobRepository.findLatestCompletedPhase2()
                .map(MemoryJobRecord::completedAt)
                .map(completedAt -> completedAt == null
                        || Duration.between(completedAt, clock.instant()).toMillis() >= properties.phase2MinIntervalMillis())
                .orElse(true);
    }

    private static String newJobId() {
        return "memjob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
