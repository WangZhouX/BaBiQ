package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.context.ContextStatusService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * context/status JSON-RPC handler。
 *
 * <p>桌面端输入框上下文 chip 通过该接口读取当前 thread 的上下文窗口使用情况。</p>
 */
@Component
public class ContextStatusHandler implements JsonRpcMethodHandler {

    /** 上下文窗口查询服务。 */
    private final ContextStatusService service;

    /**
     * 创建 context/status handler。
     *
     * @param service 上下文窗口查询服务
     */
    public ContextStatusHandler(ContextStatusService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "context/status";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return service.status(requiredText(params, "threadId"));
    }

    static String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
