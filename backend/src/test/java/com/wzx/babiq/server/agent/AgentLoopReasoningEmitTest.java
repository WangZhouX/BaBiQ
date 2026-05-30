package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.DeepSeekV4OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证模型 thinking/reasoning 内容会作为独立展示 item 输出。
 *
 * <p>P5 的关键边界是：Spring AI 把供应商返回的 reasoningContent 放在
 * {@link AssistantMessage#getMetadata()}，BaBiQ 需要把它转成 ReasoningItem 给 UI
 * 折叠展示，但不能混进普通 assistant 正文或后续上下文。</p>
 */
class AgentLoopReasoningEmitTest {

    @Test
    void handleOutput_should_emit_reasoning_before_final_assistant_message() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoopOutputHandler handler = new AgentLoopOutputHandler(
                strategy,
                new PendingApprovals(),
                summaryEmitter,
                new TurnObservationRegistry());
        Turn turn = startedTurn();
        NodeOutput output = mock(NodeOutput.class);
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        AssistantMessage assistantMessage = assistantMessage("最终回答", "先检查目录，再读取 index.html。");
        when(strategy.extractAssistantMessage(output)).thenReturn(assistantMessage);

        handler.handleOutput(
                turn,
                emitter,
                new AgentStreamConsumer.StreamResult(Optional.of(output), null, ""),
                TurnObservationContext.start(turn.id(), "thr_1", ".", "deepseek-v4-pro"),
                ".",
                null,
                null);

        assertThat(emitted).extracting(ThreadItem::type)
                .containsExactly("reasoning", "agentMessage");
        assertThat(emitted.getFirst())
                .isInstanceOfSatisfying(ReasoningItem.class,
                        item -> assertThat(item.text()).isEqualTo("先检查目录，再读取 index.html。"));
        assertThat(emitted.get(1))
                .isInstanceOfSatisfying(AgentMessageItem.class,
                        item -> assertThat(item.text()).isEqualTo("最终回答"));
        verify(summaryEmitter).emit(any(TurnObservationContext.class), any(ItemEmitter.class), org.mockito.ArgumentMatchers.eq("completed"));
    }

    @Test
    void handleOutput_should_not_emit_reasoning_when_metadata_is_blank() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoopOutputHandler handler = new AgentLoopOutputHandler(
                strategy,
                new PendingApprovals(),
                summaryEmitter,
                new TurnObservationRegistry());
        Turn turn = startedTurn();
        NodeOutput output = mock(NodeOutput.class);
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        when(strategy.extractAssistantMessage(output)).thenReturn(assistantMessage("最终回答", "  "));

        handler.handleOutput(
                turn,
                emitter,
                new AgentStreamConsumer.StreamResult(Optional.of(output), null, ""),
                TurnObservationContext.start(turn.id(), "thr_1", ".", "deepseek-v4-pro"),
                ".",
                null,
                null);

        assertThat(emitted).extracting(ThreadItem::type)
                .containsExactly("agentMessage");
    }

    @Test
    void consume_should_emit_streaming_reasoning_before_streaming_assistant_text() throws Exception {
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);

        AgentStreamConsumer.consume(reactor.core.publisher.Flux.just(
                streamingChunk(assistantMessage("你好", "我先理解用户意图。")),
                streamingChunk(assistantMessage("，已完成", "我先理解用户意图，然后准备回答。"))
        ), emitter);

        assertThat(emitted).extracting(ThreadItem::type)
                .containsExactly("reasoning", "agentMessage", "reasoning", "agentMessage");
        assertThat(emitted.getFirst())
                .isInstanceOfSatisfying(ReasoningItem.class,
                        item -> assertThat(item.text()).isEqualTo("我先理解用户意图。"));
        assertThat(emitted.get(2))
                .isInstanceOfSatisfying(ReasoningItem.class,
                        item -> assertThat(item.text()).isEqualTo("我先理解用户意图，然后准备回答。"));
    }

    @Test
    void reasoning_text_should_be_truncated_before_emit() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        AgentLoopOutputHandler handler = new AgentLoopOutputHandler(
                strategy,
                new PendingApprovals(),
                mock(TurnSummaryEmitter.class),
                new TurnObservationRegistry());
        Turn turn = startedTurn();
        NodeOutput output = mock(NodeOutput.class);
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        String longReasoning = "推理".repeat(8_000);
        when(strategy.extractAssistantMessage(output)).thenReturn(assistantMessage("最终回答", longReasoning));

        handler.handleOutput(
                turn,
                emitter,
                new AgentStreamConsumer.StreamResult(Optional.of(output), null, ""),
                TurnObservationContext.start(turn.id(), "thr_1", ".", "deepseek-v4-pro"),
                ".",
                null,
                null);

        assertThat(emitted.getFirst())
                .isInstanceOfSatisfying(ReasoningItem.class, item -> {
                    assertThat(item.text()).contains("思考过程过长，已截断");
                    assertThat(item.text().length()).isLessThan(longReasoning.length());
                });
    }

    private Turn startedTurn() {
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        return turn;
    }

    private AssistantMessage assistantMessage(String text, String reasoning) {
        return AssistantMessage.builder()
                .content(text)
                .properties(Map.of(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, reasoning))
                .build();
    }

    private StreamingOutput<?> streamingChunk(AssistantMessage message) {
        return new StreamingOutput<>(message, "model", "babiq_agent", new OverAllState());
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
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitReasoning(any(ThreadItem.class));
        return emitter;
    }
}
