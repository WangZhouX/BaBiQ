package com.wzx.babiq.server.api.dto;

/**
 * memory/status 响应。
 *
 * @param enabled 长期记忆总开关
 * @param generateEnabled 后台生成开关
 * @param readEnabled 上下文注入开关
 * @param retrievalEnabled 长期记忆检索增强开关
 * @param rootDir Markdown 镜像根目录
 * @param pendingJobs 等待执行任务数
 * @param runningJobs 正在执行任务数
 * @param cleanCandidateCount 未归并 CLEAN 候选数
 * @param lastSummaryArtifactId 最近 memory_summary 产物 id
 * @param lastConsolidatedAt 最近归并完成时间
 * @param phase2Generation 最近 Phase2 generation
 * @param secretRiskCandidateCount 尚未归并且被 SECRET_RISK 隔离的候选数
 */
public record MemoryStatusResult(
        boolean enabled,
        boolean generateEnabled,
        boolean readEnabled,
        boolean retrievalEnabled,
        String rootDir,
        long pendingJobs,
        long runningJobs,
        long cleanCandidateCount,
        long secretRiskCandidateCount,
        String lastSummaryArtifactId,
        String lastConsolidatedAt,
        int phase2Generation
) {
}
