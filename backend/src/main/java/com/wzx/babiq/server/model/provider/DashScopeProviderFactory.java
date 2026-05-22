package com.wzx.babiq.server.model.provider;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 阿里 DashScope 原生 Provider 工厂。
 *
 * <p>该工厂使用 Spring AI Alibaba 的 DashScopeApi 和 DashScopeChatModel。
 * 它不在启动期主动联网,只构建本地客户端对象;缺少 api-key 时给出中文业务错误,
 * 避免后续调用阶段变成难排查的 NullPointerException。</p>
 */
@Component
public class DashScopeProviderFactory implements ProviderFactory {

    /**
     * 返回 DashScope 工厂支持的 Provider 类型。
     *
     * @return {@link ProviderType#DASHSCOPE}
     */
    @Override
    public ProviderType supports() {
        return ProviderType.DASHSCOPE;
    }

    /**
     * 构建 DashScope ChatModel。
     *
     * @param config DashScope provider 配置
     * @return 使用指定 api-key 和模型名的 DashScopeChatModel
     * @throws IllegalStateException api-key 为空时抛出
     */
    @Override
    public ChatModel build(ModelProviderConfig config) {
        requireText(config.apiKey(), "api-key", config);

        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(config.apiKey())
                .build();
        DashScopeChatOptions chatOptions = DashScopeChatOptions.builder()
                .model(config.model())
                .build();

        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(chatOptions)
                .build();
    }

    private static void requireText(String value, String fieldName, ModelProviderConfig config) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Provider [" + config.id() + "] (type=DASHSCOPE) 缺少 " + fieldName
                            + ",请在 application.yml 或环境变量中配置。");
        }
    }
}
