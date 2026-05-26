package com.wzx.babiq.server.context.compaction;

/**
 * 上下文预算策略参数。
 *
 * <p>P3-3 先把预算规则集中在一个不可变 record 中，后续如果要从数据库或设置页读取，
 * 只需要替换配置来源，不需要改动压缩判断算法。</p>
 *
 * @param maxContextWindowTokens BaBiQ 当前允许使用的最大模型上下文窗口，防止盲目相信超大模型声明
 * @param outputReserveRatio 为模型输出预留的比例，避免输入吃满窗口后无法生成回答
 * @param minOutputReserveTokens 小窗口模型的最小输出预留 token
 * @param maxOutputReserveTokens 大窗口模型的最大输出预留 token
 * @param safetyMarginRatio 额外安全余量比例，用来吸收 tokenizer 误差和工具 schema 波动
 * @param autoCompactRatio 输入预算达到该比例时触发自动压缩
 */
public record ContextBudgetProperties(
        int maxContextWindowTokens,
        double outputReserveRatio,
        int minOutputReserveTokens,
        int maxOutputReserveTokens,
        double safetyMarginRatio,
        double autoCompactRatio
) {

    /**
     * P3-3 默认预算：最大 1M，上下文超过输入预算 75% 自动压缩。
     *
     * @return 默认预算参数
     */
    public static ContextBudgetProperties defaults() {
        return new ContextBudgetProperties(
                1_000_000,
                0.10d,
                8_192,
                64_000,
                0.05d,
                0.75d);
    }

    /**
     * 校验预算参数，避免错误配置把模型输入预算算成负数或永不触发压缩。
     */
    public ContextBudgetProperties {
        if (maxContextWindowTokens <= 0) {
            throw new IllegalArgumentException("maxContextWindowTokens must be positive");
        }
        if (outputReserveRatio < 0 || outputReserveRatio >= 1) {
            throw new IllegalArgumentException("outputReserveRatio must be in [0, 1)");
        }
        if (minOutputReserveTokens < 0 || maxOutputReserveTokens < minOutputReserveTokens) {
            throw new IllegalArgumentException("output reserve range is invalid");
        }
        if (safetyMarginRatio < 0 || safetyMarginRatio >= 1) {
            throw new IllegalArgumentException("safetyMarginRatio must be in [0, 1)");
        }
        if (autoCompactRatio <= 0 || autoCompactRatio > 1) {
            throw new IllegalArgumentException("autoCompactRatio must be in (0, 1]");
        }
    }
}
