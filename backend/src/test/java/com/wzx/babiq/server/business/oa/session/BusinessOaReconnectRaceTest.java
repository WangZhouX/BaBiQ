package com.wzx.babiq.server.business.oa.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.application.ApplicationBridgeLifecycleCoordinator;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class BusinessOaReconnectRaceTest {

    @Test
    void every_public_authentication_service_constructor_requires_the_connection_registry() {
        assertThat(BusinessOaAuthenticationService.class.getConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .as("public constructor %s", constructor)
                        .contains(BusinessDesktopConnectionRegistry.class));
    }

    @Test
    void same_handle_retry_during_restoring_returns_immediately_without_second_refresh() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        repository.put(detached(DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray())));
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry handles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, handles,
                finalized.registry());
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> first = executor.submit(() -> authentication.attach(connection, handle));

        try {
            assertThat(gateway.refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            OaSessionRecord beforeRetry = repository.record();
            assertThat(beforeRetry.phase()).isEqualTo(OaSessionPhase.RESTORING);

            Future<BusinessAuthDtos.Session> retry = executor.submit(
                    () -> authentication.attach(connection, handle));
            BusinessAuthDtos.Session inFlight = retry.get(1, TimeUnit.SECONDS);

            assertThat(inFlight.state()).isEqualTo(OaSessionPhase.RESTORING.name());
            assertThat(inFlight.generation()).isEqualTo(beforeRetry.generation());
            assertThat(inFlight.attachHandle()).isNull();
            assertThat(inFlight.canAttach()).isFalse();
            assertThat(inFlight.canRestore()).isFalse();
            assertThat(inFlight.identityEpoch()).isZero();
            assertThat(inFlight.roles()).isEmpty();
            assertThat(inFlight.permissions()).isEmpty();
            assertThat(repository.record()).isEqualTo(beforeRetry);
            assertThat(gateway.refreshCalls).hasValue(1);
            assertThat(first).isNotDone();

            gateway.releaseRefresh.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo(OaSessionPhase.READY.name());
            assertThat(authentication.attach(connection, handle).state())
                    .isEqualTo(OaSessionPhase.READY.name());
            assertThat(gateway.refreshCalls).hasValue(1);
        } finally {
            gateway.releaseRefresh.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void same_handle_retry_during_installing_returns_immediately_without_touching_the_installation() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        repository.put(detached(DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray())));
        repository.blockActivation = true;
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        gateway.releaseRefresh.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry handles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, handles,
                finalized.registry());
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> first = executor.submit(() -> authentication.attach(connection, handle));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            OaSessionRecord beforeRetry = repository.record();
            assertThat(beforeRetry.phase()).isEqualTo(OaSessionPhase.INSTALLING);
            ApplicationInstallationLease installation =
                    identities.installationLease(connection).orElseThrow();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(identities.provisional(connection, installation)).isPresent();
            assertThat(catalogs.provisional(connection, installation)).isPresent();
            assertThat(contexts.provisional(connection, installation)).isPresent();

            Future<BusinessAuthDtos.Session> retry = executor.submit(
                    () -> authentication.attach(connection, handle));
            BusinessAuthDtos.Session inFlight = retry.get(1, TimeUnit.SECONDS);

            assertThat(inFlight.state()).isEqualTo(OaSessionPhase.INSTALLING.name());
            assertThat(inFlight.generation()).isEqualTo(beforeRetry.generation());
            assertThat(inFlight.identityEpoch()).isZero();
            assertThat(inFlight.roles()).isEmpty();
            assertThat(inFlight.permissions()).isEmpty();
            assertThat(repository.record()).isEqualTo(beforeRetry);
            assertThat(repository.record().installationId()).isEqualTo(beforeRetry.installationId());
            assertThat(repository.record().stagedCredentialRef()).isEqualTo(beforeRetry.stagedCredentialRef());
            assertThat(gateway.refreshCalls).hasValue(1);
            assertThat(first).isNotDone();

            repository.releaseActivation.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).state()).isEqualTo(OaSessionPhase.READY.name());
            assertThat(authentication.attach(connection, handle).state())
                    .isEqualTo(OaSessionPhase.READY.name());
            assertThat(gateway.refreshCalls).hasValue(1);
        } finally {
            repository.releaseActivation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_during_blocked_refresh_prevents_late_ready_publication() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached(activeRef));
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, attachHandles,
                finalized.registry());
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), finalized.registry(),
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lateAttach = executor.submit(() -> authentication.attach(connection, handle));

        try {
            assertThat(gateway.refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.RESTORING);

            lifecycle.onConnectionClosed(connection, "closed");

            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(repository.record().generation()).isEqualTo(7);
            gateway.releaseRefresh.countDown();
            assertThatThrownBy(() -> lateAttach.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(repository.record().generation()).isEqualTo(7);
            assertThat(repository.record().stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isEqualTo(1);
            assertThat(credentials.load(activeRef)).isNotNull();
            assertThat(identities.current(connection)).isEmpty();
        } finally {
            gateway.releaseRefresh.countDown();
            try {
                lateAttach.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The asserted stale result is expected.
            }
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_during_blocked_activation_clears_provisional_installation() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached(activeRef));
        repository.blockActivation = true;
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        gateway.releaseRefresh.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, attachHandles,
                finalized.registry());
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), finalized.registry(),
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lateAttach = executor.submit(() -> authentication.attach(connection, handle));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.INSTALLING);
            assertThat(repository.record().stagedCredentialRef()).isNotNull();
            ApplicationInstallationLease installation =
                    identities.installationLease(connection).orElseThrow();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(identities.provisional(connection, installation)).isPresent();
            assertThat(catalogs.provisional(connection, installation)).isPresent();
            assertThat(contexts.provisional(connection, installation)).isPresent();

            lifecycle.onConnectionClosed(connection, "closed");

            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(repository.record().generation()).isEqualTo(7);
            assertThat(repository.record().stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isEqualTo(1);
            assertThat(identities.current(connection)).isEmpty();
            repository.releaseActivation.countDown();
            assertThatThrownBy(() -> lateAttach.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(repository.record().generation()).isEqualTo(7);
            assertThat(identities.current(connection)).isEmpty();
        } finally {
            repository.releaseActivation.countDown();
            try {
                lateAttach.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The asserted stale result is expected.
            }
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_retries_after_activation_wins_the_old_generation_cas() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        repository.put(installing(activeRef, stagedRef));
        repository.blockActivation = true;
        repository.blockDetachTransition = true;
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<OaSessionRecord> activation = executor.submit(() -> persistence.activate(
                "auth-1", 6, "installation-1", connection,
                "user-1", "tenant-1", "2"));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> close = executor.submit(() -> lifecycle.onConnectionClosed(connection, "closed"));
            assertThat(repository.detachTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            repository.releaseActivation.countDown();
            activation.get(5, TimeUnit.SECONDS);
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.READY);
            assertThat(repository.record().generation()).isEqualTo(7);

            repository.releaseDetachTransition.countDown();
            close.get(5, TimeUnit.SECONDS);

            OaSessionRecord detached = repository.record();
            assertThat(detached.phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(detached.generation()).isEqualTo(8);
            assertThat(detached.stagedCredentialRef()).isNull();
            assertThat(detached.installationId()).isNull();
            assertThat(detached.installationExpiresAt()).isNull();
            assertThat(secrets.size()).isEqualTo(1);
            assertThat(credentials.load(detached.activeCredentialRef())).isNotNull();
            assertThat(identities.current(connection)).isEmpty();
        } finally {
            repository.releaseActivation.countDown();
            repository.releaseDetachTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void login_origin_close_stays_signed_out_when_activation_wins_the_first_cas() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        OaSessionRecord installing = installing(null, stagedRef);
        repository.put(installing);
        repository.blockActivation = true;
        repository.blockSignedOutExpectedGeneration = 6L;
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease installationLease = new ApplicationInstallationLease(
                installing.installationId(), connection, installing.installationTargetGeneration(),
                installing.installationExpiresAt());
        identities.installServer(connection, installationLease, "auth-1", 1,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("framework:read"));
        com.fasterxml.jackson.databind.node.ObjectNode catalogPayload =
                new ObjectMapper().createObjectNode();
        catalogPayload.putObject("actions");
        catalogs.installServer(connection, installationLease, 1, catalogPayload);
        contexts.installServer(connection, installationLease, 1, 1,
                new ObjectMapper().createObjectNode());
        assertThat(identities.current(connection)).isEmpty();
        assertThat(catalogs.current(connection)).isEmpty();
        assertThat(contexts.current(connection)).isEmpty();
        assertThat(identities.provisional(connection, installationLease)).isPresent();
        assertThat(catalogs.provisional(connection, installationLease)).isPresent();
        assertThat(contexts.provisional(connection, installationLease)).isPresent();
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<OaSessionRecord> activation = executor.submit(() -> persistence.activate(
                "auth-1", 6, "installation-1", connection,
                "user-1", "tenant-1", "2"));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> close = executor.submit(() -> lifecycle.onConnectionClosed(connection, "closed"));
            assertThat(repository.signedOutTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            repository.releaseActivation.countDown();
            activation.get(5, TimeUnit.SECONDS);
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.READY);
            assertThat(repository.record().generation()).isEqualTo(7);

            repository.releaseSignedOutTransition.countDown();
            close.get(5, TimeUnit.SECONDS);

            OaSessionRecord closed = repository.record();
            assertThat(closed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(closed.generation()).isEqualTo(8);
            assertThat(closed.activeCredentialRef()).isNull();
            assertThat(closed.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
        } finally {
            repository.releaseActivation.countDown();
            repository.releaseSignedOutTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void revocation_retries_after_activation_wins_the_old_generation_cas() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        repository.put(installing(activeRef, stagedRef));
        repository.blockActivation = true;
        repository.blockRevokeTransition = true;
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<OaSessionRecord> activation = executor.submit(() -> persistence.activate(
                "auth-1", 6, "installation-1", connection,
                "user-1", "tenant-1", "2"));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<OaSessionRecord> revocation = executor.submit(() -> {
                OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                        connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT);
                return persistence.revoke(revoking.authSessionId(), revoking.generation());
            });
            assertThat(repository.revokeTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            repository.releaseActivation.countDown();
            activation.get(5, TimeUnit.SECONDS);
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.READY);
            assertThat(repository.record().generation()).isEqualTo(7);

            repository.releaseRevokeTransition.countDown();
            OaSessionRecord signedOut = revocation.get(5, TimeUnit.SECONDS);

            assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(signedOut.generation()).isEqualTo(9);
            assertThat(signedOut.activeCredentialRef()).isNull();
            assertThat(signedOut.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
        } finally {
            repository.releaseActivation.countDown();
            repository.releaseRevokeTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void revocation_follows_refresh_activation_of_the_same_ready_target() {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord originalReady = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", activeRef, Instant.now());
        repository.put(originalReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        AtomicReference<OaSessionRecord> refreshedReady = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> {
            OaSessionRecord staged = persistence.stage(
                    originalReady.authSessionId(), originalReady.generation(), connection,
                    "new-access".toCharArray(), "new-refresh".toCharArray());
            refreshedReady.set(persistence.activate(
                    staged.authSessionId(), staged.generation(), staged.installationId(), connection,
                    originalReady.userId(), originalReady.tenantId(), originalReady.platformId()));
        });

        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT, originalReady);
        OaSessionRecord signedOut = persistence.revoke(
                revoking.authSessionId(), revoking.generation());

        assertThat(refreshedReady.get()).isNotNull();
        assertThat(refreshedReady.get().phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(refreshedReady.get().generation()).isEqualTo(originalReady.generation() + 1);
        assertThat(revoking.phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(revoking.activeCredentialRef()).isEqualTo(refreshedReady.get().activeCredentialRef());
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(secrets.size()).isZero();
    }

    @Test
    void revocation_follows_detach_of_the_same_ready_target() {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord originalReady = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", activeRef, Instant.now());
        repository.put(originalReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        AtomicReference<OaSessionRecord> detached = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> detached.set(
                persistence.detachBeforeCleanup(connection)));

        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT, originalReady);
        OaSessionRecord signedOut = persistence.revoke(
                revoking.authSessionId(), revoking.generation());

        assertThat(detached.get()).isNotNull();
        assertThat(detached.get().phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detached.get().activeCredentialRef()).isEqualTo(activeRef);
        assertThat(revoking.phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(revoking.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(secrets.size()).isZero();
    }

    @Test
    void revocation_follows_abandoned_refresh_detach_of_the_same_ready_target() {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord originalReady = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", activeRef, Instant.now());
        repository.put(originalReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        AtomicReference<OaSessionRecord> detached = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> {
            persistence.stage(
                    originalReady.authSessionId(), originalReady.generation(), connection,
                    "new-access".toCharArray(), "new-refresh".toCharArray());
            detached.set(persistence.detachBeforeCleanup(connection));
        });

        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT, originalReady);
        OaSessionRecord signedOut = persistence.revoke(
                revoking.authSessionId(), revoking.generation());

        assertThat(detached.get()).isNotNull();
        assertThat(detached.get().phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detached.get().generation()).isEqualTo(originalReady.generation() + 1);
        assertThat(detached.get().credentialVersion()).isEqualTo(originalReady.credentialVersion() + 1);
        assertThat(detached.get().activeCredentialRef()).isEqualTo(activeRef);
        assertThat(detached.get().stagedCredentialRef()).isNull();
        assertThat(revoking.phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(revoking.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(secrets.size()).isZero();
    }

    @Test
    void revocation_follows_detach_after_refresh_activation_of_the_same_ready_target() {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord originalReady = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", activeRef, Instant.now());
        repository.put(originalReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        AtomicReference<OaSessionRecord> detached = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> {
            OaSessionRecord staged = persistence.stage(
                    originalReady.authSessionId(), originalReady.generation(), connection,
                    "new-access".toCharArray(), "new-refresh".toCharArray());
            persistence.activate(
                    staged.authSessionId(), staged.generation(), staged.installationId(), connection,
                    originalReady.userId(), originalReady.tenantId(), originalReady.platformId());
            detached.set(persistence.detachBeforeCleanup(connection));
        });

        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT, originalReady);
        OaSessionRecord signedOut = persistence.revoke(
                revoking.authSessionId(), revoking.generation());

        assertThat(detached.get()).isNotNull();
        assertThat(detached.get().phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detached.get().generation()).isEqualTo(originalReady.generation() + 2);
        assertThat(detached.get().credentialVersion()).isEqualTo(originalReady.credentialVersion() + 1);
        assertThat(detached.get().activeCredentialRef()).isNotEqualTo(activeRef);
        assertThat(revoking.phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(revoking.activeCredentialRef()).isEqualTo(detached.get().activeCredentialRef());
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(secrets.size()).isZero();
    }

    @Test
    void stale_signed_out_logout_losing_to_new_login_preserves_new_installation_and_account() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, finalized.registry());
        String firstCandidate = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        assertThat(authentication.login(
                connection, "account-1", firstCandidate, "password".toCharArray()).state())
                .isEqualTo(OaSessionPhase.READY.name());
        assertThat(authentication.logout(connection).state())
                .isEqualTo(OaSessionPhase.SIGNED_OUT.name());
        repository.blockNextSignedOutLookup();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BusinessAuthDtos.Session> staleLogout = executor.submit(
                () -> authentication.logout(connection));

        try {
            assertThat(repository.signedOutLookupCaptured.await(5, TimeUnit.SECONDS)).isTrue();
            String nextCandidate = authentication.findTenantCandidates(connection, "account-1")
                    .candidates().getFirst().candidateId();
            BusinessAuthDtos.Session newlyReady = authentication.login(
                    connection, "account-1", nextCandidate, "password".toCharArray());
            ApplicationInstallationLease newInstallation =
                    identities.installationLease(connection).orElseThrow();
            var newIdentity = identities.current(connection).orElseThrow();

            repository.releaseSignedOutLookup.countDown();
            BusinessAuthDtos.Session staleResult = staleLogout.get(5, TimeUnit.SECONDS);
            BusinessAuthDtos.Session current = authentication.session(connection);

            assertThat(staleResult.state()).isEqualTo(OaSessionPhase.READY.name());
            assertThat(current.state()).isEqualTo(OaSessionPhase.READY.name());
            assertThat(current.authSessionId()).isEqualTo(newlyReady.authSessionId());
            assertThat(current.generation()).isEqualTo(newlyReady.generation());
            assertThat(current.identityEpoch()).isEqualTo(newIdentity.identityEpoch()).isPositive();
            assertThat(current.rememberedAccount()).isEqualTo("account-1");
            assertThat(identities.current(connection)).contains(newIdentity);
            assertThat(identities.installationLease(connection)).contains(newInstallation);
            assertThat(catalogs.current(connection).orElseThrow().installationLease())
                    .isEqualTo(newInstallation);
            assertThat(contexts.current(connection).orElseThrow().installationLease())
                    .isEqualTo(newInstallation);
        } finally {
            repository.releaseSignedOutLookup.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logout_observing_new_ready_at_durable_read_clears_that_same_installation() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, finalized.registry());
        String firstCandidate = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        assertThat(authentication.login(
                connection, "account-1", firstCandidate, "password".toCharArray()).state())
                .isEqualTo(OaSessionPhase.READY.name());
        assertThat(authentication.logout(connection).state())
                .isEqualTo(OaSessionPhase.SIGNED_OUT.name());

        repository.blockNextSignedOutLookupBeforeRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BusinessAuthDtos.Session> staleLogout = executor.submit(
                () -> authentication.logout(connection));

        try {
            assertThat(repository.signedOutLookupEnteredBeforeRead.await(5, TimeUnit.SECONDS)).isTrue();
            String nextCandidate = authentication.findTenantCandidates(connection, "account-1")
                    .candidates().getFirst().candidateId();
            authentication.login(
                    connection, "account-1", nextCandidate, "password".toCharArray());
            assertThat(identities.installationLease(connection)).isPresent();
            assertThat(identities.current(connection)).isPresent();

            repository.releaseSignedOutLookupBeforeRead.countDown();
            BusinessAuthDtos.Session staleResult = staleLogout.get(5, TimeUnit.SECONDS);
            BusinessAuthDtos.Session current = authentication.session(connection);

            assertThat(staleResult.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
            assertThat(current.state()).isEqualTo(OaSessionPhase.SIGNED_OUT.name());
            assertThat(current.rememberedAccount()).isNull();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(identities.installationLease(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
        } finally {
            repository.releaseSignedOutLookupBeforeRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logout_removes_same_installation_account_remembered_after_capture_before_revoke_cas()
            throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = spy(fixture.sessions());
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, finalized.registry());
        ReadyRememberRaceBarrier barrier = new ReadyRememberRaceBarrier();
        doAnswer(barrier::interceptReadyPublication)
                .when(sessions).captureReady(any(OaSessionRecord.class), any(TrustedDesktopConnection.class));
        doAnswer(barrier::interceptRevocationEntry)
                .when(sessions).revokeBeforeCleanup(
                        any(TrustedDesktopConnection.class),
                        any(BusinessOaSessionRegistry.RevocationReason.class),
                        any(OaSessionRecord.class));
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> login = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));
        Future<BusinessAuthDtos.Session> logout = null;

        try {
            assertThat(barrier.readyPublished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.READY);
            logout = executor.submit(() -> authentication.logout(connection));
            assertThat(barrier.revocationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            barrier.allowRemember.countDown();
            assertThat(login.get(5, TimeUnit.SECONDS).rememberedAccount()).isEqualTo("account-1");
            barrier.allowRevocation.countDown();

            assertThat(logout.get(5, TimeUnit.SECONDS).state())
                    .isEqualTo(OaSessionPhase.SIGNED_OUT.name());
            assertThat(authentication.session(connection).rememberedAccount()).isNull();
        } finally {
            barrier.allowRemember.countDown();
            barrier.allowRevocation.countDown();
            if (logout != null) {
                try {
                    logout.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The assertions above report any unexpected logout outcome.
                }
            }
            executor.shutdownNow();
        }
    }

    @Test
    void terminalization_between_remember_validation_and_compute_cannot_reinsert_account_after_signed_out()
            throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        RememberValidationIdentityRegistry identities = new RememberValidationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, finalized.registry());
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> login = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));
        Future<Void> terminal = null;

        try {
            assertThat(identities.validationCaptured.await(5, TimeUnit.SECONDS)).isTrue();
            ReadyOaSessionLease ready = sessions.captureReady(connection);
            terminal = executor.submit(() -> {
                authentication.terminate(
                        ready, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);
                return null;
            });
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.SIGNED_OUT));

            identities.allowRememberCompute.countDown();
            login.get(5, TimeUnit.SECONDS);
            terminal.get(5, TimeUnit.SECONDS);

            Future<BusinessAuthDtos.Session> firstRead = executor.submit(() ->
                    authentication.session(connection));
            Future<BusinessAuthDtos.Session> secondRead = executor.submit(() ->
                    authentication.session(connection));
            assertThat(firstRead.get(5, TimeUnit.SECONDS).rememberedAccount()).isNull();
            assertThat(secondRead.get(5, TimeUnit.SECONDS).rememberedAccount()).isNull();
        } finally {
            identities.allowRememberCompute.countDown();
            if (terminal != null) {
                try {
                    terminal.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The assertions above report any unexpected terminalization outcome.
                }
            }
            executor.shutdownNow();
        }
    }

    @Test
    void stale_ready_logout_does_not_chase_a_later_login_after_losing_its_first_cas() {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, finalized.registry());
        String firstCandidate = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        assertThat(authentication.login(
                connection, "account-1", firstCandidate, "password".toCharArray()).state())
                .isEqualTo(OaSessionPhase.READY.name());

        AtomicReference<BusinessAuthDtos.Session> laterLogin = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> {
            assertThat(authentication.logout(connection).state())
                    .isEqualTo(OaSessionPhase.SIGNED_OUT.name());
            String nextCandidate = authentication.findTenantCandidates(connection, "account-1")
                    .candidates().getFirst().candidateId();
            laterLogin.set(authentication.login(
                    connection, "account-1", nextCandidate, "password".toCharArray()));
        });

        BusinessAuthDtos.Session staleResult = authentication.logout(connection);
        BusinessAuthDtos.Session current = authentication.session(connection);
        ApplicationInstallationLease newInstallation =
                identities.installationLease(connection).orElseThrow();
        var newIdentity = identities.current(connection).orElseThrow();

        assertThat(laterLogin.get()).isNotNull();
        assertThat(staleResult.state()).isEqualTo(OaSessionPhase.READY.name());
        assertThat(current.state()).isEqualTo(OaSessionPhase.READY.name());
        assertThat(current.authSessionId()).isEqualTo(laterLogin.get().authSessionId());
        assertThat(current.generation()).isEqualTo(laterLogin.get().generation());
        assertThat(current.rememberedAccount()).isEqualTo("account-1");
        assertThat(identities.current(connection)).contains(newIdentity);
        assertThat(identities.installationLease(connection)).contains(newInstallation);
        assertThat(catalogs.current(connection).orElseThrow().installationLease())
                .isEqualTo(newInstallation);
        assertThat(contexts.current(connection).orElseThrow().installationLease())
                .isEqualTo(newInstallation);
    }

    @Test
    void refresh_stage_winning_stale_revoke_keeps_staged_credential_reachable() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord ready = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", activeRef, Instant.now());
        repository.put(ready);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        AtomicReference<OaSessionRecord> installing = new AtomicReference<>();
        repository.beforeRevokeCas.set(() -> installing.set(persistence.stage(
                    ready.authSessionId(), ready.generation(), connection,
                    "new-access".toCharArray(), "new-refresh".toCharArray())));

        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT);

        assertThat(installing.get()).isNotNull();
        assertThat(installing.get().phase()).isEqualTo(OaSessionPhase.INSTALLING);
        assertThat(installing.get().stagedCredentialRef()).isNotNull();
        assertThat(revoking.phase()).isEqualTo(OaSessionPhase.REVOKING);
        assertThat(revoking.activeCredentialRef()).isEqualTo(activeRef);
        assertThat(revoking.stagedCredentialRef()).isEqualTo(installing.get().stagedCredentialRef());

        OaSessionRecord signedOut = persistence.revoke(
                revoking.authSessionId(), revoking.generation());
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(secrets.size()).isZero();
    }

    @Test
    void startup_recovery_losing_to_activation_keeps_promoted_active_credential_readable() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        OaSessionRecord installing = installing(activeRef, stagedRef);
        repository.put(installing);
        repository.blockRecoveryTransition = true;
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRecoveryService recovery = new BusinessOaSessionRecoveryService(repository, persistence);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessOaSessionRecoveryService.RecoveryReport> recoveryFuture =
                executor.submit(recovery::recover);

        try {
            assertThat(repository.recoveryTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            OaSessionRecord ready = persistence.activate(
                    "auth-1", 6, "installation-1", connection,
                    "user-1", "tenant-1", "2");
            assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
            assertThat(ready.activeCredentialRef()).isEqualTo(stagedRef);

            repository.releaseRecoveryTransition.countDown();
            assertThat(recoveryFuture.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(1, 0, 1));
            assertThat(repository.record()).isEqualTo(ready);
            try (OaSessionCredentialStore.CredentialMaterial material =
                         credentials.load(ready.activeCredentialRef())) {
                assertThat(material).isNotNull();
                assertThat(material.accessToken()).isEqualTo("new-access".toCharArray());
            }
        } finally {
            repository.releaseRecoveryTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_during_remote_login_prevents_late_ready_publication() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(mode);
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));

        try {
            assertThat(gateway.loginStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(connections.release(reservation, "ws-1")).isTrue();
            OaSessionRecord closed = repository.record();
            assertThat(closed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(closed.generation()).isEqualTo(2);

            gateway.releaseLogin.countDown();
            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            assertThat(gateway.permissionCalls).hasValue(0);
            assertThat(secrets.size()).isZero();
            assertThat(repository.record()).isEqualTo(closed);
        } finally {
            gateway.releaseLogin.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_during_permission_load_prevents_staging_credentials() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        gateway.blockPermissions = true;
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(mode);
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));

        try {
            assertThat(gateway.permissionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(connections.release(reservation, "ws-1")).isTrue();
            OaSessionRecord closed = repository.record();
            assertThat(closed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(closed.generation()).isEqualTo(2);

            gateway.releasePermission.countDown();
            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            assertThat(gateway.permissionCalls).hasValue(1);
            assertThat(secrets.size()).isZero();
            assertThat(repository.record()).isEqualTo(closed);
        } finally {
            gateway.releasePermission.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_during_login_activation_returns_to_signed_out_without_remote_logout() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.blockActivation = true;
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(mode);
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            OaSessionRecord installing = repository.record();
            assertThat(installing.phase()).isEqualTo(OaSessionPhase.INSTALLING);
            assertThat(installing.activeCredentialRef()).isNull();
            assertThat(installing.stagedCredentialRef()).isNotNull();
            ApplicationInstallationLease installation =
                    identities.installationLease(connection).orElseThrow();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(identities.provisional(connection, installation)).isPresent();
            assertThat(catalogs.provisional(connection, installation)).isPresent();
            assertThat(contexts.provisional(connection, installation)).isPresent();

            assertThat(connections.release(reservation, "ws-1")).isTrue();
            OaSessionRecord closed = repository.record();
            assertThat(closed.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(closed.generation()).isEqualTo(2);
            assertThat(closed.activeCredentialRef()).isNull();
            assertThat(closed.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);

            repository.releaseActivation.countDown();
            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(repository.record()).isEqualTo(closed);
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
        } finally {
            repository.releaseActivation.countDown();
            try {
                lateLogin.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The asserted stale result is expected.
            }
            executor.shutdownNow();
        }
    }

    @Test
    void closed_connection_never_returns_ready_when_login_activation_wins_the_first_close_cas() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.blockActivation = true;
        repository.blockSignedOutExpectedGeneration = 1L;
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = spy(fixture.sessions());
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(mode);
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> close = executor.submit(() -> connections.release(reservation, "ws-1"));
            assertThat(repository.signedOutTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(connections.isFinalized(connection)).isFalse();

            repository.releaseActivation.countDown();
            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");

            OaSessionRecord compensated = repository.record();
            assertThat(compensated.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(compensated.activeCredentialRef()).isNull();
            assertThat(compensated.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
            verify(sessions, never()).captureReady(
                    any(OaSessionRecord.class), any(TrustedDesktopConnection.class));

            repository.releaseSignedOutTransition.countDown();
            assertThat(close.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record()).isEqualTo(compensated);
        } finally {
            repository.releaseActivation.countDown();
            repository.releaseSignedOutTransition.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void release_between_login_validation_and_capture_never_publishes_ready() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.blockActivation = true;
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = spy(fixture.sessions());
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = spy(new BusinessDesktopConnectionRegistry(mode));
        FinalizedPublicationBarrier barrier = new FinalizedPublicationBarrier();
        doAnswer(barrier::interceptPublicationEntry)
                .when(connections).withFinalized(any(TrustedDesktopConnection.class), any());
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(barrier::blockCleanupAfterRelease);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));
        Future<Boolean> close = null;

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            barrier.arm();
            repository.releaseActivation.countDown();
            assertThat(barrier.publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            close = executor.submit(() -> connections.release(reservation, "ws-1"));
            assertThat(barrier.releaseLinearized.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(connections.isFinalized(connection)).isFalse();

            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            OaSessionRecord compensated = repository.record();
            assertThat(compensated.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(compensated.activeCredentialRef()).isNull();
            assertThat(compensated.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
            verify(sessions, never()).captureReady(
                    any(OaSessionRecord.class), any(TrustedDesktopConnection.class));

            barrier.allowCleanup.countDown();
            assertThat(close.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record()).isEqualTo(compensated);
        } finally {
            repository.releaseActivation.countDown();
            barrier.allowCleanup.countDown();
            if (close != null) {
                try {
                    close.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The assertions above report any unexpected close outcome.
                }
            }
            executor.shutdownNow();
        }
    }

    @Test
    void login_activation_then_close_then_publish_failure_aborts_to_signed_out() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.blockActivation = true;
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        BlockingLoginGateway gateway = new BlockingLoginGateway();
        gateway.releaseLogin.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = spy(new BusinessDesktopConnectionRegistry(mode));
        PublicationAfterCleanupBarrier barrier = new PublicationAfterCleanupBarrier();
        doAnswer(barrier::interceptPublicationEntry)
                .when(connections).withFinalized(any(TrustedDesktopConnection.class), any());
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        connections.addCloseListener(barrier::afterCleanup);
        String candidateId = authentication.findTenantCandidates(connection, "account-1")
                .candidates().getFirst().candidateId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BusinessAuthDtos.Session> lateLogin = executor.submit(() -> authentication.login(
                connection, "account-1", candidateId, "password".toCharArray()));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            repository.releaseActivation.countDown();
            assertThat(barrier.publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(connections.release(reservation, "ws-1")).isTrue();
            assertThatThrownBy(() -> lateLogin.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");

            OaSessionRecord signedOut = repository.record();
            assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(signedOut.activeCredentialRef()).isNull();
            assertThat(signedOut.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isZero();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
        } finally {
            repository.releaseActivation.countDown();
            barrier.cleanupCompleted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void release_between_attach_validation_and_capture_never_publishes_ready() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        repository.blockActivation = true;
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String oldActiveRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached(oldActiveRef));
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        gateway.releaseRefresh.countDown();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = spy(fixture.sessions());
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = spy(new BusinessDesktopConnectionRegistry(mode));
        FinalizedPublicationBarrier barrier = new FinalizedPublicationBarrier();
        doAnswer(barrier::interceptPublicationEntry)
                .when(connections).withFinalized(any(TrustedDesktopConnection.class), any());
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections,
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        connections.addCloseListener(barrier::blockCleanupAfterRelease);
        connections.addCloseListener(lifecycle::onConnectionClosed);
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<BusinessAuthDtos.Session> lateAttach = executor.submit(
                () -> authentication.attach(connection, handle));
        Future<Boolean> close = null;

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            barrier.arm();
            repository.releaseActivation.countDown();
            assertThat(barrier.publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();

            close = executor.submit(() -> connections.release(reservation, "ws-1"));
            assertThat(barrier.releaseLinearized.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(connections.isFinalized(connection)).isFalse();

            assertThatThrownBy(() -> lateAttach.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            OaSessionRecord detached = repository.record();
            assertThat(detached.phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(detached.activeCredentialRef()).isNotNull();
            assertThat(detached.stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isEqualTo(1);
            assertThat(credentials.load(detached.activeCredentialRef())).isNotNull();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(gateway.logoutCalls).hasValue(0);
            verify(sessions, never()).captureReady(
                    any(OaSessionRecord.class), any(TrustedDesktopConnection.class));

            barrier.allowCleanup.countDown();
            assertThat(close.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record()).isEqualTo(detached);
        } finally {
            repository.releaseActivation.countDown();
            barrier.allowCleanup.countDown();
            if (close != null) {
                try {
                    close.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // The assertions above report any unexpected close outcome.
                }
            }
            executor.shutdownNow();
        }
    }

    @Test
    void finalized_publication_holds_release_linearization_until_callback_returns() throws Exception {
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(mode);
        String reservation = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        CountDownLatch releaseLinearized = new CountDownLatch(1);
        AtomicReference<Thread> releaseThread = new AtomicReference<>();
        connections.addCloseListener((ignored, ignoredReason) -> releaseLinearized.countDown());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> publication = executor.submit(() -> connections.withFinalized(connection, () -> {
            publicationEntered.countDown();
            try {
                if (!allowPublication.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("publication latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("publication interrupted");
            }
            return "published";
        }));

        try {
            assertThat(publicationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> close = executor.submit(() -> {
                releaseThread.set(Thread.currentThread());
                return connections.release(reservation, "ws-1");
            });

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(releaseThread.get()).isNotNull();
                assertThat(releaseThread.get().getState()).isEqualTo(Thread.State.BLOCKED);
            });
            assertThat(releaseLinearized.getCount()).isEqualTo(1);

            allowPublication.countDown();
            assertThat(publication.get(5, TimeUnit.SECONDS)).isEqualTo("published");
            assertThat(close.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseLinearized.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void websocket_close_cannot_slip_between_attach_claim_and_restoring_transition() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        TrackingSecretStore secrets = new TrackingSecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached(activeRef));
        repository.blockRestoringTransition = true;
        BlockingRefreshGateway gateway = new BlockingRefreshGateway();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        BusinessOaAttachHandleRegistry attachHandles = new BusinessOaAttachHandleRegistry(repository);
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities, attachHandles,
                finalized.registry());
        ApplicationOutboundRequestTracker outbound = mock(ApplicationOutboundRequestTracker.class);
        ApplicationBridgeLifecycleCoordinator lifecycle = new ApplicationBridgeLifecycleCoordinator(
                outbound, finalized.registry(),
                identities, catalogs, contexts, mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                sessions, attachHandles);
        String handle = authentication.session(connection).attachHandle();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> lateAttach = executor.submit(() -> authentication.attach(connection, handle));
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicReference<Thread> closeThread = new AtomicReference<>();

        try {
            assertThat(repository.restoringTransitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> close = executor.submit(() -> {
                closeThread.set(Thread.currentThread());
                closeStarted.countDown();
                lifecycle.onConnectionClosed(connection, "closed");
            });
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() ->
                    close.isDone() || closeThread.get().getState() == Thread.State.BLOCKED);
            repository.releaseRestoringTransition.countDown();

            close.get(5, TimeUnit.SECONDS);
            gateway.releaseRefresh.countDown();
            assertThatThrownBy(() -> lateAttach.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("BUSINESS_SESSION_STALE");
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(repository.record().generation()).isEqualTo(7);
            assertThat(repository.record().stagedCredentialRef()).isNull();
            assertThat(secrets.size()).isEqualTo(1);
            assertThat(identities.current(connection)).isEmpty();
        } finally {
            repository.releaseRestoringTransition.countDown();
            gateway.releaseRefresh.countDown();
            executor.shutdownNow();
        }
    }

    private static FinalizedConnection finalizedConnection() {
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        org.mockito.Mockito.when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry(mode);
        String reservation = registry.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        return new FinalizedConnection(registry, connection);
    }

    private static OaSessionRecord detached(String activeRef) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", "session-1",
                "user-1", "tenant-1", "2", OaSessionPhase.DETACHED, 5,
                activeRef, null, 1, null, now, now, null, now);
    }

    private static OaSessionRecord installing(String activeRef, String stagedRef) {
        Instant now = Instant.now();
        return new OaSessionRecord("auth-1", "desktop-1", "session-1",
                "user-1", "tenant-1", "2", OaSessionPhase.INSTALLING, 6,
                activeRef, stagedRef, 2, now, now, now, null, now,
                "installation-1", "desktop-1", "session-1", 6, now.plusSeconds(90));
    }

    private record FinalizedConnection(BusinessDesktopConnectionRegistry registry,
                                       TrustedDesktopConnection connection) {
    }

    private static final class BlockingRefreshGateway implements OaAuthenticationGateway {
        private final CountDownLatch refreshStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRefresh = new CountDownLatch(1);
        private final AtomicInteger refreshCalls = new AtomicInteger();
        private final AtomicInteger logoutCalls = new AtomicInteger();

        @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
            throw new UnsupportedOperationException();
        }
        @Override public OaAuthDtos.OaCredential login(
                OaAuthDtos.OaTenantCandidate candidate, char[] password) {
            throw new UnsupportedOperationException();
        }
        @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken) {
            refreshCalls.incrementAndGet();
            refreshStarted.countDown();
            try {
                if (!releaseRefresh.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("refresh latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("refresh interrupted");
            }
            return new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L);
        }
        @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] accessToken) {
            return new OaAuthDtos.OaPermissionSnapshot(
                    List.of("framework:read"), List.of("lawyer"), "user-1", "Lawyer", List.of());
        }
        @Override public void logout(String tenantId, char[] accessToken) {
            logoutCalls.incrementAndGet();
        }
    }

    private static final class FinalizedPublicationBarrier {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch publicationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLinearized = new CountDownLatch(1);
        private final CountDownLatch allowCleanup = new CountDownLatch(1);

        void arm() {
            armed.set(true);
        }

        Object interceptPublicationEntry(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            if (armed.compareAndSet(true, false)) {
                publicationEntered.countDown();
                if (!releaseLinearized.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release latch timeout");
                }
            }
            return invocation.callRealMethod();
        }

        void blockCleanupAfterRelease(TrustedDesktopConnection ignored, String ignoredReason) {
            releaseLinearized.countDown();
            try {
                if (!allowCleanup.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("cleanup latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cleanup interrupted");
            }
        }
    }

    private static final class ReadyRememberRaceBarrier {
        private final CountDownLatch readyPublished = new CountDownLatch(1);
        private final CountDownLatch allowRemember = new CountDownLatch(1);
        private final CountDownLatch revocationEntered = new CountDownLatch(1);
        private final CountDownLatch allowRevocation = new CountDownLatch(1);

        Object interceptReadyPublication(org.mockito.invocation.InvocationOnMock invocation)
                throws Throwable {
            Object readyLease = invocation.callRealMethod();
            readyPublished.countDown();
            await(allowRemember, "remember account");
            return readyLease;
        }

        Object interceptRevocationEntry(org.mockito.invocation.InvocationOnMock invocation)
                throws Throwable {
            revocationEntered.countDown();
            await(allowRevocation, "revocation");
            return invocation.callRealMethod();
        }

        private static void await(CountDownLatch latch, String operation) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(operation + " latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(operation + " interrupted");
            }
        }
    }

    private static final class RememberValidationIdentityRegistry
            extends ApplicationIdentityRegistry {
        private final AtomicBoolean firstValidation = new AtomicBoolean(true);
        private final CountDownLatch validationCaptured = new CountDownLatch(1);
        private final CountDownLatch allowRememberCompute = new CountDownLatch(1);

        @Override
        public Optional<ApplicationInstallationLease> installationLease(
                TrustedDesktopConnection connection) {
            Optional<ApplicationInstallationLease> installation =
                    super.installationLease(connection);
            if (firstValidation.compareAndSet(true, false)) {
                validationCaptured.countDown();
                try {
                    allowRememberCompute.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("remember compute interrupted", interrupted);
                }
            }
            return installation;
        }
    }

    private static final class PublicationAfterCleanupBarrier {
        private final CountDownLatch publicationEntered = new CountDownLatch(1);
        private final CountDownLatch cleanupCompleted = new CountDownLatch(1);

        Object interceptPublicationEntry(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            publicationEntered.countDown();
            if (!cleanupCompleted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("cleanup latch timeout");
            }
            return invocation.callRealMethod();
        }

        void afterCleanup(TrustedDesktopConnection ignored, String ignoredReason) {
            cleanupCompleted.countDown();
        }
    }

    private static final class BlockingLoginGateway implements OaAuthenticationGateway {
        private final CountDownLatch loginStarted = new CountDownLatch(1);
        private final CountDownLatch releaseLogin = new CountDownLatch(1);
        private final CountDownLatch permissionStarted = new CountDownLatch(1);
        private final CountDownLatch releasePermission = new CountDownLatch(1);
        private final AtomicInteger permissionCalls = new AtomicInteger();
        private final AtomicInteger logoutCalls = new AtomicInteger();
        private volatile boolean blockPermissions;

        @Override
        public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
            return List.of(new OaAuthDtos.OaTenantCandidate(
                    "user-1", "tenant-1", 2, "Tenant", 1, "entry-1", account));
        }

        @Override
        public OaAuthDtos.OaCredential login(
                OaAuthDtos.OaTenantCandidate candidate,
                char[] password) {
            loginStarted.countDown();
            try {
                if (!releaseLogin.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("login latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("login interrupted");
            }
            return new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L);
        }

        @Override
        public OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] accessToken) {
            permissionCalls.incrementAndGet();
            if (blockPermissions) {
                permissionStarted.countDown();
                try {
                    if (!releasePermission.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("permission latch timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("permission interrupted");
                }
            }
            return new OaAuthDtos.OaPermissionSnapshot(
                    List.of("framework:read"), List.of("lawyer"),
                    "user-1", "Lawyer", List.of());
        }

        @Override
        public void logout(String tenantId, char[] accessToken) {
            logoutCalls.incrementAndGet();
        }
    }

    private static final class MemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;
        private volatile boolean blockActivation;
        private volatile boolean blockRestoringTransition;
        private volatile boolean blockDetachTransition;
        private volatile Long blockSignedOutExpectedGeneration;
        private volatile boolean blockRevokeTransition;
        private volatile boolean blockRecoveryTransition;
        private final CountDownLatch activationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseActivation = new CountDownLatch(1);
        private final CountDownLatch restoringTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRestoringTransition = new CountDownLatch(1);
        private final CountDownLatch detachTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDetachTransition = new CountDownLatch(1);
        private final CountDownLatch signedOutTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSignedOutTransition = new CountDownLatch(1);
        private final CountDownLatch revokeTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRevokeTransition = new CountDownLatch(1);
        private final CountDownLatch recoveryTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRecoveryTransition = new CountDownLatch(1);
        private final CountDownLatch signedOutLookupEnteredBeforeRead = new CountDownLatch(1);
        private final CountDownLatch releaseSignedOutLookupBeforeRead = new CountDownLatch(1);
        private final CountDownLatch signedOutLookupCaptured = new CountDownLatch(1);
        private final CountDownLatch releaseSignedOutLookup = new CountDownLatch(1);
        private final AtomicReference<Runnable> beforeRevokeCas = new AtomicReference<>();
        private volatile boolean blockNextSignedOutLookupBeforeRead;
        private volatile boolean blockNextSignedOutLookup;
        synchronized void put(OaSessionRecord value) { record = value; }
        synchronized OaSessionRecord record() { return record; }
        @Override public synchronized Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId)
                    ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId, String desktopSessionId) {
            boolean pauseBeforeRead;
            synchronized (this) {
                pauseBeforeRead = blockNextSignedOutLookupBeforeRead
                        && record != null
                        && record.desktopInstanceId().equals(desktopInstanceId)
                        && record.desktopSessionId().equals(desktopSessionId)
                        && record.phase() == OaSessionPhase.SIGNED_OUT;
                if (pauseBeforeRead) {
                    blockNextSignedOutLookupBeforeRead = false;
                }
            }
            if (pauseBeforeRead) {
                signedOutLookupEnteredBeforeRead.countDown();
                await(releaseSignedOutLookupBeforeRead, "signed-out lookup before read");
            }
            OaSessionRecord captured;
            boolean pause;
            synchronized (this) {
                captured = record != null && record.desktopInstanceId().equals(desktopInstanceId)
                        && record.desktopSessionId().equals(desktopSessionId)
                        ? record : null;
                pause = blockNextSignedOutLookup
                        && captured != null
                        && captured.phase() == OaSessionPhase.SIGNED_OUT;
                if (pause) {
                    blockNextSignedOutLookup = false;
                }
            }
            if (pause) {
                signedOutLookupCaptured.countDown();
                await(releaseSignedOutLookup, "signed-out lookup");
            }
            return Optional.ofNullable(captured);
        }

        void blockNextSignedOutLookup() {
            blockNextSignedOutLookup = true;
        }
        void blockNextSignedOutLookupBeforeRead() {
            blockNextSignedOutLookupBeforeRead = true;
        }
        @Override public synchronized OaSessionRecord insert(OaSessionRecord value) {
            record = value;
            return value;
        }
        @Override public synchronized OaSessionRecord update(OaSessionRecord value) {
            record = value;
            return value;
        }
        @Override public boolean compareAndSwapGeneration(
                String authSessionId, long expectedGeneration, OaSessionRecord value) {
            synchronized (this) {
                if (record == null || !record.authSessionId().equals(authSessionId)
                        || record.generation() != expectedGeneration) return false;
                record = value;
                return true;
            }
        }
        @Override public boolean compareAndSwapExact(
                OaSessionRecord expected, OaSessionRecord next) {
            runBeforeRevokeCas(next);
            if (blockRestoringTransition && next.phase() == OaSessionPhase.RESTORING) {
                restoringTransitionStarted.countDown();
                await(releaseRestoringTransition, "transition");
            }
            if (blockDetachTransition && next.phase() == OaSessionPhase.DETACHED) {
                detachTransitionStarted.countDown();
                await(releaseDetachTransition, "detach");
            }
            if (blockSignedOutExpectedGeneration != null
                    && expected.generation() == blockSignedOutExpectedGeneration
                    && next.phase() == OaSessionPhase.SIGNED_OUT) {
                signedOutTransitionStarted.countDown();
                await(releaseSignedOutTransition, "signed-out");
            }
            if (blockRevokeTransition && next.phase() == OaSessionPhase.REVOKING) {
                revokeTransitionStarted.countDown();
                await(releaseRevokeTransition, "revoke");
            }
            if (blockActivation
                    && expected.phase() == OaSessionPhase.INSTALLING
                    && next.phase() == OaSessionPhase.READY) {
                activationStarted.countDown();
                await(releaseActivation, "activation");
            }
            if (blockRecoveryTransition
                    && expected.phase() != OaSessionPhase.READY
                    && (next.phase() == OaSessionPhase.SIGNED_OUT
                    || next.phase() == OaSessionPhase.DETACHED)) {
                recoveryTransitionStarted.countDown();
                await(releaseRecoveryTransition, "recovery");
            }
            synchronized (this) {
                if (!expected.equals(record)) return false;
                record = next;
                return true;
            }
        }

        private void runBeforeRevokeCas(OaSessionRecord next) {
            if (next.phase() == OaSessionPhase.REVOKING) {
                Runnable hook = beforeRevokeCas.getAndSet(null);
                if (hook != null) hook.run();
            }
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
            if (blockActivation) {
                activationStarted.countDown();
                try {
                    if (!releaseActivation.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("activation latch timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("activation interrupted");
                }
            }
            return OaSessionRepository.super.compareAndSwapInstallation(
                    authSessionId, expectedGeneration, expectedInstallationId,
                    expectedOwnerDesktopInstanceId, expectedOwnerDesktopSessionId,
                    expectedTargetGeneration, expectedActiveCredentialRef,
                    expectedStagedCredentialRef, value);
        }
        @Override public boolean compareAndSwapRecoverySnapshot(
                String authSessionId,
                OaSessionPhase expectedPhase,
                long expectedGeneration,
                String expectedInstallationId,
                String expectedActiveCredentialRef,
                String expectedStagedCredentialRef,
                OaSessionRecord value) {
            if (blockRecoveryTransition) {
                recoveryTransitionStarted.countDown();
                try {
                    if (!releaseRecoveryTransition.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("recovery latch timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("recovery interrupted");
                }
            }
            return OaSessionRepository.super.compareAndSwapRecoverySnapshot(
                    authSessionId, expectedPhase, expectedGeneration, expectedInstallationId,
                    expectedActiveCredentialRef, expectedStagedCredentialRef, value);
        }
        @Override public synchronized List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : List.of(record);
        }

        private static void await(CountDownLatch latch, String operation) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(operation + " latch timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(operation + " interrupted");
            }
        }
    }

    private static final class TrackingSecretStore implements SecretStore {
        private final Map<String, char[]> values = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        @Override public String allocateRef(String namespace) {
            return "keystore://" + namespace + ".test-" + sequence.incrementAndGet();
        }
        @Override public void saveCharsAtRef(String ref, char[] value) {
            char[] previous = values.putIfAbsent(ref, value.clone());
            if (previous != null) {
                throw new IllegalStateException("SecretStore reference already exists");
            }
        }
        @Override public List<String> listRefs(String namespacePrefix) {
            String prefix = "keystore://" + namespacePrefix;
            return values.keySet().stream()
                    .filter(ref -> ref.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        @Override public String saveChars(String namespace, char[] value) {
            String ref = allocateRef(namespace);
            saveCharsAtRef(ref, value);
            return ref;
        }
        @Override public Optional<char[]> loadChars(String ref) {
            char[] value = values.get(ref);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public void delete(String ref) { if (ref != null) values.remove(ref); }
        @Override public String save(String namespace, String value) {
            return saveChars(namespace, value.toCharArray());
        }
        @Override public Optional<String> load(String ref) {
            return loadChars(ref).map(String::new);
        }
        int size() { return values.size(); }
    }
}
