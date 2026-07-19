package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.settings.AnthropicOAuthCredentialSource;
import com.wzx.babiq.server.settings.AnthropicOAuthStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `provider/oauth/status` JSON-RPC handler。
 */
@Component
public class ProviderOAuthStatusHandler implements JsonRpcMethodHandler {

    private final AnthropicOAuthCredentialSource credentialSource;

    public ProviderOAuthStatusHandler(AnthropicOAuthCredentialSource credentialSource) {
        this.credentialSource = credentialSource;
    }

    @Override
    public String method() {
        return "provider/oauth/status";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        AnthropicOAuthStatus status;
        try {
            status = credentialSource.status();
        } catch (RuntimeException exception) {
            status = new AnthropicOAuthStatus(false, false, "未登录");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("providerType", "ANTHROPIC");
        payload.put("authMode", "oauth_cli");
        payload.put("cliInstalled", status.cliInstalled());
        payload.put("loggedIn", status.loggedIn());
        payload.put("message", status.loggedIn() ? "已登录" : "未登录");
        return payload;
    }
}
