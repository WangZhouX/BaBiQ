package com.wzx.babiq.server.business.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.session.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** Atomically installs server-owned identity/catalog/context before exposing a READY lease. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessOaReadyInstaller {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_NAVIGATION_PATHS = Set.of(
            "/", "/lawoa", "/bpm", "/approval", "/case", "/administration", "/management",
            "/customer", "/cost", "/consultant", "/lawyer-admin", "/tools", "/team");
    private final ApplicationIdentityRegistry identities;
    private final ApplicationCatalogRegistry catalogs;
    private final ApplicationPageContextRegistry contexts;
    private final OaSessionPersistenceService persistence;
    private final BusinessOaSessionRegistry sessions;

    public BusinessOaReadyInstaller(ApplicationIdentityRegistry identities,
                                    ApplicationCatalogRegistry catalogs,
                                    ApplicationPageContextRegistry contexts,
                                    OaSessionPersistenceService persistence,
                                    BusinessOaSessionRegistry sessions) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ReadyOaSessionLease install(TrustedDesktopConnection connection,
                                       OaSessionRecord staged,
                                       String userId,
                                       String tenantId,
                                       String platformId,
                                       OaAuthDtos.OaPermissionSnapshot permissions,
                                       BiFunction<OaSessionRecord, Runnable, ReadyOaSessionLease> publishReady) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(publishReady, "publishReady");
        if (staged.phase() != OaSessionPhase.INSTALLING) {
            throw new IllegalStateException("OA session installation is not staged");
        }
        if (!Objects.equals(userId, permissions.userId())) {
            throw new IllegalStateException("OA permission identity mismatch");
        }
        ApplicationInstallationLease installationLease = installationLease(staged, connection);
        abort(connection, installationLease);
        try {
            long epoch = Math.incrementExact(staged.generation());
            identities.installServer(connection, installationLease, staged.authSessionId(), epoch,
                    userId, tenantId, platformId,
                    java.util.Set.copyOf(permissions.roles()), java.util.Set.copyOf(permissions.permissions()),
                    menuPaths(permissions.menus()));
            ObjectNode catalog = JSON.createObjectNode();
            catalog.putObject("actions");
            catalogs.installServer(connection, installationLease, 1, catalog);
            ObjectNode context = JSON.createObjectNode();
            contexts.installServer(connection, installationLease, 1, 1, context);
            OaSessionRecord ready = persistence.activate(staged.authSessionId(), staged.generation(),
                    staged.installationId(), connection, userId, tenantId, platformId);
            return publishReady.apply(ready, () -> commit(connection, installationLease));
        } catch (RuntimeException failure) {
            abort(connection, installationLease);
            throw failure;
        }
    }

    private void commit(
            TrustedDesktopConnection connection,
            ApplicationInstallationLease installationLease) {
        identities.provisional(connection, installationLease)
                .orElseThrow(() -> new IllegalStateException("Identity installation is stale"));
        catalogs.provisional(connection, installationLease)
                .orElseThrow(() -> new IllegalStateException("Catalog installation is stale"));
        contexts.provisional(connection, installationLease)
                .orElseThrow(() -> new IllegalStateException("Context installation is stale"));
        contexts.commitInstallation(connection, installationLease);
        catalogs.commitInstallation(connection, installationLease);
        identities.commitInstallation(connection, installationLease);
    }

    /** Clears only projections created by the complete staged installation attempt. */
    public void abort(TrustedDesktopConnection connection, OaSessionRecord staged) {
        Objects.requireNonNull(connection, "connection");
        if (staged == null) {
            return;
        }
        abort(connection, installationLease(staged, connection));
    }

    /** Clears only projections that still carry the complete expected installation lease. */
    public void abort(
            TrustedDesktopConnection connection,
            ApplicationInstallationLease installationLease) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(installationLease, "installationLease").requireOwner(connection);
        contexts.clearInstallation(connection, installationLease);
        catalogs.clearInstallation(connection, installationLease);
        identities.clearInstallation(connection, installationLease);
    }

    /** Explicit revocation clears only the exact installation captured when logout began. */
    public void revoke(
            TrustedDesktopConnection connection,
            ApplicationInstallationLease expectedInstallation) {
        Objects.requireNonNull(connection, "connection");
        if (expectedInstallation == null) {
            return;
        }
        expectedInstallation.requireOwner(connection);
        contexts.clearInstallation(connection, expectedInstallation);
        catalogs.clearInstallation(connection, expectedInstallation);
        identities.revokeInstallation(connection, expectedInstallation);
    }

    private static ApplicationInstallationLease installationLease(
            OaSessionRecord staged,
            TrustedDesktopConnection connection) {
        if (!staged.desktopInstanceId().equals(connection.desktopInstanceId())
                || !staged.desktopSessionId().equals(connection.desktopSessionId())
                || !Objects.equals(staged.installationOwnerDesktopInstanceId(), connection.desktopInstanceId())
                || !Objects.equals(staged.installationOwnerDesktopSessionId(), connection.desktopSessionId())) {
            throw new IllegalStateException("OA installation owner mismatch");
        }
        if (staged.installationTargetGeneration() != staged.generation()) {
            throw new IllegalStateException("OA installation generation mismatch");
        }
        return new ApplicationInstallationLease(
                staged.installationId(), connection, staged.installationTargetGeneration(),
                staged.installationExpiresAt());
    }

    private static Set<String> menuPaths(List<Object> menus) {
        Set<String> paths = new LinkedHashSet<>();
        collectMenuPaths(menus, paths);
        return Set.copyOf(paths);
    }

    private static void collectMenuPaths(Object value, Set<String> paths) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectMenuPaths(item, paths));
            return;
        }
        if (!(value instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object candidate = entry.getValue();
            if (("path".equalsIgnoreCase(key) || "url".equalsIgnoreCase(key))
                    && candidate instanceof String text) {
                String normalized = "/index".equals(text) || "/index/unfinished".equals(text) ? "/" : text;
                if (ALLOWED_NAVIGATION_PATHS.contains(normalized)) paths.add(normalized);
            } else if ("children".equalsIgnoreCase(key)) {
                collectMenuPaths(candidate, paths);
            }
        }
    }
}
