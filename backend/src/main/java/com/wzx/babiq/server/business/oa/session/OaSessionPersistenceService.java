package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 协调 OA 会话索引与凭据引用，确保 staged 凭据只有在 CAS 成功后成为 active。 */
public final class OaSessionPersistenceService {
    private static final Logger log = LoggerFactory.getLogger(OaSessionPersistenceService.class);
    public static final Duration INSTALLATION_TTL = Duration.ofSeconds(90);
    private static final int LIFECYCLE_CAS_ATTEMPTS = 4;
    private final OaSessionRepository repository;
    private final BusinessOaSecretCleanupRepository cleanupRepository;
    private final BusinessOaSecretCleanupService cleanupService;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    /**
     * 构造生产凭据生命周期协调器；SQLite 状态在短事务内提交，JCEKS I/O 由 cleanup service 放在事务外。
     */
    public OaSessionPersistenceService(
            OaSessionRepository repository,
            BusinessOaSecretCleanupRepository cleanupRepository,
            BusinessOaSecretCleanupService cleanupService,
            PlatformTransactionManager transactionManager) {
        this(repository, cleanupRepository, cleanupService, transactionManager, Clock.systemUTC());
    }

    OaSessionPersistenceService(
            OaSessionRepository repository,
            BusinessOaSecretCleanupRepository cleanupRepository,
            BusinessOaSecretCleanupService cleanupService,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OaSessionRecord stage(String authSessionId, long expectedGeneration,
                                 char[] accessToken, char[] refreshToken) {
        OaSessionRecord current = required(authSessionId);
        TrustedDesktopConnection owner = new TrustedDesktopConnection("server-persistence",
                current.desktopInstanceId(), current.desktopSessionId(), "server-persistence");
        return stage(authSessionId, expectedGeneration, owner, accessToken, refreshToken);
    }

    public OaSessionRecord stage(String authSessionId, long expectedGeneration,
                                 TrustedDesktopConnection owner,
                                 char[] accessToken, char[] refreshToken) {
        return stage(authSessionId, expectedGeneration, owner, null, accessToken, refreshToken);
    }

    public OaSessionRecord stage(String authSessionId, long expectedGeneration,
                                 TrustedDesktopConnection owner,
                                 Instant absoluteDeadline,
                                 char[] accessToken, char[] refreshToken) {
        Objects.requireNonNull(owner, "owner");
        OaSessionRecord current = required(authSessionId);
        if (current.phase() == OaSessionPhase.REVOKED || current.revokedAt() != null) {
            throw new IllegalStateException("OA session is revoked");
        }
        if (current.generation() != expectedGeneration) throw new IllegalStateException("OA session generation conflict");
        requireOwner(current, owner);
        if (!isStageSourcePhase(current.phase())
                || current.stagedCredentialRef() != null
                || current.installationId() != null
                || current.installationOwnerDesktopInstanceId() != null
                || current.installationOwnerDesktopSessionId() != null
                || current.installationTargetGeneration() != 0
                || current.installationExpiresAt() != null) {
            throw new IllegalStateException("OA session cannot stage credentials");
        }
        Instant now = clock.instant();
        Instant installationExpiresAt = now.plus(INSTALLATION_TTL);
        if (absoluteDeadline != null) {
            if (!now.isBefore(absoluteDeadline)) {
                throw new IllegalStateException("OA installation expired");
            }
            if (absoluteDeadline.isBefore(installationExpiresAt)) {
                installationExpiresAt = absoluteDeadline;
            }
        }
        String installationId = UUID.randomUUID().toString();
        String stagedRef = cleanupService.reserveAndWrite(
                authSessionId,
                current.credentialVersion() + 1,
                accessToken,
                refreshToken,
                "SESSION_STAGE",
                installationId);
        OaSessionRecord next = new OaSessionRecord(current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                current.userId(), current.tenantId(), current.platformId(), OaSessionPhase.INSTALLING, current.generation(),
                current.activeCredentialRef(), stagedRef, current.credentialVersion() + 1, now,
                current.installedAt(), current.detachedAt(), current.revokedAt(), now, installationId,
                owner.desktopInstanceId(), owner.desktopSessionId(), expectedGeneration,
                installationExpiresAt);
        try {
            OaSessionRecord committed = requiresNew.execute(status -> {
                if (!repository.compareAndSwapExact(current, next)) {
                    throw new IllegalStateException("OA session generation conflict");
                }
                if (!cleanupRepository.consumeReserved(stagedRef, authSessionId)) {
                    throw new IllegalStateException("OA credential reservation conflict");
                }
                return next;
            });
            if (committed == null) {
                throw new IllegalStateException("OA session transaction returned no result");
            }
            return committed;
        } catch (RuntimeException failure) {
            compensateUnlinkedCredential(
                    stagedRef, authSessionId, installationId);
            throw failure;
        }
    }

    /** CAS 失败时只登记耐久删除并尽力 drain，不得用补偿失败覆盖原始并发错误。 */
    private void compensateUnlinkedCredential(
            String stagedRef,
            String authSessionId,
            String operationId) {
        try {
            cleanupService.scheduleReservedDelete(
                    stagedRef,
                    authSessionId,
                    "STAGE_ROLLBACK",
                    operationId);
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            cleanupService.drainDeletePending();
        } catch (RuntimeException ignored) {
            // RESERVED/DELETE_PENDING journal 已耐久化，后续 drain 可以重试。
        }
    }

    /** Moves one exact durable snapshot to a regular lifecycle phase without losing concurrent stage data. */
    public OaSessionRecord transition(
            String authSessionId,
            long expectedGeneration,
            OaSessionPhase target) {
        Objects.requireNonNull(target, "target");
        OaSessionRecord current = required(authSessionId);
        if (current.generation() != expectedGeneration || !isAllowed(current.phase(), target)) {
            throw new IllegalStateException("invalid OA session transition");
        }
        Instant now = clock.instant();
        OaSessionRecord next = copy(
                current,
                target,
                expectedGeneration + 1,
                current.userId(),
                current.tenantId(),
                current.platformId(),
                current.activeCredentialRef(),
                current.stagedCredentialRef(),
                current.detachedAt(),
                target == OaSessionPhase.REVOKED ? now : current.revokedAt(),
                now);
        if (!commitLifecycleTransition(current, next, "SESSION_TRANSITION", current.installationId())) {
            throw new IllegalStateException("OA session generation conflict");
        }
        drainReleasedCredentialRefsBestEffort(current, next);
        return next;
    }

    /** Closes the durable gate with an exact CAS while preserving credentials owned by a concurrent stage. */
    public OaSessionRecord beginRevocation(TrustedDesktopConnection connection) {
        Objects.requireNonNull(connection, "connection");
        return beginRevocation(connection, required(connection));
    }

    /**
     * Closes only the logout target captured by the caller. A CAS retry may follow that
     * target through one credential stage/activation, but must never cross a completed
     * revocation into a later login that reused the desktop session slot.
     */
    public OaSessionRecord beginRevocation(
            TrustedDesktopConnection connection,
            OaSessionRecord expectedTarget) {
        return claimRevocation(connection, expectedTarget).record();
    }

    /** Claims the exact transition into REVOKING and identifies its single CAS winner. */
    public RevocationTransition claimRevocation(
            TrustedDesktopConnection connection,
            OaSessionRecord expectedTarget) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        requireOwner(expectedTarget, connection);
        OaSessionRecord current = expectedTarget;
        String authSessionId = expectedTarget.authSessionId();
        for (int attempt = 0; attempt < LIFECYCLE_CAS_ATTEMPTS; attempt++) {
            if (!belongsToRevocationTarget(expectedTarget, current)) {
                throw new IllegalStateException("BUSINESS_SESSION_STALE");
            }
            if (current.phase() == OaSessionPhase.REVOKED
                    || current.phase() == OaSessionPhase.REVOKING
                    || current.phase() == OaSessionPhase.SIGNED_OUT) {
                return new RevocationTransition(current, false);
            }
            Instant now = clock.instant();
            OaSessionRecord revoking = copy(
                    current,
                    OaSessionPhase.REVOKING,
                    current.generation() + 1,
                    current.userId(),
                    current.tenantId(),
                    current.platformId(),
                    current.activeCredentialRef(),
                    current.stagedCredentialRef(),
                    current.detachedAt(),
                    current.revokedAt(),
                    now);
            if (commitLifecycleTransition(
                    current, revoking, "SESSION_REVOKING", current.installationId())) {
                return new RevocationTransition(revoking, true);
            }
            current = currentOwned(authSessionId, connection);
        }
        throw new IllegalStateException("OA session generation conflict");
    }

