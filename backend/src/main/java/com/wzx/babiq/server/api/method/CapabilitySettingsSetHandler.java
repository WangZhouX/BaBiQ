package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.capability.CapabilityCatalogService;
import com.wzx.babiq.server.capability.CapabilityExposureMode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * capability/settings/set JSON-RPC handler。
 *
 * <p>它只允许修改能力开关和暴露模式，不接受任意工具 schema 或命令文本，避免 UI 成为越权入口。</p>
 */
@Component
public class CapabilitySettingsSetHandler implements JsonRpcMethodHandler {

    /** 能力目录应用服务。 */
    private final CapabilityCatalogService service;

    public CapabilitySettingsSetHandler(CapabilityCatalogService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "capability/settings/set";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String capabilityId = ContextStatusHandler.requiredText(params, "capabilityId");
        Boolean enabled = booleanOrNull(params, "enabled");
        CapabilityExposureMode exposureMode = exposureModeOrNull(params);
        try {
            return service.updateSettings(capabilityId, enabled, exposureMode);
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    private static Boolean booleanOrNull(JsonNode params, String name) {
        JsonNode value = params == null ? null : params.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static CapabilityExposureMode exposureModeOrNull(JsonNode params) {
        JsonNode value = params == null ? null : params.get("exposureMode");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return CapabilityExposureMode.valueOf(value.asText());
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "未知能力暴露模式: " + value.asText());
        }
    }
}
