package com.wzx.babiq.server.memory.extract;

import java.util.List;

/**
 * Phase1 抽取结果。
 *
 * @param rawMemory 面向后续 Phase2 的原始长期记忆候选文本；入库前还会经过 Java 侧脱敏
 * @param rolloutSummary 面向人工审计的本轮片段摘要；会镜像到 rollout_summaries 产物
 * @param rolloutSlug rollout summary 文件名使用的稳定短标识，通常来自 thread 或时间水位
 * @param sourceItemIds 参与抽取的 item id 列表，用于审计和复现
 */
public record MemoryStageOneResult(
        String rawMemory,
        String rolloutSummary,
        String rolloutSlug,
        List<String> sourceItemIds
) {

    /**
     * 表示该会话片段没有值得沉淀的长期记忆。
     */
    public static MemoryStageOneResult empty() {
        return new MemoryStageOneResult("", "", "", List.of());
    }

    /**
     * 判断结果是否包含可入库内容。
     */
    public boolean hasOutput() {
        return (rawMemory != null && !rawMemory.isBlank())
                || (rolloutSummary != null && !rolloutSummary.isBlank());
    }
}
