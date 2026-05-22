package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * OpenAI 协议兼容 Provider 工厂。
 *
 * <p>DeepSeek 官方、OneAPI 中转、Ollama OpenAI 兼容端口都遵循同一套
 * chat/completions 协议,因此统一由该工厂构建 OpenAiChatModel。Ollama 这类
 * 本地服务不校验 key,仍要求配置一个占位字符串,让配置形态和远端 provider 保持一致。</p>
 */
@Component
public class OpenAiCompatibleProviderFactory implements ProviderFactory {

    /**
     * 返回 OpenAI 兼容工厂支持的 Provider 类型。
     *
     * @return {@link ProviderType#OPENAI_COMPATIBLE}
     */
    @Override
    public ProviderType supports() {
        return ProviderType.OPENAI_COMPATIBLE;
    }

    /**
     * 构建 OpenAI 兼容 ChatModel。
     *
     * @param config OpenAI 兼容 provider 配置
     * @return 使用指定 base-url、api-key 和模型名的 OpenAiChatModel
     * @throws IllegalStateException base-url 或 api-key 为空时抛出
     */
    @Override
    public ChatModel build(ModelProviderConfig config) {
        requireText(config.baseUrl(), "base-url", config);
        requireText(config.apiKey(), "api-key", config);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .build();
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(config.model())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();
    }

    private static void requireText(String value, String fieldName, ModelProviderConfig config) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Provider [" + config.id() + "] (type=OPENAI_COMPATIBLE) 缺少 " + fieldName
                            + ",请检查 babiq.providers 中的配置。");
        }
    }
}
