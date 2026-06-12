package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderAuthMode;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Anthropic Claude 官方 Messages API ProviderFactory。
 *
 * <p>API Key 模式直接复用 Spring AI 的 {@code x-api-key} 链路；OAuth CLI 模式传入空
 * {@code SimpleApiKey} 抑制 API Key 头，并在 RestClient/WebClient 的真实请求前动态读取
 * 官方 {@code ant} CLI access token。这样 ChatClient 可以继续缓存，而短期 Bearer token
 * 不会被冻结在 build 阶段。
 */
@Component
public class AnthropicProviderFactory implements ProviderFactory {

    /** Anthropic Messages API 强制要求 max_tokens；这里给 Agent 场景一个明确输出预算。 */
    private static final int DEFAULT_MAX_TOKENS = 4096;
    /** 官方 OAuth CLI 凭证链要求追加的 Anthropic beta feature。 */
    private static final String OAUTH_BETA = "oauth-2025-04-20";

    /** OAuth CLI access token 来源；只在 OAuth 模式的真实 HTTP 请求前读取。 */
    private final AnthropicOAuthCredentialSource credentialSource;

    public AnthropicProviderFactory(AnthropicOAuthCredentialSource credentialSource) {
        this.credentialSource = credentialSource;
    }

    @Override
    public ProviderType supports() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public ChatModel build(ModelProviderConfig config) {
        ProviderAuthMode authMode = config.effectiveAuthMode();
        String apiKey = authMode == ProviderAuthMode.API_KEY ? requireApiKey(config) : "";

        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .baseUrl(effectiveBaseUrl(config.baseUrl()))
                .apiKey(new SimpleApiKey(apiKey));
        if (authMode == ProviderAuthMode.OAUTH_CLI) {
            apiBuilder.anthropicBetaFeatures(withOAuthBeta())
                    .restClientBuilder(oauthRestClientBuilder())
                    .webClientBuilder(oauthWebClientBuilder());
        }

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(DEFAULT_MAX_TOKENS);

        return AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    private static String requireApiKey(ModelProviderConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalArgumentException("Provider 缺少 Anthropic API Key: " + config.id());
        }
        return config.apiKey();
    }

    private static String effectiveBaseUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? AnthropicApi.DEFAULT_BASE_URL : baseUrl;
    }

    private RestClient.Builder oauthRestClientBuilder() {
        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(credentialSource.accessToken());
                    return execution.execute(request, body);
                });
    }

    private WebClient.Builder oauthWebClientBuilder() {
        return WebClient.builder()
                .filter(ExchangeFilterFunction.ofRequestProcessor(request -> Mono.fromSupplier(() ->
                        ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(credentialSource.accessToken()))
                                .build())));
    }

    private static String withOAuthBeta() {
        Set<String> betas = new LinkedHashSet<>();
        betas.add(AnthropicApi.DEFAULT_ANTHROPIC_BETA_VERSION);
        betas.add(OAUTH_BETA);
        return String.join(",", betas);
    }
}
