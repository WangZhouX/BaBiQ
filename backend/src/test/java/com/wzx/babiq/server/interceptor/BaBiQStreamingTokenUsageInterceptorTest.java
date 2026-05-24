package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 流式 token 用量拦截器测试。
 *
 * <p>真实 Provider 在 streaming 模式下通常会把 usage 放在最后一个 ChatResponse chunk。
 * 这个测试确保 BaBiQ 不需要关闭流式输出，也能在流结束后把 token 写入本轮观测上下文。</p>
 */
class BaBiQStreamingTokenUsageInterceptorTest {

    @Test
    void should_record_latest_stream_usage_when_stream_completes() {
        BaBiQStreamingTokenUsageInterceptor interceptor = new BaBiQStreamingTokenUsageInterceptor();
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "deepseek-v4-pro");
        ModelRequest request = ModelRequest.builder()
                .messages(List.of())
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("你好"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(12, 8))
                        .build());

        ModelRequest streamRequest = interceptor.beforeStreamCall(request);
        interceptor.onStreamChunk(response, streamRequest);

        assertThat(context.promptTokens()).isZero();
        assertThat(context.completionTokens()).isZero();

        interceptor.afterStreamComplete(new AssistantMessage("你好"), streamRequest);

        assertThat(context.promptTokens()).isEqualTo(12L);
        assertThat(context.completionTokens()).isEqualTo(8L);
        assertThat(context.totalTokens()).isEqualTo(20L);
    }

    @Test
    void should_ignore_usage_when_turn_context_is_missing() {
        BaBiQStreamingTokenUsageInterceptor interceptor = new BaBiQStreamingTokenUsageInterceptor();
        ModelRequest request = ModelRequest.builder()
                .messages(List.of())
                .context(Map.of())
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("你好"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(12, 8))
                        .build());

        ModelRequest streamRequest = interceptor.beforeStreamCall(request);
        interceptor.onStreamChunk(response, streamRequest);

        assertThatCode(() -> interceptor.afterStreamComplete(new AssistantMessage("你好"), streamRequest))
                .doesNotThrowAnyException();
    }
}
