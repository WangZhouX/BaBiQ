package com.wzx.babiq.server.context.compaction;

import org.springframework.stereotype.Component;

/**
 * 模型上下文预算计算器。
 *
 * <p>Codex 的实现把模型上下文窗口和自动压缩阈值分开配置。BaBiQ 采用同样思路，
 * 但把策略封装在 Java 侧，保证 DeepSeek、OpenAI-compatible、DashScope 等模型都走同一套预算语义。</p>
 */
@Component
public class ContextBudgetPolicy {

    /** 当前运行使用的预算参数。 */
    private final ContextBudgetProperties properties;

    /**
     * 使用默认 P3-3 预算参数创建策略。
     */
    public ContextBudgetPolicy() {
        this(ContextBudgetProperties.defaults());
    }

    /**
     * 创建可测试、可替换配置的预算策略。
     *
     * @param properties 预算参数
     */
    public ContextBudgetPolicy(ContextBudgetProperties properties) {
        this.properties = properties == null ? ContextBudgetProperties.defaults() : properties;
    }

    /**
     * 根据模型声明窗口计算 BaBiQ 实际输入预算。
     *
     * @param requestedModelContextWindow 模型声明或 Provider 配置的上下文窗口
     * @return 预算结果
     */
    public ContextBudget calculate(int requestedModelContextWindow) {
        int requested = Math.max(1, requestedModelContextWindow);
        int effective = Math.min(requested, properties.maxContextWindowTokens());
        int rawReserve = (int) Math.floor(effective * properties.outputReserveRatio());
        int outputReserve = Math.max(properties.minOutputReserveTokens(),
                Math.min(properties.maxOutputReserveTokens(), rawReserve));
        outputReserve = Math.min(outputReserve, Math.max(0, effective - 1));
        int safetyMargin = (int) Math.floor(effective * properties.safetyMarginRatio());
        int inputBudget = Math.max(0, effective - outputReserve - safetyMargin);
        int threshold = (int) Math.floor(inputBudget * properties.autoCompactRatio());
        return new ContextBudget(
                requestedModelContextWindow,
                effective,
                outputReserve,
                safetyMargin,
                inputBudget,
                threshold);
    }
}
