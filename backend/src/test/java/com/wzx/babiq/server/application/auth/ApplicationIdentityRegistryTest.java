package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证桌面握手连接上的可信业务身份只能按严格递增 epoch 安装。 */
class ApplicationIdentityRegistryTest {

    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-1", "desktop-1", "desktop-session-1", "websocket-1");

    @Test
    void bindRequiresAuthenticatedProtocolIdentityAndMatchingTrustedConnection() {
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry();

        assertThatThrownBy(() -> registry.bind(connection, identity(8, false, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.bind(connection, identity(
                "desktop-other", "desktop-session-1", 8, true,
                "auth-session-1", "user-1", "tenant-1", "platform-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.bind(connection, identity(
                "desktop-1", "desktop-session-other", 8, true,
                "auth-session-1", "user-1", "tenant-1", "platform-1")))
                .isInstanceOf(IllegalArgumentException.class);

        TrustedBusinessIdentity installed = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        assertThat(registry.find("websocket-1")).contains(installed);
        assertThat(registry.current(connection)).contains(installed);
        assertThat(registry.isAuthenticated("websocket-1")).isTrue();
        assertThatThrownBy(() -> registry.bind(connection,
                identity(9, true, "auth-session-2", "user-1", "tenant-1", "platform-1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateRequiresExistingConnectionAndStrictlyHigherEpochWithoutChangingStateOnRejection() {
        List<IdentityChange> changes = new ArrayList<>();
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) ->
                        changes.add(new IdentityChange(trustedConnection, oldIdentity, newIdentity)));
        TrustedBusinessIdentity original = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        assertThatThrownBy(() -> registry.update(connection,
                identity(8, true, "auth-session-2", "user-2", "tenant-2", "platform-2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.update(connection,
                identity(7, true, "auth-session-2", "user-2", "tenant-2", "platform-2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.current(connection)).contains(original);
        assertThat(changes).isEmpty();

        TrustedBusinessIdentity refreshed = registry.update(connection,
                        identity(9, true, "auth-session-1", "user-1", "tenant-1", "platform-1"))
                .orElseThrow();

        assertThat(refreshed.identityEpoch()).isEqualTo(9);
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.connection()).isEqualTo(connection);
            assertThat(change.oldIdentity()).isEqualTo(original);
            assertThat(change.newIdentity()).isEqualTo(refreshed);
        });

        TrustedDesktopConnection unknown = new TrustedDesktopConnection(
                "reservation-2", "desktop-2", "desktop-session-2", "websocket-2");
        assertThatThrownBy(() -> registry.update(unknown,
                identity("desktop-2", "desktop-session-2", 1, true,
                        "auth-session-2", "user-2", "tenant-2", "platform-2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signedOutHigherEpochClearsAuthenticatedIdentityAndStillNotifiesChange() {
        List<IdentityChange> changes = new ArrayList<>();
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) ->
                        changes.add(new IdentityChange(trustedConnection, oldIdentity, newIdentity)));
        TrustedBusinessIdentity original = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        assertThat(registry.update(connection, identity(9, false, null, null, null, null))).isEmpty();

        assertThat(registry.find("websocket-1")).isEmpty();
        assertThat(registry.current(connection)).isEmpty();
        assertThat(registry.isAuthenticated("websocket-1")).isFalse();
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.oldIdentity()).isEqualTo(original);
            assertThat(change.newIdentity()).isNull();
        });
        assertThatThrownBy(() -> registry.update(connection,
                identity(9, true, "auth-session-1", "user-1", "tenant-1", "platform-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearRemovesIdentityAndEpochWatermarkForTheClosedConnection() {
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry();
        registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        registry.clear(connection);

        assertThat(registry.find("websocket-1")).isEmpty();
        assertThat(registry.isAuthenticated("websocket-1")).isFalse();
        assertThat(registry.bind(connection,
                identity(1, true, "auth-session-new", "user-1", "tenant-1", "platform-1")))
                .extracting(TrustedBusinessIdentity::identityEpoch)
                .isEqualTo(1L);
    }

    @Test
    void trustedIdentityDefensivelyCopiesAuthoritiesAndRedactsBusinessIdentifiers() {
        Set<String> roles = new HashSet<>(Set.of("lawyer"));
        Set<String> permissions = new HashSet<>(Set.of("framework:read"));
        TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
                "reservation-secret", "websocket-1", "desktop-1", "desktop-session-1", "auth-secret", 8,
                "user-secret", "tenant-secret", "platform-secret", roles, permissions);

        roles.add("admin");
        permissions.add("framework:write");

        assertThat(identity.roles()).containsExactly("lawyer");
        assertThat(identity.permissions()).containsExactly("framework:read");
        assertThatThrownBy(() -> identity.roles().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(identity.toString())
                .doesNotContain("reservation-secret", "auth-secret", "user-secret", "tenant-secret", "platform-secret");
    }

    @Test
    void listenerRunsBeforeCommitAndMayReenterCurrentWithoutObservingThePreparedIdentity() {
        AtomicReference<ApplicationIdentityRegistry> registryRef = new AtomicReference<>();
        AtomicBoolean callbackSawTransitionClosed = new AtomicBoolean();
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) -> callbackSawTransitionClosed.set(
                        registryRef.get().current(trustedConnection).isEmpty()
                                && registryRef.get().find(trustedConnection.webSocketSessionId()).isEmpty()
                                && !registryRef.get().isAuthenticated(trustedConnection.webSocketSessionId())));
        registryRef.set(registry);
        TrustedBusinessIdentity original = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        TrustedBusinessIdentity updated = registry.update(connection,
                        identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2"))
                .orElseThrow();

        assertThat(callbackSawTransitionClosed).isTrue();
        assertThat(registry.current(connection)).contains(updated);
    }

    @Test
    void listenerFailureKeepsOldStateAndTheSameHigherEpochCanBeRetried() {
        AtomicBoolean failNext = new AtomicBoolean(true);
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) -> {
                    if (failNext.getAndSet(false)) {
                        throw new IllegalStateException("listener failure secret");
                    }
                });
        TrustedBusinessIdentity original = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        assertThatThrownBy(() -> registry.update(connection,
                identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.current(connection)).contains(original);

        TrustedBusinessIdentity retried = registry.update(connection,
                        identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2"))
                .orElseThrow();
        assertThat(retried.identityEpoch()).isEqualTo(9);
        assertThat(registry.current(connection)).contains(retried);
    }

    @Test
    void springConstructorComposesAllOrderedIdentityChangeListeners() {
        List<String> calls = new ArrayList<>();
        ApplicationIdentityRegistry.IdentityChangeListener first =
                (trustedConnection, oldIdentity, newIdentity) -> calls.add("first");
        ApplicationIdentityRegistry.IdentityChangeListener second =
                (trustedConnection, oldIdentity, newIdentity) -> calls.add("second");
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationIdentityRegistry.IdentityChangeListener> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(first, second));
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(provider);
        registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        registry.update(connection,
                identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2"));

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void transitionIsFailClosedDuringCallbackAndCleanupThenPublishesTheNewIdentity() {
        AtomicReference<ApplicationIdentityRegistry> registryRef = new AtomicReference<>();
        AtomicBoolean callbackSawClosedIdentity = new AtomicBoolean();
        AtomicBoolean cleanupSawClosedIdentity = new AtomicBoolean();
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) -> callbackSawClosedIdentity.set(
                        registryRef.get().find(trustedConnection.webSocketSessionId()).isEmpty()
                                && registryRef.get().current(trustedConnection).isEmpty()
                                && !registryRef.get().isAuthenticated(trustedConnection.webSocketSessionId())));
        registryRef.set(registry);
        registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        TrustedBusinessIdentity updated = registry.update(
                        connection,
                        identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2"),
                        () -> cleanupSawClosedIdentity.set(
                                registry.find("websocket-1").isEmpty()
                                        && registry.current(connection).isEmpty()
                                        && !registry.isAuthenticated("websocket-1")))
                .orElseThrow();

        assertThat(callbackSawClosedIdentity).isTrue();
        assertThat(cleanupSawClosedIdentity).isTrue();
        assertThat(registry.current(connection)).contains(updated);
        assertThat(registry.isAuthenticated("websocket-1")).isTrue();
    }

    @Test
    void cleanupFailureRestoresOldIdentityAndClearsTransitionState() {
        ApplicationIdentityRegistry registry = new ApplicationIdentityRegistry();
        TrustedBusinessIdentity original = registry.bind(connection,
                identity(8, true, "auth-session-1", "user-1", "tenant-1", "platform-1"));

        assertThatThrownBy(() -> registry.update(
                connection,
                identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2"),
                () -> {
                    throw new IllegalStateException("cleanup secret failure");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.current(connection)).contains(original);
        assertThat(registry.isAuthenticated("websocket-1")).isTrue();
        assertThat(registry.update(connection,
                identity(9, true, "auth-session-2", "user-2", "tenant-2", "platform-2")))
                .get()
                .extracting(TrustedBusinessIdentity::identityEpoch)
                .isEqualTo(9L);
    }

    private ApplicationIdentityMessage identity(
            long epoch,
            boolean authenticated,
            String authSessionId,
            String userId,
            String tenantId,
            String platformId) {
        return identity("desktop-1", "desktop-session-1", epoch, authenticated,
                authSessionId, userId, tenantId, platformId);
    }

    private ApplicationIdentityMessage identity(
            String desktopInstanceId,
            String desktopSessionId,
            long epoch,
            boolean authenticated,
            String authSessionId,
            String userId,
            String tenantId,
            String platformId) {
        return new ApplicationIdentityMessage(
                "1.0", desktopInstanceId, desktopSessionId, authSessionId, epoch, epoch,
                "2026-07-17T00:00:00Z", userId, tenantId, platformId, authenticated,
                authenticated ? Set.of("lawyer") : Set.of(),
                authenticated ? Set.of("framework:read", "framework:write") : Set.of());
    }

    private record IdentityChange(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity oldIdentity,
            TrustedBusinessIdentity newIdentity) {
    }
}
