package com.wzx.babiq.server.memory.artifact;

import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Markdown 镜像生成请求。
 *
 * @param rootDir 长期记忆 Markdown 根目录
 * @param sourceJobId 来源 Phase2 job id
 * @param version 本次产物版本，通常等于 Phase2 generation
 * @param candidates 被选中的 CLEAN 候选
 * @param memorySummary 模型生成的 dense 摘要
 * @param memoryHandbook 模型生成的主题索引手册
 * @param now 产物生成时间
 */
public record MemoryArtifactMirrorRequest(
        Path rootDir,
        String sourceJobId,
        int version,
        List<MemoryCandidateRecord> candidates,
        String memorySummary,
        String memoryHandbook,
        Instant now
) {

    /**
     * 规整候选集合，保证镜像器可以直接遍历。
     */
    public MemoryArtifactMirrorRequest {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
