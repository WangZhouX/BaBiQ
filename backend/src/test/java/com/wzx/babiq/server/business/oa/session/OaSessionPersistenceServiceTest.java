package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OaSessionPersistenceServiceTest {

    @Test
    void signed_out_session_cannot_stage_credentials_before_authentication_begins() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secretStore =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secretStore);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService service = fixture.persistence();
        repository.put(OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now()));

        assertThatThrownBy(() -> service.stage(
                "auth-1", 0, "access".toCharArray(), "refresh".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA session cannot stage credentials");

        assertThat(secretStore.size()).isZero();
    }

    @Test
    void installation_is_server_owned_and_expires_after_ninety_seconds() {
        InMemoryRepository repository = new InMemoryRepository();
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(
                repository, Clock.fixed(now, java.time.ZoneOffset.UTC));
        OaSessionPersistenceService service = fixture.persistence();
        OaSessionRecord authenticating = beginAuthenticating(repository, fixture.sessions(), now);
        TrustedDesktopConnection owner = new TrustedDesktopConnection("reservation-1", "desktop-1", "session-1", "ws-1");

        OaSessionRecord staged = service.stage("auth-1", authenticating.generation(), owner,
                "access".toCharArray(), "refresh".toCharArray());

        assertThat(staged.installationId()).isNotBlank();
        assertThat(staged.installationOwnerDesktopInstanceId()).isEqualTo("desktop-1");
        assertThat(staged.installationOwnerDesktopSessionId()).isEqualTo("session-1");
        assertThat(staged.installationTargetGeneration()).isEqualTo(authenticating.generation());
        assertThat(staged.installationExpiresAt()).isEqualTo(now.plusSeconds(90));

        OaSessionRecord active = service.activate("auth-1", staged.generation(), staged.installationId(), owner,
                "user-1", "tenant-1", "2");
        assertThat(active.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(active.installationId()).isNull();
        assertThat(active.installationExpiresAt()).isNull();
    }

    @Test
    void expired_or_different_owner_cannot_publish_ready() {
        InMemoryRepository repository = new InMemoryRepository();
        OaSessionCredentialStore credentials = DurableOaSessionFixture.newCredentialStore();
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(
                repository, credentials, Clock.fixed(now, java.time.ZoneOffset.UTC));
        OaSessionPersistenceService service = fixture.persistence();
        OaSessionRecord authenticating = beginAuthenticating(repository, fixture.sessions(), now);
        TrustedDesktopConnection owner = new TrustedDesktopConnection("reservation-1", "desktop-1", "session-1", "ws-1");
        OaSessionRecord staged = service.stage("auth-1", authenticating.generation(), owner,
                "access".toCharArray(), "refresh".toCharArray());

        TrustedDesktopConnection other = new TrustedDesktopConnection("reservation-2", "desktop-1", "other-session", "ws-2");
        assertThatThrownBy(() -> service.activate("auth-1", staged.generation(), staged.installationId(), other,
                "user-1", "tenant-1", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA installation owner mismatch");

        DurableOaSessionFixture expiredFixture = DurableOaSessionFixture.memory(
                repository, credentials, Clock.fixed(now.plusSeconds(91), java.time.ZoneOffset.UTC));
        OaSessionPersistenceService expiredService = expiredFixture.persistence();
        assertThatThrownBy(() -> expiredService.activate("auth-1", staged.generation(), staged.installationId(), owner,
                "user-1", "tenant-1", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA installation expired");
    }

    @Test
    void activation_requires_installing_phase_and_matching_persisted_installation_lease() {
        InMemoryRepository repository = new InMemoryRepository();
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(
                repository, Clock.fixed(now, java.time.ZoneOffset.UTC));
        OaSessionPersistenceService service = fixture.persistence();
        OaSessionRecord authenticating = beginAuthenticating(repository, fixture.sessions(), now);
        TrustedDesktopConnection owner = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        OaSessionRecord staged = service.stage("auth-1", authenticating.generation(), owner,
                "access".toCharArray(), "refresh".toCharArray());

        repository.put(copyInstallation(staged, OaSessionPhase.AUTHENTICATING,
                staged.installationOwnerDesktopInstanceId(),
                staged.installationOwnerDesktopSessionId(), staged.installationTargetGeneration()));
        assertThatThrownBy(() -> service.activate("auth-1", staged.generation(), staged.installationId(), owner,
                "user-1", "tenant-1", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA session installation is stale");

        repository.put(copyInstallation(staged, OaSessionPhase.INSTALLING,
                "desktop-other", staged.installationOwnerDesktopSessionId(),
                staged.installationTargetGeneration()));
        assertThatThrownBy(() -> service.activate("auth-1", staged.generation(), staged.installationId(), owner,
                "user-1", "tenant-1", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA installation owner mismatch");

        repository.put(copyInstallation(staged, OaSessionPhase.INSTALLING,
                staged.installationOwnerDesktopInstanceId(),
                staged.installationOwnerDesktopSessionId(), staged.installationTargetGeneration() + 1));
        assertThatThrownBy(() -> service.activate("auth-1", staged.generation(), staged.installationId(), owner,
                "user-1", "tenant-1", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA installation generation mismatch");
    }

    @Test
    void staged_credential_is_activated_by_generation_compare_and_swap() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService service = fixture.persistence();
        OaSessionRecord authenticating = beginAuthenticating(repository, fixture.sessions(), Instant.now());

        OaSessionRecord staged = service.stage(
                "auth-1", authenticating.generation(), "access".toCharArray(), "refresh".toCharArray());
        OaSessionRecord active = service.activate("auth-1", staged.generation(), "user-1", "tenant-1", "2");

        assertThat(active.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(active.activeCredentialRef()).isEqualTo(staged.stagedCredentialRef());
        assertThat(active.stagedCredentialRef()).isNull();
        assertThat(active.generation()).isEqualTo(staged.generation() + 1);
    }

    @Test
    void signed_out_revoke_is_idempotent_and_authenticating_session_can_be_staged_again() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService service = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        repository.put(OaSessionRecord.signedOut("auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord signedOut = service.revoke("auth-1", 0);
        OaSessionRecord authenticating = sessions.transition(
                "auth-1", signedOut.generation(), BusinessOaSessionState.AUTHENTICATING);

        OaSessionRecord staged = service.stage(
                "auth-1", authenticating.generation(), "access".toCharArray(), "refresh".toCharArray());

        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(signedOut.generation()).isZero();
        assertThat(staged.phase()).isEqualTo(OaSessionPhase.INSTALLING);
        assertThat(staged.generation()).isEqualTo(authenticating.generation());
    }

    @Test
    void failed_generation_cas_keeps_existing_ready_session_and_cleans_staged_secret() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secretStore =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secretStore);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService service = fixture.persistence();
        repository.put(OaSessionRecord.ready("auth-1", "desktop-1", "session-1", "old-ref", Instant.now()));
        repository.failNextUpdate();

        assertThatThrownBy(() -> service.stage("auth-1", 1, "new-access".toCharArray(), "new-refresh".toCharArray()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(secretStore.size()).isZero();
    }

    @Test
    void public_detach_drains_the_staged_secret_when_stage_wins_between_its_two_reads() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture.MemorySecretStore secretStore =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secretStore);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository, credentials);
        OaSessionPersistenceService service = fixture.persistence();
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        OaSessionRecord authenticating = beginAuthenticating(repository, fixture.sessions(), now);
        TrustedDesktopConnection owner = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        OaSessionRecord installing = service.stage(
                authenticating.authSessionId(), authenticating.generation(), owner,
                "access".toCharArray(), "refresh".toCharArray());
        repository.returnOnceFromDesktopLookup(authenticating);

        OaSessionRecord signedOut = service.detach(owner);

        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(fixture.cleanupRepository()
                .findBySecretRef(installing.stagedCredentialRef())).isEmpty();
        assertThat(secretStore.size()).isZero();
    }

    @Test
    void durable_fixture_rejects_cross_session_cleanup_owner_override_for_every_tombstone_state() {
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(new InMemoryRepository());
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        String reservedRef = "keystore://business.oa.auth-a.v1-reserved";
        BusinessOaSecretCleanupRecord reserved = fixture.cleanupRepository().upsertReserved(
                reservedRef, "auth-a", "SESSION_STAGE", "operation-a", now);

        BusinessOaSecretCleanupException reservedConflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> fixture.cleanupRepository().upsertDeletePending(
                        reservedRef,
                        "auth-b",
                        "SESSION_REVOKE",
                        "operation-b",
                        now.plusSeconds(1)));
        assertThat(reservedConflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(reservedConflict).hasMessage("OA secret cleanup state conflict");
        assertThat(fixture.cleanupRepository().findBySecretRef(reservedRef)).contains(reserved);

        String pendingRef = "keystore://business.oa.auth-a.v1-delete-pending";
        fixture.cleanupRepository().upsertReserved(
                pendingRef, "auth-a", "SESSION_STAGE", "operation-a", now);
        assertThat(fixture.cleanupRepository().markReservedDeletePending(
                pendingRef,
                "auth-a",
                "SESSION_REVOKE",
                "operation-a",
                now.plusSeconds(1))).isTrue();
        BusinessOaSecretCleanupRecord pending =
                fixture.cleanupRepository().findBySecretRef(pendingRef).orElseThrow();

        BusinessOaSecretCleanupException pendingConflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> fixture.cleanupRepository().upsertDeletePending(
                        pendingRef,
                        "auth-b",
                        "SESSION_REVOKE",
                        "operation-b",
                        now.plusSeconds(2)));
        assertThat(pendingConflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(pendingConflict).hasMessage("OA secret cleanup state conflict");
        assertThat(fixture.cleanupRepository().findBySecretRef(pendingRef)).contains(pending);
    }

    @Test
    void durable_fixture_repeated_delete_schedule_matches_sqlite_audit_semantics() {
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(new InMemoryRepository());
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        String secretRef = "keystore://business.oa.auth-a.v1-repeated-pending";
        fixture.cleanupRepository().upsertReserved(
                secretRef, "auth-a", "SESSION_STAGE", "operation-stage", now);
        BusinessOaSecretCleanupRecord first = fixture.cleanupRepository().upsertDeletePending(
                secretRef,
                "auth-a",
                "SESSION_REPLACED",
                "operation-first",
                now.plusSeconds(1));
        assertThat(fixture.cleanupRepository().recordDeleteFailure(
                secretRef,
                "KEYSTORE_DELETE_FAILED",
                now.plusSeconds(2))).isTrue();

        BusinessOaSecretCleanupRecord repeated = fixture.cleanupRepository().upsertDeletePending(
                secretRef,
                "auth-a",
                "SESSION_REVOKED",
                "operation-repeated",
                now.plusSeconds(3));

        assertThat(repeated.reasonCode()).isEqualTo("SESSION_REVOKED");
        assertThat(repeated.operationId()).isEqualTo("operation-repeated");
        assertThat(repeated.attemptCount()).isEqualTo(1);
        assertThat(repeated.createdAt()).isEqualTo(first.createdAt());
        assertThat(repeated.updatedAt()).isEqualTo(now.plusSeconds(3));
        assertThat(repeated.lastAttemptAt()).isEqualTo(now.plusSeconds(2));
        assertThat(repeated.lastResultCode()).isEqualTo("KEYSTORE_DELETE_FAILED");
    }

    @Test
    void durable_fixture_rolls_back_session_cas_when_cleanup_owner_conflict_follows() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        String activeRef = "keystore://business.oa.auth-owner.v1-active";
        OaSessionRecord revoking = new OaSessionRecord(
                "auth-owner",
                "desktop-owner",
                "session-owner",
                "user-owner",
                "tenant-owner",
                "2",
                OaSessionPhase.REVOKING,
                7,
                activeRef,
                null,
                1,
                null,
                now,
                null,
                now,
                now);
        repository.put(revoking);
        fixture.cleanupRepository().upsertReserved(
                activeRef,
                "auth-foreign",
                "FOREIGN_RESERVATION",
                "operation-foreign",
                now);

        BusinessOaSecretCleanupException conflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> fixture.persistence().recoverInstalling(revoking));
        assertThat(conflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(conflict).hasMessage("OA secret cleanup state conflict");

        assertThat(repository.findByAuthSessionId(revoking.authSessionId())).contains(revoking);
        assertThat(fixture.cleanupRepository().findBySecretRef(activeRef).orElseThrow())
                .satisfies(tombstone -> {
                    assertThat(tombstone.authSessionId()).isEqualTo("auth-foreign");
                    assertThat(tombstone.state()).isEqualTo(BusinessOaSecretCleanupState.RESERVED);
                });
    }

    @Test
    void terminal_revoke_rejects_ready_without_the_revoking_gate() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService service = fixture.persistence();
        OaSessionRecord ready = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", "active-ref", Instant.now());
        repository.put(ready);

        assertThatThrownBy(() -> service.revoke(ready.authSessionId(), ready.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA session cannot complete revocation");
        assertThat(repository.findByAuthSessionId(ready.authSessionId())).contains(ready);
    }

    @Test
    void legacy_revoked_normalization_is_phase_specific() {
        InMemoryRepository repository = new InMemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService service = fixture.persistence();
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        OaSessionRecord revoked = new OaSessionRecord(
                "auth-1", "desktop-1", "session-1",
                "user-1", "tenant-1", "2", OaSessionPhase.REVOKED, 7,
                null, null, 1, null, now, null, now, now);
        repository.put(revoked);

        OaSessionRecord signedOut = service.normalizeLegacyRevoked(
                revoked.authSessionId(), revoked.generation());

        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(signedOut.generation()).isEqualTo(revoked.generation() + 1);

        OaSessionRecord ready = OaSessionRecord.ready(
                "auth-1", "desktop-1", "session-1", "active-ref", now.plusSeconds(1));
        repository.put(ready);
        assertThatThrownBy(() -> service.normalizeLegacyRevoked(
                ready.authSessionId(), ready.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OA session is not legacy revoked");
    }

    private static OaSessionRecord copyInstallation(
            OaSessionRecord source,
            OaSessionPhase phase,
            String ownerDesktopInstanceId,
            String ownerDesktopSessionId,
            long targetGeneration) {
        return new OaSessionRecord(source.authSessionId(), source.desktopInstanceId(), source.desktopSessionId(),
                source.userId(), source.tenantId(), source.platformId(), phase, source.generation(),
                source.activeCredentialRef(), source.stagedCredentialRef(), source.credentialVersion(),
                source.installStartedAt(), source.installedAt(), source.detachedAt(), source.revokedAt(),
                source.updatedAt(), source.installationId(), ownerDesktopInstanceId, ownerDesktopSessionId,
                targetGeneration, source.installationExpiresAt());
    }

    private static OaSessionRecord beginAuthenticating(
            InMemoryRepository repository,
            BusinessOaSessionRegistry sessions,
            Instant now) {
        repository.put(OaSessionRecord.signedOut("auth-1", "desktop-1", "session-1", now));
        return sessions.transition("auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
    }

    private static final class InMemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;
        private boolean failNextUpdate;
        private OaSessionRecord oneTimeDesktopLookup;

        void put(OaSessionRecord value) { record = value; }
        void failNextUpdate() { failNextUpdate = true; }
        void returnOnceFromDesktopLookup(OaSessionRecord value) { oneTimeDesktopLookup = value; }

        @Override public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId) ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
            if (oneTimeDesktopLookup != null) {
                OaSessionRecord result = oneTimeDesktopLookup;
                oneTimeDesktopLookup = null;
                return Optional.of(result);
            }
            return record != null && record.desktopInstanceId().equals(instanceId) && record.desktopSessionId().equals(sessionId)
                    ? Optional.of(record) : Optional.empty();
        }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) {
            if (failNextUpdate) { failNextUpdate = false; throw new IllegalStateException("controlled repository failure"); }
            record = value; return value;
        }
        @Override public boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration, OaSessionRecord value) {
            if (record == null || !record.authSessionId().equals(authSessionId) || record.generation() != expectedGeneration) return false;
            update(value); return true;
        }
        @Override public synchronized boolean compareAndSwapExact(
                OaSessionRecord expected, OaSessionRecord next) {
            if (!expected.equals(record)) return false;
            update(next);
            return true;
        }
        @Override public List<OaSessionRecord> listRecoverable() { return record == null ? List.of() : new ArrayList<>(List.of(record)); }
    }
}
