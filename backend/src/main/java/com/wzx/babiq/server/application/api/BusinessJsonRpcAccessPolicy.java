package com.wzx.babiq.server.application.api;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 业务桌面 WebSocket 的精确 JSON-RPC default-deny 策略。 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessJsonRpcAccessPolicy {

    private static final Set<String> PRE_BIND_METHODS = Set.of(
            "application/identity/bind",
            "application/identity/update",
            "model/providers/list",
            "model/providers/set-active",
            "settings/get",
            "settings/update",
            "sandbox/policy",
            "sandbox/policy/set",
            "approval/policy",
            "approval/policy/set");

    private static final Set<String> POST_BIND_METHODS = Set.of(
            "provider/list",
            "provider/create",
            "provider/update",
            "provider/delete",
            "provider/test",
            "provider/set-active",
            "provider/oauth/status",
            "provider/oauth/login",
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
            "application/catalog/register",
            "application/catalog/update",
            "application/context/publish",
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

    /** 兼容无 Spring 的既有策略测试；post-bind 方法因无 finalized registry 默认拒绝。 */
    public BusinessJsonRpcAccessPolicy(ApplicationIdentityRegistry identities) {
        this(identities, null);
    }

    /** 生产构造器同时校验可信身份与仍处于 active 状态的 finalized 连接。 */
    @Autowired
    public BusinessJsonRpcAccessPolicy(
            ApplicationIdentityRegistry identities,
            BusinessDesktopConnectionRegistry connections) {
        this.identities = identities;
        this.connections = connections;
    }

    public boolean isAllowed(String method, String webSocketSessionId) {
        if (method == null || webSocketSessionId == null) {
            return false;
        }
        if (PRE_BIND_METHODS.contains(method)) {
            return true;
        }
        if (!POST_BIND_METHODS.contains(method) || connections == null) {
            return false;
        }
        return identities.find(webSocketSessionId)
                .filter(this::matchesActiveConnection)
                .isPresent();
    }

    private boolean matchesActiveConnection(TrustedBusinessIdentity identity) {
        return connections.findByDesktopSessionId(identity.desktopSessionId())
                .filter(connection -> matches(identity, connection))
                .isPresent();
    }

    private boolean matches(TrustedBusinessIdentity identity, TrustedDesktopConnection connection) {
        return identity.reservationId().equals(connection.reservationId())
                && identity.webSocketSessionId().equals(connection.webSocketSessionId())
                && identity.desktopInstanceId().equals(connection.desktopInstanceId())
                && identity.desktopSessionId().equals(connection.desktopSessionId());
    }
}
