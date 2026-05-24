package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.settings.ProviderSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `provider/list` JSON-RPC handler。
 *
 * <p>新设置页优先使用这个方法；P1 的 `model/providers/list` 保留兼容。</p>
 */
@Component
public class ProviderListHandler implements JsonRpcMethodHandler {

    /** Provider 设置服务。 */
    private final ProviderSettingsService providerSettingsService;

    public ProviderListHandler(ProviderSettingsService providerSettingsService) {
        this.providerSettingsService = providerSettingsService;
    }

    @Override
    public String method() {
        return "provider/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("providers", providerSettingsService.listEnabled().stream()
                .map(ProviderPayloadMapper::toPayload)
                .toList());
    }
}
