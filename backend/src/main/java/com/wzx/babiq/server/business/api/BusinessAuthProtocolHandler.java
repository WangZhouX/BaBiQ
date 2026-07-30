package com.wzx.babiq.server.business.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMultiMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.oa.session.BusinessOaAuthenticationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

/** JSON-RPC boundary for the server-owned business OA session lifecycle. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessAuthProtocolHandler implements JsonRpcMultiMethodHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_GET = "business/auth/session/get";
    private static final String SESSION_ATTACH = "business/auth/session/attach";
    private static final String SESSION_RESTORE = "business/auth/session/restore";
    private static final String TENANT_CANDIDATES = "business/auth/tenant-candidates";
    private static final String LOGIN = "business/auth/login";
    private static final String LOGOUT = "business/auth/logout";
    private final BusinessOaAuthenticationService authentication;
    private final BusinessDesktopConnectionResolver connections;

    public BusinessAuthProtocolHandler(BusinessOaAuthenticationService authentication,
                                       BusinessDesktopConnectionResolver connections) {
        this.authentication = authentication;
        this.connections = connections;
    }

    @Override
    public Set<String> methods() {
        return Set.of(SESSION_GET, SESSION_ATTACH, SESSION_RESTORE, TENANT_CANDIDATES, LOGIN, LOGOUT);
    }

    @Override
    public Object handle(String method, JsonNode params, WebSocketSession session) {
        TrustedDesktopConnection connection;
        try {
            connection = connections.requireFinalized(session);
            JsonNode input = params == null || params.isNull() ? JSON.nullNode() : params;
            return switch (method) {
                case SESSION_GET -> {
                    requireNoFields(input);
                    yield payload(authentication.session(connection));
                }
                case SESSION_ATTACH -> {
                    requireOnlyFields(input, Set.of("attachHandle"));
                    yield payload(authentication.attach(connection, requiredText(input, "attachHandle")));
                }
                case SESSION_RESTORE -> {
                    requireNoFields(input);
                    yield payload(authentication.restore(connection));
                }
                case TENANT_CANDIDATES -> {
                    rejectIdentityOverrides(input);
                    String account = requiredText(input, "account");
                    yield payload(authentication.findTenantCandidates(connection, account));
                }
                case LOGIN -> {
                    rejectIdentityOverrides(input);
                    String account = requiredText(input, "account");
                    String candidateId = requiredText(input, "candidateId");
                    String password = requiredText(input, "password");
                    char[] secret = password.toCharArray();
                    try {
                        yield payload(authentication.login(connection, account, candidateId, secret));
                    } finally {
                        java.util.Arrays.fill(secret, '\0');
                    }
                }
                case LOGOUT -> {
                    requireNoFields(input);
                    yield payload(authentication.logout(connection));
                }
                default -> throw invalid();
            };
        } catch (JsonRpcException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid business authentication parameters");
        } catch (RuntimeException exception) {
            BusinessRpcErrorMapper.MappedError mapped = BusinessRpcErrorMapper.map(exception);
            throw new JsonRpcException(code(mapped.rpcCode()), mapped.message(),
                    Map.of("businessCode", mapped.businessCode(), "retryable", mapped.retryable()));
        }
    }

    private static Object payload(Object value) { return value; }

    private static void rejectIdentityOverrides(JsonNode params) {
        for (String name : new String[]{"tenantId", "userId", "platformId", "accessToken", "refreshToken"}) {
            if (params.has(name)) throw invalid();
        }
    }

    private static String requiredText(JsonNode params, String name) {
        JsonNode value = params.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static void requireOnlyFields(JsonNode params, Set<String> allowed) {
        if (!params.isObject()) throw invalid();
        params.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid();
        });
    }

    private static void requireNoFields(JsonNode params) {
        if (params == null || params.isNull()) return;
        if (!params.isObject() || params.fieldNames().hasNext()) throw invalid();
    }

    private static JsonRpcException invalid() {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "Invalid business authentication parameters");
    }

    private static JsonRpcErrorCode code(int value) {
        for (JsonRpcErrorCode candidate : JsonRpcErrorCode.values()) if (candidate.code() == value) return candidate;
        return JsonRpcErrorCode.SERVER_ERROR;
    }
}
