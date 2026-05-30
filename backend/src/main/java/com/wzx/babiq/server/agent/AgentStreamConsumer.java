package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 消费 Spring AI Alibaba 的 NodeOutput 流，并把助手文本片段转换成 BaBiQ 协议 item。 */
final class AgentStreamConsumer {

    private AgentStreamConsumer() {
    }

    /**
     * 同步消费 ReactAgent.stream 返回的 Flux。
     *
     * <p>DeepSeek 流式 token 统计位于最后的 usage chunk 中，所以这里必须订阅完整流，而不能改回
     * invokeAndGetOutput。消费过程中会将模型文本增量转成累计文本快照，方便桌面端直接覆盖显示。</p>
     *
     * @param stream Spring AI Alibaba 返回的流，可能包含文本 chunk、工具节点输出或 HITL 中断
     * @param emitter 当前 turn 的协议发射器，负责把 item 发送到 WebSocket 客户端
     * @return 最后一个 NodeOutput 与已经发送过的助手消息快照
     */
    static StreamResult consume(Flux<NodeOutput> stream, ItemEmitter emitter) throws Exception {
        AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
        AssistantSnapshot snapshot = new AssistantSnapshot();
        stream.doOnNext(output -> {
            lastOutput.set(output);
            try {
                consumeAssistantChunk(output, emitter, snapshot);
            } catch (Exception exception) {
                throw Exceptions.propagate(exception);
            }
        }).blockLast();
        return new StreamResult(
                Optional.ofNullable(lastOutput.get()),
                snapshot.itemId,
                snapshot.text.toString(),
                snapshot.reasoning.itemId);
    }

    /** 只处理助手普通文本 chunk；工具调用 chunk 由现有工具回调和观测拦截器负责。 */
    private static void consumeAssistantChunk(NodeOutput output,
                                              ItemEmitter emitter,
                                              AssistantSnapshot snapshot) throws Exception {
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }
        if (!(streamingOutput.message() instanceof AssistantMessage assistantMessage)) {
            return;
        }
        ReasoningContentSupport.extractDisplayText(assistantMessage)
                .ifPresent(reasoning -> {
                    try {
                        snapshot.reasoning.update(reasoning, emitter);
                    } catch (Exception exception) {
                        throw Exceptions.propagate(exception);
                    }
                });
        if (assistantMessage.hasToolCalls()) {
            return;
        }
        snapshot.append(assistantMessage.getText(), emitter);
    }

    /** 消费完成后的不可变结果，AgentLoop 通过它判断是否需要补发普通非流式消息。 */
    static final class StreamResult {
        /** 最后一个 NodeOutput，普通完成和 HITL 中断都靠它进入后续收口逻辑。 */
        private final Optional<NodeOutput> output;
        /** 已经发送到前端的助手消息 id；为空表示本次没有收到文本 chunk。 */
        private final String assistantItemId;
        /** 当前助手消息累计文本，complete 时用它固化最终气泡内容。 */
        private final String assistantText;
        /** 已经发到前端的 reasoning item id；用于收尾阶段避免重复补发。 */
        private final String reasoningItemId;

        StreamResult(Optional<NodeOutput> output, String assistantItemId, String assistantText) {
            this(output, assistantItemId, assistantText, null);
        }

        StreamResult(Optional<NodeOutput> output, String assistantItemId, String assistantText, String reasoningItemId) {
            this.output = output;
            this.assistantItemId = assistantItemId;
            this.assistantText = assistantText;
            this.reasoningItemId = reasoningItemId;
        }

        Optional<NodeOutput> output() {
            return output;
        }

        boolean hasAssistantContent() {
            return assistantItemId != null;
        }

        boolean hasReasoningContent() {
            return reasoningItemId != null;
        }

        void completeAssistant(ItemEmitter emitter) throws Exception {
            if (assistantItemId != null) {
                emitter.emitItemCompleted(AgentMessageItem.full(assistantItemId, assistantText));
            }
        }
    }

    /** 单次 turn 内的助手文本累加器，只在 consume 调用栈内存在，避免并发会话互相污染。 */
    private static final class AssistantSnapshot {
        /** 首个文本 chunk 到达时生成的 item id，后续 updated/completed 都复用它。 */
        private String itemId;
        /** 已收到的完整助手文本；前端收到的是累计快照，不需要自己拼 delta。 */
        private final StringBuilder text = new StringBuilder();
        /** 本轮 reasoning 展示 item 的累积快照，保持它在首个 assistant 文本前输出。 */
        private final ReasoningSnapshot reasoning = new ReasoningSnapshot();

        void append(String chunk, ItemEmitter emitter) throws Exception {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            text.append(chunk);
            if (itemId == null) {
                itemId = AgentLoopSupport.newItemId();
                emitter.emitItemAdded(AgentMessageItem.full(itemId, text.toString()));
                return;
            }
            emitter.emitItemUpdated(AgentMessageItem.full(itemId, text.toString()));
        }
    }

    /** 单轮 turn 内的 reasoning 累积器；首帧 add，后续帧 update，避免 UI 自己拼 delta。 */
    private static final class ReasoningSnapshot {
        /** 首个 reasoning chunk 到达时生成的 item id，后续 updated 复用它。 */
        private String itemId;
        /** 已发送到前端的最新 reasoning 快照；重复 chunk 不再发 update，减少无意义刷新。 */
        private String text;

        void update(String nextText, ItemEmitter emitter) throws Exception {
            if (nextText == null || nextText.isBlank() || nextText.equals(text)) {
                return;
            }
            text = nextText;
            if (itemId == null) {
                itemId = AgentLoopSupport.newItemId();
                emitter.emitReasoning(new ReasoningItem(itemId, "reasoning", text));
                return;
            }
            emitter.emitItemUpdated(new ReasoningItem(itemId, "reasoning", text));
        }
    }
}
