package org.springframework.ai.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeepSeek V4 专用 OpenAI 兼容适配器测试。
 *
 * <p>这些测试不访问真实 DeepSeek 网络接口，只检查最终发送给 /chat/completions 的
 * wire payload。DeepSeek V4 thinking mode 的关键要求是：assistant 只要携带
 * tool_calls，后续请求就必须把上一轮返回的 reasoning_content 原样带回去。</p>
 */
class DeepSeekV4OpenAiChatModelTest {

    @Test
    @DisplayName("流式工具调用必须在工具 chunk 上保留已累计的 reasoning_content")
    void stream_should_preserve_accumulated_reasoning_content_for_tool_call_chunks() {
        OpenAiApi openAiApi = mock(OpenAiApi.class);
        when(openAiApi.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(
                        chunk(null, message(null, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null,
                                "我需要创建文件")),
                        chunk(OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, message("",
                                null,
                                List.of(new OpenAiApi.ChatCompletionMessage.ToolCall(
                                        "call_write_file",
                                        "function",
                                        new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                "write_file",
                                                "{\"path\":\"hello.html\"}"))),
                                null))
                ));
        DeepSeekV4OpenAiChatModel chatModel = new DeepSeekV4OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .internalToolExecutionEnabled(false)
                .build());

        List<ChatResponse> responses = chatModel.stream(new Prompt(List.of(new UserMessage("创建 hello.html")),
                chatModel.getDefaultOptions())).collectList().block();

        ChatResponse toolCallResponse = responses.stream()
                .filter(ChatResponse::hasToolCalls)
                .findFirst()
                .orElseThrow();
        assertThat(toolCallResponse.getResult().getOutput().getMetadata())
                .containsEntry(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, "我需要创建文件");
    }

    @Test
    @DisplayName("内部工具执行后的第二次请求必须回放第一次工具调用的 reasoning_content")
    void stream_internal_tool_execution_should_replay_reasoning_content_in_followup_request() {
        OpenAiApi openAiApi = mock(OpenAiApi.class);
        List<OpenAiApi.ChatCompletionRequest> capturedRequests = new ArrayList<>();
        when(openAiApi.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(
                            chunk(null, message(null, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null,
                                    "我需要创建文件")),
                            chunk(OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, message("",
                                    null,
                                    List.of(new OpenAiApi.ChatCompletionMessage.ToolCall(
                                            "call_write_file",
                                            "function",
                                            new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                    "write_file",
                                                    "{\"path\":\"hello.html\"}"))),
                                    null))
                    );
                })
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(chunk(OpenAiApi.ChatCompletionFinishReason.STOP,
                            message("已创建", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null)));
                });
        DeepSeekV4OpenAiChatModel chatModel = new DeepSeekV4OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .build());
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(fakeWriteFileTool())
                .build();

        chatModel.stream(new Prompt(List.of(new UserMessage("创建 hello.html")), toolOptions)).collectList().block();

        assertThat(capturedRequests).hasSize(2);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = capturedRequests.get(1).messages().get(1);
        assertThat(assistantWireMessage.toolCalls()).hasSize(1);
        assertThat(assistantWireMessage.reasoningContent()).isEqualTo("我需要创建文件");
    }

    @Test
    @DisplayName("工具调用 chunk 自身携带 reasoning 片段时，第二次请求必须回放完整 reasoning_content")
    void stream_internal_tool_execution_should_replay_full_reasoning_when_tool_chunk_contains_reasoning_fragment() {
        OpenAiApi openAiApi = mock(OpenAiApi.class);
        List<OpenAiApi.ChatCompletionRequest> capturedRequests = new ArrayList<>();
        when(openAiApi.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(
                            chunk(null, message(null, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null,
                                    "我需要")),
                            // 2026-05-25 Bug 回归测试记录：
                            // DeepSeek V4 可能在 tool_call chunk 自身继续输出 reasoning_content 片段。
                            // 旧实现只把这个片段放行给 Spring AI，MessageAggregator 最终只保存“创建文件”，
                            // 审批/工具恢复后的下一次请求就无法原样回放完整 reasoning_content。
                            chunk(OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, message("",
                                    null,
                                    List.of(new OpenAiApi.ChatCompletionMessage.ToolCall(
                                            "call_write_file",
                                            "function",
                                            new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                    "write_file",
                                                    "{\"path\":\"hello.html\"}"))),
                                    "创建文件"))
                    );
                })
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(chunk(OpenAiApi.ChatCompletionFinishReason.STOP,
                            message("已创建", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null)));
                });
        DeepSeekV4OpenAiChatModel chatModel = new DeepSeekV4OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .build());
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(fakeWriteFileTool())
                .build();

        chatModel.stream(new Prompt(List.of(new UserMessage("创建 hello.html")), toolOptions)).collectList().block();

        assertThat(capturedRequests).hasSize(2);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = capturedRequests.get(1).messages().get(1);
        assertThat(assistantWireMessage.toolCalls()).hasSize(1);
        assertThat(assistantWireMessage.reasoningContent()).isEqualTo("我需要创建文件");
    }

    @Test
    @DisplayName("工具调用历史必须回放 reasoning_content，并移除 thinking mode 不支持的 tool_choice")
    void createRequest_should_replay_reasoning_content_for_assistant_tool_calls() {
        DeepSeekV4OpenAiChatModel chatModel = newModel(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .toolChoice("auto")
                .streamUsage(true)
                .build());
        AssistantMessage assistantMessage = assistantToolCall("我需要写入文件");
        Prompt prompt = new Prompt(List.of(
                new UserMessage("创建 hello.html"),
                assistantMessage,
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call_write_file",
                                "write_file",
                                "ok")))
                        .build()
        ), chatModel.getDefaultOptions());

        OpenAiApi.ChatCompletionRequest request = chatModel.createRequest(prompt, true);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = request.messages().get(1);

        assertThat(request.toolChoice()).isNull();
        assertThat(request.extraBody()).containsEntry("thinking", Map.of("type", "enabled"));
        assertThat(assistantWireMessage.reasoningContent()).isEqualTo("我需要写入文件");
        assertThat(assistantWireMessage.content()).isEqualTo("");
        assertThat(assistantWireMessage.toolCalls()).hasSize(1);
    }

    @Test
    @DisplayName("真实 HTTP 请求体必须序列化 reasoning_content，避免对象层通过但 wire payload 丢字段")
    void stream_should_serialize_reasoning_content_to_http_request_body() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant","content":"完成"},"finish_reason":null}]}

                    data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("sk-test")
                    .build();
            DeepSeekV4OpenAiChatModel chatModel = new DeepSeekV4OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                    .model("deepseek-v4-pro")
                    .toolChoice("auto")
                    .streamUsage(true)
                    .build());
            Prompt prompt = new Prompt(List.of(
                    new UserMessage("创建 hello.html"),
                    assistantToolCall("我需要写入文件"),
                    ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    "call_write_file",
                                    "write_file",
                                    "ok")))
                            .build()
            ), chatModel.getDefaultOptions());

            chatModel.stream(prompt).collectList().block(Duration.ofSeconds(5));

            assertThat(capturedBody.get()).contains("\"reasoning_content\":\"我需要写入文件\"");
            assertThat(capturedBody.get()).contains("\"thinking\":{\"type\":\"enabled\"}");
            assertThat(capturedBody.get()).doesNotContain("\"tool_choice\"");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HITL 恢复重建历史丢 metadata 时，仍应从同一工具调用的原始流缓存回填 reasoning_content")
    void stream_should_recover_reasoning_from_cached_tool_call_when_resume_history_metadata_is_missing() {
        OpenAiApi openAiApi = mock(OpenAiApi.class);
        List<OpenAiApi.ChatCompletionRequest> capturedRequests = new ArrayList<>();
        when(openAiApi.chatCompletionStream(any(OpenAiApi.ChatCompletionRequest.class), any()))
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(
                            chunk(null, message(null, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null,
                                    "我需要创建文件")),
                            chunk(OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS, message("",
                                    null,
                                    List.of(new OpenAiApi.ChatCompletionMessage.ToolCall(
                                            "call_write_file",
                                            "function",
                                            new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                                    "write_file",
                                                    "{\"path\":\"hello.html\"}"))),
                                    null))
                    );
                })
                .thenAnswer(invocation -> {
                    capturedRequests.add(invocation.getArgument(0));
                    return Flux.just(chunk(OpenAiApi.ChatCompletionFinishReason.STOP,
                            message("已创建", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null)));
                });
        DeepSeekV4OpenAiChatModel chatModel = new DeepSeekV4OpenAiChatModel(openAiApi, OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .internalToolExecutionEnabled(false)
                .build());

        chatModel.stream(new Prompt(List.of(new UserMessage("创建 hello.html")), chatModel.getDefaultOptions()))
                .collectList()
                .block();
        Prompt resumePrompt = new Prompt(List.of(
                new UserMessage("创建 hello.html"),
                assistantToolCall(null),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call_write_file",
                                "write_file",
                                "ok")))
                        .build()
        ), chatModel.getDefaultOptions());

        chatModel.stream(resumePrompt).collectList().block();

        assertThat(capturedRequests).hasSize(2);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = capturedRequests.get(1).messages().get(1);
        assertThat(assistantWireMessage.toolCalls()).hasSize(1);
        assertThat(assistantWireMessage.reasoningContent()).isEqualTo("我需要创建文件");
    }

    @Test
    @DisplayName("历史里 reasoning_content 丢失时，请求构建保留工具调用并交给 API 边界回填")
    void createRequest_should_leave_missing_tool_call_reasoning_for_api_boundary_repair() {
        DeepSeekV4OpenAiChatModel chatModel = newModel(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .build());
        AssistantMessage assistantMessage = assistantToolCall("");
        Prompt prompt = new Prompt(List.of(new UserMessage("创建文件"), assistantMessage), chatModel.getDefaultOptions());

        OpenAiApi.ChatCompletionRequest request = chatModel.createRequest(prompt, true);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = request.messages().get(1);

        assertThat(assistantWireMessage.toolCalls()).hasSize(1);
        assertThat(assistantWireMessage.reasoningContent()).isNull();
        assertThat(assistantWireMessage.content()).isEqualTo("");
    }

    @Test
    @DisplayName("普通 assistant 消息不强行追加 reasoning_content，避免污染非工具上下文")
    void createRequest_should_not_replay_reasoning_for_plain_assistant_message() {
        DeepSeekV4OpenAiChatModel chatModel = newModel(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .build());
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("已经完成")
                .properties(Map.of(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, "内部思考"))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("你好"), assistantMessage), chatModel.getDefaultOptions());

        OpenAiApi.ChatCompletionRequest request = chatModel.createRequest(prompt, true);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = request.messages().get(1);

        assertThat(assistantWireMessage.reasoningContent()).isNull();
        assertThat(assistantWireMessage.content()).isEqualTo("已经完成");
    }

    @Test
    @DisplayName("显式关闭 thinking 时，不回放 reasoning_content，也不强制移除 tool_choice")
    void createRequest_should_respect_explicit_thinking_disabled() {
        DeepSeekV4OpenAiChatModel chatModel = newModel(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .toolChoice("auto")
                .streamUsage(true)
                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                .build());
        Prompt prompt = new Prompt(List.of(new UserMessage("创建文件"), assistantToolCall("需要工具")),
                chatModel.getDefaultOptions());

        OpenAiApi.ChatCompletionRequest request = chatModel.createRequest(prompt, true);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = request.messages().get(1);

        assertThat(request.toolChoice()).isEqualTo("auto");
        assertThat(assistantWireMessage.reasoningContent()).isNull();
    }

    private static DeepSeekV4OpenAiChatModel newModel(OpenAiChatOptions options) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey("sk-fake")
                .build();
        return new DeepSeekV4OpenAiChatModel(openAiApi, options);
    }

    private static AssistantMessage assistantToolCall(String reasoningContent) {
        return AssistantMessage.builder()
                .content("")
                .properties(reasoningContent == null || reasoningContent.isBlank()
                        ? Map.of()
                        : Map.of(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY, reasoningContent))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_write_file",
                        "function",
                        "write_file",
                        "{\"path\":\"hello.html\"}")))
                .build();
    }

    /**
     * 提供一个只服务测试的 write_file 工具，让 Spring AI 内部工具执行链真的跑起来。
     *
     * <p>这比直接构造历史消息更接近线上 bug：线上是 DeepSeek 返回 tool_call 后，
     * Spring AI 先执行工具，再把工具结果和 assistant tool_call 历史拼成第二次请求。</p>
     */
    private static ToolCallback fakeWriteFileTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("write_file")
                        .description("写入文件")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

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

    private static OpenAiApi.ChatCompletionMessage message(Object content,
                                                           OpenAiApi.ChatCompletionMessage.Role role,
                                                           List<OpenAiApi.ChatCompletionMessage.ToolCall> toolCalls,
                                                           String reasoningContent) {
        return new OpenAiApi.ChatCompletionMessage(content, role, null, null, toolCalls, null, null, null,
                reasoningContent);
    }
}