    public record RevocationTransition(OaSessionRecord record, boolean winner) {
        public RevocationTransition {
            Objects.requireNonNull(record, "record");
        }
    }

    private static boolean belongsToRevocationTarget(
            OaSessionRecord expected,
            OaSessionRecord current) {
        if (!expected.authSessionId().equals(current.authSessionId())
                || !expected.desktopInstanceId().equals(current.desktopInstanceId())
                || !expected.desktopSessionId().equals(current.desktopSessionId())) {
            return false;
        }
        if (expected.equals(current)) {
            return true;
        }
        long generationDelta = current.generation() - expected.generation();
        if (expected.phase() == OaSessionPhase.READY) {
            return belongsToReadyRevocationTarget(expected, current, generationDelta);
        }
        if (current.phase() == OaSessionPhase.REVOKING
                || current.phase() == OaSessionPhase.REVOKED
                || current.phase() == OaSessionPhase.SIGNED_OUT) {
            return generationDelta >= 0 && generationDelta <= 2;
        }
        if (current.phase() == OaSessionPhase.INSTALLING
                && generationDelta == 0
                && current.installationTargetGeneration() == expected.generation()) {
            return expected.installationId() == null
                    || expected.installationId().equals(current.installationId());
        }
        if (current.phase() != OaSessionPhase.READY || generationDelta != 1) {
            return false;
        }
        if (expected.phase() == OaSessionPhase.INSTALLING) {
            return Objects.equals(expected.stagedCredentialRef(), current.activeCredentialRef())
                    && expected.credentialVersion() == current.credentialVersion();
        }
        return false;
    }

