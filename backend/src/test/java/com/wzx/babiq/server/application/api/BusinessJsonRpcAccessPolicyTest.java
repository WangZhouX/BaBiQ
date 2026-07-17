package com.wzx.babiq.server.application.api;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessJsonRpcAccessPolicyTest {

    private static final Set<String> PRE_BIND = Set.of(
            "application/identity/bind",
            "application/identity/update",
            "provider/list",
            "provider/create",
            "provider/update",
            "provider/delete",
            "provider/test",
            "provider/set-active",
            "provider/oauth/status",
            "provider/oauth/login",
            "model/providers/list",
            "model/providers/set-active",
            "settings/get",
            "settings/update",
            "sandbox/policy",
            "sandbox/policy/set",
            "approval/policy",
            "approval/policy/set");

    private static final Set<String> POST_BIND_ONLY = Set.of(
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

    private static final Set<String> ALWAYS_DENIED = Set.of(
            "application/action/request",
            "application/action/cancel",
            "run/recovery/status",
            "memory/status",
            "mcp/servers/list",
            "skills/list",
            "workunit/list",
            "team/list",
            "runtime/item/remove",
            "observability/snapshot");

    @Test
    void preBindAllowlistIsExactAndDefaultDeny() {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(identities);

        PRE_BIND.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isTrue());
        POST_BIND_ONLY.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isFalse());
        ALWAYS_DENIED.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isFalse());
        assertThat(policy.isAllowed("future/unknown", "ws-1")).isFalse();
    }

    @Test
    void postBindAllowlistAddsOnlyScopedBusinessMethods() {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        identities.bind(connection(), authenticatedIdentity());
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1"))
                .thenReturn(Optional.of(connection()));
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(identities, connections);

        PRE_BIND.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isTrue());
        POST_BIND_ONLY.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isTrue());
        ALWAYS_DENIED.forEach(method -> assertThat(policy.isAllowed(method, "ws-1")).as(method).isFalse());
        assertThat(policy.isAllowed("future/unknown", "ws-1")).isFalse();
        assertThat(policy.isAllowed("thread/list", "other-ws")).isFalse();
    }

    @Test
    void postBindMethodsAreDeniedAfterFinalizedConnectionReleaseOrIdentityDrift() {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        identities.bind(connection(), authenticatedIdentity());
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        AtomicReference<TrustedDesktopConnection> active = new AtomicReference<>(connection());
        when(connections.findByDesktopSessionId("desktop-session-1"))
                .thenAnswer(invocation -> Optional.ofNullable(active.get()));
        doAnswer(invocation -> {
            active.set(null);
            return true;
        }).when(connections).release("reservation-1", "ws-1");
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(identities, connections);

        assertThat(policy.isAllowed("thread/list", "ws-1")).isTrue();
        connections.release("reservation-1", "ws-1");
        assertThat(policy.isAllowed("thread/list", "ws-1")).isFalse();

        active.set(new TrustedDesktopConnection(
                "reservation-other", "desktop-1", "desktop-session-1", "ws-1"));
        assertThat(policy.isAllowed("thread/list", "ws-1")).isFalse();
    }

    @Test
    void postBindMethodsAreDeniedWhileIdentityIsTransitioning() {
        AtomicReference<BusinessJsonRpcAccessPolicy> policyRef = new AtomicReference<>();
        AtomicBoolean deniedDuringCallback = new AtomicBoolean();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) ->
                        deniedDuringCallback.set(!policyRef.get().isAllowed("thread/list", "ws-1")));
        identities.bind(connection(), authenticatedIdentity());
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1"))
                .thenReturn(Optional.of(connection()));
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(identities, connections);
        policyRef.set(policy);

        identities.update(connection(), new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", "auth-session-2", 2, 2,
                "2026-07-17T00:01:00Z", "user-2", "tenant-2", "platform-2",
                true, Set.of("lawyer"), Set.of("framework:read")));

        assertThat(deniedDuringCallback).isTrue();
        assertThat(policy.isAllowed("thread/list", "ws-1")).isTrue();
    }

    private static TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation-1", "desktop-1", "desktop-session-1", "ws-1");
    }

    private static ApplicationIdentityMessage authenticatedIdentity() {
        return new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 1, 1,
                "2026-07-17T00:00:00Z", "user-1", "tenant-1", "platform-1",
                true, Set.of("lawyer"), Set.of("framework:read"));
    }
}
