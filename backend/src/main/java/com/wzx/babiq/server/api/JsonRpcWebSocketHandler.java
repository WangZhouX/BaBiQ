package com.wzx.babiq.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final int MAX_PENDING_BUSINESS_REQUESTS = 32;

    /** JSON-RPC 分发器，负责根据 method 找到具体业务 handler。 */
    private final JsonRpcDispatcher dispatcher;
    /** WebSocket 文本帧与 JsonRpcMessage 之间的 Jackson 编解码器。 */
    private final ObjectMapper objectMapper;
    /** 仅 business-desktop profile 存在的可信连接注册表。 */
    private final BusinessDesktopConnectionRegistry businessDesktopConnectionRegistry;
    /** 仅 business profile 存在的服务端主动 request correlation。 */
    private final ApplicationOutboundRequestTracker outboundRequestTracker;
    private final PendingApplicationActions pendingApplicationActions;
    private final ApplicationOutboundJsonRpcClient outboundJsonRpcClient;
    private final ExecutorService businessRequestExecutor;
    private final Map<String, SerialRequestQueue> businessRequestQueues = new ConcurrentHashMap<>();

    /**
     * 创建 WebSocket handler。
     *
     * @param dispatcher JSON-RPC 方法路由器
     * @param objectMapper JSON 序列化器
     */
    public JsonRpcWebSocketHandler(
            JsonRpcDispatcher dispatcher,
            ObjectMapper objectMapper,
            ObjectProvider<BusinessDesktopConnectionRegistry> connectionRegistryProvider) {
        this(dispatcher, objectMapper, connectionRegistryProvider, null, null, null);
    }

    /** Spring 构造器可选接入业务 outbound tracker，普通 profile 保持 null。 */
    public JsonRpcWebSocketHandler(
            JsonRpcDispatcher dispatcher,
            ObjectMapper objectMapper,
            ObjectProvider<BusinessDesktopConnectionRegistry> connectionRegistryProvider,
            ObjectProvider<ApplicationOutboundRequestTracker> outboundTrackerProvider) {
        this(dispatcher, objectMapper, connectionRegistryProvider, outboundTrackerProvider, null, null);
    }

    public JsonRpcWebSocketHandler(
            JsonRpcDispatcher dispatcher,
            ObjectMapper objectMapper,
            ObjectProvider<BusinessDesktopConnectionRegistry> connectionRegistryProvider,
            ObjectProvider<ApplicationOutboundRequestTracker> outboundTrackerProvider,
            ObjectProvider<PendingApplicationActions> pendingActionsProvider) {
        this(dispatcher, objectMapper, connectionRegistryProvider, outboundTrackerProvider, pendingActionsProvider, null);
    }

    @Autowired
    public JsonRpcWebSocketHandler(
            JsonRpcDispatcher dispatcher,
            ObjectMapper objectMapper,
            ObjectProvider<BusinessDesktopConnectionRegistry> connectionRegistryProvider,
            ObjectProvider<ApplicationOutboundRequestTracker> outboundTrackerProvider,
            ObjectProvider<PendingApplicationActions> pendingActionsProvider,
            ObjectProvider<ApplicationOutboundJsonRpcClient> outboundClientProvider) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.businessDesktopConnectionRegistry = connectionRegistryProvider == null
                ? null
                : connectionRegistryProvider.getIfAvailable();
        this.outboundRequestTracker = outboundTrackerProvider == null
                ? null
                : outboundTrackerProvider.getIfAvailable();
        this.pendingApplicationActions = pendingActionsProvider == null
                ? null
                : pendingActionsProvider.getIfAvailable();
        this.outboundJsonRpcClient = outboundClientProvider == null
                ? null
                : outboundClientProvider.getIfAvailable();
        this.businessRequestExecutor = businessDesktopConnectionRegistry == null
                ? null
                : Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("business-jsonrpc-", 0).factory());
    }

    /**
     * 记录连接建立事件。
     *
     * @param session 新建立的 WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (businessDesktopConnectionRegistry != null) {
            try {
                businessDesktopConnectionRegistry.finalizeReservation(
                        trustedAttribute(session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE),
                        trustedAttribute(session, BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE),
                        trustedAttribute(session, BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE),
                        session.getId());
            } catch (RuntimeException exception) {
                String reservationId = stringAttribute(
                        session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
                if (reservationId != null) {
                    businessDesktopConnectionRegistry.cancelReservation(reservationId);
                } else {
                    businessDesktopConnectionRegistry.cancelPending(
                            stringAttribute(
                                    session,
                                    BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE),
                            stringAttribute(
                                    session,
                                    BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE));
                }
                session.close(CloseStatus.POLICY_VIOLATION);
                throw exception;
            }
        }
        if (outboundJsonRpcClient != null) {
            outboundJsonRpcClient.registerSession(session);
        }
        if (businessRequestExecutor != null) {
            businessRequestQueues.put(session.getId(), new SerialRequestQueue(businessRequestExecutor));
        }
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
        if (businessRequestExecutor != null) {
            // A request running on the per-session queue may itself be waiting for a
            // server-initiated JSON-RPC call. Its correlated response must therefore
            // bypass that request queue or the two sides deadlock.
            if (outboundRequestTracker != null
                    && isCorrelatedOutboundResponse(message.getPayload())) {
                processTextMessage(session, message);
                return;
            }
            if (isExplicitLogoutRequest(message.getPayload())) {
                businessRequestExecutor.execute(() -> processTextMessage(session, message));
                return;
            }
            SerialRequestQueue queue = businessRequestQueues.get(session.getId());
            if (queue != null) {
                QueueSubmission submission =
                        queue.submit(() -> processTextMessage(session, message));
                if (submission == QueueSubmission.FULL) {
                    sendResponse(session, JsonRpcMessage.ErrorResponse.of(
                            requestId(message.getPayload()),
                            JsonRpcErrorCode.SERVER_ERROR,
                            "Server busy",
                            null));
                }
            }
            return;
        }
        processTextMessage(session, message);
    }

    private boolean isCorrelatedOutboundResponse(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length >
                com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator.MAX_ENVELOPE_BYTES) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root != null
                    && root.isObject()
                    && !root.has("method")
                    && (root.has("result") || root.has("error"))
                    && hasCorrelatableId(root);
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private boolean isExplicitLogoutRequest(String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length >
                com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator.MAX_ENVELOPE_BYTES) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root != null
                    && root.isObject()
                    && "2.0".equals(root.path("jsonrpc").asText())
                    && "business/auth/logout".equals(root.path("method").asText())
                    && hasCorrelatableId(root)
                    && !root.has("result")
                    && !root.has("error")
                    && (!root.has("params") || root.path("params").isObject());
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private Long requestId(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root != null && root.isObject() && hasCorrelatableId(root)
                    ? root.path("id").longValue()
                    : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private void processTextMessage(WebSocketSession session, TextMessage message) {
        long startedNanos = System.nanoTime();
        JsonRpcMessage response = handleInboundMessage(session, message.getPayload(), startedNanos);
        if (response != null) {
            sendResponse(session, response);
        }
    }

    /**
     * 记录连接关闭事件。
     *
     * @param session 被关闭的 WebSocket 会话
     * @param status 关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SerialRequestQueue queue = businessRequestQueues.remove(session.getId());
        if (queue != null) {
            queue.close();
        }
        try {
            if (businessDesktopConnectionRegistry != null) {
                String reservationId = stringAttribute(
                        session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
                if (reservationId != null) {
                    businessDesktopConnectionRegistry.release(reservationId, session.getId());
                }
            } else {
                if (outboundRequestTracker != null) {
                    outboundRequestTracker.closePending(
                            session.getId(), new IOException("business desktop WebSocket closed"));
                }
                if (pendingApplicationActions != null) {
                    pendingApplicationActions.onConnectionClosed(
                            session.getId(), "business desktop WebSocket closed");
                }
            }
        } finally {
            if (outboundJsonRpcClient != null) {
                outboundJsonRpcClient.unregisterSession(session.getId(), session);
            }
        }
        log.info("WebSocket 已关闭: sessionId={}, status={}", session.getId(), status);
    }

    @PreDestroy
    void closeBusinessRequestExecutor() {
        businessRequestQueues.values().forEach(SerialRequestQueue::close);
        businessRequestQueues.clear();
        if (businessRequestExecutor != null) {
            businessRequestExecutor.shutdownNow();
        }
    }

    private static String trustedAttribute(WebSocketSession session, String key) {
        String value = stringAttribute(session, key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("trusted business desktop handshake attributes are incomplete");
        }
        return value;
    }

    private static String stringAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof String text ? text : null;
    }

    private JsonRpcMessage handleInboundMessage(WebSocketSession session, String payload, long startedNanos) {
        Long requestId = null;
        String method = "<parse-failed>";
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length >
                com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator.MAX_ENVELOPE_BYTES) {
            return JsonRpcMessage.ErrorResponse.of(null, JsonRpcErrorCode.PROTOCOL_ERROR,
                    "PROTOCOL_ERROR", null);
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.has("method")) {
                if (root.has("id")) {
                    return handleRequest(session, objectMapper.treeToValue(root, JsonRpcMessage.Request.class), startedNanos);
                }
                handleNotification(session, objectMapper.treeToValue(root, JsonRpcMessage.Notification.class));
                return null;
            }
            if (root.has("error")) {
                if (!hasCorrelatableId(root)) {
                    return null;
                }
                completeOutbound(session, objectMapper.treeToValue(root, JsonRpcMessage.ErrorResponse.class));
                return null;
            }
            if (root.has("result")) {
                if (!hasCorrelatableId(root)) {
                    return null;
                }
                completeOutbound(session, objectMapper.treeToValue(root, JsonRpcMessage.Response.class));
                return null;
            }
            return JsonRpcMessage.ErrorResponse.of(
                    root.has("id") && root.path("id").canConvertToLong() ? root.path("id").longValue() : null,
                    JsonRpcErrorCode.INVALID_REQUEST,
                    "Invalid JSON-RPC envelope",
                    null);
        } catch (JsonProcessingException exception) {
            log.warn("JSON-RPC 解析失败: sessionId={}, payloadBytes={}, errorType={}",
                    session.getId(),
                    payload.getBytes(StandardCharsets.UTF_8).length,
                    exception.getClass().getSimpleName());
            return JsonRpcMessage.ErrorResponse.of(
                    null,
                    JsonRpcErrorCode.PARSE_ERROR,
                    "Malformed JSON",
                    null);
        } catch (Exception exception) {
            log.error("WebSocket 请求处理失败: sessionId={}, requestId={}, method={}, elapsedMs={}, errorType={}",
                    session.getId(), requestId, method, JsonRpcLogSupport.elapsedMillis(startedNanos),
                    exception.getClass().getSimpleName());
            return JsonRpcMessage.ErrorResponse.of(
                    requestId,
                    JsonRpcErrorCode.INTERNAL_ERROR,
                    "Internal error",
                    null);
        }
    }

    private boolean hasCorrelatableId(JsonNode root) {
        JsonNode id = root.get("id");
        return id != null && id.canConvertToLong() && id.longValue() > 0;
    }

    private JsonRpcMessage handleRequest(
            WebSocketSession session,
            JsonRpcMessage.Request request,
            long startedNanos) {
        Long requestId = request.id();
        String method = request.method();
        try {
            requestId = request.id();
            method = request.method();
            log.info("JSON-RPC 请求进入: sessionId={}, requestId={}, method={}, params={}",
                    session.getId(),
                    requestId,
                    method,
                    JsonRpcLogSupport.paramsSummary(method, objectMapper.valueToTree(request.params())));
            if (!isValidEnvelope(request)) {
                // envelope 不合法时不进入 dispatcher，直接返回 INVALID_REQUEST。
                log.warn("JSON-RPC envelope 非法: sessionId={}, requestId={}, method={}",
                        session.getId(), requestId, method);
                return JsonRpcMessage.ErrorResponse.of(
                        requestId,
                        JsonRpcErrorCode.INVALID_REQUEST,
                        "Invalid JSON-RPC envelope",
                        null);
            }
            // 具体 method 分发和业务异常映射交给 JsonRpcDispatcher。
            JsonRpcMessage response = dispatcher.dispatch(request, session);
            log.info("JSON-RPC 请求完成: sessionId={}, requestId={}, method={}, response={}, elapsedMs={}",
                    session.getId(),
                    requestId,
                    method,
                    responseSummary(response),
                    JsonRpcLogSupport.elapsedMillis(startedNanos));
            return response;
        } catch (Exception exception) {
            log.error("WebSocket 请求处理失败: sessionId={}, requestId={}, method={}, elapsedMs={}, errorType={}",
                    session.getId(), requestId, method, JsonRpcLogSupport.elapsedMillis(startedNanos),
                    exception.getClass().getSimpleName());
            return JsonRpcMessage.ErrorResponse.of(
                    requestId,
                    JsonRpcErrorCode.INTERNAL_ERROR,
                    "Internal error",
                    null);
        }
    }

    private void handleNotification(WebSocketSession session, JsonRpcMessage.Notification notification) {
        if (!"2.0".equals(notification.jsonrpc())
                || notification.method() == null
                || notification.method().isBlank()) {
            return;
        }
        dispatcher.dispatchNotification(notification, session);
    }

    private void completeOutbound(WebSocketSession session, JsonRpcMessage response) {
        if (outboundRequestTracker == null) {
            return;
        }
        if (response instanceof JsonRpcMessage.Response success) {
            outboundRequestTracker.complete(session.getId(), success);
        } else if (response instanceof JsonRpcMessage.ErrorResponse error) {
            outboundRequestTracker.complete(session.getId(), error);
        }
    }

    /**
     * 校验 JSON-RPC 2.0 envelope 的最小必填字段。
     */
    private boolean isValidEnvelope(JsonRpcMessage.Request request) {
        return "2.0".equals(request.jsonrpc())
                && request.id() != null
                && request.method() != null
                && !request.method().isBlank();
    }

    /**
     * 向客户端写回同步 response。
     */
    private void sendResponse(WebSocketSession session, JsonRpcMessage response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            if (payload.getBytes(StandardCharsets.UTF_8).length >
                    com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator.MAX_ENVELOPE_BYTES) {
                Long id = response instanceof JsonRpcMessage.Response success ? success.id()
                        : response instanceof JsonRpcMessage.ErrorResponse error ? error.id() : null;
                payload = objectMapper.writeValueAsString(JsonRpcMessage.ErrorResponse.of(
                        id, JsonRpcErrorCode.PROTOCOL_ERROR, "PROTOCOL_ERROR", null));
            }
            // 与 ItemEmitter 一样同步写 session,防止同步响应和异步 notification 并发写同一连接。
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException | IllegalStateException exception) {
            log.warn("WebSocket 响应已丢弃: sessionId={}, errorType={}",
                    session.getId(), exception.getClass().getSimpleName());
        }
    }

    private static final class SerialRequestQueue {
        private final ExecutorService executor;
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean running;
        private boolean closed;

        private SerialRequestQueue(ExecutorService executor) {
            this.executor = executor;
        }

        synchronized QueueSubmission submit(Runnable request) {
            if (closed) {
                return QueueSubmission.CLOSED;
            }
            if (pending.size() >= MAX_PENDING_BUSINESS_REQUESTS) {
                return QueueSubmission.FULL;
            }
            pending.addLast(request);
            if (!running) {
                running = true;
                executor.execute(this::drain);
            }
            return QueueSubmission.ACCEPTED;
        }

        private void drain() {
            while (true) {
                Runnable request;
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        running = false;
                        return;
                    }
                    request = pending.removeFirst();
                }
                try {
                    request.run();
                } catch (RuntimeException failure) {
                    log.warn("Business WebSocket request dropped: errorType={}",
                            failure.getClass().getSimpleName());
                }
            }
        }

        synchronized void close() {
            closed = true;
            pending.clear();
        }
    }

    private enum QueueSubmission {
        ACCEPTED,
        FULL,
        CLOSED
    }

    /**
     * 生成用于日志的响应摘要，避免把完整 result 大对象打进控制台。
     */
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
