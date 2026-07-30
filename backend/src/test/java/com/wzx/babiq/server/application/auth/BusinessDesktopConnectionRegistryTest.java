package com.wzx.babiq.server.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcDispatcher;
import com.wzx.babiq.server.api.JsonRpcWebSocketHandler;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.action.ApplicationOutboundJsonRpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;

@ExtendWith(OutputCaptureExtension.class)
class BusinessDesktopConnectionRegistryTest {

    private static final String INSTANCE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_ID = "22222222-2222-4222-8222-222222222222";

    private final BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();

    @Test
    void findsFinalizedConnectionByWebSocketSessionIdOnly() {
        String reservationId = registry.reserve(INSTANCE_ID, SESSION_ID);
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservationId, INSTANCE_ID, SESSION_ID, "ws-by-id");

        assertThat(registry.findByWebSocketSessionId("ws-by-id")).contains(connection);
        assertThat(registry.findByWebSocketSessionId("ws-missing")).isEmpty();
        assertThat(registry.isFinalized("ws-by-id")).isTrue();

        registry.release(reservationId, "ws-by-id");
        assertThat(registry.isFinalized("ws-by-id")).isFalse();
    }

    @Test
    void reservesThenFinalizesOneTrustedConnection() {
        String reservationId = registry.reserve(INSTANCE_ID, SESSION_ID);

        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservationId, INSTANCE_ID, SESSION_ID, "ws-1");

        assertThat(connection.desktopInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(connection.desktopSessionId()).isEqualTo(SESSION_ID);
        assertThat(connection.webSocketSessionId()).isEqualTo("ws-1");
        assertThat(registry.findByDesktopSessionId(SESSION_ID)).contains(connection);
        assertThat(connection.toString())
                .contains("[REDACTED]")
                .doesNotContain(INSTANCE_ID)
                .doesNotContain(SESSION_ID);
    }

    @Test
    void rejectsDuplicateReservedOrActiveDesktopSessions() {
        String reservationId = registry.reserve(INSTANCE_ID, SESSION_ID);

        assertThatThrownBy(() -> registry.reserve(INSTANCE_ID, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active")
                .hasMessageNotContaining(SESSION_ID);

        registry.finalizeReservation(reservationId, INSTANCE_ID, SESSION_ID, "ws-1");
        assertThatThrownBy(() -> registry.reserve(INSTANCE_ID, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void rejectsReservationDriftWithoutConsumingTheValidReservation() {
        String reservationId = registry.reserve(INSTANCE_ID, SESSION_ID);

        assertThatThrownBy(() -> registry.finalizeReservation(
                reservationId,
                "33333333-3333-4333-8333-333333333333",
                SESSION_ID,
                "ws-drift"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation");

        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservationId, INSTANCE_ID, SESSION_ID, "ws-valid");
        assertThat(connection.webSocketSessionId()).isEqualTo("ws-valid");
    }

    @Test
    void staleCloseCannotEvictANewerReservationForTheSameDesktopSession() {
        String oldReservation = registry.reserve(INSTANCE_ID, SESSION_ID);
        registry.finalizeReservation(oldReservation, INSTANCE_ID, SESSION_ID, "ws-old");
        assertThat(registry.release(oldReservation, "ws-old")).isTrue();

        String newReservation = registry.reserve(INSTANCE_ID, SESSION_ID);
        TrustedDesktopConnection newer = registry.finalizeReservation(
                newReservation, INSTANCE_ID, SESSION_ID, "ws-new");

        assertThat(registry.release(oldReservation, "ws-old")).isFalse();
        assertThat(registry.release(newReservation, "ws-stale")).isFalse();
        assertThat(registry.findByDesktopSessionId(SESSION_ID)).contains(newer);
        assertThat(registry.release(newReservation, "ws-new")).isTrue();
        assertThat(registry.findByDesktopSessionId(SESSION_ID)).isEmpty();
    }

    @Test
    void releaseNotifiesEveryCloseListenerOnceAndIsIdempotentWhenOneFails() {
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();
        java.util.concurrent.atomic.AtomicInteger first = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger second = new java.util.concurrent.atomic.AtomicInteger();
        registry.addCloseListener((connection, reason) -> {
            first.incrementAndGet();
            throw new IllegalStateException("first close listener failed");
        });
        registry.addCloseListener((connection, reason) -> second.incrementAndGet());
        String reservation = registry.reserve(INSTANCE_ID, SESSION_ID);
        registry.finalizeReservation(reservation, INSTANCE_ID, SESSION_ID, "ws-once");

        assertThat(registry.release(reservation, "ws-once")).isTrue();
        assertThat(registry.release(reservation, "ws-once")).isFalse();

        assertThat(first).hasValue(1);
        assertThat(second).hasValue(1);
        assertThat(registry.findByDesktopSessionId(SESSION_ID)).isEmpty();
    }

    @Test
    void closeListenerFailureLogDoesNotLeakExceptionMessage(CapturedOutput output) {
        String secret = "secret-tenant-SQL-E:/private/case.db";
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();
        registry.addCloseListener((connection, reason) -> {
            throw new IllegalStateException(secret);
        });
        String reservation = registry.reserve(INSTANCE_ID, SESSION_ID);
        registry.finalizeReservation(reservation, INSTANCE_ID, SESSION_ID, "ws-safe-log");

        assertThat(registry.release(reservation, "ws-safe-log")).isTrue();

        assertThat(output).contains("IllegalStateException");
        assertThat(output).doesNotContain(secret, "secret-tenant", "private/case.db");
    }

    @Test
    void handlerUsesRegistryReleaseAsTheOnlyBusinessCloseLifecyclePath() {
        BusinessDesktopConnectionRegistry connectionRegistry = new BusinessDesktopConnectionRegistry();
        String reservation = connectionRegistry.reserve(INSTANCE_ID, SESSION_ID);
        connectionRegistry.finalizeReservation(reservation, INSTANCE_ID, SESSION_ID, "ws-close-once");
        ApplicationOutboundRequestTracker outbound = mock(ApplicationOutboundRequestTracker.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-close-once");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservation));
        ObjectProvider<BusinessDesktopConnectionRegistry> connectionsProvider = mock(ObjectProvider.class);
        ObjectProvider<ApplicationOutboundRequestTracker> outboundProvider = mock(ObjectProvider.class);
        ObjectProvider<PendingApplicationActions> pendingProvider = mock(ObjectProvider.class);
        when(connectionsProvider.getIfAvailable()).thenReturn(connectionRegistry);
        when(outboundProvider.getIfAvailable()).thenReturn(outbound);
        when(pendingProvider.getIfAvailable()).thenReturn(pending);
        connectionRegistry.addCloseListener((connection, reason) -> {
            outbound.closePending(connection.webSocketSessionId(), new java.io.IOException(reason));
            pending.onConnectionClosed(connection.webSocketSessionId(), reason);
        });
        JsonRpcWebSocketHandler handler = new JsonRpcWebSocketHandler(
                mock(JsonRpcDispatcher.class), new ObjectMapper(), connectionsProvider,
                outboundProvider, pendingProvider);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(outbound, times(1)).closePending(eq("ws-close-once"), any(java.io.IOException.class));
        verify(pending, times(1)).onConnectionClosed("ws-close-once", "business desktop WebSocket closed");
    }

    @Test
    void handlerAlwaysUnregistersOutboundSessionWhenReleaseCleanupFails() {
        BusinessDesktopConnectionRegistry connectionRegistry = new BusinessDesktopConnectionRegistry();
        String reservation = connectionRegistry.reserve(INSTANCE_ID, SESSION_ID);
        connectionRegistry.finalizeReservation(reservation, INSTANCE_ID, SESSION_ID, "ws-finally");
        connectionRegistry.addCloseListener((connection, reason) -> {
            throw new IllegalStateException("cleanup failed");
        });
        ApplicationOutboundJsonRpcClient outboundClient = mock(ApplicationOutboundJsonRpcClient.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-finally");
        when(session.getAttributes()).thenReturn(Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservation));
        ObjectProvider<BusinessDesktopConnectionRegistry> connectionsProvider = mock(ObjectProvider.class);
        ObjectProvider<ApplicationOutboundJsonRpcClient> clientProvider = mock(ObjectProvider.class);
        when(connectionsProvider.getIfAvailable()).thenReturn(connectionRegistry);
        when(clientProvider.getIfAvailable()).thenReturn(outboundClient);
        JsonRpcWebSocketHandler handler = new JsonRpcWebSocketHandler(
                mock(JsonRpcDispatcher.class), new ObjectMapper(), connectionsProvider,
                mock(ObjectProvider.class), mock(ObjectProvider.class), clientProvider);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(outboundClient).unregisterSession("ws-finally", session);
        assertThat(connectionRegistry.findByDesktopSessionId(SESSION_ID)).isEmpty();
    }

    @Test
    void failedHandshakeCancelsOnlyTheMatchingPendingReservation() {
        String reservationId = registry.reserve(INSTANCE_ID, SESSION_ID);

        assertThat(registry.cancelReservation("wrong-reservation")).isFalse();
        assertThatThrownBy(() -> registry.reserve(INSTANCE_ID, SESSION_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.cancelReservation(reservationId)).isTrue();
        assertThat(registry.reserve(INSTANCE_ID, SESSION_ID)).isNotBlank();
    }

    @Test
    void expiredPendingReservationCanBeReservedAgainAndCannotBeFinalized() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
        BusinessDesktopConnectionRegistry expiringRegistry =
                new BusinessDesktopConnectionRegistry(clock, Duration.ofSeconds(10));
        String expiredReservation = expiringRegistry.reserve(INSTANCE_ID, SESSION_ID);

        clock.advance(Duration.ofSeconds(11));

        String replacementReservation = expiringRegistry.reserve(INSTANCE_ID, SESSION_ID);
        assertThat(replacementReservation).isNotEqualTo(expiredReservation);
        assertThatThrownBy(() -> expiringRegistry.finalizeReservation(
                expiredReservation, INSTANCE_ID, SESSION_ID, "ws-expired"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation");
        assertThat(expiringRegistry.cancelReservation(expiredReservation)).isFalse();
    }

    @Test
    void pendingExpiresExactlyAtTtlButActiveConnectionDoesNotExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
        BusinessDesktopConnectionRegistry expiringRegistry =
                new BusinessDesktopConnectionRegistry(clock, Duration.ofSeconds(10));
        String pendingReservation = expiringRegistry.reserve(INSTANCE_ID, SESSION_ID);

        clock.advance(Duration.ofSeconds(10));

        String replacement = expiringRegistry.reserve(INSTANCE_ID, SESSION_ID);
        assertThat(replacement).isNotEqualTo(pendingReservation);
        TrustedDesktopConnection active = expiringRegistry.finalizeReservation(
                replacement, INSTANCE_ID, SESSION_ID, "ws-active");

        clock.advance(Duration.ofMinutes(1));

        assertThat(expiringRegistry.findByDesktopSessionId(SESSION_ID)).contains(active);
        assertThatThrownBy(() -> expiringRegistry.reserve(INSTANCE_ID, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void businessHandlerRejectsConnectionsMissingAnyTrustedHandshakeAttribute() throws Exception {
        for (String missingAttribute : List.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE,
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE)) {
            BusinessDesktopConnectionRegistry connectionRegistry = new BusinessDesktopConnectionRegistry();
            String reservationId = connectionRegistry.reserve(INSTANCE_ID, SESSION_ID);
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservationId);
            attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, INSTANCE_ID);
            attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, SESSION_ID);
            attributes.remove(missingAttribute);
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getAttributes()).thenReturn(attributes);
            when(session.getId()).thenReturn("ws-missing-attribute");

            assertThatThrownBy(() -> handler(connectionRegistry).afterConnectionEstablished(session))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("trusted business desktop handshake attributes");

            verify(session).close(CloseStatus.POLICY_VIOLATION);
            assertThat(connectionRegistry.findByDesktopSessionId(SESSION_ID)).isEmpty();
            assertThat(connectionRegistry.reserve(INSTANCE_ID, SESSION_ID)).isNotBlank();
        }
    }

    @Test
    void businessHandlerReturnsAStableInternalErrorWithoutLeakingDispatcherDetails(
            CapturedOutput output) throws Exception {
        String sensitiveMessage = "failed to open E:\\private\\clients\\secret-case.json";
        JsonRpcDispatcher dispatcher = mock(JsonRpcDispatcher.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-internal-error");
        when(dispatcher.dispatch(any(), eq(session)))
                .thenThrow(new IllegalStateException(sensitiveMessage));
        ExposedJsonRpcWebSocketHandler handler = new ExposedJsonRpcWebSocketHandler(dispatcher);

        handler.handle(session, new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":91,\"method\":\"thread/create\",\"params\":{}}"));

        ArgumentCaptor<TextMessage> response = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(response.capture());
        assertThat(response.getValue().getPayload())
                .contains("\"code\":-32603")
                .contains("Internal error")
                .doesNotContain(sensitiveMessage)
                .doesNotContain("secret-case.json");
        assertThat(output)
                .contains("IllegalStateException")
                .doesNotContain(sensitiveMessage)
                .doesNotContain("secret-case.json");
    }

    @SuppressWarnings("unchecked")
    private JsonRpcWebSocketHandler handler(BusinessDesktopConnectionRegistry connectionRegistry) {
        ObjectProvider<BusinessDesktopConnectionRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(connectionRegistry);
        return new JsonRpcWebSocketHandler(mock(JsonRpcDispatcher.class), new ObjectMapper(), provider);
    }

    private static final class ExposedJsonRpcWebSocketHandler extends JsonRpcWebSocketHandler {

        @SuppressWarnings("unchecked")
        private ExposedJsonRpcWebSocketHandler(JsonRpcDispatcher dispatcher) {
            super(dispatcher, new ObjectMapper(), mock(ObjectProvider.class));
        }

        void handle(WebSocketSession session, TextMessage message) {
            handleTextMessage(session, message);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
