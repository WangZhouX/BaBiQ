package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 方法路由器。
 *
 * <p>WebSocket handler 只负责解析报文和写回响应,真正的 method 分发集中在这里。
 * 这样错误码映射、未知方法处理和 handler 异常保护都只有一份实现。</p>
 */
@Component
public class JsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    private final Map<String, JsonRpcMethodHandler> handlers;
    private final ObjectMapper objectMapper;

    /**
     * 创建 dispatcher 并注册所有 method handler。
     *
     * @param allHandlers Spring 注入的所有 handler
     * @param objectMapper JSON 参数转换器
     * @throws IllegalStateException 出现重复 method 时抛出
     */
    public JsonRpcDispatcher(List<JsonRpcMethodHandler> allHandlers, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.handlers = indexHandlers(allHandlers);
    }

    /**
     * 分发 JSON-RPC request 并返回 response/error response。
     *
     * @param request 已解析的 JSON-RPC request
     * @param session 当前 WebSocket 会话
     * @return 成功响应或错误响应
     */
    public JsonRpcMessage dispatch(JsonRpcMessage.Request request, WebSocketSession session) {
        JsonRpcMethodHandler handler = handlers.get(request.method());
        if (handler == null) {
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    JsonRpcErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + request.method(),
                    null);
        }

        return callHandler(request, session, handler);
    }

    private JsonRpcMessage callHandler(
            JsonRpcMessage.Request request,
            WebSocketSession session,
            JsonRpcMethodHandler handler) {
        try {
            JsonNode params = request.params() == null
                    ? objectMapper.nullNode()
                    : objectMapper.valueToTree(request.params());
            Object responsePayload = handler.handle(params, session);
            return JsonRpcMessage.Response.ok(request.id(), responsePayload);
        } catch (JsonRpcException jsonRpcException) {
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    jsonRpcException.errorCode(),
                    jsonRpcException.getMessage(),
                    jsonRpcException.errorData());
        } catch (Exception exception) {
            log.error("JSON-RPC method={} 执行失败", request.method(), exception);
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    JsonRpcErrorCode.SERVER_ERROR,
                    exception.getMessage(),
                    null);
        }
    }

    private Map<String, JsonRpcMethodHandler> indexHandlers(List<JsonRpcMethodHandler> allHandlers) {
        Map<String, JsonRpcMethodHandler> indexedHandlers = new LinkedHashMap<>();
        for (JsonRpcMethodHandler handler : allHandlers) {
            JsonRpcMethodHandler previousHandler = indexedHandlers.putIfAbsent(handler.method(), handler);
            if (previousHandler != null) {
                throw new IllegalStateException("JSON-RPC method 重复注册: " + handler.method());
            }
        }
        return Map.copyOf(indexedHandlers);
    }
}
