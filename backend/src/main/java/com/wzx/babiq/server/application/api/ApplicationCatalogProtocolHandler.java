package com.wzx.babiq.server.application.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMultiMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 路由目录注册、目录更新和页面上下文发布，并验证 finalized 连接。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationCatalogProtocolHandler implements JsonRpcMultiMethodHandler {

    private static final String REGISTER = "application/catalog/register";
    private static final String UPDATE = "application/catalog/update";
    private static final String PUBLISH = "application/context/publish";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationIdentityRegistry identities;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final BusinessDesktopConnectionRegistry connections;

    public ApplicationCatalogProtocolHandler(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            BusinessDesktopConnectionRegistry connections) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Set<String> methods() {
        return Set.of(REGISTER, UPDATE, PUBLISH);
    }

    @Override
    public Object handle(String method, JsonNode params, WebSocketSession session) {
        try {
            TrustedDesktopConnection connection = requireTrustedConnection(session);
            synchronized (connection) {
                if (identities.current(connection).isEmpty()) {
                    throw new IllegalStateException("Authenticated identity is required");
                }
                ApplicationCatalogMessage message = JSON.convertValue(params, ApplicationCatalogMessage.class);
                return switch (method) {
                    case REGISTER -> Map.of("catalogEpoch", catalogs.register(connection, message).catalogEpoch());
                    case UPDATE -> Map.of("catalogEpoch", catalogs.update(connection, message).catalogEpoch());
                    case PUBLISH -> {
                        ApplicationPageContextRegistry.PageContextSnapshot snapshot = contexts.publish(connection, message);
                        yield Map.of(
                                "catalogEpoch", snapshot.catalogEpoch(),
                                "contextSequence", snapshot.contextSequence());
                    }
                    default -> throw new IllegalArgumentException("Unsupported catalog application method");
                };
            }
        } catch (JsonRpcException exception) {
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                    "Invalid application catalog parameters");
        }
    }

    private TrustedDesktopConnection requireTrustedConnection(WebSocketSession session) {
        if (session == null || session.getId() == null || session.getAttributes() == null) {
            throw new IllegalArgumentException("Trusted WebSocket session is required");
        }
        String reservationId = attribute(session, BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
        String desktopInstanceId = attribute(session,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE);
        String desktopSessionId = attribute(session,
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE);
        TrustedDesktopConnection connection = connections.findByDesktopSessionId(desktopSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Finalized business desktop connection is required"));
        if (!connection.reservationId().equals(reservationId)
                || !connection.desktopInstanceId().equals(desktopInstanceId)
                || !connection.desktopSessionId().equals(desktopSessionId)
                || !connection.webSocketSessionId().equals(session.getId())) {
            throw new IllegalArgumentException("WebSocket attributes do not match finalized connection");
        }
        return connection;
    }

    private String attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Missing trusted WebSocket attribute: " + name);
        }
        return text;
    }
}
