package com.wzx.babiq.server.application.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessDesktopConnectionResolverTest {
    private static final String RESERVATION = "reservation-1";
    private static final String INSTANCE = "instance-1";
    private static final String DESKTOP_SESSION = "desktop-session-1";
    private static final String WS = "ws-1";

    @Test
    void requiresFinalizedConnectionAndMatchesAllHandshakeAttributes() {
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();
        String reservation = registry.reserve(INSTANCE, DESKTOP_SESSION);
        registry.finalizeReservation(reservation, INSTANCE, DESKTOP_SESSION, WS);
        BusinessDesktopConnectionResolver resolver = new BusinessDesktopConnectionResolver(registry);
        WebSocketSession session = session(WS, Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservation,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, INSTANCE,
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, DESKTOP_SESSION));

        assertThat(resolver.requireFinalized(session).webSocketSessionId()).isEqualTo(WS);
    }

    @Test
    void rejectsArbitraryOrPartiallyMatchingSocketSession() {
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();
        BusinessDesktopConnectionResolver resolver = new BusinessDesktopConnectionResolver(registry);
        WebSocketSession arbitrary = session(WS, Map.of());
        assertThatThrownBy(() -> resolver.requireFinalized(arbitrary))
                .isInstanceOf(IllegalStateException.class);

        String reservation = registry.reserve(INSTANCE, DESKTOP_SESSION);
        registry.finalizeReservation(reservation, INSTANCE, DESKTOP_SESSION, WS);
        WebSocketSession drift = session(WS, Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservation,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "other-instance",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, DESKTOP_SESSION));
        assertThatThrownBy(() -> resolver.requireFinalized(drift))
                .isInstanceOf(IllegalStateException.class);
    }

    private static WebSocketSession session(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(new HashMap<>(attributes));
        return session;
    }
}
