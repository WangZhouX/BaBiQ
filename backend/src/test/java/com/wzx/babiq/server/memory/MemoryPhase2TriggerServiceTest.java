package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase2 自动触发测试。
 *
 * <p>P3-4 不允许长期记忆只停留在手动 consolidate：Phase1 产出 CLEAN 候选达到阈值后，
 * 后台需要创建带 generation 的 phase2 job，同时保留每次归并的审计历史。</p>
 */
class MemoryPhase2TriggerServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("CLEAN 未归并候选达到阈值时创建 phase2 generation job")
    void trigger_should_enqueue_phase2_job_when_clean_candidates_reach_threshold() {
        InMemoryJobRepository jobRepository = new InMemoryJobRepository();
        MemoryPhase2TriggerService service = new MemoryPhase2TriggerService(
                jobRepository,
                new InMemoryCandidateRepository(5),
                LongTermMemoryProperties.defaultsForTests(),
                clock);

        Optional<MemoryJobRecord> job = service.enqueueIfNeeded(false);

        assertThat(job).isPresent();
        assertThat(job.get().jobKey()).isEqualTo("phase2:1");
        assertThat(job.get().generation()).isEqualTo(1);
        assertThat(job.get().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("两次自动 Phase2 之间必须满足最小间隔，force=true 可以绕过间隔")
    void trigger_should_respect_min_interval_unless_forced() {
        InMemoryJobRepository jobRepository = new InMemoryJobRepository();
        jobRepository.jobs.add(MemoryJobRecord.phase2Pending(
                "memjob_old",
                "phase2:1",
                1,
                clock.instant().minusSeconds(60),
                clock.instant().minusSeconds(60)).withStatus("SUCCEEDED", clock.instant().minusSeconds(60)));
        MemoryPhase2TriggerService service = new MemoryPhase2TriggerService(
                jobRepository,
                new InMemoryCandidateRepository(5),
                LongTermMemoryProperties.defaultsForTests().withPhase2MinIntervalMillis(3_600_000),
                clock);

        assertThat(service.enqueueIfNeeded(false)).isEmpty();
        assertThat(service.enqueueIfNeeded(true)).isPresent();
        assertThat(jobRepository.jobs.get(1).jobKey()).isEqualTo("phase2:2");
    }

    private static final class InMemoryCandidateRepository implements MemoryCandidateRepository {
        private final long count;

        private InMemoryCandidateRepository(long count) {
            this.count = count;
        }

        @Override
        public long countUnmergedCleanCandidates() {
            return count;
        }
    }

    private static final class InMemoryJobRepository implements MemoryJobRepository {
        private final List<MemoryJobRecord> jobs = new ArrayList<>();

        @Override
        public int nextPhase2Generation() {
            return jobs.stream().mapToInt(MemoryJobRecord::generation).max().orElse(0) + 1;
        }

        @Override
        public Optional<MemoryJobRecord> findLatestCompletedPhase2() {
            return jobs.stream()
                    .filter(job -> "PHASE2".equals(job.jobType()))
                    .filter(job -> "SUCCEEDED".equals(job.status()))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<MemoryJobRecord> findActivePhase2() {
            return jobs.stream()
                    .filter(job -> "PHASE2".equals(job.jobType()))
                    .filter(job -> "PENDING".equals(job.status()) || "RUNNING".equals(job.status()))
                    .findFirst();
        }

        @Override
        public void save(MemoryJobRecord record) {
            jobs.add(record);
        }

        @Override
        public List<MemoryJobRecord> listLatest(int limit) {
            return jobs;
        }
    }
}
