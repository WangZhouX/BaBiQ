package com.wzx.babiq.server.application.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** 通过既有业务 WebSocket 发送服务端主动 JSON-RPC request。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationOutboundJsonRpcClient {

    private final ObjectMapper objectMapper;
    private final ApplicationOutboundRequestTracker tracker;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ApplicationOutboundJsonRpcClient(
            ObjectMapper objectMapper,
            ApplicationOutboundRequestTracker tracker) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /** 注册 correlation 后线程安全写入 session；发送失败会立即清理 pending。 */
    public CompletableFuture<JsonRpcMessage> request(
            WebSocketSession session,
            String method,
            Object params,
            Duration timeout) {
        Objects.requireNonNull(session, "session");
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        long requestId = nextRequestId.getAndIncrement();
        CompletableFuture<JsonRpcMessage> future = tracker.register(session.getId(), requestId, timeout);
        JsonRpcMessage.Request request = new JsonRpcMessage.Request("2.0", requestId, method, params);
        try {
            String payload = objectMapper.writeValueAsString(request);
            ApplicationProtocolValidator.validateEnvelopeSize(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
            return future;
        } catch (IOException | RuntimeException exception) {
            tracker.fail(session.getId(), requestId, exception);
            throw new IllegalStateException("Cannot send outbound application request", exception);
        }
    }

    public void registerSession(WebSocketSession session) {
        Objects.requireNonNull(session, "session");
        sessions.put(session.getId(), session);
    }

    public void unregisterSession(String sessionId, WebSocketSession session) {
        if (sessionId != null && session != null) {
            sessions.remove(sessionId, session);
        }
    }

    public CompletableFuture<JsonRpcMessage> request(
            String sessionId,
            String method,
            Object params,
            Duration timeout) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("business desktop WebSocket session is unavailable"));
        }
        return request(session, method, params, timeout);
    }

    /** Sends a server-initiated JSON-RPC notification to one registered desktop session. */
    public void sendNotification(String sessionId, String method, Object params) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("business desktop WebSocket session is unavailable");
        }
        try {
            String payload = objectMapper.writeValueAsString(JsonRpcMessage.Notification.of(method, params));
            ApplicationProtocolValidator.validateEnvelopeSize(
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot send outbound application notification", exception);
        }
    }
}
