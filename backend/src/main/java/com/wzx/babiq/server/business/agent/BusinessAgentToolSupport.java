package com.wzx.babiq.server.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.business.api.BusinessRpcErrorMapper;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.observability.TurnObservationContext;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Shared identity, READY-lease and bounded-response boundary for business Agent tools. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
final class BusinessAgentToolSupport {
    private static final int MAX_RESULT_BYTES = 60 * 1024;
    private static final String CONTEXT_MISSING = "BUSINESS_AGENT_CONTEXT_MISSING";
    private static final String SESSION_STALE = "BUSINESS_SESSION_STALE";
    private static final String INVALID_INPUT = "BUSINESS_INVALID_INPUT";
    private static final String RESULT_TOO_LARGE = "BUSINESS_RESULT_TOO_LARGE";
    private final BusinessIdentityScopeService scopes;
    private final BusinessOaSessionRegistry sessions;
    private final ObjectMapper json;

    BusinessAgentToolSupport(BusinessIdentityScopeService scopes,
                             BusinessOaSessionRegistry sessions,
                             ObjectMapper json) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.json = json == null ? new ObjectMapper() : json.copy();
    }

    String execute(ToolContext context, Function<Invocation, Object> operation) {
        try {
            Invocation invocation = resolve(context);
            return success(operation.apply(invocation));
        } catch (IllegalArgumentException invalid) {
            return failure(INVALID_INPUT, false);
        } catch (IllegalStateException state) {
            if (CONTEXT_MISSING.equals(state.getMessage())) {
                return failure(CONTEXT_MISSING, false);
            }
            if (SESSION_STALE.equals(state.getMessage())) {
                return failure(SESSION_STALE, false);
            }
            BusinessRpcErrorMapper.MappedError mapped = BusinessRpcErrorMapper.map(state);
            return failure(mapped.businessCode(), mapped.retryable());
        } catch (RuntimeException failure) {
            BusinessRpcErrorMapper.MappedError mapped = BusinessRpcErrorMapper.map(failure);
            return failure(mapped.businessCode(), mapped.retryable());
        }
    }

    private Invocation resolve(ToolContext context) {
        Map<String, Object> values = context == null ? null : context.getContext();
        Object explicit = values == null ? null : values.get(BusinessIdentityScope.METADATA_KEY);
        Object observed = values == null ? null : values.get(TurnObservationContext.METADATA_KEY);
        if (!(explicit instanceof BusinessIdentityScope scope) || !scope.scoped()
                || !(observed instanceof TurnObservationContext observation)
                || !scope.equals(observation.businessIdentityScope())) {
            throw new IllegalStateException(CONTEXT_MISSING);
        }
        BusinessIdentityScopeService.ActiveBusinessIdentity active = scopes.resolveActive(scope)
                .orElseThrow(() -> new IllegalStateException(SESSION_STALE));
        ReadyOaSessionLease lease = sessions.currentReady(active.connection(), active.identity())
                .orElseThrow(() -> new IllegalStateException(SESSION_STALE));
        return new Invocation(lease, active.identity());
    }

    private String success(Object data) {
        ObjectNode root = json.createObjectNode().put("ok", true);
        root.set("data", sanitize(json.valueToTree(data), null, 0));
        String rendered = write(root);
        return rendered.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES
                ? rendered
                : failure(RESULT_TOO_LARGE, false);
    }

    private String failure(String code, boolean retryable) {
        return write(json.createObjectNode()
                .put("ok", false)
                .put("code", code == null || code.isBlank() ? "PROTOCOL_ERROR" : code)
                .put("retryable", retryable));
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception impossible) {
            return "{\"ok\":false,\"code\":\"PROTOCOL_ERROR\",\"retryable\":false}";
        }
    }

    private JsonNode sanitize(JsonNode value, String key, int depth) {
        if (value == null || value.isNull() || depth > 12 || credentialKey(key)) {
            return json.nullNode();
        }
        if (value.isObject()) {
            ObjectNode safe = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = sanitize(field.getValue(), field.getKey(), depth + 1);
                if (!child.isNull()) {
                    safe.set(field.getKey(), child);
                }
            }
            return safe;
        }
        if (value.isArray()) {
            ArrayNode safe = json.createArrayNode();
            value.forEach(item -> {
                JsonNode child = sanitize(item, key, depth + 1);
                if (!child.isNull()) {
                    safe.add(child);
                }
            });
            return safe;
        }
        if (value.isTextual() && value.textValue().length() > 2_000) {
            return json.getNodeFactory().textNode(value.textValue().substring(0, 2_000));
        }
        return value.deepCopy();
    }

    private static boolean credentialKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.contains("authorization")
                || normalized.contains("clientsecret")
                || normalized.contains("apikey")
                || normalized.contains("credentialref")
                || normalized.contains("remoteurl");
    }

    record Invocation(ReadyOaSessionLease lease, TrustedBusinessIdentity identity) {
    }
}
