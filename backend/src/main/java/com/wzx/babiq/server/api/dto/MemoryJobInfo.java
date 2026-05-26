package com.wzx.babiq.server.api.dto;

/**
 * 记忆任务列表项。
 */
public record MemoryJobInfo(
        String jobId,
        String jobType,
        String jobKey,
        int generation,
        String status,
        String createdAt
) {
}
