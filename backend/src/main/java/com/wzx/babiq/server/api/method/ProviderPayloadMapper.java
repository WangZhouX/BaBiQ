package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.settings.ProviderSettingsService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 设置响应映射器。
 *
 * <p>集中做一件事：把服务层 ProviderView 转成 JSON-RPC 响应 Map，同时永远不输出 apiKey。</p>
 */
final class ProviderPayloadMapper {

    private ProviderPayloadMapper() {
    }

    static Map<String, Object> toPayload(ProviderSettingsService.ProviderView view) {
        // Map.of 不允许 value 为 null；这里用 LinkedHashMap 保持响应字段顺序，也允许 baseUrl 这类可选字段安全输出。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", view.id());
        payload.put("label", view.displayName());
        payload.put("displayName", view.displayName());
        payload.put("type", view.type());
        payload.put("authMode", view.authMode());
        payload.put("baseUrl", view.baseUrl() == null ? "" : view.baseUrl());
        payload.put("model", view.model());
        payload.put("contextWindow", view.contextWindow());
        payload.put("enabled", view.enabled());
        payload.put("hasApiKey", view.hasApiKey());
        payload.put("active", view.active());
        payload.put("models", List.of(Map.of(
                "id", view.model(),
                "label", view.model(),
                "active", view.active()
        )));
        return payload;
    }
}
