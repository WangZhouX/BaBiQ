package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.upload.BusinessBinaryLeaseLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned OA authentication lifecycle. Secrets never leave this service. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessOaAuthenticationService implements OaSessionTerminalizer {
    private static final Logger log = LoggerFactory.getLogger(BusinessOaAuthenticationService.class);
    private static final Duration CANDIDATE_TTL = Duration.ofSeconds(90);
    private final OaSessionRepository repository;
    private final OaAuthenticationGateway gateway;
    private final OaSessionPersistenceService persistence;
    private final BusinessOaSessionRegistry sessions;
    private final BusinessOaReadyInstaller installer;
    private final OaSessionCredentialStore credentials;
    private final ApplicationIdentityRegistry identities;
    private final BusinessOaAttachHandleRegistry attachHandles;
    private final BusinessDesktopConnectionRegistry connections;
    private final BusinessAuthStateNotifier stateNotifier;
    private final BusinessBinaryLeaseLifecycle binaryLeaseLifecycle;
    private final Map<String, CandidateTicket> candidateTickets = new ConcurrentHashMap<>();
    private final Map<DesktopConnectionSlot, RememberedAccountBinding> rememberedAccounts =
            new ConcurrentHashMap<>();
    private final Map<TerminalNotificationKey, PendingTerminalNotification> pendingTerminalNotifications =
            new ConcurrentHashMap<>();

    public BusinessOaAuthenticationService(OaSessionRepository repository,
                                           OaAuthenticationGateway gateway,
                                           OaSessionPersistenceService persistence,
                                           BusinessOaSessionRegistry sessions,
                                           BusinessOaReadyInstaller installer,
                                           OaSessionCredentialStore credentials,
                                           ApplicationIdentityRegistry identities,
                                           BusinessOaAttachHandleRegistry attachHandles,
                                           BusinessDesktopConnectionRegistry connections) {
        this(repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections, BusinessAuthStateNotifier.noop(), null);
    }

    public BusinessOaAuthenticationService(OaSessionRepository repository,
                                           OaAuthenticationGateway gateway,
                                           OaSessionPersistenceService persistence,
                                           BusinessOaSessionRegistry sessions,
                                           BusinessOaReadyInstaller installer,
                                           OaSessionCredentialStore credentials,
                                           ApplicationIdentityRegistry identities,
                                           BusinessOaAttachHandleRegistry attachHandles,
                                           BusinessDesktopConnectionRegistry connections,
                                           BusinessAuthStateNotifier stateNotifier) {
        this(repository, gateway, persistence, sessions, installer, credentials, identities,
                attachHandles, connections, stateNotifier, null);
    }

    @Autowired
    public BusinessOaAuthenticationService(OaSessionRepository repository,
                                           OaAuthenticationGateway gateway,
                                           OaSessionPersistenceService persistence,
                                           BusinessOaSessionRegistry sessions,
                                           BusinessOaReadyInstaller installer,
                                           OaSessionCredentialStore credentials,
                                           ApplicationIdentityRegistry identities,
                                           BusinessOaAttachHandleRegistry attachHandles,
                                           BusinessDesktopConnectionRegistry connections,
                                           BusinessAuthStateNotifier stateNotifier,
                                           BusinessBinaryLeaseLifecycle binaryLeaseLifecycle) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.attachHandles = Objects.requireNonNull(attachHandles, "attachHandles");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.stateNotifier = Objects.requireNonNull(stateNotifier, "stateNotifier");
        this.binaryLeaseLifecycle = binaryLeaseLifecycle;
    }

    public BusinessAuthDtos.Session session(TrustedDesktopConnection connection) {
        OaSessionRecord record = repository.findByDesktopSession(
                connection.desktopInstanceId(), connection.desktopSessionId()).orElse(null);
        if (record == null) {
            OaSessionRecord restorable = repository.findLatestDetachedByDesktopInstanceId(
                    connection.desktopInstanceId()).orElse(null);
            if (restorable != null) {
                return project(connection, restorable, null, true, false);
            }
            return new BusinessAuthDtos.Session(
                    null, OaSessionPhase.SIGNED_OUT.name(), 0L, 0L, null,
                    null, null, null, null, null, Set.of(), Set.of(),
                    rememberedAccount(connection), false, false);
        }
        String attachHandle = record.phase() == OaSessionPhase.DETACHED
                ? attachHandles.issue(connection, record) : null;
        if (record.phase() == OaSessionPhase.READY) {
            Optional<TrustedBusinessIdentity> identity = identities.find(
                    connection.webSocketSessionId());
            Optional<ReadyOaSessionLease> ready = identity.flatMap(
                    current -> sessions.currentReady(connection, current));
            if (identity.isEmpty() || ready.isEmpty()) {
                return projectInstalling(connection, record);
            }
            return projectReady(connection, identity.orElseThrow(), ready.orElseThrow());
        }
        return project(connection, record, attachHandle,
                false,
                record.phase() == OaSessionPhase.DETACHED);
    }

    private BusinessAuthDtos.Session project(TrustedDesktopConnection connection,
                                              OaSessionRecord record,
                                              String attachHandle,
                                              boolean canRestore,
                                              boolean canAttach) {
        return new BusinessAuthDtos.Session(
                record.authSessionId(),
                record.phase().name(),
                0L,
                record.generation(),
                attachHandle,
                record.userId(),
                null,
                record.tenantId(),
                null,
                record.platformId(),
                Set.of(),
                Set.of(),
                rememberedAccount(connection),
                canRestore,
                canAttach);
    }

    private BusinessAuthDtos.Session projectInstalling(
            TrustedDesktopConnection connection,
            OaSessionRecord record) {
        return new BusinessAuthDtos.Session(
                record.authSessionId(), OaSessionPhase.INSTALLING.name(), 0L,
                record.generation(), null, record.userId(), null, record.tenantId(),
                null, record.platformId(), Set.of(), Set.of(),
                rememberedAccount(connection), false, false);
    }

    private BusinessAuthDtos.Session projectReady(
            TrustedDesktopConnection connection,
            TrustedBusinessIdentity identity,
            ReadyOaSessionLease ready) {
        return new BusinessAuthDtos.Session(
                ready.authSessionId(), OaSessionPhase.READY.name(), identity.identityEpoch(),
                ready.generation(), null, identity.userId(), null, identity.tenantId(),
                null, identity.platformId(), identity.roles(), identity.permissions(),
                rememberedAccount(connection), false, false);
    }

    public BusinessAuthDtos.TenantCandidates findTenantCandidates(TrustedDesktopConnection connection, String account) {
        requireText(account, "account");
        OaSessionRecord current = ensureSession(connection);
        requirePhase(current, OaSessionPhase.SIGNED_OUT);
        List<OaAuthDtos.OaTenantCandidate> candidates = gateway.findTenantCandidates(account);
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("No tenant candidates");
        List<BusinessAuthDtos.TenantCandidate> result = new ArrayList<>();
        for (OaAuthDtos.OaTenantCandidate candidate : candidates) {
            if (candidate == null || candidate.account() != null && !account.equals(candidate.account())) {
                throw new IllegalStateException("tenant candidate account mismatch");
            }
            // Keep the account that entered this query on the server-owned ticket. The
            // remote response is allowed to omit it, but may not substitute another account.
            OaAuthDtos.OaTenantCandidate boundCandidate = candidate.account() == null || candidate.account().isBlank()
                    ? new OaAuthDtos.OaTenantCandidate(candidate.userId(), candidate.tenantId(), candidate.platformId(),
                    candidate.tenantName(), candidate.tenantEnterStatus(), candidate.tenantEnterId(), account)
                    : candidate;
            String id = UUID.randomUUID().toString();
            candidateTickets.put(id, new CandidateTicket(id, connection.desktopInstanceId(),
                    connection.desktopSessionId(), account, boundCandidate, Instant.now().plus(CANDIDATE_TTL)));
            result.add(new BusinessAuthDtos.TenantCandidate(id, boundCandidate.tenantName(), boundCandidate.platformId(), boundCandidate.tenantEnterStatus()));
        }
        cleanupTickets();
        return new BusinessAuthDtos.TenantCandidates(result, account);
    }

    public BusinessAuthDtos.Session login(TrustedDesktopConnection connection, String account,
                                          String candidateId, char[] password) {
        requireText(account, "account");
        requireText(candidateId, "candidateId");
        requireMaterial(password, "password");
        CandidateTicket ticket = candidateTickets.remove(candidateId);
        if (ticket == null || ticket.expired() || !ticket.matches(connection) || !ticket.account().equals(account)) {
            wipe(password);
            throw new IllegalStateException("candidate is stale");
        }
        OaSessionRecord current = ensureSession(connection);
        requirePhase(current, OaSessionPhase.SIGNED_OUT);
        OaSessionRecord authenticating = sessions.transition(current.authSessionId(), current.generation(), BusinessOaSessionState.AUTHENTICATING);
        LoginAttempt attempt = new LoginAttempt(authenticating);
        try {
            OaAuthDtos.OaCredential credential;
            try {
                credential = gateway.login(ticket.candidate(), password);
            } finally {
                wipe(password);
            }
            requireCurrentLoginAttempt(connection, attempt);
            if (credential == null || credential.accessToken() == null || credential.refreshToken() == null) {
                throw new IllegalStateException("OA login returned no credential");
            }
            OaAuthDtos.OaPermissionSnapshot permissions = loadPermissions(ticket.candidate().tenantId(), credential);
            String effectiveUserId = credential.userId() == null
                    ? ticket.candidate().userId()
                    : credential.userId();
            if (!Objects.equals(ticket.candidate().userId(), effectiveUserId)) {
                throw new IllegalStateException("OA login identity mismatch");
            }
            if (permissions == null || !Objects.equals(effectiveUserId, permissions.userId())) {
                throw new IllegalStateException("OA permission identity mismatch");
            }
            requireCurrentLoginAttempt(connection, attempt);
            OaSessionRecord staged = persistence.stage(authenticating.authSessionId(), authenticating.generation(), connection,
                    credential.accessToken().toCharArray(), credential.refreshToken().toCharArray());
            attempt.markStaged(staged);
            ReadyOaSessionLease readyLease = installer.install(connection, staged,
                    effectiveUserId,
                    ticket.candidate().tenantId(), Integer.toString(ticket.candidate().platformId()), permissions,
                    (ready, commitProjections) -> publishReady(
                            connection, ready, () -> { }, commitProjections));
            rememberReadyAccount(connection, ticket.account(), staged.installationId(), readyLease);
            return session(connection);
        } catch (RuntimeException failure) {
            throw compensateLoginFailure(connection, attempt, failure);
        }
    }

    public BusinessAuthDtos.Session attach(TrustedDesktopConnection connection, String attachHandle) {
        RestoreAttempt attempt;
        synchronized (connection) {
            BusinessOaAttachHandleRegistry.AttachClaim claim = attachHandles.claim(attachHandle, connection);
            if (!claim.startsRestore()) {
                return session(connection);
            }
            try {
                attempt = new RestoreAttempt(beginRestore(
                        connection, claim.authSessionId(), claim.observedGeneration()), claim.expiresAt());
            } catch (RuntimeException failure) {
                if (!attachHandles.fail(attachHandle, connection)) {
                    throw new IllegalStateException("BUSINESS_SESSION_STALE");
                }
                throw normalizeAttachFailure(failure);
            }
        }
        try {
            BusinessAuthDtos.Session result = finishRestore(
                    connection, attempt,
                    () -> attachHandles.validateClaim(attachHandle, connection));
            attachHandles.complete(attachHandle, connection, result.generation());
            return result;
        } catch (RuntimeException failure) {
            throw compensateRestoreFailure(connection, attachHandle, attempt, failure);
        }
    }

    public BusinessAuthDtos.Session restore(TrustedDesktopConnection connection) {
        RestoreAttempt attempt;
        synchronized (connection) {
            if (repository.findByDesktopSession(
                    connection.desktopInstanceId(), connection.desktopSessionId()).isPresent()) {
                throw new IllegalStateException("BUSINESS_SESSION_NOT_ATTACHABLE");
            }
            OaSessionRecord current = repository.findLatestDetachedByDesktopInstanceId(
                            connection.desktopInstanceId())
                    .orElseThrow(() -> new IllegalStateException("BUSINESS_SESSION_NOT_ATTACHABLE"));
            if (current.desktopSessionId().equals(connection.desktopSessionId())) {
                throw new IllegalStateException("BUSINESS_SESSION_NOT_ATTACHABLE");
            }
            current = rebindDetached(current, connection);
            attempt = new RestoreAttempt(
                    beginRestore(connection, current.authSessionId(), current.generation()), null);
        }
        try {
            return finishRestore(connection, attempt, () -> { });
        } catch (RuntimeException failure) {
            throw compensateRestoreFailure(connection, null, attempt, failure);
        }
    }

    private OaSessionRecord rebindDetached(OaSessionRecord current,
                                           TrustedDesktopConnection connection) {
        Instant now = Instant.now();
        OaSessionRecord rebound = new OaSessionRecord(
                current.authSessionId(), connection.desktopInstanceId(), connection.desktopSessionId(),
                current.userId(), current.tenantId(), current.platformId(), OaSessionPhase.DETACHED,
                current.generation() + 1, current.activeCredentialRef(), null,
                current.credentialVersion(), current.installStartedAt(), current.installedAt(),
                current.detachedAt(), current.revokedAt(), now,
                null, null, null, 0, null);
        if (!repository.compareAndSwapDetachedLease(
                current.authSessionId(), current.generation(), current.desktopInstanceId(),
                current.desktopSessionId(), rebound)) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
        return rebound;
    }

    private OaSessionRecord beginRestore(TrustedDesktopConnection connection,
                                         String authSessionId,
                                         long expectedGeneration) {
        OaSessionRecord current = repository.findByAuthSessionId(authSessionId)
                .orElseThrow(() -> new IllegalStateException("BUSINESS_SESSION_STALE"));
        if (current.phase() != OaSessionPhase.DETACHED
                || current.generation() != expectedGeneration
                || !current.desktopInstanceId().equals(connection.desktopInstanceId())
                || !current.desktopSessionId().equals(connection.desktopSessionId())) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
        try {
            return sessions.transition(
                    current.authSessionId(), current.generation(), BusinessOaSessionState.RESTORING);
        } catch (IllegalStateException conflict) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
    }

    private BusinessAuthDtos.Session finishRestore(TrustedDesktopConnection connection,
                                                    RestoreAttempt attempt,
                                                    Runnable validateClaim) {
        OaSessionRecord restoring = attempt.restoring();
        OaSessionCredentialStore.CredentialMaterial material = credentials.load(restoring.activeCredentialRef());
        if (material == null) throw new IllegalStateException("OA session credential is unavailable");
        OaAuthDtos.OaCredential refreshed;
        try {
            refreshed = gateway.refresh(restoring.tenantId(), material.refreshToken());
        } finally {
            material.close();
        }
        if (refreshed == null || refreshed.accessToken() == null || refreshed.refreshToken() == null) {
            throw new IllegalStateException("OA restore returned no credential");
        }
        if (!Objects.equals(restoring.userId(), refreshed.userId())) {
            throw new IllegalStateException("OA restore identity mismatch");
        }
        OaAuthDtos.OaPermissionSnapshot permissions = loadPermissions(restoring.tenantId(), refreshed);
        validateClaim.run();
        OaSessionRecord staged = persistence.stage(
                restoring.authSessionId(), restoring.generation(), connection, attempt.absoluteDeadline(),
                refreshed.accessToken().toCharArray(), refreshed.refreshToken().toCharArray());
        attempt.markStaged(staged);
        validateClaim.run();
        installer.install(connection, staged,
                refreshed.userId() == null ? restoring.userId() : refreshed.userId(),
                restoring.tenantId(), restoring.platformId(), permissions,
                (ready, commitProjections) -> publishReady(
                        connection, ready, validateClaim, commitProjections));
        return session(connection);
    }

    private ReadyOaSessionLease publishReady(TrustedDesktopConnection connection,
                                              OaSessionRecord ready,
                                              Runnable validateClaim,
                                              Runnable commitProjections) {
        return connections.withFinalized(connection, () -> {
            validateClaim.run();
            commitProjections.run();
            return sessions.captureReady(ready, connection);
        });
    }

    private static RuntimeException normalizeAttachFailure(RuntimeException failure) {
        if (failure instanceof IllegalStateException state && state.getMessage() != null) {
            return switch (state.getMessage()) {
                case "OA session generation conflict", "OA session installation is stale",
                        "OA installation id mismatch", "OA installation owner mismatch",
                        "OA installation generation mismatch", "OA installation expired",
                        "OA session is revoked", "BUSINESS_SESSION_STALE" ->
                        new IllegalStateException("BUSINESS_SESSION_STALE");
                default -> failure;
            };
        }
        return failure;
    }

    private RuntimeException compensateRestoreFailure(TrustedDesktopConnection connection,
                                                        String attachHandle,
                                                        RestoreAttempt attempt,
                                                        RuntimeException failure) {
        boolean claimReleased = attachHandle == null || attachHandles.fail(attachHandle, connection);
        try {
            sessions.abortRestore(
                    connection, attempt.restoring().authSessionId(), attempt.restoring().generation(),
                    attempt.installationId(), attempt.stagedCredentialRef());
        } catch (RuntimeException compensationFailure) {
            if ("BUSINESS_SESSION_STALE".equals(compensationFailure.getMessage())) {
                return new IllegalStateException("BUSINESS_SESSION_STALE");
            }
            failure.addSuppressed(compensationFailure);
        }
        try {
            installer.abort(connection, attempt.staged());
        } catch (RuntimeException projectionFailure) {
            failure.addSuppressed(projectionFailure);
        }
        if (!claimReleased) {
            return new IllegalStateException("BUSINESS_SESSION_STALE");
        }
        return normalizeAttachFailure(failure);
    }

    private RuntimeException compensateLoginFailure(TrustedDesktopConnection connection,
                                                      LoginAttempt attempt,
                                                      RuntimeException failure) {
        try {
            sessions.abortLogin(
                    connection, attempt.authenticating().authSessionId(),
                    attempt.authenticating().generation(), attempt.installationId(),
                    attempt.stagedCredentialRef());
        } catch (RuntimeException compensationFailure) {
            failure.addSuppressed(compensationFailure);
        }
        try {
            installer.abort(connection, attempt.staged());
        } catch (RuntimeException projectionFailure) {
            failure.addSuppressed(projectionFailure);
        }
        return failure;
    }

    private void requireCurrentLoginAttempt(
            TrustedDesktopConnection connection,
            LoginAttempt attempt) {
        requireFinalizedConnection(connection);
        OaSessionRecord current = repository.findByAuthSessionId(
                        attempt.authenticating().authSessionId())
                .orElseThrow(() -> new IllegalStateException("BUSINESS_SESSION_STALE"));
        if (current.phase() != OaSessionPhase.AUTHENTICATING
                || current.generation() != attempt.authenticating().generation()
                || !current.desktopInstanceId().equals(connection.desktopInstanceId())
                || !current.desktopSessionId().equals(connection.desktopSessionId())) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
    }

    private void requireFinalizedConnection(TrustedDesktopConnection connection) {
        if (connections != null && !connections.isFinalized(connection)) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
    }

    private static final class LoginAttempt {
        private final OaSessionRecord authenticating;
        private OaSessionRecord staged;

        private LoginAttempt(OaSessionRecord authenticating) {
            this.authenticating = Objects.requireNonNull(authenticating, "authenticating");
        }

        OaSessionRecord authenticating() {
            return authenticating;
        }

        void markStaged(OaSessionRecord staged) {
            this.staged = Objects.requireNonNull(staged, "staged");
        }

        String installationId() {
            return staged == null ? null : staged.installationId();
        }

        OaSessionRecord staged() {
            return staged;
        }

        String stagedCredentialRef() {
            return staged == null ? null : staged.stagedCredentialRef();
        }
    }

    private static final class RestoreAttempt {
        private final OaSessionRecord restoring;
        private final Instant absoluteDeadline;
        private OaSessionRecord staged;

        private RestoreAttempt(OaSessionRecord restoring, Instant absoluteDeadline) {
            this.restoring = Objects.requireNonNull(restoring, "restoring");
            this.absoluteDeadline = absoluteDeadline;
        }

        OaSessionRecord restoring() {
            return restoring;
        }

        Instant absoluteDeadline() {
            return absoluteDeadline;
        }

        void markStaged(OaSessionRecord staged) {
            this.staged = Objects.requireNonNull(staged, "staged");
        }

        String installationId() {
            return staged == null ? null : staged.installationId();
        }

        OaSessionRecord staged() {
            return staged;
        }

        String stagedCredentialRef() {
            return staged == null ? null : staged.stagedCredentialRef();
        }
    }

    public BusinessAuthDtos.Session logout(TrustedDesktopConnection connection) {
        attachHandles.revoke(connection);
        candidateTickets.entrySet().removeIf(entry -> entry.getValue().matches(connection));
        ReadyOaSessionLease binaryLease = identities.current(connection)
                .flatMap(identity -> sessions.currentReady(connection, identity))
                .orElse(null);
        OaSessionRecord current = repository.findByDesktopSession(
                connection.desktopInstanceId(), connection.desktopSessionId()).orElse(null);
        LogoutStart logoutStart;
        synchronized (connection) {
            logoutStart = LogoutStart.capture(
                    current,
                    identities.installed(connection).orElse(null),
                    identities.installationLease(connection).orElse(null),
                    rememberedAccounts.get(connectionSlot(connection)));
        }
        if (current == null || current.phase() == OaSessionPhase.SIGNED_OUT) {
            revokeInstallationAndForget(connection, logoutStart, current);
            return session(connection);
        }
        if (current.phase() == OaSessionPhase.REVOKED) {
            try {
                persistence.normalizeLegacyRevoked(current.authSessionId(), current.generation());
            } finally {
                revokeInstallationAndForget(connection, logoutStart, current);
            }
            return session(connection);
        }
        OaSessionRecord revoking;
        try {
            revoking = sessions.revokeBeforeCleanup(
                    connection,
                    BusinessOaSessionRegistry.RevocationReason.LOGOUT,
                    current);
        } catch (IllegalStateException stale) {
            if (!"BUSINESS_SESSION_STALE".equals(stale.getMessage())) {
                throw stale;
            }
            revokeInstallationAndForget(connection, logoutStart, current);
            return session(connection);
        }
        revokeInstallationAndForget(connection, logoutStart, current);
        revokeBinaryLease(connection, binaryLease);
        bestEffortRemoteLogout(revoking);
        persistence.revoke(revoking.authSessionId(), revoking.generation());
        return session(connection);
    }

    @Override
    public void terminate(ReadyOaSessionLease lease, OaRemoteRequestException.TerminalReason reason) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(reason, "reason");
        TerminalNotificationKey notificationKey = TerminalNotificationKey.from(lease);
        PendingTerminalNotification pending = pendingTerminalNotifications.get(notificationKey);
        if (pending != null) {
            completePendingTerminalNotification(notificationKey, pending);
            return;
        }
        TrustedDesktopConnection connection = connections.findByWebSocketSessionId(lease.webSocketSessionId())
                .filter(candidate -> candidate.desktopInstanceId().equals(lease.desktopInstanceId()))
                .filter(candidate -> candidate.desktopSessionId().equals(lease.desktopSessionId()))
                .orElseThrow(OaAuthenticatedRequestExecutor.StaleLeaseException::new);
        OaSessionRecord current = repository.findByAuthSessionId(lease.authSessionId())
                .filter(record -> record.phase() == OaSessionPhase.READY)
                .filter(record -> record.generation() == lease.generation())
                .filter(record -> record.desktopInstanceId().equals(lease.desktopInstanceId()))
                .filter(record -> record.desktopSessionId().equals(lease.desktopSessionId()))
                .filter(record -> Objects.equals(record.activeCredentialRef(), lease.activeCredentialRef()))
                .filter(record -> record.credentialVersion() == lease.credentialVersion())
                .orElseThrow(OaAuthenticatedRequestExecutor.StaleLeaseException::new);
        LogoutStart terminalStart = LogoutStart.capture(
                current,
                identities.current(connection).orElse(null),
                identities.installationLease(connection).orElse(null),
                rememberedAccounts.get(connectionSlot(connection)));
        BusinessOaSessionRegistry.RevocationClaim revocation = sessions.claimRevocationBeforeCleanup(
                connection,
                reason == OaRemoteRequestException.TerminalReason.MEMBERSHIP_EXPIRED
                        ? BusinessOaSessionRegistry.RevocationReason.MEMBERSHIP_EXPIRED
                        : BusinessOaSessionRegistry.RevocationReason.AUTH_EXPIRED,
                current);
        if (!revocation.winner()) {
            throw new OaAuthenticatedRequestExecutor.StaleLeaseException();
        }
        OaSessionRecord revoking = revocation.record();
        attachHandles.revoke(connection);
        candidateTickets.entrySet().removeIf(entry -> entry.getValue().matches(connection));
        revokeInstallationAndForget(connection, terminalStart, current);
        revokeBinaryLease(connection, lease);
        bestEffortRemoteLogout(revoking);
        OaSessionRecord signedOut = persistence.revoke(revoking.authSessionId(), revoking.generation());
        PendingTerminalNotification notification = new PendingTerminalNotification(
                lease, revoking, signedOut, reason);
        pendingTerminalNotifications.put(notificationKey, notification);
        completePendingTerminalNotification(notificationKey, notification);
    }

    private void completePendingTerminalNotification(
            TerminalNotificationKey key,
            PendingTerminalNotification pending) {
        synchronized (pending) {
            if (pendingTerminalNotifications.get(key) != pending) return;
            persistence.drainReleasedCredentialCleanupStrict(
                    pending.revoking(), pending.signedOut());
            if (!pendingTerminalNotifications.remove(key, pending)) return;
            try {
                stateNotifier.signedOut(
                        pending.lease(), pending.signedOut(), pending.reason());
            } catch (RuntimeException notificationFailure) {
                log.warn("Business auth state notification failed: reasonType={}",
                        notificationFailure.getClass().getSimpleName());
            }
        }
    }

    private void bestEffortRemoteLogout(OaSessionRecord revoking) {
        String remoteCredentialRef = revoking.stagedCredentialRef() != null
                ? revoking.stagedCredentialRef() : revoking.activeCredentialRef();
        try (OaSessionCredentialStore.CredentialMaterial material =
                     credentials.load(remoteCredentialRef)) {
            if (material != null) {
                gateway.logout(revoking.tenantId(), material.accessToken());
            }
        } catch (RuntimeException ignored) {
            // Credential reads, remote logout and credential material close are best effort.
            // Durable revocation and exact projection cleanup below remain fail-visible.
        }
    }

    private void revokeBinaryLease(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        if (binaryLeaseLifecycle != null && lease != null) {
            binaryLeaseLifecycle.revoke(connection, lease);
        }
    }

    private void revokeInstallationAndForget(
            TrustedDesktopConnection connection,
            LogoutStart logoutStart,
            OaSessionRecord durableTarget) {
        try {
            revokeInstallation(connection, logoutStart);
        } finally {
            forgetRememberedAccount(connection, logoutStart, durableTarget);
        }
    }

    private void revokeInstallation(
            TrustedDesktopConnection connection,
            LogoutStart logoutStart) {
        installer.revoke(connection, logoutStart.installationLease());
    }

    private void forgetRememberedAccount(
            TrustedDesktopConnection connection,
            LogoutStart logoutStart,
            OaSessionRecord durableTarget) {
        RememberedAccountBinding binding = logoutStart.rememberedAccount();
        rememberedAccounts.computeIfPresent(connectionSlot(connection), (ignored, current) ->
                binding != null && binding.equals(current)
                        || logoutStart.matchesRememberedAccount(durableTarget, current)
                        ? null : current);
    }

    private static DesktopConnectionSlot connectionSlot(TrustedDesktopConnection connection) {
        return new DesktopConnectionSlot(
                connection.desktopInstanceId(), connection.desktopSessionId());
    }

    private void rememberReadyAccount(
            TrustedDesktopConnection connection,
            String account,
            String installationId,
            ReadyOaSessionLease readyLease) {
        RememberedAccountBinding candidate = new RememberedAccountBinding(
                account, readyLease.authSessionId(), readyLease.generation(), installationId);
        if (!sessions.isCurrent(readyLease)
                || identities.installationLease(connection)
                .filter(lease -> installationId.equals(lease.installationId()))
                .isEmpty()) {
            return;
        }
        rememberedAccounts.compute(connectionSlot(connection), (ignored, current) -> {
            if (!sessions.isCurrent(readyLease)) {
                return current;
            }
            return shouldReplaceRememberedAccount(current, candidate)
                    ? candidate : current;
        });
    }

    private static boolean shouldReplaceRememberedAccount(
            RememberedAccountBinding current,
            RememberedAccountBinding candidate) {
        if (current == null || !current.authSessionId().equals(candidate.authSessionId())) {
            return true;
        }
        if (current.readyGeneration() != candidate.readyGeneration()) {
            return current.readyGeneration() < candidate.readyGeneration();
        }
        return !current.installationId().equals(candidate.installationId());
    }

    private String rememberedAccount(TrustedDesktopConnection connection) {
        RememberedAccountBinding binding = rememberedAccounts.get(connectionSlot(connection));
        return binding == null ? null : binding.account();
    }

    private OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, OaAuthDtos.OaCredential credential) {
        char[] token = credential.accessToken().toCharArray();
        try { return gateway.loadPermissions(tenantId, token); }
        finally { wipe(token); }
    }

    private OaSessionRecord ensureSession(TrustedDesktopConnection connection) {
        return repository.findByDesktopSession(connection.desktopInstanceId(), connection.desktopSessionId())
                .orElseGet(() -> repository.insert(OaSessionRecord.signedOut(
                        UUID.randomUUID().toString(), connection.desktopInstanceId(), connection.desktopSessionId(), Instant.now())));
    }

    private static void requirePhase(OaSessionRecord record, OaSessionPhase expected) {
        if (record.phase() != expected) throw new IllegalStateException("OA session is not in required phase");
    }

    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required"); }
    private static void requireMaterial(char[] value, String name) { if (value == null || value.length == 0) throw new IllegalArgumentException(name + " is required"); }
    private static void wipe(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
    private void cleanupTickets() { Instant now = Instant.now(); candidateTickets.values().removeIf(ticket -> ticket.expiresAt().isBefore(now)); }

    private record TerminalNotificationKey(
            String authSessionId,
            String desktopInstanceId,
            String desktopSessionId,
            String webSocketSessionId,
            long generation,
            int credentialVersion) {
        private static TerminalNotificationKey from(ReadyOaSessionLease lease) {
            return new TerminalNotificationKey(
                    lease.authSessionId(),
                    lease.desktopInstanceId(),
                    lease.desktopSessionId(),
                    lease.webSocketSessionId(),
                    lease.generation(),
                    lease.credentialVersion());
        }
    }

    private record PendingTerminalNotification(
            ReadyOaSessionLease lease,
            OaSessionRecord revoking,
            OaSessionRecord signedOut,
            OaRemoteRequestException.TerminalReason reason) {
        private PendingTerminalNotification {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(revoking, "revoking");
            Objects.requireNonNull(signedOut, "signedOut");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private record CandidateTicket(String id, String desktopInstanceId, String desktopSessionId, String account,
                                   OaAuthDtos.OaTenantCandidate candidate, Instant expiresAt) {
        boolean expired() { return expiresAt.isBefore(Instant.now()); }
        boolean matches(TrustedDesktopConnection c) { return desktopInstanceId.equals(c.desktopInstanceId()) && desktopSessionId.equals(c.desktopSessionId()); }
    }

    private record RememberedAccountBinding(
            String account,
            String authSessionId,
            long readyGeneration,
            String installationId) {
        private RememberedAccountBinding {
            requireText(account, "account");
            requireText(authSessionId, "authSessionId");
            requireText(installationId, "installationId");
        }
    }

    private record DesktopConnectionSlot(
            String desktopInstanceId,
            String desktopSessionId) {
    }

    private record LogoutStart(
            TrustedBusinessIdentity identity,
            ApplicationInstallationLease installationLease,
            RememberedAccountBinding rememberedAccount) {
        static LogoutStart capture(
                OaSessionRecord durableTarget,
                TrustedBusinessIdentity identity,
                ApplicationInstallationLease installationLease,
                RememberedAccountBinding rememberedAccount) {
            if (!projectionBelongsTo(durableTarget, identity, installationLease)) {
                identity = null;
                installationLease = null;
            }
            if (!rememberedAccountBelongsTo(durableTarget, rememberedAccount)) {
                rememberedAccount = null;
            }
            return new LogoutStart(identity, installationLease, rememberedAccount);
        }

        boolean matchesRememberedAccount(
                OaSessionRecord durableTarget,
                RememberedAccountBinding candidate) {
            return rememberedAccountBelongsTo(durableTarget, candidate)
                    && (installationLease == null
                    || installationLease.installationId().equals(candidate.installationId()));
        }

        private static boolean projectionBelongsTo(
                OaSessionRecord durableTarget,
                TrustedBusinessIdentity identity,
                ApplicationInstallationLease installationLease) {
            if (durableTarget == null || identity == null || installationLease == null
                    || !identity.authSessionId().equals(durableTarget.authSessionId())
                    || installationLease.targetGeneration() == Long.MAX_VALUE
                    || identity.identityEpoch() != installationLease.targetGeneration() + 1) {
                return false;
            }
            long generationDelta = durableTarget.generation() - installationLease.targetGeneration();
            return switch (durableTarget.phase()) {
                case INSTALLING -> generationDelta == 0
                        && Objects.equals(durableTarget.installationId(), installationLease.installationId())
                        && durableTarget.installationTargetGeneration()
                        == installationLease.targetGeneration();
                case READY -> generationDelta >= 1;
                case DETACHED -> generationDelta == 2;
                case REVOKING -> generationDelta >= 1 && generationDelta <= 3;
                case REVOKED, SIGNED_OUT -> generationDelta >= 2 && generationDelta <= 4;
                case AUTHENTICATING, RESTORING -> false;
            };
        }

        private static boolean rememberedAccountBelongsTo(
                OaSessionRecord durableTarget,
                RememberedAccountBinding rememberedAccount) {
            if (durableTarget == null || rememberedAccount == null
                    || !rememberedAccount.authSessionId().equals(durableTarget.authSessionId())) {
                return false;
            }
            long generationDelta = durableTarget.generation() - rememberedAccount.readyGeneration();
            return switch (durableTarget.phase()) {
                case READY -> generationDelta >= 0;
                case DETACHED -> generationDelta >= 1;
                case REVOKING -> generationDelta >= 1;
                case REVOKED, SIGNED_OUT -> generationDelta >= 2;
                case AUTHENTICATING, RESTORING, INSTALLING -> false;
            };
        }
    }
}
