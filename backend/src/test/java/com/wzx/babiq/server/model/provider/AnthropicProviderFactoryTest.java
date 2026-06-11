package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderAuthMode;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicProviderFactoryTest {

    @Test
    @DisplayName("supports 返回 ANTHROPIC")
    void supports_should_return_anthropic_provider_type() {
        AnthropicProviderFactory factory = new AnthropicProviderFactory(mock(AnthropicOAuthCredentialSource.class));

        assertThat(factory.supports()).isEqualTo(ProviderType.ANTHROPIC);
    }

    @Test
    @DisplayName("api_key 模式直接构建 AnthropicChatModel 且不读取 OAuth token")
    void api_key_mode_should_build_chat_model_without_oauth_token() {
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        AnthropicProviderFactory factory = new AnthropicProviderFactory(credentialSource);

        ChatModel chatModel = factory.build(new ModelProviderConfig(
                "claude-api-key",
                "Claude API Key",
                ProviderType.ANTHROPIC,
                ProviderAuthMode.API_KEY,
                "claude-sonnet-4-6",
                "sk-ant-test",
                "",
                1_000_000));

        assertThat(chatModel).isNotNull();
        verify(credentialSource, never()).accessToken();
    }

    @Test
    @DisplayName("oauth_cli 模式构建模型前读取 ant CLI access token")
    void oauth_cli_mode_should_request_access_token_before_building_model() {
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        when(credentialSource.accessToken()).thenReturn("oauth-token");
        AnthropicProviderFactory factory = new AnthropicProviderFactory(credentialSource);

        ChatModel chatModel = factory.build(new ModelProviderConfig(
                "claude-oauth",
                "Claude OAuth",
                ProviderType.ANTHROPIC,
                ProviderAuthMode.OAUTH_CLI,
                "claude-sonnet-4-6",
                null,
                "",
                1_000_000));

        assertThat(chatModel).isNotNull();
        verify(credentialSource).accessToken();
    }
}
