package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.memory.LongTermMemoryPipeline;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC `memory/consolidate` 处理器。
 */
@Component
public class MemoryConsolidateHandler implements JsonRpcMethodHandler {

    /** 长期记忆流水线。 */
    private final LongTermMemoryPipeline pipeline;

    public MemoryConsolidateHandler(LongTermMemoryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public String method() {
        return "memory/consolidate";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        boolean force = params != null && params.get("force") != null && params.get("force").asBoolean(false);
        return pipeline.consolidate(force);
    }
}
