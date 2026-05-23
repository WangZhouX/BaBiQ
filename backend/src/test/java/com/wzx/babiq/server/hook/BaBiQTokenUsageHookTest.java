package com.wzx.babiq.server.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.Map;

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

    @Test
    void afterModel_should_forward_usage_to_turn_observation_context() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        OverAllState state = new OverAllState(Map.of("usage", new DefaultUsage(100, 50)));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("thr_1")
                .addMetadata(TurnObservationContext.METADATA_KEY, context)
                .build();

        hook.afterModel(state, config).join();

        assertThat(context.promptTokens()).isEqualTo(100L);
        assertThat(context.completionTokens()).isEqualTo(50L);
    }
}
