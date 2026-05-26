package com.wzx.babiq.server.context.model;

/**
 * 短期压缩摘要。
 *
 * <p>短期摘要代表一段被压缩的历史区间，只能作为中优先级背景；它不能替代最新用户输入。</p>
 *
 * @param summaryId 摘要 id，后续持久化到 bq_context_summaries 后用于追溯
 * @param sourceItemRange 摘要覆盖的来源 item 范围
 * @param summary 摘要正文
 */
public record ShortTermSummary(
        String summaryId,
        String sourceItemRange,
        String summary
) {
}
