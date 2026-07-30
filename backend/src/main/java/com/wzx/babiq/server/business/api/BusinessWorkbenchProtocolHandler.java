package com.wzx.babiq.server.business.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.JsonRpcMultiMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.business.api.dto.BusinessWorkbenchDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchService;
import com.wzx.babiq.server.business.workbench.BusinessScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.List;

/** JSON-RPC boundary for server-owned workbench BFF calls. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessWorkbenchProtocolHandler implements JsonRpcMultiMethodHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of(
            "business/workbench/get", "business/workbench/navigation/get", "business/workbench/home-info/get",
            "business/workbench/page/get", "business/workbench/team-roles/list", "business/workbench/sort/update",
            "business/schedule/month/get", "business/schedule/day/get", "business/schedule/completion/set",
            "business/schedule/form/get", "business/schedule/relation-options/get",
            "business/schedule/service-projects/get", "business/schedule/create");
    private final BusinessWorkbenchService service;
    private final ObjectMapper mapper;
    private final BusinessDesktopConnectionResolver connections;
    private final BusinessOaSessionRegistry sessions;
    private final ApplicationIdentityRegistry identities;
    private final BusinessScheduleService schedules;

    @Autowired
    public BusinessWorkbenchProtocolHandler(BusinessWorkbenchService service, ObjectMapper mapper,
                                            BusinessDesktopConnectionResolver connections,
                                            BusinessOaSessionRegistry sessions,
                                            ApplicationIdentityRegistry identities,
                                            BusinessScheduleService schedules) {
        this.service = service;
        this.mapper = mapper;
        this.connections = connections;
        this.sessions = sessions;
        this.identities = identities;
        this.schedules = schedules;
    }

    /** Compatibility constructor for isolated workbench tests that do not exercise schedule mutations. */
    public BusinessWorkbenchProtocolHandler(BusinessWorkbenchService service, ObjectMapper mapper,
                                            BusinessDesktopConnectionResolver connections,
                                            BusinessOaSessionRegistry sessions,
                                            ApplicationIdentityRegistry identities) {
        this(service, mapper, connections, sessions, identities, null);
    }

    /** Isolated test constructor; production uses the full server-owned dependency graph. */
    public BusinessWorkbenchProtocolHandler(BusinessWorkbenchService service, ObjectMapper mapper) {
        this(service, mapper, null, null, null, null);
    }

    @Override public Set<String> methods() { return METHODS; }

    @Override public Object handle(String method, JsonNode params, WebSocketSession session) {
        try {
            if (connections == null || sessions == null || identities == null) throw new IllegalStateException("business session boundary unavailable");
            TrustedDesktopConnection connection = connections.requireFinalized(session);
            ReadyOaSessionLease lease = sessions.captureReady(connection);
            TrustedBusinessIdentity identity = identities.current(connection)
                    .orElseThrow(() -> new IllegalStateException("BUSINESS_SESSION_NOT_READY"));
            JsonNode input = params == null || params.isNull() ? JSON.createObjectNode() : params;
            rejectCredentials(input);
            return switch (method) {
                case "business/workbench/get" -> {
                    String month = optionalText(input, "month");
                    String day = optionalText(input, "day");
                    yield envelope(service.snapshot(lease, identity, month, day), identity, lease);
                }
                case "business/workbench/navigation/get" -> service.navigation(lease, identity);
                case "business/workbench/home-info/get" -> service.homeInfo(lease, identity);
                case "business/workbench/team-roles/list" -> service.teamRoles(lease, identity,
                        requiredText(input, "kind"), requiredText(input, "teamId"));
                case "business/workbench/page/get" -> {
                    BusinessWorkbenchDtos.PageResult page = service.page(lease, identity, pageRequest(input));
                    yield new BusinessWorkbenchDtos.PageEnvelope(identity.identityEpoch(), lease.generation(), page);
                }
                case "business/workbench/sort/update" -> requireSchedules().updateSort(lease, identity,
                        requiredText(input, "kind"), textList(input, "ids"), requiredLong(input, "expectedRevision"));
                case "business/schedule/month/get" -> requireSchedules().month(lease, identity, scheduleQuery(input, false));
                case "business/schedule/day/get" -> requireSchedules().day(lease, identity, scheduleQuery(input, true));
                case "business/schedule/completion/set" -> requireSchedules().setCompletion(lease, identity,
                        requiredText(input, "scheduleId"), requiredBoolean(input, "completed"));
                case "business/schedule/form/get" -> requireSchedules().form(lease, identity,
                        requiredText(input, "scope"), optionalText(input, "teamId"));
                case "business/schedule/relation-options/get" -> requireSchedules().relationOptions(lease, identity,
                        requiredText(input, "relationType"), optionalText(input, "keyword"),
                        optionalText(input, "teamId"), optionalText(input, "parentId"));
                case "business/schedule/service-projects/get" -> requireSchedules().serviceProjects(lease, identity,
                        requiredText(input, "recordId"), optionalText(input, "keyword"),
                        optionalText(input, "teamId"));
                case "business/schedule/create" -> requireSchedules().create(lease, identity, createRequest(input));
                default -> throw invalid();
            };
        } catch (JsonRpcException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid workbench parameters");
        } catch (RuntimeException exception) {
            BusinessRpcErrorMapper.MappedError mapped = BusinessRpcErrorMapper.map(exception);
            throw new JsonRpcException(code(mapped.rpcCode()), mapped.message(),
                    Map.of("businessCode", mapped.businessCode(), "retryable", mapped.retryable()));
        }
    }

    @Override public String method() { return "business/workbench/page/get"; }
    @Override public Object handle(JsonNode params, WebSocketSession session) { return handle(method(), params, session); }

    private static Object envelope(BusinessWorkbenchDtos.Snapshot snapshot, TrustedBusinessIdentity identity,
                                   ReadyOaSessionLease lease) {
        return new BusinessWorkbenchDtos.SnapshotEnvelope(identity.identityEpoch(), lease.generation(), snapshot);
    }

    private BusinessWorkbenchDtos.PageRequest pageRequest(JsonNode input) {
        try {
            return mapper.treeToValue(input, BusinessWorkbenchDtos.PageRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid();
        }
    }

    private static void rejectCredentials(JsonNode params) {
        for (String name : new String[]{"tenantId", "userId", "platformId", "accessToken", "refreshToken", "moduleId",
                "relatedIds", "dataRoleInfos", "dataRoleCodes"}) if (params.has(name)) throw invalid();
    }

    private static String requiredText(JsonNode params, String name) {
        String value = optionalText(params, name);
        if (value == null) throw invalid();
        return value;
    }

    private static String optionalText(JsonNode params, String name) {
        JsonNode value = params.get(name);
        return value == null || !value.isTextual() || value.textValue().isBlank() ? null : value.textValue();
    }

    private static boolean requiredBoolean(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static long longValue(JsonNode params, String name, long fallback) {
        JsonNode value = params.get(name);
        return value == null || value.isNull() ? fallback : value.isIntegralNumber() ? value.longValue() : Long.MIN_VALUE;
    }

    private static long requiredLong(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || !value.isIntegralNumber() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    private static List<String> textList(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || !value.isArray()) throw invalid();
        List<String> result = new java.util.ArrayList<>();
        value.forEach(item -> { if (!item.isTextual() || item.textValue().isBlank()) throw invalid(); result.add(item.textValue()); });
        return result;
    }

    private BusinessWorkbenchDtos.ScheduleQuery scheduleQuery(JsonNode input, boolean day) {
        String date = requiredText(input, "date");
        String scope = requiredText(input, "scope");
        JsonNode onlyMine = input.get("onlyMine");
        boolean mine = onlyMine == null || onlyMine.isNull() ? false : onlyMine.isBoolean() && onlyMine.booleanValue();
        if (onlyMine != null && !onlyMine.isBoolean()) throw invalid();
        return new BusinessWorkbenchDtos.ScheduleQuery(date, scope, optionalText(input, "teamId"), mine,
                optionalText(input, "typeId"));
    }

    private BusinessWorkbenchDtos.ScheduleCreateRequest createRequest(JsonNode input) {
        try {
            if (input.hasNonNull("attachmentBatchId")) {
                JsonNode revision = input.get("formRevision");
                if (revision == null || !revision.isIntegralNumber() || revision.longValue() < 0
                        || optionalText(input, "attachmentParentRelationType") == null) throw invalid();
            }
            return mapper.treeToValue(input, BusinessWorkbenchDtos.ScheduleCreateRequest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw invalid();
        }
    }

    private static JsonRpcException invalid() {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid workbench parameters");
    }

    private BusinessScheduleService requireSchedules() {
        if (schedules == null) throw new IllegalStateException("business schedule boundary unavailable");
        return schedules;
    }

    private static JsonRpcErrorCode code(int value) {
        for (JsonRpcErrorCode candidate : JsonRpcErrorCode.values()) if (candidate.code() == value) return candidate;
        return JsonRpcErrorCode.SERVER_ERROR;
    }
}
