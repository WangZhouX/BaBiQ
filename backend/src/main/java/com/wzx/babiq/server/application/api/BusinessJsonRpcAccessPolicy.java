package com.wzx.babiq.server.application.api;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Exact default-deny JSON-RPC policy for the business desktop WebSocket. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessJsonRpcAccessPolicy {

    private static final Set<String> PRE_BIND_METHODS = Set.of(
            "business/auth/session/get",
            "business/auth/session/attach",
            "business/auth/session/restore",
            "business/auth/tenant-candidates",
            "business/auth/login",
            "business/auth/logout");

    private static final Set<String> SAFE_LOCAL_METHODS = Set.of(
            "model/providers/list",
            "settings/get",
            "sandbox/policy",
            "sandbox/policy/set",
            "approval/policy",
            "approval/policy/set");

    private static final Set<String> LEGACY_CLIENT_PROJECTION_METHODS = Set.of(
            "application/identity/bind",
            "application/identity/update",
            "application/catalog/register",
            "application/catalog/update",
            "application/context/publish");

    private static final Set<String> POST_BIND_METHODS = Set.of(
            "provider/list",
            "provider/create",
            "provider/update",
            "provider/delete",
            "provider/test",
            "provider/set-active",
            "provider/oauth/status",
            "provider/oauth/login",
            "model/providers/set-active",
            "settings/update",
            "thread/create",
            "thread/list",
            "thread/load",
            "thread/archive",
            "turn/start",
            "turn/cancel",
            "turn/interrupt",
            "run/turns/list",
            "run/turn/get",
            "context/status",
            "context/snapshot/get",
            "business/workbench/get",
            "business/workbench/navigation/get",
            "business/workbench/home-info/get",
            "business/workbench/page/get",
            "business/workbench/team-roles/list",
            "business/workbench/sort/update",
            "business/schedule/month/get",
            "business/schedule/day/get",
            "business/schedule/completion/set",
            "business/schedule/form/get",
            "business/schedule/relation-options/get",
            "business/schedule/service-projects/get",
            "business/schedule/create",
            "business/attachments/upload/prepare",
            "application/action/accepted",
            "application/action/previewed",
            "application/action/approval-required",
            "application/action/running",
            "application/action/completed",
            "application/action/failed",
            "application/action/rejected",
            "application/action/canceled",
            "application/action/expired",
            "application/action/outcome-unknown",
            "application/action/status",
            "application/action/result/get");

    private final ApplicationIdentityRegistry identities;
    private final BusinessDesktopConnectionRegistry connections;
    private final BusinessOaSessionRegistry sessions;
    private final boolean legacyClientProjectionsEnabled;

    /** Compatibility constructor; without connection and READY registries post-bind fails closed. */
    public BusinessJsonRpcAccessPolicy(ApplicationIdentityRegistry identities) {
        this(identities, null, null, false);
    }

    /** Compatibility constructor; without a READY registry all post-bind methods fail closed. */
    public BusinessJsonRpcAccessPolicy(
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections) {
        this(identities, connections, null, false);
    }

    /** Production gate requires a finalized connection, committed identity and exact READY lease. */
    public BusinessJsonRpcAccessPolicy(
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections,
            BusinessOaSessionRegistry sessions) {
        this(identities, connections, sessions, false);
    }

    @Autowired
    public BusinessJsonRpcAccessPolicy(
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections,
            BusinessOaSessionRegistry sessions,
            @Value("${babiq.business.legacy-client-projections-enabled:false}")
            boolean legacyClientProjectionsEnabled) {
        this.identities = identities;
        this.connections = connections;
        this.sessions = sessions;
        this.legacyClientProjectionsEnabled = legacyClientProjectionsEnabled;
    }

    public boolean isAllowed(String method, String webSocketSessionId) {
        if (method == null || webSocketSessionId == null) {
            return false;
        }
        if (PRE_BIND_METHODS.contains(method) || SAFE_LOCAL_METHODS.contains(method)) {
            return connections != null
                    && (connections.isFinalized(webSocketSessionId)
                    || connections.findByWebSocketSessionId(webSocketSessionId).isPresent());
        }
        if (legacyClientProjectionsEnabled && LEGACY_CLIENT_PROJECTION_METHODS.contains(method)) {
            return connections != null
                    && (connections.isFinalized(webSocketSessionId)
                    || connections.findByWebSocketSessionId(webSocketSessionId).isPresent());
        }
        if (!POST_BIND_METHODS.contains(method) || connections == null) {
            return false;
        }
        return connections.findByWebSocketSessionId(webSocketSessionId)
                .flatMap(connection -> identities.find(webSocketSessionId)
                        .filter(identity -> matches(identity, connection))
                        .filter(identity -> legacyClientProjectionsEnabled
                                || sessions != null
                                && sessions.matchesCurrentReady(connection, identity)))
                .isPresent();
    }

    private boolean matches(TrustedBusinessIdentity identity, TrustedDesktopConnection connection) {
        return identity.reservationId().equals(connection.reservationId())
                && identity.webSocketSessionId().equals(connection.webSocketSessionId())
                && identity.desktopInstanceId().equals(connection.desktopInstanceId())
                && identity.desktopSessionId().equals(connection.desktopSessionId());
    }
}
