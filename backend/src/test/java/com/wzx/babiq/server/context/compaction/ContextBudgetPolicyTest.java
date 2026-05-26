package com.wzx.babiq.server.context.compaction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-3 上下文预算策略测试。
 *
 * <p>预算策略负责把模型声明的 context window 转成 BaBiQ 可控的输入预算和自动压缩阈值。
 * 它不直接调用模型，也不读取数据库，因此可以用纯单元测试固定边界。</p>
 */
class ContextBudgetPolicyTest {

    @Test
    void calculate_should_cap_large_model_window_and_trigger_at_seventy_five_percent() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(ContextBudgetProperties.defaults());

        ContextBudget budget = policy.calculate(2_000_000);

        assertThat(budget.effectiveModelContextWindow()).isEqualTo(1_000_000);
        assertThat(budget.outputReserveTokens()).isEqualTo(64_000);
        assertThat(budget.safetyMarginTokens()).isEqualTo(50_000);
        assertThat(budget.inputBudgetTokens()).isEqualTo(886_000);
        assertThat(budget.autoCompactThresholdTokens()).isEqualTo(664_500);
        assertThat(budget.shouldCompact(664_499)).isFalse();
        assertThat(budget.shouldCompact(664_500)).isTrue();
    }

    @Test
    void calculate_should_keep_enough_output_reserve_for_small_windows() {
        ContextBudgetPolicy policy = new ContextBudgetPolicy(ContextBudgetProperties.defaults());

        ContextBudget budget = policy.calculate(32_768);

        assertThat(budget.effectiveModelContextWindow()).isEqualTo(32_768);
        assertThat(budget.outputReserveTokens()).isEqualTo(8_192);
        assertThat(budget.safetyMarginTokens()).isEqualTo(1_638);
        assertThat(budget.inputBudgetTokens()).isEqualTo(22_938);
        assertThat(budget.autoCompactThresholdTokens()).isEqualTo(17_203);
    }
}
