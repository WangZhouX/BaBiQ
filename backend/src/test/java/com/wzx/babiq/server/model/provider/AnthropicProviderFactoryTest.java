package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderAuthMode;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void oauth_cli_mode_should_not_consume_access_token_while_building_model() {
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
        verify(credentialSource, never()).accessToken();
    }

    @Test
    @DisplayName("api_key 模式缺少 API Key 时立即失败")
    void api_key_mode_should_fail_fast_when_api_key_is_missing() {
        AnthropicProviderFactory factory = new AnthropicProviderFactory(mock(AnthropicOAuthCredentialSource.class));

        assertThatThrownBy(() -> factory.build(new ModelProviderConfig(
                "claude-api-key",
                "Claude API Key",
                ProviderType.ANTHROPIC,
                ProviderAuthMode.API_KEY,
                "claude-sonnet-4-6",
                " ",
                "",
                1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anthropic API Key")
                .hasMessageContaining("claude-api-key");
    }

    @Test
    @DisplayName("oauth_cli 模式每次请求动态注入 Bearer 且不发送 x-api-key")
    void oauth_cli_mode_should_inject_fresh_bearer_per_request_without_x_api_key() throws IOException {
        RecordingAnthropicServer server = RecordingAnthropicServer.start();
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        when(credentialSource.accessToken()).thenReturn("oauth-token-1", "oauth-token-2");
        AnthropicProviderFactory factory = new AnthropicProviderFactory(credentialSource);

        try {
            ChatModel chatModel = factory.build(new ModelProviderConfig(
                    "claude-oauth",
                    "Claude OAuth",
                    ProviderType.ANTHROPIC,
                    ProviderAuthMode.OAUTH_CLI,
                    "claude-sonnet-4-6",
                    null,
                    server.baseUrl(),
                    1_000_000));

            chatModel.call(new Prompt("first"));
            chatModel.call(new Prompt("second"));

            assertThat(server.authorizationHeaders())
                    .containsExactly("Bearer oauth-token-1", "Bearer oauth-token-2");
            assertThat(server.xApiKeyHeaders()).containsExactly(null, null);
            assertThat(server.betaHeaders())
                    .allSatisfy(header -> assertThat(header).contains("oauth-2025-04-20"));
            verify(credentialSource, times(2)).accessToken();
        } finally {
            server.stop();
        }
    }

    private static final class RecordingAnthropicServer {
        private final HttpServer server;
        private final List<String> authorizationHeaders = new ArrayList<>();
        private final List<String> xApiKeyHeaders = new ArrayList<>();
        private final List<String> betaHeaders = new ArrayList<>();

        private RecordingAnthropicServer(HttpServer server) {
            this.server = server;
        }

        static RecordingAnthropicServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RecordingAnthropicServer recordingServer = new RecordingAnthropicServer(server);
            server.createContext("/", recordingServer::handle);
            server.start();
            return recordingServer;
        }

        String baseUrl() {
            InetSocketAddress address = server.getAddress();
            return "http://" + address.getHostString() + ":" + address.getPort();
        }

        List<String> authorizationHeaders() {
            return authorizationHeaders;
        }

        List<String> xApiKeyHeaders() {
            return xApiKeyHeaders;
        }

        List<String> betaHeaders() {
            return betaHeaders;
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            xApiKeyHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            betaHeaders.add(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {
                      "id": "msg_test",
                      "type": "message",
                      "role": "assistant",
                      "content": [{"type": "text", "text": "ok"}],
                      "model": "claude-sonnet-4-6",
                      "stop_reason": "end_turn",
                      "usage": {"input_tokens": 1, "output_tokens": 1}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
