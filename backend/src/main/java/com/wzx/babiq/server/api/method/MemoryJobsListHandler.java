package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.memory.MemoryStatusService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC `memory/jobs/list` 处理器。
 */
@Component
public class MemoryJobsListHandler implements JsonRpcMethodHandler {

    /** 长期记忆状态服务。 */
    private final MemoryStatusService service;

    public MemoryJobsListHandler(MemoryStatusService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "memory/jobs/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        int limit = limit(params);
        return service.jobs(limit);
    }

    static int limit(JsonNode params) {
        int limit = params == null || params.get("limit") == null ? 20 : params.get("limit").asInt();
        if (limit < 1 || limit > 200) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "limit 必须在 1 到 200 之间");
        }
        return limit;
    }
}
