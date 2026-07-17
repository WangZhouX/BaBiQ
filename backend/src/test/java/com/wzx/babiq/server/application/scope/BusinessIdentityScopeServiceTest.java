package com.wzx.babiq.server.application.scope;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Thread 创建边界只接收服务端已经认证并绑定的不可变业务身份。 */
class BusinessIdentityScopeServiceTest {

    private final BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
    private final ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-secret", "desktop-secret", "desktop-session-secret", "websocket-secret");
    private final TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
            "reservation-secret", "websocket-secret", "desktop-secret", "desktop-session-secret",
            "auth-session-secret", 8, "user-secret", "tenant-secret", "platform-secret",
            Set.of("lawyer"), Set.of("case:read"));

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("websocket-secret");
        when(session.getAttributes()).thenReturn(attributes(
                "reservation-secret", "desktop-secret", "desktop-session-secret"));
        when(connections.findByDesktopSessionId("desktop-session-secret"))
                .thenReturn(Optional.of(connection));
        when(identities.current(connection)).thenReturn(Optional.of(identity));
    }

    @Test
    void commonModeReturnsTheExplicitUnscopedValueWithoutConsultingBusinessRegistries() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(false, connections, identities);

        BusinessIdentityScope scope = service.resolve(session);

        assertThat(scope).isSameAs(BusinessIdentityScope.UNSCOPED);
        assertThat(scope.scoped()).isFalse();
        verifyNoInteractions(connections, identities);
    }

    @Test
    void businessModeResolvesAllRequiredFieldsFromTheFinalizedConnectionAndCurrentIdentity() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(true, connections, identities);

        BusinessIdentityScope scope = service.resolve(session);

        assertThat(scope).isEqualTo(BusinessIdentityScope.scoped(
                "desktop-secret", "desktop-session-secret", "auth-session-secret", 8,
                "user-secret", "tenant-secret", "platform-secret"));
    }

    @Test
    void businessModeRejectsMissingOrUnfinalizedConnectionState() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(true, connections, identities);

        when(session.getAttributes()).thenReturn(Map.of());
        assertThatThrownBy(() -> service.resolve(session)).isInstanceOf(IllegalStateException.class);

        when(session.getAttributes()).thenReturn(attributes(
                "reservation-secret", "desktop-secret", "unknown-session"));
        when(connections.findByDesktopSessionId("unknown-session")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everySessionAttributeMustMatchTheCurrentFinalizedConnection() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(true, connections, identities);
        Map<String, String> forged = Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-other",
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "desktop-other",
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-other");

        for (Map.Entry<String, String> entry : forged.entrySet()) {
            when(session.getId()).thenReturn("websocket-secret");
            Map<String, Object> attributes = new HashMap<>(attributes(
                    "reservation-secret", "desktop-secret", "desktop-session-secret"));
            attributes.put(entry.getKey(), entry.getValue());
            when(session.getAttributes()).thenReturn(attributes);
            if (entry.getKey().equals(BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE)) {
                when(connections.findByDesktopSessionId("desktop-session-other"))
                        .thenReturn(Optional.of(connection));
            }

            assertThatThrownBy(() -> service.resolve(session))
                    .as(entry.getKey())
                    .isInstanceOf(IllegalStateException.class);
        }

        when(session.getAttributes()).thenReturn(attributes(
                "reservation-secret", "desktop-secret", "desktop-session-secret"));
        when(session.getId()).thenReturn("websocket-other");
        assertThatThrownBy(() -> service.resolve(session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingTransitioningOrStaleIdentityFailsClosed() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(true, connections, identities);

        when(identities.current(connection)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(session)).isInstanceOf(IllegalStateException.class);

        TrustedBusinessIdentity stale = new TrustedBusinessIdentity(
                "reservation-secret", "websocket-old", "desktop-secret", "desktop-session-secret",
                "auth-stale", 7, "user-stale", "tenant-stale", "platform-stale",
                Set.of(), Set.of());
        when(identities.current(connection)).thenReturn(Optional.of(stale));
        assertThatThrownBy(() -> service.resolve(session)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopeIsAValidatedValueAndItsTextNeverLeaksBusinessIdentifiers() {
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop-secret", "desktop-session-secret", "auth-session-secret", 8,
                "user-secret", "tenant-secret", "platform-secret");

        assertThat(scope.toString()).doesNotContain(
                "desktop-secret", "desktop-session-secret", "auth-session-secret",
                "user-secret", "tenant-secret", "platform-secret");
        assertThatThrownBy(() -> BusinessIdentityScope.scoped(
                "", "desktop-session", "auth-session", 8, "user", "tenant", "platform"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth-session", 0, "user", "tenant", "platform"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frozenScopeResolvesOnlyTheExactStillActiveConnectionAndIdentity() {
        BusinessIdentityScopeService service = new BusinessIdentityScopeService(true, connections, identities);
        BusinessIdentityScope frozen = BusinessIdentityScope.scoped(
                "desktop-secret", "desktop-session-secret", "auth-session-secret", 8,
                "user-secret", "tenant-secret", "platform-secret");

        assertThat(service.resolveActive(frozen)).get().satisfies(active -> {
            assertThat(active.connection()).isSameAs(connection);
            assertThat(active.identity()).isSameAs(identity);
        });

        assertThat(service.resolveActive(BusinessIdentityScope.scoped(
                "desktop-secret", "desktop-session-secret", "auth-session-secret", 8,
                "user-secret", "tenant-other", "platform-secret"))).isEmpty();
        when(identities.current(connection)).thenReturn(Optional.empty());
        assertThat(service.resolveActive(frozen)).isEmpty();
    }

    private static Map<String, Object> attributes(
            String reservationId,
            String desktopInstanceId,
            String desktopSessionId) {
        return Map.of(
                BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, reservationId,
                BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, desktopInstanceId,
                BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, desktopSessionId);
    }
}