    private static boolean belongsToReadyRevocationTarget(
            OaSessionRecord expected,
            OaSessionRecord current,
            long generationDelta) {
        int credentialVersionDelta = current.credentialVersion() - expected.credentialVersion();
        if (generationDelta < 0 || credentialVersionDelta < 0 || credentialVersionDelta > 1) {
            return false;
        }
        boolean sameActiveCredential = Objects.equals(
                expected.activeCredentialRef(), current.activeCredentialRef());
        boolean refreshedActiveCredential = credentialVersionDelta == 1
                && expected.activeCredentialRef() != null
                && current.activeCredentialRef() != null
                && !sameActiveCredential;
        return switch (current.phase()) {
            case INSTALLING -> generationDelta == 0
                    && credentialVersionDelta == 1
                    && sameActiveCredential
                    && current.stagedCredentialRef() != null
                    && current.installationId() != null
                    && expected.desktopInstanceId().equals(
                    current.installationOwnerDesktopInstanceId())
                    && expected.desktopSessionId().equals(
                    current.installationOwnerDesktopSessionId())
                    && current.installationTargetGeneration() == expected.generation();
            case READY -> generationDelta == 1
                    && refreshedActiveCredential
                    && current.stagedCredentialRef() == null
                    && current.installationId() == null;
            case DETACHED -> current.stagedCredentialRef() == null
                    && current.installationId() == null
                    && ((generationDelta == 1
                    && credentialVersionDelta == 0
                    && sameActiveCredential)
                    || (generationDelta == 1
                    && credentialVersionDelta == 1
                    && sameActiveCredential)
                    || (generationDelta == 2 && refreshedActiveCredential));
            case REVOKING -> belongsToReadyRevokingLineage(
                    current, generationDelta, credentialVersionDelta,
                    sameActiveCredential, refreshedActiveCredential);
            case REVOKED -> belongsToReadyRevokedLineage(
                    current, generationDelta, credentialVersionDelta,
                    sameActiveCredential, refreshedActiveCredential);
            case SIGNED_OUT -> current.activeCredentialRef() == null
                    && current.stagedCredentialRef() == null
                    && ((credentialVersionDelta == 0
                    && generationDelta >= 2 && generationDelta <= 3)
                    || (credentialVersionDelta == 1
                    && generationDelta >= 2 && generationDelta <= 4));
            case AUTHENTICATING, RESTORING -> false;
        };
    }

    private static boolean belongsToReadyRevokingLineage(
            OaSessionRecord current,
            long generationDelta,
            int credentialVersionDelta,
            boolean sameActiveCredential,
            boolean refreshedActiveCredential) {
        if (credentialVersionDelta == 0) {
            return generationDelta >= 1 && generationDelta <= 2
                    && sameActiveCredential
                    && current.stagedCredentialRef() == null;
        }
        return (generationDelta == 1
                && sameActiveCredential
                && current.stagedCredentialRef() != null)
                || (generationDelta == 2
                && sameActiveCredential
                && current.stagedCredentialRef() == null)
                || (generationDelta >= 2 && generationDelta <= 3
                && refreshedActiveCredential
                && current.stagedCredentialRef() == null);
    }

