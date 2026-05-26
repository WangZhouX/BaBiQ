package com.wzx.babiq.server.context.compaction;

/**
 * 单次模型调用的上下文预算结果。
 *
 * <p>该对象是运行时判断是否需要压缩的唯一输入，既能用于 Agent Loop，也能写入
 * bq_context_windows 作为审计快照。</p>
 *
 * @param requestedModelContextWindow Provider 或模型元数据声明的原始上下文窗口
 * @param effectiveModelContextWindow BaBiQ 实际采用的上下文窗口，已按系统上限裁剪
 * @param outputReserveTokens 输出预留 token
 * @param safetyMarginTokens 预算安全余量 token
 * @param inputBudgetTokens 可用于 prompt、历史、摘要、工具说明的输入 token 预算
 * @param autoCompactThresholdTokens 自动压缩触发阈值
 */
public record ContextBudget(
        int requestedModelContextWindow,
        int effectiveModelContextWindow,
        int outputReserveTokens,
        int safetyMarginTokens,
        int inputBudgetTokens,
        int autoCompactThresholdTokens
) {

    /**
     * 判断当前估算输入是否达到自动压缩阈值。
     *
     * @param estimatedInputTokens 当前 prompt 估算 token
     * @return true 表示下一轮应该先压缩旧历史
     */
    public boolean shouldCompact(int estimatedInputTokens) {
        return estimatedInputTokens >= autoCompactThresholdTokens;
    }
}
