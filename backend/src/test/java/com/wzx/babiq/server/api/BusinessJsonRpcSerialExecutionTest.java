package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessJsonRpcSerialExecutionTest {

    @Test
    void businessTransportReturnsImmediatelyButDispatchesOneSessionInArrivalOrder() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-serial");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-1",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "instance-1",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-1"));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        List<Long> dispatchOrder = new CopyOnWriteArrayList<>();
        List<String> outbound = new CopyOnWriteArrayList<>();
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session)))
                .thenAnswer(invocation -> {
                    JsonRpcMessage.Request request = invocation.getArgument(0);
                    dispatchOrder.add(request.id());
                    if (request.id() == 1L) {
                        firstStarted.countDown();
                        assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                    } else if (request.id() == 2L) {
                        secondStarted.countDown();
                    } else {
                        thirdStarted.countDown();
                    }
                    return JsonRpcMessage.Response.ok(request.id(), Map.of("ok", true));
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            outbound.add(invocation.<TextMessage>getArgument(0).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        ExposedBusinessHandler handler = new ExposedBusinessHandler(dispatcher, json, connections);
        try {
            handler.afterConnectionEstablished(session);

            handler.handle(session, request(1L));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            handler.handle(session, request(2L));

            assertThat(secondStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(dispatchOrder).containsExactly(1L);

            releaseFirst.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(dispatchOrder).containsExactly(1L, 2L);
                assertThat(outbound).hasSize(2);
            });
            assertThat(outbound.stream().map(value -> parse(json, value))
                    .map(node -> node.path("id").asLong()).toList()).containsExactly(1L, 2L);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            verify(connections).release("reservation-1", "ws-serial");
            handler.handle(session, request(3L));
            assertThat(thirdStarted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(dispatchOrder).containsExactly(1L, 2L);
        } finally {
            releaseFirst.countDown();
            handler.closeBusinessRequestExecutor();
        }
    }

    @Test
    void outboundCorrelationResponseBypassesBlockedInboundRequestQueue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-correlation");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-correlation",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "instance-correlation",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-correlation"));

        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session))).thenAnswer(invocation -> {
            requestStarted.countDown();
            assertThat(releaseRequest.await(5, TimeUnit.SECONDS)).isTrue();
            JsonRpcMessage.Request request = invocation.getArgument(0);
            return JsonRpcMessage.Response.ok(request.id(), Map.of("ok", true));
        });

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var tracker = new ApplicationOutboundRequestTracker(scheduler);
        ExposedBusinessHandler handler =
                new ExposedBusinessHandler(dispatcher, json, connections, tracker);
        try {
            handler.afterConnectionEstablished(session);
            var correlated = tracker.register(session.getId(), 99L, Duration.ofSeconds(4));

            handler.handle(session, request(1L));
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            handler.handle(session, """
                    {"jsonrpc":"2.0","id":99,"result":{"accepted":true}}
                    """);

            JsonRpcMessage response = correlated.get(1, TimeUnit.SECONDS);
            assertThat(response).isInstanceOf(JsonRpcMessage.Response.class);
            assertThat(((JsonRpcMessage.Response) response).id()).isEqualTo(99L);
        } finally {
            releaseRequest.countDown();
            handler.closeBusinessRequestExecutor();
            tracker.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void saturatedBusinessQueueReturnsStableSecretFreeBusyError() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-overflow");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-overflow",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "instance-overflow",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-overflow"));

        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        when(dispatcher.dispatch(any(JsonRpcMessage.Request.class), eq(session))).thenAnswer(invocation -> {
            requestStarted.countDown();
            assertThat(releaseRequest.await(5, TimeUnit.SECONDS)).isTrue();
            JsonRpcMessage.Request request = invocation.getArgument(0);
            return JsonRpcMessage.Response.ok(request.id(), Map.of("ok", true));
        });
        List<String> outbound = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            outbound.add(invocation.<TextMessage>getArgument(0).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        ExposedBusinessHandler handler = new ExposedBusinessHandler(dispatcher, json, connections);
        try {
            handler.afterConnectionEstablished(session);
            handler.handle(session, request(1L));
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (long id = 2; id <= 33; id++) {
                handler.handle(session, request(id));
            }
            handler.handle(session, """
                    {"jsonrpc":"2.0","id":34,"method":"business/workbench/page/get",
                     "params":{"tokenCanary":"queue-overflow-secret-canary"}}
                    """);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(outbound.stream().map(value -> parse(json, value))
                            .filter(node -> node.path("id").asLong() == 34L))
                            .singleElement()
                            .satisfies(node -> {
                                assertThat(node.at("/error/code").asInt()).isEqualTo(-32000);
                                assertThat(node.at("/error/message").asText()).isEqualTo("Server busy");
                                assertThat(node.toString())
                                        .doesNotContain("queue-overflow-secret-canary");
                            }));
        } finally {
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
            releaseRequest.countDown();
            handler.closeBusinessRequestExecutor();
        }
    }

    private static String request(long id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"business/auth/session/get\",\"params\":{}}";
    }

    private static JsonNode parse(ObjectMapper json, String payload) {
        try {
            return json.readTree(payload);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class ExposedBusinessHandler extends JsonRpcWebSocketHandler {

        private ExposedBusinessHandler(
                JsonRpcDispatcher dispatcher,
                ObjectMapper json,
                BusinessDesktopConnectionRegistry connections) {
            super(dispatcher, json, provider(connections), emptyProvider(), emptyProvider(), emptyProvider());
        }

        private ExposedBusinessHandler(
                JsonRpcDispatcher dispatcher,
                ObjectMapper json,
                BusinessDesktopConnectionRegistry connections,
                ApplicationOutboundRequestTracker tracker) {
            super(dispatcher, json, provider(connections), provider(tracker), emptyProvider(), emptyProvider());
        }

        private void handle(WebSocketSession session, String payload) {
            handleTextMessage(session, new TextMessage(payload));
        }

        private static <T> ObjectProvider<T> emptyProvider() {
            @SuppressWarnings("unchecked")
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        private static <T> ObjectProvider<T> provider(T value) {
            @SuppressWarnings("unchecked")
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(value);
            return provider;
        }
    }
}
