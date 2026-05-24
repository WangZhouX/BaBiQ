package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

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
}
