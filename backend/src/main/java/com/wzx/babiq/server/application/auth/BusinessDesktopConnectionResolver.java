package com.wzx.babiq.server.application.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Resolves only finalized local connections and verifies the complete handshake scope. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessDesktopConnectionResolver {
    private final BusinessDesktopConnectionRegistry registry;

    @Autowired
    public BusinessDesktopConnectionResolver(BusinessDesktopConnectionRegistry registry) {
        this.registry = registry;
    }

    public TrustedDesktopConnection requireFinalized(WebSocketSession session) {
        if (session == null) {
            throw new IllegalArgumentException("WebSocket session is required");
        }
        TrustedDesktopConnection connection = registry.findByWebSocketSessionId(session.getId())
                .orElseThrow(() -> new IllegalStateException("WebSocket connection is not finalized"));
        requireAttribute(session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE,
                connection.reservationId());
        requireAttribute(session, BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE,
                connection.desktopInstanceId());
        requireAttribute(session, BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE,
                connection.desktopSessionId());
        if (!connection.webSocketSessionId().equals(session.getId())) {
            throw new IllegalStateException("WebSocket connection scope does not match finalized connection");
        }
        return connection;
    }

    private static void requireAttribute(WebSocketSession session, String key, String expected) {
        Object value = session.getAttributes().get(key);
        if (!(value instanceof String actual) || !expected.equals(actual)) {
            throw new IllegalStateException("WebSocket connection scope does not match finalized connection");
        }
    }
}
