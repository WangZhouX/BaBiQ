package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.DeepSeekV4OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 协议兼容 Provider 工厂。
 *
 * <p>DeepSeek 官方、OneAPI 中转、Ollama OpenAI 兼容端口都遵循 OpenAI 风格
 * chat/completions 协议,因此统一从这里构建 ChatModel。DeepSeek V4 thinking mode
 * 对 tool_call 历史有额外的 reasoning_content 回放要求，会路由到专用适配器；
 * 其他兼容端点继续使用 Spring AI 原生 OpenAiChatModel。</p>
 */
@Component
public class OpenAiCompatibleProviderFactory implements ProviderFactory {
    /** Provider 构建日志，排查线上是否真的命中 DeepSeek V4 专用适配器时使用。 */
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProviderFactory.class);

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
        boolean deepSeekV4Official = isDeepSeekV4Official(config);
        OpenAiChatOptions.Builder chatOptionsBuilder = OpenAiChatOptions.builder()
                .model(config.model())
                // OpenAI-compatible 流式接口默认不一定返回 usage；开启后 Provider 才会在最后一个 chunk 带回 token 统计。
                .streamUsage(true);
        if (deepSeekV4Official) {
            // 2026-05-25 修复记录：
            // 用户要求保留 DeepSeek V4 thinking mode，而不是用 thinking.disabled 绕过问题。
            // 因此这里显式开启 thinking，并把后续 reasoning_content 回放交给 DeepSeekV4OpenAiChatModel。
            chatOptionsBuilder.extraBody(Map.of("thinking", Map.of("type", "enabled")));
        }
        OpenAiChatOptions chatOptions = chatOptionsBuilder.build();

        if (deepSeekV4Official) {
            // 2026-05-25 诊断记录：
            // 用户环境多次重启后仍返回 DeepSeek 官方 400。这里仅打印脱敏后的路由信息，
            // 不输出 api-key，方便确认真实进程是否加载并使用了 DeepSeek V4 专用适配器。
            log.info("OpenAI-compatible provider [{}] 使用 DeepSeek V4 官方适配器: baseUrl={}, model={}, chatModel={}",
                    config.id(), normalize(config.baseUrl()), config.model(), DeepSeekV4OpenAiChatModel.class.getName());
            return new DeepSeekV4OpenAiChatModel(openAiApi, chatOptions);
        }

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

    private static boolean isDeepSeekV4Official(ModelProviderConfig config) {
        String baseUrl = normalize(config.baseUrl());
        String model = normalize(config.model());
        return baseUrl.equals("https://api.deepseek.com")
                && (model.startsWith("deepseek-v4-pro") || model.startsWith("deepseek-v4-flash"));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
