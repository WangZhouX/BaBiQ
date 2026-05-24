package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.settings.ProviderSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `provider/test` JSON-RPC handler。
 *
 * <p>P2-3 的测试连接只做轻量配置构建检查，不主动向模型发送真实 prompt。</p>
 */
@Component
public class ProviderTestHandler implements JsonRpcMethodHandler {

    /** Provider 设置服务。 */
    private final ProviderSettingsService providerSettingsService;

    public ProviderTestHandler(ProviderSettingsService providerSettingsService) {
        this.providerSettingsService = providerSettingsService;
    }

    @Override
    public String method() {
        return "provider/test";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String providerId = requiredText(params, "providerId");
        ProviderSettingsService.ProviderTestResult result = providerSettingsService.testConnection(providerId);
        return Map.of("ok", result.ok(), "providerId", result.providerId(), "message", result.message());
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
