package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * memory/artifacts/list 响应。
 */
public record MemoryArtifactsListResult(List<MemoryArtifactInfo> artifacts) {
}
