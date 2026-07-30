package com.wzx.babiq.server.business.oa.session;

import java.util.List;
import java.util.Optional;

/** OA 会话非敏感索引的领域持久化端口。 */
public interface OaSessionRepository {
    Optional<OaSessionRecord> findByAuthSessionId(String authSessionId);

    Optional<OaSessionRecord> findByDesktopSession(String desktopInstanceId, String desktopSessionId);

    /**
     * Whether any durable session still owns this active or staged credential reference.
     * The safe default protects the credential when an adapter cannot prove that it is orphaned.
     */
    default boolean existsCredentialReference(String secretRef) {
        return true;
    }

    /** Latest durable DETACHED session eligible for startup restore by the same desktop instance. */
    default Optional<OaSessionRecord> findLatestDetachedByDesktopInstanceId(String desktopInstanceId) {
        return listRecoverable().stream()
                .filter(record -> record.phase() == OaSessionPhase.DETACHED)
                .filter(record -> record.desktopInstanceId().equals(desktopInstanceId))
                .max(java.util.Comparator.comparing(
                        OaSessionRecord::updatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
    }

    OaSessionRecord insert(OaSessionRecord record);

    OaSessionRecord update(OaSessionRecord record);

    boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration, OaSessionRecord record);

    /** 仅当当前持久化记录与 expected 完全一致时，以单次原子写替换为 next。 */
    boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next);

    /**
     * Atomically stages credentials from one exact source snapshot. The source must not already own
     * an installation lease or staged credential, so a same-generation contender cannot overwrite
     * the first installation.
     */
    default boolean compareAndSwapStage(
            String authSessionId,
            OaSessionPhase expectedSourcePhase,
            long expectedGeneration,
            String expectedDesktopInstanceId,
            String expectedDesktopSessionId,
            String expectedActiveCredentialRef,
            OaSessionRecord record) {
        if (!isStageSourcePhase(expectedSourcePhase)) return false;
        synchronized (this) {
            Optional<OaSessionRecord> current = findByAuthSessionId(authSessionId);
            if (current.filter(value -> value.phase() == expectedSourcePhase)
                    .filter(value -> value.generation() == expectedGeneration)
                    .filter(value -> value.desktopInstanceId().equals(expectedDesktopInstanceId))
                    .filter(value -> value.desktopSessionId().equals(expectedDesktopSessionId))
                    .filter(value -> java.util.Objects.equals(
                            value.activeCredentialRef(), expectedActiveCredentialRef))
                    .filter(value -> value.stagedCredentialRef() == null)
                    .filter(value -> value.installationId() == null)
                    .filter(value -> value.installationOwnerDesktopInstanceId() == null)
                    .filter(value -> value.installationOwnerDesktopSessionId() == null)
                    .filter(value -> value.installationTargetGeneration() == 0)
                    .filter(value -> value.installationExpiresAt() == null)
                    .isEmpty()) {
                return false;
            }
            return compareAndSwapGeneration(authSessionId, expectedGeneration, record);
        }
    }

    /**
     * Atomically rebinds an exact DETACHED durable lease to a new child desktop session.
     * Production adapters must include phase, generation and the complete previous owner in the predicate.
     */
    default boolean compareAndSwapDetachedLease(
            String authSessionId,
            long expectedGeneration,
            String expectedDesktopInstanceId,
            String expectedDesktopSessionId,
            OaSessionRecord record) {
        Optional<OaSessionRecord> current = findByAuthSessionId(authSessionId);
        if (current.filter(value -> value.phase() == OaSessionPhase.DETACHED)
                .filter(value -> value.generation() == expectedGeneration)
                .filter(value -> value.desktopInstanceId().equals(expectedDesktopInstanceId))
                .filter(value -> value.desktopSessionId().equals(expectedDesktopSessionId))
                .isEmpty()) {
            return false;
        }
        return compareAndSwapGeneration(authSessionId, expectedGeneration, record);
    }

    /**
     * Atomically publishes one exact INSTALLING transaction. Production adapters must include every
     * supplied lease field and phase=INSTALLING in the update predicate.
     */
    default boolean compareAndSwapInstallation(
            String authSessionId,
            long expectedGeneration,
            String expectedInstallationId,
            String expectedOwnerDesktopInstanceId,
            String expectedOwnerDesktopSessionId,
            long expectedTargetGeneration,
            String expectedActiveCredentialRef,
            String expectedStagedCredentialRef,
            OaSessionRecord record) {
        Optional<OaSessionRecord> current = findByAuthSessionId(authSessionId);
        if (current.filter(value -> value.phase() == OaSessionPhase.INSTALLING)
                .filter(value -> value.generation() == expectedGeneration)
                .filter(value -> java.util.Objects.equals(value.installationId(), expectedInstallationId))
                .filter(value -> java.util.Objects.equals(
                        value.installationOwnerDesktopInstanceId(), expectedOwnerDesktopInstanceId))
                .filter(value -> java.util.Objects.equals(
                        value.installationOwnerDesktopSessionId(), expectedOwnerDesktopSessionId))
                .filter(value -> value.installationTargetGeneration() == expectedTargetGeneration)
                .filter(value -> java.util.Objects.equals(
                        value.activeCredentialRef(), expectedActiveCredentialRef))
                .filter(value -> java.util.Objects.equals(
                        value.stagedCredentialRef(), expectedStagedCredentialRef))
                .isEmpty()) {
            return false;
        }
        return compareAndSwapGeneration(authSessionId, expectedGeneration, record);
    }

    /**
     * Claims one exact interrupted snapshot for startup recovery before any referenced secret is deleted.
     * Production adapters must include every supplied nullable reference in the atomic predicate.
     */
    default boolean compareAndSwapRecoverySnapshot(
            String authSessionId,
            OaSessionPhase expectedPhase,
            long expectedGeneration,
            String expectedInstallationId,
            String expectedActiveCredentialRef,
            String expectedStagedCredentialRef,
            OaSessionRecord record) {
        Optional<OaSessionRecord> current = findByAuthSessionId(authSessionId);
        if (current.filter(value -> value.phase() == expectedPhase)
                .filter(value -> value.generation() == expectedGeneration)
                .filter(value -> java.util.Objects.equals(
                        value.installationId(), expectedInstallationId))
                .filter(value -> java.util.Objects.equals(
                        value.activeCredentialRef(), expectedActiveCredentialRef))
                .filter(value -> java.util.Objects.equals(
                        value.stagedCredentialRef(), expectedStagedCredentialRef))
                .isEmpty()) {
            return false;
        }
        return compareAndSwapGeneration(authSessionId, expectedGeneration, record);
    }

    List<OaSessionRecord> listRecoverable();

    private static boolean isStageSourcePhase(OaSessionPhase phase) {
        return phase == OaSessionPhase.AUTHENTICATING
                || phase == OaSessionPhase.RESTORING
                || phase == OaSessionPhase.READY;
    }
}
