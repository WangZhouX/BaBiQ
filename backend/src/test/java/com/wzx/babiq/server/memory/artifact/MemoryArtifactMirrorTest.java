package com.wzx.babiq.server.memory.artifact;

import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import com.wzx.babiq.server.memory.redaction.MemoryPollutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 镜像生成测试。
 *
 * <p>Codex 的长期记忆把 DB 作为事实源，同时生成可读 Markdown 镜像。BaBiQ 也采用这个方向：
 * raw_memories 和 rollout_summaries 由 Java 机械拼装，避免让模型重复生成可审计原文。</p>
 */
class MemoryArtifactMirrorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("镜像器应机械生成 raw_memories 和 rollout_summaries，并写入模型生成的摘要文件")
    void mirror_should_write_mechanical_and_model_artifacts() throws Exception {
        MemoryArtifactMirror mirror = new MemoryArtifactMirror(new ApproximateContextTokenEstimator());
        List<MemoryCandidateRecord> candidates = List.of(
                candidate("cand_b", "thr_b", "turn_b", "beta-run", "B raw", "B rollout"),
                candidate("cand_a", "thr_a", "turn_a", "alpha/run", "A raw", "A rollout")
        );

        MemoryArtifactMirrorResult result = mirror.mirror(new MemoryArtifactMirrorRequest(
                tempDir,
                "job_phase2_1",
                1,
                candidates,
                "dense summary",
                "keyword handbook",
                Instant.parse("2026-05-27T00:00:00Z")));

        assertThat(Files.readString(tempDir.resolve("raw_memories.md")))
                .contains("cand_a")
                .contains("A raw")
                .contains("cand_b")
                .contains("B raw");
        assertThat(Files.readString(tempDir.resolve("rollout_summaries").resolve("alpha-run.md")))
                .contains("A rollout");
        assertThat(Files.readString(tempDir.resolve("memory_summary.md"))).contains("dense summary");
        assertThat(Files.readString(tempDir.resolve("MEMORY.md"))).contains("keyword handbook");
        assertThat(result.artifacts())
                .extracting(MemoryArtifactRecord::artifactType)
                .contains("RAW_MEMORIES", "ROLLOUT_SUMMARY", "MEMORY_SUMMARY", "MEMORY_HANDBOOK");
    }

    private static MemoryCandidateRecord candidate(
            String candidateId,
            String threadId,
            String turnId,
            String slug,
            String rawMemory,
            String rolloutSummary) {
        return new MemoryCandidateRecord(
                candidateId,
                threadId,
                turnId,
                "job_1",
                "E:\\BaBiQ",
                "deepseek",
                "deepseek-v4-pro",
                rawMemory,
                rolloutSummary,
                slug,
                "[\"it_1\"]",
                "ctxsnap_1",
                MemoryPollutionStatus.CLEAN,
                0,
                false,
                null,
                0,
                null,
                Instant.parse("2026-05-27T00:00:00Z"),
                Instant.parse("2026-05-27T00:00:00Z"));
    }
}
