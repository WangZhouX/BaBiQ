package com.wzx.babiq.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON-RPC WebSocket 主入口。
 *
 * <p>该 handler 只负责 WebSocket 文本帧的协议壳处理:解析 request、校验基础
 * JSON-RPC envelope、调用 dispatcher、写回 response。具体 method 业务必须放在
 * JsonRpcMethodHandler 中,避免入口类变成巨型路由表。</p>
 */
@Component
public class JsonRpcWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcWebSocketHandler.class);

    private final JsonRpcDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 创建 WebSocket handler。
     *
     * @param dispatcher JSON-RPC 方法路由器
     * @param objectMapper JSON 序列化器
     */
    public JsonRpcWebSocketHandler(JsonRpcDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录连接建立事件。
     *
     * @param session 新建立的 WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 已连接: sessionId={}, remote={}, uri={}",
                session.getId(), session.getRemoteAddress(), session.getUri());
    }

    /**
     * 处理客户端文本帧。
     *
     * @param session 当前 WebSocket 会话
     * @param message 客户端发送的文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        long startedNanos = System.nanoTime();
        JsonRpcMessage response = handleRequest(session, message.getPayload(), startedNanos);
        sendResponse(session, response);
    }

    /**
     * 记录连接关闭事件。
     *
     * @param session 被关闭的 WebSocket 会话
     * @param status 关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 已关闭: sessionId={}, status={}", session.getId(), status);
    }

    private JsonRpcMessage handleRequest(WebSocketSession session, String payload, long startedNanos) {
        Long requestId = null;
        String method = "<parse-failed>";
        try {
            JsonRpcMessage.Request request = objectMapper.readValue(payload, JsonRpcMessage.Request.class);
            requestId = request.id();
            method = request.method();
            log.info("JSON-RPC 请求进入: sessionId={}, requestId={}, method={}, payloadBytes={}, params={}",
                    session.getId(),
                    requestId,
                    method,
                    payload.getBytes(StandardCharsets.UTF_8).length,
                    JsonRpcLogSupport.paramsSummary(objectMapper.valueToTree(request.params())));
            if (!isValidEnvelope(request)) {
                log.warn("JSON-RPC envelope 非法: sessionId={}, requestId={}, method={}, payload={}",
                        session.getId(), requestId, method, JsonRpcLogSupport.preview(payload));
                return JsonRpcMessage.ErrorResponse.of(
                        requestId,
                        JsonRpcErrorCode.INVALID_REQUEST,
                        "Invalid JSON-RPC envelope",
                        null);
            }
            JsonRpcMessage response = dispatcher.dispatch(request, session);
            log.info("JSON-RPC 请求完成: sessionId={}, requestId={}, method={}, response={}, elapsedMs={}",
                    session.getId(),
                    requestId,
                    method,
                    responseSummary(response),
                    JsonRpcLogSupport.elapsedMillis(startedNanos));
            return response;
        } catch (JsonProcessingException exception) {
            log.warn("JSON-RPC 解析失败: sessionId={}, payloadBytes={}, error={}, payloadPreview={}",
                    session.getId(),
                    payload.getBytes(StandardCharsets.UTF_8).length,
                    exception.getOriginalMessage(),
                    JsonRpcLogSupport.preview(payload));
            return JsonRpcMessage.ErrorResponse.of(
                    null,
                    JsonRpcErrorCode.PARSE_ERROR,
                    "Parse error: " + exception.getOriginalMessage(),
                    null);
        } catch (Exception exception) {
            log.error("WebSocket 请求处理失败: sessionId={}, requestId={}, method={}, elapsedMs={}",
                    session.getId(), requestId, method, JsonRpcLogSupport.elapsedMillis(startedNanos), exception);
            return JsonRpcMessage.ErrorResponse.of(
                    requestId,
                    JsonRpcErrorCode.INTERNAL_ERROR,
                    exception.getMessage(),
                    null);
        }
    }

    private boolean isValidEnvelope(JsonRpcMessage.Request request) {
        return "2.0".equals(request.jsonrpc())
                && request.id() != null
                && request.method() != null
                && !request.method().isBlank();
    }

    private void sendResponse(WebSocketSession session, JsonRpcMessage response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            // 与 ItemEmitter 一样同步写 session,防止同步响应和异步 notification 并发写同一连接。
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException exception) {
            log.error("WebSocket 响应发送失败: sessionId={}", session.getId(), exception);
        }
    }

    private String responseSummary(JsonRpcMessage response) {
        if (response instanceof JsonRpcMessage.Response success) {
            Object result = success.result();
            return "ok:" + (result == null ? "null" : result.getClass().getSimpleName());
        }
        if (response instanceof JsonRpcMessage.ErrorResponse errorResponse) {
            return "error:" + errorResponse.error().code() + ":" + JsonRpcLogSupport.preview(errorResponse.error().message());
        }
        return response.getClass().getSimpleName();
    }
}
