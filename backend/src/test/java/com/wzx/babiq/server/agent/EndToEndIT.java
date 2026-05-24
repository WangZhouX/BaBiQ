package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * P1-3a 端到端集成测试。
 *
 * <p>该测试保留真实 Spring 应用上下文、真实 AgentLoop、真实 ReActStrategy 和真实 ToolRegistry，
 * 只 mock P1-2 的 ChatClientFactory。这样既避免 ProviderFactory 类型重复，又能验证
 * SAA ReactAgent 会按“模型 tool_call -> 执行 read_file -> 模型 final answer”的主链路跑完。</p>
 */
@SpringBootTest(properties = {
        "babiq.active-provider=e2e-provider",
        "babiq.providers[0].id=e2e-provider",
        "babiq.providers[0].name=E2E Mock",
        "babiq.providers[0].type=DASHSCOPE",
        "babiq.providers[0].model=mock-react",
        "babiq.providers[0].api-key=sk-test",
        "babiq.agent.sandbox-mode=WORKSPACE_WRITE",
        "babiq.agent.approval-policy=NEVER"
})
class EndToEndIT {

    @MockBean
    private ChatClientFactory chatClientFactory;

    @jakarta.annotation.Resource
    private AgentLoop agentLoop;

    /**
     * 每个用例使用新的 mock ChatModel，避免调用次数在测试间串扰。
     */
    @BeforeEach
    void setUpMockModel() {
        Mockito.when(chatClientFactory.resolveChatModel("e2e-provider"))
                .thenReturn(new ToolCallingChatModel());
        Mockito.when(chatClientFactory.resolveModelName("e2e-provider"))
                .thenReturn("mock-react");
    }

    @Test
    void agent_loop_runs_full_react_cycle_with_mocked_tool_call() throws Exception {
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        Turn turn = new Turn("turn_e2e", "thr_e2e");
        turn.start();

        agentLoop.invoke(turn, "读取 README.md 并总结", "e2e-provider", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(emittedItems).extracting(ThreadItem::type)
                .contains("userMessage", "agentMessage", "turnSummary");
        assertThat(emittedItems.get(emittedItems.size() - 1).type()).isEqualTo("turnSummary");
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emittedItems) throws Exception {
        ItemEmitter emitter = Mockito.mock(ItemEmitter.class);
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemAdded(any(ThreadItem.class));
        Mockito.doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitTurnSummary(any(ThreadItem.class));
        return emitter;
    }

    /**
     * 用两次固定响应模拟一次 ReAct：先要求 read_file，再给最终回答。
     */
    private static final class ToolCallingChatModel implements ChatModel {

        private final AtomicInteger callCounter = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.incrementAndGet();
            if (currentCall == 1) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_readme", "function", "read_file", "{\"path\":\"README.md\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            AssistantMessage finalMessage = new AssistantMessage("README 已通过 read_file 工具读取并完成总结。");
            return new ChatResponse(List.of(new Generation(finalMessage)));
        }

        /**
         * AgentLoop 现在统一走 ReactAgent.stream；测试模型也要实现流式接口，才能覆盖真实执行链路。
         *
         * @param prompt SAA 传入的当前轮 Prompt，内容和 call 方法一致
         * @return 只有一个响应块的 Flux，足够模拟“流式 API 最终返回一个 ChatResponse”的行为
         */
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
