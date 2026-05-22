package com.wzx.babiq.server.test;

import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 跨轮短期记忆自动化验收。
 *
 * <p>本测试不用真实 LLM,而是让 mock ChatModel 记录每一轮收到的 Prompt。
 * 这样可以直接证明 {@code MessageChatMemoryAdvisor} 把上一轮 user/assistant 消息
 * 注入了下一轮 prompt,并且不同 {@link ChatMemory#CONVERSATION_ID} 不串台。</p>
 */
class CrossTurnMemoryIT {

    @Test
    @DisplayName("同一 threadId 第二轮 prompt 包含第一轮历史")
    void second_turn_with_same_thread_id_should_include_first_turn_history() {
        RecordingChatModel recordingChatModel = new RecordingChatModel();
        ChatClient chatClient = buildChatClient(recordingChatModel);

        chatClient.prompt()
                .user("第一轮:我叫小明,最喜欢的水果是芒果")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-memory"))
                .call()
                .content();
        chatClient.prompt()
                .user("第二轮:请说出我的名字和水果")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-memory"))
                .call()
                .content();

        List<String> secondPromptTexts = recordingChatModel.promptTextsAt(1);
        assertThat(secondPromptTexts)
                .anySatisfy(text -> assertThat(text).contains("小明").contains("芒果"))
                .anySatisfy(text -> assertThat(text).contains("mock-reply-1"))
                .anySatisfy(text -> assertThat(text).contains("第二轮"));
    }

    @Test
    @DisplayName("不同 threadId 不共享上一轮历史")
    void different_thread_id_should_not_share_history() {
        RecordingChatModel recordingChatModel = new RecordingChatModel();
        ChatClient chatClient = buildChatClient(recordingChatModel);

        chatClient.prompt()
                .user("A 线程:我叫小明")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-A"))
                .call()
                .content();
        chatClient.prompt()
                .user("B 线程:我是谁")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-B"))
                .call()
                .content();

        List<String> secondPromptTexts = recordingChatModel.promptTextsAt(1);
        assertThat(secondPromptTexts).noneMatch(text -> text.contains("小明"));
        assertThat(secondPromptTexts).noneMatch(text -> text.contains("mock-reply-1"));
        assertThat(secondPromptTexts).anyMatch(text -> text.contains("B 线程"));
    }

    @Test
    @DisplayName("短期记忆窗口按 maxMessages 裁剪旧消息")
    void memory_window_should_trim_old_messages_by_max_messages() {
        RecordingChatModel recordingChatModel = new RecordingChatModel();
        ChatClient chatClient = buildChatClient(recordingChatModel, 3);

        chatClient.prompt()
                .user("第一轮:应该被裁剪")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-window"))
                .call()
                .content();
        chatClient.prompt()
                .user("第二轮:应该保留")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-window"))
                .call()
                .content();
        chatClient.prompt()
                .user("第三轮:检查窗口")
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, "thread-window"))
                .call()
                .content();

        List<String> thirdPromptTexts = recordingChatModel.promptTextsAt(2);
        assertThat(thirdPromptTexts).noneMatch(text -> text.contains("第一轮"));
        assertThat(thirdPromptTexts).anyMatch(text -> text.contains("第二轮"));
        assertThat(thirdPromptTexts).anyMatch(text -> text.contains("第三轮"));
    }

    private static ChatClient buildChatClient(ChatModel chatModel) {
        return buildChatClient(chatModel, 20);
    }

    private static ChatClient buildChatClient(ChatModel chatModel, int maxMessages) {
        BaBiQProperties properties = properties(maxMessages);
        ProviderFactory providerFactory = new ProviderFactory() {
            @Override
            public ProviderType supports() {
                return ProviderType.DASHSCOPE;
            }

            @Override
            public ChatModel build(ModelProviderConfig config) {
                return chatModel;
            }
        };

        ChatClientFactory chatClientFactory = new ChatClientFactory(
                new ModelProviderRegistry(properties),
                List.of(providerFactory),
                properties
        );
        return chatClientFactory.resolve("dashscope-default");
    }

    private static BaBiQProperties properties(int maxMessages) {
        ModelProviderConfig providerConfig = new ModelProviderConfig(
                "dashscope-default",
                "DashScope mock",
                ProviderType.DASHSCOPE,
                "qwen-plus",
                "sk-test",
                null,
                null
        );
        BaBiQProperties.ShortTerm shortTerm = new BaBiQProperties.ShortTerm(maxMessages);
        BaBiQProperties.Memory memory = new BaBiQProperties.Memory(shortTerm);
        return new BaBiQProperties("dashscope-default", List.of(providerConfig), memory);
    }

    /**
     * 记录每轮 Prompt 的 ChatModel。
     *
     * <p>返回内容带序号,便于断言上一轮 assistant 消息是否被 advisor 写回记忆。</p>
     */
    private static final class RecordingChatModel implements ChatModel {

        private final List<List<Message>> prompts = new ArrayList<>();
        private final AtomicReference<Integer> callCounter = new AtomicReference<>(0);

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.updateAndGet(value -> value + 1);
            prompts.add(List.copyOf(prompt.getInstructions()));
            AssistantMessage assistantMessage = new AssistantMessage("mock-reply-" + currentCall);
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }

        private List<String> promptTextsAt(int index) {
            return prompts.get(index).stream()
                    .filter(message -> message.getMessageType() == MessageType.USER
                            || message.getMessageType() == MessageType.ASSISTANT)
                    .map(Message::getText)
                    .toList();
        }
    }
}
