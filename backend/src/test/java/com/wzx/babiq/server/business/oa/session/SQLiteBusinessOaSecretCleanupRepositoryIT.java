package com.wzx.babiq.server.business.oa.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.persistence.entity.BusinessOaSecretCleanupEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
class SQLiteBusinessOaSecretCleanupRepositoryIT {
    private static final Path TEST_DB = Path.of("target", "test-db",
            "oa-secret-cleanup-repository-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private BusinessOaSecretCleanupRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void upserts_reserved_reference_without_exposing_it_from_domain_to_string() throws Exception {
        String secretRef = "keystore://business.oa.auth-reserve-private-ref";
        Instant createdAt = Instant.parse("2026-07-28T01:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(5);

        repository.upsertReserved(secretRef, "auth-reserve", "LOGIN_STAGE", null, createdAt);
        BusinessOaSecretCleanupRecord upserted = repository.upsertReserved(
                secretRef, "auth-reserve", "LOGIN_RETRY", "operation-1", updatedAt);

        assertThat(repository.listByState(BusinessOaSecretCleanupState.RESERVED)).containsExactly(upserted);
        assertThat(upserted.authSessionId()).isEqualTo("auth-reserve");
        assertThat(upserted.state()).isEqualTo(BusinessOaSecretCleanupState.RESERVED);
        assertThat(upserted.reasonCode()).isEqualTo("LOGIN_RETRY");
        assertThat(upserted.operationId()).isEqualTo("operation-1");
        assertThat(upserted.attemptCount()).isZero();
        assertThat(upserted.createdAt()).isEqualTo(createdAt);
        assertThat(upserted.updatedAt()).isEqualTo(updatedAt);
        assertThat(upserted.toString()).doesNotContain(secretRef).doesNotContain("secretRef");
        assertThat(objectMapper.writeValueAsString(upserted))
                .doesNotContain(secretRef)
                .doesNotContain("secretRef");
        BusinessOaSecretCleanupEntity entity = new BusinessOaSecretCleanupEntity();
        entity.setSecretRef(secretRef);
        assertThat(entity.toString()).doesNotContain(secretRef);
        assertThat(objectMapper.writeValueAsString(entity))
                .doesNotContain(secretRef)
                .doesNotContain("secretRef");
        assertThat(repository.existsByAuthSessionId("auth-reserve")).isTrue();

        BusinessOaSecretCleanupException crossSessionConflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> repository.upsertReserved(
                        secretRef, "auth-other", "LOGIN_STAGE", null, updatedAt.plusSeconds(1)));
        assertThat(crossSessionConflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(crossSessionConflict.getMessage()).doesNotContain(secretRef).doesNotContain("keystore://");
        BusinessOaSecretCleanupRecord unchanged = repository.findBySecretRef(secretRef).orElseThrow();
        assertThat(unchanged.authSessionId()).isEqualTo("auth-reserve");
        assertThat(unchanged.reasonCode()).isEqualTo("LOGIN_RETRY");
    }

    @Test
    void consumes_only_the_owning_session_reservation_and_recreates_pending_without_losing_audit() {
        String secretRef = "keystore://business.oa.auth-consume-private-ref";
        Instant reservedAt = Instant.parse("2026-07-28T01:30:00Z");
        Instant pendingAt = reservedAt.plusSeconds(1);
        Instant attemptedAt = pendingAt.plusSeconds(1);
        Instant repeatedAt = attemptedAt.plusSeconds(1);
        repository.upsertReserved(secretRef, "auth-consume", "LOGIN_STAGE", "operation-stage", reservedAt);

        assertThat(repository.consumeReserved(secretRef, "auth-other")).isFalse();
        assertThat(repository.findBySecretRef(secretRef)).isPresent();
        assertThat(repository.consumeReserved(secretRef, "auth-consume")).isTrue();
        assertThat(repository.consumeReserved(secretRef, "auth-consume")).isFalse();
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();

        BusinessOaSecretCleanupRecord firstPending = repository.upsertDeletePending(
                secretRef, "auth-consume", "SESSION_REPLACED", "operation-activate", pendingAt);
        assertThat(firstPending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(firstPending.attemptCount()).isZero();
        assertThat(repository.recordDeleteFailure(
                secretRef, "KEYSTORE_DELETE_FAILED", attemptedAt)).isTrue();

        BusinessOaSecretCleanupRecord repeated = repository.upsertDeletePending(
                secretRef, "auth-consume", "SESSION_REVOKED", "operation-revoke", repeatedAt);

        assertThat(repeated.reasonCode()).isEqualTo("SESSION_REVOKED");
        assertThat(repeated.operationId()).isEqualTo("operation-revoke");
        assertThat(repeated.attemptCount()).isEqualTo(1);
        assertThat(repeated.lastAttemptAt()).isEqualTo(attemptedAt);
        assertThat(repeated.lastResultCode()).isEqualTo("KEYSTORE_DELETE_FAILED");
        BusinessOaSecretCleanupException crossSessionConflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> repository.upsertDeletePending(
                        secretRef, "auth-other", "SESSION_REVOKED", null, repeatedAt.plusSeconds(1)));
        assertThat(crossSessionConflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(repository.findBySecretRef(secretRef)).contains(repeated);
        assertThat(repository.deleteTombstone(secretRef)).isTrue();
    }

    @Test
    void transitions_to_delete_pending_records_failed_attempts_and_deletes_tombstone() {
        String secretRef = "keystore://business.oa.auth-delete-private-ref";
        Instant reservedAt = Instant.parse("2026-07-28T02:00:00Z");
        Instant pendingAt = reservedAt.plusSeconds(1);
        Instant firstAttemptAt = pendingAt.plusSeconds(1);
        Instant secondAttemptAt = firstAttemptAt.plusSeconds(1);
        repository.upsertReserved(secretRef, "auth-delete", "LOGIN_STAGE", null, reservedAt);

        assertThat(repository.deleteTombstone(secretRef)).isFalse();
        assertThat(repository.findBySecretRef(secretRef).orElseThrow().state())
                .isEqualTo(BusinessOaSecretCleanupState.RESERVED);

        assertThat(repository.markDeletePending(
                secretRef, "SESSION_REVOKED", "operation-2", pendingAt)).isTrue();
        assertThat(repository.recordDeleteFailure(
                secretRef, "KEYSTORE_DELETE_FAILED", firstAttemptAt)).isTrue();
        assertThat(repository.recordDeleteFailure(
                secretRef, "KEYSTORE_DELETE_FAILED", secondAttemptAt)).isTrue();

        BusinessOaSecretCleanupRecord pending = repository.findBySecretRef(secretRef).orElseThrow();
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.reasonCode()).isEqualTo("SESSION_REVOKED");
        assertThat(pending.operationId()).isEqualTo("operation-2");
        assertThat(pending.attemptCount()).isEqualTo(2);
        assertThat(pending.lastAttemptAt()).isEqualTo(secondAttemptAt);
        assertThat(pending.lastResultCode()).isEqualTo("KEYSTORE_DELETE_FAILED");
        assertThat(repository.listByState(BusinessOaSecretCleanupState.RESERVED))
                .noneMatch(record -> record.secretRef().equals(secretRef));
        assertThat(repository.listByState(BusinessOaSecretCleanupState.DELETE_PENDING)).containsExactly(pending);

        BusinessOaSecretCleanupException conflict = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> repository.upsertReserved(
                        secretRef, "auth-delete", "LOGIN_RETRY", null, secondAttemptAt.plusSeconds(1)));
        assertThat(conflict.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(conflict.getMessage()).doesNotContain(secretRef).doesNotContain("keystore://");
        assertThat(conflict.getCause()).isNull();
        assertThat(conflict.getSuppressed()).isEmpty();

        assertThat(repository.deleteTombstone(secretRef)).isTrue();
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();
        assertThat(repository.existsByAuthSessionId("auth-delete")).isFalse();
        assertThat(repository.markDeletePending(
                secretRef, "SESSION_REVOKED", null, secondAttemptAt.plusSeconds(1))).isFalse();
    }

    @Test
    void repeated_delete_scheduling_does_not_rewrite_pending_audit() {
        String secretRef = "keystore://business.oa.auth-delete-repeat-private-ref";
        Instant reservedAt = Instant.parse("2026-07-28T02:30:00Z");
        Instant pendingAt = reservedAt.plusSeconds(1);
        Instant attemptedAt = pendingAt.plusSeconds(1);
        repository.upsertReserved(secretRef, "auth-delete-repeat", "LOGIN_STAGE", null, reservedAt);
        assertThat(repository.markDeletePending(
                secretRef, "SESSION_REVOKED", "operation-original", pendingAt)).isTrue();
        assertThat(repository.recordDeleteFailure(
                secretRef, "KEYSTORE_DELETE_FAILED", attemptedAt)).isTrue();
        BusinessOaSecretCleanupRecord beforeRepeat =
                repository.findBySecretRef(secretRef).orElseThrow();

        assertThat(repository.markDeletePending(
                secretRef,
                "SESSION_REPLACED",
                "operation-replacement",
                attemptedAt.plusSeconds(1))).isFalse();

        assertThat(repository.findBySecretRef(secretRef)).contains(beforeRepeat);
        assertThat(repository.deleteTombstone(secretRef)).isTrue();
    }

    @Test
    void lists_delete_pending_batch_by_update_time_then_secret_reference() {
        Instant early = Instant.parse("2026-07-28T04:00:00Z");
        Instant late = early.plusSeconds(1);
        String earlyA = "keystore://business.oa.batch-order-a";
        String earlyB = "keystore://business.oa.batch-order-b";
        String lateA = "keystore://business.oa.batch-order-late";
        reservePending(earlyB, "auth-batch-order-b", early);
        reservePending(lateA, "auth-batch-order-late", late);
        reservePending(earlyA, "auth-batch-order-a", early);

        assertThat(repository.listDeletePendingBatch(2))
                .extracting(BusinessOaSecretCleanupRecord::secretRef)
                .containsExactly(earlyA, earlyB);

        assertThat(repository.deleteTombstone(earlyA)).isTrue();
        assertThat(repository.deleteTombstone(earlyB)).isTrue();
        assertThat(repository.deleteTombstone(lateA)).isTrue();
    }

    @Test
    void mixed_fraction_update_times_are_listed_in_chronological_order() {
        Instant early = Instant.parse("2026-07-28T04:30:00.100Z");
        Instant later = Instant.parse("2026-07-28T04:30:00.100001Z");
        String earlyRef = "keystore://business.oa.batch-fraction-early";
        String laterRef = "keystore://business.oa.batch-fraction-later";
        reservePending(earlyRef, "auth-batch-fraction-early", early);
        reservePending(laterRef, "auth-batch-fraction-later", later);

        assertThat(repository.listDeletePendingBatch(1))
                .extracting(BusinessOaSecretCleanupRecord::secretRef)
                .containsExactly(earlyRef);

        assertThat(repository.deleteTombstone(earlyRef)).isTrue();
        assertThat(repository.deleteTombstone(laterRef)).isTrue();
    }

    private void reservePending(String secretRef, String authSessionId, Instant updatedAt) {
        repository.upsertReserved(secretRef, authSessionId, "LOGIN_STAGE", null, updatedAt);
        assertThat(repository.markDeletePending(
                secretRef, "SESSION_REVOKED", null, updatedAt)).isTrue();
    }
}
