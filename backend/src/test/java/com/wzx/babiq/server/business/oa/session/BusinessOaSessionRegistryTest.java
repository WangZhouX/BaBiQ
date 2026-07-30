package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOaSessionRegistryTest {

    @Test
    void exposes_only_durable_lifecycle_constructors() {
        assertThat(OaSessionPersistenceService.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                        OaSessionRepository.class,
                        BusinessOaSecretCleanupRepository.class,
                        BusinessOaSecretCleanupService.class,
                        PlatformTransactionManager.class));
        assertThat(BusinessOaSessionRegistry.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                        OaSessionRepository.class,
                        OaSessionPersistenceService.class));
    }

    @Test
    void captures_only_ready_session_and_rejects_old_generation_after_revoke() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.put(ready("auth-1", "desktop-1", "lease-1", 4));
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessOaSessionRegistry registry = fixture.sessions();
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");

        ReadyOaSessionLease lease = registry.captureReady(connection);

        assertThat(lease.authSessionId()).isEqualTo("auth-1");
        assertThat(lease.generation()).isEqualTo(4);
        assertThat(registry.isCurrent(lease)).isTrue();

        registry.revokeBeforeCleanup(connection, BusinessOaSessionRegistry.RevocationReason.LOGOUT);

        assertThat(registry.isCurrent(lease)).isFalse();
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.REVOKING);
    }

    @Test
    void does_not_allow_ready_transition_from_signed_out_or_unknown_state() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.put(OaSessionRecord.signedOut("auth-1", "desktop-1", "lease-1", Instant.now()));
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessOaSessionRegistry registry = fixture.sessions();

        assertThatThrownBy(() -> registry.transition("auth-1", 0, BusinessOaSessionState.READY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid OA session transition");
    }

    @Test
    void detaching_invalidates_live_lease_but_keeps_durable_credentials() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.put(ready("auth-1", "desktop-1", "lease-1", 2));
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessOaSessionRegistry registry = fixture.sessions();
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);

        registry.detach(connection);

        OaSessionRecord detached = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(detached.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detached.generation()).isEqualTo(3);
        assertThat(detached.activeCredentialRef()).isEqualTo("credential-2");
        assertThat(registry.isCurrent(lease)).isFalse();
    }

    @Test
    void losing_revocation_claim_does_not_remove_the_live_ready_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionRecord ready = ready("auth-1", "desktop-1", "lease-1", 4);
        repository.put(ready);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry registry = new BusinessOaSessionRegistry(repository, persistence);
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);
        OaSessionRecord revoking = new OaSessionRecord(
                ready.authSessionId(), ready.desktopInstanceId(), ready.desktopSessionId(),
                ready.userId(), ready.tenantId(), ready.platformId(), OaSessionPhase.REVOKING,
                ready.generation() + 1, ready.activeCredentialRef(), null,
                ready.credentialVersion(), ready.installStartedAt(), ready.installedAt(),
                ready.detachedAt(), ready.revokedAt(), Instant.now());
        when(persistence.claimRevocation(connection, ready))
                .thenReturn(new OaSessionPersistenceService.RevocationTransition(revoking, false));

        BusinessOaSessionRegistry.RevocationClaim claim = registry.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, ready);

        assertThat(claim.winner()).isFalse();
        assertThat(registry.isCurrent(lease)).isTrue();
        assertThat(repository.findByAuthSessionId("auth-1")).contains(ready);
    }

    @Test
    void failed_revocation_claim_does_not_remove_the_live_ready_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionRecord ready = ready("auth-1", "desktop-1", "lease-1", 4);
        repository.put(ready);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry registry = new BusinessOaSessionRegistry(repository, persistence);
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);
        when(persistence.claimRevocation(connection, ready))
                .thenThrow(new IllegalStateException("OA session generation conflict"));

        assertThatThrownBy(() -> registry.claimRevocationBeforeCleanup(
                connection, BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED, ready))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA session generation conflict");

        assertThat(registry.isCurrent(lease)).isTrue();
        assertThat(repository.findByAuthSessionId("auth-1")).contains(ready);
    }

    @Test
    void stale_login_abort_does_not_remove_a_later_ready_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionRecord laterReady = ready("auth-1", "desktop-1", "lease-1", 4);
        repository.put(laterReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessOaSessionRegistry registry = fixture.sessions();
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);

        OaSessionRecord result = registry.abortLogin(
                connection, "auth-1", 1, "old-installation", "old-credential");

        assertThat(result).isEqualTo(laterReady);
        assertThat(registry.isCurrent(lease)).isTrue();
    }

    @Test
    void losing_login_abort_cas_does_not_remove_the_live_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionRecord ready = ready("auth-1", "desktop-1", "lease-1", 2);
        repository.put(ready);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        BusinessOaSessionRegistry registry = new BusinessOaSessionRegistry(repository, persistence);
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);
        OaSessionRecord observedSignedOut = new OaSessionRecord(
                ready.authSessionId(), ready.desktopInstanceId(), ready.desktopSessionId(),
                null, null, null, OaSessionPhase.SIGNED_OUT, 3,
                null, null, ready.credentialVersion(), null, null, null, null, Instant.now());
        when(persistence.abortLogin(
                connection, "auth-1", 1, "installation-1", ready.activeCredentialRef()))
                .thenReturn(new OaSessionPersistenceService.AbortTransition(observedSignedOut, false));

        registry.abortLogin(
                connection, "auth-1", 1, "installation-1", ready.activeCredentialRef());

        assertThat(registry.isCurrent(lease)).isTrue();
    }

    @Test
    void failed_restore_abort_does_not_remove_a_later_ready_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionRecord laterReady = ready("auth-1", "desktop-1", "lease-1", 4);
        repository.put(laterReady);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessOaSessionRegistry registry = fixture.sessions();
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");
        ReadyOaSessionLease lease = registry.captureReady(connection);

        assertThatThrownBy(() -> registry.abortRestore(
                connection, "auth-1", 1, "old-installation", "old-credential"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");

        assertThat(registry.isCurrent(lease)).isTrue();
    }

    @Test
    void connection_close_cancels_restoring_and_installing_generations() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        BusinessOaSessionRegistry registry = fixture.sessions();
        TrustedDesktopConnection connection = connection("desktop-1", "lease-1", "ws-1");

        repository.put(inProgress(OaSessionPhase.RESTORING, 6, null));
        OaSessionRecord detachedRestoring = registry.detach(connection);
        assertThat(detachedRestoring.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detachedRestoring.generation()).isEqualTo(7);
        assertThat(detachedRestoring.activeCredentialRef()).isEqualTo("credential-active");

        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        repository.put(inProgress(OaSessionPhase.INSTALLING, 8, stagedRef));
        OaSessionRecord detachedInstalling = registry.detach(connection);
        assertThat(detachedInstalling.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detachedInstalling.generation()).isEqualTo(9);
        assertThat(detachedInstalling.activeCredentialRef()).isEqualTo("credential-active");
        assertThat(detachedInstalling.stagedCredentialRef()).isNull();
        assertThat(detachedInstalling.installationId()).isNull();
        assertThat(detachedInstalling.installationExpiresAt()).isNull();
        assertThat(credentials.load(stagedRef)).isNull();
    }

    private static OaSessionRecord ready(String auth, String instance, String desktopSession, long generation) {
        Instant now = Instant.now();
        return new OaSessionRecord(auth, instance, desktopSession, "user-1", "tenant-1", "2",
                OaSessionPhase.READY, generation, "credential-" + generation, null, 1,
                null, now, null, null, now);
    }

    private static OaSessionRecord inProgress(OaSessionPhase phase, long generation, String stagedRef) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", "lease-1", "user-1", "tenant-1", "2",
                phase, generation, "credential-active", stagedRef, 2,
                now, now, now, null, now,
                phase == OaSessionPhase.INSTALLING ? "installation-1" : null,
                phase == OaSessionPhase.INSTALLING ? "desktop-1" : null,
                phase == OaSessionPhase.INSTALLING ? "lease-1" : null,
                phase == OaSessionPhase.INSTALLING ? generation : 0,
                phase == OaSessionPhase.INSTALLING ? now.plusSeconds(90) : null);
    }

    private static TrustedDesktopConnection connection(String instance, String desktopSession, String ws) {
        return new TrustedDesktopConnection("reservation-1", instance, desktopSession, ws);
    }

    private static final class InMemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;

        void put(OaSessionRecord value) { record = value; }

        @Override public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId) ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
            return record != null && record.desktopInstanceId().equals(instanceId) && record.desktopSessionId().equals(sessionId)
                    ? Optional.of(record) : Optional.empty();
        }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration, OaSessionRecord value) {
            if (record == null || !record.authSessionId().equals(authSessionId) || record.generation() != expectedGeneration) return false;
            record = value;
            return true;
        }
        @Override public synchronized boolean compareAndSwapExact(
                OaSessionRecord expected, OaSessionRecord next) {
            if (!expected.equals(record)) return false;
            record = next;
            return true;
        }
        @Override public List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : new ArrayList<>(List.of(record));
        }
    }
}
