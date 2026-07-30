package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.settings.SecretStore;
import com.wzx.babiq.server.settings.SecretStoreException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
class BusinessOaSecretCleanupServiceIT {
    private static final Path TEST_DB = Path.of("target", "test-db",
            "oa-secret-cleanup-service-" + UUID.randomUUID() + ".db").toAbsolutePath();
    private static final Instant NOW = Instant.parse("2026-07-28T03:00:00Z");

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private BusinessOaSecretCleanupRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void reservation_commits_before_keystore_write_even_inside_an_outer_transaction() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        AtomicBoolean observedCommittedReservation = new AtomicBoolean();
        secrets.beforeWrite(secretRef -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            BusinessOaSecretCleanupRecord reservation = repository.findBySecretRef(secretRef).orElseThrow();
            assertThat(reservation.state()).isEqualTo(BusinessOaSecretCleanupState.RESERVED);
            observedCommittedReservation.set(true);
        });
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        String[] reservedRef = new String[1];
        outer.executeWithoutResult(status -> {
            reservedRef[0] = service.reserveAndWrite(
                    "auth-reserve-order", 1,
                    "access-order".toCharArray(), "refresh-order".toCharArray(),
                    "LOGIN_STAGE", "operation-order");
            status.setRollbackOnly();
        });
        String secretRef = reservedRef[0];

        assertThat(secretRef).isNotNull();
        assertThat(observedCommittedReservation).isTrue();
        assertThat(secrets.contains(secretRef)).isTrue();
        assertThat(repository.findBySecretRef(secretRef).orElseThrow().state())
                .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
    }

    @Test
    void write_failure_commits_reservation_before_compensating_without_leaking_an_alias() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        String[] reservedRef = new String[1];
        AtomicBoolean observedCommittedReservation = new AtomicBoolean();
        secrets.beforeWrite(secretRef -> {
            BusinessOaSecretCleanupRecord reservation = repository.findBySecretRef(secretRef).orElseThrow();
            assertThat(reservation.authSessionId()).isEqualTo("auth-write-failure");
            assertThat(reservation.state()).isEqualTo(BusinessOaSecretCleanupState.RESERVED);
            reservedRef[0] = secretRef;
            observedCommittedReservation.set(true);
        });
        secrets.failNextWrite();
        BusinessOaSecretCleanupService service = service(secrets);

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> service.reserveAndWrite(
                        "auth-write-failure", 2,
                        "private-access".toCharArray(), "private-refresh".toCharArray(),
                        "LOGIN_STAGE", null));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertSafeFailure(failure, "private-access", "private-refresh", "private-store.jceks", "original cause");
        assertThat(observedCommittedReservation).isTrue();
        assertThat(reservedRef[0]).isNotNull();
        assertThat(secrets.contains(reservedRef[0])).isFalse();
        assertThat(repository.findBySecretRef(reservedRef[0])).isEmpty();
        assertThat(repository.existsByAuthSessionId("auth-write-failure")).isFalse();
    }

    @Test
    void alias_written_before_write_failure_is_durably_scheduled() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        secrets.failNextWriteAfterSave();
        secrets.failNextDelete();
        BusinessOaSecretCleanupService service = service(secrets);

        SecretStoreException failure = catchThrowableOfType(
                SecretStoreException.class,
                () -> service.reserveAndWrite(
                        "auth-write-after-save-failure", 2,
                        "private-access-after-save".toCharArray(),
                        "private-refresh-after-save".toCharArray(),
                        "LOGIN_STAGE", "operation-write-after-save"));

        assertThat(failure.resultCode()).isEqualTo("SECRET_STORE_WRITE_FAILED");
        assertSafeFailure(
                failure,
                "private-access-after-save",
                "private-refresh-after-save",
                "private-store.jceks",
                "original cause");
        List<BusinessOaSecretCleanupRecord> pendingForSession = repository
                .listByState(BusinessOaSecretCleanupState.DELETE_PENDING)
                .stream()
                .filter(record -> record.authSessionId().equals("auth-write-after-save-failure"))
                .toList();
        assertThat(pendingForSession).hasSize(1);
        BusinessOaSecretCleanupRecord pending = pendingForSession.getFirst();
        assertThat(secrets.contains(pending.secretRef())).isTrue();
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertThat(pending.lastAttemptAt()).isEqualTo(NOW);
        assertThat(pending.lastResultCode()).isEqualTo("SECRET_STORE_DELETE_FAILED");

        BusinessOaSecretCleanupService.DrainReport retry = service.drainDeletePending();

        assertThat(retry).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(secrets.contains(pending.secretRef())).isFalse();
        assertThat(repository.findBySecretRef(pending.secretRef())).isEmpty();
    }

    @Test
    void missing_reservation_is_recreated_before_compensation_delete() {
        String authSessionId = "auth-missing-reservation";
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        String secretRef = service.reserveAndWrite(
                authSessionId, 3,
                "missing-reservation-access".toCharArray(),
                "missing-reservation-refresh".toCharArray(),
                "LOGIN_STAGE", "operation-missing-reservation");
        assertThat(repository.consumeReserved(secretRef, authSessionId)).isTrue();
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();

        boolean scheduled = service.scheduleReservedDelete(
                secretRef,
                authSessionId,
                "STAGE_ROLLBACK",
                "operation-missing-reservation");

        assertThat(scheduled).isTrue();
        BusinessOaSecretCleanupRecord pending = repository.findBySecretRef(secretRef).orElseThrow();
        assertThat(pending.authSessionId()).isEqualTo(authSessionId);
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.reasonCode()).isEqualTo("STAGE_ROLLBACK");
        assertThat(pending.operationId()).isEqualTo("operation-missing-reservation");

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(secrets.contains(secretRef)).isFalse();
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();
    }

    @Test
    void reservation_failure_never_writes_the_preallocated_reference() {
        String secretRef = "keystore://business.oa.auth-reservation-conflict";
        repository.upsertReserved(secretRef, "auth-reservation-conflict", "LOGIN_STAGE", null, NOW);
        repository.markDeletePending(
                secretRef, "SESSION_REVOKED", "operation-conflict", NOW.plusSeconds(1));
        ControllableSecretStore secrets = new ControllableSecretStore(secretRef);
        BusinessOaSecretCleanupService service = service(secrets);

        BusinessOaSecretCleanupException failure = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> service.reserveAndWrite(
                        "auth-reservation-conflict", 3,
                        "access-conflict".toCharArray(), "refresh-conflict".toCharArray(),
                        "LOGIN_RETRY", null));

        assertThat(failure.resultCode()).isEqualTo("SECRET_CLEANUP_STATE_CONFLICT");
        assertThat(secrets.writeCalls()).isZero();
        assertThat(secrets.contains(secretRef)).isFalse();
        repository.deleteTombstone(secretRef);
    }

    @Test
    void failed_delete_is_audited_and_the_next_drain_removes_alias_and_tombstone() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        String secretRef = service.reserveAndWrite(
                "auth-delete-retry", 4,
                "delete-access".toCharArray(), "delete-refresh".toCharArray(),
                "LOGIN_STAGE", null);
        assertThat(service.scheduleReservedDelete(
                secretRef, "auth-delete-retry", "SESSION_REVOKED", "operation-delete")).isTrue();
        AtomicBoolean observedDeleteOutsideTransaction = new AtomicBoolean();
        secrets.beforeDelete(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            observedDeleteOutsideTransaction.set(true);
        });
        secrets.failNextDelete();

        BusinessOaSecretCleanupService.DrainReport first = service.drainDeletePending();

        assertThat(first).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 0, 1, 0));
        assertThat(observedDeleteOutsideTransaction).isTrue();
        assertThat(secrets.contains(secretRef)).isTrue();
        BusinessOaSecretCleanupRecord failed = repository.findBySecretRef(secretRef).orElseThrow();
        assertThat(failed.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(failed.attemptCount()).isEqualTo(1);
        assertThat(failed.lastAttemptAt()).isEqualTo(NOW);
        assertThat(failed.lastResultCode()).isEqualTo("SECRET_STORE_DELETE_FAILED");

        BusinessOaSecretCleanupService.DrainReport second = service.drainDeletePending();

        assertThat(second).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(secrets.contains(secretRef)).isFalse();
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();
    }

    @Test
    void strict_drain_targets_released_ref_even_when_it_is_beyond_the_batch_backlog() {
        String suffix = UUID.randomUUID().toString();
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        for (int index = 0; index < 101; index++) {
            String backlogRef = "keystore://business.oa.strict-backlog-" + suffix + "-" + index;
            Instant timestamp = NOW.minusSeconds(200L - index);
            repository.upsertReserved(
                    backlogRef, "auth-backlog-" + suffix, "LOGIN_STAGE", null, timestamp);
            assertThat(repository.markDeletePending(
                    backlogRef, "SESSION_REVOKED", null, timestamp)).isTrue();
        }
        String releasedRef = service.reserveAndWrite(
                "auth-strict-target-" + suffix, 1,
                "strict-access".toCharArray(), "strict-refresh".toCharArray(),
                "LOGIN_STAGE", null);
        assertThat(service.scheduleReservedDelete(
                releasedRef, "auth-strict-target-" + suffix, "SESSION_REVOKED", null)).isTrue();

        service.drainDeletePendingStrict(List.of(releasedRef));

        assertThat(secrets.contains(releasedRef)).isFalse();
        assertThat(repository.findBySecretRef(releasedRef)).isEmpty();
        assertThat(repository.listByState(BusinessOaSecretCleanupState.DELETE_PENDING)).hasSize(101);
        service.drainDeletePending();
        service.drainDeletePending();
    }

    @Test
    void strict_drain_is_fail_visible_and_retains_tombstone_until_retry_succeeds() {
        String suffix = UUID.randomUUID().toString();
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        String releasedRef = service.reserveAndWrite(
                "auth-strict-failure-" + suffix, 1,
                "strict-failure-access".toCharArray(), "strict-failure-refresh".toCharArray(),
                "LOGIN_STAGE", null);
        assertThat(service.scheduleReservedDelete(
                releasedRef, "auth-strict-failure-" + suffix, "SESSION_REVOKED", null)).isTrue();
        secrets.failNextDelete();

        BusinessOaSecretCleanupException failure = catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> service.drainDeletePendingStrict(List.of(releasedRef)));

        assertThat(failure.resultCode()).isEqualTo("SECRET_CLEANUP_INCOMPLETE");
        assertSafeFailure(failure, releasedRef, "strict-failure-access", "strict-failure-refresh");
        assertThat(secrets.contains(releasedRef)).isTrue();
        assertThat(repository.findBySecretRef(releasedRef)).get()
                .extracting(BusinessOaSecretCleanupRecord::state)
                .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);

        service.drainDeletePendingStrict(List.of(releasedRef));

        assertThat(secrets.contains(releasedRef)).isFalse();
        assertThat(repository.findBySecretRef(releasedRef)).isEmpty();
    }

    @Test
    void failure_audit_transaction_does_not_stop_later_pending_cleanup() {
        String suffix = UUID.randomUUID().toString();
        String firstRef = "keystore://business.oa.audit-failure-" + suffix + "-a";
        String secondRef = "keystore://business.oa.audit-failure-" + suffix + "-b";
        ControllableSecretStore secrets = pendingSecrets(firstRef, secondRef);
        BusinessOaSecretCleanupRepository failingRepository = failingRepository();
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "audit failed for " + firstRef + " at C:\\private\\cleanup.db"))
                .when(failingRepository)
                .recordDeleteFailure(
                        org.mockito.ArgumentMatchers.eq(firstRef),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        secrets.failNextDelete();
        BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                failingRepository,
                new OaSessionCredentialStore(secrets),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(2, 1, 1, 0));
        assertThat(secrets.contains(firstRef)).isTrue();
        assertThat(repository.findBySecretRef(firstRef)).isPresent();
        assertThat(secrets.contains(secondRef)).isFalse();
        assertThat(repository.findBySecretRef(secondRef)).isEmpty();
        assertThat(repository.deleteTombstone(firstRef)).isTrue();
        secrets.remove(firstRef);
    }

    @Test
    void tombstone_finalize_transaction_does_not_stop_later_pending_cleanup() {
        String suffix = UUID.randomUUID().toString();
        String firstRef = "keystore://business.oa.finalize-failure-" + suffix + "-a";
        String secondRef = "keystore://business.oa.finalize-failure-" + suffix + "-b";
        ControllableSecretStore secrets = pendingSecrets(firstRef, secondRef);
        BusinessOaSecretCleanupRepository failingRepository = failingRepository();
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "finalize failed for " + firstRef + " at C:\\private\\cleanup.db"))
                .when(failingRepository)
                .deleteTombstone(firstRef);
        BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                failingRepository,
                new OaSessionCredentialStore(secrets),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(2, 1, 1, 0));
        assertThat(secrets.contains(firstRef)).isFalse();
        assertThat(repository.findBySecretRef(firstRef)).isPresent();
        assertThat(secrets.contains(secondRef)).isFalse();
        assertThat(repository.findBySecretRef(secondRef)).isEmpty();
        assertThat(repository.deleteTombstone(firstRef)).isTrue();
    }

    @Test
    void missing_alias_is_idempotent_and_missing_tombstone_is_recreated_before_delete() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        String secretRef = service.reserveAndWrite(
                "auth-missing-alias", 5,
                "missing-access".toCharArray(), "missing-refresh".toCharArray(),
                "LOGIN_STAGE", null);
        secrets.remove(secretRef);
        assertThat(service.scheduleReservedDelete(
                secretRef, "auth-missing-alias", "SESSION_REVOKED", null)).isTrue();

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();
        int deleteCallsAfterTrackedDrain = secrets.deleteCalls();
        boolean scheduledMissing = service.scheduleReservedDelete(
                "keystore://business.oa.auth-no-tombstone",
                "auth-no-tombstone",
                "SESSION_REVOKED",
                null);
        BusinessOaSecretCleanupService.DrainReport empty = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();
        assertThat(scheduledMissing).isTrue();
        assertThat(empty).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(secrets.deleteCalls()).isEqualTo(deleteCallsAfterTrackedDrain + 1);
    }

    @Test
    void concurrent_tombstone_finalize_is_not_reported_as_deleted() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        String secretRef = service.reserveAndWrite(
                "auth-concurrent-finalize", 6,
                "concurrent-access".toCharArray(), "concurrent-refresh".toCharArray(),
                "LOGIN_STAGE", null);
        assertThat(service.scheduleReservedDelete(
                secretRef, "auth-concurrent-finalize", "SESSION_REVOKED",
                "operation-concurrent-finalize")).isTrue();
        secrets.beforeDelete(ignored -> assertThat(repository.deleteTombstone(secretRef)).isTrue());

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.deleted()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.concurrent()).isEqualTo(1);
        assertThat(repository.findBySecretRef(secretRef)).isEmpty();
        assertThat(secrets.contains(secretRef)).isFalse();
    }

    @Test
    void drain_report_rejects_overflowed_component_sum() {
        IllegalArgumentException failure = catchThrowableOfType(
                IllegalArgumentException.class,
                () -> new BusinessOaSecretCleanupService.DrainReport(
                        1, Integer.MAX_VALUE, Integer.MAX_VALUE, 3));

        assertThat(failure).isNotNull();
    }

    @Test
    void drain_processes_at_most_one_hundred_pending_references_per_call() {
        ControllableSecretStore secrets = new ControllableSecretStore();
        BusinessOaSecretCleanupService service = service(secrets);
        for (int index = 0; index < 101; index++) {
            String suffix = String.format("%03d", index);
            String secretRef = "keystore://business.oa.auth-batch-" + suffix;
            Instant updatedAt = NOW.plusSeconds(index);
            repository.upsertReserved(
                    secretRef, "auth-batch-" + suffix, "LOGIN_STAGE", null, updatedAt);
            assertThat(repository.markDeletePending(
                    secretRef, "SESSION_REVOKED", null, updatedAt)).isTrue();
        }

        BusinessOaSecretCleanupService.DrainReport first = service.drainDeletePending();

        assertThat(first).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(100, 100, 0, 0));
        assertThat(repository.listByState(BusinessOaSecretCleanupState.DELETE_PENDING)).hasSize(1);

        BusinessOaSecretCleanupService.DrainReport second = service.drainDeletePending();

        assertThat(second).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(repository.listByState(BusinessOaSecretCleanupState.DELETE_PENDING)).isEmpty();
    }

    @Test
    void drain_reads_and_removes_tombstones_inside_short_transactions() {
        String secretRef = "keystore://business.oa.auth-transaction-boundary";
        repository.upsertReserved(
                secretRef, "auth-transaction-boundary", "LOGIN_STAGE", null, NOW);
        repository.markDeletePending(
                secretRef, "SESSION_REVOKED", "operation-transaction-boundary", NOW);
        TransactionAssertingRepository transactionAssertingRepository =
                new TransactionAssertingRepository(repository);
        BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                transactionAssertingRepository,
                new OaSessionCredentialStore(new ControllableSecretStore()),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 1, 0, 0));
        assertThat(transactionAssertingRepository.listObserved()).isTrue();
        assertThat(transactionAssertingRepository.deleteObserved()).isTrue();
    }

    @Test
    void failed_delete_records_audit_inside_a_short_transaction() {
        String secretRef = "keystore://business.oa.auth-failure-transaction-boundary";
        repository.upsertReserved(
                secretRef, "auth-failure-transaction-boundary", "LOGIN_STAGE", null, NOW);
        repository.markDeletePending(
                secretRef, "SESSION_REVOKED", "operation-failure-transaction-boundary", NOW);
        TransactionAssertingRepository transactionAssertingRepository =
                new TransactionAssertingRepository(repository);
        ControllableSecretStore secrets = new ControllableSecretStore();
        secrets.failNextDelete();
        BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                transactionAssertingRepository,
                new OaSessionCredentialStore(secrets),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessOaSecretCleanupService.DrainReport report = service.drainDeletePending();

        assertThat(report).isEqualTo(new BusinessOaSecretCleanupService.DrainReport(1, 0, 1, 0));
        assertThat(transactionAssertingRepository.listObserved()).isTrue();
        assertThat(transactionAssertingRepository.failureObserved()).isTrue();
        assertThat(transactionAssertingRepository.deleteObserved()).isFalse();
        assertThat(repository.deleteTombstone(secretRef)).isTrue();
    }

    @Test
    void transaction_infrastructure_failures_are_fixed_and_do_not_expose_database_details() {
        BusinessOaSecretCleanupRepository isolatedRepository =
                org.mockito.Mockito.mock(BusinessOaSecretCleanupRepository.class);
        for (PlatformTransactionManager failingManager : List.of(
                new LeakyFailingTransactionManager(true),
                new LeakyFailingTransactionManager(false))) {
            BusinessOaSecretCleanupService service = new BusinessOaSecretCleanupService(
                    isolatedRepository,
                    new OaSessionCredentialStore(new ControllableSecretStore()),
                    failingManager,
                    Clock.fixed(NOW, ZoneOffset.UTC));

            BusinessOaSecretCleanupException failure = catchThrowableOfType(
                    BusinessOaSecretCleanupException.class,
                    () -> service.scheduleReservedDelete(
                            "keystore://business.oa.auth-transaction-failure",
                            "auth-transaction-failure",
                            "SESSION_REVOKED",
                            "operation-transaction-failure"));

            assertThat(failure.resultCode()).isEqualTo("SECRET_CLEANUP_TRANSACTION_FAILED");
            assertSafeFailure(
                    failure,
                    "C:\\private\\cleanup.db",
                    "original transaction cause");
        }
        assertThat(repository.findBySecretRef(
                "keystore://business.oa.auth-transaction-failure")).isEmpty();
    }

    private BusinessOaSecretCleanupService service(ControllableSecretStore secrets) {
        return new BusinessOaSecretCleanupService(
                repository,
                new OaSessionCredentialStore(secrets),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ControllableSecretStore pendingSecrets(String firstRef, String secondRef) {
        ControllableSecretStore secrets = new ControllableSecretStore();
        for (String secretRef : List.of(firstRef, secondRef)) {
            String authSessionId = "auth-" + secretRef.substring(secretRef.length() - 1);
            repository.upsertReserved(
                    secretRef, authSessionId, "LOGIN_STAGE", null, NOW);
            assertThat(repository.markDeletePending(
                    secretRef, "SESSION_REVOKED", null, NOW)).isTrue();
            secrets.saveCharsAtRef(secretRef, "pending-secret".toCharArray());
        }
        return secrets;
    }

    private BusinessOaSecretCleanupRepository failingRepository() {
        return org.mockito.Mockito.mock(
                BusinessOaSecretCleanupRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repository));
    }

    private static void assertSafeFailure(Throwable failure, String... forbiddenValues) {
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        String rendered = failure.getClass().getName() + ":" + failure.getMessage();
        for (String forbidden : forbiddenValues) {
            assertThat(rendered).doesNotContain(forbidden);
        }
    }

    private static final class TransactionAssertingRepository
            implements BusinessOaSecretCleanupRepository {
        private final BusinessOaSecretCleanupRepository delegate;
        private boolean listObserved;
        private boolean deleteObserved;
        private boolean failureObserved;

        private TransactionAssertingRepository(BusinessOaSecretCleanupRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public BusinessOaSecretCleanupRecord upsertReserved(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.upsertReserved(secretRef, authSessionId, reasonCode, operationId, now);
        }

        @Override
        public boolean consumeReserved(String secretRef, String authSessionId) {
            return delegate.consumeReserved(secretRef, authSessionId);
        }

        @Override
        public BusinessOaSecretCleanupRecord upsertDeletePending(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.upsertDeletePending(
                    secretRef, authSessionId, reasonCode, operationId, now);
        }

        @Override
        public Optional<BusinessOaSecretCleanupRecord> findBySecretRef(String secretRef) {
            return delegate.findBySecretRef(secretRef);
        }

        @Override
        public boolean markDeletePending(
                String secretRef,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.markDeletePending(secretRef, reasonCode, operationId, now);
        }

        @Override
        public boolean recordDeleteFailure(String secretRef, String resultCode, Instant attemptedAt) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            failureObserved = true;
            return delegate.recordDeleteFailure(secretRef, resultCode, attemptedAt);
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listByState(BusinessOaSecretCleanupState state) {
            return delegate.listByState(state);
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listDeletePendingBatch(int limit) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            listObserved = true;
            return delegate.listDeletePendingBatch(limit);
        }

        @Override
        public boolean existsByAuthSessionId(String authSessionId) {
            return delegate.existsByAuthSessionId(authSessionId);
        }

        @Override
        public boolean deleteTombstone(String secretRef) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            deleteObserved = true;
            return delegate.deleteTombstone(secretRef);
        }

        private boolean listObserved() {
            return listObserved;
        }

        private boolean deleteObserved() {
            return deleteObserved;
        }

        private boolean failureObserved() {
            return failureObserved;
        }
    }

    private static final class ControllableSecretStore implements SecretStore {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final String fixedRef;
        private int sequence;
        private int writeCalls;
        private int deleteCalls;
        private boolean failNextWrite;
        private boolean failNextWriteAfterSave;
        private boolean failNextDelete;
        private Consumer<String> beforeWrite = ignored -> { };
        private Consumer<String> beforeDelete = ignored -> { };

        private ControllableSecretStore() {
            this(null);
        }

        private ControllableSecretStore(String fixedRef) {
            this.fixedRef = fixedRef;
        }

        @Override
        public String save(String namespace, String secretPlainText) {
            throw new UnsupportedOperationException("legacy save must not be used");
        }

        @Override
        public String allocateRef(String namespace) {
            if (fixedRef != null) return fixedRef;
            sequence++;
            return "keystore://" + namespace + "-" + sequence + "-" + UUID.randomUUID();
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            writeCalls++;
            beforeWrite.accept(secretRef);
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException(
                        "write failed for " + secretRef + " at private-store.jceks: " + new String(secretChars),
                        new IllegalArgumentException("original cause"));
            }
            if (values.containsKey(secretRef)) {
                throw new SecretStoreException(
                        "SECRET_STORE_REFERENCE_EXISTS", "SecretStore 引用已存在");
            }
            values.put(secretRef, new String(secretChars));
            if (failNextWriteAfterSave) {
                failNextWriteAfterSave = false;
                throw new IllegalStateException(
                        "write failed for " + secretRef + " at private-store.jceks: " + new String(secretChars),
                        new IllegalArgumentException("original cause"));
            }
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            return values.keySet().stream().sorted().toList();
        }

        @Override
        public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }

        @Override
        public void delete(String secretRef) {
            deleteCalls++;
            beforeDelete.accept(secretRef);
            if (failNextDelete) {
                failNextDelete = false;
                throw new IllegalStateException(
                        "delete failed for " + secretRef + " at private-store.jceks",
                        new IllegalArgumentException("original cause"));
            }
            values.remove(secretRef);
        }

        void beforeWrite(Consumer<String> callback) {
            beforeWrite = callback;
        }

        void beforeDelete(Consumer<String> callback) {
            beforeDelete = callback;
        }

        void failNextWrite() {
            failNextWrite = true;
        }

        void failNextWriteAfterSave() {
            failNextWriteAfterSave = true;
        }

        void failNextDelete() {
            failNextDelete = true;
        }

        void remove(String secretRef) {
            values.remove(secretRef);
        }

        boolean contains(String secretRef) {
            return values.containsKey(secretRef);
        }

        int writeCalls() {
            return writeCalls;
        }

        int deleteCalls() {
            return deleteCalls;
        }
    }

    private static final class LeakyFailingTransactionManager implements PlatformTransactionManager {
        private final boolean failOnBegin;

        private LeakyFailingTransactionManager(boolean failOnBegin) {
            this.failOnBegin = failOnBegin;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failOnBegin) {
                throw new CannotCreateTransactionException(
                        "cannot open C:\\private\\cleanup.db",
                        new IllegalStateException("original transaction cause"));
            }
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            throw new TransactionSystemException(
                    "cannot commit C:\\private\\cleanup.db",
                    new IllegalStateException("original transaction cause"));
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op: this manager exists only to exercise TransactionTemplate failure sanitization.
        }
    }
}
