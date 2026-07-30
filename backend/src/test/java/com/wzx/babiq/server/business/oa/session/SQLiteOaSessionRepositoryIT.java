package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SQLiteOaSessionRepositoryIT {

    private static final Path TEST_DB = Path.of(
            "target", "test-db", "oa-session-null-clear-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", TEST_DB::toString);
    }

    @Autowired
    private SQLiteOaSessionRepository repository;

    @Autowired
    private SQLiteBusinessOaSecretCleanupRepository cleanupRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void activation_and_detach_clear_nullable_installation_fields_without_deleting_active_secret() {
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        repository.insert(OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = sessions.transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);

        OaSessionRecord staged = persistence.stage(
                "auth-1", authenticating.generation(), connection,
                "new-access".toCharArray(), "new-refresh".toCharArray());
        OaSessionRecord activated = persistence.activate(
                staged.authSessionId(), staged.generation(), staged.installationId(), connection,
                "user-1", "tenant-1", "2");

        OaSessionRecord ready = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(ready.activeCredentialRef()).isEqualTo(activated.activeCredentialRef());
        assertThat(ready.stagedCredentialRef()).isNull();
        assertThat(ready.installationId()).isNull();
        assertThat(ready.installationOwnerDesktopInstanceId()).isNull();
        assertThat(ready.installationOwnerDesktopSessionId()).isNull();
        assertThat(ready.installationTargetGeneration()).isZero();
        assertThat(ready.installationExpiresAt()).isNull();

        sessions.detach(connection);

        OaSessionRecord detached = repository.findByAuthSessionId("auth-1").orElseThrow();
        assertThat(detached.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(detached.stagedCredentialRef()).isNull();
        assertThat(detached.installationId()).isNull();
        assertThat(detached.installationOwnerDesktopInstanceId()).isNull();
        assertThat(detached.installationOwnerDesktopSessionId()).isNull();
        assertThat(detached.installationTargetGeneration()).isZero();
        assertThat(detached.installationExpiresAt()).isNull();
        try (OaSessionCredentialStore.CredentialMaterial material =
                     credentials.load(detached.activeCredentialRef())) {
            assertThat(material).isNotNull();
        }
    }

    @Test
    void concurrent_stage_same_generation_has_exactly_one_winner() throws Exception {
        String authSessionId = "auth-concurrent-" + UUID.randomUUID();
        String desktopSessionId = "session-concurrent-" + UUID.randomUUID();
        TrustedDesktopConnection owner = new TrustedDesktopConnection(
                "reservation-concurrent", "desktop-concurrent", desktopSessionId, "ws-concurrent");
        repository.insert(OaSessionRecord.signedOut(
                authSessionId, owner.desktopInstanceId(), owner.desktopSessionId(), Instant.now()));
        HookedSecretStore secrets = new HookedSecretStore(null, new CyclicBarrier(2));
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);
        OaSessionRecord authenticating = sessions.transition(
                authSessionId, 0, BusinessOaSessionState.AUTHENTICATING);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<OaSessionRecord>> attempts = List.of(
                    executor.submit(() -> persistence.stage(
                            authSessionId, authenticating.generation(), owner,
                            "access-a".toCharArray(), "refresh-a".toCharArray())),
                    executor.submit(() -> persistence.stage(
                            authSessionId, authenticating.generation(), owner,
                            "access-b".toCharArray(), "refresh-b".toCharArray())));

            List<OaSessionRecord> winners = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<OaSessionRecord> attempt : attempts) {
                try {
                    winners.add(attempt.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    failures.add(failure.getCause());
                }
            }

            assertThat(winners).hasSize(1);
            assertThat(failures).hasSize(1);
            assertThat(failures.getFirst())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("OA session generation conflict");
            OaSessionRecord persisted = repository.findByAuthSessionId(authSessionId).orElseThrow();
            assertThat(persisted.phase()).isEqualTo(OaSessionPhase.INSTALLING);
            assertThat(persisted.installationId()).isEqualTo(winners.getFirst().installationId());
            assertThat(persisted.stagedCredentialRef()).isEqualTo(winners.getFirst().stagedCredentialRef());
            assertThat(secrets.references()).containsExactly(persisted.stagedCredentialRef());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exact_stage_cas_rejects_same_generation_snapshot_changes_without_overwrite() {
        List<StageMutation> mutations = List.of(
                new StageMutation("phase", current -> copyStageSource(
                        current, OaSessionPhase.SIGNED_OUT, current.desktopInstanceId(),
                        current.desktopSessionId(), current.activeCredentialRef(), null, null)),
                new StageMutation("desktop instance owner", current -> copyStageSource(
                        current, current.phase(), "desktop-rebound", current.desktopSessionId(),
                        current.activeCredentialRef(), null, null)),
                new StageMutation("desktop session owner", current -> copyStageSource(
                        current, current.phase(), current.desktopInstanceId(), "session-rebound",
                        current.activeCredentialRef(), null, null)),
                new StageMutation("active credential", current -> copyStageSource(
                        current, current.phase(), current.desktopInstanceId(), current.desktopSessionId(),
                        "concurrent-active-ref", null, null)),
                new StageMutation("existing staged credential", current -> copyStageSource(
                        current, current.phase(), current.desktopInstanceId(), current.desktopSessionId(),
                        current.activeCredentialRef(), "concurrent-staged-ref", null)),
                new StageMutation("existing installation", current -> copyStageSource(
                        current, current.phase(), current.desktopInstanceId(), current.desktopSessionId(),
                        current.activeCredentialRef(), null, "concurrent-installation")));

        for (StageMutation mutation : mutations) {
            String suffix = mutation.name().replace(' ', '-');
            String authSessionId = "auth-exact-" + suffix + "-" + UUID.randomUUID();
            String desktopSessionId = "session-exact-" + suffix + "-" + UUID.randomUUID();
            TrustedDesktopConnection owner = new TrustedDesktopConnection(
                    "reservation-exact", "desktop-exact", desktopSessionId, "ws-exact");
            repository.insert(OaSessionRecord.signedOut(
                    authSessionId, owner.desktopInstanceId(), owner.desktopSessionId(), Instant.now()));
            AtomicReference<OaSessionRecord> concurrentSnapshot = new AtomicReference<>();
            HookedSecretStore secrets = new HookedSecretStore(() -> {
                OaSessionRecord current = repository.findByAuthSessionId(authSessionId).orElseThrow();
                OaSessionRecord changed = mutation.change().apply(current);
                repository.update(changed);
                concurrentSnapshot.set(changed);
            }, null);
            OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
            OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
            BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);
            OaSessionRecord authenticating = sessions.transition(
                    authSessionId, 0, BusinessOaSessionState.AUTHENTICATING);

            assertThatThrownBy(() -> persistence.stage(
                    authSessionId, authenticating.generation(), owner,
                    "new-access".toCharArray(), "new-refresh".toCharArray()))
                    .as(mutation.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("OA session generation conflict");

            assertThat(repository.findByAuthSessionId(authSessionId).orElseThrow())
                    .as(mutation.name())
                    .isEqualTo(concurrentSnapshot.get());
            assertThat(secrets.references()).as(mutation.name()).isEmpty();
        }
    }

    @Test
    void exact_cas_rejects_each_owner_reference_and_installation_field_drift() {
        List<String> fields = List.of(
                "phase", "generation", "desktopInstanceId", "desktopSessionId",
                "userId", "tenantId", "platformId",
                "activeCredentialRef", "stagedCredentialRef", "installationId",
                "installationOwnerDesktopInstanceId", "installationOwnerDesktopSessionId",
                "installationTargetGeneration", "installationExpiresAt", "credentialVersion",
                "installStartedAt", "installedAt", "detachedAt", "revokedAt", "updatedAt");

        for (String field : fields) {
            OaSessionRecord expected = exactInstallingRecord("exact-" + field + "-" + UUID.randomUUID());
            OaSessionRecord drifted = driftExactField(expected, field);
            repository.insert(drifted);
            OaSessionRecord next = exactReadyRecord(expected);

            assertThat(repository.compareAndSwapExact(expected, next)).as(field).isFalse();
            assertThat(repository.findByAuthSessionId(expected.authSessionId()))
                    .as(field)
                    .contains(drifted);
        }
    }

    @Test
    void installation_cas_rejects_mismatched_active_or_staged_reference() {
        OaSessionRecord expected = exactInstallingRecord("installation-refs-" + UUID.randomUUID());
        repository.insert(expected);
        OaSessionRecord next = exactReadyRecord(expected);

        assertThat(repository.compareAndSwapInstallation(
                expected.authSessionId(), expected.generation(), expected.installationId(),
                expected.installationOwnerDesktopInstanceId(),
                expected.installationOwnerDesktopSessionId(),
                expected.installationTargetGeneration(),
                "keystore://business.oa.concurrent-active", expected.stagedCredentialRef(), next)).isFalse();
        assertThat(repository.compareAndSwapInstallation(
                expected.authSessionId(), expected.generation(), expected.installationId(),
                expected.installationOwnerDesktopInstanceId(),
                expected.installationOwnerDesktopSessionId(),
                expected.installationTargetGeneration(),
                expected.activeCredentialRef(), "keystore://business.oa.concurrent-staged", next)).isFalse();
        assertThat(repository.compareAndSwapInstallation(
                expected.authSessionId(), expected.generation(), expected.installationId(),
                expected.installationOwnerDesktopInstanceId(),
                expected.installationOwnerDesktopSessionId(),
                expected.installationTargetGeneration(),
                expected.activeCredentialRef(), expected.stagedCredentialRef(), next)).isTrue();
    }

    @Test
    void startup_recovery_closes_authenticating_and_restoring_by_origin() {
        Instant now = Instant.parse("2026-07-28T03:00:00Z");
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);

        String authenticatingId = "auth-recover-authenticating-" + UUID.randomUUID();
        repository.insert(OaSessionRecord.signedOut(
                authenticatingId, "desktop-authenticating", "session-authenticating", now));
        OaSessionRecord authenticating = sessions.transition(
                authenticatingId, 0, BusinessOaSessionState.AUTHENTICATING);

        String restoringId = "auth-recover-restoring-" + UUID.randomUUID();
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                restoringId, 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.insert(OaSessionRecord.ready(
                restoringId, "desktop-restoring", "session-restoring", activeRef, now));
        TrustedDesktopConnection restoringOwner = new TrustedDesktopConnection(
                "reservation-restoring", "desktop-restoring", "session-restoring", "ws-restoring");
        OaSessionRecord detached = sessions.detach(restoringOwner);
        OaSessionRecord restoring = sessions.transition(
                restoringId, detached.generation(), BusinessOaSessionState.RESTORING);

        new BusinessOaSessionRecoveryService(repository, persistence).recover();

        OaSessionRecord closedLogin = repository.findByAuthSessionId(authenticatingId).orElseThrow();
        assertThat(closedLogin.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(closedLogin.generation()).isGreaterThan(authenticating.generation());
        assertInstallationCleared(closedLogin);

        OaSessionRecord closedRestore = repository.findByAuthSessionId(restoringId).orElseThrow();
        assertThat(closedRestore.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(closedRestore.generation()).isGreaterThan(restoring.generation());
        assertThat(closedRestore.activeCredentialRef()).isEqualTo(activeRef);
        assertCredentialReadable(credentials, activeRef);
        assertInstallationCleared(closedRestore);
    }

    @Test
    void startup_recovery_closes_installing_by_credential_origin_and_finishes_revoking() {
        Instant now = Instant.parse("2026-07-28T03:30:00Z");
        DurableOaSessionFixture.MemorySecretStore secrets =
                new DurableOaSessionFixture.MemorySecretStore();
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);

        String loginId = "auth-recover-login-installing-" + UUID.randomUUID();
        TrustedDesktopConnection loginOwner = new TrustedDesktopConnection(
                "reservation-login", "desktop-login", "session-login", "ws-login");
        repository.insert(OaSessionRecord.signedOut(
                loginId, loginOwner.desktopInstanceId(), loginOwner.desktopSessionId(), now));
        OaSessionRecord authenticating = sessions.transition(
                loginId, 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord loginInstalling = persistence.stage(
                loginId, authenticating.generation(), loginOwner,
                "login-access".toCharArray(), "login-refresh".toCharArray());

        String restoreId = "auth-recover-restore-installing-" + UUID.randomUUID();
        TrustedDesktopConnection restoreOwner = new TrustedDesktopConnection(
                "reservation-restore", "desktop-restore", "session-restore", "ws-restore");
        String restoreActiveRef = DurableOaSessionFixture.seedCredential(credentials,
                restoreId, 1, "restore-old-access".toCharArray(), "restore-old-refresh".toCharArray());
        repository.insert(OaSessionRecord.ready(
                restoreId, restoreOwner.desktopInstanceId(), restoreOwner.desktopSessionId(), restoreActiveRef, now));
        OaSessionRecord detached = sessions.detach(restoreOwner);
        OaSessionRecord restoring = sessions.transition(
                restoreId, detached.generation(), BusinessOaSessionState.RESTORING);
        OaSessionRecord restoreInstalling = persistence.stage(
                restoreId, restoring.generation(), restoreOwner,
                "restore-new-access".toCharArray(), "restore-new-refresh".toCharArray());

        String revokingId = "auth-recover-revoking-" + UUID.randomUUID();
        TrustedDesktopConnection revokingOwner = new TrustedDesktopConnection(
                "reservation-revoking", "desktop-revoking", "session-revoking", "ws-revoking");
        String revokingActiveRef = DurableOaSessionFixture.seedCredential(credentials,
                revokingId, 1, "revoking-access".toCharArray(), "revoking-refresh".toCharArray());
        repository.insert(OaSessionRecord.ready(
                revokingId, revokingOwner.desktopInstanceId(), revokingOwner.desktopSessionId(), revokingActiveRef, now));
        OaSessionRecord revoking = sessions.revokeBeforeCleanup(
                revokingOwner, BusinessOaSessionRegistry.RevocationReason.RECOVERY);

        new BusinessOaSessionRecoveryService(repository, persistence).recover();

        OaSessionRecord closedLogin = repository.findByAuthSessionId(loginId).orElseThrow();
        assertThat(closedLogin.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(closedLogin.generation()).isGreaterThan(loginInstalling.generation());
        assertThat(closedLogin.activeCredentialRef()).isNull();
        assertThat(closedLogin.stagedCredentialRef()).isNull();
        assertThat(credentials.load(loginInstalling.stagedCredentialRef())).isNull();
        assertInstallationCleared(closedLogin);

        OaSessionRecord closedRestore = repository.findByAuthSessionId(restoreId).orElseThrow();
        assertThat(closedRestore.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(closedRestore.generation()).isGreaterThan(restoreInstalling.generation());
        assertThat(closedRestore.activeCredentialRef()).isEqualTo(restoreActiveRef);
        assertThat(closedRestore.stagedCredentialRef()).isNull();
        assertCredentialReadable(credentials, restoreActiveRef);
        assertThat(credentials.load(restoreInstalling.stagedCredentialRef())).isNull();
        assertInstallationCleared(closedRestore);

        OaSessionRecord closedRevoking = repository.findByAuthSessionId(revokingId).orElseThrow();
        assertThat(closedRevoking.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(closedRevoking.generation()).isGreaterThan(revoking.generation());
        assertThat(credentials.load(revokingActiveRef)).isNull();
        assertInstallationCleared(closedRevoking);
    }

    @Test
    void startup_recovery_delete_failure_keeps_terminal_session_and_drains_pending_secret_on_retry() {
        HookedSecretStore secrets = new HookedSecretStore(null, null);
        RestoreInstallingFixture fixture = prepareRestoreInstalling("delete-failure", secrets);
        secrets.failNextDeleteBeforeMutation(fixture.installing().stagedCredentialRef());
        RecoveryFaultRepository recoveryRepository = new RecoveryFaultRepository(
                repository, fixture.installing().authSessionId(), false);
        BusinessOaSessionRecoveryService recovery = new BusinessOaSessionRecoveryService(
                recoveryRepository,
                durablePersistence(recoveryRepository, fixture.credentials()));

        BusinessOaSessionRecoveryService.RecoveryReport firstReport = recovery.recover();

        assertThat(firstReport)
                .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(1, 1, 0));
        OaSessionRecord recovered = repository.findByAuthSessionId(
                fixture.installing().authSessionId()).orElseThrow();
        assertThat(recovered.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(recovered.activeCredentialRef()).isEqualTo(fixture.activeCredentialRef());
        assertThat(recovered.stagedCredentialRef()).isNull();
        assertInstallationCleared(recovered);
        assertThat(recoveryRepository.listRecoverable()).isEmpty();
        assertCredentialReadable(fixture.credentials(), fixture.activeCredentialRef());
        assertCredentialReadable(fixture.credentials(), fixture.installing().stagedCredentialRef());
        BusinessOaSecretCleanupRecord pending = cleanupRepository
                .findBySecretRef(fixture.installing().stagedCredentialRef())
                .orElseThrow();
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.attemptCount()).isEqualTo(1);

        BusinessOaSessionRecoveryService.RecoveryReport secondReport = recovery.recover();

        assertThat(secondReport)
                .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(0, 0, 0));
        assertCredentialReadable(fixture.credentials(), fixture.activeCredentialRef());
        assertThat(fixture.credentials().load(fixture.installing().stagedCredentialRef())).isNull();
        assertThat(cleanupRepository.findBySecretRef(fixture.installing().stagedCredentialRef())).isEmpty();
    }

    @Test
    void startup_recovery_exact_cas_failure_is_rescannable_without_ready_or_credential_deletion() {
        HookedSecretStore secrets = new HookedSecretStore(null, null);
        RestoreInstallingFixture fixture = prepareRestoreInstalling("final-cas-failure", secrets);
        RecoveryFaultRepository recoveryRepository = new RecoveryFaultRepository(
                repository, fixture.installing().authSessionId(), true);
        BusinessOaSessionRecoveryService recovery = new BusinessOaSessionRecoveryService(
                recoveryRepository,
                durablePersistence(recoveryRepository, fixture.credentials()));

        BusinessOaSessionRecoveryService.RecoveryReport firstReport = recovery.recover();

        assertThat(firstReport)
                .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(1, 0, 1));
        assertThat(recoveryRepository.recoveryExactCasCalls()).isEqualTo(1);
        OaSessionRecord failed = repository.findByAuthSessionId(
                fixture.installing().authSessionId()).orElseThrow();
        assertThat(failed).isEqualTo(fixture.installing());
        assertThat(recoveryRepository.listRecoverable())
                .extracting(OaSessionRecord::authSessionId)
                .containsExactly(fixture.installing().authSessionId());
        assertCredentialReadable(fixture.credentials(), fixture.activeCredentialRef());
        assertCredentialReadable(fixture.credentials(), fixture.installing().stagedCredentialRef());
        assertThat(cleanupRepository.findBySecretRef(fixture.installing().stagedCredentialRef())).isEmpty();

        BusinessOaSessionRecoveryService.RecoveryReport secondReport = recovery.recover();

        assertThat(secondReport)
                .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(1, 1, 0));
        assertThat(recoveryRepository.recoveryExactCasCalls()).isEqualTo(2);
        OaSessionRecord recovered = repository.findByAuthSessionId(
                fixture.installing().authSessionId()).orElseThrow();
        assertThat(recovered.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(recovered.activeCredentialRef()).isEqualTo(fixture.activeCredentialRef());
        assertThat(recovered.stagedCredentialRef()).isNull();
        assertCredentialReadable(fixture.credentials(), fixture.activeCredentialRef());
        assertThat(fixture.credentials().load(fixture.installing().stagedCredentialRef())).isNull();
        assertThat(cleanupRepository.findBySecretRef(fixture.installing().stagedCredentialRef())).isEmpty();
        assertInstallationCleared(recovered);
    }

    private RestoreInstallingFixture prepareRestoreInstalling(String name, HookedSecretStore secrets) {
        Instant now = Instant.parse("2026-07-28T04:00:00Z");
        String authSessionId = "auth-recover-" + name + "-" + UUID.randomUUID();
        String desktopSessionId = "session-recover-" + name + "-" + UUID.randomUUID();
        TrustedDesktopConnection owner = new TrustedDesktopConnection(
                "reservation-recover-" + name, "desktop-recover-" + name,
                desktopSessionId, "ws-recover-" + name);
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        String activeCredentialRef = DurableOaSessionFixture.seedCredential(credentials,
                authSessionId, 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.insert(OaSessionRecord.ready(
                authSessionId, owner.desktopInstanceId(), owner.desktopSessionId(),
                activeCredentialRef, now));
        OaSessionPersistenceService persistence = durablePersistence(repository, credentials);
        BusinessOaSessionRegistry sessions = new BusinessOaSessionRegistry(repository, persistence);
        OaSessionRecord detached = sessions.detach(owner);
        OaSessionRecord restoring = sessions.transition(
                authSessionId, detached.generation(), BusinessOaSessionState.RESTORING);
        OaSessionRecord installing = persistence.stage(
                authSessionId, restoring.generation(), owner,
                "new-access".toCharArray(), "new-refresh".toCharArray());
        return new RestoreInstallingFixture(credentials, installing, activeCredentialRef);
    }

    private OaSessionPersistenceService durablePersistence(
            OaSessionRepository sessionRepository,
            OaSessionCredentialStore credentials) {
        Clock clock = Clock.systemUTC();
        BusinessOaSecretCleanupService cleanupService = new BusinessOaSecretCleanupService(
                cleanupRepository, credentials, transactionManager, clock);
        return new OaSessionPersistenceService(
                sessionRepository,
                cleanupRepository,
                cleanupService,
                transactionManager,
                clock);
    }

    private static OaSessionRecord copyStageSource(
            OaSessionRecord source,
            OaSessionPhase phase,
            String desktopInstanceId,
            String desktopSessionId,
            String activeCredentialRef,
            String stagedCredentialRef,
            String installationId) {
        return new OaSessionRecord(
                source.authSessionId(), desktopInstanceId, desktopSessionId,
                source.userId(), source.tenantId(), source.platformId(), phase, source.generation(),
                activeCredentialRef, stagedCredentialRef, source.credentialVersion(),
                source.installStartedAt(), source.installedAt(), source.detachedAt(), source.revokedAt(),
                source.updatedAt(), installationId,
                installationId == null ? null : desktopInstanceId,
                installationId == null ? null : desktopSessionId,
                installationId == null ? 0 : source.generation(),
                installationId == null ? null : source.updatedAt().plusSeconds(90));
    }

    private static OaSessionRecord exactInstallingRecord(String suffix) {
        Instant now = Instant.parse("2026-07-28T04:30:00Z");
        return new OaSessionRecord(
                "auth-" + suffix, "desktop-" + suffix, "session-" + suffix,
                "user-1", "tenant-1", "2", OaSessionPhase.INSTALLING, 7,
                "keystore://business.oa.active-" + suffix,
                "keystore://business.oa.staged-" + suffix,
                2, now.minusSeconds(1), now.minusSeconds(60), null, null, now,
                "installation-" + suffix, "desktop-" + suffix, "session-" + suffix,
                7, now.plusSeconds(90));
    }

    private static OaSessionRecord exactReadyRecord(OaSessionRecord source) {
        return new OaSessionRecord(
                source.authSessionId(), source.desktopInstanceId(), source.desktopSessionId(),
                source.userId(), source.tenantId(), source.platformId(), OaSessionPhase.READY,
                source.generation() + 1, source.stagedCredentialRef(), null,
                source.credentialVersion(), source.installStartedAt(), source.updatedAt(),
                null, null, source.updatedAt().plusSeconds(1), null, null, null, 0, null);
    }

    private static OaSessionRecord driftExactField(OaSessionRecord source, String field) {
        return new OaSessionRecord(
                source.authSessionId(),
                field.equals("desktopInstanceId") ? source.desktopInstanceId() + "-other" : source.desktopInstanceId(),
                field.equals("desktopSessionId") ? source.desktopSessionId() + "-other" : source.desktopSessionId(),
                field.equals("userId") ? source.userId() + "-other" : source.userId(),
                field.equals("tenantId") ? source.tenantId() + "-other" : source.tenantId(),
                field.equals("platformId") ? source.platformId() + "-other" : source.platformId(),
                field.equals("phase") ? OaSessionPhase.RESTORING : source.phase(),
                field.equals("generation") ? source.generation() + 1 : source.generation(),
                field.equals("activeCredentialRef") ? source.activeCredentialRef() + "-other" : source.activeCredentialRef(),
                field.equals("stagedCredentialRef") ? source.stagedCredentialRef() + "-other" : source.stagedCredentialRef(),
                field.equals("credentialVersion") ? source.credentialVersion() + 1 : source.credentialVersion(),
                field.equals("installStartedAt") ? source.installStartedAt().plusSeconds(1) : source.installStartedAt(),
                field.equals("installedAt") ? source.installedAt().plusSeconds(1) : source.installedAt(),
                field.equals("detachedAt") ? source.updatedAt().minusSeconds(2) : source.detachedAt(),
                field.equals("revokedAt") ? source.updatedAt().minusSeconds(1) : source.revokedAt(),
                field.equals("updatedAt") ? source.updatedAt().plusSeconds(1) : source.updatedAt(),
                field.equals("installationId") ? source.installationId() + "-other" : source.installationId(),
                field.equals("installationOwnerDesktopInstanceId")
                        ? source.installationOwnerDesktopInstanceId() + "-other"
                        : source.installationOwnerDesktopInstanceId(),
                field.equals("installationOwnerDesktopSessionId")
                        ? source.installationOwnerDesktopSessionId() + "-other"
                        : source.installationOwnerDesktopSessionId(),
                field.equals("installationTargetGeneration")
                        ? source.installationTargetGeneration() + 1
                        : source.installationTargetGeneration(),
                field.equals("installationExpiresAt")
                        ? source.installationExpiresAt().plusSeconds(1)
                        : source.installationExpiresAt());
    }

    private static void assertInstallationCleared(OaSessionRecord record) {
        assertThat(record.installStartedAt()).isNull();
        assertInstallationLeaseCleared(record);
    }

    private static void assertInstallationLeaseCleared(OaSessionRecord record) {
        assertThat(record.installationId()).isNull();
        assertThat(record.installationOwnerDesktopInstanceId()).isNull();
        assertThat(record.installationOwnerDesktopSessionId()).isNull();
        assertThat(record.installationTargetGeneration()).isZero();
        assertThat(record.installationExpiresAt()).isNull();
    }

    private static void assertCredentialReadable(OaSessionCredentialStore credentials, String credentialRef) {
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(credentialRef)) {
            assertThat(material).isNotNull();
        }
    }

    private record StageMutation(String name, UnaryOperator<OaSessionRecord> change) {
    }

    private record RestoreInstallingFixture(
            OaSessionCredentialStore credentials,
            OaSessionRecord installing,
            String activeCredentialRef) {
    }

    private static final class RecoveryFaultRepository implements OaSessionRepository {
        private final SQLiteOaSessionRepository delegate;
        private final String targetAuthSessionId;
        private final AtomicInteger recoveryExactCasCalls = new AtomicInteger();
        private final boolean failRecoveryCas;

        private RecoveryFaultRepository(
                SQLiteOaSessionRepository delegate,
                String targetAuthSessionId,
                boolean failRecoveryCas) {
            this.delegate = delegate;
            this.targetAuthSessionId = targetAuthSessionId;
            this.failRecoveryCas = failRecoveryCas;
        }

        @Override
        public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return delegate.findByAuthSessionId(authSessionId);
        }

        @Override
        public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId,
                String desktopSessionId) {
            return delegate.findByDesktopSession(desktopInstanceId, desktopSessionId);
        }

        @Override
        public OaSessionRecord insert(OaSessionRecord record) {
            return delegate.insert(record);
        }

        @Override
        public OaSessionRecord update(OaSessionRecord record) {
            return delegate.update(record);
        }

        @Override
        public boolean compareAndSwapGeneration(
                String authSessionId,
                long expectedGeneration,
                OaSessionRecord record) {
            return delegate.compareAndSwapGeneration(authSessionId, expectedGeneration, record);
        }

        @Override
        public boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
            if (expected.authSessionId().equals(targetAuthSessionId)) {
                int call = recoveryExactCasCalls.incrementAndGet();
                if (failRecoveryCas && call == 1) return false;
            }
            return delegate.compareAndSwapExact(expected, next);
        }

        @Override
        public boolean compareAndSwapRecoverySnapshot(
                String authSessionId,
                OaSessionPhase expectedPhase,
                long expectedGeneration,
                String expectedInstallationId,
                String expectedActiveCredentialRef,
                String expectedStagedCredentialRef,
                OaSessionRecord record) {
            return delegate.compareAndSwapRecoverySnapshot(
                    authSessionId, expectedPhase, expectedGeneration, expectedInstallationId,
                    expectedActiveCredentialRef, expectedStagedCredentialRef, record);
        }

        @Override
        public List<OaSessionRecord> listRecoverable() {
            return delegate.listRecoverable().stream()
                    .filter(record -> record.authSessionId().equals(targetAuthSessionId))
                    .toList();
        }

        private int recoveryExactCasCalls() {
            return recoveryExactCasCalls.get();
        }
    }

    private static final class HookedSecretStore implements SecretStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicReference<String> failDeleteRef = new AtomicReference<>();
        private final Runnable afterSave;
        private final CyclicBarrier barrier;

        private HookedSecretStore(Runnable afterSave, CyclicBarrier barrier) {
            this.afterSave = afterSave;
            this.barrier = barrier;
        }

        @Override
        public String allocateRef(String namespace) {
            return "keystore://" + namespace + "/" + sequence.getAndIncrement();
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            String previous = values.putIfAbsent(secretRef, new String(secretChars));
            if (previous != null) {
                throw new IllegalStateException("test secret reference already exists");
            }
            afterSave();
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            String prefix = "keystore://" + namespacePrefix;
            return values.keySet().stream()
                    .filter(ref -> ref.startsWith(prefix))
                    .sorted()
                    .toList();
        }

        @Override
        public String save(String namespace, String secretPlainText) {
            String ref = allocateRef(namespace);
            saveCharsAtRef(ref, secretPlainText.toCharArray());
            return ref;
        }

        @Override
        public String saveChars(String namespace, char[] secretChars) {
            return save(namespace, new String(secretChars));
        }

        @Override
        public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }

        @Override
        public void delete(String secretRef) {
            String expectedFailure = failDeleteRef.get();
            if (expectedFailure != null
                    && expectedFailure.equals(secretRef)
                    && failDeleteRef.compareAndSet(expectedFailure, null)) {
                throw new IllegalStateException("injected delete failure before mutation");
            }
            values.remove(secretRef);
        }

        private void failNextDeleteBeforeMutation(String secretRef) {
            failDeleteRef.set(secretRef);
        }

        private Set<String> references() {
            return Set.copyOf(values.keySet());
        }

        private void afterSave() {
            if (afterSave != null) afterSave.run();
            if (barrier == null) return;
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("test barrier failed", exception);
            }
        }
    }
}
