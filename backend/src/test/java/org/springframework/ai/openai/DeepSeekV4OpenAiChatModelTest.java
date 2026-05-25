package org.springframework.ai.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

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
    @DisplayName("历史里 reasoning_content 丢失时，工具调用 assistant 消息使用占位值兜底")
    void createRequest_should_add_placeholder_when_tool_call_reasoning_is_missing() {
        DeepSeekV4OpenAiChatModel chatModel = newModel(OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .streamUsage(true)
                .build());
        AssistantMessage assistantMessage = assistantToolCall("");
        Prompt prompt = new Prompt(List.of(new UserMessage("创建文件"), assistantMessage), chatModel.getDefaultOptions());

        OpenAiApi.ChatCompletionRequest request = chatModel.createRequest(prompt, true);
        OpenAiApi.ChatCompletionMessage assistantWireMessage = request.messages().get(1);

        assertThat(assistantWireMessage.reasoningContent())
                .isEqualTo(DeepSeekV4OpenAiChatModel.REASONING_OMITTED_PLACEHOLDER);
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
