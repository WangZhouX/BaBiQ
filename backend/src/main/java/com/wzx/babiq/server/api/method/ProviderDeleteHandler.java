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
 * `provider/delete` JSON-RPC handler。
 *
 * <p>P2-3 删除采用软删除：禁用 Provider 配置，但不触碰历史 turn。</p>
 */
@Component
public class ProviderDeleteHandler implements JsonRpcMethodHandler {

    /** Provider 设置服务。 */
    private final ProviderSettingsService providerSettingsService;

    public ProviderDeleteHandler(ProviderSettingsService providerSettingsService) {
        this.providerSettingsService = providerSettingsService;
    }

    @Override
    public String method() {
        return "provider/delete";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String providerId = requiredText(params, "providerId");
        try {
            ProviderSettingsService.ProviderDeleteResult result = providerSettingsService.delete(providerId);
            return Map.of(
                    "ok", true,
                    "providerId", result.providerId(),
                    "activeProviderId", result.activeProviderId());
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Provider 删除请求无效");
        }
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
