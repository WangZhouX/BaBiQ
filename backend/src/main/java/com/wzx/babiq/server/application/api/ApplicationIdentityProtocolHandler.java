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
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.application.action.ApplicationActionReconciliationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 接收身份 bind/update，并只信任握手 registry 已 finalized 的桌面连接。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationIdentityProtocolHandler implements JsonRpcMultiMethodHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationIdentityProtocolHandler.class);
    private static final String BIND = "application/identity/bind";
    private static final String UPDATE = "application/identity/update";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplicationIdentityRegistry identities;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final BusinessDesktopConnectionRegistry connections;
    private final ApplicationActionReconciliationService reconciliation;

    public ApplicationIdentityProtocolHandler(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            BusinessDesktopConnectionRegistry connections) {
        this(identities, catalogs, contexts, connections, null);
    }

    @Autowired
    public ApplicationIdentityProtocolHandler(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            BusinessDesktopConnectionRegistry connections,
            ObjectProvider<ApplicationActionReconciliationService> reconciliationProvider) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.reconciliation = reconciliationProvider == null ? null : reconciliationProvider.getIfAvailable();
    }

    @Override
    public Set<String> methods() {
        return Set.of(BIND, UPDATE);
    }

    @Override
    public Object handle(String method, JsonNode params, WebSocketSession session) {
        try {
            TrustedDesktopConnection connection = requireTrustedConnection(session);
            ApplicationIdentityMessage message = JSON.convertValue(params, ApplicationIdentityMessage.class);
            if (BIND.equals(method)) {
                synchronized (connection) {
                    var identity = identities.bind(connection, message);
                    if (reconciliation != null) {
                        try {
                            reconciliation.reconcile(connection, identity);
                        } catch (RuntimeException failure) {
                            log.warn("Application identity post-bind reconciliation failed: reasonType={}",
                                    failure.getClass().getSimpleName());
                        }
                    }
                }
            } else if (UPDATE.equals(method)) {
                identities.update(connection, message, () -> {
                    catalogs.clear(connection);
                    contexts.clear(connection);
                });
            } else {
                throw new IllegalArgumentException("Unsupported identity application method");
            }
            return Map.of("authenticated", message.authenticated(), "identityEpoch", message.identityEpoch());
        } catch (JsonRpcException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                    "Invalid application identity parameters");
        } catch (RuntimeException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR,
                    UPDATE.equals(method)
                            ? "Application identity update failed"
                            : "Application identity operation failed");
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
