package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.memory.MemoryStatusService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC `memory/artifacts/list` 处理器。
 */
@Component
public class MemoryArtifactsListHandler implements JsonRpcMethodHandler {

    /** 长期记忆状态服务。 */
    private final MemoryStatusService service;

    public MemoryArtifactsListHandler(MemoryStatusService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "memory/artifacts/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.artifacts(MemoryJobsListHandler.limit(params));
    }
}
