package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentLoop 单元测试。
 *
 * <p>只验证薄主流程：发 userMessage、调用 ReactAgent、发 agentMessage 并完成 turn。</p>
 */
class AgentLoopTest {

    @Test
    void invoke_emits_user_and_agent_messages() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        PendingApprovals pendingApprovals = new PendingApprovals();
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoop loop = new AgentLoop(strategy, pendingApprovals, summaryEmitter, new TurnObservationRegistry());
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);

        when(strategy.resolveModelName("provider-a")).thenReturn("mock-react");
        when(strategy.buildAgent(eq("provider-a"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(agent);
        when(strategy.buildConfig(eq("thr_1"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(RunnableConfig.builder().threadId("thr_1").build());
        when(agent.stream(any(String.class), any())).thenReturn(Flux.just(output));
        when(strategy.extractAssistantMessage(output)).thenReturn(new AssistantMessage("done"));

        loop.invoke(turn, "hello", "provider-a", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(emitted).extracting(ThreadItem::type).containsExactly("userMessage", "agentMessage");
        verify(summaryEmitter).emit(any(TurnObservationContext.class), eq(emitter), eq("completed"));
    }

    @Test
    void invoke_marks_turn_failed_when_agent_throws() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals(), summaryEmitter, new TurnObservationRegistry());
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        ItemEmitter emitter = mock(ItemEmitter.class);

        when(strategy.resolveModelName(null)).thenReturn("mock-react");
        when(strategy.buildAgent(eq(null), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(agent);
        when(strategy.buildConfig(eq("thr_1"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(RunnableConfig.builder().threadId("thr_1").build());
        when(agent.stream(any(String.class), any())).thenThrow(new GraphRunnerException("model down"));

        loop.invoke(turn, "hello", null, ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).contains("model down");
        verify(summaryEmitter).emit(any(TurnObservationContext.class), eq(emitter), eq("failed"));
    }

    @Test
    void failure_message_should_include_deepseek_response_body() {
        WebClientResponseException deepSeek400 = WebClientResponseException.create(
                400,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"missing tool response\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String message = AgentLoopSupport.failureMessage(new RuntimeException("wrapped", deepSeek400));

        assertThat(message)
                .contains("400 Bad Request")
                .contains("missing tool response");
    }

    @Test
    void invoke_uses_streaming_agent_path() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        PendingApprovals pendingApprovals = new PendingApprovals();
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoop loop = new AgentLoop(strategy, pendingApprovals, summaryEmitter, new TurnObservationRegistry());
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        RunnableConfig config = RunnableConfig.builder().threadId("thr_1").build();

        when(strategy.resolveModelName("provider-a")).thenReturn("mock-react");
        when(strategy.buildAgent(eq("provider-a"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(agent);
        when(strategy.buildConfig(eq("thr_1"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(config);
        when(agent.invokeAndGetOutput(any(String.class), any()))
                .thenThrow(new GraphRunnerException("不应再走非流式 Agent 调用"));
        when(agent.stream(eq("hello"), eq(config))).thenReturn(Flux.just(output));
        when(strategy.extractAssistantMessage(output)).thenReturn(new AssistantMessage("done"));

        loop.invoke(turn, "hello", "provider-a", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(emitted).extracting(ThreadItem::type).containsExactly("userMessage", "agentMessage");
        verify(agent).stream(eq("hello"), eq(config));
        verify(summaryEmitter).emit(any(TurnObservationContext.class), eq(emitter), eq("completed"));
    }

    @Test
    void invoke_emits_incremental_agent_message_snapshots() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        PendingApprovals pendingApprovals = new PendingApprovals();
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoop loop = new AgentLoop(strategy, pendingApprovals, summaryEmitter, new TurnObservationRegistry());
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        RunnableConfig config = RunnableConfig.builder().threadId("thr_1").build();

        when(strategy.resolveModelName("provider-a")).thenReturn("mock-react");
        when(strategy.buildAgent(eq("provider-a"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class)))
                .thenReturn(agent);
        when(strategy.buildConfig(eq("thr_1"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(config);
        when(agent.stream(eq("hello"), eq(config))).thenReturn(Flux.just(
                streamingChunk("你"),
                streamingChunk("好"),
                output));

        loop.invoke(turn, "hello", "provider-a", ".", emitter);

        List<String> agentTexts = emitted.stream()
                .filter(AgentMessageItem.class::isInstance)
                .map(AgentMessageItem.class::cast)
                .map(AgentMessageItem::text)
                .toList();
        assertThat(agentTexts).containsExactly("你", "你好", "你好");
        verify(strategy, org.mockito.Mockito.never()).extractAssistantMessage(any());
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emitted) throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemAdded(any(ThreadItem.class));
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemUpdated(any(ThreadItem.class));
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemCompleted(any(ThreadItem.class));
        return emitter;
    }

    private StreamingOutput<?> streamingChunk(String text) {
        return new StreamingOutput<>(new AssistantMessage(text), "model", "babiq_agent", new OverAllState());
    }
}
