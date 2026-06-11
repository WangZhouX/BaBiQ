package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.settings.ProviderSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * `provider/create` JSON-RPC handler。
 *
 * <p>handler 只做字段校验和 DTO 转换，API Key 交给 ProviderSettingsService 后立刻进入 SecretStore，
 * 本类不会记录或回显明文。</p>
 */
@Component
public class ProviderCreateHandler implements JsonRpcMethodHandler {

    /** Provider 设置服务。 */
    private final ProviderSettingsService providerSettingsService;
    /** JSON 转换器，用来把 JsonNode 转成草稿 record。 */
    private final ObjectMapper objectMapper;

    public ProviderCreateHandler(ProviderSettingsService providerSettingsService, ObjectMapper objectMapper) {
        this.providerSettingsService = providerSettingsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String method() {
        return "provider/create";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        ProviderSettingsService.ProviderDraft draft = draftFrom(params, true);
        try {
            return ProviderPayloadMapper.toPayload(providerSettingsService.create(draft));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    ProviderSettingsService.ProviderDraft draftFrom(JsonNode params, boolean requireApiKey) {
        String providerId = requiredText(params, "providerId");
        String displayName = requiredText(params, "displayName");
        String type = requiredText(params, "type");
        String authMode = optionalText(params, "authMode");
        String baseUrl = optionalText(params, "baseUrl");
        String model = requiredText(params, "model");
        String apiKey = optionalText(params, "apiKey");
        int contextWindow = params != null && params.hasNonNull("contextWindow") ? params.get("contextWindow").asInt(0) : 0;
        boolean enabled = params == null || !params.hasNonNull("enabled") || params.get("enabled").asBoolean(true);
        return new ProviderSettingsService.ProviderDraft(providerId, displayName, type, authMode, baseUrl, model,
                apiKey, contextWindow, enabled);
    }

    private String requiredText(JsonNode params, String fieldName) {
        String value = optionalText(params, fieldName);
        if (value == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return value;
    }

    private String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }
}
