package com.wzx.babiq.server.api.dto;

/**
 * memory/scan 响应。
 *
 * @param queuedPhase1Jobs 本次扫描新增入队的 Phase1 任务数；为 0 表示没有满足 idle 条件的 thread。
 * @param status 给桌面端展示的轻量状态，后续详细审计仍通过 memory/jobs/list 查看。
 */
public record MemoryScanResult(
        int queuedPhase1Jobs,
        String status
) {
}