    private static boolean belongsToReadyRevokedLineage(
            OaSessionRecord current,
            long generationDelta,
            int credentialVersionDelta,
            boolean sameActiveCredential,
            boolean refreshedActiveCredential) {
        if (credentialVersionDelta == 0) {
            return generationDelta >= 2 && generationDelta <= 3
                    && sameActiveCredential
                    && current.stagedCredentialRef() == null;
        }
        return (generationDelta == 2
                && sameActiveCredential
                && current.stagedCredentialRef() != null)
                || (generationDelta == 3
                && sameActiveCredential
                && current.stagedCredentialRef() == null)
                || (generationDelta >= 3 && generationDelta <= 4
                && refreshedActiveCredential
                && current.stagedCredentialRef() == null);
    }

    /** 启动恢复时按中断来源收束会话，避免半安装凭据或旧 READY 被重新发布。 */
    public OaSessionRecord recoverInstalling(OaSessionRecord record) {
        OaSessionRecord recovered = recoverInstallingBeforeCleanup(record);
        drainReleasedCredentialRefsBestEffort(record, recovered);
        return recovered;
    }

    OaSessionRecord recoverInstallingBeforeCleanup(OaSessionRecord record) {
        if (record.phase() != OaSessionPhase.AUTHENTICATING
                && record.phase() != OaSessionPhase.RESTORING
                && record.phase() != OaSessionPhase.INSTALLING
                && record.phase() != OaSessionPhase.REVOKING) {
            return record;
        }
        return recoverWithDurableCleanup(record);
    }

