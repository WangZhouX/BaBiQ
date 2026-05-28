package com.wzx.babiq.server.memory.retrieval;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.memory.LongTermMemoryProperties;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆检索服务测试。
 *
 * <p>P3-5 在 P3-4 summary read path 之外只补充少量高相关片段，且必须写入检索审计。</p>
 */
class LongTermMemoryRetrievalServiceTest {

    @Test
    @DisplayName("检索服务按 query 从 artifact 中选择相关片段并记录审计")
    void retrieve_should_select_relevant_artifacts_and_record_event() {
        InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        InMemoryRetrievalEventRepository eventRepository = new InMemoryRetrievalEventRepository();
        artifactRepository.save(artifact("memart_1", "MEMORY_SUMMARY", "用户喜欢中文 commit，并要求使用 Spring AI。"));
        artifactRepository.save(artifact("memart_2", "MEMORY_HANDBOOK", "权限切换必须影响后端 agent，不只是 UI 高亮。"));
        LongTermMemoryRetrievalService service = new LongTermMemoryRetrievalService(
                artifactRepository,
                eventRepository,
                new ApproximateContextTokenEstimator(),
                LongTermMemoryProperties.defaultsForTests().withRetrievalEnabled(true));

        LongTermMemoryRetrievalResult result = service.retrieve(
                "thr_1", "turn_1", "ctxsnap_1", "后端 权限 agent", 32_768);

        assertThat(result.references()).hasSize(1);
        assertThat(result.references().get(0).artifactId()).isEqualTo("memart_2");
        assertThat(result.tokenEstimate()).isGreaterThan(0);
        assertThat(eventRepository.events).hasSize(1);
        assertThat(eventRepository.events.get(0).selectedReferencesJson()).contains("memart_2");
    }

    @Test
    @DisplayName("预览检索不写入需要 thread/turn 的注入审计")
    void retrieve_preview_should_not_record_injection_audit_event() {
        InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        InMemoryRetrievalEventRepository eventRepository = new InMemoryRetrievalEventRepository();
        artifactRepository.save(artifact("memart_1", "MEMORY_SUMMARY", "html 页面和当前工作目录相关。"));
        LongTermMemoryRetrievalService service = new LongTermMemoryRetrievalService(
                artifactRepository,
                eventRepository,
                new ApproximateContextTokenEstimator(),
                LongTermMemoryProperties.defaultsForTests().withRetrievalEnabled(true));

        LongTermMemoryRetrievalResult result = service.retrievePreview("html", 32_768);

        assertThat(result.references()).hasSize(1);
        assertThat(eventRepository.events).isEmpty();
    }

    private static MemoryArtifactRecord artifact(String id, String type, String text) {
        return new MemoryArtifactRecord(id, type, Path.of(id + ".md").toString(), "hash",
                1, "job_1", "[]", text, 100,
                Instant.parse("2026-05-27T00:00:00Z"),
                Instant.parse("2026-05-27T00:00:00Z"));
    }

    private static final class InMemoryArtifactRepository implements MemoryArtifactRepository {
        private final List<MemoryArtifactRecord> records = new ArrayList<>();

        @Override
        public void save(MemoryArtifactRecord record) {
            records.add(record);
        }

        @Override
        public Optional<MemoryArtifactRecord> findLatestByType(String artifactType) {
            return records.stream().filter(record -> record.artifactType().equals(artifactType)).findFirst();
        }

        @Override
        public List<MemoryArtifactRecord> listLatest(int limit) {
            return records.stream().limit(limit).toList();
        }
    }

    private static final class InMemoryRetrievalEventRepository implements MemoryRetrievalEventRepository {
        private final List<MemoryRetrievalEventRecord> events = new ArrayList<>();

        @Override
        public void save(MemoryRetrievalEventRecord record) {
            events.add(record);
        }
    }
}
