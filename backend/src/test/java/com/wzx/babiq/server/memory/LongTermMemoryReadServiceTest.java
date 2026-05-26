package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryReferenceRecord;
import com.wzx.babiq.server.memory.repository.MemoryReferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆读取路径测试。
 *
 * <p>读取路径只把经过 Phase2 归并的 memory_summary 按预算注入 reference 层；
 * 原始候选和 handbook 仍通过审计/检索接口查看，不直接塞进每轮 prompt。</p>
 */
class LongTermMemoryReadServiceTest {

    @Test
    @DisplayName("读取路径只注入最新 memory_summary，并按 token 预算截断到段落边界")
    void read_should_return_latest_summary_reference_with_budget() {
        InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        InMemoryReferenceRepository referenceRepository = new InMemoryReferenceRepository();
        artifactRepository.save(new MemoryArtifactRecord(
                "memart_1",
                "MEMORY_SUMMARY",
                Path.of("memory_summary.md").toString(),
                "hash",
                1,
                "job_phase2_1",
                "[\"cand_1\"]",
                "第一段：用户偏好中文提交。\n\n第二段：这个段落故意很长，用来验证预算裁剪不会把所有内容都塞进去。",
                80,
                Instant.parse("2026-05-27T00:00:00Z"),
                Instant.parse("2026-05-27T00:00:00Z")));
        LongTermMemoryReadService readService = new LongTermMemoryReadService(
                artifactRepository,
                referenceRepository,
                new ApproximateContextTokenEstimator(),
                LongTermMemoryProperties.defaultsForTests().withReadBudgetTokens(12));

        LongTermMemoryReadResult result = readService.readForTurn("thr_1", "turn_1", "ctxsnap_1");

        assertThat(result.references()).hasSize(1);
        assertThat(result.references().get(0).artifactId()).isEqualTo("memart_1");
        assertThat(result.references().get(0).text()).contains("第一段").doesNotContain("第二段");
        readService.recordReferences("thr_1", "turn_1", "ctxsnap_1", result.references());
        assertThat(referenceRepository.records).hasSize(1);
        assertThat(referenceRepository.records.get(0).snapshotId()).isEqualTo("ctxsnap_1");
    }

    private static final class InMemoryArtifactRepository implements MemoryArtifactRepository {
        private MemoryArtifactRecord record;

        @Override
        public void save(MemoryArtifactRecord record) {
            this.record = record;
        }

        @Override
        public Optional<MemoryArtifactRecord> findLatestByType(String artifactType) {
            return "MEMORY_SUMMARY".equals(artifactType) ? Optional.ofNullable(record) : Optional.empty();
        }

        @Override
        public List<MemoryArtifactRecord> listLatest(int limit) {
            return record == null ? List.of() : List.of(record);
        }
    }

    private static final class InMemoryReferenceRepository implements MemoryReferenceRepository {
        private final List<MemoryReferenceRecord> records = new ArrayList<>();

        @Override
        public void save(MemoryReferenceRecord record) {
            records.add(record);
        }
    }
}
