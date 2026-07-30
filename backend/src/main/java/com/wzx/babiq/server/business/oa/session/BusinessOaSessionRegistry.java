package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory live-lease index backed by the durable OA session repository.
 * Network calls must happen outside this class and outside its state transitions.
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessOaSessionRegistry {
    private final OaSessionRepository repository;
    private final OaSessionPersistenceService persistence;
    private final Map<String, ReadyOaSessionLease> liveLeases = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessOaSessionRegistry(OaSessionRepository repository,
                                     OaSessionPersistenceService persistence) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public ReadyOaSessionLease captureReady(TrustedDesktopConnection connection) {
        Objects.requireNonNull(connection, "connection");
        OaSessionRecord record = repository.findByDesktopSession(connection.desktopInstanceId(), connection.desktopSessionId())
                .orElseThrow(() -> new IllegalStateException("OA session is not available"));
        return captureReady(record, connection);
    }

    /** Publishes an already-activated READY record without repository or network IO. */
    public ReadyOaSessionLease captureReady(OaSessionRecord record, TrustedDesktopConnection connection) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(connection, "connection");
        if (record.phase() != OaSessionPhase.READY) {
            throw new IllegalStateException("OA session is not READY");
        }
        if (!record.desktopInstanceId().equals(connection.desktopInstanceId())
                || !record.desktopSessionId().equals(connection.desktopSessionId())) {
            throw new IllegalStateException("OA session lease owner mismatch");
        }
        ReadyOaSessionLease lease = lease(record, connection.webSocketSessionId());
        liveLeases.put(record.authSessionId(), lease);
        return lease;
    }

    /** Capture after a state-changing operation when no reservation id is available to the server-internal caller. */
    public ReadyOaSessionLease captureReady(String authSessionId, String desktopInstanceId,
                                     String desktopSessionId, String webSocketSessionId) {
        OaSessionRecord record = repository.findByAuthSessionId(authSessionId)
                .orElseThrow(() -> new IllegalStateException("OA session is not available"));
        if (!record.desktopInstanceId().equals(desktopInstanceId)
                || !record.desktopSessionId().equals(desktopSessionId)) {
            throw new IllegalStateException("OA session lease owner mismatch");
        }
        return captureReady(record, new TrustedDesktopConnection(
                "server-internal", desktopInstanceId, desktopSessionId, webSocketSessionId));
    }

    public boolean isCurrent(ReadyOaSessionLease lease) {
        if (lease == null) return false;
        ReadyOaSessionLease live = liveLeases.get(lease.authSessionId());
        if (!lease.equals(live)) return false;
        Optional<OaSessionRecord> current = repository.findByAuthSessionId(lease.authSessionId());
        return current.filter(record -> record.phase() == OaSessionPhase.READY)
                .filter(record -> record.generation() == lease.generation())
                .filter(record -> record.desktopInstanceId().equals(lease.desktopInstanceId()))
                .filter(record -> record.desktopSessionId().equals(lease.desktopSessionId()))
                .filter(record -> Objects.equals(record.userId(), lease.userId()))
                .filter(record -> Objects.equals(record.tenantId(), lease.tenantId()))
                .filter(record -> Objects.equals(record.platformId(), lease.platformId()))
                .filter(record -> Objects.equals(record.activeCredentialRef(), lease.activeCredentialRef()))
                .filter(record -> record.credentialVersion() == lease.credentialVersion())
                .isPresent();
    }

    /** Requires the exact committed identity to match the current durable and live READY lease. */
    public boolean matchesCurrentReady(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
        return currentReady(connection, identity).isPresent();
    }

    /** Returns the exact current READY snapshot without coupling identity and credential generations. */
    public Optional<ReadyOaSessionLease> currentReady(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity) {
        if (connection == null || identity == null) {
            return Optional.empty();
        }
        ReadyOaSessionLease lease = liveLeases.get(identity.authSessionId());
        boolean matches = lease != null
                && identity.reservationId().equals(connection.reservationId())
                && identity.webSocketSessionId().equals(connection.webSocketSessionId())
                && identity.desktopInstanceId().equals(connection.desktopInstanceId())
                && identity.desktopSessionId().equals(connection.desktopSessionId())
                && lease.authSessionId().equals(identity.authSessionId())
                && lease.desktopInstanceId().equals(connection.desktopInstanceId())
                && lease.desktopSessionId().equals(connection.desktopSessionId())
                && lease.webSocketSessionId().equals(connection.webSocketSessionId())
                && Objects.equals(lease.userId(), identity.userId())
                && Objects.equals(lease.tenantId(), identity.tenantId())
                && Objects.equals(lease.platformId(), identity.platformId())
                && isCurrent(lease);
        return matches ? Optional.of(lease) : Optional.empty();
    }

    public OaSessionRecord detach(TrustedDesktopConnection connection) {
        OaSessionRecord detached = detachBeforeCredentialCleanup(connection);
        requiredPersistence().drainPendingCredentialCleanupBestEffort();
        return detached;
    }

    public OaSessionRecord detachBeforeCredentialCleanup(
            TrustedDesktopConnection connection) {
        OaSessionRecord current = required(connection);
        liveLeases.remove(current.authSessionId());
        OaSessionRecord detached = requiredPersistence().detachBeforeCleanup(connection);
        liveLeases.remove(detached.authSessionId());
        return detached;
    }

    public void drainPendingCredentialCleanup() {
        requiredPersistence().drainPendingCredentialCleanup();
    }

    /** Closes the business gate before cleanup. Cleanup may subsequently move REVOKING to REVOKED. */
    public OaSessionRecord revokeBeforeCleanup(TrustedDesktopConnection connection, RevocationReason reason) {
        return revokeBeforeCleanup(connection, reason, required(connection));
    }

    /** Closes only the durable session snapshot captured when this revocation began. */
    public OaSessionRecord revokeBeforeCleanup(
            TrustedDesktopConnection connection,
            RevocationReason reason,
            OaSessionRecord expectedTarget) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        removeLiveLeaseForTarget(expectedTarget);
        OaSessionRecord revoking = requiredPersistence().beginRevocation(
                connection, expectedTarget);
        removeLiveLeaseForRevoking(revoking);
        return revoking;
    }

    /** Claims terminal revocation so only the real READY lineage CAS winner may notify the desktop. */
    public RevocationClaim claimRevocationBeforeCleanup(
            TrustedDesktopConnection connection,
            RevocationReason reason,
            OaSessionRecord expectedTarget) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        OaSessionPersistenceService.RevocationTransition transition =
                requiredPersistence().claimRevocation(connection, expectedTarget);
        if (transition.winner()) {
            removeLiveLeaseForTarget(expectedTarget);
            removeLiveLeaseForRevoking(transition.record());
        }
        return new RevocationClaim(transition.record(), transition.winner());
    }

    private void removeLiveLeaseForTarget(OaSessionRecord target) {
        liveLeases.computeIfPresent(target.authSessionId(), (ignored, lease) ->
                leaseMatchesTarget(target, lease) ? null : lease);
    }

    private void removeLiveLeaseForRevoking(OaSessionRecord revoking) {
        if (revoking.phase() != OaSessionPhase.REVOKING) {
            return;
        }
        liveLeases.computeIfPresent(revoking.authSessionId(), (ignored, lease) ->
                lease.generation() + 1 == revoking.generation()
                        && Objects.equals(lease.activeCredentialRef(), revoking.activeCredentialRef())
                        ? null : lease);
    }

    private static boolean leaseMatchesTarget(
            OaSessionRecord target,
            ReadyOaSessionLease lease) {
        if (!target.authSessionId().equals(lease.authSessionId())
                || !target.desktopInstanceId().equals(lease.desktopInstanceId())
                || !target.desktopSessionId().equals(lease.desktopSessionId())) {
            return false;
        }
        if (target.phase() == OaSessionPhase.READY) {
            return target.generation() == lease.generation()
                    && Objects.equals(target.activeCredentialRef(), lease.activeCredentialRef());
        }
        return target.phase() == OaSessionPhase.INSTALLING
                && target.generation() + 1 == lease.generation()
                && Objects.equals(target.stagedCredentialRef(), lease.activeCredentialRef());
    }

    /** Cancels one exact restore attempt without detaching a later generation that reused the desktop slot. */
    public OaSessionRecord abortRestore(TrustedDesktopConnection connection,
                                        String authSessionId,
                                        long restoringGeneration,
                                        String expectedInstallationId,
                                        String expectedStagedCredentialRef) {
        OaSessionPersistenceService.AbortTransition transition = requiredPersistence().abortRestore(
                connection,
                authSessionId,
                restoringGeneration,
                expectedInstallationId,
                expectedStagedCredentialRef);
        if (transition.winner()) {
            removeLiveLeaseForAbortedAttempt(
                    connection, authSessionId, restoringGeneration,
                    expectedStagedCredentialRef, transition.record(), OaSessionPhase.DETACHED, 2);
        }
        return transition.record();
    }

    /** Cancels only the exact login attempt; a later generation or revocation always wins. */
    public OaSessionRecord abortLogin(TrustedDesktopConnection connection,
                                      String authSessionId,
                                      long authenticatingGeneration,
                                      String expectedInstallationId,
                                      String expectedStagedCredentialRef) {
        OaSessionPersistenceService.AbortTransition transition = requiredPersistence().abortLogin(
                connection,
                authSessionId,
                authenticatingGeneration,
                expectedInstallationId,
                expectedStagedCredentialRef);
        if (transition.winner()) {
            removeLiveLeaseForAbortedAttempt(
                    connection, authSessionId, authenticatingGeneration,
                    expectedStagedCredentialRef, transition.record(), OaSessionPhase.SIGNED_OUT, 3);
        }
        return transition.record();
    }

    private void removeLiveLeaseForAbortedAttempt(
            TrustedDesktopConnection connection,
            String authSessionId,
            long attemptGeneration,
            String expectedStagedCredentialRef,
            OaSessionRecord terminal,
            OaSessionPhase expectedTerminalPhase,
            long maxTerminalGenerationDelta) {
        if (expectedStagedCredentialRef == null
                || terminal.phase() != expectedTerminalPhase
                || !terminal.authSessionId().equals(authSessionId)
                || !terminal.desktopInstanceId().equals(connection.desktopInstanceId())
                || !terminal.desktopSessionId().equals(connection.desktopSessionId())
                || terminal.generation() < attemptGeneration + 1
                || terminal.generation() > attemptGeneration + maxTerminalGenerationDelta) {
            return;
        }
        liveLeases.computeIfPresent(authSessionId, (ignored, lease) ->
                lease.desktopInstanceId().equals(connection.desktopInstanceId())
                        && lease.desktopSessionId().equals(connection.desktopSessionId())
                        && lease.generation() == attemptGeneration + 1
                        && Objects.equals(lease.activeCredentialRef(), expectedStagedCredentialRef)
                        ? null : lease);
    }

    public OaSessionRecord transition(String authSessionId, long expectedGeneration, BusinessOaSessionState target) {
        Objects.requireNonNull(target, "target");
        OaSessionRecord next = requiredPersistence().transition(
                authSessionId, expectedGeneration, target.toPhase());
        if (target != BusinessOaSessionState.READY) liveLeases.remove(authSessionId);
        return next;
    }

    private OaSessionRecord required(TrustedDesktopConnection connection) {
        OaSessionRecord record = repository.findByDesktopSession(connection.desktopInstanceId(), connection.desktopSessionId())
                .orElseThrow(() -> new IllegalStateException("OA session not found"));
        if (record.phase() == OaSessionPhase.REVOKED) throw new IllegalStateException("OA session is revoked");
        return record;
    }

    private OaSessionPersistenceService requiredPersistence() {
        return persistence;
    }

    private static ReadyOaSessionLease lease(OaSessionRecord record, String webSocketSessionId) {
        return new ReadyOaSessionLease(record.authSessionId(), record.desktopInstanceId(), record.desktopSessionId(),
                webSocketSessionId, record.userId(), record.tenantId(), record.platformId(), record.generation(),
                record.activeCredentialRef(), record.credentialVersion(), Instant.now());
    }

    public record RevocationClaim(OaSessionRecord record, boolean winner) {
        public RevocationClaim {
            Objects.requireNonNull(record, "record");
        }
    }

    public enum RevocationReason { LOGOUT, AUTH_EXPIRED, MEMBERSHIP_EXPIRED, IDENTITY_CHANGED, RECOVERY }
}
