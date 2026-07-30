package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ApprovalRequestPayload;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import com.wzx.babiq.server.conversation.ItemEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 楠岃瘉 JSON-RPC envelope 浠?UTF-8 瀛楄妭璁℃暟锛屽苟瑕嗙洊鍙屽悜杈圭晫銆?*/
class JsonRpcEnvelopeSizeBoundaryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void validatorAcceptsExactlyMaxUtf8BytesAndRejectsOneByteOver() {
        assertThat(ApplicationProtocolValidator.MAX_ENVELOPE_BYTES).isEqualTo(262144);
        ApplicationProtocolValidator.validateEnvelopeSize(new byte[262144]);
        assertThatThrownBy(() -> ApplicationProtocolValidator.validateEnvelopeSize(new byte[262145]))
                .isInstanceOf(ApplicationProtocolValidator.ApplicationProtocolValidationException.class);
    }

    @Test
    void inboundRequestExactlyMaxBytesIsDispatchedButOneByteOverReturnsProtocolErrorBeforeDispatch()
            throws Exception {
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), any()))
                .thenReturn(JsonRpcMessage.Response.ok(7L, Map.of("ok", true)));
        CapturingHandler handler = new CapturingHandler(dispatcher, objectMapper);
        WebSocketSession session = session(handler.sent);

        String exact = sizedRequest(objectMapper, 262144, false);
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(262144);
        handler.handle(session, exact);
        verify(dispatcher, org.mockito.Mockito.times(1)).dispatch(any(JsonRpcMessage.Request.class), eq(session));

        String over = sizedRequest(objectMapper, 262145, false);
        assertThat(over.getBytes(StandardCharsets.UTF_8)).hasSize(262145);
        handler.sent.set(null);
        handler.handle(session, over);
        verify(dispatcher, org.mockito.Mockito.times(1)).dispatch(any(JsonRpcMessage.Request.class), eq(session));
        JsonNode error = objectMapper.readTree(handler.sent.get().getPayload()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32041);
        assertThat(error.path("message").asText()).isEqualTo("PROTOCOL_ERROR");
    }

    @Test
    void inboundNotificationExactlyMaxBytesIsDispatchedButOneByteOverReturnsProtocolError() throws Exception {
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        when(dispatcher.dispatchNotification(any(JsonRpcMessage.Notification.class), any())).thenReturn(true);
        CapturingHandler handler = new CapturingHandler(dispatcher, objectMapper);
        WebSocketSession session = session(handler.sent);

        String exact = sizedRequest(objectMapper, 262144, true);
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(262144);
        handler.handle(session, exact);
        verify(dispatcher, org.mockito.Mockito.times(1))
                .dispatchNotification(any(JsonRpcMessage.Notification.class), eq(session));
        assertThat(handler.sent.get()).isNull();

        String over = sizedRequest(objectMapper, 262145, true);
        assertThat(over.getBytes(StandardCharsets.UTF_8)).hasSize(262145);
        handler.handle(session, over);
        JsonNode error = objectMapper.readTree(handler.sent.get().getPayload()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32041);
        assertThat(error.path("message").asText()).isEqualTo("PROTOCOL_ERROR");
    }

    @Test
    void oversizedOutboundResponseIsReplacedByFixedProtocolError() throws Exception {
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        CapturingHandler handler = new CapturingHandler(dispatcher, objectMapper);
        WebSocketSession session = session(handler.sent);
        String padding = sizedResponsePadding(objectMapper, 262145, 7L);
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session)))
                .thenReturn(JsonRpcMessage.Response.ok(7L, Map.of("padding", padding)));

        handler.handle(session, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");

        JsonNode response = objectMapper.readTree(handler.sent.get().getPayload());
        assertThat(response.path("id").asLong()).isEqualTo(7L);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32041);
        assertThat(response.path("error").path("message").asText()).isEqualTo("PROTOCOL_ERROR");
    }

    @Test
    void itemEmitterRejectsOversizedNotificationBeforeSending() throws Exception {
        List<String> sent = new ArrayList<>();
        ItemEmitter emitter = new ItemEmitter(recordingSession(sent), objectMapper, "thread", "turn");
        String description = sizedApprovalDescription(objectMapper, 262145);
        ApprovalRequestPayload payload = new ApprovalRequestPayload(
                "thread", "turn", "item", "tool", "{}", description);

        assertThatThrownBy(() -> emitter.emitApprovalRequest(payload))
                .isInstanceOf(ApplicationProtocolValidator.ApplicationProtocolValidationException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void outboundClientRejectsOversizedRequestBeforeSending() throws Exception {
        List<String> sent = new ArrayList<>();
        WebSocketSession session = recordingSession(sent);
        ApplicationOutboundRequestTracker tracker = new ApplicationOutboundRequestTracker(scheduler);
        ApplicationOutboundJsonRpcClient client = new ApplicationOutboundJsonRpcClient(objectMapper, tracker);
        String padding = sizedRequestPadding(objectMapper, 262145, 1L, "application/action/request");

        assertThatThrownBy(() -> client.request(
                session, "application/action/request", Map.of("padding", padding), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(ApplicationProtocolValidator.ApplicationProtocolValidationException.class);
        assertThat(sent).isEmpty();
        assertThat(tracker.pendingCount()).isZero();
    }

    @Test
    void websocketContainerLeavesOneByteOversizeFrameForApplicationValidation() {
        ServerContainer container = mock(ServerContainer.class);
        ServletContext servletContext = mock(ServletContext.class);
        ServletWebServerApplicationContext applicationContext = mock(ServletWebServerApplicationContext.class);
        ServletWebServerInitializedEvent event = mock(ServletWebServerInitializedEvent.class);
        when(servletContext.getAttribute(ServerContainer.class.getName())).thenReturn(container);
        when(applicationContext.getServletContext()).thenReturn(servletContext);
        when(event.getApplicationContext()).thenReturn(applicationContext);

        new com.wzx.babiq.server.config.WebSocketConfig(
                mock(JsonRpcWebSocketHandler.class), "/ws", "*", emptyProvider())
                .configureWebSocketContainer(event);

        verify(container).setDefaultMaxTextMessageBufferSize(
                org.mockito.ArgumentMatchers.intThat(value -> value >= 262145));
    }

    private static String sizedRequest(ObjectMapper mapper, int target, boolean notification) throws Exception {
        String padding = exactPadding(target, value -> {
            JsonRpcMessage message = notification
                    ? JsonRpcMessage.Notification.of("ping", Map.of("padding", value))
                    : new JsonRpcMessage.Request("2.0", 1L, "ping", Map.of("padding", value));
            return mapper.writeValueAsString(message);
        });
        String result = mapper.writeValueAsString(notification
                ? JsonRpcMessage.Notification.of("ping", Map.of("padding", padding))
                : new JsonRpcMessage.Request("2.0", 1L, "ping", Map.of("padding", padding)));
        assertThat(result.getBytes(StandardCharsets.UTF_8)).hasSize(target);
        return result;
    }

    private static String sizedRequestPadding(ObjectMapper mapper, int target, Long id, String method)
            throws Exception {
        return exactPadding(target, count -> {
            JsonRpcMessage message = id == null
                    ? JsonRpcMessage.Notification.of(method, Map.of("padding", count))
                    : new JsonRpcMessage.Request("2.0", id, method, Map.of("padding", count));
            return mapper.writeValueAsString(message);
        });
    }

    private static String sizedResponsePadding(ObjectMapper mapper, int target, long id) throws Exception {
        return exactPadding(target, count -> mapper.writeValueAsString(
                JsonRpcMessage.Response.ok(id, Map.of("padding", count))));
    }

    private static String sizedApprovalDescription(ObjectMapper mapper, int target) throws Exception {
        return exactPadding(target, count -> mapper.writeValueAsString(JsonRpcMessage.Notification.of(
                "approval/request",
                new ApprovalRequestPayload("thread", "turn", "item", "tool", "{}", count))));
    }

    private static String exactPadding(int target, Serializer serializer) throws Exception {
        int low = 0;
        int high = target;
        int best = 0;
        while (low <= high) {
            int middle = low + ((high - low) >>> 1);
            int size = serializer.serialize("a".repeat(middle)).getBytes(StandardCharsets.UTF_8).length;
            if (size <= target) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        String unicode = "a".repeat(best);
        String base = serializer.serialize(unicode);
        int remaining = target - base.getBytes(StandardCharsets.UTF_8).length;
        String candidate = unicode + "a".repeat(Math.max(0, remaining));
        if (serializer.serialize(candidate).getBytes(StandardCharsets.UTF_8).length != target) {
            throw new AssertionError("Unable to construct exact UTF-8 envelope size");
        }
        return candidate;
    }

    private WebSocketSession session(AtomicReference<TextMessage> sent) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("size-boundary-session");
        when(session.getAttributes()).thenReturn(Map.of());
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private WebSocketSession recordingSession(List<String> payloads) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("size-boundary-session");
        doAnswer(invocation -> {
            payloads.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @FunctionalInterface
    private interface Serializer {
        String serialize(String value) throws Exception;
    }

    private static final class CapturingHandler extends JsonRpcWebSocketHandler {
        private final AtomicReference<TextMessage> sent = new AtomicReference<>();

        private CapturingHandler(JsonRpcDispatcher dispatcher, ObjectMapper mapper) {
            super(dispatcher, mapper, emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider());
        }

        private void handle(WebSocketSession session, String payload) {
            handleTextMessage(session, new TextMessage(payload));
        }
    }
}
