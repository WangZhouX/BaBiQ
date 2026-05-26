package com.wzx.babiq.server.context.model;

/**
 * 短期压缩摘要。
 *
 * <p>短期摘要代表一段被压缩的历史区间，只能作为中优先级背景；它不能替代最新用户输入。</p>
 *
 * @param summaryId 摘要 id，后续持久化到 bq_context_summaries 后用于追溯
 * @param sourceItemRange 摘要覆盖的来源 item 范围，给人读和 UI 展示使用
 * @param summary 摘要正文
 * @param sourceStartItemId 摘要覆盖的起点 item id，ContextAssembler 用它追踪被替换历史
 * @param sourceEndItemId 摘要覆盖的终点 item id，后续新历史从它之后继续注入模型
 */
public record ShortTermSummary(
        String summaryId,
        String sourceItemRange,
        String summary,
        String sourceStartItemId,
        String sourceEndItemId
) {

    /**
     * 兼容 P3-1/P3-2 中只关心摘要正文的测试和调用点。
     *
     * @param summaryId 摘要 id
     * @param sourceItemRange 摘要来源范围
     * @param summary 摘要正文
     */
    public ShortTermSummary(String summaryId, String sourceItemRange, String summary) {
        this(summaryId, sourceItemRange, summary, null, null);
    }
}
