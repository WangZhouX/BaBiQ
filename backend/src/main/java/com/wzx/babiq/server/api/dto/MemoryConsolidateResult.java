package com.wzx.babiq.server.api.dto;

/**
 * memory/consolidate 响应。
 *
 * @param queued 是否创建或已有可执行任务
 * @param jobId Phase2 job id
 * @param generation Phase2 generation
 * @param status 任务状态说明
 */
public record MemoryConsolidateResult(
        boolean queued,
        String jobId,
        int generation,
        String status
) {
}
