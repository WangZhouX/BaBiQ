package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Coordinates one refresh per OA session generation, with all remote I/O outside registry locks. */
public final class OaTokenRefreshCoordinator {
    private final BusinessOaSessionRegistry registry;
    private final OaSessionRepository repository;
    private final OaSessionPersistenceService persistence;
    private final OaSessionCredentialStore credentials;
    private final OaAuthenticationGateway gateway;
    private final ConcurrentMap<RefreshKey, CompletableFuture<ReadyOaSessionLease>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletedRefresh> completedRefreshes = new ConcurrentHashMap<>();

    public OaTokenRefreshCoordinator(BusinessOaSessionRegistry registry, OaSessionRepository repository,
                                     OaSessionPersistenceService persistence, OaSessionCredentialStore credentials,
                                     OaAuthenticationGateway gateway) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public CompletableFuture<ReadyOaSessionLease> refresh(ReadyOaSessionLease lease) {
        Objects.requireNonNull(lease, "lease");
        RefreshKey key = RefreshKey.from(lease);
        CompletableFuture<ReadyOaSessionLease> candidate = new CompletableFuture<>();
        CompletableFuture<ReadyOaSessionLease> existing = inFlight.putIfAbsent(key, candidate);
        if (existing != null) return existing;
        try {
            ReadyOaSessionLease successor = completedSuccessor(key);
            if (successor != null) {
                candidate.complete(successor);
                inFlight.remove(key, candidate);
                return candidate;
            }
            if (!registry.isCurrent(lease)) {
                candidate.completeExceptionally(new IllegalStateException("OA session lease is stale"));
                inFlight.remove(key, candidate);
                return candidate;
            }
        } catch (Throwable failure) {
            candidate.completeExceptionally(failure);
            inFlight.remove(key, candidate);
            return candidate;
        }
        CompletableFuture.runAsync(() -> {
            try {
                CompletedRefresh completed = performRefresh(lease, key);
                rememberCompletedRefresh(completed);
                candidate.complete(completed.successor());
            } catch (Throwable failure) {
                candidate.completeExceptionally(failure);
            } finally {
                inFlight.remove(key, candidate);
            }
        });
        return candidate;
    }

    private ReadyOaSessionLease completedSuccessor(RefreshKey key) {
        CompletedRefresh completed = completedRefreshes.get(key.authSessionId());
        if (completed == null || !completed.predecessor().equals(key)) {
            return null;
        }
        if (!registry.isCurrent(completed.successor())) {
            completedRefreshes.remove(key.authSessionId(), completed);
            return null;
        }
        return completed.successor();
    }

    private void rememberCompletedRefresh(CompletedRefresh completed) {
        completedRefreshes.compute(completed.successor().authSessionId(), (ignored, current) ->
                current == null
                        || current.successor().generation() <= completed.successor().generation()
                        ? completed : current);
    }

    private CompletedRefresh performRefresh(
            ReadyOaSessionLease lease,
            RefreshKey predecessor) {
        OaSessionRecord current = repository.findByAuthSessionId(lease.authSessionId())
                .filter(record -> record.phase() == OaSessionPhase.READY)
                .filter(record -> record.generation() == lease.generation())
                .filter(record -> record.desktopInstanceId().equals(predecessor.desktopInstanceId()))
                .filter(record -> record.desktopSessionId().equals(predecessor.desktopSessionId()))
                .filter(record -> Objects.equals(record.userId(), predecessor.userId()))
                .filter(record -> Objects.equals(record.tenantId(), predecessor.tenantId()))
                .filter(record -> Objects.equals(record.platformId(), predecessor.platformId()))
                .filter(record -> Objects.equals(record.activeCredentialRef(), predecessor.activeCredentialRef()))
                .filter(record -> record.credentialVersion() == predecessor.credentialVersion())
                .orElseThrow(() -> new IllegalStateException("OA session lease is stale"));
        OaSessionCredentialStore.CredentialMaterial material = credentials.load(current.activeCredentialRef());
        if (material == null) throw new IllegalStateException("OA session credential is unavailable");
        OaAuthDtos.OaCredential refreshed;
        try {
            refreshed = gateway.refresh(current.tenantId(), material.refreshToken());
        } finally {
            material.close();
        }
        if (refreshed == null || refreshed.accessToken() == null || refreshed.refreshToken() == null) {
            throw new IllegalStateException("OA refresh returned no credential");
        }
        if (!Objects.equals(predecessor.userId(), refreshed.userId())) {
            throw new IllegalStateException("OA refresh identity mismatch");
        }
        com.wzx.babiq.server.application.auth.TrustedDesktopConnection owner =
                new com.wzx.babiq.server.application.auth.TrustedDesktopConnection(
                        "server-refresh", current.desktopInstanceId(), current.desktopSessionId(), lease.webSocketSessionId());
        OaSessionRecord staged = persistence.stage(current.authSessionId(), current.generation(), owner,
                refreshed.accessToken().toCharArray(), refreshed.refreshToken().toCharArray());
        OaSessionRecord active = persistence.activate(staged.authSessionId(), staged.generation(), staged.installationId(), owner,
                refreshed.userId() == null ? current.userId() : refreshed.userId(), current.tenantId(), current.platformId());
        ReadyOaSessionLease successor = registry.captureReady(
                active.authSessionId(), active.desktopInstanceId(),
                active.desktopSessionId(), lease.webSocketSessionId());
        return new CompletedRefresh(predecessor, successor, staged.installationId());
    }

    private record RefreshKey(
            String authSessionId,
            String desktopInstanceId,
            String desktopSessionId,
            String webSocketSessionId,
            String userId,
            String tenantId,
            String platformId,
            long generation,
            String activeCredentialRef,
            int credentialVersion) {
        private static RefreshKey from(ReadyOaSessionLease lease) {
            return new RefreshKey(
                    lease.authSessionId(),
                    lease.desktopInstanceId(),
                    lease.desktopSessionId(),
                    lease.webSocketSessionId(),
                    lease.userId(),
                    lease.tenantId(),
                    lease.platformId(),
                    lease.generation(),
                    lease.activeCredentialRef(),
                    lease.credentialVersion());
        }
    }

    private record CompletedRefresh(
            RefreshKey predecessor,
            ReadyOaSessionLease successor,
            String installationId) {
        private CompletedRefresh {
            Objects.requireNonNull(predecessor, "predecessor");
            Objects.requireNonNull(successor, "successor");
            Objects.requireNonNull(installationId, "installationId");
        }
    }
}
