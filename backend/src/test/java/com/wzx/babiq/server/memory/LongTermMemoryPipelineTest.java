package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.memory.artifact.MemoryArtifactMirror;
import com.wzx.babiq.server.memory.extract.MemoryStageOneExtractor;
import com.wzx.babiq.server.memory.extract.MemoryStageOneRequest;
import com.wzx.babiq.server.memory.extract.MemoryStageOneResult;
import com.wzx.babiq.server.memory.redaction.MemoryPollutionStatus;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
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
 * 长期记忆流水线测试。
 *
 * <p>P3-4 的关键边界是：Phase1 不能只停留在“扫描并排队”，它必须能把 SQLite 中的真实会话片段抽取成候选，
 * 经过 Java 侧脱敏后再进入 Phase2 触发判断；这组测试用内存仓库隔离外部模型和文件系统，只验证流水线编排本身。</p>
 */
class LongTermMemoryPipelineTest {

    /** 固定时钟让 job/candidate 时间字段可预测，避免测试依赖当前系统时间。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T01:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Phase1 worker 会读取会话 item、抽取长期记忆、脱敏入库并触发 Phase2")
    void runNextPhase1_should_extract_redact_save_candidate_and_enqueue_phase2() {
        InMemoryJobRepository jobRepository = new InMemoryJobRepository();
        InMemoryCandidateRepository candidateRepository = new InMemoryCandidateRepository();
        InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        ThreadEntity thread = ThreadEntity.active("thr_memory", "记忆测试", "E:\\BaBiQ",
                "provider-a", "model-a", "WORKSPACE_WRITE", "ON_REQUEST", clock.instant());
        conversationRepository.thread = thread;
        conversationRepository.items.add(ItemRecord.of("item_1", "thr_memory", "turn_1", "message", 1,
                "{\"role\":\"user\",\"text\":\"以后请优先用中文解释。\"}", "created", clock.instant()));
        jobRepository.save(MemoryJobRecord.phase1Pending("memjob_phase1", "phase1:thr_memory:watermark",
                "thr_memory", "watermark", clock.instant(), clock.instant()));
        MemoryStatusService statusService = new MemoryStatusService(
                jobRepository,
                candidateRepository,
                artifactRepository,
                LongTermMemoryProperties.defaultsForTests().withPhase2TriggerOnCandidateCount(1));
        LongTermMemoryPipeline pipeline = newPipeline(
                conversationRepository,
                jobRepository,
                candidateRepository,
                artifactRepository,
                statusService,
                request -> new MemoryStageOneResult(
                        "用户偏好：以后请优先使用中文解释。api_key=sk-test-secret",
                        "本轮确认用户偏好中文说明。",
                        "thr_memory",
                        List.of("item_1")));

        Optional<MemoryJobRecord> result = pipeline.runNextPhase1();

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("SUCCEEDED");
        assertThat(candidateRepository.candidates).hasSize(1);
        MemoryCandidateRecord candidate = candidateRepository.candidates.get(0);
        assertThat(candidate.threadId()).isEqualTo("thr_memory");
        assertThat(candidate.providerId()).isEqualTo("provider-a");
        assertThat(candidate.model()).isEqualTo("model-a");
        assertThat(candidate.rawMemory()).contains("[REDACTED:api-key]").doesNotContain("sk-test-secret");
        assertThat(candidate.rolloutSummary()).contains("中文说明");
        assertThat(candidate.pollutionStatus()).isEqualTo(MemoryPollutionStatus.CLEAN);
        assertThat(jobRepository.jobs).anySatisfy(job -> {
            assertThat(job.jobType()).isEqualTo("PHASE2");
            assertThat(job.status()).isEqualTo("PENDING");
        });
    }

    @Test
    @DisplayName("Phase1 没有可抽取内容时标记 NO_OUTPUT，不写入候选")
    void runNextPhase1_should_mark_no_output_when_extractor_returns_empty_result() {
        InMemoryJobRepository jobRepository = new InMemoryJobRepository();
        InMemoryCandidateRepository candidateRepository = new InMemoryCandidateRepository();
        InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        InMemoryConversationRepository conversationRepository = new InMemoryConversationRepository();
        conversationRepository.thread = ThreadEntity.active("thr_empty", "空记忆测试", "E:\\BaBiQ",
                "provider-a", "model-a", "WORKSPACE_WRITE", "ON_REQUEST", clock.instant());
        jobRepository.save(MemoryJobRecord.phase1Pending("memjob_empty", "phase1:thr_empty:watermark",
                "thr_empty", "watermark", clock.instant(), clock.instant()));
        MemoryStatusService statusService = new MemoryStatusService(
                jobRepository,
                candidateRepository,
                artifactRepository,
                LongTermMemoryProperties.defaultsForTests());
        LongTermMemoryPipeline pipeline = newPipeline(
                conversationRepository,
                jobRepository,
                candidateRepository,
                artifactRepository,
                statusService,
                request -> MemoryStageOneResult.empty());

        Optional<MemoryJobRecord> result = pipeline.runNextPhase1();

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("NO_OUTPUT");
        assertThat(candidateRepository.candidates).isEmpty();
    }

    private LongTermMemoryPipeline newPipeline(
            ConversationRepository conversationRepository,
            MemoryJobRepository jobRepository,
            MemoryCandidateRepository candidateRepository,
            MemoryArtifactRepository artifactRepository,
            MemoryStatusService statusService,
            MemoryStageOneExtractor extractor) {
        MemoryPhase2TriggerService triggerService = new MemoryPhase2TriggerService(
                jobRepository,
                candidateRepository,
                statusService.properties(),
                clock);
        return new LongTermMemoryPipeline(
                triggerService,
                conversationRepository,
                jobRepository,
                candidateRepository,
                artifactRepository,
                new FakeConsolidationStrategy(),
                new MemoryArtifactMirror(new ApproximateContextTokenEstimator()),
                statusService,
                extractor,
                new MemorySecretRedactor(),
                clock);
    }

    /**
     * 测试用对话仓库，只实现 Phase1 需要的 thread/item 读取方法。
     */
    private static final class InMemoryConversationRepository implements ConversationRepository {
        private ThreadEntity thread;
        private final List<ItemRecord> items = new ArrayList<>();

