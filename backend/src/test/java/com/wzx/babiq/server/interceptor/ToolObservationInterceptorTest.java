package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
                ignored -> ToolCallResponse.of("call_1", "read_file", "ok"));

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("read_file", 1L);
    }

    @Test
    void interceptToolCall_should_emit_tool_detail_item_before_turn_summary() throws Exception {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_1", "turn_1", "provider-a", "qwen-plus");
        ItemEmitter emitter = mock(ItemEmitter.class);
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_1")
                .arguments("{\"path\":\"README.md\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, context,
                        BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_1", "read_file", "README content"));

        assertThat(response.getResult()).isEqualTo("README content");
        ArgumentCaptor<ThreadItem> captor = ArgumentCaptor.forClass(ThreadItem.class);
        verify(emitter).emitCommandExecution(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CommandExecutionItem.class);
        CommandExecutionItem item = (CommandExecutionItem) captor.getValue();
        assertThat(item.command()).contains("read_file").contains("README.md");
        assertThat(item.status()).isEqualTo("completed");
        assertThat(item.stdout()).contains("README content");
    }

    @Test
    void interceptToolCall_should_not_fail_agent_loop_when_persistence_is_unavailable() {
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolCallPersistenceService persistenceService = mock(ToolCallPersistenceService.class);
        doThrow(new IllegalStateException("foreign key missing in legacy in-memory turn"))
                .when(persistenceService)
                .recordStarted(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        ToolObservationInterceptor interceptor = new ToolObservationInterceptor(metrics, persistenceService);
        TurnObservationContext context = TurnObservationContext.start(
                "thr_legacy", "turn_legacy", "provider-a", "qwen-plus");
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call_legacy")
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, context))
                .build();

        ToolCallResponse response = interceptor.interceptToolCall(request,
                ignored -> ToolCallResponse.of("call_legacy", "read_file", "ok"));

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("read_file", 1L);
    }
}
