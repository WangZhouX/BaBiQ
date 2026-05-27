package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.capability.CapabilityCatalogService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * capability/status JSON-RPC handler。
 *
 * <p>桌面端设置页通过它读取当前本地工具、MCP 工具和 Skill 的统一能力目录。</p>
 */
@Component
public class CapabilityStatusHandler implements JsonRpcMethodHandler {

    /** 能力目录应用服务。 */
    private final CapabilityCatalogService service;

    public CapabilityStatusHandler(CapabilityCatalogService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "capability/status";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.status();
    }
}
