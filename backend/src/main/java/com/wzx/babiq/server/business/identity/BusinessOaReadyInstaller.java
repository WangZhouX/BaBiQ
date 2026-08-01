package com.wzx.babiq.server.business.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
            ObjectNode catalog = workbenchCatalog();
            catalogs.installServer(connection, installationLease, 1, catalog);
            ObjectNode context = workbenchContext(epoch, menuPaths(permissions.menus()), catalog.path("actions"));
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

    private static ObjectNode workbenchCatalog() {
        ObjectNode actions = JSON.createObjectNode();
        actions.set("business_workbench_read", action(
                "business_workbench_read",
                "读取工作台",
                "通过当前 READY 身份查询工作台、列表、日程和选项。",
                "read_only",
                "view"));
        actions.set("business_schedule_mutate", action(
                "business_schedule_mutate",
                "修改工作台日程",
                "通过当前 READY 身份完成日程状态、排序或创建。",
                "high_risk",
                "operation"));
        return JSON.createObjectNode().set("actions", actions);
    }

    private static ObjectNode action(
            String id,
            String title,
            String description,
            String risk,
            String discriminator) {
        ObjectNode action = JSON.createObjectNode()
                .put("id", id)
                .put("version", 1)
                .put("enabled", true)
                .put("title", title)
                .put("description", description)
                .put("risk", risk)
                .put("authorization", "current_ready_oa_identity");
        // OA does not expose stable workbench permission codes here. READY identity and BFF data-scope
        // validation remain the authoritative authorization boundary instead of inventing local codes.
        action.putArray("requiredPermissions");
        ObjectNode schema = action.putObject("inputSchema")
                .put("type", "object")
                .put("additionalProperties", false);
        ObjectNode request = schema.putObject("properties")
                .putObject("request")
                .put("type", "object")
                .put("additionalProperties", false);
        request.putObject("properties").putObject(discriminator).put("type", "string");
        request.putArray("required").add(discriminator);
        schema.putArray("required").add("request");
        return action;
    }

    private static ObjectNode workbenchContext(
            long identityEpoch,
            Set<String> grantedNavigation,
            com.fasterxml.jackson.databind.JsonNode actions) {
        ObjectNode context = JSON.createObjectNode()
                .put("pageId", "business.workbench")
                .put("pageTitle", "工作台")
                .put("route", "/")
                .put("contextRevision", 1)
                .put("identityEpoch", identityEpoch)
                .put("selectedScope", "PERSONAL")
                .put("selectedKind", "CASE");
        ArrayNode navigation = context.putArray("navigation");
        addNavigation(navigation, "/", "工作台");
        for (String path : List.of(
                "/case", "/customer", "/lawoa", "/bpm", "/approval", "/administration",
                "/management", "/cost", "/consultant", "/lawyer-admin", "/tools", "/team")) {
            if (grantedNavigation.contains(path)) {
                addNavigation(navigation, path, navigationTitle(path));
            }
        }
        context.putArray("sections")
                .add("notices")
                .add("shortcuts")
                .add("summary")
                .add("profile")
                .add("teams")
                .add("schedule");
        ArrayNode available = context.putArray("availableActions");
        actions.fields().forEachRemaining(entry -> available.addObject()
                .put("id", entry.getKey())
                .put("enabled", true)
                .put("title", entry.getValue().path("title").asText())
                .put("description", entry.getValue().path("description").asText()));
        return context;
    }

    private static void addNavigation(ArrayNode navigation, String path, String title) {
        navigation.addObject().put("path", path).put("title", title);
    }

    private static String navigationTitle(String path) {
        return switch (path) {
            case "/case" -> "案件管理";
            case "/customer" -> "客户管理";
            case "/lawoa" -> "律所业务";
            case "/bpm" -> "流程审批";
            case "/approval" -> "审批中心";
            case "/administration" -> "行政管理";
            case "/management" -> "经营管理";
            case "/cost" -> "费用管理";
            case "/consultant" -> "顾问服务";
            case "/lawyer-admin" -> "律师管理";
            case "/tools" -> "工具中心";
            case "/team" -> "团队管理";
            default -> path;
        };
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
