package com.wzx.babiq.server.memory.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.memory.repository.MemoryArtifactRecord;
import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 长期记忆 Markdown 镜像器。
 *
 * <p>这里刻意把 raw_memories 和 rollout_summaries 做成 Java 机械拼接：
 * 它们是审计材料，不需要模型改写。模型只参与 memory_summary 和 MEMORY handbook。</p>
 */
@Component
public class MemoryArtifactMirror {

    private static final Pattern UNSAFE_SLUG = Pattern.compile("[^A-Za-z0-9._-]+");

    /** token 预估器，用于给 read path 预算提供粗略统计。 */
    private final ContextTokenEstimator tokenEstimator;
    /** JSON 编码器，用于把 candidate ids 写入产物记录。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 Markdown 镜像器。
     *
     * @param tokenEstimator token 预估器
     */
    public MemoryArtifactMirror(ContextTokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 写入本次 Phase2 的全部 Markdown 镜像。
     *
     * @param request 镜像请求
     * @return 可落库的产物记录
     */
    public MemoryArtifactMirrorResult mirror(MemoryArtifactMirrorRequest request) {
        try {
            Files.createDirectories(request.rootDir());
            Files.createDirectories(request.rootDir().resolve("rollout_summaries"));
            List<MemoryCandidateRecord> orderedCandidates = request.candidates().stream()
                    .sorted(Comparator.comparing(MemoryCandidateRecord::threadId)
                            .thenComparing(MemoryCandidateRecord::createdAt)
                            .thenComparing(MemoryCandidateRecord::candidateId))
                    .toList();
            List<MemoryArtifactRecord> artifacts = new ArrayList<>();
            artifacts.add(writeArtifact(request, "RAW_MEMORIES", Path.of("raw_memories.md"),
                    rawMemories(orderedCandidates), orderedCandidates));
            for (MemoryCandidateRecord candidate : orderedCandidates) {
                String relativePath = "rollout_summaries/" + safeSlug(candidate) + ".md";
                artifacts.add(writeArtifact(request, "ROLLOUT_SUMMARY", Path.of(relativePath),
                        candidate.rolloutSummary(), List.of(candidate)));
            }
            artifacts.add(writeArtifact(request, "MEMORY_SUMMARY", Path.of("memory_summary.md"),
                    request.memorySummary(), orderedCandidates));
            artifacts.add(writeArtifact(request, "MEMORY_HANDBOOK", Path.of("MEMORY.md"),
                    request.memoryHandbook(), orderedCandidates));
            return new MemoryArtifactMirrorResult(List.copyOf(artifacts));
        } catch (Exception exception) {
            throw new IllegalStateException("长期记忆 Markdown 镜像写入失败", exception);
        }
    }

    private MemoryArtifactRecord writeArtifact(MemoryArtifactMirrorRequest request,
                                               String artifactType,
                                               Path relativePath,
                                               String content,
                                               List<MemoryCandidateRecord> candidates) throws Exception {
        String safeContent = content == null ? "" : content;
        Path target = request.rootDir().resolve(relativePath);
        Files.createDirectories(target.getParent() == null ? request.rootDir() : target.getParent());
        Files.writeString(target, safeContent, StandardCharsets.UTF_8);
        return new MemoryArtifactRecord(
                newArtifactId(),
                artifactType,
                relativePath.toString().replace('\\', '/'),
                sha256(safeContent),
                request.version(),
                request.sourceJobId(),
                objectMapper.writeValueAsString(candidates.stream().map(MemoryCandidateRecord::candidateId).toList()),
                safeContent,
                tokenEstimator.estimate(safeContent),
                request.now() == null ? Instant.now() : request.now(),
                request.now() == null ? Instant.now() : request.now());
    }

    private static String rawMemories(List<MemoryCandidateRecord> candidates) {
        StringBuilder builder = new StringBuilder("# Raw Memories\n\n");
        for (MemoryCandidateRecord candidate : candidates) {
            builder.append("## ").append(candidate.threadId()).append(" / ").append(candidate.candidateId()).append("\n\n");
            builder.append(candidate.rawMemory() == null ? "" : candidate.rawMemory()).append("\n\n");
        }
        return builder.toString();
    }

    private static String safeSlug(MemoryCandidateRecord candidate) {
        String slug = candidate.rolloutSlug();
        if (slug == null || slug.isBlank()) {
            slug = candidate.candidateId();
        }
        String normalized = UNSAFE_SLUG.matcher(slug.trim()).replaceAll("-");
        normalized = normalized.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? candidate.candidateId() : normalized;
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String newArtifactId() {
        return "memart_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
