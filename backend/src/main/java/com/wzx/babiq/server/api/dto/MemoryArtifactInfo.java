package com.wzx.babiq.server.api.dto;

/**
 * 长期记忆产物列表项。
 */
public record MemoryArtifactInfo(
        String artifactId,
        String artifactType,
        String artifactPath,
        int version,
        int tokenEstimate,
        String createdAt
) {
}
