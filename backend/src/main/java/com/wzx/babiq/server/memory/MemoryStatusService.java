package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.api.dto.MemoryArtifactInfo;
import com.wzx.babiq.server.api.dto.MemoryArtifactsListResult;
import com.wzx.babiq.server.api.dto.MemoryJobInfo;
import com.wzx.babiq.server.api.dto.MemoryJobsListResult;
import com.wzx.babiq.server.api.dto.MemorySettingsSetResult;
import com.wzx.babiq.server.api.dto.MemoryStatusResult;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRepository;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRepository;
import com.wzx.babiq.server.memory.repository.MemoryJobRecord;
import com.wzx.babiq.server.memory.repository.MemoryJobRepository;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 长期记忆状态查询服务。
 *
 * <p>它把 SQLite 任务/候选/产物聚合成桌面端需要的轻量状态，同时维护运行期开关。
 * P3-4 先用内存覆盖配置开关，后续可再落到 AppSetting。</p>
 */
@Service
public class MemoryStatusService {

    /** 任务仓库。 */
    private final MemoryJobRepository jobRepository;
    /** 候选仓库。 */
    private final MemoryCandidateRepository candidateRepository;
    /** 产物仓库。 */
    private final MemoryArtifactRepository artifactRepository;
    /** 当前生效配置，设置页修改后写入这里。 */
    private final AtomicReference<LongTermMemoryProperties> currentProperties;

    /**
     * 创建长期记忆状态服务。
     */
    public MemoryStatusService(MemoryJobRepository jobRepository,
                               MemoryCandidateRepository candidateRepository,
                               MemoryArtifactRepository artifactRepository,
                               LongTermMemoryProperties properties) {
        this.jobRepository = jobRepository;
        this.candidateRepository = candidateRepository;
        this.artifactRepository = artifactRepository;
        this.currentProperties = new AtomicReference<>(properties);
    }

    /**
     * 当前有效长期记忆配置。
     */
    public LongTermMemoryProperties properties() {
        return currentProperties.get();
    }

    /**
     * 查询状态摘要。
     */
    public MemoryStatusResult status() {
        LongTermMemoryProperties properties = currentProperties.get();
        MemoryArtifactRecord latestSummary = artifactRepository.findLatestByType("MEMORY_SUMMARY").orElse(null);
        int generation = jobRepository.nextPhase2Generation() - 1;
        return new MemoryStatusResult(
                properties.enabled(),
                properties.generateEnabled(),
                properties.readEnabled(),
                properties.retrievalEnabled(),
                properties.rootDir().toString(),
                jobRepository.countByStatus("PENDING"),
                jobRepository.countByStatus("RUNNING"),
                candidateRepository.countUnmergedCleanCandidates(),
                candidateRepository.countUnmergedSecretRiskCandidates(),
                latestSummary == null ? null : latestSummary.artifactId(),
                latestSummary == null ? null : latestSummary.createdAt().toString(),
                Math.max(0, generation));
    }

    /**
     * 局部更新长期记忆开关。
     */
    public MemorySettingsSetResult updateSettings(Boolean enabled,
                                                  Boolean generateEnabled,
                                                  Boolean readEnabled,
                                                  Boolean retrievalEnabled) {
        LongTermMemoryProperties updated = currentProperties.updateAndGet(
                properties -> properties.withSwitches(enabled, generateEnabled, readEnabled, retrievalEnabled));
        return new MemorySettingsSetResult(updated.enabled(), updated.generateEnabled(),
                updated.readEnabled(), updated.retrievalEnabled());
    }

    /**
     * 最近记忆任务列表。
     */
    public MemoryJobsListResult jobs(int limit) {
        return new MemoryJobsListResult(jobRepository.listLatest(limit).stream()
                .map(this::toJobInfo)
                .toList());
    }

    /**
     * 最近记忆产物列表。
     */
    public MemoryArtifactsListResult artifacts(int limit) {
        return new MemoryArtifactsListResult(artifactRepository.listLatest(limit).stream()
                .map(this::toArtifactInfo)
                .toList());
    }

    private MemoryJobInfo toJobInfo(MemoryJobRecord record) {
        return new MemoryJobInfo(record.jobId(), record.jobType(), record.jobKey(), record.generation(),
                record.status(), record.createdAt() == null ? null : record.createdAt().toString());
    }

    private MemoryArtifactInfo toArtifactInfo(MemoryArtifactRecord record) {
        return new MemoryArtifactInfo(record.artifactId(), record.artifactType(), record.artifactPath(),
                record.version(), record.tokenEstimate(), record.createdAt() == null ? null : record.createdAt().toString());
    }
}
