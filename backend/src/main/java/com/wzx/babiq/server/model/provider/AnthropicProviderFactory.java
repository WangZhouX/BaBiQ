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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Claude 官方 Messages API ProviderFactory。
 */
@Component
public class AnthropicProviderFactory implements ProviderFactory {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String OAUTH_BETA = "oauth-2025-04-20";

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
        String token = authMode == ProviderAuthMode.OAUTH_CLI ? credentialSource.accessToken() : null;
        String apiKey = authMode == ProviderAuthMode.API_KEY ? requireApiKey(config) : "";

        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .baseUrl(effectiveBaseUrl(config.baseUrl()))
                .apiKey(new SimpleApiKey(apiKey));
        if (authMode == ProviderAuthMode.OAUTH_CLI) {
            apiBuilder.anthropicBetaFeatures(withOAuthBeta());
        }

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(config.model())
                .maxTokens(DEFAULT_MAX_TOKENS);
        if (authMode == ProviderAuthMode.OAUTH_CLI) {
            optionsBuilder.httpHeaders(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        }

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

    private static String withOAuthBeta() {
        Set<String> betas = new LinkedHashSet<>();
        betas.add(AnthropicApi.DEFAULT_ANTHROPIC_BETA_VERSION);
        betas.add(OAUTH_BETA);
        return String.join(",", betas);
    }
}
