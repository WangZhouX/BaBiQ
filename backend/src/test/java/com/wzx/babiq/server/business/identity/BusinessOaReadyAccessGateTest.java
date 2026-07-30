package com.wzx.babiq.server.business.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcDispatcher;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.application.api.BusinessJsonRpcAccessPolicy;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.api.BusinessAuthProtocolHandler;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaAttachHandleRegistry;
import com.wzx.babiq.server.business.oa.session.BusinessOaAuthenticationService;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionState;
import com.wzx.babiq.server.business.oa.session.DurableOaSessionFixture;
import com.wzx.babiq.server.business.oa.session.OaSessionPhase;
import com.wzx.babiq.server.business.oa.session.OaSessionRecord;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import com.wzx.babiq.server.business.oa.session.OaTokenRefreshCoordinator;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOaReadyAccessGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void token_refresh_advances_credential_generation_without_closing_post_bind() throws Exception {
        MutableRepository repository = new MutableRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        String credentialRef = DurableOaSessionFixture.seedCredential(
                fixture.credentials(), "auth-1", 1,
                "access-old".toCharArray(), "refresh-old".toCharArray());
        OaSessionRecord initialReady = ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                5, credentialRef, 1);
        repository.put(initialReady);

        FinalizedConnection finalized = finalizedConnection();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationInstallationLease installation = new ApplicationInstallationLease(
                "installation-1", finalized.connection(), 4,
                Instant.now().plusSeconds(90));
        identities.installServer(
                finalized.connection(), installation, "auth-1", 5,
                "user-1", "tenant-1", "2",
                Set.of("lawyer"), Set.of("framework:read"));
        identities.commitInstallation(finalized.connection(), installation);

        BusinessOaSessionRegistry sessions = fixture.sessions();
        ReadyOaSessionLease predecessor = sessions.captureReady(
                initialReady, finalized.connection());
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(
                identities, finalized.registry(), sessions);
        assertThat(policy.isAllowed("thread/list", "ws-1")).isTrue();

        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                sessions, repository, fixture.persistence(), fixture.credentials(),
                refreshGateway());
        ReadyOaSessionLease successor = coordinator.refresh(predecessor)
                .get(5, TimeUnit.SECONDS);
        OaSessionRecord durable = repository.record();
        TrustedBusinessIdentity committed = identities.find("ws-1").orElseThrow();

        assertThat(successor.generation()).isEqualTo(6);
        assertThat(committed.identityEpoch()).isEqualTo(5);
        assertThat(durable.generation()).isEqualTo(successor.generation());
        assertThat(durable.activeCredentialRef()).isEqualTo(successor.activeCredentialRef());
        assertThat(durable.credentialVersion()).isEqualTo(successor.credentialVersion());
        assertThat(successor.userId()).isEqualTo(committed.userId());
        assertThat(successor.tenantId()).isEqualTo(committed.tenantId());
        assertThat(successor.platformId()).isEqualTo(committed.platformId());
        assertThat(sessions.isCurrent(successor)).isTrue();
        assertThat(policy.isAllowed("thread/list", "ws-1")).isTrue();
    }

    @Test
    void session_get_does_not_expose_ready_before_projection_publication() throws Exception {
        MutableRepository repository = new MutableRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(
                identities, catalogs);
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), sessions);
        FinalizedConnection finalized = finalizedConnection();
        repository.insert(OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = sessions.transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord staged = fixture.persistence().stage(
                "auth-1", authenticating.generation(), finalized.connection(),
                "access-token".toCharArray(), "refresh-token".toCharArray());

        BusinessOaAuthenticationService authentication = new BusinessOaAuthenticationService(
                repository, mock(OaAuthenticationGateway.class), fixture.persistence(), sessions,
                installer, fixture.credentials(), identities,
                new BusinessOaAttachHandleRegistry(repository), finalized.registry());
        BusinessAuthProtocolHandler handler = new BusinessAuthProtocolHandler(
                authentication, new BusinessDesktopConnectionResolver(finalized.registry()));
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(
                identities, finalized.registry(), sessions);
        @SuppressWarnings("unchecked")
        ObjectProvider<BusinessJsonRpcAccessPolicy> policyProvider = mock(ObjectProvider.class);
        when(policyProvider.getIfAvailable()).thenReturn(policy);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(
                List.of(handler), JSON, policyProvider);
        WebSocketSession webSocket = webSocket(finalized.connection());

        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ReadyOaSessionLease> installation = executor.submit(() -> installer.install(
                finalized.connection(), staged, "user-1", "tenant-1", "2", permissions(),
                (ready, commitProjections) -> {
                    publicationStarted.countDown();
                    await(releasePublication, "READY publication");
                    return finalized.registry().withFinalized(finalized.connection(), () -> {
                        commitProjections.run();
                        return sessions.captureReady(ready, finalized.connection());
                    });
                }));

        try {
            assertThat(publicationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.READY);

            JsonRpcMessage duringPublication = dispatcher.dispatch(
                    sessionGetRequest(1L), webSocket);
            assertReadyIsNotExposed(duringPublication);

            releasePublication.countDown();
            ReadyOaSessionLease ready = installation.get(5, TimeUnit.SECONDS);
            JsonRpcMessage afterPublication = dispatcher.dispatch(
                    sessionGetRequest(2L), webSocket);
            assertThat(afterPublication).isInstanceOf(JsonRpcMessage.Response.class);
            BusinessAuthDtos.Session published = (BusinessAuthDtos.Session)
                    ((JsonRpcMessage.Response) afterPublication).result();
            assertThat(published.state()).isEqualTo(OaSessionPhase.READY.name());
            assertThat(published.identityEpoch()).isEqualTo(
                    identities.find("ws-1").orElseThrow().identityEpoch());
            assertThat(published.generation()).isEqualTo(ready.generation());
        } finally {
            releasePublication.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exact_ready_match_rejects_each_connection_identity_or_durable_lineage_mismatch() {
        MutableRepository repository = new MutableRepository();
        OaSessionRecord exact = ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                7, "credential-7", 3);
        repository.put(exact);
        BusinessOaSessionRegistry sessions = DurableOaSessionFixture.memory(repository).sessions();
        TrustedDesktopConnection connection = connection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        TrustedBusinessIdentity identity = identity(
                connection, "user-1", "tenant-1", "2", 7);
        sessions.captureReady(exact, connection);

        assertThat(sessions.matchesCurrentReady(connection, identity)).isTrue();
        assertThat(sessions.matchesCurrentReady(
                connection,
                identity(connection("reservation-other", "desktop-1", "session-1", "ws-1"),
                        "user-1", "tenant-1", "2", 7))).isFalse();
        assertThat(sessions.matchesCurrentReady(
                connection,
                identity(connection, "user-other", "tenant-1", "2", 7))).isFalse();
        assertThat(sessions.matchesCurrentReady(
                connection,
                identity(connection, "user-1", "tenant-other", "2", 7))).isFalse();
        assertThat(sessions.matchesCurrentReady(
                connection,
                identity(connection, "user-1", "tenant-1", "platform-other", 7))).isFalse();
        assertThat(sessions.matchesCurrentReady(
                connection("reservation-1", "desktop-1", "session-1", "ws-other"),
                identity(connection("reservation-1", "desktop-1", "session-1", "ws-other"),
                        "user-1", "tenant-1", "2", 7))).isFalse();

        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                8, "credential-7", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                7, "credential-other", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                7, "credential-7", 4));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-other", "session-1", "user-1", "tenant-1", "2",
                7, "credential-7", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-other", "user-1", "tenant-1", "2",
                7, "credential-7", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-other", "tenant-1", "2",
                7, "credential-7", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-1", "tenant-other", "2",
                7, "credential-7", 3));
        assertDurableDriftDenied(repository, sessions, connection, identity, ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "platform-other",
                7, "credential-7", 3));
    }

    @Test
    void post_bind_rejects_provisional_identity_even_when_exact_ready_lease_is_live() {
        MutableRepository repository = new MutableRepository();
        OaSessionRecord ready = ready(
                "desktop-1", "session-1", "user-1", "tenant-1", "2",
                7, "credential-7", 3);
        repository.put(ready);
        BusinessOaSessionRegistry sessions = DurableOaSessionFixture.memory(repository).sessions();
        TrustedDesktopConnection connection = connection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        sessions.captureReady(ready, connection);

        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationInstallationLease installation = new ApplicationInstallationLease(
                "installation-1", connection, 6, Instant.now().plusSeconds(90));
        TrustedBusinessIdentity provisional = identities.installServer(
                connection, installation, "auth-1", 7,
                "user-1", "tenant-1", "2", Set.of("lawyer"), Set.of("framework:read"));
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByWebSocketSessionId("ws-1")).thenReturn(Optional.of(connection));
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(
                identities, connections, sessions);

        assertThat(sessions.matchesCurrentReady(connection, provisional)).isTrue();
        assertThat(identities.find("ws-1")).isEmpty();
        assertThat(identities.current(connection)).isEmpty();
        assertThat(policy.isAllowed("thread/list", "ws-1")).isFalse();

        identities.commitInstallation(connection, installation);

        assertThat(policy.isAllowed("thread/list", "ws-1")).isTrue();
    }

    private static void assertReadyIsNotExposed(JsonRpcMessage response) {
        assertThat(response).isInstanceOf(JsonRpcMessage.Response.class);
        Object result = ((JsonRpcMessage.Response) response).result();
        assertThat(result).isInstanceOf(BusinessAuthDtos.Session.class);
        BusinessAuthDtos.Session session = (BusinessAuthDtos.Session) result;
        assertThat(session.state()).isEqualTo(OaSessionPhase.INSTALLING.name());
        assertThat(session.identityEpoch()).isZero();
    }

    private static JsonRpcMessage.Request sessionGetRequest(long id) {
        return new JsonRpcMessage.Request(
                "2.0", id, "business/auth/session/get", Map.of());
    }

    private static WebSocketSession webSocket(TrustedDesktopConnection connection) {
        WebSocketSession webSocket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE,
                connection.reservationId());
        attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE,
                connection.desktopInstanceId());
        attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE,
                connection.desktopSessionId());
        when(webSocket.getId()).thenReturn(connection.webSocketSessionId());
        when(webSocket.getAttributes()).thenReturn(attributes);
        return webSocket;
    }

    private static FinalizedConnection finalizedConnection() {
        BusinessDesktopModeProperties properties = mock(BusinessDesktopModeProperties.class);
        when(properties.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry(properties);
        String reservationId = registry.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservationId, "desktop-1", "session-1", "ws-1");
        return new FinalizedConnection(registry, connection);
    }

    private static OaAuthenticationGateway refreshGateway() {
        return new OaAuthenticationGateway() {
            @Override
            public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
                return List.of();
            }

            @Override
            public OaAuthDtos.OaCredential login(
                    OaAuthDtos.OaTenantCandidate candidate,
                    char[] password) {
                return null;
            }

            @Override
            public OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken) {
                return new OaAuthDtos.OaCredential(
                        "access-new", "refresh-new", "user-1", 3600);
            }

            @Override
            public OaAuthDtos.OaPermissionSnapshot loadPermissions(
                    String tenantId,
                    char[] accessToken) {
                return permissions();
            }

            @Override
            public void logout(String tenantId, char[] accessToken) {
            }
        };
    }

    private static OaAuthDtos.OaPermissionSnapshot permissions() {
        return new OaAuthDtos.OaPermissionSnapshot(
                List.of("framework:read"), List.of("lawyer"),
                "user-1", "Lawyer", List.of());
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(operation + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", interrupted);
        }
    }

    private static void assertDurableDriftDenied(
            MutableRepository repository,
            BusinessOaSessionRegistry sessions,
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity,
            OaSessionRecord drifted) {
        OaSessionRecord exact = repository.record();
        repository.put(drifted);
        assertThat(sessions.matchesCurrentReady(connection, identity)).isFalse();
        repository.put(exact);
        assertThat(sessions.matchesCurrentReady(connection, identity)).isTrue();
    }

    private static TrustedDesktopConnection connection(
            String reservationId,
            String desktopInstanceId,
            String desktopSessionId,
            String webSocketSessionId) {
        return new TrustedDesktopConnection(
                reservationId, desktopInstanceId, desktopSessionId, webSocketSessionId);
    }

    private static TrustedBusinessIdentity identity(
            TrustedDesktopConnection connection,
            String userId,
            String tenantId,
            String platformId,
            long identityEpoch) {
        return new TrustedBusinessIdentity(
                connection.reservationId(), connection.webSocketSessionId(),
                connection.desktopInstanceId(), connection.desktopSessionId(),
                "auth-1", identityEpoch, userId, tenantId, platformId,
                Set.of("lawyer"), Set.of("framework:read"));
    }

    private static OaSessionRecord ready(
            String desktopInstanceId,
            String desktopSessionId,
            String userId,
            String tenantId,
            String platformId,
            long generation,
            String credentialRef,
            int credentialVersion) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord(
                "auth-1", desktopInstanceId, desktopSessionId,
                userId, tenantId, platformId, OaSessionPhase.READY,
                generation, credentialRef, null, credentialVersion,
                null, now, null, null, now);
    }

    private static final class MutableRepository implements OaSessionRepository {
        private OaSessionRecord record;

        private void put(OaSessionRecord value) {
            record = value;
        }

        private OaSessionRecord record() {
            return record;
        }

        @Override
        public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId)
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId,
                String desktopSessionId) {
            return record != null
                    && record.desktopInstanceId().equals(desktopInstanceId)
                    && record.desktopSessionId().equals(desktopSessionId)
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public OaSessionRecord insert(OaSessionRecord value) {
            record = value;
            return value;
        }

        @Override
        public OaSessionRecord update(OaSessionRecord value) {
            record = value;
            return value;
        }

        @Override
        public boolean compareAndSwapGeneration(
                String authSessionId,
                long expectedGeneration,
                OaSessionRecord value) {
            if (record == null
                    || !record.authSessionId().equals(authSessionId)
                    || record.generation() != expectedGeneration) {
                return false;
            }
            record = value;
            return true;
        }

        @Override
        public boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord value) {
            if (!expected.equals(record)) {
                return false;
            }
            record = value;
            return true;
        }

        @Override
        public List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : List.of(record);
        }
    }

    private record FinalizedConnection(
            BusinessDesktopConnectionRegistry registry,
            TrustedDesktopConnection connection) {
    }
}
