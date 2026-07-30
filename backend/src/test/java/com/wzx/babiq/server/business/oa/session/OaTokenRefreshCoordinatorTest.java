package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.business.oa.client.OaAuthenticationException;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationError;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OaTokenRefreshCoordinatorTest {

    @Test
    void refreshes_one_time_for_concurrent_requests_on_same_generation() throws Exception {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(credentials,"auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready("auth-1", "desktop-1", "lease-1", ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        BlockingGateway gateway = new BlockingGateway();
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(registry, repository,
                fixture.persistence(), credentials, gateway);

        var first = coordinator.refresh(lease);
        assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        var second = coordinator.refresh(lease);
        gateway.release.countDown();

        ReadyOaSessionLease firstLease = first.get(2, TimeUnit.SECONDS);
        ReadyOaSessionLease secondLease = second.get(2, TimeUnit.SECONDS);
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(firstLease.generation()).isEqualTo(secondLease.generation());
        assertThat(firstLease.generation()).isEqualTo(6);
    }

    @Test
    void arbitrary_current_n_plus_one_is_not_a_completed_refresh_successor() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                credentials, "auth-1", 1,
                "old-access".toCharArray(), "old-refresh".toCharArray());
        OaSessionRecord originalReady = ready("auth-1", "desktop-1", "lease-1", ref);
        repository.put(originalReady);
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease predecessor = registry.captureReady(connection());
        OaSessionRecord unrelatedReady = new OaSessionRecord(
                "auth-1", "desktop-1", "lease-1", "user-1", "tenant-1", "2",
                OaSessionPhase.READY, predecessor.generation() + 1,
                "unrelated-credential-ref", null, predecessor.credentialVersion() + 1,
                null, Instant.now(), null, null, Instant.now());
        repository.put(unrelatedReady);
        registry.captureReady(unrelatedReady, connection());
        AtomicInteger refreshCalls = new AtomicInteger();
        OaAuthenticationGateway gateway = new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
                return List.of();
            }
            @Override public OaAuthDtos.OaCredential login(
                    OaAuthDtos.OaTenantCandidate candidate, char[] password) {
                return null;
            }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) {
                refreshCalls.incrementAndGet();
                return new OaAuthDtos.OaCredential(
                        "unexpected-access", "unexpected-refresh", "user-1", 3600);
            }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(
                    String tenantId, char[] token) {
                return null;
            }
            @Override public void logout(String tenantId, char[] token) {
            }
        };
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), credentials, gateway);

        assertThatThrownBy(() -> coordinator.refresh(predecessor).join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseMessage("OA session lease is stale");
        assertThat(refreshCalls).hasValue(0);
    }

    @Test
    void different_websocket_on_same_generation_cannot_join_in_flight_refresh()
            throws Exception {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                credentials, "auth-1", 1,
                "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready("auth-1", "desktop-1", "lease-1", ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease current = registry.captureReady(connection());
        ReadyOaSessionLease differentWebSocket = new ReadyOaSessionLease(
                current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                "ws-2", current.userId(), current.tenantId(), current.platformId(),
                current.generation(), current.activeCredentialRef(), current.credentialVersion(),
                Instant.now());
        BlockingGateway gateway = new BlockingGateway();
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), credentials, gateway);

        var currentRefresh = coordinator.refresh(current);
        assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        var staleRefresh = coordinator.refresh(differentWebSocket);

        try {
            assertThat(staleRefresh).isNotSameAs(currentRefresh);
            assertThatThrownBy(staleRefresh::join)
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasRootCauseMessage("OA session lease is stale");
        } finally {
            gateway.release.countDown();
        }
        assertThat(gateway.completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.installingCommitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.readyCommitted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(currentRefresh.get(5, TimeUnit.SECONDS).webSocketSessionId())
                .isEqualTo("ws-1");
        assertThat(gateway.calls).hasValue(1);
    }

    @Test
    void network_failure_keeps_ready_session() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(credentials,"auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready("auth-1", "desktop-1", "lease-1", ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        OaAuthenticationGateway gateway = new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) {
                throw new OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT);
            }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
            @Override public void logout(String tenantId, char[] token) { }
        };
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(registry, repository,
                fixture.persistence(), credentials, gateway);

        var failed = coordinator.refresh(lease);
        org.assertj.core.api.Assertions.assertThatThrownBy(failed::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class);
        OaSessionRecord current = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(current.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(current.activeCredentialRef()).isEqualTo(ref);
        assertThat(registry.isCurrent(lease)).isTrue();
    }

    @Test
    void refreshed_user_id_must_match_the_predecessor_lease() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready("auth-1", "desktop-1", "lease-1", ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        OaAuthenticationGateway gateway = new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) {
                return new OaAuthDtos.OaCredential("access-b", "refresh-b", "user-b", 3600);
            }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
            @Override public void logout(String tenantId, char[] token) { }
        };
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), credentials, gateway);

        assertThatThrownBy(() -> coordinator.refresh(lease).join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseMessage("OA refresh identity mismatch");
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().userId())
                .isEqualTo("user-1");
        assertThat(registry.isCurrent(lease)).isTrue();
    }

    @Test
    void durable_identity_drift_cannot_refresh_a_predecessor_lease() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        BusinessOaSessionRegistry registry = mock(BusinessOaSessionRegistry.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        OaSessionCredentialStore credentials = mock(OaSessionCredentialStore.class);
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        ReadyOaSessionLease predecessor = new ReadyOaSessionLease(
                "auth-1", "desktop-1", "lease-1", "ws-1",
                "user-a", "tenant-1", "2", 5, "credential-a", 1, Instant.now());
        OaSessionRecord drifted = new OaSessionRecord(
                "auth-1", "desktop-1", "lease-1", "user-b", "tenant-1", "2",
                OaSessionPhase.READY, 5, "credential-b", null, 2,
                null, Instant.now(), null, null, Instant.now());
        when(registry.isCurrent(predecessor)).thenReturn(true);
        when(repository.findByAuthSessionId("auth-1")).thenReturn(Optional.of(drifted));
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                registry, repository, persistence, credentials, gateway);

        assertThatThrownBy(() -> coordinator.refresh(predecessor).join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseMessage("OA session lease is stale");
    }

    @ParameterizedTest
    @EnumSource(value = OaAuthenticationError.class, names = {
            "INVALID_CREDENTIALS", "ACCOUNT_NOT_FOUND"
    })
    void terminal_auth_failure_is_propagated_without_partial_revocation(
            OaAuthenticationError error) {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready("auth-1", "desktop-1", "lease-1", ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        OaAuthenticationGateway gateway = new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) {
                throw new OaAuthenticationException(error);
            }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
            @Override public void logout(String tenantId, char[] token) { }
        };
        OaTokenRefreshCoordinator coordinator = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), credentials, gateway);

        var failed = coordinator.refresh(lease);

        org.assertj.core.api.Assertions.assertThatThrownBy(failed::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(OaAuthenticationException.class)
                .hasRootCauseMessage(error.name());
        OaSessionRecord ready = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(ready.generation()).isEqualTo(5);
        assertThat(ready.activeCredentialRef()).isEqualTo(ref);
        assertThat(registry.isCurrent(lease)).isTrue();
    }

    private static OaSessionRecord ready(String auth, String instance, String desktopSession, String ref) {
        Instant now = Instant.now();
        return new OaSessionRecord(auth, instance, desktopSession, "user-1", "tenant-1", "2", OaSessionPhase.READY,
                5, ref, null, 1, null, now, null, null, now);
    }

    private static TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation-1", "desktop-1", "lease-1", "ws-1");
    }

    private static final class BlockingGateway implements OaAuthenticationGateway {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
        @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
        @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) {
            calls.incrementAndGet();
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            completed.countDown();
            return new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 3600);
        }
        @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
        @Override public void logout(String tenantId, char[] token) { }
    }

    private static class Repository implements OaSessionRepository {
        private OaSessionRecord record;
        private final CountDownLatch installingCommitted = new CountDownLatch(1);
        private final CountDownLatch readyCommitted = new CountDownLatch(1);
        void put(OaSessionRecord value) { record = value; }
        @Override public Optional<OaSessionRecord> findByAuthSessionId(String id) { return record != null && record.authSessionId().equals(id) ? Optional.of(record) : Optional.empty(); }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String i, String s) { return record != null && record.desktopInstanceId().equals(i) && record.desktopSessionId().equals(s) ? Optional.of(record) : Optional.empty(); }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(String id, long generation, OaSessionRecord value) { if (record == null || !record.authSessionId().equals(id) || record.generation() != generation) return false; record = value; return true; }
        @Override public synchronized boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
            if (!expected.equals(record)) return false;
            record = next;
            if (next.phase() == OaSessionPhase.INSTALLING) installingCommitted.countDown();
            if (next.phase() == OaSessionPhase.READY) readyCommitted.countDown();
            return true;
        }
        @Override public List<OaSessionRecord> listRecoverable() { return record == null ? List.of() : List.of(record); }
    }
}
