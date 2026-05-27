package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.memory.MemoryStatusService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC `memory/settings/set` 处理器。
 */
@Component
public class MemorySettingsSetHandler implements JsonRpcMethodHandler {

    /** 长期记忆状态服务。 */
    private final MemoryStatusService service;

    public MemorySettingsSetHandler(MemoryStatusService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "memory/settings/set";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.updateSettings(
                booleanOrNull(params, "enabled"),
                booleanOrNull(params, "generateEnabled"),
                booleanOrNull(params, "readEnabled"),
                booleanOrNull(params, "retrievalEnabled"));
    }

    private static Boolean booleanOrNull(JsonNode params, String name) {
        JsonNode value = params == null ? null : params.get(name);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
