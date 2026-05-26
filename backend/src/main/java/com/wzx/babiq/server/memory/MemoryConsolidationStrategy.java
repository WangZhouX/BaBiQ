package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;

import java.util.List;

/**
 * 长期记忆 Phase2 模型生成策略。
 *
 * <p>接口只让模型生成 memory_summary 和 MEMORY handbook；raw_memories 与 rollout_summaries
 * 由 Java 机械生成，减少 token 成本和结构化输出失败面。</p>
 */
public interface MemoryConsolidationStrategy {

    /** 生成 read path 使用的 dense 摘要。 */
    String generateMemorySummary(List<MemoryCandidateRecord> candidates);

    /** 生成供人和后续检索使用的主题手册。 */
    String generateMemoryHandbook(List<MemoryCandidateRecord> candidates);
}
