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
        log.info("JSON-RPC method 注册完成: count={}, methods={}", handlers.size(), handlers.keySet());
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
            log.warn("JSON-RPC 未知 method: requestId={}, method={}, availableMethods={}",
                    request.id(), request.method(), handlers.keySet());
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
        long startedNanos = System.nanoTime();
        try {
            // request.params 在 record 里是 Object，这里统一转为 JsonNode，方便各 handler 做字段校验。
            JsonNode params = request.params() == null
                    ? objectMapper.nullNode()
                    : objectMapper.valueToTree(request.params());
            log.debug("JSON-RPC method 开始执行: requestId={}, method={}, handler={}, sessionId={}, params={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    session == null ? "null" : session.getId(),
                    JsonRpcLogSupport.paramsSummary(params));
            Object responsePayload = handler.handle(params, session);
            log.info("JSON-RPC method 执行成功: requestId={}, method={}, handler={}, elapsedMs={}, resultType={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    JsonRpcLogSupport.elapsedMillis(startedNanos),
                    responsePayload == null ? "null" : responsePayload.getClass().getSimpleName());
            return JsonRpcMessage.Response.ok(request.id(), responsePayload);
        } catch (JsonRpcException jsonRpcException) {
            // JsonRpcException 是预期内的协议/业务错误，按它携带的标准 JSON-RPC 错误码返回。
            log.warn("JSON-RPC method 参数/业务错误: requestId={}, method={}, code={}, message={}, elapsedMs={}",
                    request.id(),
                    request.method(),
                    jsonRpcException.errorCode().code(),
                    JsonRpcLogSupport.preview(jsonRpcException.getMessage()),
                    JsonRpcLogSupport.elapsedMillis(startedNanos));
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    jsonRpcException.errorCode(),
                    jsonRpcException.getMessage(),
                    jsonRpcException.errorData());
        } catch (Exception exception) {
            // 未预期异常统一映射为 SERVER_ERROR，避免把 Java 栈细节泄露给桌面端协议。
            log.error("JSON-RPC method 执行失败: requestId={}, method={}, handler={}, elapsedMs={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    JsonRpcLogSupport.elapsedMillis(startedNanos),
                    exception);
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    JsonRpcErrorCode.SERVER_ERROR,
                    exception.getMessage(),
                    null);
        }
    }

    /**
     * 将 Spring 注入的 handler 列表整理为 method -> handler 映射。
     */
    private Map<String, JsonRpcMethodHandler> indexHandlers(List<JsonRpcMethodHandler> allHandlers) {
        Map<String, JsonRpcMethodHandler> indexedHandlers = new LinkedHashMap<>();
        for (JsonRpcMethodHandler handler : allHandlers) {
            // putIfAbsent 能在注册阶段直接发现重复 method，避免运行时路由到随机 handler。
            JsonRpcMethodHandler previousHandler = indexedHandlers.putIfAbsent(handler.method(), handler);
            if (previousHandler != null) {
                throw new IllegalStateException("JSON-RPC method 重复注册: " + handler.method());
            }
        }
        return Map.copyOf(indexedHandlers);
    }
}
