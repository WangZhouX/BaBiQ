package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.settings.AntCliLoginLauncher;
import com.wzx.babiq.server.settings.AntCliLoginStartResult;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `provider/oauth/login` JSON-RPC handler。
 */
@Component
public class ProviderOAuthLoginHandler implements JsonRpcMethodHandler {

    private final AntCliLoginLauncher loginLauncher;

    public ProviderOAuthLoginHandler(AntCliLoginLauncher loginLauncher) {
        this.loginLauncher = loginLauncher;
    }

    @Override
    public String method() {
        return "provider/oauth/login";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        AntCliLoginStartResult result = loginLauncher.startLogin();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", result.ok());
        payload.put("pid", result.pid());
        payload.put("message", result.message());
        return payload;
    }
}
