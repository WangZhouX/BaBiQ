package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.api.dto.MemoryStatusResult;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆状态服务测试。
 *
 * <p>设置页只展示后端事实源返回的流水线指标，因此 SECRET_RISK 这种安全隔离计数必须由
 * MemoryStatusService 从候选仓库聚合，不能在桌面端临时猜测。</p>
 */
class MemoryStatusServiceTest {

    @Test
    @DisplayName("memory/status 同时返回 CLEAN 和 SECRET_RISK 候选计数")
    void status_should_include_secret_risk_candidate_count() {
        MemoryStatusService service = new MemoryStatusService(
                new FakeJobRepository(),
                new FakeCandidateRepository(7, 3),
                new FakeArtifactRepository(),
                LongTermMemoryProperties.defaultsForTests());

        MemoryStatusResult status = service.status();

        assertThat(status.cleanCandidateCount()).isEqualTo(7);
        assertThat(status.secretRiskCandidateCount()).isEqualTo(3);
        assertThat(status.lastSummaryArtifactId()).isEqualTo("memart_summary");
        assertThat(status.phase2Generation()).isEqualTo(4);
    }

    private static final class FakeCandidateRepository implements MemoryCandidateRepository {
        private final long cleanCount;
        private final long secretRiskCount;

        private FakeCandidateRepository(long cleanCount, long secretRiskCount) {
            this.cleanCount = cleanCount;
            this.secretRiskCount = secretRiskCount;
        }

        @Override
        public long countUnmergedCleanCandidates() {
            return cleanCount;
        }

        @Override
        public long countUnmergedSecretRiskCandidates() {
            return secretRiskCount;
        }
    }

    private static final class FakeJobRepository implements MemoryJobRepository {
        @Override
        public int nextPhase2Generation() {
            return 5;
        }

        @Override
        public void save(MemoryJobRecord record) {
        }

        @Override
        public List<MemoryJobRecord> listLatest(int limit) {
            return List.of();
        }
    }

    private static final class FakeArtifactRepository implements MemoryArtifactRepository {
        @Override
        public void save(MemoryArtifactRecord record) {
        }

        @Override
        public Optional<MemoryArtifactRecord> findLatestByType(String artifactType) {
            return Optional.of(new MemoryArtifactRecord(
                    "memart_summary",
                    artifactType,
                    "memory_summary.md",
                    "hash",
                    4,
                    "memjob_4",
                    "[]",
                    "summary",
                    128,
                    Instant.parse("2026-05-28T00:00:00Z"),
                    Instant.parse("2026-05-28T00:00:00Z")));
        }

        @Override
        public List<MemoryArtifactRecord> listLatest(int limit) {
            return List.of();
        }
    }
}