    /**
     * Startup-only reconciliation for reservations left behind before a session reference was committed.
     * Each row is independent so one persistence failure cannot strand the remaining orphan reservations.
     */
    void reconcileOrphanReservedCredentials() {
        List<BusinessOaSecretCleanupRecord> reserved;
        try {
            reserved = cleanupRepository.listByState(BusinessOaSecretCleanupState.RESERVED);
        } catch (RuntimeException failure) {
            log.warn("OA orphan credential reconciliation scan failed: reasonType={}",
                    failure.getClass().getSimpleName());
            return;
        }
        for (BusinessOaSecretCleanupRecord record : reserved) {
            try {
                if (repository.existsCredentialReference(record.secretRef())) {
                    continue;
                }
                cleanupRepository.markReservedDeletePending(
                        record.secretRef(),
                        record.authSessionId(),
                        "RECOVERY_ORPHAN_RESERVED",
                        record.operationId(),
                        clock.instant());
            } catch (RuntimeException failure) {
                log.warn("OA orphan credential reconciliation failed: reasonType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** 从一个完整中断快照直接收口到终态，并在同一事务登记其释放的凭据引用。 */
    private OaSessionRecord recoverWithDurableCleanup(OaSessionRecord record) {
        Instant now = clock.instant();
        boolean preserveActive = record.phase() == OaSessionPhase.RESTORING
                || (record.phase() == OaSessionPhase.INSTALLING
                && record.activeCredentialRef() != null);
        OaSessionRecord recovered = new OaSessionRecord(
                record.authSessionId(), record.desktopInstanceId(), record.desktopSessionId(),
                preserveActive ? record.userId() : null,
                preserveActive ? record.tenantId() : null,
                preserveActive ? record.platformId() : null,
                preserveActive ? OaSessionPhase.DETACHED : OaSessionPhase.SIGNED_OUT,
                record.generation() + 1,
                preserveActive ? record.activeCredentialRef() : null,
                null,
                record.credentialVersion(),
                null,
                record.installedAt(),
                preserveActive ? now : null,
                null,
                now,
                null, null, null, 0, null);
        OaSessionRecord committed = requiresNew.execute(status -> {
            if (!repository.compareAndSwapExact(record, recovered)) {
                throw new IllegalStateException("OA session recovery generation conflict");
            }
            if (!preserveActive) {
                journalDelete(record.activeCredentialRef(), record.authSessionId(),
                        "RECOVERY_CLEANUP", record.installationId(), now);
            }
            if (!Objects.equals(record.stagedCredentialRef(),
                    preserveActive ? record.activeCredentialRef() : null)) {
                journalDelete(record.stagedCredentialRef(), record.authSessionId(),
                        "RECOVERY_CLEANUP", record.installationId(), now);
            }
            return recovered;
        });
        if (committed == null) {
            throw new IllegalStateException("OA session transaction returned no result");
        }
        return committed;
    }

    private void journalDelete(
            String secretRef,
            String authSessionId,
            String reasonCode,
            String operationId,
            Instant now) {
        if (secretRef != null) {
            cleanupRepository.upsertDeletePending(
                    secretRef, authSessionId, reasonCode, operationId, now);
        }
    }

    public OaSessionRecord activate(String authSessionId, long expectedGeneration,
                                    String userId, String tenantId, String platformId) {
        OaSessionRecord current = required(authSessionId);
        TrustedDesktopConnection owner = new TrustedDesktopConnection("server-persistence",
                current.desktopInstanceId(), current.desktopSessionId(), "server-persistence");
        return activate(authSessionId, expectedGeneration, current.installationId(), owner,
                userId, tenantId, platformId);
    }

    public OaSessionRecord activate(String authSessionId, long expectedGeneration,
                                    String installationId, TrustedDesktopConnection owner,
                                    String userId, String tenantId, String platformId) {
        Objects.requireNonNull(owner, "owner");
        OaSessionRecord current = required(authSessionId);
        if (current.phase() == OaSessionPhase.REVOKED || current.revokedAt() != null) {
            throw new IllegalStateException("OA session is revoked");
        }
        if (current.phase() != OaSessionPhase.INSTALLING
                || current.generation() != expectedGeneration
                || current.stagedCredentialRef() == null) {
            throw new IllegalStateException("OA session installation is stale");
        }
        if (!Objects.equals(current.installationId(), installationId)) {
            throw new IllegalStateException("OA installation id mismatch");
        }
        requireInstallationOwner(current, owner);
        if (current.installationTargetGeneration() != expectedGeneration) {
            throw new IllegalStateException("OA installation generation mismatch");
        }
        Instant now = clock.instant();
        if (current.installationExpiresAt() == null || !now.isBefore(current.installationExpiresAt())) {
            throw new IllegalStateException("OA installation expired");
        }
        OaSessionRecord next = new OaSessionRecord(current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                userId, tenantId, platformId, OaSessionPhase.READY, expectedGeneration + 1,
                current.stagedCredentialRef(), null, current.credentialVersion(), current.installStartedAt(), now,
                null, null, now, null, null, null, 0, null);
        OaSessionRecord committed = requiresNew.execute(status -> {
            if (!repository.compareAndSwapExact(current, next)) {
                throw new IllegalStateException("OA session generation conflict");
            }
            if (isReplacedCredential(current.activeCredentialRef(), next.activeCredentialRef())) {
                cleanupRepository.upsertDeletePending(
                        current.activeCredentialRef(),
                        authSessionId,
                        "CREDENTIAL_REPLACED",
                        installationId,
                        now);
            }
            return next;
        });
        if (committed == null) {
            throw new IllegalStateException("OA session transaction returned no result");
        }
        if (isReplacedCredential(current.activeCredentialRef(), next.activeCredentialRef())) {
            drainPendingCredentialCleanupBestEffort();
        }
        return committed;
    }

    private static boolean isReplacedCredential(String previousRef, String nextRef) {
        return previousRef != null && !previousRef.equals(nextRef);
    }

    /** 连接关闭只提交耐久状态与待删引用；JCEKS 删除在事务提交后执行。 */
    public OaSessionRecord detach(TrustedDesktopConnection connection) {
        OaSessionRecord detached = detachBeforeCleanup(connection);
        drainPendingCredentialCleanupBestEffort();
        return detached;
    }

    OaSessionRecord detachBeforeCleanup(TrustedDesktopConnection connection) {
        Objects.requireNonNull(connection, "connection");
        OaSessionRecord current = required(connection);
        String authSessionId = current.authSessionId();
        long closingGeneration = current.generation();
        boolean closingLogin = current.phase() == OaSessionPhase.AUTHENTICATING
                || (current.phase() == OaSessionPhase.INSTALLING
                && current.activeCredentialRef() == null);
        for (int attempt = 0; attempt < LIFECYCLE_CAS_ATTEMPTS; attempt++) {
            if (closingLogin && current.phase() == OaSessionPhase.SIGNED_OUT) {
                return current;
            }
            if (closingLogin && isClosingLoginGeneration(current, closingGeneration)) {
                Instant now = clock.instant();
                OaSessionRecord signedOut = new OaSessionRecord(
                        current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                        null, null, null, OaSessionPhase.SIGNED_OUT, current.generation() + 1,
                        null, null, current.credentialVersion(), null, current.installedAt(),
                        null, null, now, null, null, null, 0, null);
                if (commitLifecycleTransition(
                        current, signedOut, "CONNECTION_CLOSED", current.installationId())) {
                    return signedOut;
                }
                current = currentOwned(authSessionId, connection);
                continue;
            }
            if (closingLogin) {
                return current;
            }
            if (current.phase() != OaSessionPhase.READY
                    && current.phase() != OaSessionPhase.RESTORING
                    && current.phase() != OaSessionPhase.INSTALLING) {
                return current;
            }
            Instant now = clock.instant();
            OaSessionRecord detached = new OaSessionRecord(
                    current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                    current.userId(), current.tenantId(), current.platformId(),
                    OaSessionPhase.DETACHED, current.generation() + 1,
                    current.activeCredentialRef(), null, current.credentialVersion(),
                    current.installStartedAt(), current.installedAt(), now, null, now,
                    null, null, null, 0, null);
            if (commitLifecycleTransition(
                    current, detached, "CONNECTION_CLOSED", current.installationId())) {
                return detached;
            }
            current = currentOwned(authSessionId, connection);
        }
        throw new IllegalStateException("OA session generation conflict");
    }

    private static boolean isClosingLoginGeneration(
            OaSessionRecord current,
            long closingGeneration) {
        if (current.generation() == closingGeneration) {
            return current.phase() == OaSessionPhase.AUTHENTICATING
                    || (current.phase() == OaSessionPhase.INSTALLING
                    && current.activeCredentialRef() == null);
        }
        return current.generation() == closingGeneration + 1
                && current.phase() == OaSessionPhase.READY;
    }

    /** 只补偿一次精确 restore 尝试，不能覆盖后来复用同一桌面槽位的会话。 */
    public AbortTransition abortRestore(
            TrustedDesktopConnection connection,
            String authSessionId,
            long restoringGeneration,
            String expectedInstallationId,
            String expectedStagedCredentialRef) {
        Objects.requireNonNull(connection, "connection");
        OaSessionRecord current = currentOwned(authSessionId, connection);
        for (int attempt = 0; attempt < LIFECYCLE_CAS_ATTEMPTS; attempt++) {
            if (current.phase() == OaSessionPhase.DETACHED) {
                return new AbortTransition(current, false);
            }
            boolean restoring = current.phase() == OaSessionPhase.RESTORING
                    && current.generation() == restoringGeneration
                    && expectedInstallationId == null
                    && expectedStagedCredentialRef == null;
            boolean installing = current.phase() == OaSessionPhase.INSTALLING
                    && current.generation() == restoringGeneration
                    && Objects.equals(current.installationId(), expectedInstallationId)
                    && Objects.equals(current.stagedCredentialRef(), expectedStagedCredentialRef);
            boolean ready = current.phase() == OaSessionPhase.READY
                    && current.generation() == restoringGeneration + 1
                    && expectedInstallationId != null
                    && expectedStagedCredentialRef != null
                    && Objects.equals(current.activeCredentialRef(), expectedStagedCredentialRef);
            if (!restoring && !installing && !ready) {
                throw new IllegalStateException("BUSINESS_SESSION_STALE");
            }
            Instant now = clock.instant();
            OaSessionRecord detached = new OaSessionRecord(
                    current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                    current.userId(), current.tenantId(), current.platformId(),
                    OaSessionPhase.DETACHED, current.generation() + 1,
                    current.activeCredentialRef(), null, current.credentialVersion(),
                    current.installStartedAt(), current.installedAt(), now, null, now,
                    null, null, null, 0, null);
            if (commitLifecycleTransition(
                    current, detached, "RESTORE_ABORT", expectedInstallationId)) {
                drainReleasedCredentialRefsBestEffort(current, detached);
                return new AbortTransition(detached, true);
            }
            current = currentOwned(authSessionId, connection);
        }
        throw new IllegalStateException("OA session generation conflict");
    }

    /** 只补偿一次精确 login 尝试，包括 READY 发布失败前已被 close 转成 DETACHED 的窗口。 */
    public AbortTransition abortLogin(
            TrustedDesktopConnection connection,
            String authSessionId,
            long authenticatingGeneration,
            String expectedInstallationId,
            String expectedStagedCredentialRef) {
        Objects.requireNonNull(connection, "connection");
        OaSessionRecord current = currentOwned(authSessionId, connection);
        for (int attempt = 0; attempt < LIFECYCLE_CAS_ATTEMPTS; attempt++) {
            boolean authenticating = current.phase() == OaSessionPhase.AUTHENTICATING
                    && current.generation() == authenticatingGeneration
                    && expectedInstallationId == null
                    && expectedStagedCredentialRef == null;
            boolean installing = current.phase() == OaSessionPhase.INSTALLING
                    && current.generation() == authenticatingGeneration
                    && Objects.equals(current.installationId(), expectedInstallationId)
                    && Objects.equals(current.stagedCredentialRef(), expectedStagedCredentialRef);
            boolean ready = current.phase() == OaSessionPhase.READY
                    && current.generation() == authenticatingGeneration + 1
                    && expectedInstallationId != null
                    && expectedStagedCredentialRef != null
                    && Objects.equals(current.activeCredentialRef(), expectedStagedCredentialRef);
            boolean detachedAfterPromotion = current.phase() == OaSessionPhase.DETACHED
                    && current.generation() == authenticatingGeneration + 2
                    && expectedInstallationId != null
                    && expectedStagedCredentialRef != null
                    && current.stagedCredentialRef() == null
                    && Objects.equals(current.activeCredentialRef(), expectedStagedCredentialRef);
            if (!authenticating && !installing && !ready && !detachedAfterPromotion) {
                return new AbortTransition(current, false);
            }
            Instant now = clock.instant();
            OaSessionRecord signedOut = new OaSessionRecord(
                    current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                    null, null, null, OaSessionPhase.SIGNED_OUT, current.generation() + 1,
                    null, null, current.credentialVersion(), null, null, null, null, now,
                    null, null, null, 0, null);
            if (commitLifecycleTransition(
                    current, signedOut, "LOGIN_ABORT", expectedInstallationId)) {
                drainReleasedCredentialRefsBestEffort(current, signedOut);
                return new AbortTransition(signedOut, true);
            }
            current = currentOwned(authSessionId, connection);
        }
        throw new IllegalStateException("OA session generation conflict");
    }

    public OaSessionRecord revoke(String authSessionId, long expectedGeneration) {
        OaSessionRecord current = required(authSessionId);
        if (current.phase() == OaSessionPhase.SIGNED_OUT) return current;
        if (current.phase() != OaSessionPhase.REVOKING) {
            throw new IllegalStateException("OA session cannot complete revocation");
        }
        if (current.generation() != expectedGeneration) throw new IllegalStateException("OA session generation conflict");
        Instant now = clock.instant();
        OaSessionRecord next = new OaSessionRecord(current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                null, null, null, OaSessionPhase.SIGNED_OUT,
                expectedGeneration + 1, null, null, current.credentialVersion(), null,
                current.installedAt(), null, null, now);
        if (!commitLifecycleTransition(
                current, next, "SESSION_REVOKED", current.installationId())) {
            throw new IllegalStateException("OA session generation conflict");
        }
        drainReleasedCredentialRefsBestEffort(current, next);
        return next;
    }

    public record AbortTransition(OaSessionRecord record, boolean winner) {
        public AbortTransition {
            Objects.requireNonNull(record, "record");
        }
    }

    public OaSessionRecord normalizeLegacyRevoked(String authSessionId, long expectedGeneration) {
        OaSessionRecord current = required(authSessionId);
        if (current.phase() != OaSessionPhase.REVOKED) {
            throw new IllegalStateException("OA session is not legacy revoked");
        }
        if (current.generation() != expectedGeneration) {
            throw new IllegalStateException("OA session generation conflict");
        }
        Instant now = clock.instant();
        OaSessionRecord signedOut = new OaSessionRecord(
                current.authSessionId(), current.desktopInstanceId(), current.desktopSessionId(),
                null, null, null, OaSessionPhase.SIGNED_OUT, expectedGeneration + 1,
                null, null, current.credentialVersion(), null,
                current.installedAt(), null, null, now);
        if (!commitLifecycleTransition(
                current, signedOut, "LEGACY_REVOKED_NORMALIZED", current.installationId())) {
            throw new IllegalStateException("OA session generation conflict");
        }
        drainReleasedCredentialRefsBestEffort(current, signedOut);
        return signedOut;
    }

    private OaSessionRecord required(String authSessionId) {
        return repository.findByAuthSessionId(authSessionId)
                .orElseThrow(() -> new IllegalStateException("OA session not found"));
    }

    private OaSessionRecord required(TrustedDesktopConnection connection) {
        OaSessionRecord current = repository.findByDesktopSession(
                        connection.desktopInstanceId(), connection.desktopSessionId())
                .orElseThrow(() -> new IllegalStateException("OA session not found"));
        if (current.phase() == OaSessionPhase.REVOKED) {
            throw new IllegalStateException("OA session is revoked");
        }
        return current;
    }

    private OaSessionRecord currentOwned(
            String authSessionId,
            TrustedDesktopConnection connection) {
        OaSessionRecord current = required(authSessionId);
        requireOwner(current, connection);
        return current;
    }

    private boolean commitLifecycleTransition(
            OaSessionRecord expected,
            OaSessionRecord next,
            String reasonCode,
            String operationId) {
        List<String> releasedRefs = releasedCredentialRefs(expected, next);
        Boolean committed = requiresNew.execute(status -> {
            if (!repository.compareAndSwapExact(expected, next)) {
                return false;
            }
            Instant now = clock.instant();
            for (String releasedRef : releasedRefs) {
                cleanupRepository.upsertDeletePending(
                        releasedRef,
                        expected.authSessionId(),
                        reasonCode,
                        operationId,
                        now);
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private static List<String> releasedCredentialRefs(
            OaSessionRecord expected,
            OaSessionRecord next) {
        List<String> released = new ArrayList<>(2);
        addReleasedRef(released, expected.activeCredentialRef(), next);
        addReleasedRef(released, expected.stagedCredentialRef(), next);
        return List.copyOf(released);
    }

    public void drainPendingCredentialCleanup() {
        cleanupService.drainDeletePending();
    }

    public void drainReleasedCredentialCleanupStrict(
            OaSessionRecord releasedFrom,
            OaSessionRecord current) {
        Objects.requireNonNull(releasedFrom, "releasedFrom");
        Objects.requireNonNull(current, "current");
        cleanupService.drainDeletePendingStrict(releasedCredentialRefs(releasedFrom, current));
    }

    void drainPendingCredentialCleanupBestEffort() {
        try {
            drainPendingCredentialCleanup();
        } catch (RuntimeException failure) {
            log.warn("OA credential cleanup drain failed: reasonType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void drainReleasedCredentialRefsBestEffort(
            OaSessionRecord expected,
            OaSessionRecord next) {
        if (!releasedCredentialRefs(expected, next).isEmpty()) {
            drainPendingCredentialCleanupBestEffort();
        }
    }

    private static void addReleasedRef(
            List<String> released,
            String candidate,
            OaSessionRecord next) {
        if (candidate != null
                && !Objects.equals(candidate, next.activeCredentialRef())
                && !Objects.equals(candidate, next.stagedCredentialRef())
                && !released.contains(candidate)) {
            released.add(candidate);
        }
    }

    private static void requireOwner(OaSessionRecord current, TrustedDesktopConnection owner) {
        if (!current.desktopInstanceId().equals(owner.desktopInstanceId())
                || !current.desktopSessionId().equals(owner.desktopSessionId())) {
            throw new IllegalStateException("OA installation owner mismatch");
        }
    }

    private static void requireInstallationOwner(
            OaSessionRecord current,
            TrustedDesktopConnection owner) {
        requireOwner(current, owner);
        if (!Objects.equals(current.installationOwnerDesktopInstanceId(), owner.desktopInstanceId())
                || !Objects.equals(current.installationOwnerDesktopSessionId(), owner.desktopSessionId())) {
            throw new IllegalStateException("OA installation owner mismatch");
        }
    }

    private static boolean isStageSourcePhase(OaSessionPhase phase) {
        return phase == OaSessionPhase.AUTHENTICATING
                || phase == OaSessionPhase.RESTORING
                || phase == OaSessionPhase.READY;
    }

    private static OaSessionRecord copy(
            OaSessionRecord current,
            OaSessionPhase phase,
            long generation,
            String userId,
            String tenantId,
            String platformId,
            String activeRef,
            String stagedRef,
            Instant detachedAt,
            Instant revokedAt,
            Instant updatedAt) {
        return new OaSessionRecord(
                current.authSessionId(),
                current.desktopInstanceId(),
                current.desktopSessionId(),
                userId,
                tenantId,
                platformId,
                phase,
                generation,
                activeRef,
                stagedRef,
                current.credentialVersion(),
                current.installStartedAt(),
                current.installedAt(),
                detachedAt,
                revokedAt,
                updatedAt,
                current.installationId(),
                current.installationOwnerDesktopInstanceId(),
                current.installationOwnerDesktopSessionId(),
                current.installationTargetGeneration(),
                current.installationExpiresAt());
    }

    private static boolean isAllowed(OaSessionPhase from, OaSessionPhase to) {
        return switch (from) {
            case SIGNED_OUT -> to == OaSessionPhase.AUTHENTICATING || to == OaSessionPhase.RESTORING;
            case AUTHENTICATING, RESTORING -> to == OaSessionPhase.INSTALLING || to == OaSessionPhase.SIGNED_OUT;
            case INSTALLING -> to == OaSessionPhase.READY || to == OaSessionPhase.SIGNED_OUT
                    || to == OaSessionPhase.REVOKING;
            case READY -> to == OaSessionPhase.DETACHED || to == OaSessionPhase.REVOKING;
            case DETACHED -> to == OaSessionPhase.RESTORING || to == OaSessionPhase.REVOKING
                    || to == OaSessionPhase.SIGNED_OUT;
            case REVOKING -> to == OaSessionPhase.REVOKED || to == OaSessionPhase.SIGNED_OUT;
            case REVOKED -> false;
        };
    }

}
