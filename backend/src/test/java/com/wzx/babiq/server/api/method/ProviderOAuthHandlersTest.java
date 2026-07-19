package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.settings.AntCliLoginLauncher;
import com.wzx.babiq.server.settings.AntCliLoginStartResult;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import com.wzx.babiq.server.settings.AnthropicOAuthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderOAuthHandlersTest {

    @Test
    @DisplayName("provider/oauth/status 返回 ant CLI 和登录状态")
    void provider_oauth_status_should_return_cli_and_login_state() {
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        when(credentialSource.status()).thenReturn(new AnthropicOAuthStatus(
                true, true, "cli=C:\\sensitive\\ant.exe token=sk-fake-sensitive-marker"));
        ProviderOAuthStatusHandler handler = new ProviderOAuthStatusHandler(credentialSource);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(handler.method()).isEqualTo("provider/oauth/status");
        assertThat(response)
                .containsEntry("providerType", "ANTHROPIC")
                .containsEntry("authMode", "oauth_cli")
                .containsEntry("cliInstalled", true)
                .containsEntry("loggedIn", true)
                .containsEntry("message", "已登录");
        assertThat(response.toString()).doesNotContain("sk-fake-sensitive-marker");
    }

    @Test
    @DisplayName("provider/oauth/login 只负责启动 ant auth login 进程")
    void provider_oauth_login_should_start_ant_login_process() {
        AntCliLoginLauncher launcher = mock(AntCliLoginLauncher.class);
        when(launcher.startLogin()).thenReturn(new AntCliLoginStartResult(
                true, 1234L, "output=sk-fake-sensitive-marker"));
        ProviderOAuthLoginHandler handler = new ProviderOAuthLoginHandler(launcher);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(handler.method()).isEqualTo("provider/oauth/login");
        assertThat(response)
                .containsEntry("ok", true)
                .containsEntry("pid", 1234L)
                .containsEntry("message", "登录已启动");
        assertThat(response.toString()).doesNotContain("sk-fake-sensitive-marker");
        verify(launcher).startLogin();
    }

    @Test
    @DisplayName("provider/oauth/status 内部异常只映射为未登录")
    void provider_oauth_status_should_hide_internal_exception() {
        AnthropicOAuthCredentialSource credentialSource = mock(AnthropicOAuthCredentialSource.class);
        when(credentialSource.status()).thenThrow(new IllegalStateException(
                "C:\\sensitive\\ant.exe token=sk-fake-sensitive-marker"));
        ProviderOAuthStatusHandler handler = new ProviderOAuthStatusHandler(credentialSource);

        Map<String, Object> response = responseFrom(handler.handle(null, null));

        assertThat(response)
                .containsEntry("cliInstalled", false)
                .containsEntry("loggedIn", false)
                .containsEntry("message", "未登录");
        assertThat(response.toString()).doesNotContain("sk-fake-sensitive-marker");
    }

    @Test
    @DisplayName("provider/oauth/login 启动失败或异常只映射为登录失败")
    void provider_oauth_login_should_hide_failure_details() {
        AntCliLoginLauncher launcher = mock(AntCliLoginLauncher.class);
        when(launcher.startLogin()).thenReturn(new AntCliLoginStartResult(
                false, null, "stderr=sk-fake-sensitive-marker"));
        ProviderOAuthLoginHandler handler = new ProviderOAuthLoginHandler(launcher);

        Map<String, Object> failedResponse = responseFrom(handler.handle(null, null));

        assertThat(failedResponse)
                .containsEntry("ok", false)
                .containsEntry("message", "登录失败");
        assertThat(failedResponse.toString()).doesNotContain("sk-fake-sensitive-marker");

        doThrow(new IllegalStateException("token=sk-fake-sensitive-marker"))
                .when(launcher).startLogin();
        Map<String, Object> exceptionResponse = responseFrom(handler.handle(null, null));
        assertThat(exceptionResponse)
                .containsEntry("ok", false)
                .containsEntry("message", "登录失败");
        assertThat(exceptionResponse.toString()).doesNotContain("sk-fake-sensitive-marker");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
