package com.wzx.babiq.server.business.oa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationError;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationException;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessOaRestoreFailureTest {

    @Test
    void concurrentTerminalReasonsHaveOneRevocationWinnerAndOneNotification() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        Fixture fixture = fixture(repository, credentials, gateway, notifier);
        login(fixture);
        ReadyOaSessionLease lease = fixture.sessions.captureReady(fixture.connection);
        repository.blockReadyReaders(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Throwable> authenticationExpired = executor.submit(() -> catchThrowable(() ->
                    fixture.authentication.terminate(
                            lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED)));
            Future<Throwable> membershipExpired = executor.submit(() -> catchThrowable(() ->
                    fixture.authentication.terminate(
                            lease, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED)));

            Throwable authenticationFailure = authenticationExpired.get(5, TimeUnit.SECONDS);
            Throwable membershipFailure = membershipExpired.get(5, TimeUnit.SECONDS);
            assertThat(Stream.of(authenticationFailure, membershipFailure)
                    .filter(java.util.Objects::isNull)).hasSize(1);
            assertThat(Stream.of(authenticationFailure, membershipFailure)
                    .filter(java.util.Objects::nonNull))
                    .allMatch(OaAuthenticatedRequestExecutor.StaleLeaseException.class::isInstance);
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            ArgumentCaptor<OaRemoteRequestException.TerminalReason> reason =
                    ArgumentCaptor.forClass(OaRemoteRequestException.TerminalReason.class);
            verify(notifier, times(1)).signedOut(eq(lease), any(OaSessionRecord.class), reason.capture());
            assertThat(reason.getValue()).isEqualTo(authenticationFailure == null
                    ? OaRemoteRequestException.TerminalReason.AUTH_EXPIRED
                    : OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sessionDoesNotExposeRememberedAccountAcrossDesktopInstancesThatReuseTheSameSessionId() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);

        BusinessAuthDtos.Session firstReady = login(fixture);
        TrustedDesktopConnection reusedSessionId = new TrustedDesktopConnection(
                "reservation-2", "desktop-2", fixture.connection.desktopSessionId(), "ws-2");

        assertThat(firstReady.rememberedAccount()).isEqualTo("alice");
        assertThat(fixture.authentication.session(reusedSessionId).rememberedAccount()).isNull();
    }

    @Test
    void repeatedRefreshMembershipTerminalizationClearsTheOriginalRememberedAccountBinding() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        when(gateway.refresh(eq("tenant-1"), any(char[].class)))
                .thenReturn(new OaAuthDtos.OaCredential(
                        "refresh-access-1", "refresh-token-1", "user-1", 123L))
                .thenReturn(new OaAuthDtos.OaCredential(
                        "refresh-access-2", "refresh-token-2", "user-1", 123L));
        Fixture fixture = fixture(repository, credentials, gateway);
        BusinessAuthDtos.Session firstReady = login(fixture);
        ReadyOaSessionLease original = fixture.sessions.captureReady(fixture.connection);
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                fixture.sessions, repository, fixture.persistence, credentials, gateway);
        ReadyOaSessionLease refreshedOnce = refresh.refresh(original).join();
        ReadyOaSessionLease refreshedTwice = refresh.refresh(refreshedOnce).join();

        fixture.authentication.terminate(
                refreshedTwice, OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);

        assertThat(firstReady.rememberedAccount()).isEqualTo("alice");
        assertThat(refreshedTwice.generation()).isGreaterThan(original.generation() + 1);
        assertThat(fixture.authentication.session(fixture.connection).rememberedAccount()).isNull();
        assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    @Test
    void login_remote_failure_returns_to_signed_out_and_preserves_the_original_oa_error() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.findTenantCandidates("alice")).thenReturn(List.of(
                new OaAuthDtos.OaTenantCandidate(
                        "user-1", "tenant-1", 2, "Firm", 1, null, "alice")));
        OaAuthenticationException remoteFailure =
                new OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE);
        when(gateway.login(any(OaAuthDtos.OaTenantCandidate.class), any(char[].class)))
                .thenThrow(remoteFailure);
        Fixture fixture = fixture(repository, credentials, gateway);
        String candidateId = fixture.authentication.findTenantCandidates(
                fixture.connection, "alice").candidates().getFirst().candidateId();

        Throwable thrown = catchThrowable(() -> fixture.authentication.login(
                fixture.connection, "alice", candidateId, "secret".toCharArray()));

        assertThat(thrown).isSameAs(remoteFailure);
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(failed.generation()).isEqualTo(2);
        assertThat(failed.activeCredentialRef()).isNull();
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(secrets.size()).isZero();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    @Test
    void login_permission_failure_returns_to_signed_out_and_preserves_the_original_oa_error() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        OaAuthenticationException remoteFailure =
                new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR);
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenThrow(remoteFailure);
        Fixture fixture = fixture(repository, credentials, gateway);
        String candidateId = candidateId(fixture);

        Throwable thrown = catchThrowable(() -> fixture.authentication.login(
                fixture.connection, "alice", candidateId, "secret".toCharArray()));

        assertThat(thrown).isSameAs(remoteFailure);
        assertSignedOutLoginFailure(repository.record(), secrets, fixture);
    }

    @Test
    void login_permission_identity_mismatch_fails_before_staging() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "different-user", "Other", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        String candidateId = candidateId(fixture);

        Throwable thrown = catchThrowable(() -> fixture.authentication.login(
                fixture.connection, "alice", candidateId, "secret".toCharArray()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("OA permission identity mismatch");
        assertSignedOutLoginFailure(repository.record(), secrets, fixture);
        assertThat(repository.record().credentialVersion()).isZero();
        assertThat(repository.record().revokedAt()).isNull();
    }

    @Test
    void login_candidate_credential_identity_mismatch_fails_before_staging() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.findTenantCandidates("alice")).thenReturn(List.of(
                new OaAuthDtos.OaTenantCandidate(
                        "candidate-user", "tenant-1", 2, "Firm", 1, null, "alice")));
        when(gateway.login(any(OaAuthDtos.OaTenantCandidate.class), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential(
                        "new-access", "new-refresh", "credential-user", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "credential-user", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        String candidateId = candidateId(fixture);

        Throwable thrown = catchThrowable(() -> fixture.authentication.login(
                fixture.connection, "alice", candidateId, "secret".toCharArray()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("OA login identity mismatch");
        assertSignedOutLoginFailure(repository.record(), secrets, fixture);
        assertThat(repository.record().credentialVersion()).isZero();
    }

    @Test
    void login_uses_candidate_identity_when_credential_user_is_absent() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.login(any(OaAuthDtos.OaTenantCandidate.class), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", null, 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);

        BusinessAuthDtos.Session ready = login(fixture);

        assertThat(ready.state()).isEqualTo(OaSessionPhase.READY.name());
        assertThat(ready.userId()).isEqualTo("user-1");
        assertThat(repository.record().userId()).isEqualTo("user-1");
        assertThat(fixture.identities.current(fixture.connection))
                .get()
                .extracting(TrustedBusinessIdentity::userId)
                .isEqualTo("user-1");
    }

    @Test
    void logout_is_idempotent_and_allows_the_same_desktop_session_to_log_in_again() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        BusinessAuthDtos.Session ready = login(fixture);

        BusinessAuthDtos.Session first = fixture.authentication.logout(fixture.connection);
        long signedOutGeneration = first.generation();
        BusinessAuthDtos.Session repeated = fixture.authentication.logout(fixture.connection);

        assertThat(ready.state()).isEqualTo(OaSessionPhase.READY.name());
        assertThat(first.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(repeated.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(repeated.generation()).isEqualTo(signedOutGeneration);
        assertThat(repository.record().activeCredentialRef()).isNull();
        assertThat(repository.record().stagedCredentialRef()).isNull();
        assertThat(repository.record().installationId()).isNull();
        assertThat(secrets.size()).isZero();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
        verify(gateway, times(1)).logout(eq("tenant-1"), any(char[].class));

        BusinessAuthDtos.Session loggedInAgain = login(fixture);
        assertThat(loggedInAgain.state()).isEqualTo(OaSessionPhase.READY.name());
        assertThat(loggedInAgain.generation()).isGreaterThan(signedOutGeneration);
        assertThat(loggedInAgain.identityEpoch()).isGreaterThan(ready.identityEpoch());
        assertThat(secrets.size()).isEqualTo(1);
    }

    @Test
    void logout_credential_read_failure_is_best_effort_and_returns_signed_out_without_remembered_account() {
        MemoryRepository repository = new MemoryRepository();
        FailingLoadSecretStore secrets = new FailingLoadSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        assertThat(login(fixture).rememberedAccount()).isEqualTo("alice");
        secrets.failLoads();
        AtomicReference<BusinessAuthDtos.Session> result = new AtomicReference<>();

        Throwable thrown = catchThrowable(() -> result.set(
                fixture.authentication.logout(fixture.connection)));

        assertThat(thrown).as("credential read and remote logout are best effort").isNull();
        assertThat(result.get().state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(result.get().rememberedAccount()).isNull();
        assertThat(fixture.authentication.session(fixture.connection).rememberedAccount()).isNull();
        assertThat(repository.record().activeCredentialRef()).isNull();
        assertThat(repository.record().stagedCredentialRef()).isNull();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
        verify(gateway, never()).logout(any(), any(char[].class));
    }

    @Test
    void logout_gateway_failure_is_best_effort_while_durable_and_projection_revocation_complete() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        login(fixture);
        doThrow(new IllegalStateException("injected gateway logout failure"))
                .when(gateway).logout(eq("tenant-1"), any(char[].class));

        AtomicReference<BusinessAuthDtos.Session> result = new AtomicReference<>();
        Throwable thrown = catchThrowable(() -> result.set(
                fixture.authentication.logout(fixture.connection)));

        assertThat(thrown).as("remote gateway logout is best effort").isNull();
        assertLogoutCompleted(repository, fixture, result.get());
        assertThat(secrets.size()).isZero();
        verify(gateway, times(1)).logout(eq("tenant-1"), any(char[].class));
    }

    @Test
    void logout_closes_gate_and_clears_projections_before_blocking_remote_logout()
            throws Exception {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        CountDownLatch remoteLogoutEntered = new CountDownLatch(1);
        CountDownLatch allowRemoteLogout = new CountDownLatch(1);
        doAnswer(invocation -> {
            remoteLogoutEntered.countDown();
            if (!allowRemoteLogout.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("remote logout latch timeout");
            }
            return null;
        }).when(gateway).logout(eq("tenant-1"), any(char[].class));
        Fixture fixture = fixture(repository, credentials, gateway);
        assertThat(login(fixture).rememberedAccount()).isEqualTo("alice");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BusinessAuthDtos.Session> logout = executor.submit(
                () -> fixture.authentication.logout(fixture.connection));

        try {
            assertThat(remoteLogoutEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.REVOKING);
            assertThat(fixture.authentication.session(fixture.connection).rememberedAccount()).isNull();
            assertThat(fixture.identities.current(fixture.connection)).isEmpty();
            assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
            assertThat(fixture.contexts.current(fixture.connection)).isEmpty();

            allowRemoteLogout.countDown();
            assertLogoutCompleted(repository, fixture, logout.get(5, TimeUnit.SECONDS));
        } finally {
            allowRemoteLogout.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logout_credential_material_close_failure_is_best_effort() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore durableCredentials = new OaSessionCredentialStore(secrets);
        OaSessionCredentialStore logoutCredentials = mock(OaSessionCredentialStore.class);
        OaSessionCredentialStore.CredentialMaterial material =
                mock(OaSessionCredentialStore.CredentialMaterial.class);
        when(material.accessToken()).thenReturn("new-access".toCharArray());
        doThrow(new IllegalStateException("injected credential material close failure"))
                .when(material).close();
        when(logoutCredentials.load(anyString())).thenReturn(material);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, durableCredentials, logoutCredentials,
                gateway, new ApplicationIdentityRegistry(), UnaryOperator.identity());
        login(fixture);

        AtomicReference<BusinessAuthDtos.Session> result = new AtomicReference<>();
        Throwable thrown = catchThrowable(() -> result.set(
                fixture.authentication.logout(fixture.connection)));

        assertThat(thrown).as("credential material close is best effort").isNull();
        assertLogoutCompleted(repository, fixture, result.get());
        assertThat(secrets.size()).isZero();
        verify(material).close();
        verify(gateway, times(1)).logout(eq("tenant-1"), any(char[].class));
    }

    @Test
    void logout_persistence_revoke_failure_is_visible_and_projection_cleanup_still_runs() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        Fixture fixture = fixture(repository, credentials, credentials, gateway,
                new ApplicationIdentityRegistry(), persistence -> spy(persistence));
        login(fixture);
        IllegalStateException persistenceFailure =
                new IllegalStateException("injected persistence revoke failure");
        doThrow(persistenceFailure).when(fixture.persistence)
                .revoke(anyString(), anyLong());

        Throwable thrown = catchThrowable(() ->
                fixture.authentication.logout(fixture.connection));

        assertThat(thrown).isSameAs(persistenceFailure);
        assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(fixture.authentication.session(fixture.connection).rememberedAccount()).isNull();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
        assertThat(secrets.size()).isEqualTo(1);
    }

    @Test
    void logout_projection_revoke_failure_is_visible_and_leaves_fail_closed_revoking_state() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        FailingRevokeIdentityRegistry identities = new FailingRevokeIdentityRegistry();
        Fixture fixture = fixture(repository, credentials, gateway, identities);
        login(fixture);
        identities.failRevocation();

        Throwable thrown = catchThrowable(() ->
                fixture.authentication.logout(fixture.connection));

        assertThat(thrown).isSameAs(identities.failure);
        assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(fixture.authentication.session(fixture.connection).rememberedAccount()).isNull();
        assertThat(fixture.identities.current(fixture.connection)).isPresent();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
        assertThat(secrets.size()).isEqualTo(1);
        verify(gateway, never()).logout(any(), any(char[].class));
    }

    @Test
    void explicit_logout_notifies_identity_listener_after_all_projections_are_cleared() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaAuthenticationGateway gateway = loginGateway();
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        List<LogoutIdentityChange> changes = new ArrayList<>();
        AtomicReference<ApplicationIdentityRegistry> identitiesRef = new AtomicReference<>();
        AtomicReference<ApplicationCatalogRegistry> catalogsRef = new AtomicReference<>();
        AtomicReference<ApplicationPageContextRegistry> contextsRef = new AtomicReference<>();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry(
                (connection, oldIdentity, newIdentity) -> changes.add(new LogoutIdentityChange(
                        oldIdentity,
                        newIdentity,
                        identitiesRef.get().current(connection).isEmpty(),
                        catalogsRef.get().current(connection).isEmpty(),
                        contextsRef.get().current(connection).isEmpty())));
        identitiesRef.set(identities);
        Fixture fixture = fixture(repository, credentials, gateway, identities);
        catalogsRef.set(fixture.catalogs);
        contextsRef.set(fixture.contexts);
        login(fixture);
        TrustedBusinessIdentity installed = identities.current(fixture.connection).orElseThrow();

        BusinessAuthDtos.Session result = fixture.authentication.logout(fixture.connection);

        assertThat(result.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.oldIdentity()).isEqualTo(installed);
            assertThat(change.newIdentity()).isNull();
            assertThat(change.identityWasCleared()).isTrue();
            assertThat(change.catalogWasCleared()).isTrue();
            assertThat(change.contextWasCleared()).isTrue();
        });
    }

    @Test
    void logout_normalizes_a_legacy_revoked_session_to_signed_out() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        repository.put(new OaSessionRecord(
                "auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.REVOKED, 7, activeRef, stagedRef, 2,
                now, now, null, now, now));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        Fixture fixture = fixture(repository, credentials, gateway);

        BusinessAuthDtos.Session result = fixture.authentication.logout(fixture.connection);

        assertThat(result.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(result.generation()).isEqualTo(8);
        assertThat(repository.record().activeCredentialRef()).isNull();
        assertThat(repository.record().stagedCredentialRef()).isNull();
        assertThat(repository.record().revokedAt()).isNull();
        assertThat(secrets.size()).isZero();
        verify(gateway, never()).logout(any(), any(char[].class));
    }

    @Test
    void logout_from_installing_uses_the_staged_credential_and_cleans_the_attempt() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        repository.put(new OaSessionRecord(
                "auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.INSTALLING, 6, activeRef, stagedRef, 2,
                now, now, null, null, now,
                "installation-1", "desktop-1", "session-1", 6,
                now.plusSeconds(90)));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        AtomicReference<String> remoteAccessToken = new AtomicReference<>();
        doAnswer(invocation -> {
            remoteAccessToken.set(new String(invocation.getArgument(1, char[].class)));
            return null;
        }).when(gateway).logout(eq("tenant-1"), any(char[].class));
        Fixture fixture = fixture(repository, credentials, gateway);

        BusinessAuthDtos.Session result = fixture.authentication.logout(fixture.connection);

        assertThat(result.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(result.generation()).isEqualTo(8);
        assertThat(remoteAccessToken).hasValue("new-access");
        assertThat(repository.record().activeCredentialRef()).isNull();
        assertThat(repository.record().stagedCredentialRef()).isNull();
        assertThat(repository.record().installationId()).isNull();
        assertThat(repository.record().installationExpiresAt()).isNull();
        assertThat(secrets.size()).isZero();
    }

    @Test
    void attach_refresh_failure_returns_to_detached_and_preserves_the_original_oa_error() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", activeRef));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        OaAuthenticationException remoteFailure =
                new OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenThrow(remoteFailure);
        Fixture fixture = fixture(repository, credentials, gateway);
        String handle = fixture.authentication.session(fixture.connection).attachHandle();

        Throwable thrown = catchThrowable(() -> fixture.authentication.attach(fixture.connection, handle));

        assertThat(thrown).isSameAs(remoteFailure);
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(7);
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    @Test
    void installer_identity_mismatch_aborts_the_exact_staged_restore() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", activeRef));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "different-user", "Other", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);
        String handle = fixture.authentication.session(fixture.connection).attachHandle();

        Throwable thrown = catchThrowable(() -> fixture.authentication.attach(fixture.connection, handle));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("OA permission identity mismatch");
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(7);
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(failed.installationOwnerDesktopInstanceId()).isNull();
        assertThat(failed.installationOwnerDesktopSessionId()).isNull();
        assertThat(failed.installationExpiresAt()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    @Test
    void startup_restore_rejects_refreshed_user_id_drift_before_staging() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("old-session", activeRef));
        OaAuthenticationGateway gateway = successfulGateway();
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("access-b", "refresh-b", "user-b", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-b", "Other", List.of()));
        Fixture fixture = fixture(repository, credentials, gateway);

        Throwable thrown = catchThrowable(() -> fixture.authentication.restore(fixture.connection));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("OA restore identity mismatch");
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.userId()).isEqualTo("user-1");
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(secrets.size()).isEqualTo(1);
    }

    @Test
    void startup_restore_refresh_failure_returns_the_rebound_session_to_detached() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("old-session", activeRef));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        OaAuthenticationException remoteFailure =
                new OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenThrow(remoteFailure);
        Fixture fixture = fixture(repository, credentials, gateway);

        Throwable thrown = catchThrowable(() -> fixture.authentication.restore(fixture.connection));

        assertThat(thrown).isSameAs(remoteFailure);
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(8);
        assertThat(failed.desktopSessionId()).isEqualTo("session-1");
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    @Test
    void attach_deadline_expiring_before_activation_never_publishes_ready() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", activeRef));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        OaAuthenticationGateway gateway = successfulGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new DeadlineAdvancingContextRegistry(
                identities, catalogs, clock, Duration.ofSeconds(60));
        DurableOaSessionFixture durable = DurableOaSessionFixture.memory(
                repository, credentials, clock);
        OaSessionPersistenceService persistence = durable.persistence();
        BusinessOaSessionRegistry sessions = durable.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, attachHandles,
                finalized.registry());
        String handle = authentication.session(connection).attachHandle();

        Throwable thrown = catchThrowable(() -> authentication.attach(connection, handle));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(7);
        assertThat(repository.readyWrites).isZero();
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(failed.installationExpiresAt()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
        assertThat(identities.current(connection)).isEmpty();
        assertThat(catalogs.current(connection)).isEmpty();
        assertThat(contexts.current(connection)).isEmpty();
    }

    @Test
    void attach_deadline_expiring_after_activation_compensates_the_exact_ready_attempt() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String oldActiveRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", oldActiveRef));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        repository.afterReadyWrite = () -> clock.advance(Duration.ofSeconds(60));
        OaAuthenticationGateway gateway = successfulGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture durable = DurableOaSessionFixture.memory(
                repository, credentials, clock);
        OaSessionPersistenceService persistence = durable.persistence();
        BusinessOaSessionRegistry sessions = durable.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, attachHandles,
                finalized.registry());
        String handle = authentication.session(connection).attachHandle();

        Throwable thrown = catchThrowable(() -> authentication.attach(connection, handle));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(8);
        assertThat(repository.readyWrites).isEqualTo(1);
        assertThat(failed.activeCredentialRef()).isNotEqualTo(oldActiveRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(failed.installationExpiresAt()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material =
                     credentials.load(failed.activeCredentialRef())) {
            assertThat(material).isNotNull();
        }
        assertThat(identities.current(connection)).isEmpty();
        assertThat(catalogs.current(connection)).isEmpty();
        assertThat(contexts.current(connection)).isEmpty();
    }

    @Test
    void attach_permission_failure_preserves_the_original_oa_error_and_old_active_credential() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", activeRef));
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L));
        OaAuthenticationException remoteFailure =
                new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR);
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenThrow(remoteFailure);
        Fixture fixture = fixture(repository, credentials, gateway);
        String handle = fixture.authentication.session(fixture.connection).attachHandle();

        Throwable thrown = catchThrowable(() -> fixture.authentication.attach(fixture.connection, handle));

        assertThat(thrown).isSameAs(remoteFailure);
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(7);
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
    }

    @Test
    void activation_cas_failure_aborts_installing_and_clears_projections() {
        MemoryRepository repository = new MemoryRepository();
        repository.failReadyWrite = true;
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("session-1", activeRef));
        Fixture fixture = fixture(repository, credentials, successfulGateway());
        String handle = fixture.authentication.session(fixture.connection).attachHandle();

        Throwable thrown = catchThrowable(() -> fixture.authentication.attach(fixture.connection, handle));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
        OaSessionRecord failed = repository.record();
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(failed.generation()).isEqualTo(7);
        assertThat(repository.readyWrites).isZero();
        assertThat(failed.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(failed.installationExpiresAt()).isNull();
        assertThat(secrets.size()).isEqualTo(1);
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(activeRef)) {
            assertThat(material).isNotNull();
        }
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    private static OaAuthenticationGateway successfulGateway() {
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        return gateway;
    }

    private static OaAuthenticationGateway loginGateway() {
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.findTenantCandidates("alice")).thenReturn(List.of(
                new OaAuthDtos.OaTenantCandidate(
                        "user-1", "tenant-1", 2, "Firm", 1, null, "alice")));
        when(gateway.login(any(OaAuthDtos.OaTenantCandidate.class), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L));
        return gateway;
    }

    private static String candidateId(Fixture fixture) {
        return fixture.authentication.findTenantCandidates(
                fixture.connection, "alice").candidates().getFirst().candidateId();
    }

    private static BusinessAuthDtos.Session login(Fixture fixture) {
        return fixture.authentication.login(
                fixture.connection, "alice", candidateId(fixture), "secret".toCharArray());
    }

    private static void assertLogoutCompleted(
            MemoryRepository repository,
            Fixture fixture,
            BusinessAuthDtos.Session result) {
        assertThat(result.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        assertThat(result.rememberedAccount()).isNull();
        assertThat(repository.record().activeCredentialRef()).isNull();
        assertThat(repository.record().stagedCredentialRef()).isNull();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    private static void assertSignedOutLoginFailure(
            OaSessionRecord failed,
            DurableOaSessionFixture.MemorySecretStore secrets,
            Fixture fixture) {
        assertThat(failed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(failed.generation()).isEqualTo(2);
        assertThat(failed.userId()).isNull();
        assertThat(failed.tenantId()).isNull();
        assertThat(failed.platformId()).isNull();
        assertThat(failed.activeCredentialRef()).isNull();
        assertThat(failed.stagedCredentialRef()).isNull();
        assertThat(failed.installationId()).isNull();
        assertThat(failed.installationExpiresAt()).isNull();
        assertThat(secrets.size()).isZero();
        assertThat(fixture.identities.current(fixture.connection)).isEmpty();
        assertThat(fixture.catalogs.current(fixture.connection)).isEmpty();
        assertThat(fixture.contexts.current(fixture.connection)).isEmpty();
    }

    private static Fixture fixture(MemoryRepository repository,
                                   OaSessionCredentialStore credentials,
                                   OaAuthenticationGateway gateway) {
        return fixture(repository, credentials, gateway, new ApplicationIdentityRegistry());
    }

    private static Fixture fixture(MemoryRepository repository,
                                   OaSessionCredentialStore credentials,
                                   OaAuthenticationGateway gateway,
                                   BusinessAuthStateNotifier notifier) {
        return fixture(repository, credentials, credentials, gateway,
                new ApplicationIdentityRegistry(), UnaryOperator.identity(), notifier);
    }

    private static Fixture fixture(MemoryRepository repository,
                                   OaSessionCredentialStore credentials,
                                   OaAuthenticationGateway gateway,
                                   ApplicationIdentityRegistry identities) {
        return fixture(repository, credentials, credentials, gateway, identities,
                UnaryOperator.identity());
    }

    private static Fixture fixture(
            MemoryRepository repository,
            OaSessionCredentialStore durableCredentials,
            OaSessionCredentialStore authenticationCredentials,
            OaAuthenticationGateway gateway,
            ApplicationIdentityRegistry identities,
            UnaryOperator<OaSessionPersistenceService> persistenceDecorator) {
        return fixture(repository, durableCredentials, authenticationCredentials, gateway,
                identities, persistenceDecorator, BusinessAuthStateNotifier.noop());
    }

    private static Fixture fixture(
            MemoryRepository repository,
            OaSessionCredentialStore durableCredentials,
            OaSessionCredentialStore authenticationCredentials,
            OaAuthenticationGateway gateway,
            ApplicationIdentityRegistry identities,
            UnaryOperator<OaSessionPersistenceService> persistenceDecorator,
            BusinessAuthStateNotifier notifier) {
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture durable = DurableOaSessionFixture.memory(repository, durableCredentials);
        OaSessionPersistenceService persistence = persistenceDecorator.apply(durable.persistence());
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        FinalizedConnection finalized = finalizedConnection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, authenticationCredentials, identities,
                new BusinessOaAttachHandleRegistry(repository), finalized.registry(), notifier);
        return new Fixture(authentication, persistence, sessions, identities, catalogs, contexts,
                finalized.connection());
    }

    private static FinalizedConnection finalizedConnection() {
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry(mode);
        String reservation = registry.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        return new FinalizedConnection(registry, connection);
    }

    private static OaSessionRecord detached(String desktopSessionId, String activeRef) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", desktopSessionId,
                "user-1", "tenant-1", "2", OaSessionPhase.DETACHED, 5,
                activeRef, null, 1, null, now, now, null, now);
    }

    private record Fixture(BusinessOaAuthenticationService authentication,
                           OaSessionPersistenceService persistence,
                           BusinessOaSessionRegistry sessions,
                           ApplicationIdentityRegistry identities,
                           ApplicationCatalogRegistry catalogs,
                           ApplicationPageContextRegistry contexts,
                           TrustedDesktopConnection connection) { }

    private record FinalizedConnection(BusinessDesktopConnectionRegistry registry,
                                       TrustedDesktopConnection connection) { }

    private record LogoutIdentityChange(TrustedBusinessIdentity oldIdentity,
                                        TrustedBusinessIdentity newIdentity,
                                        boolean identityWasCleared,
                                        boolean catalogWasCleared,
                                        boolean contextWasCleared) { }

    private static final class FailingLoadSecretStore implements SecretStore {
        private final DurableOaSessionFixture.MemorySecretStore delegate =
                new DurableOaSessionFixture.MemorySecretStore();
        private final AtomicBoolean failLoads = new AtomicBoolean();

        void failLoads() {
            failLoads.set(true);
        }

        @Override public String allocateRef(String namespace) {
            return delegate.allocateRef(namespace);
        }

        @Override public void saveCharsAtRef(String secretRef, char[] secretChars) {
            delegate.saveCharsAtRef(secretRef, secretChars);
        }

        @Override public List<String> listRefs(String namespacePrefix) {
            return delegate.listRefs(namespacePrefix);
        }

        @Override public String save(String namespace, String secretPlainText) {
            return delegate.save(namespace, secretPlainText);
        }

        @Override public Optional<String> load(String secretRef) {
            return delegate.load(secretRef);
        }

        @Override public Optional<char[]> loadChars(String secretRef) {
            if (failLoads.get()) {
                throw new IllegalStateException("injected credential read failure");
            }
            return delegate.loadChars(secretRef);
        }

        @Override public void delete(String secretRef) {
            delegate.delete(secretRef);
        }
    }

    private static final class FailingRevokeIdentityRegistry extends ApplicationIdentityRegistry {
        private final IllegalStateException failure =
                new IllegalStateException("injected projection revoke failure");
        private boolean failRevocation;

        void failRevocation() {
            failRevocation = true;
        }

        @Override
        public boolean revokeInstallation(
                TrustedDesktopConnection connection,
                ApplicationInstallationLease installationLease) {
            if (failRevocation) {
                throw failure;
            }
            return super.revokeInstallation(connection, installationLease);
        }
    }

    private static final class MemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;
        private int readyWrites;
        private Runnable afterReadyWrite = () -> { };
        private boolean failReadyWrite;
        private volatile CountDownLatch readyReadBarrier;

        void put(OaSessionRecord value) { record = value; }
        OaSessionRecord record() { return record; }
        void blockReadyReaders(int readers) { readyReadBarrier = new CountDownLatch(readers); }

        @Override public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            OaSessionRecord snapshot = record;
            CountDownLatch barrier = readyReadBarrier;
            if (snapshot != null && snapshot.phase() == OaSessionPhase.READY && barrier != null) {
                barrier.countDown();
                try {
                    if (!barrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("ready read barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ready read barrier interrupted", interrupted);
                }
            }
            return snapshot != null && snapshot.authSessionId().equals(authSessionId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
            return record != null && record.desktopInstanceId().equals(instanceId)
                    && record.desktopSessionId().equals(sessionId) ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findLatestDetachedByDesktopInstanceId(String instanceId) {
            return record != null && record.desktopInstanceId().equals(instanceId)
                    && record.phase() == OaSessionPhase.DETACHED ? Optional.of(record) : Optional.empty();
        }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(
                String authSessionId, long expectedGeneration, OaSessionRecord value) {
            if (record == null || !record.authSessionId().equals(authSessionId)
                    || record.generation() != expectedGeneration) return false;
            record = value;
            return true;
        }
        @Override public synchronized boolean compareAndSwapExact(
                OaSessionRecord expected, OaSessionRecord next) {
            if (failReadyWrite && next.phase() == OaSessionPhase.READY) {
                failReadyWrite = false;
                return false;
            }
            if (!expected.equals(record)) return false;
            record = next;
            if (next.phase() == OaSessionPhase.READY) {
                readyWrites++;
                afterReadyWrite.run();
            }
            return true;
        }
        @Override public boolean compareAndSwapInstallation(
                String authSessionId,
                long expectedGeneration,
                String expectedInstallationId,
                String expectedOwnerDesktopInstanceId,
                String expectedOwnerDesktopSessionId,
                long expectedTargetGeneration,
                String expectedActiveCredentialRef,
                String expectedStagedCredentialRef,
                OaSessionRecord value) {
            if (failReadyWrite) {
                failReadyWrite = false;
                return false;
            }
            boolean updated = OaSessionRepository.super.compareAndSwapInstallation(
                    authSessionId, expectedGeneration, expectedInstallationId,
                    expectedOwnerDesktopInstanceId, expectedOwnerDesktopSessionId,
                    expectedTargetGeneration, expectedActiveCredentialRef,
                    expectedStagedCredentialRef, value);
            if (updated && value.phase() == OaSessionPhase.READY) {
                readyWrites++;
                afterReadyWrite.run();
            }
            return updated;
        }
        @Override public List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : List.of(record);
        }
    }

    private static final class DeadlineAdvancingContextRegistry extends ApplicationPageContextRegistry {
        private final MutableClock clock;
        private final Duration advance;

        private DeadlineAdvancingContextRegistry(ApplicationIdentityRegistry identities,
                                                  ApplicationCatalogRegistry catalogs,
                                                  MutableClock clock,
                                                  Duration advance) {
            super(identities, catalogs);
            this.clock = clock;
            this.advance = advance;
        }

        @Override
        public synchronized PageContextSnapshot installServer(
                TrustedDesktopConnection connection,
                ApplicationInstallationLease installationLease,
                long catalogEpoch,
                long contextSequence,
                JsonNode payload) {
            PageContextSnapshot installed = super.installServer(
                    connection, installationLease, catalogEpoch, contextSequence, payload);
            clock.advance(advance);
            return installed;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
