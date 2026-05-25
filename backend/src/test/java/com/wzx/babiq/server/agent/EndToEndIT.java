package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.DeepSeekV4OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        "babiq.agent.approval-policy=NEVER",
        "babiq.persistence.database-path=target/test-db/end-to-end-it-${random.uuid}.db"
})
class EndToEndIT {

    @MockBean
    private ChatClientFactory chatClientFactory;

    @jakarta.annotation.Resource
    private AgentLoop agentLoop;

    @jakarta.annotation.Resource
    private PendingApprovals pendingApprovals;

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

    @Test
    void agent_loop_resume_after_hitl_approval_should_execute_tool_before_calling_model_again() throws Exception {
        HitlToolCallingChatModel model = new HitlToolCallingChatModel();
        Mockito.when(chatClientFactory.resolveChatModel("e2e-provider")).thenReturn(model);
        Mockito.when(chatClientFactory.resolveChatModel(null)).thenReturn(model);
        Mockito.when(chatClientFactory.resolveModelName(null)).thenReturn("mock-react");
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        Turn turn = new Turn("turn_hitl", "thr_hitl");
        turn.start();

        agentLoop.invoke(turn, "在当前目录执行 cd", "e2e-provider", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        InterruptionMetadata pending = pendingApprovals.take("thr_hitl");
        assertThat(pending).isNotNull();

        agentLoop.invokeResume(turn, approvedFeedback(pending), ".", emitter);

        assertThat(model.sawToolResponseBeforeResumeModelCall())
                .as("HITL 审批恢复后，必须先执行工具并把 ToolResponseMessage 补到模型上下文里")
                .isTrue();
        assertThat(model.sawReasoningBeforeResumeModelCall())
                .as("DeepSeek V4 thinking mode 的 tool_call assistant 在 HITL 恢复后必须保留 reasoningContent metadata")
                .isTrue();
        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void hitl_approval_should_execute_write_file_under_thread_cwd(@org.junit.jupiter.api.io.TempDir Path workspace) throws Exception {
        HitlWriteFileChatModel model = new HitlWriteFileChatModel();
        Mockito.when(chatClientFactory.resolveChatModel("e2e-provider")).thenReturn(model);
        Mockito.when(chatClientFactory.resolveChatModel(null)).thenReturn(model);
        Mockito.when(chatClientFactory.resolveModelName(null)).thenReturn("mock-react");
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        Turn turn = new Turn("turn_hitl_write_cwd", "thr_hitl_write_cwd");
        turn.start();

        agentLoop.invoke(turn, "create index.html in the current directory", "e2e-provider", workspace.toString(), emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        InterruptionMetadata pending = pendingApprovals.take("thr_hitl_write_cwd");
        assertThat(pending).isNotNull();

        agentLoop.invokeResume(turn, approvedFeedback(pending), workspace.toString(), emitter);

        Path writtenFile = workspace.resolve("index.html");
        assertThat(writtenFile)
                .as("approval resume must execute write_file against the thread cwd, not just produce a tool response")
                .exists();
        assertThat(Files.readString(writtenFile)).isEqualTo("hello-from-hitl");
        assertThat(model.sawWriteToolResponseBeforeFinalModelCall()).isTrue();
        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void deepseek_v4_hitl_resume_should_send_reasoning_content_in_second_request() throws Exception {
        OpenAiApi openAiApi = Mockito.mock(OpenAiApi.class);
        List<OpenAiApi.ChatCompletionRequest> capturedRequests = new ArrayList<>();
        Mockito.when(openAiApi.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(
                            chunk(null, message(null, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                                    null, "我需要创建文件，所以先调用 write_file。")),
                            chunk(OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, message("",
                                    null,
                                    List.of(new OpenAiApi.ChatCompletionMessage.ToolCall(
                                            "call_write_file",
                                            "function",
                                            new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                    "write_file",
                                                    "{\"path\":\"target/deepseek-hitl-e2e/hello.txt\",\"content\":\"你好\"}"))),
                                    null))
                    );
                })
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(chunk(OpenAiApi.ChatCompletionFinishReason.STOP,
                            message("文件已经创建。", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                                    null, null)));
                });
        DeepSeekV4OpenAiChatModel model = new DeepSeekV4OpenAiChatModel(openAiApi,
                OpenAiChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .streamUsage(true)
                        .build());
        Mockito.when(chatClientFactory.resolveChatModel("e2e-provider")).thenReturn(model);
        Mockito.when(chatClientFactory.resolveModelName("e2e-provider")).thenReturn("deepseek-v4-pro");
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        Turn turn = new Turn("turn_deepseek_hitl", "thr_deepseek_hitl");
        turn.start();

        agentLoop.invoke(turn, "在当前工作目录创建 html 内容是你好", "e2e-provider", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        InterruptionMetadata pending = pendingApprovals.take("thr_deepseek_hitl");
        assertThat(pending).isNotNull();

        agentLoop.invokeResume(turn, approvedFeedback(pending), ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(capturedRequests).hasSize(2);
        OpenAiApi.ChatCompletionRequest resumeRequest = capturedRequests.get(1);
        OpenAiApi.ChatCompletionMessage assistantToolCall = resumeRequest.messages().stream()
                .filter(message -> message.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT)
                .filter(message -> message.toolCalls() != null && !message.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(assistantToolCall.reasoningContent())
                .as("DeepSeek V4 thinking mode 在审批恢复后的第二次请求必须回放 assistant.tool_calls 的 reasoning_content")
                .isEqualTo("我需要创建文件，所以先调用 write_file。");
        assertThat(resumeRequest.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(OpenAiApi.ChatCompletionMessage.Role.TOOL);
            assertThat(message.toolCallId()).isEqualTo("call_write_file");
        });
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
     * 把 HumanInTheLoopHook 产生的暂停元数据转换成“用户已批准”的恢复元数据。
     *
     * <p>真实链路里这一步由 {@code approval/respond} 完成；集成测试直接构造它，
     * 可以把关注点收窄到 AgentLoop 与 SAA Graph 的恢复顺序。</p>
     */
    private InterruptionMetadata approvedFeedback(InterruptionMetadata pending) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(pending);
        builder.toolFeedbacks(List.of());
        for (InterruptionMetadata.ToolFeedback feedback : pending.toolFeedbacks()) {
            builder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder(feedback)
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build());
        }
        return builder.build();
    }

    /**
     * 构造 DeepSeek V4 流式响应分片，避免测试访问真实网络。
     */
    private static OpenAiApi.ChatCompletionChunk chunk(OpenAiApi.ChatCompletionFinishReason finishReason,
                                                       OpenAiApi.ChatCompletionMessage delta) {
        return new OpenAiApi.ChatCompletionChunk(
                "chatcmpl-test",
                List.of(new OpenAiApi.ChatCompletionChunk.ChunkChoice(finishReason, 0, delta, null)),
                1L,
                "deepseek-v4-pro",
                null,
                null,
                "chat.completion.chunk",
                null);
    }

    /**
     * 构造 OpenAI-compatible wire message，重点允许测试直接设置 reasoning_content。
     */
    private static OpenAiApi.ChatCompletionMessage message(Object content,
                                                           OpenAiApi.ChatCompletionMessage.Role role,
                                                           List<OpenAiApi.ChatCompletionMessage.ToolCall> toolCalls,
                                                           String reasoningContent) {
        return new OpenAiApi.ChatCompletionMessage(content, role, null, null, toolCalls, null, null, null,
                reasoningContent);
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

    /**
     * 专门复现 DeepSeek 400 的 HITL 测试模型。
     *
     * <p>DeepSeek 官方 OpenAI 兼容接口要求：一条带 {@code tool_calls} 的 assistant 消息后面，
     * 必须紧跟同 {@code tool_call_id} 的 tool 响应。这个假模型在恢复后的第二次模型调用里做同样校验，
     * 让“审批后没有先执行工具”这种协议错误可以在本地测试中稳定暴露。</p>
     */
    private static final class HitlToolCallingChatModel implements ChatModel {

        /** 记录模型被调用到第几次，用来区分首轮 tool_call 和审批恢复后的最终回答。 */
        private final AtomicInteger callCounter = new AtomicInteger();

        /** 记录恢复后的模型调用是否已经看到了 exec_shell 对应的 ToolResponseMessage。 */
        private boolean sawToolResponseBeforeResumeModelCall;

        /** 记录恢复后的模型调用是否仍然保留了 DeepSeek V4 tool_call 所需的 reasoningContent。 */
        private boolean sawReasoningBeforeResumeModelCall;

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.incrementAndGet();
            if (currentCall == 1) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("我需要先查看当前目录。")
                        // 2026-05-25 Bug 回归测试记录：
                        // DeepSeek V4 thinking mode 的 tool_call assistant 不能只保留工具调用，
                        // 还必须把模型返回的 reasoning_content 存进 metadata，后续请求才能原样回传。
                        .properties(Map.of(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, "我需要查看当前目录，所以先调用 cd。"))
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_cd", "function", "exec_shell", "{\"command\":\"cd\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }

            sawToolResponseBeforeResumeModelCall = hasToolResponse(prompt.getInstructions(), "call_cd", "exec_shell");
            sawReasoningBeforeResumeModelCall = hasReasoning(prompt.getInstructions(), "call_cd");
            if (!sawToolResponseBeforeResumeModelCall) {
                throw new AssertionError("HITL 恢复后缺少 exec_shell 的 ToolResponseMessage，当前消息链路="
                        + describeMessages(prompt.getInstructions()));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("工具已执行，当前目录读取完成。"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        boolean sawToolResponseBeforeResumeModelCall() {
            return sawToolResponseBeforeResumeModelCall;
        }

        boolean sawReasoningBeforeResumeModelCall() {
            return sawReasoningBeforeResumeModelCall;
        }

        /**
         * 检查恢复后的 Prompt 里，带指定工具调用 id 的 AssistantMessage 是否仍有 reasoningContent。
         *
         * <p>这不是 UI 展示内容，而是 DeepSeek V4 后续请求必须回传的协议字段来源；
         * 如果这里为 false，后面的 OpenAI-compatible 请求即使有 tool_calls 也会缺少真实思考内容。</p>
         */
        private boolean hasReasoning(List<Message> messages, String toolCallId) {
            for (Message message : messages) {
                if (message instanceof AssistantMessage assistantMessage) {
                    boolean hasTargetToolCall = assistantMessage.getToolCalls().stream()
                            .anyMatch(call -> toolCallId.equals(call.id()));
                    if (!hasTargetToolCall) {
                        continue;
                    }
                    Object reasoning = assistantMessage.getMetadata().get(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY);
                    return reasoning instanceof String text && !text.isBlank();
                }
            }
            return false;
        }

        /**
         * 在 Prompt 历史里查找指定工具调用 id 的响应消息。
         *
         * <p>这里不关心工具输出内容，只关心协议顺序是否完整：assistant.tool_calls 之后必须出现
         * 同 id、同工具名的 ToolResponseMessage。</p>
         */
        private boolean hasToolResponse(List<Message> messages, String toolCallId, String toolName) {
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (toolCallId.equals(response.id()) && toolName.equals(response.name())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * 把恢复时传给模型的消息类型压缩成一行文本，方便定位 Graph 到底有没有先进入工具节点。
         */
        private String describeMessages(List<Message> messages) {
            List<String> names = new ArrayList<>();
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    List<String> responses = new ArrayList<>();
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        responses.add(response.id() + "/" + response.name());
                    }
                    names.add("ToolResponseMessage" + responses);
                } else if (message instanceof AssistantMessage assistantMessage) {
                    List<String> calls = new ArrayList<>();
                    for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                        calls.add(call.id() + "/" + call.name());
                    }
                    names.add("AssistantMessage" + calls);
                } else {
                    names.add(message.getClass().getSimpleName());
                }
            }
            return names.toString();
        }
    }

    /**
     * 鎶婂鎵规仮澶嶅悗鐨勫啓鏂囦欢琛屼负閿佸畾鍒扮湡瀹炴枃浠剁郴缁熶笂銆?
     */
    private static final class HitlWriteFileChatModel implements ChatModel {

        private final AtomicInteger callCounter = new AtomicInteger();

        private boolean sawWriteToolResponseBeforeFinalModelCall;

        @Override
        public ChatResponse call(Prompt prompt) {
            int currentCall = callCounter.incrementAndGet();
            if (currentCall == 1) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                        .content("I need to create a file.")
                        .properties(Map.of(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, "Need to write the requested file."))
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_write_index", "function", "write_file",
                                "{\"path\":\"index.html\",\"content\":\"hello-from-hitl\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            sawWriteToolResponseBeforeFinalModelCall = hasToolResponse(prompt.getInstructions(),
                    "call_write_index", "write_file");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("file created"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        boolean sawWriteToolResponseBeforeFinalModelCall() {
            return sawWriteToolResponseBeforeFinalModelCall;
        }

        private boolean hasToolResponse(List<Message> messages, String toolCallId, String toolName) {
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (toolCallId.equals(response.id()) && toolName.equals(response.name())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