        @Override
        public ThreadEntity createThread(String threadId, String title, String cwd, String providerId, String model,
                                         String sandboxMode, String approvalPolicy, Instant now) {
            thread = ThreadEntity.active(threadId, title, cwd, providerId, model, sandboxMode, approvalPolicy, now);
            return thread;
        }

        @Override
        public Optional<ThreadEntity> findThread(String threadId) {
            return thread != null && threadId.equals(thread.getThreadId()) ? Optional.of(thread) : Optional.empty();
        }

        @Override
        public List<ThreadEntity> listRecentThreads(String cwd, boolean includeArchived, int limit) {
            return thread == null ? List.of() : List.of(thread);
        }

        @Override
        public void archiveThread(String threadId, Instant archivedAt) {
        }

        @Override
        public void saveItem(ItemRecord record) {
            items.add(record);
        }

        @Override
        public Optional<ItemRecord> findItem(String itemId) {
            return items.stream()
                    .filter(item -> item.itemId().equals(itemId))
                    .findFirst();
        }

        @Override
        public Optional<ItemRecord> markItemRemoved(String itemId, Instant removedAt) {
            for (int index = 0; index < items.size(); index++) {
                ItemRecord existing = items.get(index);
                if (existing.itemId().equals(itemId)) {
                    ItemRecord removed = new ItemRecord(
                            existing.itemId(),
                            existing.threadId(),
                            existing.turnId(),
                            existing.type(),
                            existing.sequenceNo(),
                            existing.payloadJson(),
                            "removed",
                            existing.createdAt(),
                            removedAt);
                    items.set(index, removed);
                    return Optional.of(removed);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<ItemRecord> listItems(String threadId, int limit) {
            return items.stream()
                    .filter(item -> !"removed".equals(item.status()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ItemRecord> listItems(String threadId, int limit, String beforeItemId) {
            return listItems(threadId, limit);
        }

        @Override
        public long countItems(String threadId) {
            return items.stream()
                    .filter(item -> !"removed".equals(item.status()))
                    .count();
        }

        @Override
        public Optional<String> findLatestTurnStatus(String threadId) {
            return Optional.empty();
        }

        @Override
        public void saveTurnSummary(TurnSummaryRecord record) {
        }

        @Override
        public Optional<TurnSummaryRecord> findTurnSummary(String turnId) {
            return Optional.empty();
        }
    }

    /**
     * 测试用任务仓库，按 jobKey 覆盖保存，模拟 SQLite upsert 行为。
     */
    private static final class InMemoryJobRepository implements MemoryJobRepository {
        private final List<MemoryJobRecord> jobs = new ArrayList<>();

        @Override
        public int nextPhase2Generation() {
            return jobs.stream().filter(job -> "PHASE2".equals(job.jobType()))
                    .mapToInt(MemoryJobRecord::generation).max().orElse(0) + 1;
        }

        @Override
        public Optional<MemoryJobRecord> findActivePhase2() {
            return jobs.stream()
                    .filter(job -> "PHASE2".equals(job.jobType()))
                    .filter(job -> "PENDING".equals(job.status()) || "RUNNING".equals(job.status()))
                    .findFirst();
        }

        @Override
        public Optional<MemoryJobRecord> findPendingPhase1() {
            return jobs.stream()
                    .filter(job -> "PHASE1".equals(job.jobType()))
                    .filter(job -> "PENDING".equals(job.status()))
                    .findFirst();
        }

        @Override
        public void save(MemoryJobRecord record) {
            jobs.removeIf(existing -> existing.jobKey().equals(record.jobKey()));
            jobs.add(record);
        }

        @Override
        public List<MemoryJobRecord> listLatest(int limit) {
            return jobs.stream().limit(limit).toList();
        }
    }

    /**
     * 测试用候选仓库，Phase2 触发统计只计算 CLEAN 且未 selected 的候选。
     */
    private static final class InMemoryCandidateRepository implements MemoryCandidateRepository {
        private final List<MemoryCandidateRecord> candidates = new ArrayList<>();

        @Override
        public long countUnmergedCleanCandidates() {
            return candidates.stream()
                    .filter(candidate -> candidate.pollutionStatus() == MemoryPollutionStatus.CLEAN)
                    .filter(candidate -> !candidate.selectedForPhase2())
                    .count();
        }

        @Override
        public void save(MemoryCandidateRecord record) {
            candidates.add(record);
        }
    }

    private static final class InMemoryArtifactRepository implements MemoryArtifactRepository {
        @Override
        public void save(MemoryArtifactRecord record) {
        }

        @Override
        public Optional<MemoryArtifactRecord> findLatestByType(String artifactType) {
            return Optional.empty();
        }

        @Override
        public List<MemoryArtifactRecord> listLatest(int limit) {
            return List.of();
        }
    }

    private static final class FakeConsolidationStrategy implements MemoryConsolidationStrategy {
        @Override
        public String generateMemorySummary(List<MemoryCandidateRecord> candidates) {
            return "summary";
        }

        @Override
        public String generateMemoryHandbook(List<MemoryCandidateRecord> candidates) {
            return "handbook";
        }
    }
}
