package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.settings.AntCliLoginLauncher;
import com.wzx.babiq.server.settings.AntCliLoginStartResult;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import com.wzx.babiq.server.settings.AnthropicOAuthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderOAuthHandlersTest {

    @Test
    @DisplayName("provider/oauth/status 返回 ant CLI 和登录状态")
    void provider_oauth_status_should_return_cli_and_login_state() {
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        when(credentialSource.status()).thenReturn(new AnthropicOAuthStatus(true, true, "已登录"));
        ProviderOAuthStatusHandler handler = new ProviderOAuthStatusHandler(credentialSource);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(handler.method()).isEqualTo("provider/oauth/status");
        assertThat(response)
                .containsEntry("providerType", "ANTHROPIC")
                .containsEntry("authMode", "oauth_cli")
                .containsEntry("cliInstalled", true)
                .containsEntry("loggedIn", true);
    }

    @Test
    @DisplayName("provider/oauth/login 只负责启动 ant auth login 进程")
    void provider_oauth_login_should_start_ant_login_process() {
        AntCliLoginLauncher launcher = mock(AntCliLoginLauncher.class);
        when(launcher.startLogin()).thenReturn(new AntCliLoginStartResult(true, 1234L, "已启动 ant auth login"));
        ProviderOAuthLoginHandler handler = new ProviderOAuthLoginHandler(launcher);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(handler.method()).isEqualTo("provider/oauth/login");
        assertThat(response)
                .containsEntry("ok", true)
                .containsEntry("pid", 1234L)
                .containsEntry("message", "已启动 ant auth login");
        verify(launcher).startLogin();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
