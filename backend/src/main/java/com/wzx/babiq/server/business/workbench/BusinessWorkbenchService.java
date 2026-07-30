package com.wzx.babiq.server.business.workbench;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.client.OaWorkbenchGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.OaAuthenticatedRequestExecutor;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessResourceHandleRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;

/** Server-owned workbench BFF. OA credentials are available only inside the executor callback. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessWorkbenchService {
    private static final Set<String> NAVIGATION_PATHS = Set.of(
            "/", "/lawoa", "/bpm", "/approval", "/case", "/administration", "/management",
            "/customer", "/cost", "/consultant", "/lawyer-admin", "/tools", "/team");
    private final OaWorkbenchGateway gateway;
    private final BusinessDataScopeValidator scopes;
    private final BusinessOaSessionRegistry sessions;
    private final OaAuthenticatedRequestExecutor executor;
    private final BusinessResourceHandleRegistry resourceHandles;

    public BusinessWorkbenchService(OaWorkbenchGateway gateway, BusinessDataScopeValidator scopes,
                                    BusinessOaSessionRegistry sessions,
                                    OaAuthenticatedRequestExecutor executor) {
        this(gateway, scopes, sessions, executor, null);
    }

    @Autowired
    public BusinessWorkbenchService(OaWorkbenchGateway gateway, BusinessDataScopeValidator scopes,
                                    BusinessOaSessionRegistry sessions,
                                    OaAuthenticatedRequestExecutor executor,
                                    BusinessResourceHandleRegistry resourceHandles) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.resourceHandles = resourceHandles;
    }

    /** Kept for isolated mapper/service tests; production construction always supplies the session boundary. */
    BusinessWorkbenchService(OaWorkbenchGateway gateway, BusinessDataScopeValidator scopes) {
        this.gateway = gateway;
        this.scopes = scopes;
        this.sessions = null;
        this.executor = null;
        this.resourceHandles = null;
    }

    public BusinessWorkbenchDtos.PageResult page(ReadyOaSessionLease lease,
                                                  TrustedBusinessIdentity identity,
                                                  BusinessWorkbenchDtos.PageRequest request) {
        requireLease(lease, identity);
        scopes.validate(request);
        OaWorkbenchDtos.PageQuery query = pageQuery(request);
        OaWorkbenchDtos.PageResult remote = executor.execute(lease, OaAuthenticatedRequestExecutor.RequestKind.READ,
                token -> {
                    if ("TEAM".equals(request.scope())) {
                        requireVisibleTeam(gateway.teams(lease.tenantId(), token), request.teamId());
                        if (request.roleCode() != null) {
                            requireConfiguredRole(gateway.teamRoles(
                                    lease.tenantId(), token, request.teamId(), request.kind()), request.roleCode());
                        }
                    }
                    return gateway.page(query, lease.tenantId(), token);
                });
        return BusinessWorkbenchMapper.page(request.kind(), remote);
    }

    public BusinessWorkbenchDtos.Snapshot snapshot(ReadyOaSessionLease lease,
                                                    TrustedBusinessIdentity identity,
                                                    String month,
                                                    String day) {
        requireLease(lease, identity);
        List<String> issues = new ArrayList<>();
        BusinessWorkbenchDtos.Section notices = section(() -> executor.execute(lease, read(),
                token -> gateway.notices(lease.tenantId(), token, 1, 10)), issues, "notices");
        BusinessWorkbenchDtos.Section shortcuts = section(() -> executor.execute(lease, read(),
                token -> gateway.shortcuts(lease.tenantId(), token)), issues, "shortcuts");
        BusinessWorkbenchDtos.Section summary = section(() -> executor.execute(lease, read(),
                token -> gateway.summary(lease.tenantId(), token)), issues, "summary");
        BusinessWorkbenchDtos.Section profile =
                section(() -> homeInfoWithResources(lease, identity), issues, "profile");
        BusinessWorkbenchDtos.Section teams = section(() -> executor.execute(lease, read(),
                token -> gateway.teams(lease.tenantId(), token)), issues, "teams");
        BusinessWorkbenchDtos.Section schedule = section(() -> {
            Map<String, Object> value = new LinkedHashMap<>();
            try (var reads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var count = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> executor.execute(lease, read(),
                                token -> gateway.scheduleCount(lease.tenantId(), token)),
                        reads);
                var dayResult = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> executor.execute(lease, read(),
                                token -> gateway.scheduleDay(lease.tenantId(), token)),
                        reads);
                value.put("count", count.join());
                value.put("day", dayResult.join());
            }
            return value;
        }, issues, "schedule");
        return new BusinessWorkbenchDtos.Snapshot(notices, shortcuts, summary, profile, teams, schedule, issues);
    }

    public BusinessWorkbenchDtos.NavigationEnvelope navigation(ReadyOaSessionLease lease,
                                                                TrustedBusinessIdentity identity) {
        requireLease(lease, identity);
        List<BusinessWorkbenchDtos.NavigationTarget> items = List.of(
                target("WORKBENCH", "/", "工作台"), target("LAW_OA", "/lawoa", "律所业务"),
                target("BPM", "/bpm", "流程审批"), target("APPROVAL", "/approval", "审批中心"),
                target("CASE", "/case", "案件管理"), target("ADMINISTRATION", "/administration", "行政管理"),
                target("MANAGEMENT", "/management", "经营管理"), target("CUSTOMER", "/customer", "客户管理"),
                target("COST", "/cost", "费用管理"), target("CONSULTANT", "/consultant", "顾问服务"),
                target("LAWYER_ADMIN", "/lawyer-admin", "律师管理"), target("TOOLS", "/tools", "工具中心"),
                target("TEAM", "/team", "团队管理"));
        items = items.stream()
                .filter(item -> "/".equals(item.path()) || identity.navigationPaths().contains(item.path()))
                .toList();
        return new BusinessWorkbenchDtos.NavigationEnvelope(identity.identityEpoch(), lease.generation(), items);
    }

    public BusinessWorkbenchDtos.HomeInfoEnvelope homeInfo(ReadyOaSessionLease lease,
                                                            TrustedBusinessIdentity identity) {
        requireLease(lease, identity);
        BusinessWorkbenchDtos.Section section =
                section(() -> homeInfoWithResources(lease, identity), new ArrayList<>(), "profile");
        return new BusinessWorkbenchDtos.HomeInfoEnvelope(identity.identityEpoch(), lease.generation(), section);
    }

    private OaWorkbenchDtos.UserHomeInfo homeInfoWithResources(
            ReadyOaSessionLease lease,
            TrustedBusinessIdentity identity) {
        return executor.execute(lease, read(), token -> {
            OaWorkbenchDtos.UserHomeInfo info = gateway.homeInfo(lease.tenantId(), token);
            if (resourceHandles == null || info == null || info.avatar() == null || info.avatar().isBlank()) {
                return info;
            }
            try {
                OaWorkbenchGateway.RemoteResource resource =
                        gateway.fetchResource(lease.tenantId(), token, info.avatar());
                var descriptor = resourceHandles.register(
                        new com.wzx.babiq.server.application.auth.TrustedDesktopConnection(
                                identity.reservationId(), identity.desktopInstanceId(),
                                identity.desktopSessionId(), identity.webSocketSessionId()),
                        lease, resource.mediaType(), resource.bytes(), Duration.ofMinutes(5));
                return new OaWorkbenchDtos.UserHomeInfo(
                        info.userId(), info.tenantId(), info.nickname(), descriptor.handle(), info.values());
            } catch (OaRemoteRequestException failure) {
                if (failure.terminal()) throw failure;
                return withoutAvatar(info);
            } catch (RuntimeException failure) {
                return withoutAvatar(info);
            }
        });
    }

    private static OaWorkbenchDtos.UserHomeInfo withoutAvatar(OaWorkbenchDtos.UserHomeInfo info) {
        return new OaWorkbenchDtos.UserHomeInfo(
                info.userId(), info.tenantId(), info.nickname(), null, info.values());
    }

    public BusinessWorkbenchDtos.TeamRolesEnvelope teamRoles(ReadyOaSessionLease lease,
                                                              TrustedBusinessIdentity identity,
                                                              String kind,
                                                              String teamId) {
        requireLease(lease, identity);
        if (!isKind(kind) || teamId == null || teamId.isBlank()) throw new IllegalArgumentException("invalid team role request");
        List<Map<String, Object>> remote = executor.execute(lease, read(),
                token -> {
                    requireVisibleTeam(gateway.teams(lease.tenantId(), token), teamId);
                    return gateway.teamRoles(lease.tenantId(), token, teamId, kind);
                });
        List<BusinessWorkbenchDtos.TeamRole> roles = remote.stream()
                .map(value -> new BusinessWorkbenchDtos.TeamRole(text(value, "roleCode", "code", "dataRoleCode"),
                        text(value, "name", "roleName", "dataRoleName")))
                .toList();
        return new BusinessWorkbenchDtos.TeamRolesEnvelope(identity.identityEpoch(), lease.generation(), roles);
    }

    private OaWorkbenchDtos.PageQuery pageQuery(BusinessWorkbenchDtos.PageRequest request) {
        Object value = request.filters().values().stream().findFirst().orElse(null);
        return new OaWorkbenchDtos.PageQuery(request.kind(), module(request.kind()), request.scope(), request.teamId(),
                request.roleCode(), request.pageNo(), request.pageSize(), canonicalFilterValue(value));
    }

    private static String canonicalFilterValue(Object value) {
        if (value == null) return null;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid filter");
            }
            return Integer.toString((int) number);
        }
        if (value instanceof String text && !text.isBlank() && text.matches("-?[0-9]+")) {
            try {
                return Integer.toString(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // Rejected below with the same stable validation error.
            }
        }
        throw new IllegalArgumentException("invalid filter");
    }

    private <T> BusinessWorkbenchDtos.Section section(java.util.concurrent.Callable<T> call,
                                                       List<String> issues,
                                                       String name) {
        try {
            T result = call.call();
            if (result == null) return BusinessWorkbenchDtos.Section.empty();
            Object safe = BusinessWorkbenchDataSanitizer.sanitize(name, result);
            return safe == null ? BusinessWorkbenchDtos.Section.empty() : BusinessWorkbenchDtos.Section.ok(safe);
        } catch (Exception failure) {
            RuntimeException fatal = fatalAuthenticationFailure(failure);
            if (fatal != null) throw fatal;
            issues.add(name);
            return BusinessWorkbenchDtos.Section.error();
        }
    }

    private static RuntimeException fatalAuthenticationFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof com.wzx.babiq.server.business.oa.session.OaRemoteRequestException remote
                && remote.terminal()) {
            return remote;
        }
        if (current instanceof com.wzx.babiq.server.business.oa.client.OaAuthenticationException authentication
                && (authentication.error()
                == com.wzx.babiq.server.business.oa.client.OaAuthenticationError.AUTH_EXPIRED
                || authentication.error()
                == com.wzx.babiq.server.business.oa.client.OaAuthenticationError.MEMBER_EXPIRED)) {
            return authentication;
        }
        if (current instanceof OaAuthenticatedRequestExecutor.StaleLeaseException stale) {
            return stale;
        }
        return null;
    }

    private void requireLease(ReadyOaSessionLease lease, TrustedBusinessIdentity identity) {
        requireIdentity(identity);
        if (lease == null || sessions == null || !sessions.isCurrent(lease)
                || !lease.authSessionId().equals(identity.authSessionId())
                || !lease.desktopSessionId().equals(identity.desktopSessionId())
                || !lease.tenantId().equals(identity.tenantId())) {
            throw new IllegalStateException("BUSINESS_SESSION_STALE");
        }
    }

    private static void requireIdentity(TrustedBusinessIdentity identity) {
        if (identity == null) throw new IllegalStateException("BUSINESS_SESSION_NOT_READY");
    }

    private static OaAuthenticatedRequestExecutor.RequestKind read() {
        return OaAuthenticatedRequestExecutor.RequestKind.READ;
    }

    private static boolean isKind(String kind) {
        return Set.of("CASE", "APPOINTMENT", "COUNSELOR_SERVICE", "VISIT").contains(kind);
    }

    private static int module(String kind) {
        return switch (kind) {
            case "CASE" -> 1007;
            case "APPOINTMENT" -> 1006;
            case "COUNSELOR_SERVICE" -> 1003;
            case "VISIT" -> 1004;
            default -> throw new IllegalArgumentException("invalid kind");
        };
    }

    private static BusinessWorkbenchDtos.NavigationTarget target(String kind, String path, String title) {
        if (!NAVIGATION_PATHS.contains(path)) throw new IllegalArgumentException("navigation path is not allowlisted");
        return new BusinessWorkbenchDtos.NavigationTarget(kind, path, title);
    }

    private static void requireVisibleTeam(List<Map<String, Object>> teams, String teamId) {
        if (teams == null || teams.stream().noneMatch(team -> matches(team, teamId, "id", "teamId"))) {
            throw new IllegalArgumentException("team is not authorized");
        }
    }

    private static void requireConfiguredRole(List<Map<String, Object>> roles, String roleCode) {
        if (roles == null || roles.stream().noneMatch(
                role -> matches(role, roleCode, "roleCode", "code", "dataRoleCode"))) {
            throw new IllegalArgumentException("role is not authorized");
        }
    }

    private static boolean matches(Map<String, Object> value, String expected, String... names) {
        if (value == null) return false;
        for (String name : names) {
            Object candidate = value.get(name);
            if (candidate != null && expected.equals(String.valueOf(candidate))) return true;
        }
        return false;
    }

    private static String text(Map<String, Object> value, String... names) {
        for (String name : names) {
            Object candidate = value.get(name);
            if (candidate != null && !String.valueOf(candidate).isBlank()) return String.valueOf(candidate);
        }
        throw new IllegalArgumentException("remote role is missing roleCode");
    }
}
