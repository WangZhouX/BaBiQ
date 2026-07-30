package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.upload.BusinessBinaryLeaseLifecycle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessOaAuthenticationServiceTerminalizationTest {

    @Test
    void terminalStateAfterRefreshRevokesTheOriginalInstalledProjection() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        OaSessionCredentialStore.CredentialMaterial material =
                mock(OaSessionCredentialStore.CredentialMaterial.class);
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        BusinessBinaryLeaseLifecycle binaryLifecycle = mock(BusinessBinaryLeaseLifecycle.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease installation = new ApplicationInstallationLease(
                "installation-1", connection, 1, Instant.now().plusSeconds(60));
        TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
                "reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 2,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("workbench.read"));
        ReadyOaSessionLease refreshedLease = new ReadyOaSessionLease(
                "auth-1", "desktop-1", "session-1", "ws-1",
                "user-1", "tenant-1", "2", 3, "refreshed-credential-ref", 2, Instant.now());
        OaSessionRecord refreshedReady = new OaSessionRecord(
                "auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.READY, 3, "refreshed-credential-ref", null, 2,
                null, Instant.now(), null, null, Instant.now());
        OaSessionRecord revoking = phase(refreshedReady, OaSessionPhase.REVOKING, 4);
        OaSessionRecord signedOut = phase(refreshedReady, OaSessionPhase.SIGNED_OUT, 5);

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(refreshedReady));
        when(identities.current(connection)).thenReturn(Optional.of(identity));
        when(identities.installationLease(connection)).thenReturn(Optional.of(installation));
        when(sessions.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, refreshedReady))
                .thenReturn(new BusinessOaSessionRegistry.RevocationClaim(revoking, true));
        when(persistence.revoke("auth-1", 4)).thenReturn(signedOut);
        when(credentials.load("refreshed-credential-ref")).thenReturn(material);
        when(material.accessToken()).thenReturn("remote-access".toCharArray());
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier, binaryLifecycle);

        service.terminate(refreshedLease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);

        verify(installer).revoke(connection, installation);
        var ordered = inOrder(sessions, installer, binaryLifecycle, credentials, gateway, persistence, notifier);
        ordered.verify(sessions).claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, refreshedReady);
        ordered.verify(installer).revoke(connection, installation);
        ordered.verify(binaryLifecycle).revoke(connection, refreshedLease);
        ordered.verify(credentials).load("refreshed-credential-ref");
        ordered.verify(gateway).logout(
                org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.argThat(
                        token -> java.util.Arrays.equals(token, "remote-access".toCharArray())));
        ordered.verify(persistence).revoke("auth-1", 4);
        ordered.verify(persistence).drainReleasedCredentialCleanupStrict(revoking, signedOut);
        ordered.verify(notifier).signedOut(
                refreshedLease, signedOut, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);
    }

    @Test
    void secretDeleteFailureIsVisibleAndNotificationWaitsForAnExplicitSafeRetry() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ReadyOaSessionLease lease = lease();
        OaSessionRecord ready = ready();
        OaSessionRecord revoking = phase(ready, OaSessionPhase.REVOKING, 2);
        OaSessionRecord signedOut = phase(ready, OaSessionPhase.SIGNED_OUT, 3);
        BusinessOaSecretCleanupException cleanupFailure = new BusinessOaSecretCleanupException(
                "SECRET_CLEANUP_INCOMPLETE", "OA credential cleanup is incomplete");

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1"))
                .thenReturn(Optional.of(ready), Optional.of(signedOut));
        when(identities.current(connection)).thenReturn(Optional.empty());
        when(identities.installationLease(connection)).thenReturn(Optional.empty());
        when(sessions.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, ready))
                .thenReturn(new BusinessOaSessionRegistry.RevocationClaim(revoking, true));
        when(persistence.revoke("auth-1", 2)).thenReturn(signedOut);
        doThrow(cleanupFailure).doNothing()
                .when(persistence).drainReleasedCredentialCleanupStrict(revoking, signedOut);
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier);

        assertThatThrownBy(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .isSameAs(cleanupFailure);
        verifyNoInteractions(notifier);

        assertThatCode(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .doesNotThrowAnyException();
        verify(notifier).signedOut(
                lease, signedOut, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);

        assertThatThrownBy(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .isInstanceOf(OaAuthenticatedRequestExecutor.StaleLeaseException.class);
        verify(persistence, times(1)).revoke("auth-1", 2);
        verify(persistence, times(2)).drainReleasedCredentialCleanupStrict(revoking, signedOut);
        verify(notifier, times(1)).signedOut(
                lease, signedOut, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);
    }

    @Test
    void projectionCleanupFailurePreventsRemoteAndDurableCleanupAndDoesNotNotify() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease installation = new ApplicationInstallationLease(
                "installation-1", connection, 0, Instant.now().plusSeconds(60));
        TrustedBusinessIdentity identity = new TrustedBusinessIdentity(
                "reservation-1", "ws-1", "desktop-1", "session-1", "auth-1", 1,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("workbench.read"));
        ReadyOaSessionLease lease = lease();
        OaSessionRecord ready = ready();
        OaSessionRecord revoking = phase(ready, OaSessionPhase.REVOKING, 2);
        OaSessionRecord signedOut = phase(ready, OaSessionPhase.SIGNED_OUT, 3);
        IllegalStateException cleanupFailure = new IllegalStateException("projection cleanup failed");

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(ready));
        when(identities.current(connection)).thenReturn(Optional.of(identity));
        when(identities.installationLease(connection)).thenReturn(Optional.of(installation));
        when(sessions.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, ready))
                .thenReturn(new BusinessOaSessionRegistry.RevocationClaim(revoking, true));
        when(persistence.revoke("auth-1", 2)).thenReturn(signedOut);
        org.mockito.Mockito.doThrow(cleanupFailure).when(installer).revoke(connection, installation);
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier);

        assertThatThrownBy(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .isSameAs(cleanupFailure);

        var ordered = inOrder(sessions, installer);
        ordered.verify(sessions).claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, ready);
        ordered.verify(installer).revoke(connection, installation);
        verifyNoInteractions(credentials);
        verifyNoInteractions(persistence);
        verifyNoInteractions(notifier);
    }

    @Test
    void notificationFailureDoesNotOverrideCompletedDurableRevocation() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ReadyOaSessionLease lease = lease();
        OaSessionRecord ready = ready();
        OaSessionRecord revoking = phase(ready, OaSessionPhase.REVOKING, 2);
        OaSessionRecord signedOut = phase(ready, OaSessionPhase.SIGNED_OUT, 3);

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(ready));
        when(identities.current(connection)).thenReturn(Optional.empty());
        when(identities.installationLease(connection)).thenReturn(Optional.empty());
        when(sessions.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.MEMBERSHIP_EXPIRED, ready))
                .thenReturn(new BusinessOaSessionRegistry.RevocationClaim(revoking, true));
        when(persistence.revoke("auth-1", 2)).thenReturn(signedOut);
        org.mockito.Mockito.doThrow(new IllegalStateException("socket unavailable"))
                .when(notifier).signedOut(
                        lease, signedOut, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier);

        assertThatCode(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED))
                .doesNotThrowAnyException();

        verify(persistence).revoke("auth-1", 2);
        verify(notifier).signedOut(
                lease, signedOut, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
        var ordered = inOrder(persistence, notifier);
        ordered.verify(persistence).revoke("auth-1", 2);
        ordered.verify(notifier).signedOut(
                lease, signedOut, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
    }

    @Test
    void staleLeaseCannotRevokeALaterReadyLoginInTheSameDesktopSlot() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-2", "desktop-1", "session-1", "ws-1");
        OaSessionRecord laterLogin = new OaSessionRecord(
                "auth-1", "desktop-1", "session-1", "user-2", "tenant-2", "2",
                OaSessionPhase.READY, 9, "new-credential-ref", null, 4,
                null, Instant.now(), null, null, Instant.now());

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(laterLogin));
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier);

        assertThatThrownBy(() -> service.terminate(
                lease(), OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .isInstanceOf(OaAuthenticatedRequestExecutor.StaleLeaseException.class);

        verifyNoInteractions(sessions, persistence, installer, identities, attachHandles, notifier);
    }

    @Test
    void revocationLoserDoesNotCleanOrNotifyAfterAnotherReasonWins() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry sessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaReadyInstaller installer = mock(BusinessOaReadyInstaller.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ReadyOaSessionLease lease = lease();
        OaSessionRecord ready = ready();
        OaSessionRecord winnerRevoking = phase(ready, OaSessionPhase.REVOKING, 2);

        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(ready));
        when(identities.current(connection)).thenReturn(Optional.empty());
        when(identities.installationLease(connection)).thenReturn(Optional.empty());
        when(sessions.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.MEMBERSHIP_EXPIRED, ready))
                .thenReturn(new BusinessOaSessionRegistry.RevocationClaim(winnerRevoking, false));
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), persistence, sessions, installer,
                credentials, identities, attachHandles, connections, notifier);

        assertThatThrownBy(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED))
                .isInstanceOf(OaAuthenticatedRequestExecutor.StaleLeaseException.class);

        verify(sessions).claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.MEMBERSHIP_EXPIRED, ready);
        verifyNoInteractions(persistence, installer, credentials, attachHandles, notifier);
    }

    private static ReadyOaSessionLease lease() {
        return new ReadyOaSessionLease(
                "auth-1", "desktop-1", "session-1", "ws-1",
                "user-1", "tenant-1", "2", 1, "credential-ref", 1, Instant.now());
    }

    private static OaSessionRecord ready() {
        return new OaSessionRecord(
                "auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.READY, 1, "credential-ref", null, 1,
                null, Instant.now(), null, null, Instant.now());
    }

    private static OaSessionRecord phase(OaSessionRecord source, OaSessionPhase phase, long generation) {
        return new OaSessionRecord(
                source.authSessionId(), source.desktopInstanceId(), source.desktopSessionId(),
                phase == OaSessionPhase.SIGNED_OUT ? null : source.userId(),
                phase == OaSessionPhase.SIGNED_OUT ? null : source.tenantId(),
                phase == OaSessionPhase.SIGNED_OUT ? null : source.platformId(),
                phase, generation,
                phase == OaSessionPhase.SIGNED_OUT ? null : source.activeCredentialRef(),
                null, source.credentialVersion(), null, source.installedAt(), null,
                phase == OaSessionPhase.SIGNED_OUT ? Instant.now() : null, Instant.now());
    }
}
