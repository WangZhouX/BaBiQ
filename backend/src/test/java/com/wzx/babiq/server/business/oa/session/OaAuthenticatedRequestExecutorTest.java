package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OaAuthenticatedRequestExecutorTest {

    @Test
    void retries_read_once_after_confirmed_401_using_a_new_ready_lease() throws Exception {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(store,"auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        OaAuthenticationGateway gateway = refreshGateway();
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(registry, repository,
                fixture.persistence(), store, gateway);
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry));
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.READ, token -> {
            if (calls.getAndIncrement() == 0) throw OaRemoteRequestException.authenticationExpired(401);
            assertThat(new String(token)).isEqualTo("new-access");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void lateOldTokenReadReusesOnlyTheCompletedDirectRefreshSuccessor() throws Exception {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                store, "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease original = registry.captureReady(connection());
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
                assertThat(new String(token)).isEqualTo("old-refresh");
                return new OaAuthDtos.OaCredential(
                        "new-access", "new-refresh", "user-1", 3600);
            }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(
                    String tenantId, char[] token) {
                return null;
            }
            @Override public void logout(String tenantId, char[] token) {
            }
        };
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), store, gateway);
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry));
        CountDownLatch lateReadHasOldToken = new CountDownLatch(1);
        CountDownLatch releaseLate401 = new CountDownLatch(1);
        AtomicInteger lateCalls = new AtomicInteger();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Future<String> lateRead = workers.submit(() -> executor.execute(
                original, OaAuthenticatedRequestExecutor.RequestKind.READ, token -> {
                    int call = lateCalls.getAndIncrement();
                    if (call == 0) {
                        assertThat(new String(token)).isEqualTo("old-access");
                        lateReadHasOldToken.countDown();
                        await(releaseLate401);
                        throw OaRemoteRequestException.authenticationExpired(401);
                    }
                    assertThat(new String(token)).isEqualTo("new-access");
                    return "late-ok";
                }));

        try {
            assertThat(lateReadHasOldToken.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicInteger earlyCalls = new AtomicInteger();
            String earlyResult = executor.execute(
                    original, OaAuthenticatedRequestExecutor.RequestKind.READ, token -> {
                        if (earlyCalls.getAndIncrement() == 0) {
                            assertThat(new String(token)).isEqualTo("old-access");
                            throw OaRemoteRequestException.authenticationExpired(401);
                        }
                        assertThat(new String(token)).isEqualTo("new-access");
                        return "early-ok";
                    });
            assertThat(earlyResult).isEqualTo("early-ok");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(inFlight(refresh)).isEmpty());

            releaseLate401.countDown();
            assertThat(lateRead.get(5, TimeUnit.SECONDS)).isEqualTo("late-ok");
            assertThat(earlyCalls).hasValue(2);
            assertThat(lateCalls).hasValue(2);
            assertThat(refreshCalls).hasValue(1);
        } finally {
            releaseLate401.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void never_replays_write_after_auth_expiry_and_rejects_stale_response() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(store,"auth-1", 1, "access".toCharArray(), "refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease lease = registry.captureReady(connection());
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(registry, repository,
                fixture.persistence(), store, refreshGateway());
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry));

        AtomicInteger writeCalls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.WRITE,
                token -> {
                    writeCalls.incrementAndGet();
                    throw OaRemoteRequestException.authenticationExpired(499);
                }))
                .isInstanceOf(OaRemoteRequestException.class);
        assertThat(writeCalls).hasValue(1);
        OaSessionRecord signedOut = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(signedOut.activeCredentialRef()).isNull();
        assertThat(store.load(ref)).isNull();

        repository.put(ready(DurableOaSessionFixture.seedCredential(
                store, "auth-1", 2, "access-2".toCharArray(), "refresh-2".toCharArray())));
        ReadyOaSessionLease lateLease = registry.captureReady(connection());
        assertThatThrownBy(() -> executor.execute(lateLease, OaAuthenticatedRequestExecutor.RequestKind.READ, token -> {
            registry.revokeBeforeCleanup(connection(), BusinessOaSessionRegistry.RevocationReason.LOGOUT);
            return "late";
        })).isInstanceOf(OaAuthenticatedRequestExecutor.StaleLeaseException.class);
    }

    @Test
    void secondRead401TerminalizesTheRefreshedLeaseWithoutASecondReplay() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                store, "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease original = registry.captureReady(connection());
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), store, refreshGateway());
        AtomicInteger terminalCalls = new AtomicInteger();
        AtomicReference<ReadyOaSessionLease> terminalLease = new AtomicReference<>();
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry,
                (lease, reason) -> {
                    terminalCalls.incrementAndGet();
                    terminalLease.set(lease);
                }));
        AtomicInteger remoteCalls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(original, OaAuthenticatedRequestExecutor.RequestKind.READ,
                token -> {
                    remoteCalls.incrementAndGet();
                    throw OaRemoteRequestException.authenticationExpired(401);
                }))
                .isInstanceOfSatisfying(OaRemoteRequestException.class,
                        failure -> assertThat(failure.authenticationExpired()).isTrue());

        assertThat(remoteCalls).hasValue(2);
        assertThat(terminalCalls).hasValue(1);
        assertThat(terminalLease.get().generation()).isGreaterThan(original.generation());
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.SIGNED_OUT);
    }

    @Test
    void secondReadMembershipExpiryTerminalizesTheRefreshedLeaseWithoutASecondReplay() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                store, "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease original = registry.captureReady(connection());
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), store, refreshGateway());
        AtomicInteger terminalCalls = new AtomicInteger();
        AtomicReference<ReadyOaSessionLease> terminalLease = new AtomicReference<>();
        AtomicReference<OaRemoteRequestException.TerminalReason> terminalReason = new AtomicReference<>();
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry,
                (lease, reason) -> {
                    terminalCalls.incrementAndGet();
                    terminalLease.set(lease);
                    terminalReason.set(reason);
                }));
        AtomicInteger remoteCalls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(original, OaAuthenticatedRequestExecutor.RequestKind.READ,
                token -> {
                    if (remoteCalls.getAndIncrement() == 0) {
                        throw OaRemoteRequestException.authenticationExpired(499);
                    }
                    throw OaRemoteRequestException.membershipExpired(1002010000);
                }))
                .isInstanceOfSatisfying(OaRemoteRequestException.class, failure -> {
                    assertThat(failure.terminalReason())
                            .isEqualTo(OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
                    assertThat(failure.statusCode()).isEqualTo(1002010000);
                });

        assertThat(remoteCalls).hasValue(2);
        assertThat(terminalCalls).hasValue(1);
        assertThat(terminalLease.get().generation()).isGreaterThan(original.generation());
        assertThat(terminalReason).hasValue(OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.SIGNED_OUT);
    }

    @Test
    void refreshMembershipExpiryTerminalizesOriginalLeaseAndUnwrapsCompletionFailure() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                store, "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease original = registry.captureReady(connection());
        OaAuthenticationGateway gateway = gatewayRefreshingWith(
                new com.wzx.babiq.server.business.oa.client.OaAuthenticationException(
                        com.wzx.babiq.server.business.oa.client.OaAuthenticationError.MEMBER_EXPIRED));
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), store, gateway);
        AtomicReference<OaRemoteRequestException.TerminalReason> terminalReason = new AtomicReference<>();
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry,
                (lease, reason) -> terminalReason.set(reason)));

        assertThatThrownBy(() -> executor.execute(original, OaAuthenticatedRequestExecutor.RequestKind.READ,
                token -> { throw OaRemoteRequestException.authenticationExpired(401); }))
                .isInstanceOfSatisfying(OaRemoteRequestException.class, failure -> {
                    assertThat(failure.terminalReason())
                            .isEqualTo(OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
                    assertThat(failure.statusCode()).isEqualTo(1002010000);
                });

        assertThat(terminalReason).hasValue(OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED);
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.SIGNED_OUT);
    }

    @Test
    void refreshNetworkFailureIsUnwrappedWithoutRevokingOrNotifying() {
        Repository repository = new Repository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore store = fixture.credentials();
        String ref = DurableOaSessionFixture.seedCredential(
                store, "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(ready(ref));
        BusinessOaSessionRegistry registry = fixture.sessions();
        ReadyOaSessionLease original = registry.captureReady(connection());
        OaAuthenticationGateway gateway = gatewayRefreshingWith(
                new com.wzx.babiq.server.business.oa.client.OaAuthenticationException(
                        com.wzx.babiq.server.business.oa.client.OaAuthenticationError.REMOTE_TIMEOUT));
        OaTokenRefreshCoordinator refresh = new OaTokenRefreshCoordinator(
                registry, repository, fixture.persistence(), store, gateway);
        AtomicInteger terminalCalls = new AtomicInteger();
        OaAuthenticatedRequestExecutor executor = new OaAuthenticatedRequestExecutor(
                registry, store, refresh, terminalizer(repository, fixture, registry,
                (lease, reason) -> terminalCalls.incrementAndGet()));

        assertThatThrownBy(() -> executor.execute(original, OaAuthenticatedRequestExecutor.RequestKind.READ,
                token -> { throw OaRemoteRequestException.authenticationExpired(401); }))
                .isInstanceOf(com.wzx.babiq.server.business.oa.client.OaAuthenticationException.class)
                .hasMessage("REMOTE_TIMEOUT")
                .isNotInstanceOf(java.util.concurrent.CompletionException.class);

        assertThat(terminalCalls).hasValue(0);
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.READY);
        assertThat(registry.isCurrent(original)).isTrue();
        OaSessionCredentialStore.CredentialMaterial material = store.load(ref);
        assertThat(material).isNotNull();
        material.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test barrier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for test barrier", interrupted);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> inFlight(OaTokenRefreshCoordinator coordinator)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = OaTokenRefreshCoordinator.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(coordinator);
    }

    private static OaAuthenticationGateway refreshGateway() {
        return new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) { return new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 3600); }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
            @Override public void logout(String tenantId, char[] token) { }
        };
    }

    private static OaAuthenticationGateway gatewayRefreshingWith(RuntimeException failure) {
        return new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) { return List.of(); }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) { return null; }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] token) { throw failure; }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] token) { return null; }
            @Override public void logout(String tenantId, char[] token) { }
        };
    }

    private static OaSessionTerminalizer terminalizer(
            Repository repository,
            DurableOaSessionFixture fixture,
            BusinessOaSessionRegistry registry) {
        return terminalizer(repository, fixture, registry, (lease, reason) -> { });
    }

    private static OaSessionTerminalizer terminalizer(
            Repository repository,
            DurableOaSessionFixture fixture,
            BusinessOaSessionRegistry registry,
            BiConsumer<ReadyOaSessionLease, OaRemoteRequestException.TerminalReason> observer) {
        return (lease, reason) -> {
            observer.accept(lease, reason);
            OaSessionRecord target = repository.findByAuthSessionId(lease.authSessionId()).orElseThrow();
            OaSessionRecord revoking = registry.revokeBeforeCleanup(
                    connection(),
                    reason == OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED
                            ? BusinessOaSessionRegistry.RevocationReason.MEMBERSHIP_EXPIRED
                            : BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED,
                    target);
            fixture.persistence().revoke(revoking.authSessionId(), revoking.generation());
        };
    }

    private static OaSessionRecord ready(String ref) {
        Instant now = Instant.now();
        return new OaSessionRecord("auth-1", "desktop-1", "lease-1", "user-1", "tenant-1", "2", OaSessionPhase.READY,
                1, ref, null, 1, null, now, null, null, now);
    }
    private static TrustedDesktopConnection connection() { return new TrustedDesktopConnection("reservation-1", "desktop-1", "lease-1", "ws-1"); }

    private static final class Repository implements OaSessionRepository {
        private OaSessionRecord record;
        void put(OaSessionRecord value) { record = value; }
        @Override public Optional<OaSessionRecord> findByAuthSessionId(String id) { return record != null && record.authSessionId().equals(id) ? Optional.of(record) : Optional.empty(); }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String i, String s) { return record != null && record.desktopInstanceId().equals(i) && record.desktopSessionId().equals(s) ? Optional.of(record) : Optional.empty(); }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(String id, long generation, OaSessionRecord value) { if (record == null || !record.authSessionId().equals(id) || record.generation() != generation) return false; record = value; return true; }
        @Override public synchronized boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) { if (!expected.equals(record)) return false; record = next; return true; }
        @Override public List<OaSessionRecord> listRecoverable() { return record == null ? List.of() : List.of(record); }
    }
}
