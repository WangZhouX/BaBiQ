package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolObservationInterceptorTest {

    @Test
    void interceptToolCall_should_record_tool_call_in_context_and_metrics() {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_1")
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("read_file", "call_1", "ok"));

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("read_file", 1L);
    }
}
