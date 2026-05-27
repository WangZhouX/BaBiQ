package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.capability.CapabilityCatalogService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * capability/search JSON-RPC handler。
 *
 * <p>这是给 UI 使用的检索接口；模型内部发现能力走 `tool_search` 工具并会单独写审计。</p>
 */
@Component
public class CapabilitySearchHandler implements JsonRpcMethodHandler {

    /** 能力目录应用服务。 */
    private final CapabilityCatalogService service;

    public CapabilitySearchHandler(CapabilityCatalogService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "capability/search";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String query = params == null || !params.hasNonNull("query") ? "" : params.get("query").asText();
        int limit = params == null || !params.hasNonNull("limit") ? 8 : params.get("limit").asInt(8);
        boolean recordEvent = params != null && params.hasNonNull("recordEvent") && params.get("recordEvent").asBoolean();
        return service.search(query, limit, recordEvent);
    }
}
