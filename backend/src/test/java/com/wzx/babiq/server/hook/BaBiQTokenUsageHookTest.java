package com.wzx.babiq.server.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BaBiQTokenUsageHook 测试。
 *
 * <p>该测试只验证本地累计逻辑，SAA AFTER_MODEL 的 state key 兼容性由后续集成测试兜底。</p>
 */
class BaBiQTokenUsageHookTest {

    @Test
    void initial_usage_is_zero() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();

        assertThat(hook.totalPromptTokens()).isZero();
        assertThat(hook.totalCompletionTokens()).isZero();
    }

    @Test
    void accumulate_across_calls() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();

        hook.record(100, 50);
        hook.record(20, 10);

        assertThat(hook.snapshot().promptTokens()).isEqualTo(120);
        assertThat(hook.snapshot().completionTokens()).isEqualTo(60);
        assertThat(hook.snapshot().total()).isEqualTo(180);
    }

    @Test
    void reset_clears_usage() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        hook.record(100, 50);

        hook.reset();

        assertThat(hook.totalPromptTokens()).isZero();
        assertThat(hook.totalCompletionTokens()).isZero();
    }

    @Test
    void negative_tokens_are_rejected() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();

        assertThatThrownBy(() -> hook.record(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
