package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.context.compaction.ContextManualCompactionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * context/compact JSON-RPC handler。
 *
 * <p>该接口给桌面端或调试工具一个手动压缩入口；它复用后端自动压缩链路，不允许前端直接提交摘要正文。</p>
 */
@Component
public class ContextCompactHandler implements JsonRpcMethodHandler {

    /** 手动压缩服务。 */
    private final ContextManualCompactionService service;

    /**
     * 创建 context/compact handler。
     *
     * @param service 手动压缩服务
     */
    public ContextCompactHandler(ContextManualCompactionService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "context/compact";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.compact(ContextStatusHandler.requiredText(params, "threadId"));
    }
}
