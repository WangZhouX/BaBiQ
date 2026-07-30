package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessOaAttachHandleRegistryTest {

    @Test
    void claim_binds_the_complete_connection_target_generation_and_single_winner() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(repository);
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");

        String firstHandle = registry.issue(connection, repository.record());
        String repeatedProbeHandle = registry.issue(connection, repository.record());
        TrustedDesktopConnection competingConnection = connection("reservation-2", "ws-2");
        String competingHandle = registry.issue(competingConnection, repository.record());
        BusinessOaAttachHandleRegistry.AttachClaim claim = registry.claim(firstHandle, connection);

        assertThat(repeatedProbeHandle).isEqualTo(firstHandle);
        assertThat(firstHandle).hasSizeGreaterThanOrEqualTo(43)
                .doesNotContain("auth-1", "desktop-1", "session-1", "reservation-1", "ws-1");
        assertThat(claim.authSessionId()).isEqualTo("auth-1");
        assertThat(claim.observedGeneration()).isEqualTo(5);
        assertThat(claim.alreadyAttached()).isFalse();
        assertThat(claim.toString()).doesNotContain(firstHandle);
        assertNotAttachable(() -> registry.claim(firstHandle, connection));
        assertNotAttachable(() -> registry.claim(competingHandle, competingConnection));
    }

    @Test
    void wrong_connection_or_expired_handle_is_not_attachable() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");

        String handle = registry.issue(connection, repository.record());

        assertNotAttachable(() -> registry.claim(handle, connection("reservation-2", "ws-1")));
        assertNotAttachable(() -> registry.claim(handle, connection("reservation-1", "ws-2")));
        assertNotAttachable(() -> registry.claim(handle,
                new TrustedDesktopConnection("reservation-1", "desktop-other", "session-1", "ws-1")));
        assertNotAttachable(() -> registry.claim(handle,
                new TrustedDesktopConnection("reservation-1", "desktop-1", "session-other", "ws-1")));

        clock.advance(Duration.ofSeconds(60));
        assertNotAttachable(() -> registry.claim(handle, connection));
    }

    @Test
    void claim_does_not_extend_the_absolute_issue_deadline() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");
        String handle = registry.issue(connection, repository.record());

        clock.advance(Duration.ofSeconds(30));
        registry.claim(handle, connection);
        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> registry.validateClaim(handle, connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
    }

    @Test
    void completion_does_not_extend_the_absolute_issue_deadline() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");
        String handle = registry.issue(connection, repository.record());
        registry.claim(handle, connection);

        clock.advance(Duration.ofSeconds(30));
        repository.put(ready(8));
        registry.complete(handle, connection, 8);
        clock.advance(Duration.ofSeconds(30));

        assertNotAttachable(() -> registry.claim(handle, connection));
    }

    @Test
    void generation_drift_is_stale_but_successful_same_lease_retry_is_idempotent() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(repository);
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");

        String staleHandle = registry.issue(connection, repository.record());
        repository.put(detached(6));
        assertThatThrownBy(() -> registry.claim(staleHandle, connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");

        String successfulHandle = registry.issue(connection, repository.record());
        BusinessOaAttachHandleRegistry.AttachClaim winner = registry.claim(successfulHandle, connection);
        assertThat(winner.alreadyAttached()).isFalse();
        repository.put(ready(8));
        registry.complete(successfulHandle, connection, 8);

        BusinessOaAttachHandleRegistry.AttachClaim retry = registry.claim(successfulHandle, connection);
        assertThat(retry.alreadyAttached()).isTrue();
        assertThat(retry.readyGeneration()).isEqualTo(8);
        assertNotAttachable(() -> registry.claim(successfulHandle, connection("reservation-2", "ws-2")));
    }

    @Test
    void revoking_a_connection_invalidates_all_of_its_unconsumed_handles() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(repository);
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");
        String handle = registry.issue(connection, repository.record());

        registry.revoke(connection);

        assertNotAttachable(() -> registry.claim(handle, connection));
    }

    @Test
    void failed_expired_and_revoked_claims_release_the_target_for_a_new_connection() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        TrustedDesktopConnection first = connection("reservation-1", "ws-1");

        String failed = registry.issue(first, repository.record());
        registry.claim(failed, first);
        registry.fail(failed, first);
        TrustedDesktopConnection afterFailure = connection("reservation-2", "ws-2");
        String afterFailureHandle = registry.issue(afterFailure, repository.record());
        assertThat(registry.claim(afterFailureHandle, afterFailure).alreadyAttached()).isFalse();

        registry.fail(afterFailureHandle, afterFailure);
        String expiring = registry.issue(afterFailure, repository.record());
        registry.claim(expiring, afterFailure);
        clock.advance(Duration.ofSeconds(60));
        TrustedDesktopConnection afterExpiry = connection("reservation-3", "ws-3");
        assertThat(registry.claim(registry.issue(afterExpiry, repository.record()), afterExpiry)
                .alreadyAttached()).isFalse();

        registry.revoke(afterExpiry);
        TrustedDesktopConnection afterRevoke = connection("reservation-4", "ws-4");
        assertThat(registry.claim(registry.issue(afterRevoke, repository.record()), afterRevoke)
                .alreadyAttached()).isFalse();
    }

    @Test
    void successful_retry_is_valid_only_until_ttl_and_connection_revoke() {
        MemoryRepository repository = new MemoryRepository();
        repository.put(detached(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T04:00:00Z"));
        BusinessOaAttachHandleRegistry registry = new BusinessOaAttachHandleRegistry(
                repository, clock, Duration.ofSeconds(60));
        TrustedDesktopConnection connection = connection("reservation-1", "ws-1");
        String handle = registry.issue(connection, repository.record());
        registry.claim(handle, connection);
        repository.put(ready(8));
        registry.complete(handle, connection, 8);

        assertThat(registry.claim(handle, connection).alreadyAttached()).isTrue();
        clock.advance(Duration.ofSeconds(60));
        assertNotAttachable(() -> registry.claim(handle, connection));

        repository.put(detached(9));
        String nextHandle = registry.issue(connection, repository.record());
        registry.claim(nextHandle, connection);
        repository.put(ready(12));
        registry.complete(nextHandle, connection, 12);
        registry.revoke(connection);
        assertNotAttachable(() -> registry.claim(nextHandle, connection));
    }

    private static void assertNotAttachable(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_NOT_ATTACHABLE");
    }

    private static TrustedDesktopConnection connection(String reservationId, String webSocketSessionId) {
        return new TrustedDesktopConnection(
                reservationId, "desktop-1", "session-1", webSocketSessionId);
    }

    private static OaSessionRecord detached(long generation) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.DETACHED, generation, "credential-ref", null, 1,
                null, now, now, null, now);
    }

    private static OaSessionRecord ready(long generation) {
        Instant now = Instant.parse("2026-07-27T04:01:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", "session-1", "user-1", "tenant-1", "2",
                OaSessionPhase.READY, generation, "credential-ref-2", null, 2,
                null, now, null, null, now);
    }

    private static final class MemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;
        void put(OaSessionRecord value) { record = value; }
        OaSessionRecord record() { return record; }
        @Override public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId)
                    ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
            return record != null && record.desktopInstanceId().equals(instanceId)
                    && record.desktopSessionId().equals(sessionId) ? Optional.of(record) : Optional.empty();
        }
        @Override public OaSessionRecord insert(OaSessionRecord value) { record = value; return value; }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration,
                                                          OaSessionRecord value) {
            if (record == null || !record.authSessionId().equals(authSessionId)
                    || record.generation() != expectedGeneration) return false;
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

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
