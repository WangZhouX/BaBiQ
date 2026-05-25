package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.DeepSeekV4OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 协议兼容 ProviderFactory 的行为测试。
 *
 * <p>DeepSeek、OneAPI 和 Ollama 都复用同一套 OpenAI 兼容协议,
 * 因此这里重点覆盖 base-url、api-key 和 model 三类配置的本地构建行为。</p>
 */
class OpenAiCompatibleProviderFactoryTest {

    private final OpenAiCompatibleProviderFactory factory = new OpenAiCompatibleProviderFactory();

    @Test
    @DisplayName("supports 返回 OPENAI_COMPATIBLE")
    void supports_should_return_openai_compatible() {
        assertThat(factory.supports()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("DeepSeek 风格配置构建 ChatModel")
    void build_should_create_chat_model_for_deepseek_style_config() {
        ModelProviderConfig config = new ModelProviderConfig(
                "deepseek-official",
                "DeepSeek 官方",
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-chat",
                "sk-fake-key",
                "https://api.deepseek.com",
                null
        );

        assertThat(factory.build(config)).isNotNull();
    }

    @Test
    @DisplayName("OpenAI 兼容流式调用必须请求 usage")
    void build_should_enable_stream_usage_for_openai_compatible_provider() {
        ModelProviderConfig config = new ModelProviderConfig(
                "deepseek-official",
                "DeepSeek 官方",
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-v4-pro",
                "sk-fake-key",
                "https://api.deepseek.com",
                null
        );

        OpenAiChatModel chatModel = (OpenAiChatModel) factory.build(config);
        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.getDefaultOptions();

        assertThat(options.getStreamUsage()).isTrue();
    }

    @Test
    @DisplayName("DeepSeek V4 官方端点使用专用适配器，不再用关闭 thinking 的方式绕过问题")
    void build_should_use_deepseek_v4_adapter_for_official_endpoint() {
        ModelProviderConfig config = new ModelProviderConfig(
                "deepseek-official",
                "DeepSeek 官方",
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-v4-pro",
                "sk-fake-key",
                "https://api.deepseek.com",
                null
        );

        ChatModel chatModel = factory.build(config);
        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.getDefaultOptions();

        assertThat(chatModel).isInstanceOf(DeepSeekV4OpenAiChatModel.class);
        assertThat(options.getExtraBody())
                .containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    @DisplayName("OpenAI 兼容流式工具调用必须保留 DeepSeek usage 请求参数")
    void build_should_keep_stream_usage_when_tool_options_are_merged() throws Exception {
        ModelProviderConfig config = new ModelProviderConfig(
                "deepseek-official",
                "DeepSeek 官方",
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-v4-pro",
                "sk-fake-key",
                "https://api.deepseek.com",
                null
        );
        OpenAiChatModel chatModel = (OpenAiChatModel) factory.build(config);
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(fakeTool())
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(new UserMessage("你好"), toolOptions);

        Prompt requestPrompt = invokeBuildRequestPrompt(chatModel, prompt);
        OpenAiApi.ChatCompletionRequest request = invokeCreateRequest(chatModel, requestPrompt, true);

        assertThat(request.streamOptions()).isNotNull();
        assertThat(request.streamOptions().includeUsage()).isTrue();
        assertThat(request.extraBody())
                .containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    @DisplayName("非 DeepSeek V4 兼容端点不注入 DeepSeek 私有 thinking 参数")
    void build_should_not_add_deepseek_extra_body_for_other_openai_compatible_models() {
        ModelProviderConfig config = new ModelProviderConfig(
                "oneapi-relay",
                "我的中转",
                ProviderType.OPENAI_COMPATIBLE,
                "gpt-4o",
                "sk-fake-key",
                "https://relay.example.com/v1",
                null
        );

        OpenAiChatModel chatModel = (OpenAiChatModel) factory.build(config);
        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.getDefaultOptions();

        assertThat(options.getExtraBody()).isNull();
    }

    @Test
    @DisplayName("Ollama 风格配置用占位 key 也能构建 ChatModel")
    void build_should_create_chat_model_for_ollama_style_config() {
        ModelProviderConfig config = new ModelProviderConfig(
                "ollama-local",
                "本地 Llama3",
                ProviderType.OPENAI_COMPATIBLE,
                "llama3:8b",
                "ollama",
                "http://localhost:11434/v1",
                null
        );

        assertThat(factory.build(config)).isNotNull();
    }

    @Test
    @DisplayName("缺 base-url 时抛出带 provider id 的清晰错误")
    void build_should_reject_missing_base_url() {
        ModelProviderConfig config = new ModelProviderConfig(
                "oneapi-relay",
                null,
                ProviderType.OPENAI_COMPATIBLE,
                "gpt-4o",
                "sk-fake-key",
                null,
                null
        );

        assertThatThrownBy(() -> factory.build(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url")
                .hasMessageContaining("oneapi-relay");
    }

    @Test
    @DisplayName("缺 api-key 时抛出带 provider id 的清晰错误")
    void build_should_reject_missing_api_key() {
        ModelProviderConfig config = new ModelProviderConfig(
                "deepseek-official",
                null,
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-chat",
                null,
                "https://api.deepseek.com",
                null
        );

        assertThatThrownBy(() -> factory.build(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key")
                .hasMessageContaining("deepseek-official");
    }

    private static Prompt invokeBuildRequestPrompt(OpenAiChatModel chatModel, Prompt prompt) throws Exception {
        Method method = OpenAiChatModel.class.getDeclaredMethod("buildRequestPrompt", Prompt.class);
        method.setAccessible(true);
        return (Prompt) method.invoke(chatModel, prompt);
    }

    private static OpenAiApi.ChatCompletionRequest invokeCreateRequest(OpenAiChatModel chatModel,
                                                                       Prompt prompt,
                                                                       boolean streaming) throws Exception {
        Method method = OpenAiChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
        method.setAccessible(true);
        return (OpenAiApi.ChatCompletionRequest) method.invoke(chatModel, prompt, streaming);
    }

    private static ToolCallback fakeTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("read_file")
                        .description("读取文件")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
