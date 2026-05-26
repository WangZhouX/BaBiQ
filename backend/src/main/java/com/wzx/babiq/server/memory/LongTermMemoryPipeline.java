package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.api.dto.MemoryConsolidateResult;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.memory.artifact.MemoryArtifactMirror;
import com.wzx.babiq.server.memory.artifact.MemoryArtifactMirrorRequest;
import com.wzx.babiq.server.memory.artifact.MemoryArtifactMirrorResult;
import com.wzx.babiq.server.memory.extract.MemoryStageOneExtractor;
import com.wzx.babiq.server.memory.extract.MemoryStageOneRequest;
import com.wzx.babiq.server.memory.extract.MemoryStageOneResult;
import com.wzx.babiq.server.memory.redaction.MemoryPollutionStatus;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactionResult;
import com.wzx.babiq.server.memory.redaction.MemorySecretRedactor;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 长期记忆后台流水线。
 *
 * <p>流水线分为两个阶段：Phase1 从 idle 会话中抽取候选，Phase2 把 CLEAN 候选归并为 Markdown 产物。
 * 这里保留 Codex 的低成本异步思路，但所有事实源、任务状态和产物索引都落在 BaBiQ SQLite 中，方便审计和恢复。</p>
 */
@Service
public class LongTermMemoryPipeline {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryPipeline.class);

    /** Phase2 触发服务，负责阈值、防抖和 generation job_key。 */
    private final MemoryPhase2TriggerService triggerService;
    /** 对话仓库，Phase1 从这里读取真实 thread 和 item。 */
    private final ConversationRepository conversationRepository;
    /** 任务仓库，Phase1/Phase2 共用同一张 job 表。 */
    private final MemoryJobRepository jobRepository;
    /** 候选仓库，保存 Phase1 产出的长期记忆事实单元。 */
    private final MemoryCandidateRepository candidateRepository;
    /** 产物仓库，保存 Phase2 生成的 Markdown 镜像索引。 */
    private final MemoryArtifactRepository artifactRepository;
    /** 模型归并策略，只负责生成需要语义压缩的 summary 和 handbook。 */
    private final MemoryConsolidationStrategy consolidationStrategy;
    /** Markdown 镜像器，把 Java 拼接和模型输出写到本地 memory 目录。 */
    private final MemoryArtifactMirror artifactMirror;
    /** 运行时状态服务，提供开关、阈值和 rootDir。 */
    private final MemoryStatusService statusService;
    /** Phase1 抽取器，生产环境使用 Spring AI structured output，测试可注入 fake。 */
    private final MemoryStageOneExtractor stageOneExtractor;
    /** 入库前的 Java 侧脱敏器，作为模型自我约束之外的硬防线。 */
    private final MemorySecretRedactor secretRedactor;
    /** 时钟由测试注入固定值，生产环境使用系统 UTC 时间。 */
    private final Clock clock;

    /**
     * 创建生产环境长期记忆流水线。
     */
    @Autowired
    public LongTermMemoryPipeline(MemoryPhase2TriggerService triggerService,
                                  ConversationRepository conversationRepository,
                                  MemoryJobRepository jobRepository,
                                  MemoryCandidateRepository candidateRepository,
                                  MemoryArtifactRepository artifactRepository,
                                  MemoryConsolidationStrategy consolidationStrategy,
                                  MemoryArtifactMirror artifactMirror,
                                  MemoryStatusService statusService,
                                  MemoryStageOneExtractor stageOneExtractor,
                                  MemorySecretRedactor secretRedactor) {
        this(triggerService, conversationRepository, jobRepository, candidateRepository, artifactRepository,
                consolidationStrategy, artifactMirror, statusService, stageOneExtractor, secretRedactor,
                Clock.systemUTC());
    }

    /**
     * 测试构造器，允许注入固定时钟。
     */
    LongTermMemoryPipeline(MemoryPhase2TriggerService triggerService,
                           ConversationRepository conversationRepository,
                           MemoryJobRepository jobRepository,
                           MemoryCandidateRepository candidateRepository,
                           MemoryArtifactRepository artifactRepository,
                           MemoryConsolidationStrategy consolidationStrategy,
                           MemoryArtifactMirror artifactMirror,
                           MemoryStatusService statusService,
                           MemoryStageOneExtractor stageOneExtractor,
                           MemorySecretRedactor secretRedactor,
                           Clock clock) {
        this.triggerService = triggerService;
        this.conversationRepository = conversationRepository;
        this.jobRepository = jobRepository;
        this.candidateRepository = candidateRepository;
        this.artifactRepository = artifactRepository;
        this.consolidationStrategy = consolidationStrategy;
        this.artifactMirror = artifactMirror;
        this.statusService = statusService;
        this.stageOneExtractor = stageOneExtractor;
        this.secretRedactor = secretRedactor;
        this.clock = clock;
    }

    /**
     * 手动或自动触发 Phase2。
     *
     * @param force 是否绕过最小间隔和候选数阈值
     * @return 排队结果
     */
    public MemoryConsolidateResult consolidate(boolean force) {
        Optional<MemoryJobRecord> job = triggerService.enqueueIfNeeded(force);
        return job.map(value -> new MemoryConsolidateResult(true, value.jobId(), value.generation(), "QUEUED"))
                .orElseGet(() -> new MemoryConsolidateResult(false, null, 0, "SKIPPED"));
    }

    /**
     * 扫描已空闲的会话并创建 Phase1 抽取任务。
     *
     * <p>扫描只创建可审计 job，不直接调用模型；真正的模型调用由 {@link #runNextPhase1()} 领取 PENDING job 后执行。
     * 这样可以避免用户连续对话时产生热回路，也便于失败重试和后台限流。</p>
     *
     * @return 本次新建或刷新 Phase1 任务数量
     */
    public int scanPhase1() {
        LongTermMemoryProperties properties = statusService.properties();
        if (!properties.enabled() || !properties.generateEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minusMillis(properties.phase1MinIdleMillis());
        return (int) conversationRepository.listRecentThreads(null, false, 100).stream()
                .filter(thread -> !"DISABLED".equalsIgnoreCase(thread.getMemoryMode()))
                .filter(thread -> thread.getUpdatedAt() != null && Instant.parse(thread.getUpdatedAt()).isBefore(cutoff))
                .limit(properties.phase1MaxThreadsPerScan())
                .peek(thread -> {
                    String jobKey = "phase1:" + thread.getThreadId() + ":" + thread.getUpdatedAt();
                    MemoryJobRecord job = MemoryJobRecord.phase1Pending(newJobId(), jobKey,
                            thread.getThreadId(), thread.getUpdatedAt(), now, now);
                    jobRepository.save(job);
                })
                .count();
    }

    /**
     * 处理一个待执行 Phase1 job。
     *
     * <p>这个 worker 才是真正会调用模型的入口。它先从 SQLite 会话仓库取真实 item，再调用抽取器，
     * 最后用 Java 脱敏器清洗后写入候选。只有 CLEAN 候选会触发 Phase2 入队检查，SECRET_RISK 候选保留审计但不参与归并。</p>
     */
    @Transactional
    public Optional<MemoryJobRecord> runNextPhase1() {
        Optional<MemoryJobRecord> pending = jobRepository.findPendingPhase1();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        MemoryJobRecord running = jobRepository.markRunning(pending.get(), newWorkerId(),
                now.plusSeconds(600), now);
        try {
            Optional<ThreadEntity> maybeThread = conversationRepository.findThread(running.threadId());
            if (maybeThread.isEmpty()) {
                MemoryJobRecord noOutput = running.withStatus("NO_OUTPUT", clock.instant());
                jobRepository.save(noOutput);
                return Optional.of(noOutput);
            }
            ThreadEntity thread = maybeThread.get();
            List<ItemRecord> items = conversationRepository.listItems(thread.getThreadId(), 200);
            MemoryStageOneResult extraction = stageOneExtractor.extract(new MemoryStageOneRequest(
                    thread.getThreadId(),
                    thread.getCwd(),
                    thread.getProviderId(),
                    thread.getModel(),
                    statusService.properties().phase1FallbackTokenLimit(),
                    items));
            if (extraction == null || !extraction.hasOutput()) {
                MemoryJobRecord noOutput = running.withStatus("NO_OUTPUT", clock.instant());
                jobRepository.save(noOutput);
                return Optional.of(noOutput);
            }
            MemoryCandidateRecord candidate = toCandidate(running, thread, extraction, items);
            candidateRepository.save(candidate);
            MemoryJobRecord completed = running.withStatus(
                    candidate.pollutionStatus() == MemoryPollutionStatus.CLEAN ? "SUCCEEDED" : "SKIPPED_POLLUTED",
                    clock.instant());
            jobRepository.save(completed);
            if (candidate.pollutionStatus() == MemoryPollutionStatus.CLEAN) {
                triggerService.enqueueIfNeeded(false);
            }
            return Optional.of(completed);
        } catch (Exception exception) {
            log.warn("长期记忆 Phase1 执行失败: jobId={}, reason={}: {}",
                    running.jobId(), exception.getClass().getSimpleName(), exception.getMessage());
            MemoryJobRecord failed = running.withStatus("FAILED", clock.instant());
            jobRepository.save(failed);
            return Optional.of(failed);
        }
    }

    /**
     * 处理一个待执行 Phase2 job。
     */
    @Transactional
    public Optional<MemoryJobRecord> runNextPhase2() {
        Optional<MemoryJobRecord> pending = jobRepository.findPendingPhase2();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        MemoryJobRecord running = jobRepository.markRunning(pending.get(), newWorkerId(),
                clock.instant().plusSeconds(600), clock.instant());
        try {
            List<MemoryCandidateRecord> candidates = candidateRepository.selectForPhase2(
                    statusService.properties().phase2MaxCandidates());
            if (candidates.isEmpty()) {
                MemoryJobRecord succeeded = running.withStatus("SUCCEEDED", clock.instant());
                jobRepository.save(succeeded);
                return Optional.of(succeeded);
            }
            String summary = consolidationStrategy.generateMemorySummary(candidates);
            String handbook = consolidationStrategy.generateMemoryHandbook(candidates);
            MemoryArtifactMirrorResult mirrorResult = artifactMirror.mirror(new MemoryArtifactMirrorRequest(
                    statusService.properties().rootDir(),
                    running.jobId(),
                    running.generation(),
                    candidates,
                    summary,
                    handbook,
                    clock.instant()));
            mirrorResult.artifacts().forEach(artifactRepository::save);
            candidateRepository.markSelected(candidates.stream().map(MemoryCandidateRecord::candidateId).toList(),
                    clock.instant());
            MemoryJobRecord succeeded = running.withStatus("SUCCEEDED", clock.instant());
            jobRepository.save(succeeded);
            return Optional.of(succeeded);
        } catch (Exception exception) {
            log.warn("长期记忆 Phase2 执行失败: jobId={}, reason={}: {}",
                    running.jobId(), exception.getClass().getSimpleName(), exception.getMessage());
            MemoryJobRecord failed = running.withStatus("FAILED", clock.instant());
            jobRepository.save(failed);
            return Optional.of(failed);
        }
    }

    private MemoryCandidateRecord toCandidate(MemoryJobRecord job,
                                              ThreadEntity thread,
                                              MemoryStageOneResult extraction,
                                              List<ItemRecord> fallbackItems) {
        MemorySecretRedactionResult rawRedaction = secretRedactor.redact(nullToBlank(extraction.rawMemory()));
        MemorySecretRedactionResult summaryRedaction = secretRedactor.redact(nullToBlank(extraction.rolloutSummary()));
        MemoryPollutionStatus pollutionStatus = rawRedaction.pollutionStatus() == MemoryPollutionStatus.SECRET_RISK
                || summaryRedaction.pollutionStatus() == MemoryPollutionStatus.SECRET_RISK
                ? MemoryPollutionStatus.SECRET_RISK
                : MemoryPollutionStatus.CLEAN;
        Instant now = clock.instant();
        List<String> sourceIds = extraction.sourceItemIds() == null || extraction.sourceItemIds().isEmpty()
                ? fallbackItems.stream().map(ItemRecord::itemId).toList()
                : extraction.sourceItemIds();
        return new MemoryCandidateRecord(
                "memcand_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                thread.getThreadId(),
                job.turnId(),
                job.jobId(),
                thread.getCwd(),
                thread.getProviderId(),
                thread.getModel(),
                rawRedaction.redactedText(),
                summaryRedaction.redactedText(),
                slugOrDefault(extraction.rolloutSlug(), thread.getThreadId()),
                toJsonArray(sourceIds),
                null,
                pollutionStatus,
                rawRedaction.redactionCount() + summaryRedaction.redactionCount(),
                false,
                null,
                0,
                null,
                now,
                now);
    }

    private static String newWorkerId() {
        return "memory-worker-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String newJobId() {
        return "memjob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String nullToBlank(String text) {
        return text == null ? "" : text;
    }

    private static String slugOrDefault(String slug, String fallback) {
        String source = slug == null || slug.isBlank() ? fallback : slug;
        return source.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
