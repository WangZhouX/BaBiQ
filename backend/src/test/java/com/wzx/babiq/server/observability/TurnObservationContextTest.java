package com.wzx.babiq.server.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TurnObservationContextTest {

    @Test
    void context_should_accumulate_tokens_tools_and_duration() {
        AtomicLong now = new AtomicLong(1_000_000L);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus", now::get);

        context.recordTokens(100L, 50L);
        context.recordTokens(5L, 5L);
        context.recordToolCall("read_file");
        context.recordToolCall("read_file");
        now.set(3_500_000L);

        assertThat(context.promptTokens()).isEqualTo(105L);
        assertThat(context.completionTokens()).isEqualTo(55L);
        assertThat(context.totalTokens()).isEqualTo(160L);
        assertThat(context.toolCalls()).isEqualTo(2);
        assertThat(context.durationMs()).isEqualTo(2L);
    }
}
